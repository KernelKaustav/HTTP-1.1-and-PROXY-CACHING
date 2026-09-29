package benchmark;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
public class SweepRunner{
     public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9091;
        String outputCsv = args.length > 2 ? args[2] : "results/threadpool_sweep.csv";
        int[] concurrencyLevels= {10, 50, 100, 500, 1000};
        int requestPerClient= 20;
        try(PrintWriter csv= new PrintWriter(new FileWriter(outputCsv))){
            csv.println("concurrency,requests_per_client,total_requests,successful,errors,requests_per_sec,p50_ms,p95_ms,p99_ms");
            for(int concurrency: concurrencyLevels){
                System.out.println("Running sweep at concurrency=" + concurrency+ "...");
                SweepResult result= runOneLevel(host,port,concurrency,requestPerClient);
                 csv.printf("%d,%d,%d,%d,%d,%.2f,%d,%d,%d%n",
                        concurrency, requestPerClient, result.totalRequests,
                        result.successful, result.errors, result.requestsPerSec,
                        result.p50, result.p95, result.p99);
                        csv.flush();
                         System.out.printf("  -> %.2f req/s, p50=%dms, p95=%dms, p99=%dms%n", result.requestsPerSec, result.p50, result.p95, result.p99);
                         Thread.sleep(2000);
            }
        }
        System.out.println("Sweep complete. Results written to " + outputCsv);

}
 private static SweepResult runOneLevel(String host, int port, int concurrency, int requestsPerClient) throws Exception{
    int totalRequests= concurrency*requestsPerClient;
    ExecutorService clientPool= Executors.newFixedThreadPool(concurrency);
    AtomicInteger errors= new AtomicInteger(0);
     List<Future<Long>> futures = new ArrayList<>(totalRequests);
     long wallClockStart= System.nanoTime();
     for(int i=0;i<totalRequests;i++){
        futures.add(clientPool.submit(() -> LoadTesterInternal.sendOneRequest(host, port, errors)));
     }
     List<Long> latenciesMs = new ArrayList<>(totalRequests);
     for(Future<Long> f: futures){
        long latency= f.get();
        if(latency>=0) latenciesMs.add(latency);
     }
     long wallClockEnd= System.nanoTime();
     clientPool.shutdown();
     double totalSeconds= (wallClockEnd-wallClockStart)/1_000_000_000.0;
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
 private static long percentile(List<Long> sortedLatencies, int p){
    if(sortedLatencies.isEmpty()) return 0;
    int index= (int)Math.ceil(p/100.0*sortedLatencies.size())-1;
    index= Math.max(0,Math.min(index,sortedLatencies.size()-1));
    return sortedLatencies.get(index);
 }
 private static class SweepResult{
    int totalRequests, successful, errors;
    double requestsPerSec;
    long p50, p95, p99;
 }
}