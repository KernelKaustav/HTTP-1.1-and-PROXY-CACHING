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

public final class ForwardProxy implements RequestHandler {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+"; //used ai
    private static final Set<String> HOP_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization",
            "proxy-authenticate"));

    @Override
    public HttpResponse handle(HttpRequest request) {
        boolean head = request.method() == HttpMethod.HEAD;
        if (request.method() != HttpMethod.GET && !head) {
            return error(405, "Only GET and HEAD are supported.", false)
                    .header("Allow", "GET, HEAD");
        }

        // HttpRequest does not yet store request bodies. Reject them explicitly.
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
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            return error(400, "Use an absolute http:// URL without credentials or fragments.", head);
        }

        String host = uri.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        try (Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(READ_TIMEOUT_MS);

            // Use origin-form upstream: GET /path?query HTTP/1.1.
            String hostHeader = host.contains(":") ? "[" + host + "]" : host;
            if (port != 80) hostHeader += ":" + port;
            StringBuilder outgoing = new StringBuilder()
                    .append(head ? "HEAD " : "GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n");
            Set<String> excluded = hopHeaders(request.header("connection"));
            excluded.add("host");
            excluded.add("content-length");
            excluded.add("expect");
            for (Map.Entry<String, String> field : request.headers().entrySet()) {
                validateHeader(field.getKey(), field.getValue());
                if (!excluded.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    outgoing.append(field.getKey()).append(": ")
                            .append(field.getValue()).append("\r\n");
                }
            }
            outgoing.append("Connection: close\r\n\r\n");
            upstream.getOutputStream().write(outgoing.toString()
                    .getBytes(StandardCharsets.ISO_8859_1));
            upstream.getOutputStream().flush();
            return readResponse(new BufferedInputStream(upstream.getInputStream()), head);
        } catch (SocketTimeoutException e) {
            return error(504, "Upstream server timed out.", head);
        } catch (IOException e) {
            return error(502, "Could not obtain a valid upstream response.", head);
        }
    }

    private static HttpResponse readResponse(InputStream in, boolean head) throws IOException {
        int status;
        String reason;
        Map<String, List<String>> headers;
        // Skip informational responses, but protocol switching is unsupported.
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

        HttpResponse response = new HttpResponse().status(status).reasonPhrase(reason);
        Set<String> excluded = hopHeaders(joined(headers, "connection"));
        excluded.add("content-length");
        for (Map.Entry<String, List<String>> field : headers.entrySet()) {
            if (!excluded.contains(field.getKey())) {
                for (String value : field.getValue()) response.addHeader(field.getKey(), value);
            }
        }
        if (!noBody) {
            response.body(body); // decoded body with a newly calculated Content-Length
        } else if (status != 204 && contentLength != null) {
            response.header("Content-Length", Long.toString(parseLength(contentLength)));
        }
        return response.header("Connection", "close");
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
                readHeaders(in); // consume trailers; this version does not forward them
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

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        if (port < 1 || port > 65535 || workers < 1) {
            throw new IllegalArgumentException("Use a valid port and positive worker count.");
        }
        ThreadPoolServer server = new ThreadPoolServer(port, workers, new ForwardProxy());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (IOException e) { System.err.println(e.getMessage()); }
        }));
        server.start();
    }
}
