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
## Notes for teammates

- Add a row every time you use an AI tool for anything beyond trivial
  syntax lookup.
- If you copy AI-generated code into the repo, say so here even if you
  then heavily edited it.
- Undisclosed use discovered at viva is penalized; disclosed use is not.
