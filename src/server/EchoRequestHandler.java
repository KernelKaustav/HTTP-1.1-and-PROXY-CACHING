package server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
public class EchoRequestHandler implements RequestHandler{
    public void handle(Socket clientSocket) throws IOException{
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()){
            //draining headers
        }
        String body = "Hello from thread-pool server\n";
        String response = "HTTP/1.1 200 OK\r\n"  + "Content-Type: text/plain\r\n" + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" + "Connection: close\r\n" + "\r\n" + body;
OutputStream out = clientSocket.getOutputStream();
out.write(response.getBytes(StandardCharsets.UTF_8));
out.flush();
    }
}