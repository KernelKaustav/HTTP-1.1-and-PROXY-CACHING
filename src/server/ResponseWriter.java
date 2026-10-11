package server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
public final class ResponseWriter {
    private static final byte[] CRLF = {'\r', '\n'};
    private static final AtomicLong fixups = new AtomicLong();
    private ResponseWriter() {
    }
    public static HttpResponse error(int status, String message) {
        return new HttpResponse()
                .status(status)
                .reasonPhrase(reasonFor(status))
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(message + "\n");
    }
    public static byte[] serialize(HttpResponse response, HttpRequest request) throws IOException {
        boolean headOnly = request != null && request.method() == HttpMethod.HEAD;
        return serialize(response, headOnly);
    }
    public static byte[] serialize(HttpResponse response, boolean headOnly) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        response.writeTo(buf);
        byte[] wire = buf.toByteArray();

        int headEnd = findHeadEnd(wire);         
        if (headEnd < 0) {
            throw new IOException("response has no header terminator");
        }
        int bodyLength = wire.length - headEnd;   // bytes the handler actually supplied
        String head = new String(wire, 0, headEnd - 4, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n", -1);  // lines[0] = status line

        List<String> kept = new ArrayList<>();    // header lines except Content-Length
        int slot = -1;                            // where Content-Length used to sit
        int clLines = 0;                          // how many Content-Length lines were there
        long declared = -1;                       // the value they declared (-1 = invalid/conflict)
        boolean hasTransferEncoding = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            String name = (colon < 0 ? line : line.substring(0, colon)) .trim().toLowerCase(Locale.ROOT);
            if (name.equals("content-length")) {
                long value = parseLength(line.substring(colon + 1));
                if (clLines == 0) {
                    slot = kept.size();
                    declared = value;
                } else if (value != declared) {
                    declared = -1;                // two different values: can't trust either
                }
                clLines++;
                continue;                         // dropped here, re-added below if needed
            }
            if (name.equals("transfer-encoding")) {
                hasTransferEncoding = true;
            }
            kept.add(line);
        }
        int status = response.statusCode();
        boolean sendBody = !headOnly && !mustHaveNoBody(status);
        Long length;                              
        if (status < 200 || status == 204 || hasTransferEncoding) {
            length = null;
        } else if (status == 304) {
            length = (declared >= 0) ? Long.valueOf(declared) : null;
        } else if (headOnly) {
            if (bodyLength > 0) {
                length = (long) bodyLength;
            } else {
                length = (declared >= 0) ? declared : 0L;
            }
        } else {
            length = (long) bodyLength;           
        }
        boolean changed = (clLines == 0)
                ? (length != null)
                : (length == null || clLines > 1 || declared != length);
        if (changed) {
            fixups.incrementAndGet();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(wire.length + 32);
        writeLine(out, lines[0]);
        int insertAt = (slot >= 0) ? slot : kept.size();
        for (int i = 0; i <= kept.size(); i++) {
            if (i == insertAt && length != null) {
                writeLine(out, "Content-Length: " + length);
            }
            if (i < kept.size()) {
                writeLine(out, kept.get(i));
            }
        }
        out.write(CRLF);                          // blank line ends the head
        if (sendBody) {
            out.write(wire, headEnd, bodyLength);
        }
        return out.toByteArray();
    }
    public static void write(OutputStream out, HttpResponse response, boolean headOnly) throws IOException {
        out.write(serialize(response, headOnly));
        out.flush();
    }
    public static boolean mustHaveNoBody(int status) {
        return status < 200 || status == 204 || status == 304;
    }
    public static long fixups() {
        return fixups.get();
    }
    public static String reasonFor(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            case 505: return "HTTP Version Not Supported";
            default:  return "Unknown";
        }
    }
    private static void writeLine(ByteArrayOutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
    }
    private static int findHeadEnd(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }
    private static long parseLength(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 18) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(s);
    }
    public static void main(String[] args) throws IOException {
        int failed = 0;
        failed += check("normal body",
                serialize(new HttpResponse().status(200).body("hello"), false), "5", "hello");
        failed += check("utf-8 counts bytes",
                serialize(new HttpResponse().status(200).body("café"), false), "5",
                new String("café".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        failed += check("no body -> Content-Length: 0",
                serialize(new HttpResponse().status(404), false), "0", "");
        failed += check("stale Content-Length corrected",
                serialize(new HttpResponse().status(200).body("hi").header("Content-Length", "999"), false),
                "2", "hi");
        failed += check("duplicate Content-Length collapsed",
                serialize(new HttpResponse().status(200).body("abc").addHeader("Content-Length", "7"), false),
                "3", "abc");
        failed += check("HEAD keeps length, drops body",
                serialize(new HttpResponse().status(200).body("hello"), true), "5", "");
        failed += check("HEAD with declared length only",
                serialize(new HttpResponse().status(200).header("Content-Length", "1234"), true), "1234", "");
        failed += check("204 has no Content-Length/body",
                serialize(new HttpResponse().status(204).body("oops"), false), null, "");
        failed += check("304 keeps declared length, no body",
                serialize(new HttpResponse().status(304).header("Content-Length", "42"), false), "42", "");
        failed += check("chunked: no Content-Length",
                serialize(new HttpResponse().status(200).header("Transfer-Encoding", "chunked").body("x"), false),
                null, "x");
        byte[] tricky = {'a', '\r', '\n', '\r', '\n', 'b'};
        failed += check("body containing CRLFCRLF",
                serialize(new HttpResponse().status(200).body(tricky), false), "6", new String(tricky, StandardCharsets.ISO_8859_1));
        byte[] big = new byte[3 * 1024 * 1024];
        byte[] bigWire = serialize(new HttpResponse().status(200).body(big), false);
        boolean bigOk = bigWire.length == new String(bigWire, 0, headEndOf(bigWire), StandardCharsets.ISO_8859_1).length() + big.length
                && headerValue(bigWire, "content-length").equals(String.valueOf(big.length));
        System.out.println((bigOk ? "PASS" : "FAIL") + ": 3 MB body");
        failed += bigOk ? 0 : 1;
        byte[] err = serialize(error(500, "boom"), false);
        boolean errOk = headerValue(err, "content-length").equals("5") && bodyOf(err).equals("boom\n");
        System.out.println((errOk ? "PASS" : "FAIL") + ": error() response");
        failed += errOk ? 0 : 1;

        System.out.println(failed == 0 ? "\nAll ResponseWriter checks passed." : "\n" + failed + " check(s) FAILED.");
        System.out.println("Content-Length fixups applied during this run: " + fixups());
        if (failed != 0) {
            System.exit(1);
        }
    }
    private static int check(String name, byte[] wire, String expectedLength, String expectedBody) {
        String cl = headerValue(wire, "content-length");
        String body = bodyOf(wire);
        boolean ok = (expectedLength == null ? cl == null : expectedLength.equals(cl))
                && body.equals(expectedBody);
        System.out.println((ok ? "PASS" : "FAIL") + ": " + name
                + (ok ? "" : "   (got Content-Length=" + cl + ", body=\"" + body + "\")"));
        return ok ? 0 : 1;
    }
    private static int headEndOf(byte[] wire) {
        return findHeadEnd(wire);
    }
    private static String bodyOf(byte[] wire) {
        int end = findHeadEnd(wire);
        return new String(wire, end, wire.length - end, StandardCharsets.ISO_8859_1);
    }
    private static String headerValue(byte[] wire, String name) {
        String head = new String(wire, 0, findHeadEnd(wire), StandardCharsets.ISO_8859_1);
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
