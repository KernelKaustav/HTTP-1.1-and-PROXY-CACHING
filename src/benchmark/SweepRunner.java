package benchmark;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;


public class SweepRunner {
   private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 500, 1000};
   private static final int REQUESTS_PER_CLIENT = 20;
   private static final int GLOBAL_WARMUP_REQUESTS = 3000;
   private static final long COOLDOWN_MS = 90_000;

        public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9091;
        String outputCsv = args.length > 2 ? args[2] : "results/sweep.csv";
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        File parent = new File(outputCsv).getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();

        System.out.println("Global warm-up (" + GLOBAL_WARMUP_REQUESTS + " requests)...");
        runOneLevel(host, port, 50, GLOBAL_WARMUP_REQUESTS / 50);
        cooldown();

        try (PrintWriter csv = new PrintWriter(new FileWriter(outputCsv))) {
            csv.println("concurrency,requests_per_client,total_requests,successful,errors,requests_per_sec,p50_ms,p95_ms,p99_ms");
            for (int concurrency : CONCURRENCY_LEVELS) {
                runOneLevel(host, port, concurrency, 5);          // per-level warm-up, discarded
                cooldown();
                List<SweepResult> runs = new ArrayList<>();
                for (int r = 1; r <= repeats; r++) {
                    System.out.println("Concurrency=" + concurrency + " run " + r + "/" + repeats + "...");
                    SweepResult res = runOneLevel(host, port, concurrency, REQUESTS_PER_CLIENT);
                    System.out.printf("  -> %.2f req/s, p50=%dms, p95=%dms, p99=%dms, errors=%d%n",
                            res.requestsPerSec, res.p50, res.p95, res.p99, res.errors);
                    runs.add(res);
                    cooldown();
                }
                runs.sort(Comparator.comparingDouble((SweepResult x) -> x.requestsPerSec));
                SweepResult median = runs.get(runs.size() / 2);
                csv.printf("%d,%d,%d,%d,%d,%.2f,%d,%d,%d%n",
                        concurrency, REQUESTS_PER_CLIENT, median.totalRequests,
                        median.successful, median.errors, median.requestsPerSec,
                        median.p50, median.p95, median.p99);
                csv.flush();
            }
        }
        System.out.println("Sweep complete. Results written to " + outputCsv);
    }
        private static void cooldown() throws InterruptedException {
        System.out.println("  (cooling down " + COOLDOWN_MS / 1000 + " s so old connections clear)");
        Thread.sleep(COOLDOWN_MS);
    }

    private static SweepResult runOneLevel(String host, int port, int concurrency, int requestsPerClient) throws Exception {
        int totalRequests = concurrency * requestsPerClient;
        ExecutorService clientPool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<Long>> futures = new ArrayList<>(totalRequests);
        long wallClockStart = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            futures.add(clientPool.submit(() -> LoadTesterInternal.sendOneRequest(host, port, errors)));
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
        SweepResult result = new SweepResult();
        result.totalRequests = totalRequests;
        result.successful = latenciesMs.size();
        result.errors = errors.get();
        result.requestsPerSec = latenciesMs.size() / totalSeconds;
        result.p50 = percentile(latenciesMs, 50);
        result.p95 = percentile(latenciesMs, 95);
        result.p99 = percentile(latenciesMs, 99);
        return result;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static class SweepResult {
        int totalRequests, successful, errors;
        double requestsPerSec;
        long p50, p95, p99;
    }
}
