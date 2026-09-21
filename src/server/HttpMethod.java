package server;

/**
 * HTTP methods we recognize on the wire. Core scope for this project is
 * GET and HEAD only (see assignment spec) — the others are listed so the
 * parser can still recognize a well-formed request line and reply with
 * 405 Method Not Allowed instead of 400 Bad Request, which is the
 * spec-correct behaviour.
 */
public enum HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    CONNECT,
    OPTIONS,
    TRACE,
    PATCH;

    /**
     * Parses a method token from the request line. Returns null if the
     * token isn't a recognized HTTP method token at all (caller should
     * respond 400 Bad Request in that case, vs. 405 for a recognized
     * but unsupported method).
     */
    public static HttpMethod fromToken(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
