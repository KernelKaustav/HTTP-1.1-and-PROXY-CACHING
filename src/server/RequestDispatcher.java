package server;

/**
 * Sits in front of the real handler (StaticFileHandler or ForwardProxy) and
 * owns the protocol-level decisions, so the thread-pool and event-loop paths
 * behave identically:
 *
 *  - HTTP version other than 1.0 / 1.1          -> 505 HTTP Version Not Supported
 *  - method other than GET / HEAD               -> 405 Method Not Allowed (+ Allow)
 *  - GET / HEAD                                 -> delegated to the wrapped handler
 *  - any exception or null from the handler     -> 500 Internal Server Error
 *
 * HEAD needs no special code here: the handler returns the same headers it
 * would for GET and ResponseWriter.serialize(..) strips the body.
 *
 * Usage:  RequestHandler h = new RequestDispatcher(new StaticFileHandler(root));
 */
public final class RequestDispatcher implements RequestHandler {

    /** Value of the Allow header on a 405. */
    static final String ALLOW = "GET, HEAD";

    private final RequestHandler delegate;

    public RequestDispatcher(RequestHandler delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            if (!isSupportedVersion(request.httpVersion())) {
                return HttpResponse.versionNotSupported();
            }

            HttpMethod method = request.method();
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                return HttpResponse.methodNotAllowed(ALLOW);
            }

            HttpResponse response = delegate.handle(request);
            if (response == null) {
                System.err.println("RequestDispatcher: handler returned null response");
                return HttpResponse.internalServerError();
            }
            return response;

        } catch (Exception | OutOfMemoryError e) {
            // Never let a handler bug kill the connection thread / event loop.
            System.err.println("RequestDispatcher: handler failed: " + e);
            return HttpResponse.internalServerError();
        }
    }

    private static boolean isSupportedVersion(String version) {
        return "HTTP/1.1".equals(version) || "HTTP/1.0".equals(version);
    }
}