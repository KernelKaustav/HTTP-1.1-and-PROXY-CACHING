package server;

import java.io.IOException;
import java.io.InputStream;
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
        Map<String, String> headers = parseHeaders(in);

        return new HttpRequest(method, methodToken, target, version, headers);
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

            // Obsolete line folding (RFC 7230 §3.2.4): a continuation line
            // starts with a space or tab and is meant to extend the value
            // of the previous header. The RFC tells servers to reject this
            // outright rather than silently unfold it — folding is a known
            // request-smuggling vector, so we treat it as malformed instead
            // of stitching it back together.
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new MalformedRequestException(
                    "obsolete line folding is not supported: " + line, 400);
            }

            totalHeaderBytes += line.length();
            if (totalHeaderBytes > MAX_TOTAL_HEADER_LENGTH) {
                throw new MalformedRequestException("header block too large", 431);
            }
            if (headers.size() >= MAX_HEADER_COUNT) {
                throw new MalformedRequestException("too many headers", 431);
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new MalformedRequestException("malformed header line: " + line, 400);
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            // HttpHeaders.add() handles both the case-insensitive lookup
            // key and RFC 7230 §3.2.2 duplicate-combining in one place.
            headers.add(name, value);
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