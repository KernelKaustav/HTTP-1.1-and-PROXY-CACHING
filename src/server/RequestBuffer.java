package server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection buffering state machine for the non-blocking event loop.
 *
 * A non-blocking channel's read() returns whatever bytes the kernel has right
 * now: possibly 3 bytes of a header line (partial arrival), possibly several
 * complete requests back to back (pipelining). OP_READ fires once and the
 * callback must return quickly, so it cannot block waiting for "the rest" the
 * way RequestParser.parse(InputStream) can.
 *
 * Usage, one RequestBuffer per connection, kept across OP_READ events:
 *
 *   rb.append(chunk, 0, n);
 *   while (rb.hasCompleteRequest()) {
 *       HttpRequest req = rb.extractRequest();
 *       // dispatch req, write a response ...
 *   }
 *
 * The loop handles both cases: with partial arrival the body never runs until
 * enough bytes have accumulated over several reads; with pipelining it runs once
 * per complete request in this read.
 *
 * If hasCompleteRequest() or extractRequest() throws MalformedRequestException
 * the connection must be answered with that status and then CLOSED: the buffer
 * is left as-is and its byte stream can no longer be trusted.
 *
 * Scope: start line + headers only (request bodies are not supported yet).
 */
public final class RequestBuffer {

    private static final int INITIAL_CAPACITY = 4096;

    // Limits are shared with RequestParser so the blocking and non-blocking
    // paths can never drift apart.
    private static final int MAX_REQUEST_LINE_LENGTH = RequestParser.MAX_REQUEST_LINE_LENGTH; // -> 414
    private static final int MAX_HEADER_BLOCK_LENGTH = RequestParser.MAX_TOTAL_HEADER_LENGTH; // -> 431, whole head
    private static final int MAX_HEADER_COUNT = RequestParser.MAX_HEADER_COUNT;               // -> 431

    private byte[] buf = new byte[INITIAL_CAPACITY];
    private int len = 0;        // buf[0..len) = bytes received but not yet consumed by extractRequest()
    private int searchFrom = 0; // everything before this index was already scanned without finding the terminator

    /** Feeds newly-read bytes into the buffer. Safe to call with length 0. */
    public void append(byte[] data, int offset, int length) {
        if (length <= 0) {
            return; // a read that returned nothing new
        }
        ensureCapacity(len + length);
        System.arraycopy(data, offset, buf, len, length);
        len += length;
    }

    /**
     * True if the buffer holds at least one complete request head (terminated
     * by a blank line). Consumes nothing, so it is safe to call repeatedly;
     * that is what makes "while (hasCompleteRequest()) extractRequest()" drain
     * every pipelined request.
     *
     * @throws MalformedRequestException if the bytes buffered so far already
     *         break a size limit even though no terminator has arrived (a slow
     *         client trickling bytes must not make the buffer grow forever).
     */
    public boolean hasCompleteRequest() throws MalformedRequestException {
        return findHeaderBlockEnd() >= 0;
    }

    /**
     * Parses and removes exactly one complete request from the front of the
     * buffer. Call only after hasCompleteRequest() returned true. Leftover
     * bytes (the next pipelined request, whole or partial) stay buffered.
     */
    public HttpRequest extractRequest() throws MalformedRequestException {
        int blockEnd = findHeaderBlockEnd();
        if (blockEnd < 0) {
            // Programming error in the caller, not a bad client: don't send a status for it.
            throw new IllegalStateException(
                "extractRequest() called with no complete request buffered - check hasCompleteRequest() first");
        }

        String block = new String(buf, 0, blockEnd, StandardCharsets.ISO_8859_1);
        List<String> lines = splitOnCrlf(block);

        // RFC 7230 3.5: ignore one empty line before the request line (same rule as
        // RequestParser.parse). A second empty line, or nothing at all, is a 400.
        int first = (!lines.isEmpty() && lines.get(0).isEmpty()) ? 1 : 0;
        if (lines.size() <= first || lines.get(first).isEmpty()) {
            throw new MalformedRequestException("empty request line", 400);
        }

        // Size limits on a head that arrived COMPLETE. findHeaderBlockEnd() only
        // applies them while the terminator is still missing, so a whole oversized
        // head delivered in one read would otherwise be accepted.
        // The request line is checked first so the status (414) matches what
        // the blocking parser reports for the same bytes.
        String requestLine = lines.get(first);
        if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
            throw new MalformedRequestException("request line too long", 414);
        }
        if (blockEnd > MAX_HEADER_BLOCK_LENGTH) {
            throw new MalformedRequestException("header block too large", 431);
        }

