# AI Tool Use Disclosure

Per the assignment's AI-use policy: AI assistance is permitted for
explaining concepts, debugging, reviewing code, generating tests,
boilerplate, plotting scripts, and writing — not for generating the
core protocol implementation. This file logs every use, per the
"required, honest disclosure carries no penalty" rule.

| Date | Who | What was asked of the AI tool | What was used / rejected |
|------|-----|-------------------------------|---------------------------|
| 17 Sep | Aniket Ghosh | Generate the Week-1 repo skeleton: folder structure, `.gitignore`, `README.md` skeleton, this file, and a starting point for the shared `RequestHandler` interface + a first pass at TCP accept-loop and request-line/header parsing boilerplate | Used the folder structure, `.gitignore`, `README.md` and `RequestHandler`/`HttpRequest`/`HttpResponse` interface shapes as given. The parsing and server-loop code was used as a **starting skeleton with TODOs** — logic still needs to be extended (body handling, status codes, error paths) and every line needs to be understood before the individual viva. 
| 18 Sep | Ahan Bhattacharjee | Used Claude. Explained the thread-pool concept (fixed pool vs thread-per-request) and generated an initial draft of `ConnectionTask.java` and `EchoRequestHandler.java` | Rewrote both in my own style, renamed variables, and added my own comments after understanding the try-with-resources pattern. |
| 18 Sep | Kaustav Ghosh | Used Claude for understanding the concepts used to build the static file handler class . understood the use of path from java.nio package and its safety implementation . Also took help for error responses | Rewrote both in my own style, renamed variables, and added my own comments after understanding the try-with-resources pattern. |
| 19 Sep | Puskar Das | Used Claude. Explained the Selector/non-blocking-channel model (java.nio) and generated an initial draft of `EventLoopServer.java`'s skeleton (Selector setup, ServerSocketChannel registration for OP_ACCEPT, the select() loop) | Rewrote in my own style, renamed variables, restructured comments after understanding the selector-key lifecycle and why keys must be removed manually. |
| 21 Sep | Ahan Bhattacharjee | Used Claude. Generated a draft of `ThreadPoolServer.java` (`ServerSocket` + `ThreadPoolExecutor` + accept loop) and explained each constructor argument | Reworked the generated code in my own style and verified that I understood the server setup, thread-pool configuration, and accept loop. |
| 23 Sep | Ahan Bhattacharjee | Used Claude. Asked how to make `ThreadPoolServer`'s port and pool size configurable instead of hardcoded, for the concurrency-sweep experiments planned later. Claude suggested parsing them from command-line arguments (`args[0]`, `args[1]`) with basic validation. | Added the argument parsing to `main()`, added the `poolSize <= 0` validation check, and tested it by running with different port/pool size combinations to confirm it worked. |
| 24 Sep | Puskar Das | Used Claude. Asked it to read the repo and the schedule and write my 24 Sep update to `EventLoopServer.java`: handle OP_ACCEPT and register each accepted client channel for OP_READ. Claude generated the code for `handleAccept()`, the error handling in `dispatch()`, closing client channels on shutdown, and a temporary OP_READ placeholder that drains and discards bytes so the loop doesn't busy-spin. It also compiled and tested the code with 5 concurrent clients, and pointed out a filename mismatch in `Mimetypes.java`. | Used the accept loop, the registration logic and the placeholder as generated. Rewrote in my own style, renamed variables, restructured comments after understanding the selector-key lifecycle The placeholder will be replaced by my own read handler on 28 Sep. |
## Notes for teammates

- Add a row every time you use an AI tool for anything beyond trivial
  syntax lookup.
- If you copy AI-generated code into the repo, say so here even if you
  then heavily edited it.
- Undisclosed use discovered at viva is penalized; disclosed use is not.
