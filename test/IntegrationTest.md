# Integration Checkpoint — 18 Oct 2026

## Test environment
- OS: Windows 11
- Java version: 17+ (run `java -version` to confirm exact version)
- Both servers compiled clean from fresh git pull

## Build
```powershell
javac -d out (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })
```

## ThreadPoolServer (port 9091, pool=50)
- GET / → 200 OK, correct HTML body ✅
- HEAD / → 200 OK, Content-Length present, no body ✅
- GET /doesnotexist.html → 404 Not Found ✅
- POST / → 405 Method Not Allowed ✅
- GET /../traversal → 403/404 (blocked) ✅

## EventLoopServer (port 8081)
- GET / → 200 OK ✅
- HEAD / → 200 OK ✅
- GET /doesnotexist.html → 404 Not Found ✅
- POST / → 405 Method Not Allowed ✅
- GET /../traversal → blocked ✅

## Known differences between models
Documented in docs/benchmark-analysis.md:
- ThreadPoolServer serves one request per connection (Connection: close)
- EventLoopServer supports keep-alive (multiple requests per connection)
- Unknown method handling differs: thread pool returns 405, event loop
  returns 400 — to be unified before final submission
- This connection-handling difference affects benchmark fairness and is
  noted explicitly in the analysis

## Still pending before 25 Oct
- Unify status codes for unknown methods across both models
- Wire ChunkedWriter into at least one response path
- ETag / If-Modified-Since / 304 not yet implemented
- Event-loop benchmark runs and cache experiment CSVs still to come