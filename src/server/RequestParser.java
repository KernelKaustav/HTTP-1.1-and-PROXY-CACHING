package server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the bytes of one HTTP request head (request line + headers) into an
 * {@link HttpRequest}, or rejects them with a {@link MalformedRequestException}
 * that carries the status code the server must answer with.
 *
 * There are two entry points, one per concurrency model, but they share the
 * same validation helpers so both models answer identical bytes identically:
 *
 *   parse(InputStream)         thread-pool model: blocking socket, reads byte by byte
 *   parseFromHeadBytes(byte[]) event-loop model: the complete head is already in memory
 *
 * (RequestBuffer is the third caller: it finds the end of the head in a
 * non-blocking byte stream, then reuses parseStartLineText / addHeaderLine.)
 *
 * Status codes produced here:
 *   400  anything structurally wrong
 *   414  request line longer than MAX_REQUEST_LINE_LENGTH
 *   431  too many headers, or header bytes over MAX_TOTAL_HEADER_LENGTH
 *   505  HTTP version other than 1.0 / 1.1
 * Everything else (405, 404, 500, ...) is decided later, by the dispatcher/handlers.
 */
public final class RequestParser {

    /** Longest accepted request line, in bytes. Longer -> 414 URI Too Long. */
    static final int MAX_REQUEST_LINE_LENGTH = 8192;
    /** Longest accepted header section (sum of header line lengths). Longer -> 431. */
    static final int MAX_TOTAL_HEADER_LENGTH = 16384;
    /** Most header lines accepted. More -> 431. Stops "10 000 tiny headers" memory abuse. */
    static final int MAX_HEADER_COUNT = 100;

    private RequestParser() {
        // static utility class: nothing to instantiate
    }

    // ==================================================================
    // Entry point 1: blocking stream (thread-pool model)
    // ==================================================================

    public static HttpRequest parse(InputStream in) throws IOException, MalformedRequestException {
        String requestLine = readLine(in, MAX_REQUEST_LINE_LENGTH, 414);

        // null means the peer closed before sending a single byte. That is not a
        // protocol error (a keep-alive client simply hung up), so it is reported
        // as EOF and the caller closes quietly instead of sending a 400.
        if (requestLine == null) {
            throw new EOFException("no request line: connection closed");
        }

        // RFC 7230 3.5: a server SHOULD ignore at least one empty line received
        // before the request line (some old clients send a stray CRLF after a POST
        // body). We skip exactly one; a second empty line is treated as garbage.
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

    // ==================================================================
    // Request line
    // ==================================================================

    /** The three parts of "METHOD target HTTP-version". */
    static final class StartLine {
        final HttpMethod method;       // null if the token is not a method we know
        final String rawMethodToken;   // always kept, for error messages and tests
        final String target;
        final String version;

        StartLine(HttpMethod method, String rawMethodToken, String target, String version) {
            this.method = method;
            this.rawMethodToken = rawMethodToken;
            this.target = target;
            this.version = version;
        }
    }

    /**
     * Validates and splits one request line. Used by all three entry points.
     * Every rule here exists for a reason; the reason is in the comment.
     */
    static StartLine parseStartLineText(String requestLine) throws MalformedRequestException {
        // Control characters (CR, LF, NUL, TAB, ...) have no place in a request line.
        // A bare CR or NUL that survives into the target can be echoed into a response
        // header or log line later (response splitting / log forging), so reject early.
        rejectControlChars(requestLine, false, "request line");

        // Exactly one space between the three parts. split(" ", -1) keeps empty
        // pieces, so "GET  /" (two spaces) becomes 4 parts and is rejected below
        // instead of being silently "fixed", which would let two parsers disagree.
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

        // A method is an RFC 7230 "token" (letters, digits and a few symbols).
        // Anything else is not a method at all -> 400. A well-formed token that we
        // simply don't implement (PUT, FOO, ...) is NOT an error here: it parses
        // with method == null and the dispatcher answers 405.
        if (!isToken(methodToken)) {
            throw new MalformedRequestException("invalid method token", 400);
        }

        // Only HTTP/1.0 and HTTP/1.1 are spoken. Matching is case-sensitive on
        // purpose: the RFC defines the version string as exactly "HTTP/1.1".
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new MalformedRequestException("unsupported HTTP version: " + version, 505);
        }

        HttpMethod method = HttpMethod.fromToken(methodToken);
        return new StartLine(method, methodToken, target, version);
    }

    // ==================================================================
    // Header line
    // ==================================================================

    /**
     * Validates one header line ("Name: value") and stores it. The caller has
     * already removed the line terminator and guarantees line is not empty.
     */
    static void addHeaderLine(String line, HttpHeaders headers) throws MalformedRequestException {
        // A header line that starts with space/tab is "obsolete line folding"
        // (a continuation of the previous header). RFC 7230 3.2.4 lets a server
        // reject it, and accepting it is a request-smuggling risk because proxies
        // disagree on how to unfold. We reject.
        if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            throw new MalformedRequestException(
                "obsolete line folding is not supported", 400);
        }

