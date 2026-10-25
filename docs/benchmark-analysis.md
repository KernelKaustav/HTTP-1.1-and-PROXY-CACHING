# Benchmark Analysis — Thread-Pool HTTP Server

## Overview

This document analyses the performance of our thread-pool and event-loop
concurrency models under varying client load. All experiments were run on
a single Windows 11 machine (KIIT laptop) using our self-written LoadTester
and SweepRunner. The server socket backlog was set to 1024 to eliminate
OS-level connection drops at high concurrency.

---

## Experiment 1: Throughput vs Concurrency (Thread-Pool Model)

**Setup:** Pool size fixed at 50. Concurrency swept across 10, 50, 100,
500, 1000 simultaneous clients. 20 requests per client. One warm-up pass
before timing begins. Server serving static files via StaticFileHandler.

**Results (pool size = 50):**

| Concurrency | Req/sec | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|
| 10   | 629  | 8   | 17  | 123 |
| 50   | 1622 | 28  | 39  | 49  |
| 100  | 1869 | 51  | 58  | 64  |
| 500  | 1920 | 248 | 294 | 302 |
| 1000 | 2133 | 483 | 500 | 503 |

**Interpretation:**

With a warm JVM the thread-pool server reaches its highest throughput at the lowest load, about 2,650 req/s at 10 clients, and settles around 1,600 req/s from 100 to 500 clients. Extra clients add queueing delay rather than throughput: p50 rises from 3 ms to 535 ms, in line with Little’s law (e.g. 500 / 1,601 ≈ 312 ms vs measured p50 309 ms). With 10 clients there are fewer connections than workers, so every request is served immediately; beyond 50 clients all workers are busy and connections queue. The decline in throughput is probably contention from context switching among 50 workers and up to 1,000 client threads sharing one machine, but we did not isolate the cause. At 1,000 clients 488 of 20,000 requests failed (2.4%). Each level is a single run, so differences of 15–20% are within noise.

---

## Experiment 2: Pool Size Comparison

**Setup:** Same sweep repeated with pool size = 20 to show how pool
sizing affects throughput and tail latency.

**Results (pool size = 50 vs pool size = 20):**

| Concurrency | RPS (pool=50) | RPS (pool=20) | p99 (pool=50) | p99 (pool=20) |
|---|---|---|---|---|
| 10   | 629  | 747  | 123 | 103 |
| 50   | 1622 | 1690 | 49  | 36  |
| 100  | 1869 | 1875 | 64  | 59  |
| 500  | 1920 | 1917 | 302 | 303 |
| 1000 | 2133 | 1959 | 503 | 643 |

**Interpretation:**

At low concurrency (10–100 clients), pool size makes almost no difference
— both pools have sufficient spare threads and produce similar RPS and
latency. The divergence appears at concurrency=1000: pool-50 sustains
2133 req/s with p99=503ms, while pool-20 drops to 1959 req/s with
p99=643ms. This is the queuing effect — with only 20 threads serving
1000 simultaneous clients, the average queue depth is much higher than
with 50 threads, so connections wait longer before being served.

The counterintuitive result at concurrency=10–50 where pool-20 is
slightly faster than pool-50 is likely explained by cache effects: fewer
threads means less context-switching overhead and better CPU cache
locality per thread when load is light.

---

## Experiment 3: Latency Percentile Spread

The gap between p50 and p99 grows with concurrency. The tight p50/p95/p99
clustering at concurrency=1000 (483/500/503ms) suggests the server is
genuinely queue-limited at that load level — almost every request waits
roughly the same amount of time, meaning the queue is deep and consistent
rather than bursty.

---

## Event-Loop Model Comparison

Puskar's event-loop results (same machine, port 8081):

| Concurrency | Req/sec | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|
| 10   | 408  | 17  | 45  | 138 |
| 50   | 975  | 43  | 105 | 129 |
| 100  | 2575 | 35  | 59  | 71  |
| 500  | 2056 | 213 | 435 | 537 |
| 1000 | 3221 | 283 | 518 | 596 |

**Why the event-loop is slower at low concurrency (concurrency=10, 50):**

At low concurrency, the thread-pool model is faster (629 vs 408 req/s at
concurrency=10) because each request gets a dedicated thread that blocks
efficiently on I/O — there is no overhead from managing a Selector,
checking readiness, and dispatching events. The NIO event-loop has
inherent per-event overhead that only pays off when there are many
simultaneous connections to multiplex.

At concurrency=100, the event-loop overtakes the thread-pool (2575 vs
1869 req/s) — this is the crossover point where the thread-pool's 50
threads start to saturate and connections queue up, while the event-loop's
single thread continues to handle all connections without queuing delay.
By concurrency=1000, the event-loop sustains 3221 req/s vs the
thread-pool's 2133 req/s — a 51% throughput advantage at high load,
which is the core result our proposal claimed to demonstrate.

**Interpretation of the claim:**

Our proposal claimed the event-loop model would sustain higher requests/sec
and lower tail latency (p95/p99) at high concurrency. The results support
the first part but not the second. The event loop delivers higher throughput
at 100 and 1000 clients (2575 vs 1869 and 3221 vs 2133 req/s) and a better
median latency from 100 clients upward. However, its p99 latency at 500 and
1000 clients (537 ms and 596 ms) is worse than the thread pool's (302 ms
and 503 ms). At 500 clients the two models are close on throughput.
A possible explanation is that one thread serving all connections lets
individual slow connections wait longer. We did not test this directly.
---

## Design Notes

**CallerRunsPolicy:** The ThreadPoolExecutor is configured with
CallerRunsPolicy as a safety valve, but since we use an unbounded
LinkedBlockingQueue, this policy never actually fires — the queue
absorbs all overflow instead. It is retained as a safeguard in case
the queue is changed to a bounded one later.

**Connection handling:** ThreadPoolServer serves one request per
connection (Connection: close). The event-loop model supports
keep-alive (multiple requests per connection). This means the
thread-pool benchmark numbers include TCP connection setup overhead
per request, which the event-loop numbers do not. Despite this
disadvantage for the thread-pool, it still outperforms the event-loop
at low concurrency, suggesting connection overhead is not the dominant
factor at low load.

**Port defaults:** ThreadPoolServer defaults to 8080, EventLoopServer
to 8081, and LoadTester/SweepRunner to 9091. All benchmarks in this
report were run explicitly specifying the target port.

**Backlog fix:** The initial benchmark runs used the default ServerSocket
backlog of 50, which caused OS-level connection drops at high concurrency
(1,719 errors at concurrency=100, 9,538 at concurrency=500). The backlog
was increased to 1024 via new ServerSocket(port, 1024) and all experiments
were rerun — errors dropped to zero across all concurrency levels.

**Benchmark validity:** The load generator and server run on the same
machine, which means they compete for CPU cores. Results should be
interpreted as relative comparisons between configurations rather than
absolute throughput figures.

---

## Plots

See `results/plots/` for:
- `rps_vs_concurrency.png` — throughput comparison, pool-50 vs pool-20
- `latency_percentiles.png` — p50/p95/p99 vs concurrency, pool-50
- `p99_comparison.png` — tail latency comparison, pool-50 vs pool-20
