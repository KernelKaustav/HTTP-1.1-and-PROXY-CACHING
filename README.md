# P1 — HTTP/1.1 Server and Caching Proxy

CS-30003 Coding Assignment 1 — a from-scratch HTTP/1.1 server built
directly on TCP sockets (no HTTP libraries), with two interchangeable
concurrency models and a forward proxy with an LRU cache.

## Team

| Name | Component owned |
|---|---|
| Ahan Bhattacharjee (2405408) | Thread-pool concurrency model, benchmark harness |
| Kaustav Ghosh (2405439) | Static file serving, forward proxy, LRU cache |
| Puskar Das (24052165) | Event-loop concurrency model, keep-alive, chunked encoding |
| Aniket Ghosh (24052131) | TCP server core: HTTP parsing, status codes, buffering |

## What we are building

An HTTP/1.1 server from raw TCP sockets — parsing requests and generating
responses ourselves, with no HTTP library. The server sits at the
application layer over TCP; its two defining challenges are correct
byte-stream framing (a TCP stream is not a sequence of HTTP messages) and
comparing two different concurrency models for handling simultaneous clients.

## The claim we are testing

The event-loop (epoll/select) concurrency model will sustain higher
requests/sec and lower tail latency (p95/p99) than the thread-pool model
as concurrent client count grows, with the gap widening past the point
where thread count starts to exceed available cores.

## Stack

Java (java.net sockets, java.util.concurrent thread pools, java.nio/Selector
for the event loop) for the core server — standard socket API only, no HTTP
libraries. Python (matplotlib, pandas) for experiment plotting only.

## Repository layout

```
.
├── src/
│   ├── server/   # core server: socket handling, request/response model, parsing
│   ├── proxy/    # forward proxy mode + conditional GET handling
│   └── cache/    # LRU cache implementation
├── experiments/  # benchmarking / plotting scripts and raw results
├── docs/         # proposal, design notes, diagrams
├── AI-USE.md     # disclosure log of AI tool usage (required by the assignment)
└── README.md
```
> Note: The work schedule references HttpRequestParser.java and HttpRequestParser-related files — the actual implementation uses RequestParser.java (same functionality, different naming convention adopted during implementation).


## Building

```powershell
# Windows PowerShell (from repo root)
javac -d out (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })
```

```bash
# Linux / Mac (from repo root)
javac -d out $(find src -name "*.java")
```

## Running

```bash
# Thread-pool server (port 9091, pool size 50)
java -cp out server.ThreadPoolServer 9091 50

# Event-loop server (port 8081)
java -cp out server.EventLoopServer 8081

# Forward proxy (port 8082)
java -cp out server.ForwardProxy 8082

# Load test (20 concurrent clients, 10 requests each, against port 9091)
java -cp out benchmark.LoadTester localhost 9091 20 10

# Automated concurrency sweep — writes CSV to results/
java -cp out benchmark.SweepRunner localhost 9091 results/threadpool_rps.csv

# Generate plots from CSVs
python scripts/plot_results.py
```

## Default ports

| Component | Default port |
|---|---|
| ThreadPoolServer | 8080 (benchmarks use 9091 explicitly) |
| EventLoopServer | 8081 |
| ForwardProxy | 8082 |
| LoadTester / SweepRunner | targets 9091 by default |

## Status

- [x] Repo initialized, shared RequestHandler interface
- [x] TCP accept loop, request-line and header parsing
- [x] GET and HEAD with correct status codes (200, 304, 400, 404, 405, 414, 431, 500, 505)
- [x] Static file serving — MIME types, directory-traversal protection
- [x] Thread-pool concurrency model
- [x] Event-loop concurrency model (java.nio Selector)
- [x] Keep-alive — event-loop model
- [x] Chunked transfer encoding — written and tested
- [x] Forward proxy + LRU cache (Cache-Control, no-store, no-cache, max-age)
- [x] Benchmark harness — LoadTester, SweepRunner, plots
- [ ] ETag / If-Modified-Since / 304 conditional GET
- [ ] Cache hit-ratio experiment and plots
- [ ] Event-loop benchmark runs

## Results (thread-pool model, pool size 50)

| Concurrency | Req/sec | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|
| 10 | 629 | 8 | 17 | 123 |
| 50 | 1622 | 28 | 39 | 49 |
| 100 | 1869 | 51 | 58 | 64 |
| 500 | 1920 | 248 | 294 | 302 |
| 1000 | 2133 | 483 | 500 | 503 |

See `results/plots/` for graphs and `docs/benchmark-analysis.md` for analysis.

## AI usage

See `AI-USE.md` for full disclosure. AI was used for explaining concepts,
generating draft code, and debugging. Core protocol implementation was
written and understood by the team. Each member can explain their own
module line by line.

## Weekly plan

See `docs/CS-30003(P1).docx` for the full 8-week plan and work split.
