# P1 — HTTP/1.1 Server and Caching Proxy

## Team:
Ahan Bhattacharjee (2405408), Kaustav Ghosh (2405439), 
Aniket Ghosh (24052131), Puskar Das (24052165)


## What this is

We will basically build an HTTP/1.1 server from the raw TCP sockets - parsing requests and generating the response by ourselves, with no HTTP library like that serves static files and also operates as a forward proxy  with a LRU cache. The server makes the application layer over TCP; it has two defining challenges which are correct byte-stream framing (a TCP stream is not as same as sequence of the HTTP messages) and comparison of two different models which are concurrent for handling many simultaneous clients.

## Core Deliverables
● TCP server implementation of HTTP/1.1: request-line and header parsing, GET and HEAD, correct status codes (200, 304, 400, 404, 405, 414, 431, 500, 505)
●Persistent connections (Connection: keep-alive), correct Content-Length handling, and chunk transfer encoding for the responses of the unknown length
●Static file serving with MIME type of file detection and protection against directory traversal
●Two concurrency models, both implemented and benchmarked against each other: a thread pool and an event loop (select/poll/epoll)
● A forward proxy mode with an LRU cache of Cache-Control, ETag, If-Modified-Since, and conditional GET.

## Stack and Justification
Java (java.net sockets, java.util.concurrent thread polls, java.nio/Selector for event loop) for the core server, using only the standard socket API - no HTTP libraries. Matplotlib and pandas (via small Phython analysis script) are used for experiment plotting only, not for server itself. Java gives us true OS-level threads for thread-pool model and native Selector-based epoll/kqueue wrapper for the event-loop model, so both concurrency models are real and comparable on equal way- and the standard library needs no external HTTP-parsing dependency, keeping every byte and protocol logic our own.

## Team

| Name         | Component owned                               |
|--------------|------------------------------------------------|
| Aniket Ghosh | TCP Server Core (parsing, status codes, buffering) |
| Ahan Bhattacharjee | Concurrency models (thread pool + event loop) |
| TBD          | Static file serving + persistent connections  |
| TBD          | Forward proxy + LRU cache                     |

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

## Status

- [x] Repo initialized
- [x] Shared `RequestHandler` interface + request/response model
- [x] TCP accept loop skeleton
- [x] Request-line and header parsing (begun)
- [ ] GET / HEAD handling with correct status codes
- [ ] Persistent connections (keep-alive) + Content-Length handling
- [ ] Chunked transfer encoding
- [ ] Static file serving (MIME types, directory-traversal protection)
- [ ] Thread pool concurrency model
- [ ] Event loop concurrency model
- [ ] Forward proxy + LRU cache (Cache-Control, ETag, If-Modified-Since)
- [ ] Benchmarks: req/sec and latency percentiles vs. concurrency
- [ ] Benchmarks: cache hit ratio and latency savings

## Building & running

```bash
# from repo root
javac -d bin $(find src -name "*.java")
java -cp bin server.HttpServer <port>
```

## Weekly plan

See `Proposal Document` for the full 8-week plan and work split.
