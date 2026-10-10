import server.ChunkedWriter;
import server.HttpMethod;
import server.HttpRequest;
import server.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
public class ChunkedEncodingTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        formatOfOneChunk();
        emptyCases();
        splittingIntoChunks();
        roundTripsAtManySizes();
        slowStreamStillFillsChunks();
        headIsChunkedAndHasNoContentLength();
        fullResponseDecodesToOriginalBody();
        headRequestHasNoBody();
        http10FallsBackToContentLength();
        noBodyStatusesAreNotChunked();
        streamingWriter();

        if (failures == 0) {
            System.out.println("\nAll chunked encoding tests passed.");
        } else {
            System.out.println("\n" + failures + " test(s) FAILED.");
            System.exit(1);
        }
    }
    private static void formatOfOneChunk() {
        byte[] data = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII); 
        String chunk = ascii(ChunkedWriter.encodeChunk(data, 0, data.length));
        check("chunk size is lowercase hex", chunk.equals("1a\r\nabcdefghijklmnopqrstuvwxyz\r\n"));
        check("encodeChunk honours offset/length",
                ascii(ChunkedWriter.encodeChunk(data, 2, 3)).equals("3\r\ncde\r\n"));
    }
    private static void emptyCases() {
        check("empty write produces no bytes (must not look like end-of-body)",
                ChunkedWriter.encodeChunk(new byte[5], 2, 0).length == 0);
        check("empty body is just the last chunk",
                ascii(ChunkedWriter.encodeBody(new byte[0], 10)).equals("0\r\n\r\n"));
        check("lastChunk() is 0 CRLF CRLF", ascii(ChunkedWriter.lastChunk()).equals("0\r\n\r\n"));
        boolean threw = false;
        try {
            ChunkedWriter.encodeChunk(new byte[3], 2, 5);
        } catch (IndexOutOfBoundsException e) {
            threw = true;
        }
        check("out-of-range offset/length rejected", threw);
    }
    private static void splittingIntoChunks() {
        check("10 bytes, chunk size 4 -> 4,4,2,last",
                ascii(ChunkedWriter.encodeBody("abcdefghij".getBytes(StandardCharsets.US_ASCII), 4))
                        .equals("4\r\nabcd\r\n4\r\nefgh\r\n2\r\nij\r\n0\r\n\r\n"));
        check("exact multiple: no stray empty chunk before the last",
                ascii(ChunkedWriter.encodeBody("abcdefgh".getBytes(StandardCharsets.US_ASCII), 4))
                        .equals("4\r\nabcd\r\n4\r\nefgh\r\n0\r\n\r\n"));
        boolean threw = false;
        try {
            ChunkedWriter.encodeBody(new byte[1], 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("chunk size 0 rejected", threw);
    }
    private static void roundTripsAtManySizes() throws IOException {
        Random rnd = new Random(42);
        byte[] body = new byte[100_000];
        rnd.nextBytes(body);
        byte[] evil = "\r\n0\r\n\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(evil, 0, body, 500, evil.length);

        int[] sizes = {1, 7, 255, 256, 4096, 8192, 99_999, 100_000, 200_000};
        boolean ok = true;
        for (int size : sizes) {
            byte[] decoded = decode(ChunkedWriter.encodeBody(body, size), 0);
            ok &= Arrays.equals(decoded, body);
        }
        check("binary body (with CRLF / fake terminator inside) round-trips at all chunk sizes", ok);

        byte[] mb = new byte[1 << 20];
        rnd.nextBytes(mb);
        check("1 MB body round-trips", Arrays.equals(decode(ChunkedWriter.encodeBody(mb, 8192), 0), mb));
    }
    private static void slowStreamStillFillsChunks() throws IOException {
        final byte[] data = new byte[50];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + i % 26);
        }
        InputStream oneByteAtATime = new InputStream() {
            int pos = 0;

            @Override
            public int read() {
                return pos < data.length ? (data[pos++] & 0xFF) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= data.length) {
                    return -1;
                }
                b[off] = data[pos++];
                return 1;
            }
        };
        byte[] wire = ChunkedWriter.encodeStream(oneByteAtATime, 20);
        check("stream arriving 1 byte at a time still decodes correctly", Arrays.equals(decode(wire, 0), data));
        check("...and is sent as full chunks (20,20,10), not 50 tiny ones",
                ascii(wire).startsWith("14\r\n") && ascii(wire).endsWith("a\r\n" + ascii(Arrays.copyOfRange(data, 40, 50)) + "\r\n0\r\n\r\n"));
        check("empty stream -> just the last chunk",
                ascii(ChunkedWriter.encodeStream(new ByteArrayInputStream(new byte[0]), 8)).equals("0\r\n\r\n"));
    }
    private static void headIsChunkedAndHasNoContentLength() throws IOException {
        HttpResponse r = new HttpResponse().status(200).header("Content-Type", "text/plain").body("ignored");
        String head = ascii(ChunkedWriter.head(r));
        check("head ends exactly at the blank line", head.endsWith("\r\n\r\n") && head.indexOf("\r\n\r\n") == head.length() - 4);
        check("head has Transfer-Encoding: chunked", head.toLowerCase().contains("transfer-encoding: chunked\r\n"));
        check("head has no Content-Length (even though the template had one)", !head.toLowerCase().contains("content-length"));
        check("head keeps other headers", head.toLowerCase().contains("content-type: text/plain\r\n"));
    }
    private static void fullResponseDecodesToOriginalBody() throws IOException {
        byte[] body = "Hello chunked world, this body has no known length.".getBytes(StandardCharsets.US_ASCII);
        byte[] wire = ChunkedWriter.serialize(request(HttpMethod.GET, "HTTP/1.1"),
                new HttpResponse().status(200), body, 16);
        int headEnd = headEnd(wire);
        check("full response: decodes back to original body", Arrays.equals(decode(wire, headEnd), body));

        byte[] viaStream = ChunkedWriter.serialize(request(HttpMethod.GET, "HTTP/1.1"),
                new HttpResponse().status(200), new ByteArrayInputStream(body), 16);
        check("stream variant gives identical bytes", Arrays.equals(wire, viaStream));
    }
    private static void headRequestHasNoBody() throws IOException {
        byte[] wire = ChunkedWriter.serialize(request(HttpMethod.HEAD, "HTTP/1.1"),
                new HttpResponse().status(200), "payload".getBytes(StandardCharsets.US_ASCII), 4);
        check("HEAD: bytes end at the blank line (no chunks)", headEnd(wire) == wire.length);
        check("HEAD: still advertises chunked", ascii(wire).toLowerCase().contains("transfer-encoding: chunked"));
    }
    private static void http10FallsBackToContentLength() throws IOException {
        byte[] wire = ChunkedWriter.serialize(request(HttpMethod.GET, "HTTP/1.0"),
                new HttpResponse().status(200), "abcdef".getBytes(StandardCharsets.US_ASCII), 4);
        String s = ascii(wire);
        check("HTTP/1.0: no Transfer-Encoding", !s.toLowerCase().contains("transfer-encoding"));
        check("HTTP/1.0: Content-Length: 6 and raw body", s.toLowerCase().contains("content-length: 6\r\n") && s.endsWith("\r\n\r\nabcdef"));
    }
    private static void noBodyStatusesAreNotChunked() throws IOException {
        for (int status : new int[]{204, 304}) {
            byte[] wire = ChunkedWriter.serialize(request(HttpMethod.GET, "HTTP/1.1"),
                    new HttpResponse().status(status), "x".getBytes(StandardCharsets.US_ASCII), 4);
            check(status + ": no chunking and no body",
                    headEnd(wire) == wire.length && !ascii(wire).toLowerCase().contains("transfer-encoding"));
        }
        boolean threw = false;
        try {
            ChunkedWriter.head(new HttpResponse().status(204));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("head() refuses a status that cannot have a body", threw);
    }
    private static void streamingWriter() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ChunkedWriter w = new ChunkedWriter(sink);
        w.write("Hello, ".getBytes(StandardCharsets.US_ASCII));
        w.write(new byte[0]);                                   // must be ignored
        w.write("world".getBytes(StandardCharsets.US_ASCII));
        w.finish();
        w.finish();                                             // second call is harmless
        check("streaming writer output", ascii(sink.toByteArray()).equals("7\r\nHello, \r\n5\r\nworld\r\n0\r\n\r\n"));
        check("streaming writer output decodes", ascii(decode(sink.toByteArray(), 0)).equals("Hello, world"));
        boolean threw = false;
        try {
            w.write(new byte[]{1});
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("write after finish() rejected", threw);
    }
    private static byte[] decode(byte[] wire, int start) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int p = start;
        while (true) {
            int eol = indexOfCrlf(wire, p);
            if (eol < 0) {
                throw new AssertionError("chunk size line not terminated");
            }
            String sizeLine = new String(wire, p, eol - p, StandardCharsets.US_ASCII);
            if (!sizeLine.matches("[0-9a-f]+")) {
                throw new AssertionError("bad chunk size line: '" + sizeLine + "'");
            }
            int size = Integer.parseInt(sizeLine, 16);
            p = eol + 2;
            if (size == 0) {
                if (p + 2 != wire.length || wire[p] != '\r' || wire[p + 1] != '\n') {
                    throw new AssertionError("last chunk must be followed by exactly one CRLF and nothing else");
                }
                return body.toByteArray();
            }
            if (p + size + 2 > wire.length || wire[p + size] != '\r' || wire[p + size + 1] != '\n') {
                throw new AssertionError("chunk data not followed by CRLF");
            }
            body.write(wire, p, size);
            p += size + 2;
        }
    }
    private static HttpRequest request(HttpMethod m, String version) {
        return new HttpRequest(m, m.name(), "/", version, Collections.<String, String>emptyMap());
    }
    private static int headEnd(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i + 4;
            }
        }
        throw new AssertionError("no head terminator");
    }
    private static int indexOfCrlf(byte[] b, int from) {
        for (int i = from; i + 1 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }
    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + name);
        if (!ok) {
            failures++;
        }
    }
}