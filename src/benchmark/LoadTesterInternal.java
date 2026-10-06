package benchmark;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
final class LoadTesterInternal {
    private LoadTesterInternal() {
    }
    static long sendOneRequest(String host, int port, AtomicInteger errors) {
        long start = System.nanoTime();
        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();
            String request = "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String statusLine = in.readLine();
            if (statusLine == null || !statusLine.contains("200")) {
                errors.incrementAndGet();
                return -1;
            }
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (contentLength > 0) {
                char[] body = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int n = in.read(body, totalRead, contentLength - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
            }
            long end = System.nanoTime();
            return (end - start) / 1_000_000;
        } catch (IOException e) {
            errors.incrementAndGet();
            return -1;
        }
    }
}