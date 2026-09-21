package server;

import java.io.IOException;
import java.io.InputStream;
//import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses an HTTP request line and header block directly off the socket's
 * InputStream.
 *
 * WHY NOT BufferedReader.readLine()?
 * TCP gives us a byte stream, not messages (see assignment Section on P1,
 * "the hard part"). A BufferedReader wraps the stream in its own internal
 * buffer and will happily read ahead past the blank line that ends the
 * headers — including bytes that belong to the request body, or to the
 * *next* pipelined/keep-alive request. Once those bytes are sitting in
 * the reader's private buffer, our code can't get them back to hand to
 * whoever parses the body next. So instead we read byte-by-byte (well,
 * from our own small buffer we fully control) and stop the instant we
 * see the terminating blank line — not one byte more.
 *
 * This class only reads; it does not consume the body. Content-Length /
 * chunked body reading is a separate, later piece of work that will read
 * from the *same* InputStream immediately after this returns.
 */
public final class RequestParser {

    private static final int MAX_REQUEST_LINE_LENGTH = 8192;   // -> 414 if exceeded
    private static final int MAX_TOTAL_HEADER_LENGTH = 16384;  // -> 431 if exceeded
    private static final int MAX_HEADER_COUNT = 100;

    private RequestParser() {
        // static utility class
    }

    /**
     * Reads and parses one full request (request line + headers) from
     * the stream. Leaves the stream positioned exactly at the start of
     * the body (if any).
     *
     * @throws MalformedRequestException for any spec violation, carrying
     *         the status code the caller should respond with.
     * @throws IOException on genuine I/O failure (connection dropped
     *         mid-read, etc.) or on a clean EOF before any bytes arrive
     *         (caller should treat that as "client closed the connection",
     *         not an error).
     */
    public static HttpRequest parse(InputStream in) throws IOException, MalformedRequestException {
        String requestLine = readLine(in, MAX_REQUEST_LINE_LENGTH, 414);
        if (requestLine == null) {
            // EOF before a single byte arrived: nothing to parse. Caller
            // should just close the connection quietly (common on
            // keep-alive connections the client decided to close).
            throw new java.io.EOFException("no request line: connection closed");
        }
        if (requestLine.isEmpty()) {
            // RFC 7230 allows a single leading CRLF to be tolerated and
            // skipped before the real request line, for compatibility
            // with old client bugs. Read the next line instead.
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
        // Note: method == null (unrecognized token) is not itself fatal
        // here — we still return the parsed request and let the handler
        // decide 400 vs 405 based on rawMethodToken(). Keeps this parser
        // focused on syntax, not on request-handling policy.

        Map<String, String> headers = parseHeaders(in);

        return new HttpRequest(method, methodToken, target, version, headers);
    }

    private static Map<String, String> parseHeaders(InputStream in)
            throws IOException, MalformedRequestException {
        Map<String, String> headers = new LinkedHashMap<>();
        int totalHeaderBytes = 0;

        while (true) {
            String line = readLine(in, MAX_TOTAL_HEADER_LENGTH, 431);
            if (line == null) {
                throw new MalformedRequestException("connection closed mid-headers", 400);
            }
            if (line.isEmpty()) {
                // blank line: end of header block
                break;
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
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            if (headers.containsKey(name)) {
                // RFC 7230 §3.2.2: combine repeated headers with a comma
                // rather than silently overwriting — silently dropping
                // one is a common source of subtle bugs (e.g. duplicate
                // Content-Length is actually supposed to be rejected,
                // but that policy decision belongs one layer up).
                headers.put(name, headers.get(name) + ", " + value);
            } else {
                headers.put(name, value);
            }
        }

        return headers;
    }

    /**
     * Reads one CRLF- (or lone-LF-, tolerated) terminated line from the
     * stream, byte by byte, WITHOUT reading past the line terminator.
     * Returns the line without the terminator, or null on EOF with zero
     * bytes read. Uses ISO-8859-1 (Latin-1) to decode: it's a 1:1 mapping
     * from bytes to chars, which is what HTTP header syntax (RFC 7230)
     * actually specifies for header fields, and avoids UTF-8 multi-byte
     * decoding surprises on control-plane bytes.
     */
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
                // Lone CR not followed by LF: be strict, this is malformed.
                throw new MalformedRequestException("bare CR without LF", 400);
            }
            if (b == '\n') {
                // Tolerate a lone LF as a line ending (common in the wild
                // even though RFC 7230 wants CRLF).
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
