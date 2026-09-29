package benchmark;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
final class LoadTesterInternal{
    private LoadTesterInternal(){

    }
    static long sendOneRequest(String host, int port, AtomicInteger errors){
        long start= System.nanoTime();
        try(Socket socket= new Socket(host,port)){
            OutputStream out= socket.getOutputStream();
            String request=  "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();
            BufferedReader in= new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while((line = in.readLine())!= null && !line.isEmpty()){
                //draining the headers
            }
            long end= System.nanoTime();
            return (end-start)/1_000_000;
        } catch(IOException e){
            errors.incrementAndGet();
            return -1;
        }
    }
}