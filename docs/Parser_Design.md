# Parser Design (TCP Server Core: parsing, status codes, buffering)

## 1. What the parser has to do

Take the raw bytes of one HTTP/1.1 request head (request line and headers) and produce either an immutable `HttpRequest` or a `MalformedRequestException` that carries the status code the server must answer with. The same rules must apply in both concurrency models, so the thread-pool server and the event-loop server give identical answers for identical bytes.

## 2. Components

| Class | Role |
|---|---|
| `RequestParser` | All validation rules. Two entry points: `parse(InputStream)` for the blocking thread-pool model and `parseFromHeadBytes(byte[])` for the event loop. Also exposes `parseStartLineText` and `addHeaderLine`, which every path reuses. |
| `RequestBuffer` | Per-connection byte buffer for the non-blocking model. Finds the end of a head in a stream that may arrive in fragments or several requests at a time, then hands the lines to the shared validation helpers. |
| `MalformedRequestException` | Carries the HTTP status (400, 414, 431, 505) chosen by the parser. `HttpResponse.forMalformed(e)` turns it into the response. |
| `HttpRequest` | Immutable result: method, raw method token, target, version, headers. |
| `RequestDispatcher` | Not part of parsing, but the next layer: 505 for unsupported versions, 405 with `Allow` for non-GET/HEAD, 500 if a handler fails. |

One rule drives the design: **there is exactly one copy of each validation rule.** The blocking path, the head-bytes path and the buffer path all call `parseStartLineText` and `addHeaderLine`, and share the limit constants. This avoids a "third, looser parser" in the event loop.

## 3. Limits and status codes

| Condition | Status | Why |
|---|---|---|
| Request line longer than 8192 bytes | 414 URI Too Long | Bounds memory per connection |
| Header section over 16384 bytes, or more than 100 headers | 431 Request Header Fields Too Large | Same, and stops many-tiny-headers abuse |
| HTTP version not `HTTP/1.0` or `HTTP/1.1` | 505 HTTP Version Not Supported | We only implement 1.x semantics |
| Anything else structurally wrong | 400 Bad Request | See the list in section 4 |
| Unknown but well-formed method (PUT, FOO) | parses with `method == null`; dispatcher answers 405 | The request is valid HTTP, the server just doesn't support the method |

All of 400, 414, 431 and 505 close the connection after the response (`StatusCode.closesConnection()`), because after a parse failure we no longer know where the next request starts.

## 4. Edge-case decisions (and why)

1. **Exactly one space between request-line parts.** `GET  / HTTP/1.1` gives four pieces and is rejected. Silently repairing it would let two parsers disagree about the same bytes.
2. **Method and header names must be RFC 7230 tokens.** Rejects `Host : x` (whitespace before the colon) and `Bad Name: x`. A front-end that strips the space and a back-end that keeps it would see different headers, which is the basis of request-smuggling attacks.
3. **No control characters** in the request line or in header values (TAB is allowed in values). A NUL or bare CR that reaches an echoed target or a log line enables response splitting and log forging. The check runs before `trim()`, since `trim()` would delete a trailing NUL and hide it.
4. **No obsolete line folding** (a header line starting with space or tab). RFC 7230 allows servers to reject it, and proxies disagree on how to unfold it.
5. **One leading empty line is ignored** (RFC 7230 section 3.5). Some old clients send a stray CRLF after a body. All three paths do this the same way. A second empty line is a 400.
6. **Bare CR is rejected** in the blocking reader. CR is only valid as the first half of CRLF.
7. **Size limits apply even when the whole head arrives in one read.** The buffer checks limits while the terminator is missing (slow client) and again when a complete head is extracted (fast client). The first version only did the former, so an oversized head delivered in a single packet slipped through.
8. **Only CRLF CRLF ends a head in the buffer path.** The blocking reader also accepts a bare LF. The buffer path is stricter, which is safe: a bare-LF client never completes a request and is stopped by the size limit or timeout.
9. **EOF before any byte is not an error.** A keep-alive client that simply closes produces `EOFException`, handled silently, not a 400.

## 5. Fragmentation and pipelining (event-loop model)

A non-blocking `read()` returns whatever the kernel has: part of a header, or three requests at once. `RequestBuffer` handles both with two operations and a loop:

```
rb.append(chunk, 0, n);
while (rb.hasCompleteRequest()) {
    HttpRequest req = rb.extractRequest();   // removes exactly one request
    ...
}
```

* **Partial arrival:** `hasCompleteRequest()` stays false until `\r\n\r\n` is buffered. `searchFrom` remembers how far the scan got, minus 3 bytes, so a terminator split across two reads is still found without rescanning from byte 0.
* **Pipelining:** `extractRequest()` consumes only that request's bytes and shifts the remainder to the front (`consume`), so the next pipelined request is still there for the next loop iteration.
* **Bounded memory:** if the terminator is missing and the buffer exceeds a limit, the connection fails with 414 or 431 instead of growing forever.

## 6. How it was tested

* `ParserFuzzTest`: deterministic cases (about 25 inputs) run through all four paths (stream, head bytes, buffer in one chunk, buffer one byte at a time), which must agree on the status code; every 2-way split of a valid request; endless-line and endless-header guards; and 20 000 seeded random inputs (noise, HTTP-flavoured alphabets, mutated valid requests, oversized lines, many headers). The only allowed outcomes are a parsed request, a `MalformedRequestException` with 400/414/431/505, or EOF. Anything else (another exception, a hang, unbounded buffering) fails.
* `PipelineTest`: two to 1000 pipelined requests in one read, 200 requests in random fragments, terminator split at every position, partial trailing request, and an end-to-end run (buffer, dispatcher, serializer) of GET, HEAD, 405, 500 and 505.

## 7. Known limitations

* Request bodies are not parsed. A `POST` with a body gets 405, and its body bytes would be read as the start of the next request (which then fails with 400 and closes the connection). Supporting bodies means reading `Content-Length` in `RequestBuffer` and skipping that many bytes.
* `Transfer-Encoding` on requests is ignored (RFC says 501 for unsupported codings).
* `Host` is not required for HTTP/1.1 requests (RFC says 400 if missing); left optional so simple benchmark clients work.
* The buffer path limits the whole head (request line + headers) to 16384 bytes, while the blocking path limits only the headers, so a head near the limit can pass in one model and fail in the other.

## 8. Likely viva questions

* **Why 414 and 431 instead of 400?** The status tells the client what to fix; RFC 7231 and 6585 define them for exactly these cases.
* **Why does the buffer need `searchFrom`?** To avoid rescanning the buffer from byte 0 on every tiny read; the `-3` handles a terminator split across reads.
* **What stops a slow-loris client from exhausting memory?** The size checks run while the head is still incomplete, so the buffer never grows past about 16 KB per connection.
* **Why reject `Host : x`?** Different servers treat it differently; accepting it is a request-smuggling risk.
* **Why does the dispatcher, not the parser, return 405?** An unknown method is a valid request that we choose not to serve, which is a routing decision, not a syntax error.