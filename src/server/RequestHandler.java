package server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * The one interface every teammate's code should share.
 *
 * Two entry points, two audiences:
 *  - handle(HttpRequest) is what business-logic implementers write
 *    (static file serving, proxy mode). It never touches a socket.
 *  - handle(Socket) is what connection-owning code calls (thread pool
 *    worker, event-loop callback) once it has an accepted connection. It
 *    has a default implementation — parse, delegate to handle(HttpRequest),
 *    write the response — so nobody has to reimplement that wiring.
 */
public interface RequestHandler {

    HttpResponse handle(HttpRequest request) throws IOException;

    default void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        HttpRequest request;
        try {
            request = RequestParser.parse(in);
        } catch (EOFException e) {
            return;
        } catch (MalformedRequestException e) {
            new HttpResponse()
                .status(e.statusCode())
                .header("Connection", "close")
                .body(e.getMessage())
                .writeTo(out);
            return;
        }

        HttpResponse response;
        try {
            response = handle(request);
        } catch (IOException e) {
            response = new HttpResponse().status(500).body("Internal Server Error");
        }
        response.writeTo(out);
    }
}