        RequestParser.StartLine startLine = RequestParser.parseStartLineText(requestLine);

        HttpHeaders headers = new HttpHeaders();
        for (int i = first + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue; // the blank line that terminates the head
            }
            if (headers.size() >= MAX_HEADER_COUNT) {
                throw new MalformedRequestException("too many headers", 431);
            }
            RequestParser.addHeaderLine(line, headers);
        }

        // Only consume once parsing succeeded: bytes are never dropped on an error path.
        consume(blockEnd);

        return new HttpRequest(startLine.method, startLine.rawMethodToken, startLine.target,
                                startLine.version, headers.asMap());
    }

    // ------------------------------------------------------------------
    // internal plumbing
    // ------------------------------------------------------------------

    /**
     * Index just past the first "\r\n\r\n" in the buffer, or -1 if there is none
     * yet. Remembers how far it already scanned (searchFrom) so a slow
     * connection delivering many tiny reads does not rescan from byte 0 each time.
     *
     * Only CRLFCRLF ends a head here. The blocking parser also tolerates bare LF;
     * this path is stricter, which is safe (a bare-LF client just never gets a
     * request completed and hits the size limit / timeout).
     */
    private int findHeaderBlockEnd() throws MalformedRequestException {
        for (int i = searchFrom; i + 4 <= len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
        }

        // Not found. Everything except the last 3 bytes has been checked; a
        // terminator split across the old/new boundary can only start in those 3.
        searchFrom = Math.max(0, len - 3);

        // Terminator still missing but too many bytes already: this head can never
        // become valid, so fail now instead of buffering until memory runs out.
        if (len > MAX_HEADER_BLOCK_LENGTH) {
            throw new MalformedRequestException("header block too large", 431);
        }
        // No line ending at all in more bytes than a request line may have -> 414.
        if (indexOfCrlf(0) < 0 && len > MAX_REQUEST_LINE_LENGTH) {
            throw new MalformedRequestException("request line too long", 414);
        }
        return -1; // just incomplete: wait for more bytes
    }

    private int indexOfCrlf(int from) {
        for (int i = from; i + 1 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Splits a head (which ends in CRLF) into lines, without the CRLFs. */
    private List<String> splitOnCrlf(String block) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = block.indexOf("\r\n", start)) >= 0) {
            lines.add(block.substring(start, idx));
            start = idx + 2;
        }
        return lines;
    }

    /**
     * Discards the first n bytes (one extracted request) and shifts the rest to
     * the front. Without this compaction a second request delivered in the same
     * read would be lost instead of waiting for the next extractRequest().
     */
    private void consume(int n) {
        int remaining = len - n;
        if (remaining > 0) {
            System.arraycopy(buf, n, buf, 0, remaining);
        }
        len = remaining;
        searchFrom = 0; // the next request starts its terminator scan from scratch
    }

    /** Doubles the array until it can hold `needed` bytes. */
    private void ensureCapacity(int needed) {
        if (needed <= buf.length) {
            return;
        }
        int newCap = buf.length * 2;
        while (newCap < needed) {
            newCap *= 2;
        }
        byte[] bigger = new byte[newCap];
        System.arraycopy(buf, 0, bigger, 0, len);
        buf = bigger;
    }
}