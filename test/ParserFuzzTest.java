import server.HttpRequest;
import server.MalformedRequestException;
import server.RequestBuffer;
import server.RequestParser;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fuzz tests: feed deliberately fragmented, oversized and garbage requests to
 * every parsing path and prove the parser survives.
 *
 * Paths under test (they must agree on status codes):
 *   stream           RequestParser.parse(InputStream)            thread-pool model
 *   headBytes        RequestParser.parseFromHeadBytes(byte[])    event-loop model
 *   buffer(whole)    RequestBuffer, all bytes in one append()
 *   buffer(trickle)  RequestBuffer, one byte per append()
 *
 * "Survives" means: the only things a path may do are return an HttpRequest,
 * throw MalformedRequestException with status 400/414/431/505, or (stream only)
 * hit EOF. Any other exception, a hang, or unbounded buffering is a FAIL.
 *
 * Plain Java, no JUnit. Run:  java -cp out ParserFuzzTest   [seed]
 */
public class ParserFuzzTest {

    private static long seed = 20261015L;
    private static final int RANDOM_ITERATIONS = 20000;

    private static final String BASELINE =
            "GET /index.html HTTP/1.1\r\nHost: localhost\r\nUser-Agent: fuzz\r\nAccept: */*\r\n\r\n";

    private static int passed = 0;
    private static int failed = 0;
    private static int randomFailuresShown = 0;

    // ------------------------------------------------------------------
    // Outcome of feeding bytes to one path
    // ------------------------------------------------------------------

    private static final int OK = 0;
    private static final int INCOMPLETE = -1;
    private static final int UNEXPECTED = -2;

    private static final class Outcome {
        final int status;          // OK, INCOMPLETE, UNEXPECTED, or the Malformed status code
        final HttpRequest request; // when OK
        final String detail;       // when UNEXPECTED

        Outcome(int status, HttpRequest request, String detail) {
            this.status = status;
            this.request = request;
            this.detail = detail;
        }

        @Override
        public String toString() {
            switch (status) {
                case OK:         return "parsed OK (" + request + ")";
                case INCOMPLETE: return "incomplete/EOF";
                case UNEXPECTED: return "UNEXPECTED " + detail;
                default:         return "Malformed " + status;
            }
        }
    }

    private static Outcome runStream(byte[] bytes) {
        try {
            return new Outcome(OK, RequestParser.parse(new ByteArrayInputStream(bytes)), null);
        } catch (MalformedRequestException e) {
            return new Outcome(e.statusCode(), null, null);
        } catch (EOFException e) {
            return new Outcome(INCOMPLETE, null, null);
        } catch (Throwable t) {
            return new Outcome(UNEXPECTED, null, t.toString());
        }
    }

    private static Outcome runHead(byte[] bytes) {
        try {
            return new Outcome(OK, RequestParser.parseFromHeadBytes(bytes), null);
        } catch (MalformedRequestException e) {
            return new Outcome(e.statusCode(), null, null);
        } catch (Throwable t) {
            return new Outcome(UNEXPECTED, null, t.toString());
        }
    }

    /** Feeds bytes in chunks of the given size; returns the FIRST request extracted. */
    private static Outcome runBuffer(byte[] bytes, int chunk) {
        RequestBuffer rb = new RequestBuffer();
        try {
            for (int off = 0; off < bytes.length; off += chunk) {
                int n = Math.min(chunk, bytes.length - off);
                rb.append(bytes, off, n);
                if (rb.hasCompleteRequest()) {
                    return new Outcome(OK, rb.extractRequest(), null);
                }
            }
            return new Outcome(INCOMPLETE, null, null);
        } catch (MalformedRequestException e) {
            return new Outcome(e.statusCode(), null, null);
        } catch (Throwable t) {
            return new Outcome(UNEXPECTED, null, t.toString());
        }
    }

