# Benchmark Analysis — Thread-Pool HTTP Server

## Overview

This document analyses the performance of our thread-pool concurrency model
under varying client load. All experiments were run on a single Windows 11
machine (KIIT laptop) using our self-written LoadTester and SweepRunner
against a ThreadPoolServer serving static files via StaticFileHandler.
The server socket backlog was set to 1024 to eliminate OS-level connection
drops at high concurrency.

---

## Experiment 1: Throughput vs Concurrency

**Setup:** Pool size fixed at 50. Concurrency swept across 10, 50, 100,
500, 1000 simultaneous clients. 20 requests per client. One warm-up pass
before timing begins.

**Results (pool size = 50):**

| Concurrency | Req/sec | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|
| 10  | 629  | 8   | 17  | 123 |
| 50  | 1622 | 28  | 39  | 49  |
| 100 | 1869 | 51  | 58  | 64  |
| 500 | 1920 | 248 | 294 | 302 |
| 1000| 2133 | 483 | 500 | 503 |

**Interpretation:**

Throughput rises steeply from concurrency 10 to 100, then flattens
significantly between 100 and 1000. This is the classic thread-pool
saturation curve: below ~100 concurrent clients, the pool has spare
threads and handles requests with minimal queuing. Beyond 100, the 50
threads are all busy and new connections queue in the LinkedBlockingQueue
— throughput gains diminish because the bottleneck has shifted from
"not enough concurrent requests" to "not enough threads to process them
faster." The slight increase from 500 to 1000 reflects the load generator
itself saturating rather than genuine server improvement.

The p99 spike at concurrency=10 (123ms vs p95=17ms) is a JVM startup
artefact — even with a warm-up pass, the first batch of connections at
low concurrency occasionally triggers class-loading or GC pauses that
show up in the tail. This effect disappears at higher concurrency, where
JVM internals are already warmed up from the volume of prior requests.

---

## Experiment 2: Pool Size Comparison

**Setup:** Same sweep, repeated with pool size = 20 to show how pool
sizing affects throughput and tail latency.

**Results (pool size = 20 vs pool size = 50):**

| Concurrency | RPS (pool=50) | RPS (pool=20) | p99 (pool=50) | p99 (pool=20) |
|---|---|---|---|---|
| 10  | 629  | 747  | 123 | 103 |
| 50  | 1622 | 1690 | 49  | 36  |
| 100 | 1869 | 1875 | 64  | 59  |
| 500 | 1920 | 1917 | 302 | 303 |
| 1000| 2133 | 1959 | 503 | 643 |

**Interpretation:**

At low concurrency (10–100 clients), pool size makes almost no difference
— both pools have sufficient spare threads and produce similar RPS and
latency. The divergence appears at concurrency=1000: pool-50 sustains
2133 req/s with p99=503ms, while pool-20 drops to 1959 req/s with
p99=643ms. This is the queuing effect — with only 20 threads serving
1000 simultaneous clients, the average queue depth is much higher than
with 50 threads, so connections wait longer before being served.

The counterintuitive result at concurrency=10–50 (pool-20 slightly
*faster* than pool-50) is likely explained by cache effects: fewer
threads means less context-switching overhead and better CPU cache
locality per thread when load is light.

---

## Experiment 3: Latency Percentile Spread

The gap between p50 and p99 grows with concurrency, from ~115ms at
concurrency=10 (largely JVM noise) to ~20ms at concurrency=50 (tight
distribution, pool not yet saturated) to ~20ms at concurrency=100, then
widening to ~54ms at concurrency=500 and ~20ms at concurrency=1000.

The tight p50/p95/p99 clustering at concurrency=1000 (483/500/503ms)
suggests the server is genuinely queue-limited at that load level —
almost every request waits roughly the same amount of time, meaning
the queue is deep and consistent rather than bursty.

---

## Design Notes

**CallerRunsPolicy:** The ThreadPoolExecutor is configured with
CallerRunsPolicy as a safety valve, but since we use an unbounded
LinkedBlockingQueue, this policy never actually fires — the queue
absorbs all overflow instead. It is retained as a safeguard in case
the queue is changed to a bounded one later.

**Keep-alive:** ThreadPoolServer advertises Connection: keep-alive via
StaticFileHandler's response headers, but RequestHandler.handle(Socket)
serves one request per connection and closes it. LoadTester always sends
Connection: close. This means our thread-pool benchmark numbers include
TCP connection setup overhead per request, which the event-loop numbers
do not. This difference is noted for fairness in the thread-pool vs
event-loop comparison.

**Port defaults:** ThreadPoolServer defaults to 8080, EventLoopServer
to 8081, and LoadTester/SweepRunner to 9091. All benchmarks in this
report were run explicitly specifying the target port.

**Backlog fix:** The initial benchmark runs used the default ServerSocket
backlog of 50, which caused OS-level connection drops at high concurrency
(1,719 errors at concurrency=100, 9,538 at concurrency=500). The backlog
was increased to 1024 via new ServerSocket(port, 1024) and all experiments
were rerun — errors dropped to zero across all concurrency levels.

**Benchmark validity:** The load generator and server run on the same
machine, which means they compete for CPU cores. On a machine with N
cores, the effective parallelism is split between the server's thread pool
and the load generator's client threads. Results should be interpreted
as relative comparisons between configurations rather than absolute
throughput figures.

## Known Issues

**Status code mismatch:** ThreadPoolServer returns 405 for unknown HTTP 
methods (e.g. BREW), while EventLoopServer returns 400. Per RFC 7230, 
an unrecognized method token should return 400 (Bad Request), not 405 
(Method Not Allowed — which implies the method is recognized but not 
supported). This will be unified in a future fix by Aniket.

---

## Plots

See `results/plots/` for:
- `rps_vs_concurrency.png` — throughput comparison, pool-50 vs pool-20
- `latency_percentiles.png` — p50/p95/p99 vs concurrency, pool-50
- `p99_comparison.png` — tail latency comparison, pool-50 vs pool-20