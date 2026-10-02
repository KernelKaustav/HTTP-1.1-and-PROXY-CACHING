package server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
public final class HttpResponse {

    private int statusCode = 200;
    private String reasonPhrase;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    public HttpResponse status(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }
    public HttpResponse reasonPhrase(String reasonPhrase) {
        for (int i = 0; i < reasonPhrase.length(); i++) {
            char c = reasonPhrase.charAt(i);
            if ((c < 32 && c != '\t') || c == 127 || c > 255) {
                throw new IllegalArgumentException("Invalid reason phrase");
            }
        }
        this.reasonPhrase = reasonPhrase;
        return this;
    }

    public HttpResponse header(String name, String value) {
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name.toLowerCase(Locale.ROOT), values);
        return this;
    }
    public HttpResponse addHeader(String name, String value) {
        headers.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                ignored -> new ArrayList<>()).add(value);
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
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            for (String value : h.getValue()) {
            head.append(h.getKey()).append(": ").append(value).append("\r\n");
            }
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
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            case 505: return "HTTP Version Not Supported";
            case 403: return  "Forbidden";
            default:  return "Unknown";
        }
    }
}
