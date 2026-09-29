package benchmark;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTester {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9091;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        int requestsPerClient = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int totalRequests = concurrency * requestsPerClient;

        ExecutorService clientPool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<Long>> futures = new ArrayList<>(totalRequests);

        long wallClockStart = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            futures.add(clientPool.submit(() -> sendOneRequest(host, port, errors)));
        }

        List<Long> latenciesMs = new ArrayList<>(totalRequests);
        for (Future<Long> f : futures) {
            long latency = f.get();
            if (latency >= 0) latenciesMs.add(latency);
        }

        long wallClockEnd = System.nanoTime();
        clientPool.shutdown();

        double totalSeconds = (wallClockEnd - wallClockStart) / 1_000_000_000.0;
        Collections.sort(latenciesMs);

        System.out.println("---- Load test results ----");
        System.out.printf("Target:          %s:%d%n", host, port);
        System.out.printf("Concurrency:     %d clients%n", concurrency);
        System.out.printf("Total requests:  %d%n", totalRequests);
        System.out.printf("Successful:      %d%n", latenciesMs.size());
        System.out.printf("Errors:          %d%n", errors.get());
        System.out.printf("Wall time:       %.2f s%n", totalSeconds);
        System.out.printf("Requests/sec:    %.2f%n", latenciesMs.size() / totalSeconds);
        System.out.printf("p50 latency:     %d ms%n", percentile(latenciesMs, 50));
        System.out.printf("p95 latency:     %d ms%n", percentile(latenciesMs, 95));
        System.out.printf("p99 latency:     %d ms%n", percentile(latenciesMs, 99));
    }

    static long sendOneRequest(String host, int port, AtomicInteger errors) {
        long start = System.nanoTime();
        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();
            String request = "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // draining headers is enough
            }
            long end = System.nanoTime();
            return (end - start) / 1_000_000;
        } catch (IOException e) {
            errors.incrementAndGet();
            return -1;
        }
    }

    private static long percentile(List<Long> sortedLatencies, int p) {
        if (sortedLatencies.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sortedLatencies.size()) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));
        return sortedLatencies.get(index);
    }
}