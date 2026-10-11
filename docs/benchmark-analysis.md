## Design Notes

**CallerRunsPolicy:** The ThreadPoolExecutor is configured with CallerRunsPolicy 
as a safety valve, but since we use an unbounded LinkedBlockingQueue, this policy 
never actually fires — the queue absorbs all overflow instead. It is retained as 
a safeguard in case the queue is changed to a bounded one later.

**Keep-alive:** ThreadPoolServer advertises Connection: keep-alive via 
StaticFileHandler's response headers, but RequestHandler.handle(Socket) serves 
one request per connection and closes it. LoadTester always sends 
Connection: close. This means our thread-pool benchmark numbers include TCP 
connection setup overhead per request, which the event-loop numbers do not. This 
difference is noted for fairness in the thread-pool vs event-loop comparison.

**Port defaults:** ThreadPoolServer defaults to 8080, EventLoopServer to 8081, 
and LoadTester/SweepRunner to 9091. All benchmarks in this report were run 
explicitly specifying the target port.