        // No colon, or a colon in position 0 (empty name) -> not a header.
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new MalformedRequestException("malformed header line: " + line, 400);
        }

        // The name is NOT trimmed. RFC 7230 3.2.4: whitespace between the name and
        // the colon ("Host : x") must be rejected, because a front-end that strips it
        // and a back-end that keeps it see different headers (smuggling). Requiring
        // every character to be a token character rejects that and "Bad Name: x".
        String name = line.substring(0, colon);
        if (!isToken(name)) {
            throw new MalformedRequestException("invalid header name", 400);
        }

        // Control characters are checked BEFORE trim(), because String.trim() would
        // silently delete a trailing NUL or CR and hide it from the check. A TAB is
        // legal inside a value (RFC 7230 optional whitespace), so it is allowed.
        String rawValue = line.substring(colon + 1);
        rejectControlChars(rawValue, true, "header value");

        // Leading/trailing optional whitespace is not part of the value.
        headers.add(name, rawValue.trim());
    }

    // ==================================================================
    // Entry point 2: complete head already in memory (event-loop model)
    // ==================================================================

    /**
     * Parses a complete request head (start line + headers, including the
     * terminating blank line) that is already fully in memory. Applies the
     * same limits and validation as the blocking path, so the same bytes give
     * the same status code in both concurrency models.
     *
     * Accepts both CRLF and lone-LF line endings, matching ConnectionState's
     * head-end detection and the blocking readLine().
     */
    public static HttpRequest parseFromHeadBytes(byte[] head) throws MalformedRequestException {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        List<String> lines = splitLines(text);

        // Same "ignore one leading empty line" rule as parse(InputStream).
        int first = (!lines.isEmpty() && lines.get(0).isEmpty()) ? 1 : 0;
        if (lines.size() <= first || lines.get(first).isEmpty()) {
            throw new MalformedRequestException("empty request line", 400);
        }

        // The size limit must be checked here too: when a whole oversized head
        // arrives in ONE read, the event loop has already seen the terminator and
        // never runs its "head still incomplete, is it too big?" check.
        String requestLine = lines.get(first);
        if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
            throw new MalformedRequestException("line exceeds " + MAX_REQUEST_LINE_LENGTH + " bytes", 414);
        }

        StartLine startLine = parseStartLineText(requestLine);

        HttpHeaders headers = new HttpHeaders();
        int totalHeaderBytes = 0;
        for (int i = first + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue; // the blank line that ends the head
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

        return new HttpRequest(startLine.method, startLine.rawMethodToken, startLine.target,
                                startLine.version, headers.asMap());
    }

    /**
     * Splits on LF and strips one CR before it. Only a CR directly before the LF
     * is a line ending; a CR anywhere else stays in the line and is later
     * rejected as a control character. A final unterminated fragment is dropped:
     * a complete head always ends with a line terminator.
     */
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

    // ==================================================================
    // Blocking-stream helpers
    // ==================================================================

    /** Reads header lines up to the blank line, enforcing the count and size limits. */
    private static Map<String, String> parseHeaders(InputStream in)
            throws IOException, MalformedRequestException {
        HttpHeaders headers = new HttpHeaders();
        int totalHeaderBytes = 0;

        while (true) {
            // A single header line may use up to the whole header budget; longer -> 431.
            String line = readLine(in, MAX_TOTAL_HEADER_LENGTH, 431);

            if (line == null) {
                // EOF in the middle of the head: the client gave up or was cut off.
                throw new MalformedRequestException("connection closed mid-headers", 400);
            }
            if (line.isEmpty()) {
                break; // blank line: end of the header section
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

    /**
     * Reads one line (up to CRLF or LF) one byte at a time, never holding more
     * than maxLength characters. Returns null only if the stream ended before
     * any byte was read.
     *
     * Bytes are mapped to chars 1:1 (ISO-8859-1), so a byte >= 0x80 can never
     * become a multi-char sequence and "length" always means bytes.
     */
    private static String readLine(InputStream in, int maxLength, int tooLongStatus)
            throws IOException, MalformedRequestException {
        StringBuilder line = new StringBuilder();
        int b;
        boolean sawAnyByte = false;

        while ((b = in.read()) != -1) {
            sawAnyByte = true;

            if (b == '\r') {
                // CR is only legal as the first half of CRLF. Anything else
                // (including EOF right after the CR) is a bare CR -> 400.
                int next = in.read();
                if (next == '\n') {
                    return line.toString();
                }
                throw new MalformedRequestException("bare CR without LF", 400);
            }
            if (b == '\n') {
                return line.toString(); // lone LF accepted as a line end (RFC 7230 3.5)
            }

            line.append((char) (b & 0xFF));

            // Checked while reading, not afterwards, so a client that never sends
            // a newline cannot make us buffer an unbounded amount.
            if (line.length() > maxLength) {
                throw new MalformedRequestException("line exceeds " + maxLength + " bytes", tooLongStatus);
            }
        }

        // End of stream: if bytes were read, hand back the unterminated final
        // line so the caller decides (the head parsers then fail it as malformed).
        return sawAnyByte ? line.toString() : null;
    }

    // ==================================================================
    // Character-level validation
    // ==================================================================

    /** Rejects ASCII control characters (and DEL). TAB may be allowed (header values only). */
    private static void rejectControlChars(String s, boolean allowTab, String where)
            throws MalformedRequestException {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isControl = (c < 0x20) || (c == 0x7F);
            if (isControl && !(allowTab && c == '\t')) {
                throw new MalformedRequestException("control character in " + where, 400);
            }
        }
    }

    /** RFC 7230 "token": one or more tchar. Used for method names and header names. */
    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTokenChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}