package server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public final class HttpServer {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        RequestHandler handler = new EchoHandler(); // placeholder until real handlers exist

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Listening on port " + port);
            while (true) {
                Socket connection = serverSocket.accept();
                // TODO: hand off to a worker (thread pool / event loop)
                // instead of handling inline on the accept thread.
                handleConnection(connection, handler);
            }
        }
    }

    private static void handleConnection(Socket connection, RequestHandler handler) {
        try (Socket socket = connection) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            HttpRequest request;
            try {
                request = RequestParser.parse(in);
            } catch (EOFException e) {
                // Client closed without sending anything — normal on
                // keep-alive connections, nothing to respond to.
                return;
            } catch (MalformedRequestException e) {
                new HttpResponse()
                    .status(e.statusCode())
                    .header("Connection", "close")
                    .body(e.getMessage())
                    .writeTo(out);
                return;
            }

            System.out.println("Parsed request: " + request);

            HttpResponse response;
            try {
                response = handler.handle(request);
            } catch (IOException e) {
                response = new HttpResponse().status(500).body("Internal Server Error");
            }
            response.writeTo(out);

        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }
    private static final class EchoHandler implements RequestHandler {
        @Override
        public HttpResponse handle(HttpRequest request) {
            String body = "You sent: " + request.rawMethodToken() + " " + request.target()
                + " " + request.httpVersion() + "\nHeaders: " + request.headers() + "\n";
            return new HttpResponse()
                .status(200)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Connection", request.isKeepAlive() ? "keep-alive" : "close")
                .body(body);
        }
    }
}
