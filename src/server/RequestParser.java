package server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RequestParser {

    private static final int MAX_REQUEST_LINE_LENGTH = 8192;
    private static final int MAX_TOTAL_HEADER_LENGTH = 16384;
    private static final int MAX_HEADER_COUNT = 100;

    private RequestParser() {
    }

    public static HttpRequest parse(InputStream in) throws IOException, MalformedRequestException {
        String requestLine = readLine(in, MAX_REQUEST_LINE_LENGTH, 414);
        if (requestLine == null) {
            throw new java.io.EOFException("no request line: connection closed");
        }
        if (requestLine.isEmpty()) {
            requestLine = readLine(in, MAX_REQUEST_LINE_LENGTH, 414);
            if (requestLine == null || requestLine.isEmpty()) {
                throw new MalformedRequestException("empty request line", 400);
            }
        }

        StartLine startLine = parseStartLineText(requestLine);
        Map<String, String> headers = parseHeaders(in);

        return new HttpRequest(startLine.method, startLine.rawMethodToken, startLine.target,
                                startLine.version, headers);
    }

    static final class StartLine {
        final HttpMethod method;
        final String rawMethodToken;
        final String target;
        final String version;

        StartLine(HttpMethod method, String rawMethodToken, String target, String version) {
            this.method = method;
            this.rawMethodToken = rawMethodToken;
            this.target = target;
            this.version = version;
        }
    }

    static StartLine parseStartLineText(String requestLine) throws MalformedRequestException {
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3) {
            throw new MalformedRequestException(
                "request line must be 'METHOD target HTTP-version', got: " + requestLine, 400);
        }
        String methodToken = parts[0];
        String target = parts[1];
        String version = parts[2];

        if (methodToken.isEmpty() || target.isEmpty()) {
            throw new MalformedRequestException("empty method or target", 400);
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new MalformedRequestException("unsupported HTTP version: " + version, 505);
        }

        HttpMethod method = HttpMethod.fromToken(methodToken);
        return new StartLine(method, methodToken, target, version);
    }

    static void addHeaderLine(String line, HttpHeaders headers) throws MalformedRequestException {
        if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            throw new MalformedRequestException(
                "obsolete line folding is not supported: " + line, 400);
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new MalformedRequestException("malformed header line: " + line, 400);
        }
        String name = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        headers.add(name, value);
    }

    /**
     * Parses a complete request head (start line + headers, including the
     * terminating blank line) that's already fully in memory — the shape
     * ConnectionState.consume() hands back in the event-loop model, after
     * its own buffering (partial-arrival across reads) and the caller's
     * drain loop (pipelining) have already done their job. Reuses the
     * same validation as the blocking path so the event loop produces
     * identical status codes and header handling instead of a third,
     * looser parser.
     *
     * Tolerant of both CRLF and lone-LF line endings, since
     * ConnectionState.findHeadEnd() accepts either as a terminator.
     */
    public static HttpRequest parseFromHeadBytes(byte[] head) throws MalformedRequestException {
        String text = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> lines = splitLines(text);
        if (lines.isEmpty() || lines.get(0).isEmpty()) {
            throw new MalformedRequestException("empty request line", 400);
        }

        StartLine startLine = parseStartLineText(lines.get(0));

        HttpHeaders headers = new HttpHeaders();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            if (headers.size() >= MAX_HEADER_COUNT) {
                throw new MalformedRequestException("too many headers", 431);
            }
            addHeaderLine(line, headers);
        }

        return new HttpRequest(startLine.method, startLine.rawMethodToken, startLine.target,
                                startLine.version, headers.asMap());
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int end = (i > start && text.charAt(i - 1) == '\r') ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        return lines;
    }

    private static Map<String, String> parseHeaders(InputStream in)
            throws IOException, MalformedRequestException {
        HttpHeaders headers = new HttpHeaders();
        int totalHeaderBytes = 0;

        while (true) {
            String line = readLine(in, MAX_TOTAL_HEADER_LENGTH, 431);
            if (line == null) {
                throw new MalformedRequestException("connection closed mid-headers", 400);
            }
            if (line.isEmpty()) {
                break;
            }

            totalHeaderBytes += line.length();
            if (totalHeaderBytes > MAX_TOTAL_HEADER_LENGTH) {
                throw new MalformedRequestException("header block too large", 431);
            }
            if (headers.size() >= MAX_HEADER_COUNT) {
                throw new MalformedRequestException("too many headers", 431);
            }

            addHeaderLine(line, headers);
        }

        return headers.asMap();
    }

    private static String readLine(InputStream in, int maxLength, int tooLongStatus)
            throws IOException, MalformedRequestException {
        StringBuilder line = new StringBuilder();
        int b;
        boolean sawAnyByte = false;

        while ((b = in.read()) != -1) {
            sawAnyByte = true;
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return line.toString();
                }
                throw new MalformedRequestException("bare CR without LF", 400);
            }
            if (b == '\n') {
                return line.toString();
            }

            line.append((char) (b & 0xFF));
            if (line.length() > maxLength) {
                throw new MalformedRequestException("line exceeds " + maxLength + " bytes", tooLongStatus);
            }
        }

        return sawAnyByte ? line.toString() : null;
    }
}