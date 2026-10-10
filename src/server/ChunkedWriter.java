package server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
public final class ChunkedWriter {
    public static final int DEFAULT_CHUNK_SIZE = 8192;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream out;
    private boolean finished = false;

    public ChunkedWriter(OutputStream out) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        this.out = out;
    }
    public void write(byte[] data, int off, int len) throws IOException {
        if (finished) {
            throw new IllegalStateException("last chunk already sent");
        }
        out.write(encodeChunk(data, off, len));
    }
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        out.write(LAST_CHUNK);
        out.flush();
    }
    public static byte[] encodeChunk(byte[] data, int off, int len) {
        if (off < 0 || len < 0 || off > data.length - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " array=" + data.length);
        }
        if (len == 0) {
            return new byte[0];
        }
        byte[] size = Integer.toHexString(len).getBytes(StandardCharsets.US_ASCII);
        byte[] chunk = new byte[size.length + 2 + len + 2];
        int p = 0;
        System.arraycopy(size, 0, chunk, p, size.length);
        p += size.length;
        chunk[p++] = '\r';
        chunk[p++] = '\n';
        System.arraycopy(data, off, chunk, p, len);
        p += len;
        chunk[p++] = '\r';
        chunk[p] = '\n';
        return chunk;
    }
    public static byte[] lastChunk() {
        return LAST_CHUNK.clone();
    }
    public static byte[] encodeBody(byte[] body, int chunkSize) {
        requirePositive(chunkSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + body.length / chunkSize * 8 + 16);
        for (int off = 0; off < body.length; off += chunkSize) {
            int len = Math.min(chunkSize, body.length - off);
            byte[] c = encodeChunk(body, off, len);
            out.write(c, 0, c.length);
        }
        out.write(LAST_CHUNK, 0, LAST_CHUNK.length);
        return out.toByteArray();
    }
    public static byte[] encodeStream(InputStream in, int chunkSize) throws IOException {
        requirePositive(chunkSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[chunkSize];
        while (true) {
            int filled = 0;
            while (filled < chunkSize) {
                int n = in.read(buf, filled, chunkSize - filled);
                if (n == -1) {
                    break;
                }
                filled += n;
            }
            if (filled > 0) {
                byte[] c = encodeChunk(buf, 0, filled);
                out.write(c, 0, c.length);
            }
            if (filled < chunkSize) {
                break;                              // EOF reached
            }
        }
        out.write(LAST_CHUNK, 0, LAST_CHUNK.length);
        return out.toByteArray();
    }
    public static byte[] head(HttpResponse response) throws IOException {
        if (ResponseWriter.mustHaveNoBody(response.statusCode())) {
            throw new IllegalArgumentException(
                    "status " + response.statusCode() + " must not have a body, so it cannot be chunked");
        }
        response.header("Transfer-Encoding", "chunked");
        byte[] wire = ResponseWriter.serialize(response, true);   // head only; strips Content-Length
        return wire;
    }
    public static byte[] serialize(HttpRequest request, HttpResponse response, byte[] body, int chunkSize) throws IOException {
        requirePositive(chunkSize);
        boolean headOnly = request != null && request.method() == HttpMethod.HEAD;
        if (ResponseWriter.mustHaveNoBody(response.statusCode())) {
            return ResponseWriter.serialize(response, headOnly);
        }
        if (!supportsChunked(request)) {
            response.body(body);                                  // sets Content-Length
            return ResponseWriter.serialize(response, headOnly);
        }
        byte[] head = head(response);
        if (headOnly) {
            return head;
        }
        return concat(head, encodeBody(body, chunkSize));
    }
    public static byte[] serialize(HttpRequest request, HttpResponse response, InputStream body, int chunkSize) throws IOException {
        requirePositive(chunkSize);
        boolean headOnly = request != null && request.method() == HttpMethod.HEAD;
        if (ResponseWriter.mustHaveNoBody(response.statusCode()) || headOnly) {
            return serialize(request, response, new byte[0], chunkSize);
        }
        if (!supportsChunked(request)) {
            return serialize(request, response, readAll(body), chunkSize);
        }
        return concat(head(response), encodeStream(body, chunkSize));
    }
    public static boolean supportsChunked(HttpRequest request) {
        return request == null || "HTTP/1.1".equals(request.httpVersion());
    }
    private static void requirePositive(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}