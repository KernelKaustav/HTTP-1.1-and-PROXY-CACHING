package benchmark;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
public class LoadTester{
    public static void main(String[] args) throws Exception{
        String host= args.length>0?args[0]:"localhost";
        int port= args.length>1?Integer.parseInt(args[1]):9091;
        long latencyMs= sendOneRequest(host,port);
        System.out.println("Latency: " + latencyMs +" ms");
    }
    private static long sendOneRequest(String host, int port) throws IOException{
        long start= System.nanoTime();
        try(Socket socket= new Socket(host,port)){
            OutputStream out= socket.getOutputStream();
            String request= "GET / HTTP/1.1\r\nHost: " + host +"\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();
            BufferedReader in= new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while((line= in.readLine()) != null && !line.isEmpty()){
               // draining response headers is enough to know the server replied 
            }
        }
        long end= System.nanoTime();
        return (end-start)/1_000_000; //converting nanoseconds to miliseconds
    }
}