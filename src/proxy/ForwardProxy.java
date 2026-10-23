package proxy;

import server.HttpMethod;
import server.HttpRequest;
import server.HttpResponse;
import server.RequestHandler;
import server.ThreadPoolServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class ForwardProxy implements RequestHandler {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final Set<String> HOP_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization",
            "proxy-authenticate"));
    private final LruCache<String, CachedResponse> cache;
    private final CacheStats cacheStats = new CacheStats();
    private final Clock clock;

    public ForwardProxy() {
        this(64);
    }

    public ForwardProxy(int cacheCapacity) {
        this(cacheCapacity, Clock.systemUTC());
    }
    ForwardProxy(int cacheCapacity, Clock clock) {
        this.cache = new LruCache<>(cacheCapacity);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CacheStats cacheStats() {
        return cacheStats;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        boolean head = request.method() == HttpMethod.HEAD;

        if (request.method() != HttpMethod.GET && !head) {
            return error(405, "Only GET and HEAD are supported.", false)
                    .header("Allow", "GET, HEAD");
        }

        String length = request.header("content-length");

        if (request.header("transfer-encoding") != null
                || request.header("expect") != null
                || (length != null && !length.matches("0+"))) {
            return error(400, "Request bodies are not supported.", head);
        }

        final URI uri;

        try {
            uri = new URI(new URI(request.target()).toASCIIString());
        } catch (URISyntaxException e) {
            return error(400, "Invalid absolute URI.", head);
        }

        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            return error(
                    400,
                    "Use an absolute http:// URL without credentials or fragments.",
                    head
            );
        }

        String host = uri.getHost();

        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }

        int port = uri.getPort() == -1 ? 80 : uri.getPort();

        String path = uri.getRawPath();

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        String cacheKey = host.toLowerCase(Locale.ROOT)
                + ":" + port + path;

        CachePolicy requestPolicy = CachePolicy.parse(
                request.header("cache-control")
        );

        boolean cacheEligible = !head
                && permitsCaching(request)
                && !requestPolicy.noStore();

        boolean forceRefresh = requestPolicy.requiresValidation()
                || requestPolicy.maxAgeSeconds() == 0
                || (request.header("cache-control") == null
                    && CachePolicy.parse(request.header("pragma"))
                                .requiresValidation());

        CachedResponse stale = null;

        try {

            for (Map.Entry<String, String> field
                    : request.headers().entrySet()) {
                validateHeader(field.getKey(), field.getValue());
            }

            if (cacheEligible) {
                synchronized (cache) {
                    CachedResponse stored = cache.get(cacheKey);

                    if (stored != null) {
                        long ageMillis = stored.currentAgeMillis(
                                clock.millis()
                        );

                        long requestMaxAge = requestPolicy.maxAgeSeconds();

                        boolean acceptableAge = requestMaxAge < 0
                                || ageMillis <= requestMaxAge * 1000;

                        if (!forceRefresh
                                && stored.policy.isFresh(ageMillis)
                                && acceptableAge) {
                            cacheStats.recordHit();

                            String etag = joined(stored.data.headers, "etag");
                            String lastModified = joined(
                                    stored.data.headers,
                                    "last-modified"
                            );

                            if (ConditionalGet.isNotModified(
                                    request,
                                    etag,
                                    lastModified
                            )) {
                                return ConditionalGet.notModified(
                                        etag,
                                        lastModified,
                                        stored.data.headers
                                ).header("Age", Long.toString(ageMillis / 1000));
                            }

                            return stored.data.toResponse()
                                    .header("Age", Long.toString(ageMillis / 1000));
                        }

                        stale = stored;
                    }
                }
            }

            try (Socket upstream = new Socket()) {
                upstream.connect(
                        new InetSocketAddress(host, port),
                        CONNECT_TIMEOUT_MS
                );

                upstream.setSoTimeout(READ_TIMEOUT_MS);

                String hostHeader = host.contains(":")
                        ? "[" + host + "]"
                        : host;

                if (port != 80) {
                    hostHeader += ":" + port;
                }

                StringBuilder outgoing = new StringBuilder()
                        .append(head ? "HEAD " : "GET ")
                        .append(path)
                        .append(" HTTP/1.1\r\n")
                        .append("Host: ")
                        .append(hostHeader)
                        .append("\r\n");

                Set<String> excluded = hopHeaders(
                        request.header("connection")
                );

                excluded.add("host");
                excluded.add("content-length");
                excluded.add("expect");

                if (stale != null) {
                    excluded.add("if-none-match");
                    excluded.add("if-modified-since");
                }

                for (Map.Entry<String, String> field
                        : request.headers().entrySet()) {
                    String name = field.getKey()
                            .toLowerCase(Locale.ROOT);

                    if (!excluded.contains(name)) {
                        outgoing.append(field.getKey())
                                .append(": ")
                                .append(field.getValue())
                                .append("\r\n");
                    }
                }

                if (stale != null) {
                    String etag = joined(stale.data.headers, "etag");
                    String lastModified = joined(
                            stale.data.headers,
                            "last-modified"
                    );

                    if (etag != null && !stale.data.generatedEtag) {
                        outgoing.append("If-None-Match: ")
                                .append(etag)
                                .append("\r\n");
                    } else if (lastModified != null) {
                        outgoing.append("If-Modified-Since: ")
                                .append(lastModified)
                                .append("\r\n");
                    }
                }

                outgoing.append("Connection: close\r\n\r\n");

                long requestTime = clock.millis();

                upstream.getOutputStream().write(
                        outgoing.toString().getBytes(
                                StandardCharsets.ISO_8859_1
                        )
                );

                upstream.getOutputStream().flush();

                ResponseData data = readResponse(
                        new BufferedInputStream(
                                upstream.getInputStream()
                        ),
                        head
                );

                long responseTime = clock.millis();

                if (stale != null && data.status == 304) {
                    data = stale.data.refreshedBy(data);
                    cacheStats.recordHit();
                } else if (cacheEligible) {
                    cacheStats.recordMiss();
                }

                if (data.status == 200 && !data.headers.containsKey("etag")) {
                    data.headers.put(
                            "etag",
                            new ArrayList<>(Arrays.asList(
                                    ETagGenerator.generate(data.body)
                            ))
                    );
                    data.generatedEtag = true;
                }

                if (cacheEligible) {
                    CachePolicy responsePolicy = CachePolicy.parse(
                            joined(data.headers, "cache-control")
                    );

                    CachedResponse candidate = new CachedResponse(
                            data,
                            responsePolicy,
                            requestTime,
                            responseTime
                    );

                    boolean canCache = data.status == 200
                            && !data.headers.containsKey("set-cookie")
                            && !data.headers.containsKey("vary")
                            && responsePolicy.allowsSharedStorage();

                    if (canCache) {
                        cache.put(cacheKey, candidate);
                    } else {
                        cache.remove(cacheKey);
                    }
                }

                String etag = joined(data.headers, "etag");
                String lastModified = joined(data.headers, "last-modified");

                if (ConditionalGet.isNotModified(
                        request,
                        etag,
                        lastModified
                )) {
                    return ConditionalGet.notModified(
                            etag,
                            lastModified,
                            data.headers
                    );
                }

                return data.toResponse();
            }

        } catch (SocketTimeoutException e) {
            return error(504, "Upstream server timed out.", head);

        } catch (IOException e) {
            return error(
                    502,
                    "Could not obtain a valid upstream response.",
                    head
            );
        }
    }

    private static ResponseData readResponse(InputStream in, boolean head) throws IOException {
        int status;
        String reason;
        Map<String, List<String>> headers;

        int informationalCount = 0;
        do {
            String line = readLine(in);
            if (!line.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) {
                throw new IOException("Invalid upstream status line");
            }
            status = Integer.parseInt(line.substring(9, 12));
            reason = line.length() > 12 ? line.substring(13) : "";
            validateHeader("reason", reason);
            headers = readHeaders(in);
            if (status == 101 || ++informationalCount > 10) {
                throw new IOException("Unsupported upstream response");
            }
        } while (status < 200);

        boolean noBody = head || status == 204 || status == 304;
        String transferEncoding = joined(headers, "transfer-encoding");
        String contentLength = joined(headers, "content-length");
        byte[] body = new byte[0];
        if (!noBody) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (transferEncoding != null) {
                if (!"chunked".equalsIgnoreCase(transferEncoding.trim())
                        || contentLength != null) {
                    throw new IOException("Unsupported or ambiguous response framing");
                }
                readChunks(in, bytes);
            } else if (contentLength != null) {
                copyExactly(in, bytes, parseLength(contentLength));
            } else {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkSize(bytes.size(), count);
                    bytes.write(buffer, 0, count);
                }
            }
            body = bytes.toByteArray();
        }

        Long representationLength =
                noBody && status != 204 && contentLength != null
                        ? parseLength(contentLength) : null;
        return new ResponseData(
                status,
                reason,
                headers,
                body,
                noBody,
                representationLength
        );
    }
    private static void readChunks(InputStream in, ByteArrayOutputStream out) throws IOException {
        while (true) {
            String line = readLine(in);
            String sizeText = line.split(";", 2)[0].trim();
            long size;
            try {
                if (!sizeText.matches("[0-9a-fA-F]+")) throw new NumberFormatException();
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size", e);
            }
            if (size == 0) {
                readHeaders(in);
                return;
            }
            copyExactly(in, out, size);
            if (!readLine(in).isEmpty()) throw new IOException("Missing chunk terminator");
        }
    }

    private static void copyExactly(InputStream in, ByteArrayOutputStream out, long count)
            throws IOException {
        checkSize(out.size(), count);
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read == -1) throw new EOFException("Truncated upstream body");
            out.write(buffer, 0, read);
            count -= read;
        }
    }

    private static void checkSize(int current, long additional) throws IOException {
        if (additional < 0 || additional > MAX_BODY_BYTES - current) {
            throw new IOException("Response exceeds the buffering limit");
        }
    }

    private static long parseLength(String text) throws IOException {
        Long length = null;
        for (String item : text.split(",", -1)) {
            try {
                String value = item.trim();
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                long parsed = Long.parseLong(value);
                if (length != null && length != parsed) throw new NumberFormatException();
                length = parsed;
            } catch (NumberFormatException e) {
                throw new IOException("Invalid or conflicting Content-Length", e);
            }
        }
        return length;
    }

    private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        while (true) {
            String line = readLine(in);
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) throw new IOException("Upstream headers too large");
            if (line.isEmpty()) return headers;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Malformed upstream header");
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            validateHeader(name, value);
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                    ignored -> new ArrayList<>()).add(value);
        }
    }

    private static void validateHeader(String name, String value) throws IOException {
        if (!name.matches(TOKEN)) throw new IOException("Invalid header name");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 && c != '\t') || c == 127 || c > 255) {
                throw new IOException("Invalid header value");
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() <= MAX_LINE_BYTES) {
            int b = in.read();
            if (b == -1) throw new EOFException("Upstream closed mid-line");
            if (b == '\r') {
                if (in.read() != '\n') throw new IOException("Expected CRLF");
                return line.toString();
            }
            if (b == '\n') throw new IOException("Expected CRLF");
            line.append((char) b);
        }
        throw new IOException("Upstream line too long");
    }

    private static String joined(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null ? null : String.join(",", values);
    }

    private static Set<String> hopHeaders(String connection) {
        Set<String> names = new HashSet<>(HOP_HEADERS);
        if (connection != null) {
            for (String token : connection.split(",")) {
                names.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static HttpResponse error(int status, String message, boolean head) {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        HttpResponse response = new HttpResponse().status(status)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Connection", "close");
        return head ? response.header("Content-Length", Integer.toString(body.length))
                : response.body(body);
    }
    private static boolean permitsCaching(HttpRequest request) {
        for (String name : request.headers().keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.equals("authorization")
                    || lower.equals("cookie")
                    || lower.equals("range")) {
                return false;
            }
        }

        return true;
    }

    private static final class ResponseData {

        final int status;
        final String reason;
        final Map<String, List<String>> headers;
        final byte[] body;
        final boolean noBody;
        final Long representationLength;
        boolean generatedEtag;

        ResponseData(
                int status,
                String reason,
                Map<String, List<String>> headers,
                byte[] body,
                boolean noBody,
                Long representationLength
        ) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
            this.noBody = noBody;
            this.representationLength = representationLength;
        }

        HttpResponse toResponse() {
            HttpResponse response = new HttpResponse()
                    .status(status)
                    .reasonPhrase(reason);

            Set<String> excluded = hopHeaders(
                    joined(headers, "connection")
            );

            excluded.add("content-length");

            for (Map.Entry<String, List<String>> field
                    : headers.entrySet()) {
                if (!excluded.contains(field.getKey())) {
                    for (String value : field.getValue()) {
                        response.addHeader(field.getKey(), value);
                    }
                }
            }

            if (!noBody) {
                response.body(body.clone());
            } else if (representationLength != null) {
                response.header(
                        "Content-Length",
                        Long.toString(representationLength)
                );
            }

            return response.header("Connection", "close");
        }

        ResponseData refreshedBy(ResponseData validation) {
            Map<String, List<String>> refreshedHeaders = new LinkedHashMap<>();

            for (Map.Entry<String, List<String>> field : headers.entrySet()) {
                refreshedHeaders.put(
                        field.getKey(),
                        new ArrayList<>(field.getValue())
                );
            }

            for (Map.Entry<String, List<String>> field
                    : validation.headers.entrySet()) {
                if (!field.getKey().equals("content-length")
                        && !field.getKey().equals("transfer-encoding")) {
                    refreshedHeaders.put(
                            field.getKey(),
                            new ArrayList<>(field.getValue())
                    );
                }
            }

            ResponseData refreshed = new ResponseData(
                    status,
                    reason,
                    refreshedHeaders,
                    body.clone(),
                    noBody,
                    representationLength
            );
            refreshed.generatedEtag = validation.headers.containsKey("etag")
                    ? false
                    : generatedEtag;
            return refreshed;
        }
    }

    private static final class CachedResponse {

        final ResponseData data;
        final CachePolicy policy;
        final long receivedAt;
        final long initialAgeMillis;

        CachedResponse(
                ResponseData data,
                CachePolicy policy,
                long sentAt,
                long receivedAt
        ) {
            this.data = data;
            this.policy = policy;
            this.receivedAt = receivedAt;

            this.initialAgeMillis = initialAge(
                    data.headers,
                    sentAt,
                    receivedAt
            );
        }

        long currentAgeMillis(long now) {

            if (now < receivedAt) {
                return Long.MAX_VALUE;
            }

            return saturatedAdd(
                    initialAgeMillis,
                    now - receivedAt
            );
        }
    }

    private static long initialAge(
            Map<String, List<String>> headers,
            long sentAt,
            long receivedAt
    ) {
        if (receivedAt < sentAt) {
            return Long.MAX_VALUE;
        }

        long apparentAge = 0;
        String date = joined(headers, "date");

        if (date != null) {
            try {
                long dateMillis = ZonedDateTime.parse(
                        date,
                        DateTimeFormatter.RFC_1123_DATE_TIME
                ).toInstant().toEpochMilli();

                apparentAge = Math.max(
                        0,
                        Math.subtractExact(receivedAt, dateMillis)
                );

            } catch (DateTimeParseException | ArithmeticException e) {

                return Long.MAX_VALUE;
            }
        }

        long ageMillis = 0;
        String age = joined(headers, "age");

        if (age != null) {
            try {
                String value = age.trim();

                if (!value.matches("[0-9]+")) {
                    return Long.MAX_VALUE;
                }

                ageMillis = Math.multiplyExact(
                        Long.parseLong(value),
                        1000
                );

            } catch (NumberFormatException | ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }

        long responseDelay = receivedAt - sentAt;

        long correctedAge = saturatedAdd(
                ageMillis,
                responseDelay
        );

        return Math.max(apparentAge, correctedAge);
    }

    private static long saturatedAdd(long first, long second) {
        if (first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }

        return first + second;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        if (port < 1 || port > 65535 || workers < 1) {
            throw new IllegalArgumentException("Use a valid port and positive worker count.");
        }
        ForwardProxy proxy = new ForwardProxy();
        ThreadPoolServer server = new ThreadPoolServer(port, workers, proxy);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }

            CacheStats stats = proxy.cacheStats();
            System.out.printf(
                    "Cache hits: %d, misses: %d, hit rate: %.2f%%%n",
                    stats.hits(),
                    stats.misses(),
                    stats.hitRate() * 100
            );
        }));
        server.start();
    }
}
