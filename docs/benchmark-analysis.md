# Benchmark Analysis — Thread-Pool vs Event-Loop HTTP Server

## Overview

This document analyses the performance of our thread-pool and event-loop
concurrency models under varying client load, using our own LoadTester and
SweepRunner. Server and load generator ran on the same machine.

---

## Method

- **Machine:** <CPU model, cores/threads, RAM, Windows version, Java version>
- **Load generator:** N client threads, each sending requests that open a new
  TCP connection, send `GET /` with `Connection: close`, and read the full
  response. A request counts as successful only if the status is 200 and the
  whole body arrives. Latency is measured from connect to the last body byte.
- **Warm-up:** a global warm-up of 3,000 requests (50 clients) before any timed
  level, plus a small warm-up (5 requests per client) at each level; both are
  discarded.
- **Cooldown:** 90 s pause between runs so old connections clear.
- **Runs:** one timed run per level (20 requests per client) at 10, 50, 100,
  500 and 1000 clients. (SweepRunner can take the median of several runs; the
  final CSVs use a single run because of the cooldowns.)
- **Server settings:** listen backlog 1024; thread-pool sizes 50 and 20.

---

## Experiment 1: Thread-pool throughput and latency (pool size 50)

| Concurrency | Req/sec | p50 (ms) | p95 (ms) | p99 (ms) | Errors |
|---|---|---|---|---|---|
| 10   | 2647 | 3   | 5   | 7   | 0 |
| 50   | 1769 | 26  | 32  | 46  | 0 |
| 100  | 1575 | 62  | 70  | 72  | 0 |
| 500  | 1601 | 309 | 320 | 324 | 0 |
| 1000 | 1979 | 535 | 607 | 621 | 488 of 20,000 |

**Interpretation.** With a warm JVM, throughput is highest at the lowest load
(about 2,650 req/s at 10 clients) and settles near 1,600 req/s from 100 to 500
clients. At 10 clients there are fewer connections than workers, so every
request is served immediately; beyond 50 clients all workers are busy and
connections queue. Extra clients then add queueing delay instead of throughput:
p50 grows from 3 ms to 309 ms at 500 clients. Because each client sends its next
request only after the previous reply, latency is tied to throughput by Little's
law, latency ≈ clients / throughput (500 / 1,601 ≈ 312 ms against a measured p50
of 309 ms; 100 / 1,575 ≈ 63 ms against 62 ms). The decline in throughput after
10 clients is probably contention (context switching among 50 workers and up to
1,000 client threads on one machine), but we did not isolate the cause.

At 1,000 clients 488 of 20,000 requests failed (2.4%). The throughput and
percentiles for that level are computed from successful requests only.

---

## Experiment 2: Pool size 20 vs 50

| Concurrency | RPS (pool 50) | RPS (pool 20) | p99 ms (50) | p99 ms (20) | Errors (50) | Errors (20) |
|---|---|---|---|---|---|---|
| 10   | 2647 | 1144 | 7   | 14  | 0   | 0    |
| 50   | 1769 | 1510 | 46  | 37  | 0   | 0    |
| 100  | 1575 | 1659 | 72  | 65  | 0   | 0    |
| 500  | 1601 | 1693 | 324 | 551 | 0   | 0    |
| 1000 | 1979 | 1695 | 621 | 919 | 488 | 2532 |

**Interpretation.** Throughput does not separate the two pools: the sign of the
difference changes from level to level. At 10 clients both pools have more
workers than clients, so they are effectively identical, yet throughput differs
by 2.3x (1,144 vs 2,647 req/s). That gap shows the size of the run-to-run noise,
so smaller differences elsewhere should not be read as real effects. The
reproducible difference is in the tail and the failures: at 500 clients p99 was
551 ms with 20 workers against 324 ms with 50, and at 1,000 clients 2,532 of
20,000 requests failed (12.7%) against 488 (2.4%). We did not isolate why the
smaller pool fails more; longer queue waits are a plausible but untested
explanation. Each configuration was run once.

---

## Experiment 3: Latency percentile spread

For pool size 50, p50, p95 and p99 stay close together up to 500 clients
(309 / 320 / 324 ms), which indicates a deep, steady queue where almost every
request waits about the same time. At 1,000 clients the spread widens
(535 / 607 / 621 ms). With pool size 20 the spread is wider already at 500
clients (258 / 525 / 551 ms).

---

## Event-Loop Comparison

[TO COMPLETE before committing: generate the table from results/eventloop_rps.csv
with the PowerShell one-liner, then write 3-4 sentences stating (a) where the
event loop has higher throughput, (b) where its p50 and p99 are better or worse,
(c) how many requests failed at each level, and (d) which machine ran it. Do not
reuse the earlier event-loop numbers (408 / 975 / 2575 / 2056 / 3221 req/s): they
came from a different method. Delete this bracket afterwards.]

---

## Methodology Notes and Limitations

- **Earlier sweeps failed.** Sweeps run back to back without long pauses failed
  almost completely at 500 and 1,000 clients (for example 9,515 of 10,000
  requests at 500 clients), while the same server handled the same load cleanly
  when run in isolation after a cooldown. The final sweeps therefore use 90 s
  cooldowns. We tested port exhaustion as an explanation and the test did not
  support it, so the cause is not isolated.
- **Backlog.** Raising the listen backlog from the default 50 to 1024 removed
  the first set of high-concurrency connection errors; it did not prevent the
  failures described above.
- **Single runs.** Each level is one run, and run-to-run variation is large
  (up to about 2x at 10 clients), so only large or repeated differences are
  meaningful.
- **Same machine.** Server and load generator compete for CPU, so results are
  relative comparisons, not absolute capacity.
- **Connection handling.** The thread-pool server serves one request per
  connection. LoadTester always sends `Connection: close`, so keep-alive should
  not be exercised by either server in these runs, and the event loop's
  keep-alive support is not measured here.
- **CallerRunsPolicy** is configured but never fires, because the work queue is
  unbounded.
- **Ports.** ThreadPoolServer defaults to 8080, EventLoopServer to 8081 and the
  load tools to 9091; all runs specified the port explicitly.

---

## Plots

See `results/plots/`:
- `rps_vs_concurrency.png` — throughput, both models and pool sizes
- `p50_comparison.png`, `p95_comparison.png`, `p99_comparison.png` — latency percentiles
- `latency_percentiles.png` — p50/p95/p99 per model