    /** Feeds bytes in random-sized chunks and drains every complete request (pipelining). */
    private static Outcome runBufferDrain(byte[] bytes, Random rnd, int maxChunk) {
        RequestBuffer rb = new RequestBuffer();
        try {
            int off = 0;
            int extracted = 0;
            while (off < bytes.length) {
                int n = Math.min(1 + rnd.nextInt(maxChunk), bytes.length - off);
                rb.append(bytes, off, n);
                off += n;
                int guard = 0;
                while (rb.hasCompleteRequest()) {
                    rb.extractRequest();
                    extracted++;
                    if (++guard > 10000) {
                        return new Outcome(UNEXPECTED, null, "drain loop did not terminate");
                    }
                }
            }
            return new Outcome(extracted > 0 ? OK : INCOMPLETE, null, null);
        } catch (MalformedRequestException e) {
            return new Outcome(e.statusCode(), null, null);
        } catch (Throwable t) {
            return new Outcome(UNEXPECTED, null, t.toString());
        }
    }

    private static boolean survived(Outcome o) {
        if (o.status == UNEXPECTED) {
            return false;
        }
        return o.status <= 0 || o.status == 400 || o.status == 414 || o.status == 431 || o.status == 505;
    }

    // ------------------------------------------------------------------
    // 1. Deterministic cases: every path must give the same answer
    // ------------------------------------------------------------------

    private static final class Case {
        final String name;
        final byte[] bytes;
        final int expectedStatus;   // 0 = must parse
        final String method, target, version;

        Case(String name, String raw, int expectedStatus) {
            this.name = name;
            this.bytes = latin1(raw);
            this.expectedStatus = expectedStatus;
            this.method = null;
            this.target = null;
            this.version = null;
        }

        Case(String name, String raw, String method, String target, String version) {
            this.name = name;
            this.bytes = latin1(raw);
            this.expectedStatus = 0;
            this.method = method;
            this.target = target;
            this.version = version;
        }

        boolean matches(HttpRequest r) {
            return r != null
                    && method.equals(r.rawMethodToken())
                    && target.equals(r.target())
                    && version.equals(r.httpVersion());
        }
    }

    private static List<Case> buildCases() {
        List<Case> c = new ArrayList<>();

        // --- must parse
        c.add(new Case("valid GET", "GET /index.html HTTP/1.1\r\nHost: x\r\n\r\n", "GET", "/index.html", "HTTP/1.1"));
        c.add(new Case("valid HTTP/1.0", "GET / HTTP/1.0\r\n\r\n", "GET", "/", "HTTP/1.0"));
        c.add(new Case("valid HEAD with query", "HEAD /a?b=c HTTP/1.1\r\nHost: x\r\n\r\n", "HEAD", "/a?b=c", "HTTP/1.1"));
        c.add(new Case("unknown method still parses", "FOO / HTTP/1.1\r\n\r\n", "FOO", "/", "HTTP/1.1"));

        // --- 505
        c.add(new Case("HTTP/2.0 -> 505", "GET / HTTP/2.0\r\n\r\n", 505));
        c.add(new Case("HTTP/9.9 -> 505", "GET / HTTP/9.9\r\n\r\n", 505));
        c.add(new Case("lowercase http/1.1 -> 505", "GET / http/1.1\r\n\r\n", 505));

        // --- 400: malformed request line
        c.add(new Case("missing version", "GET /\r\n\r\n", 400));
        c.add(new Case("extra token", "GET / HTTP/1.1 extra\r\n\r\n", 400));
        c.add(new Case("double space", "GET  / HTTP/1.1\r\n\r\n", 400));
        c.add(new Case("empty method", " / HTTP/1.1\r\n\r\n", 400));
        c.add(new Case("tabs instead of spaces", "GET\t/\tHTTP/1.1\r\n\r\n", 400));
        c.add(new Case("garbage line", "this is not http\r\n\r\n", 400));

        // --- 400: malformed headers
        c.add(new Case("header without colon", "GET / HTTP/1.1\r\nBadHeader\r\n\r\n", 400));
        c.add(new Case("header starting with colon", "GET / HTTP/1.1\r\n: nameless\r\n\r\n", 400));
        c.add(new Case("obsolete line folding", "GET / HTTP/1.1\r\nA: b\r\n folded: x\r\n\r\n", 400));

        // --- 400: control characters (CR/NUL smuggling)
        c.add(new Case("NUL in header value", "GET / HTTP/1.1\r\nX: a\u0000b\r\n\r\n", 400));
        c.add(new Case("bare CR inside request target", "GET /\r HTTP/1.1\r\n\r\n", 400));

        // --- 414 / 431: oversized
        c.add(new Case("9000-byte request line -> 414",
                "GET /" + rep('a', 9000) + " HTTP/1.1\r\n\r\n", 414));
        c.add(new Case("20000-byte single header -> 431",
                "GET / HTTP/1.1\r\nX: " + rep('b', 20000) + "\r\n\r\n", 431));
        c.add(new Case("101 headers -> 431", manyHeaders(101), 431));
        c.add(new Case("50 x 400-byte headers (20 KB total) -> 431", bigHeaderBlock(50, 400), 431));

        c.add(new Case("one leading blank line is ignored", "\r\nGET / HTTP/1.1\r\n\r\n", "GET", "/", "HTTP/1.1"));
        c.add(new Case("space before header colon", "GET / HTTP/1.1\r\nHost : x\r\n\r\n", 400));
        c.add(new Case("space inside header name", "GET / HTTP/1.1\r\nBad Name: x\r\n\r\n", 400));

        return c;
    }

