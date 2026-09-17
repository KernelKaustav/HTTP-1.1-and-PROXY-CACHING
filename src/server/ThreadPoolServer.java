package server;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
public class ThreadPoolServer{
    private final int port;
    private final int poolSize;
    private final RequestHandler requestHandler;
    private ServerSocket serverSocket;
    private ThreadPoolExecutor pool;
    private volatile boolean running = false;
    public ThreadPoolServer(int port, int poolSize, RequestHandler requestHandler){
        this.port = port;
        this.poolSize = poolSize;
        this.requestHandler = requestHandler;
    }
    public void start() throws IOException{
        serverSocket = new ServerSocket(port);
        pool = new ThreadPoolExecutor(poolSize,poolSize, 0L,TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        running = true;
        System.out.println("[ThreadPoolServer] listening on port " + port + " | pool size = " + poolSize);
        while (running){
            try{
                Socket clientSocket = serverSocket.accept();
                pool.submit(new ConnectionTask(clientSocket, requestHandler));
            } catch (IOException e){
                if (running){
                    System.err.println("[ThreadPoolServer] accept() failed: " + e.getMessage());
                }
            }
        }
    }
    public void stop() throws IOException {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()){
            serverSocket.close();
        }
        if(pool != null){
            pool.shutdown();
        }
    }
    public String poolStats(){
        if (pool == null) return "pool not started";
        return String.format("active=%d, queued=%d, completed=%d", pool.getActiveCount(),pool.getQueue().size(), pool.getCompletedTaskCount(), pool.getPoolSize());
    }
    public static void main(String[] args) throws IOException {
        int port = 8080;
        int poolSize = 50;
        ThreadPoolServer server = new ThreadPoolServer(port, poolSize,new EchoRequestHandler());
        server.start();
    }
}