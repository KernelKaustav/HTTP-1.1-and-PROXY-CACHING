import server.EventLoopServer;
import server.HttpResponse;
import server.MalformedRequestException;
import server.StaticFileHandler;
import server.StatusCode;
import server.ThreadPoolServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * Tests for StatusCode + HttpResponse (7 Oct work item), in two layers:
 *
 *  1. Unit tests: reason phrases, validation, wire format, error factories.
 *  2. End-to-end: starts BOTH concurrency models on free ports with the real
 *     StaticFileHandler and checks 200/400/404/405/414/431/505 over raw sockets,
 *     so any difference between the thread-pool and event-loop paths shows up.
 *
 * Run from the repo root:
 *   javac -d out $(find src test -name "*.java")
 *   java -cp out StatusCodeTest
 */
public class StatusCodeTest {

    private static int failures = 0;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        unitTests();
        endToEnd();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED.");
            System.exit(1);
        }
        System.out.println("\nAll status code tests passed.");
    }

    // ------------------------------------------------------------------
    // 1. Unit tests
    // ------------------------------------------------------------------

    private static String wire(HttpResponse r) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static boolean throwsIAE(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void unitTests() throws Exception {
        // reason phrases for the required codes
        check("400 phrase", StatusCode.reasonFor(400).equals("Bad Request"));
        check("404 phrase", StatusCode.reasonFor(404).equals("Not Found"));
        check("405 phrase", StatusCode.reasonFor(405).equals("Method Not Allowed"));
        check("414 phrase", StatusCode.reasonFor(414).equals("URI Too Long"));
        check("431 phrase", StatusCode.reasonFor(431).equals("Request Header Fields Too Large"));
        check("500/505 phrases", StatusCode.reasonFor(500).equals("Internal Server Error")
                && StatusCode.reasonFor(505).equals("HTTP Version Not Supported"));
        check("unknown code -> Unknown", StatusCode.reasonFor(299).equals("Unknown"));
        check("fromCode round-trips every constant", roundTrips());
        check("codes are unique", uniqueCodes());

        // every code the rest of the repo emits has a phrase (no silent "Unknown")
        int[] usedElsewhere = {200, 204, 304, 400, 403, 404, 405, 411, 413, 414, 431, 500, 501, 502, 504, 505};
        boolean all = true;
        for (int c : usedElsewhere) {
            all &= StatusCode.fromCode(c) != null;
        }
        check("all codes used by other modules are defined", all);

        check("body rules: 204/304 no body, 200/404 body",
                !StatusCode.NO_CONTENT.allowsBody() && !StatusCode.NOT_MODIFIED.allowsBody()
                        && StatusCode.OK.allowsBody() && StatusCode.NOT_FOUND.allowsBody());
        check("connection-closing errors", StatusCode.BAD_REQUEST.closesConnection()
                && StatusCode.URI_TOO_LONG.closesConnection()
                && StatusCode.HEADER_FIELDS_TOO_LARGE.closesConnection()
                && !StatusCode.NOT_FOUND.closesConnection()
                && !StatusCode.METHOD_NOT_ALLOWED.closesConnection());

        // HttpResponse: validation
        check("status(99) rejected", throwsIAE(() -> new HttpResponse().status(99)));
        check("status(600) rejected", throwsIAE(() -> new HttpResponse().status(600)));
        check("status(int) and status(StatusCode) agree",
                new HttpResponse().status(404).reasonPhrase().equals(
                        new HttpResponse().status(StatusCode.NOT_FOUND).reasonPhrase()));
        check("header value with CRLF rejected (response splitting)",
                throwsIAE(() -> new HttpResponse().header("X-Test", "a\r\nSet-Cookie: evil=1")));
        check("header name with ':' or space rejected",
                throwsIAE(() -> new HttpResponse().header("Bad Name", "v"))
                        && throwsIAE(() -> new HttpResponse().header("Bad:Name", "v")));
        check("empty header name rejected", throwsIAE(() -> new HttpResponse().header("", "v")));
        check("reason phrase with LF rejected", throwsIAE(() -> new HttpResponse().reasonPhrase("a\nb")));

        // HttpResponse: existing behaviour must be unchanged
        String ok = wire(new HttpResponse().status(200).header("Content-Type", "text/plain").body("hi"));
        check("status line", ok.startsWith("HTTP/1.1 200 OK\r\n"));
        check("Content-Length auto-set", ok.contains("Content-Length: 2\r\n"));
        check("header casing preserved on the wire", ok.contains("Content-Type: text/plain\r\n"));
        check("blank line then body", ok.endsWith("\r\n\r\nhi"));
        check("UTF-8 body length counts bytes",
                wire(new HttpResponse().body("café")).contains("Content-Length: 5\r\n"));
        check("null body is empty, not NPE", wire(new HttpResponse().body((byte[]) null)).contains("Content-Length: 0\r\n"));
        check("header() replaces, addHeader() appends",
                new HttpResponse().header("A", "1").header("a", "2").headerValues("A").size() == 1
                        && new HttpResponse().addHeader("Set-Cookie", "a=1").addHeader("set-cookie", "b=2")
                                .headerValues("Set-Cookie").size() == 2);
        check("case-insensitive getter", "2".equals(new HttpResponse().header("Content-Length", "2").header("content-length")));
        check("custom reason phrase honoured and resettable",
                wire(new HttpResponse().status(200).reasonPhrase("Fine")).startsWith("HTTP/1.1 200 Fine\r\n")
                        && wire(new HttpResponse().status(200).reasonPhrase("Fine").reasonPhrase(null))
                                .startsWith("HTTP/1.1 200 OK\r\n"));

        // Error factories (400 / 404 / 405 / 414 / 431)
        HttpResponse bad = HttpResponse.badRequest("bad line");
        check("400 factory", bad.statusCode() == 400 && "close".equals(bad.header("Connection"))
                && wire(bad).endsWith("bad line\n") && "9".equals(bad.header("Content-Length")));
        HttpResponse nf = HttpResponse.notFound();
        check("404 factory keeps connection open", nf.statusCode() == 404 && nf.header("Connection") == null);
        HttpResponse na = HttpResponse.methodNotAllowed("GET, HEAD");
        check("405 factory has Allow header", na.statusCode() == 405 && "GET, HEAD".equals(na.header("Allow")));
        check("414 factory", HttpResponse.uriTooLong().statusCode() == 414
                && "close".equals(HttpResponse.uriTooLong().header("Connection")));
        check("431 factory", HttpResponse.headersTooLarge().statusCode() == 431
                && "close".equals(HttpResponse.headersTooLarge().header("Connection")));
        check("forMalformed maps parser status 414",
                HttpResponse.forMalformed(new MalformedRequestException("long", 414)).statusCode() == 414);
        check("forMalformed maps parser status 505",
                HttpResponse.forMalformed(new MalformedRequestException("v", 505)).statusCode() == 505);
        check("forMalformed falls back to 400 for a code we don't define",
                HttpResponse.forMalformed(new MalformedRequestException("x", 418)).statusCode() == 400);
        check("error() on 304 has no body",
                HttpResponse.error(StatusCode.NOT_MODIFIED, "x").header("Content-Length") == null);
    }

    private static boolean roundTrips() {
        for (StatusCode s : StatusCode.values()) {
            if (StatusCode.fromCode(s.code()) != s) {
                return false;
            }
        }
        return true;
    }

    private static boolean uniqueCodes() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (StatusCode s : StatusCode.values()) {
            if (!seen.add(s.code())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 2. End-to-end over real sockets, both concurrency models
    // ------------------------------------------------------------------

    private static void endToEnd() throws Exception {
        Path root = Files.createTempDirectory("status-test-");
        try {
            Files.write(root.resolve("index.html"), "<h1>hi</h1>".getBytes(StandardCharsets.UTF_8));
            StaticFileHandler handler = new StaticFileHandler(root.toString());

            int tpPort = freePort();
            ThreadPoolServer tp = new ThreadPoolServer(tpPort, 4, handler);
            Thread tpThread = new Thread(() -> {
                try {
                    tp.start();
                } catch (IOException ignored) {
                    // closed on stop()
                }
            });
            tpThread.setDaemon(true);
            tpThread.start();

            int elPort = freePort();
            EventLoopServer el = new EventLoopServer(elPort, handler);
            Thread elThread = new Thread(() -> {
                try {
                    el.start();
                } catch (IOException ignored) {
                    // closed on stop()
                }
            });
            elThread.setDaemon(true);
            elThread.start();
            Thread.sleep(600);

            runModel("thread-pool", tpPort);
            runModel("event-loop", elPort);

            tp.stop();
            el.stop();
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void runModel(String model, int port) throws Exception {
        String t = "[" + model + "] ";
        check(t + "GET / -> 200", status(port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n") == 200);
        check(t + "HEAD / -> 200, no body", headHasNoBody(port));
        check(t + "GET /missing -> 404", status(port, "GET /missing.html HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n") == 404);
        check(t + "POST / -> 405", status(port, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") == 405);
        check(t + "garbage request line -> 400", status(port, "THIS IS NOT HTTP AT ALL\r\n\r\n") == 400);
        check(t + "malformed header (no colon) -> 400", status(port, "GET / HTTP/1.1\r\nbroken header line\r\n\r\n") == 400);
        StringBuilder longUri = new StringBuilder("GET /");
        for (int i = 0; i < 9000; i++) {
            longUri.append('a');
        }
        longUri.append(" HTTP/1.1\r\nHost: x\r\n\r\n");
        check(t + "9000-byte URI -> 414", status(port, longUri.toString()) == 414);
        StringBuilder bigHeader = new StringBuilder("GET / HTTP/1.1\r\nHost: x\r\nX-Big: ");
        for (int i = 0; i < 20000; i++) {
            bigHeader.append('b');
        }
        bigHeader.append("\r\n\r\n");
        check(t + "20000-byte header -> 431", status(port, bigHeader.toString()) == 431);
        StringBuilder manyHeaders = new StringBuilder("GET / HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 150; i++) {
            manyHeaders.append("X-H").append(i).append(": v\r\n");
        }
        manyHeaders.append("\r\n");
        check(t + "150 headers -> 431", status(port, manyHeaders.toString()) == 431);
        check(t + "HTTP/2.0 -> 505", status(port, "GET / HTTP/2.0\r\nHost: x\r\n\r\n") == 505);
        check(t + "server still healthy after the abuse",
                status(port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n") == 200);
    }

    private static boolean headHasNoBody(int port) throws IOException {
        String raw = exchange(port, "HEAD / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n", true);
        int end = raw.indexOf("\r\n\r\n");
        return raw.startsWith("HTTP/1.1 200") && end >= 0
                && raw.substring(end + 4).isEmpty()
                && raw.toLowerCase(Locale.ROOT).contains("content-length: ");
    }

    private static int status(int port, String request) throws IOException {
        String raw = exchange(port, request, false);
        if (!raw.startsWith("HTTP/1.")) {
            return -1;
        }
        return Integer.parseInt(raw.substring(9, 12));
    }

    /** Sends raw bytes and reads until the head (and Content-Length body) has arrived or the peer closes. */
    private static String exchange(int port, String request, boolean head) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            try {
                s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
            } catch (IOException e) {
                // server may already have answered and closed (e.g. 431 on an oversized header)
            }
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int headEnd = -1;
            int bodyWanted = -1;
            try {
                while (true) {
                    int n = in.read(chunk);
                    if (n == -1) {
                        break;
                    }
                    buf.write(chunk, 0, n);
                    String sofar = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                    if (headEnd < 0) {
                        headEnd = sofar.indexOf("\r\n\r\n");
                        if (headEnd >= 0) {
                            bodyWanted = head ? 0 : contentLength(sofar.substring(0, headEnd));
                        }
                    }
                    if (headEnd >= 0 && bodyWanted >= 0 && sofar.length() >= headEnd + 4 + bodyWanted) {
                        break;
                    }
                }
            } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
                // fall through with whatever arrived (connection reset after an error response)
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
