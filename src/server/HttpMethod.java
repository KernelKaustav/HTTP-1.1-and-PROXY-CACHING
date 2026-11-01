package server;


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

    
    public static HttpMethod fromToken(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
