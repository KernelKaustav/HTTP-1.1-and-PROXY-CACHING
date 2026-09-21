package server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A response a RequestHandler builds and the server writes back to the
 * client. Kept as a small mutable builder rather than a record-style
 * immutable class, since handlers typically build it up incrementally
 * (set status, add headers, then attach a body).
 *
 * Status line + reason phrases are the small subset required by Core
 * (200, 304, 400, 404, 405, 414, 431, 500, 505); extend the map in
 * reasonPhraseFor(...) as more codes are needed.
 */
public final class HttpResponse {

    private int statusCode = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    public HttpResponse status(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.body = body;
        header("Content-Length", String.valueOf(body.length));
        return this;
    }

    public HttpResponse body(String body) {
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * Writes the response to the given stream in wire format:
     * status line, headers, blank line, body. Does not close the stream
     * — the caller (server loop) owns the connection lifecycle, since a
     * keep-alive connection may serve many responses.
     */
    public void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(statusCode).append(' ')
            .append(reasonPhraseFor(statusCode)).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reasonPhraseFor(int code) {
        switch (code) {
            case 200: return "OK";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 414: return "URI Too Long";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 505: return "HTTP Version Not Supported";
            default:  return "Unknown";
        }
    }
}
