package server;

/**
 * Thrown by RequestParser when the bytes on the wire don't form a valid
 * HTTP request line or header block. Carries the status code the server
 * should reply with, since "malformed" isn't always 400 — a too-long
 * request line is 414, too-large headers are 431.
 */
public class MalformedRequestException extends Exception {

    private final int statusCode;

    public MalformedRequestException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
