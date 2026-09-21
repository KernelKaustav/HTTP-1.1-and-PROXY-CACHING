package server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, parsed representation of one HTTP request.
 *
 * This is deliberately a plain data holder: RequestParser builds it,
 * RequestHandler implementations consume it. Keeping it dumb means
 * every teammate can write against a stable shape while the parsing
 * and handling logic evolve independently.
 *
 * Body is not wired up yet (Week 1 scope is the request line + headers).
 * When Content-Length / chunked handling lands, add a `byte[] body` (or
 * an InputStream, for streaming) and a builder-style "withBody(...)"
 * rather than changing this constructor everywhere it's called.
 */
public final class HttpRequest {

    private final HttpMethod method;
    private final String rawMethodToken; // preserved even if method == null, for error messages
    private final String target;         // request-target, e.g. "/index.html?x=1"
    private final String httpVersion;    // e.g. "HTTP/1.1"
    private final Map<String, String> headers; // header name (lowercased) -> value

    public HttpRequest(HttpMethod method,
                        String rawMethodToken,
                        String target,
                        String httpVersion,
                        Map<String, String> headers) {
        this.method = method;
        this.rawMethodToken = rawMethodToken;
        this.target = target;
        this.httpVersion = httpVersion;
        // Defensive copy + unmodifiable: nobody downstream should mutate
        // a parsed request out from under another handler.
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public HttpMethod method() {
        return method;
    }

    public String rawMethodToken() {
        return rawMethodToken;
    }

    public String target() {
        return target;
    }

    public String httpVersion() {
        return httpVersion;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** Case-insensitive header lookup (header names must be case-insensitive per RFC 7230). */
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public boolean isKeepAlive() {
        String connection = header("connection");
        if (connection != null) {
            return connection.equalsIgnoreCase("keep-alive");
        }
        // HTTP/1.1 defaults to persistent connections unless told otherwise.
        return "HTTP/1.1".equals(httpVersion);
    }

    @Override
    public String toString() {
        return rawMethodToken + " " + target + " " + httpVersion + " " + headers;
    }
}
