package server;


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