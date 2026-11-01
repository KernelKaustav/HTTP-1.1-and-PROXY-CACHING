package server;


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
