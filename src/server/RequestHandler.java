package server;
import java.io.IOException;
import java.net.Socket;
public interface RequestHandler{
    void handle(Socket clientSocket) throws IOException;
}