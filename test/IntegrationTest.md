# Integration Checkpoint — 18 Oct 2026

## Both servers tested against:
- GET / → 200 OK ✅
- HEAD / → 200 OK, no body ✅
- GET /doesnotexist.html → 404 Not Found ✅
- POST / → 405 Method Not Allowed ✅
- GET /../traversal → 403/404 (blocked) ✅

## ThreadPoolServer (port 9091, pool=50)
- Serves static files via StaticFileHandler ✅
- Real HTTP parsing via RequestParser ✅
- Compiles clean from fresh git pull ✅

## EventLoopServer (port 8081)
- [Puskar fills in]

## Notes
- ThreadPoolServer uses Connection: close (one request per connection)
- EventLoopServer supports keep-alive (multiple requests per connection)
- This difference is noted in docs/benchmark-analysis.md