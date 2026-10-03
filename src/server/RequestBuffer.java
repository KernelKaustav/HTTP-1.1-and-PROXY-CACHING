package server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection buffering state machine for the non-blocking event loop.
 *
 * A non-blocking channel's read() returns whatever bytes the kernel
 * happens to have right now — that could be 3 bytes of a header line
 * (partial arrival), or it could be two or three complete requests back
 * to back if the client pipelined them and the kernel delivered them in
 * one chunk (pipelining). Either way, OP_READ fires once and the
 * callback must return quickly — it can't block waiting for "the rest"
 * the way RequestParser.parse(InputStream) can on a blocking socket.
 *
 * Usage, one RequestBuffer per connection, kept alive across OP_READ
 * events for that connection's lifetime:
 *
 *   RequestBuffer rb = new RequestBuffer();
 *   // on each OP_READ:
 *   byte[] chunk = ...;               // bytes just read from the channel
 *   int n = ...;                      // how many of them are valid
 *   rb.append(chunk, 0, n);
 *   while (rb.hasCompleteRequest()) {
 *       HttpRequest req = rb.extractRequest();
 *       // dispatch req, write a response ...
 *   }
 *   // loop condition handles both partial arrival (loop body never runs
 *   // until enough bytes accumulate across several OP_READ events) and
 *   // pipelining (loop body runs more than once per OP_READ if this
 *   // read's bytes contained several complete requests).
 *
 * Only parses the start line + headers, same scope as RequestParser for
 * now — request bodies are a separate, later piece of work.
 */
public final class RequestBuffer {

    private static final int INITIAL_CAPACITY = 4096;
    private static final int MAX_REQUEST_LINE_LENGTH = 8192;  // -> 414
    private static final int MAX_HEADER_BLOCK_LENGTH = 16384; // -> 431, covers start line + all headers
    private static final int MAX_HEADER_COUNT = 100;          // -> 431

    private byte[] buf = new byte[INITIAL_CAPACITY];
    private int len = 0;        // buf[0..len) = bytes received but not yet consumed by extractRequest()
    private int searchFrom = 0; // how far we've already scanned for the terminator without finding it

    /** Feeds newly-read bytes into the buffer. Safe to call with length 0 (a read that returned nothing new). */
    public void append(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        ensureCapacity(len + length);
        System.arraycopy(data, offset, buf, len, length);
        len += length;
    }

    /**
     * True if the buffer currently holds at least one complete request
     * (start line + headers, terminated by a blank line). Does not
     * consume anything, so it's safe to call repeatedly — this is what
     * lets a caller loop "while (hasCompleteRequest()) extractRequest()"
     * to drain every pipelined request a single read delivered.
     *
     * @throws MalformedRequestException if the buffered bytes already
     *         violate a size limit (too-long request line, too-large
     *         header block) even before a terminator has been found —
     *         e.g. for a slow client trickling bytes that will never
     *         total to a valid request, we must not just grow the
     *         buffer forever waiting for one.
     */
    public boolean hasCompleteRequest() throws MalformedRequestException {
        return findHeaderBlockEnd() >= 0;
    }

    /**
     * Parses and removes exactly one complete request's bytes from the
     * front of the buffer. Call only after hasCompleteRequest() returned
     * true for this exact state (don't call speculatively). Any leftover
     * bytes — a second pipelined request, complete or partial — remain
     * buffered for the next hasCompleteRequest()/extractRequest() pair.
     */
    public HttpRequest extractRequest() throws MalformedRequestException {
        int blockEnd = findHeaderBlockEnd();
        if (blockEnd < 0) {
            throw new IllegalStateException(
                "extractRequest() called with no complete request buffered — check hasCompleteRequest() first");
        }

        String block = new String(buf, 0, blockEnd, StandardCharsets.ISO_8859_1);
        List<String> lines = splitOnCrlf(block);
        if (lines.isEmpty()) {
            throw new MalformedRequestException("empty request line", 400);
        }

        RequestParser.StartLine startLine = RequestParser.parseStartLineText(lines.get(0));

        HttpHeaders headers = new HttpHeaders();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue; // the blank line that terminates the header block
            }
            if (headers.size() >= MAX_HEADER_COUNT) {
                throw new MalformedRequestException("too many headers", 431);
            }
            RequestParser.addHeaderLine(line, headers);
        }

        consume(blockEnd);

        return new HttpRequest(startLine.method, startLine.rawMethodToken, startLine.target,
                                startLine.version, headers.asMap());
    }

    // -- internal plumbing --

    /**
     * Index just past the terminating "\r\n\r\n" if the buffer contains
     * one, else -1. Remembers how far it already scanned (searchFrom) so
     * repeated calls across many small appends don't rescan from byte 0
     * every time — important since the event loop may call this after
     * every single OP_READ on a slow connection.
     */
    private int findHeaderBlockEnd() throws MalformedRequestException {
        for (int i = searchFrom; i + 4 <= len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
        }
        // Not found yet. Remember that everything up to the last 3 bytes
        // has been checked — a terminator straddling the old/new boundary
        // is the only thing the next append() could introduce there.
        searchFrom = Math.max(0, len - 3);

        if (len > MAX_HEADER_BLOCK_LENGTH) {
            throw new MalformedRequestException("header block too large", 431);
        }
        if (indexOfCrlf(0) < 0 && len > MAX_REQUEST_LINE_LENGTH) {
            throw new MalformedRequestException("request line too long", 414);
        }
        return -1;
    }

    private int indexOfCrlf(int from) {
        for (int i = from; i + 1 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

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
     * Discards the first n bytes (one fully-extracted request) and
     * shifts any remainder — e.g. the start of the next pipelined
     * request — to the front of the buffer. This is the piece that
     * makes pipelining work: without compaction, a second request
     * delivered in the same read as the first would be silently
     * dropped instead of staying buffered for the next extractRequest().
     */
    private void consume(int n) {
        int remaining = len - n;
        if (remaining > 0) {
            System.arraycopy(buf, n, buf, 0, remaining);
        }
        len = remaining;
        searchFrom = 0; // next request starts its terminator scan fresh
    }

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