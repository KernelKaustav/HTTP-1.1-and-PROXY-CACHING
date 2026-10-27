package benchmark;

import proxy.ForwardProxy;
import server.HttpResponse;
import server.RequestHandler;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class CacheWorkload {

    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int resources = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int capacity = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int delayMs = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int rounds = args.length > 4 ? Integer.parseInt(args[4]) : 3;

        if (requests < 1 || resources < 1 || capacity < 1
                || delayMs < 0 || rounds < 1) {
            throw new IllegalArgumentException("Invalid experiment settings.");
        }

        int[] workload = createWorkload(requests, resources);
        AtomicLong originRequests = new AtomicLong();

        Path output = Path.of("results", "cache_hitratio.csv");
        Files.createDirectories(output.getParent());

        RequestHandler handler = request -> {
            originRequests.incrementAndGet();

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Origin interrupted", e);
            }

            return new HttpResponse()
                    .status(200)
                    .header("Content-Type", "text/plain")
                    .header("Cache-Control", "public, max-age=3600")
                    .header("Connection", "close")
                    .body(content(request.target()));
        };

        try (LocalServer origin = new LocalServer(handler);
             PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output))) {

            try (LocalServer warmup = new LocalServer(new ForwardProxy(capacity))) {
                for (int i = 0; i < 100; i++) {
                    send(warmup.port(), origin.port(), i % resources, true);
                }
            }

            csv.println("round,mode,requests,resources,capacity,origin_delay_ms,"
                    + "hits,misses,origin_requests,hit_ratio,avg_ms,p50_ms,p95_ms");

            for (int round = 1; round <= rounds; round++) {
                boolean cachedFirst = round % 2 == 0;

                run(csv, round, cachedFirst, workload, resources, capacity,
                        delayMs, origin.port(), originRequests);

                run(csv, round, !cachedFirst, workload, resources, capacity,
                        delayMs, origin.port(), originRequests);
            }

            csv.flush();

            if (csv.checkError()) {
                throw new IOException("Could not write experiment results.");
            }
        }

        System.out.println("Saved " + output);
    }

    private static int[] createWorkload(int requests, int resources) {
        double[] cumulative = new double[resources];
        double total = 0;

        for (int i = 0; i < resources; i++) {
            total += 1.0 / Math.pow(i + 1, 1.2);
            cumulative[i] = total;
        }

        Random random = new Random(42);
        int[] workload = new int[requests];

        for (int i = 0; i < requests; i++) {
            double sample = random.nextDouble() * total;
            int index = Arrays.binarySearch(cumulative, sample);
            workload[i] = index >= 0 ? index : -index - 1;
        }

        return workload;
    }

    private static void run(PrintWriter csv, int round, boolean cached,
                            int[] workload, int resources, int capacity,
                            int delayMs, int originPort,
                            AtomicLong originRequests) throws Exception {

        ForwardProxy proxy = new ForwardProxy(capacity);
        long[] latencies = new long[workload.length];
        long before = originRequests.get();
        double totalMs = 0;

        try (LocalServer server = new LocalServer(proxy)) {
            for (int i = 0; i < workload.length; i++) {
                latencies[i] = send(
                        server.port(), originPort, workload[i], !cached
                );

                totalMs += latencies[i] / 1_000_000.0;
            }
        }

        long upstream = originRequests.get() - before;
        long hits = proxy.cacheStats().hits();
        long misses = workload.length - hits;

        if (upstream != misses
                || (cached && proxy.cacheStats().misses() != misses)) {
            throw new IllegalStateException(
                    "Cache counters do not match origin traffic."
            );
        }

        Arrays.sort(latencies);

        double hitRatio = (double) hits / workload.length;
        double average = totalMs / workload.length;
        String mode = cached ? "cached" : "no_cache";

        csv.printf(Locale.ROOT,
                "%d,%s,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f%n",
                round, mode, workload.length, resources, capacity, delayMs,
                hits, misses, upstream, hitRatio, average,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95));

        System.out.printf(Locale.ROOT,
                "Round %d | %s | hit ratio %.2f%% | average %.3f ms%n",
                round, mode, hitRatio * 100, average);
    }

    private static long send(int proxyPort, int originPort,
                             int resource, boolean bypass) throws IOException {

        String path = "/item/" + resource;

        String request = "GET http://127.0.0.1:" + originPort
                + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\n"
                + (bypass ? "Cache-Control: no-store\r\n" : "")
                + "Connection: close\r\n\r\n";

        long start = System.nanoTime();

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress("127.0.0.1", proxyPort), 5000
            );

            socket.setSoTimeout(15000);

            socket.getOutputStream().write(
                    request.getBytes(StandardCharsets.US_ASCII)
            );

            socket.getOutputStream().flush();

            byte[] bytes = socket.getInputStream().readAllBytes();
            long elapsed = System.nanoTime() - start;

            String response = new String(bytes, StandardCharsets.US_ASCII);
            int separator = response.indexOf("\r\n\r\n");

            if (!response.startsWith("HTTP/1.1 200 ")
                    || separator < 0
                    || !response.substring(separator + 4).equals(content(path))) {
                throw new IOException("Unexpected response for " + path);
            }

            return elapsed;
        }
    }

    private static String content(String path) {
        return "Resource " + path + "\n" + "x".repeat(1024);
    }

    private static double percentile(long[] sorted, double fraction) {
        int index = (int) Math.ceil(sorted.length * fraction) - 1;
        return sorted[index] / 1_000_000.0;
    }

    private static final class LocalServer implements AutoCloseable {

        private final ServerSocket listener;
        private final Thread worker;

        LocalServer(RequestHandler handler) throws IOException {
            listener = new ServerSocket(
                    0, 128, InetAddress.getByName("127.0.0.1")
            );

            worker = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(15000);
                        handler.handle(socket);

                    } catch (IOException e) {
                        if (!listener.isClosed()) {
                            System.err.println(
                                    "Local server: " + e.getMessage()
                            );
                        }
                    }
                }
            });

            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();

            try {
                worker.join(16000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}