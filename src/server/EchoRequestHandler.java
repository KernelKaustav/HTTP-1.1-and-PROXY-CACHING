package server;
import java.io.IOException;
public class EchoRequestHandler implements RequestHandler{
    @Override
    public HttpResponse handle(HttpRequest request){
        String body = "Hello from the thread-pool server\n";
        return new HttpResponse()
        .status(200)
        .header("Content-Type", "text/plain; charset=utf-8")
        .header("Connection", request.isKeepAlive() ? "keep-alive" : "close")
        .body(body); // Content-Length is set automatically inside body()
    }
}