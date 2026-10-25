## 3. Thread-Pool Model and Benchmark Harness (Ahan Bhattacharjee)

**Design.** ThreadPoolServer uses one thread that blocks on accept() and
hands each accepted connection to a fixed-size ThreadPoolExecutor
(core size = max size). Connections that arrive while all workers are busy
wait in an unbounded LinkedBlockingQueue. Each connection is wrapped in a
ConnectionTask, which closes the socket with try-with-resources so a failed
request cannot leak a connection. The listen backlog is set to 1024;
the default of 50 caused OS-level connection drops at high concurrency
(1,719 errors at 100 clients before the fix, zero after).

**Operation.** Port and pool size are command-line arguments. stop() closes
the server socket first, then calls shutdown() and awaitTermination(5 s),
falling back to shutdownNow(). A shutdown hook runs this on Ctrl+C.
A background thread prints pool statistics every 10 seconds.

**Benchmark harness.** LoadTester runs N concurrent clients, each sending
requests, and records latency per request, after a warm-up pass. It reports
requests/sec and p50/p95/p99. A request counts only if the status is 200 and
the full body arrives. SweepRunner repeats this at 10, 50, 100, 500 and 1000
clients and writes a CSV; plot_results.py produces the graphs.

**Interpretation.** With a warm JVM the thread-pool server reaches its highest
throughput at the lowest load, about 2,650 req/s at 10 clients, and settles around
1,600 req/s from 100 to 500 clients. Extra clients add queueing delay rather than 
throughput: p50 rises from 3 ms to 535 ms, in line with Little’s law (e.g. 500 / 
1,601 ≈ 312 ms vs measured p50 309 ms). With 10 clients there are fewer connections
than workers, so every request is served immediately; beyond 50 clients all workers
are busy and connections queue. The decline in throughput is probably contention
from context switching among 50 workers and up to 1,000 client threads sharing one
machine, but we did not isolate the cause. At 1,000 clients 488 of 20,000 requests
failed (2.4%). Each level is a single run, so differences of 15–20% are within noise.

**Results (pool size 50).** Throughput rises from 629 req/s at 10 clients to
about 1,900 req/s at 100 and then flattens (2,133 req/s at 1000 clients),
while p50 latency grows from 8 ms to 483 ms. Beyond about 100 clients the
50 workers are saturated and connections queue. A pool of 20 matches pool 50
up to 500 clients and falls behind at 1000 (p99 643 ms vs 503 ms).

**Limitations.** The server and load generator share one machine, so
results are relative comparisons rather than absolute capacity. The thread
pool serves one request per connection while the event loop supports
keep-alive, which adds connection-setup cost to the thread-pool numbers.
CallerRunsPolicy is configured but never triggers, because the queue is
unbounded.
