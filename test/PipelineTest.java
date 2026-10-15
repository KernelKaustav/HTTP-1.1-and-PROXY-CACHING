import server.HttpMethod;
import server.HttpRequest;
import server.HttpResponse;
import server.MalformedRequestException;
import server.RequestBuffer;
import server.RequestDispatcher;
import server.RequestHandler;
import server.ResponseWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pipelining tests: several requests arriving back to back (in one read, or
 * smeared across arbitrary fragments) must each be extracted exactly once, in
 * order, and each must get its own correct response.
 *
 * Plain Java, no JUnit. Run:  java -cp out PipelineTest
 */
public class PipelineTest {

    private static int passed = 0;
    private static int failed = 0;

    private interface Test {
        void run() throws Exception;
    }

    private static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS: " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  FAIL: " + name + "\n        " + e);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String req(String method, String target) {
        return method + " " + target + " HTTP/1.1\r\nHost: t\r\n\r\n";
    }

    private static void feed(RequestBuffer rb, String s) {
        byte[] b = bytes(s);
        rb.append(b, 0, b.length);
    }

    private static List<HttpRequest> drain(RequestBuffer rb) throws MalformedRequestException {
        List<HttpRequest> out = new ArrayList<>();
        while (rb.hasCompleteRequest()) {
            out.add(rb.extractRequest());
        }
        return out;
    }

    /** A response parsed back out of wire bytes. */
    private static final class Wire {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body;
    }

    private static Wire parseWire(byte[] wire) {
        String s = new String(wire, StandardCharsets.ISO_8859_1);
        int end = s.indexOf("\r\n\r\n");
        check(end >= 0, "response has no header terminator");
        String[] lines = s.substring(0, end).split("\r\n");
        Wire w = new Wire();
        w.status = Integer.parseInt(lines[0].split(" ")[1]);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            w.headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
        }
        w.body = s.substring(end + 4);
        return w;
    }

    // ------------------------------------------------------------------
    // RequestBuffer pipelining
    // ------------------------------------------------------------------

    private static void twoInOneRead() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        feed(rb, req("GET", "/a") + req("GET", "/b"));
        List<HttpRequest> got = drain(rb);
        check(got.size() == 2, "expected 2 requests, got " + got.size());
        check("/a".equals(got.get(0).target()) && "/b".equals(got.get(1).target()), "wrong order");
        check(!rb.hasCompleteRequest(), "buffer should be empty afterwards");
    }

    private static void mixedMethods() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        feed(rb, req("GET", "/1") + req("HEAD", "/2") + req("GET", "/3"));
        List<HttpRequest> got = drain(rb);
        check(got.size() == 3, "expected 3 requests, got " + got.size());
        check(got.get(0).method() == HttpMethod.GET, "request 1 should be GET");
        check(got.get(1).method() == HttpMethod.HEAD, "request 2 should be HEAD");
        check(got.get(2).method() == HttpMethod.GET, "request 3 should be GET");
        check("/3".equals(got.get(2).target()), "request 3 target wrong");
    }

    private static void partialTrailingRequestStaysBuffered() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        String second = req("GET", "/second");
        feed(rb, req("GET", "/first") + second.substring(0, 10));
        List<HttpRequest> got = drain(rb);
        check(got.size() == 1 && "/first".equals(got.get(0).target()), "first request should be extracted alone");
        check(!rb.hasCompleteRequest(), "half a request must not count as complete");
        feed(rb, second.substring(10));
        got = drain(rb);
        check(got.size() == 1 && "/second".equals(got.get(0).target()), "second request lost after completion");
    }

    private static void terminatorSplitAtEveryPosition() throws Exception {
        String full = req("GET", "/split");
        for (int k = 1; k < full.length(); k++) {
            RequestBuffer rb = new RequestBuffer();
            feed(rb, full.substring(0, k));
            check(!rb.hasCompleteRequest(), "complete too early at split " + k);
            feed(rb, full.substring(k));
            List<HttpRequest> got = drain(rb);
            check(got.size() == 1 && "/split".equals(got.get(0).target()), "request lost at split " + k);
        }
    }

    private static void twoHundredRequestsRandomFragments() throws Exception {
        Random rnd = new Random(42);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String method = (i % 5 == 0) ? "HEAD" : "GET";
            all.append(method).append(" /r").append(i).append(" HTTP/1.1\r\nHost: t\r\nX-N: ").append(i).append("\r\n\r\n");
        }
        byte[] data = bytes(all.toString());
        RequestBuffer rb = new RequestBuffer();
        List<HttpRequest> got = new ArrayList<>();
        int off = 0;
        while (off < data.length) {
            int n = Math.min(1 + rnd.nextInt(37), data.length - off);
            rb.append(data, off, n);
            off += n;
            got.addAll(drain(rb));
        }
        check(got.size() == 200, "expected 200 requests, got " + got.size());
        for (int i = 0; i < 200; i++) {
            check(("/r" + i).equals(got.get(i).target()), "request " + i + " out of order or duplicated: " + got.get(i).target());
            check(String.valueOf(i).equals(got.get(i).header("x-n")), "request " + i + " header mismatch");
        }
    }

    private static void thousandRequestBurst() throws Exception {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            all.append(req("GET", "/burst" + i));
        }
        RequestBuffer rb = new RequestBuffer();
        feed(rb, all.toString());
        List<HttpRequest> got = drain(rb);
        check(got.size() == 1000, "expected 1000 requests, got " + got.size());
        check("/burst999".equals(got.get(999).target()), "last request wrong");
    }

    private static void keepAliveFlagsPerRequest() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        feed(rb, req("GET", "/keep")
                + "GET /close HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
        List<HttpRequest> got = drain(rb);
        check(got.size() == 2, "expected 2 requests");
        check(got.get(0).isKeepAlive(), "first request should be keep-alive");
        check(!got.get(1).isKeepAlive(), "second request asked to close");
    }

    private static void extractWithNothingBufferedThrows() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        try {
            rb.extractRequest();
        } catch (IllegalStateException expected) {
            return;
        }
        check(false, "extractRequest() on an empty buffer should throw IllegalStateException");
    }

    private static void malformedRequestMidPipeline() throws Exception {
        RequestBuffer rb = new RequestBuffer();
        feed(rb, req("GET", "/good") + "GARBAGE\r\n\r\n" + req("GET", "/never-reached"));
        check(rb.hasCompleteRequest(), "first request should be complete");
        check("/good".equals(rb.extractRequest().target()), "first request should parse");
        check(rb.hasCompleteRequest(), "garbage head should count as a complete head");
        try {
            rb.extractRequest();
        } catch (MalformedRequestException e) {
            check(e.statusCode() == 400, "garbage should be 400, got " + e.statusCode());
            return; // a real server answers 400 and closes the connection here
        }
        check(false, "garbage request should have thrown MalformedRequestException");
    }

    // ------------------------------------------------------------------
    // End to end: bytes -> RequestBuffer -> RequestDispatcher -> ResponseWriter
    // ------------------------------------------------------------------

    private static void endToEndPipeline() throws Exception {
        RequestHandler stub = new RequestHandler() {
            @Override
            public HttpResponse handle(HttpRequest r) {
                if ("/boom".equals(r.target())) {
                    throw new IllegalStateException("simulated handler bug");
                }
                return new HttpResponse().status(200)
                        .header("Content-Type", "text/plain")
                        .body(r.target());
            }
        };
        RequestDispatcher dispatcher = new RequestDispatcher(stub);

        RequestBuffer rb = new RequestBuffer();
        feed(rb, req("GET", "/a")
                + req("HEAD", "/bb")
                + "POST /c HTTP/1.1\r\nHost: t\r\nContent-Length: 0\r\n\r\n"
                + req("GET", "/boom")
                + "GET /late HTTP/2.0\r\n\r\n");

        // 1. GET -> 200 with body
        HttpRequest r1 = rb.extractRequest();
        Wire w1 = parseWire(ResponseWriter.serialize(dispatcher.handle(r1), r1));
        check(w1.status == 200 && "/a".equals(w1.body) && "2".equals(w1.headers.get("content-length")),
                "GET /a: status=" + w1.status + " body=" + w1.body);

        // 2. HEAD -> 200, Content-Length of the would-be body, no body bytes
        HttpRequest r2 = rb.extractRequest();
        Wire w2 = parseWire(ResponseWriter.serialize(dispatcher.handle(r2), r2));
        check(w2.status == 200 && "3".equals(w2.headers.get("content-length")) && w2.body.isEmpty(),
                "HEAD /bb: status=" + w2.status + " cl=" + w2.headers.get("content-length") + " body=[" + w2.body + "]");

        // 3. POST -> 405 with Allow
        HttpRequest r3 = rb.extractRequest();
        Wire w3 = parseWire(ResponseWriter.serialize(dispatcher.handle(r3), r3));
        check(w3.status == 405 && "GET, HEAD".equals(w3.headers.get("allow")),
                "POST /c: status=" + w3.status + " allow=" + w3.headers.get("allow"));

        // 4. handler throws -> 500, connection marked for close
        HttpRequest r4 = rb.extractRequest();
        Wire w4 = parseWire(ResponseWriter.serialize(dispatcher.handle(r4), r4));
        check(w4.status == 500 && "close".equals(w4.headers.get("connection")),
                "GET /boom: status=" + w4.status + " connection=" + w4.headers.get("connection"));

        // 5. unsupported version is rejected by the parser -> 505 via forMalformed.
        //    (In a live server the connection is already closed after the 500 above;
        //     here each response is checked on its own.)
        try {
            rb.extractRequest();
            check(false, "HTTP/2.0 request should have been rejected");
        } catch (MalformedRequestException e) {
            Wire w5 = parseWire(ResponseWriter.serialize(HttpResponse.forMalformed(e), false));
            check(w5.status == 505, "HTTP/2.0: status=" + w5.status);
        }
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("== RequestBuffer pipelining ==");
        test("two requests in one read", new Test() { public void run() throws Exception { twoInOneRead(); } });
        test("GET/HEAD/GET in one read keep order", new Test() { public void run() throws Exception { mixedMethods(); } });
        test("partial trailing request stays buffered", new Test() { public void run() throws Exception { partialTrailingRequestStaysBuffered(); } });
        test("terminator split at every position", new Test() { public void run() throws Exception { terminatorSplitAtEveryPosition(); } });
        test("200 pipelined requests, random fragments", new Test() { public void run() throws Exception { twoHundredRequestsRandomFragments(); } });
        test("1000-request burst in a single read", new Test() { public void run() throws Exception { thousandRequestBurst(); } });
        test("keep-alive flag is per request", new Test() { public void run() throws Exception { keepAliveFlagsPerRequest(); } });
        test("extractRequest on empty buffer throws", new Test() { public void run() throws Exception { extractWithNothingBufferedThrows(); } });
        test("malformed request mid-pipeline -> 400", new Test() { public void run() throws Exception { malformedRequestMidPipeline(); } });

        System.out.println("\n== End to end: buffer -> dispatcher -> serializer ==");
        test("GET, HEAD, 405, 500, 505 in one pipelined read", new Test() { public void run() throws Exception { endToEndPipeline(); } });

        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed != 0) {
            System.exit(1);
        }
    }
}