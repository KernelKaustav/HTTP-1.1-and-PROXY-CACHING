package server;

/**
 * The HTTP status codes this server and proxy can emit, with their
 * RFC 7231 reason phrases, in one place.
 *
 * Before this enum, the code/phrase table existed twice
 * (HttpResponse.reasonPhraseFor and ResponseWriter.reasonFor) and the two
 * had drifted apart. Everything now resolves phrases through here.
 *
 * Callers that already pass plain ints (MalformedRequestException,
 * StaticFileHandler, ForwardProxy, ...) keep working unchanged:
 * HttpResponse.status(int) looks the int up with {@link #fromCode(int)}.
 */
public enum StatusCode {

    OK(200, "OK"),
    NO_CONTENT(204, "No Content"),
    NOT_MODIFIED(304, "Not Modified"),

    BAD_REQUEST(400, "Bad Request"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    LENGTH_REQUIRED(411, "Length Required"),
    PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
    URI_TOO_LONG(414, "URI Too Long"),
    HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),

    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported");

    /** Phrase used for a code that is not in this table (kept from the old behaviour). */
    public static final String UNKNOWN_REASON = "Unknown";

    private final int code;
    private final String reason;

    StatusCode(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /** True for 4xx and 5xx. */
    public boolean isError() {
        return code >= 400;
    }

    /**
     * RFC 7230 §3.3.3: 1xx, 204 and 304 responses never carry a message body.
     * Same rule as ResponseWriter.mustHaveNoBody, exposed by code here.
     */
    public boolean allowsBody() {
        return !allowsNoBody(code);
    }

    /**
     * Errors after which the byte stream can no longer be trusted (the request
     * was not parsed cleanly, or the request body was refused unread), so the
     * connection must be closed rather than reused for the next request.
     */
    public boolean closesConnection() {
        switch (this) {
            case BAD_REQUEST:
            case REQUEST_TIMEOUT:
            case PAYLOAD_TOO_LARGE:
            case URI_TOO_LONG:
            case HEADER_FIELDS_TOO_LARGE:
            case HTTP_VERSION_NOT_SUPPORTED:
                return true;
            default:
                return false;
        }
    }

    /** Looks up a code in the table; returns null if we do not define it. */
    public static StatusCode fromCode(int code) {
        for (StatusCode s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    /** Reason phrase for any int code; "Unknown" if it is not in the table. */
    public static String reasonFor(int code) {
        StatusCode s = fromCode(code);
        return s != null ? s.reason : UNKNOWN_REASON;
    }

    /** True for a syntactically valid HTTP status code (three digits, 1xx-5xx). */
    public static boolean isValid(int code) {
        return code >= 100 && code <= 599;
    }

    static boolean allowsNoBody(int code) {
        return (code >= 100 && code < 200) || code == 204 || code == 304;
    }

    @Override
    public String toString() {
        return code + " " + reason;
    }
}