    private static String manyHeaders(int n) {
        StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < n; i++) {
            sb.append("H").append(i).append(": v\r\n");
        }
        return sb.append("\r\n").toString();
    }

    private static String bigHeaderBlock(int count, int valueLen) {
        StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < count; i++) {
            sb.append("H").append(i).append(": ").append(rep('v', valueLen)).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    private static void runDeterministicCases() {
        System.out.println("== Deterministic cases (all four paths must agree) ==");
        String[] names = {"stream", "headBytes", "buffer(whole)", "buffer(trickle)"};
        for (Case c : buildCases()) {
            Outcome[] outs = {
                    runStream(c.bytes),
                    runHead(c.bytes),
                    runBuffer(c.bytes, Math.max(1, c.bytes.length)),
                    runBuffer(c.bytes, 1)
            };
            StringBuilder problems = new StringBuilder();
            for (int i = 0; i < outs.length; i++) {
                Outcome o = outs[i];
                boolean ok = (o.status == c.expectedStatus) && (c.expectedStatus != 0 || c.matches(o.request));
                if (!ok) {
                    problems.append("\n      ").append(names[i]).append(": expected ")
                            .append(c.expectedStatus == 0 ? "parse OK" : "status " + c.expectedStatus)
                            .append(", got ").append(o);
                }
            }
            report(c.name, problems.length() == 0, problems.toString());
        }
    }

    // ------------------------------------------------------------------
    // 2. Incomplete input and unbounded-buffering guards
    // ------------------------------------------------------------------

    private static void runIncompleteAndLimitTests() {
        System.out.println("\n== Incomplete input and buffering limits ==");

        report("empty stream -> EOF", runStream(new byte[0]).status == INCOMPLETE, "");

        byte[] noBlank = latin1("GET / HTTP/1.1\r\nHost: x\r\n");
        Outcome s = runStream(noBlank);
        report("stream: headers cut off before blank line -> 400", s.status == 400, " got " + s);
        Outcome b = runBuffer(noBlank, 1);
        report("buffer: headers cut off -> stays incomplete (no exception)", b.status == INCOMPLETE, " got " + b);

        // Garbage with no CRLF at all: must be rejected after a bounded number of bytes.
        {
            RequestBuffer rb = new RequestBuffer();
            byte[] chunk = latin1(rep('A', 1000));
            int fed = 0;
            int status = 0;
            try {
                while (fed < 5_000_000) {
                    rb.append(chunk, 0, chunk.length);
                    fed += chunk.length;
                    rb.hasCompleteRequest();
                }
            } catch (MalformedRequestException e) {
                status = e.statusCode();
            } catch (Throwable t) {
                status = UNEXPECTED;
            }
            report("endless line without CRLF -> 414 within 20 KB (fed " + fed + ")",
                    status == 414 && fed <= 20000, " status=" + status);
        }

        // Valid request line, then headers that never end.
        {
            RequestBuffer rb = new RequestBuffer();
            byte[] first = latin1("GET / HTTP/1.1\r\n");
            byte[] pad = latin1("X-Pad: aaaaaaaaaaaaaaaaaaaa\r\n");
            int fed = 0;
            int status = 0;
            try {
                rb.append(first, 0, first.length);
                fed += first.length;
                while (fed < 5_000_000) {
                    rb.append(pad, 0, pad.length);
                    fed += pad.length;
                    rb.hasCompleteRequest();
                }
            } catch (MalformedRequestException e) {
                status = e.statusCode();
            } catch (Throwable t) {
                status = UNEXPECTED;
            }
            report("endless headers -> 431 within 20 KB (fed " + fed + ")",
                    status == 431 && fed <= 20000, " status=" + status);
        }
    }

    // ------------------------------------------------------------------
    // 3. Fragmentation: any split of a valid request must still parse
    // ------------------------------------------------------------------

    private static void runFragmentationTests() {
        System.out.println("\n== Fragmentation ==");
        byte[] req = latin1(BASELINE);

        // Every possible 2-way split.
        boolean allSplitsOk = true;
        String firstBad = "";
        for (int k = 1; k < req.length; k++) {
            RequestBuffer rb = new RequestBuffer();
            try {
                rb.append(req, 0, k);
                if (rb.hasCompleteRequest()) {
                    allSplitsOk = false;
                    firstBad = "complete too early at split " + k;
                    break;
                }
                rb.append(req, k, req.length - k);
                if (!rb.hasCompleteRequest() || !"/index.html".equals(rb.extractRequest().target())) {
                    allSplitsOk = false;
                    firstBad = "lost request at split " + k;
                    break;
                }
            } catch (Throwable t) {
                allSplitsOk = false;
                firstBad = "split " + k + ": " + t;
                break;
            }
        }
        report("every 2-way split of a valid request parses once", allSplitsOk, " " + firstBad);

        // Random chunk sizes, many times.
        Random rnd = new Random(seed);
        boolean randomOk = true;
        String bad = "";
        for (int i = 0; i < 2000 && randomOk; i++) {
            Outcome o = runBufferDrain(req, rnd, 1 + rnd.nextInt(30));
            if (o.status != OK) {
                randomOk = false;
                bad = "iteration " + i + ": " + o;
            }
        }
        report("2000 random chunkings of a valid request parse", randomOk, " " + bad);
    }

    // ------------------------------------------------------------------
    // 4. Random garbage / mutation fuzzing
    // ------------------------------------------------------------------

    private static void runRandomFuzz() {
        System.out.println("\n== Random fuzzing (" + RANDOM_ITERATIONS + " inputs, seed " + seed + ") ==");
        Random rnd = new Random(seed);
        int bad = 0;
        for (int i = 0; i < RANDOM_ITERATIONS; i++) {
            byte[] input = randomInput(rnd);
            Outcome[] outs = {
                    runStream(input),
                    runHead(input),
                    runBufferDrain(input, rnd, Math.max(1, input.length)),
                    runBufferDrain(input, rnd, 1 + rnd.nextInt(64))
            };
            String[] names = {"stream", "headBytes", "buffer(whole)", "buffer(random chunks)"};
            for (int p = 0; p < outs.length; p++) {
                if (!survived(outs[p])) {
                    bad++;
                    if (randomFailuresShown < 10) {
                        randomFailuresShown++;
                        System.out.println("  FAIL: input #" + i + " (len " + input.length + ") on "
                                + names[p] + ": " + outs[p] + "\n        first bytes: " + hex(input, 64));
                    }
                }
            }
        }
        report(RANDOM_ITERATIONS + " random inputs x 4 paths: no crash, no unexpected exception",
                bad == 0, " " + bad + " bad outcome(s); rerun with the same seed to reproduce");
    }

    private static byte[] randomInput(Random rnd) {
        switch (rnd.nextInt(6)) {
            case 0: { // pure noise
                byte[] b = new byte[rnd.nextInt(2000)];
                rnd.nextBytes(b);
                return b;
            }
            case 1: { // HTTP-flavoured alphabet
                String alpha = "\r\n :\tGETHPOS/1.0x?%#-";
                int n = rnd.nextInt(500);
                StringBuilder sb = new StringBuilder(n);
                for (int i = 0; i < n; i++) {
                    sb.append(alpha.charAt(rnd.nextInt(alpha.length())));
                }
                return latin1(sb.toString());
            }
            case 2: // valid request with random damage
                return mutate(latin1(BASELINE), rnd);
            case 3: { // valid request cut off at a random point
                byte[] full = latin1(BASELINE);
                byte[] cut = new byte[rnd.nextInt(full.length + 1)];
                System.arraycopy(full, 0, cut, 0, cut.length);
                return cut;
            }
            case 4: { // oversized line or header
                int n = rnd.nextInt(30000);
                if (rnd.nextBoolean()) {
                    return latin1("GET /" + rep('a', n) + " HTTP/1.1\r\nHost: x\r\n\r\n");
                }
                return latin1("GET / HTTP/1.1\r\nX: " + rep('b', n) + "\r\n\r\n");
            }
            default: // lots of headers
                return latin1(manyHeaders(rnd.nextInt(300)));
        }
    }

    private static byte[] mutate(byte[] src, Random rnd) {
        List<Byte> bytes = new ArrayList<>();
        for (byte b : src) {
            bytes.add(b);
        }
        int edits = 1 + rnd.nextInt(5);
        for (int e = 0; e < edits; e++) {
            if (bytes.isEmpty()) {
                break;
            }
            int pos = rnd.nextInt(bytes.size());
            switch (rnd.nextInt(4)) {
                case 0: bytes.set(pos, (byte) rnd.nextInt(256)); break;       // flip
                case 1: bytes.add(pos, (byte) rnd.nextInt(256)); break;       // insert
                case 2: bytes.remove(pos); break;                             // delete
                default: {                                                    // duplicate a slice
                    int len = Math.min(1 + rnd.nextInt(20), bytes.size() - pos);
                    List<Byte> slice = new ArrayList<>(bytes.subList(pos, pos + len));
                    bytes.addAll(pos, slice);
                }
            }
        }
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers and main
    // ------------------------------------------------------------------

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) {
            sb.append(String.format("%02x ", b[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  PASS: " + name);
        } else {
            failed++;
            System.out.println("  FAIL: " + name + detail);
        }
    }

    private static void runAll() {
        runDeterministicCases();
        runIncompleteAndLimitTests();
        runFragmentationTests();
        runRandomFuzz();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            seed = Long.parseLong(args[0]);
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runAll();
            }
        }, "fuzz-worker");
        worker.setDaemon(true);
        worker.start();
        worker.join(60_000);
        if (worker.isAlive()) {
            System.out.println("\nFAIL: fuzzing did not finish in 60 s - the parser hung on some input");
            System.exit(2);
        }
        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed != 0) {
            System.exit(1);
        }
    }
}