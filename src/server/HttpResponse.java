package server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public final class HttpResponse {

    private int statusCode = 200;
    private String reasonPhrase;
    // lowercase name -> values (case-insensitive lookup, insertion order kept)
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    // lowercase name -> name as first written by the caller (used on the wire)
    private final Map<String, String> displayNames = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    
    public static HttpResponse of(StatusCode status) {
        return new HttpResponse().status(status);
    }

    
    public static HttpResponse error(StatusCode status, String message) {
        HttpResponse r = new HttpResponse()
                .status(status)
                .header("Content-Type", "text/plain; charset=utf-8");
        if (status.closesConnection()) {
            r.header("Connection", "close");
        }
        if (status.allowsBody()) {
            r.body((message == null ? status.reason() : message) + "\n");
        }
        return r;
    }

    public static HttpResponse badRequest(String message) {
        return error(StatusCode.BAD_REQUEST, message);
    }

    public static HttpResponse notFound() {
        return error(StatusCode.NOT_FOUND, "Not Found");
    }

    /** RFC 7231 §6.5.5: a 405 MUST list the supported methods in Allow. */
    public static HttpResponse methodNotAllowed(String allow) {
        return error(StatusCode.METHOD_NOT_ALLOWED, "Method Not Allowed").header("Allow", allow);
    }

    public static HttpResponse uriTooLong() {
        return error(StatusCode.URI_TOO_LONG, "URI Too Long");
    }

    public static HttpResponse headersTooLarge() {
        return error(StatusCode.HEADER_FIELDS_TOO_LARGE, "Request Header Fields Too Large");
    }

    /** 500: the handler failed; connection state is unknown, so close it. */
    public static HttpResponse internalServerError() {
        return error(StatusCode.INTERNAL_SERVER_ERROR, "Internal Server Error")
                .header("Connection", "close");
    }

    /** 505: request used an HTTP version this server doesn't speak. */
    public static HttpResponse versionNotSupported() {
        return error(StatusCode.HTTP_VERSION_NOT_SUPPORTED, "HTTP Version Not Supported");
    }

    
    public static HttpResponse forMalformed(MalformedRequestException e) {
        StatusCode s = StatusCode.fromCode(e.statusCode());
        if (s == null) {
            s = StatusCode.BAD_REQUEST;
        }
        return error(s, e.getMessage());
    }

    public HttpResponse status(int statusCode) {
        if (!StatusCode.isValid(statusCode)) {
            throw new IllegalArgumentException("Invalid HTTP status code: " + statusCode);
        }
        this.statusCode = statusCode;
        return this;
    }

    public HttpResponse status(StatusCode status) {
        this.statusCode = status.code();
        return this;
    }

    /** Overrides the default phrase for the status; null restores the default. */
    public HttpResponse reasonPhrase(String reasonPhrase) {
        if (reasonPhrase != null) {
            for (int i = 0; i < reasonPhrase.length(); i++) {
                char c = reasonPhrase.charAt(i);
                if ((c < 32 && c != '\t') || c == 127 || c > 255) {
                    throw new IllegalArgumentException("Invalid reason phrase");
                }
            }
        }
        this.reasonPhrase = reasonPhrase;
        return this;
    }

    public HttpResponse header(String name, String value) {
        validate(name, value);
        String key = name.toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(key, values);
        displayNames.put(key, name);
        return this;
    }

    
    public HttpResponse addHeader(String name, String value) {
        validate(name, value);
        String key = name.toLowerCase(Locale.ROOT);
        headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        displayNames.putIfAbsent(key, name);
        return this;
    }

    /** Sets the body and a matching Content-Length (UTF-8 bytes for the String overload). */
    public HttpResponse body(byte[] body) {
        this.body = (body == null) ? new byte[0] : body;
        header("Content-Length", String.valueOf(this.body.length));
        return this;
    }

    public HttpResponse body(String body) {
        return body(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    

    public int statusCode() {
        return statusCode;
    }

    /** The phrase that will be written: the override if set, else the StatusCode table. */
    public String reasonPhrase() {
        return (reasonPhrase != null) ? reasonPhrase : StatusCode.reasonFor(statusCode);
    }

    /** First value of a header (case-insensitive), or null if absent. */
    public String header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /** All values of a header (case-insensitive); empty list if absent. */
    public List<String> headerValues(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null) ? Collections.<String>emptyList()
                                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** A copy of the body bytes. */
    public byte[] bodyBytes() {
        return body.clone();
    }

    
    public void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(statusCode).append(' ')
            .append(reasonPhrase()).append("\r\n");
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            String name = displayNames.get(h.getKey());
            for (String value : h.getValue()) {
                head.append(name).append(": ").append(value).append("\r\n");
            }
        }
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void validate(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Header name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 32 || c == 127 || c == ':') {
                throw new IllegalArgumentException("Invalid character in header name: " + name);
            }
        }
        if (value == null) {
            throw new IllegalArgumentException("Header value must not be null: " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException("Illegal control character in value of header " + name);
            }
        }
    }
}