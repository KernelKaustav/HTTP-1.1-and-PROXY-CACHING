package server;
import java.io.IOException;
import java.net.Socket;
public class ConnectionTask implements Runnable{
    private final Socket socket;
    private final RequestHandler handler;
    public ConnectionTask(Socket socket, RequestHandler handler){
        this.socket=socket;
        this.handler=handler;
    }
    public void run(){
        try (Socket s = socket){
            handler.handle(s);
        }catch(IOException e){
            System.err.println("Connection error:" + e.getMessage());
        }
    }
}