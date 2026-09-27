package server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;


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