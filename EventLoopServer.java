package server;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
public class EventLoopServer {
    public static final int DEFAULT_PORT = 8081;
    private static final long SELECT_TIMEOUT_MS = 1000L;
    private static final long PAUSE_READS_ABOVE_BYTES = 1L << 20;   // 1 MiB
    private static final boolean VERBOSE = Boolean.getBoolean("eventloop.verbose");
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;
    private long acceptedCount = 0;
    private long partialWriteCount = 0;
    private long totalBytesWritten = 0;
    private final Map<SelectionKey, OutboundQueue> outbound = new HashMap<>();
    private final KeepAliveManager keepAlive;
    private final RequestHandler handler;
    public EventLoopServer(int port, RequestHandler handler) {
        this(port, KeepAliveManager.DEFAULT_IDLE_TIMEOUT_MS, handler);
    }
    public EventLoopServer(int port, long idleTimeoutMs, RequestHandler handler) {
        this.port = port;
        this.handler = handler;
        this.keepAlive = new KeepAliveManager(idleTimeoutMs, KeepAliveManager.DEFAULT_MAX_REQUESTS);
    }
    private static void log(String msg) {
        if (VERBOSE) {
            System.out.println(msg);
        }
    }
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        serverChannel.socket().setReuseAddress(true);
        serverChannel.socket().bind(new InetSocketAddress(port), 1024);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        System.out.println("[EventLoopServer] listening on port " + port
                + " (keep-alive idle timeout " + keepAlive.idleTimeoutMs() + " ms, max "
                + keepAlive.maxRequests() + " requests/connection)");
        loop();
    }
    private void loop() throws IOException {
        while (running) {
            selector.select(SELECT_TIMEOUT_MS);
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    continue;
                }
                dispatch(key);
            }
            closeIdleConnections();
        }
    }
    private void dispatch(SelectionKey key) {
        try {
            if (key.isAcceptable()) {
                handleAccept(key);
                return;
            }
            if (key.isValid() && key.isWritable()) {
                handleWrite(key);
            }
            if (key.isValid() && key.isReadable()) {
                handleRead(key);
            }
        } catch (CancelledKeyException e) {
            // key was cancelled while we were using it
        } catch (IOException | RuntimeException e) {
            log("[EventLoopServer] error, closing connection: " + e);
            closeKey(key);
        }
    }
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client;
        while ((client = server.accept()) != null) {
            try {
                client.configureBlocking(false);
                client.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ,
                        new ConnectionState(client));
                keepAlive.register(clientKey);
                acceptedCount++;
                log("[EventLoopServer] accepted " + client.getRemoteAddress()
                        + " (total accepted: " + acceptedCount + ")");
            } catch (IOException e) {
                System.err.println("[EventLoopServer] failed to register client: " + e.getMessage());
                closeQuietly(client);
            }
        }
    }
    private void handleRead(SelectionKey key) throws IOException {
        ConnectionState state = (ConnectionState) key.attachment();
        int n = state.readFromChannel();
        if (n > 0) {
            keepAlive.touch(key);
        }
        if (n == -1) {
            if (outbound.containsKey(key)) {
                keepAlive.markClosing(key);
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                closeKey(key);
            }
            return;
        }
        if (keepAlive.isClosing(key)) {
            return;
        }
        processInbound(key);
    }
    private long pendingBytes(SelectionKey key) {
        OutboundQueue q = outbound.get(key);
        return (q == null) ? 0 : q.pendingBytes();
    }
    private void processInbound(SelectionKey key) throws IOException {
        ConnectionState state = (ConnectionState) key.attachment();
        int headLength;
        while (key.isValid() && !keepAlive.isClosing(key)
                && pendingBytes(key) <= PAUSE_READS_ABOVE_BYTES
                && (headLength = state.findHeadEnd()) != -1) {
            byte[] head = state.consume(headLength);
            respondTo(key, head);
        }
        if (!key.isValid() || keepAlive.isClosing(key)) {
            return;
        }
        if (state.requestLineTooLong()) {
            rejectAndClose(key, 414, "request line too long");
        } else if (state.isInboundFull() && state.findHeadEnd() == -1) {
            rejectAndClose(key, 431, "request header fields too large");
        }
    }
    private void rejectAndClose(SelectionKey key, int status, String message) throws IOException {
        HttpResponse response = ResponseWriter.error(status, message);
        keepAlive.applyHeaders(response, false, 0);
        keepAlive.markClosing(key);
        queueResponse(key, ResponseWriter.serialize(response, false));
    }
    private void queueResponse(SelectionKey key, byte[] response) throws IOException {
        OutboundQueue queue = outbound.computeIfAbsent(key, k -> new OutboundQueue());
        queue.add(response);
        flush(key, queue);
    }
    private void handleWrite(SelectionKey key) throws IOException {
        OutboundQueue queue = outbound.get(key);
        if (queue == null) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            return;
        }
        flush(key, queue);
        if (key.isValid() && !outbound.containsKey(key) && !keepAlive.isClosing(key)) {
            processInbound(key);   
        }
    }
    private void flush(SelectionKey key, OutboundQueue queue) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        boolean wasWaitingForWrite = (key.interestOps() & SelectionKey.OP_WRITE) != 0;
        long written = queue.drainTo(channel);
        totalBytesWritten += written;
        if (written > 0) {
            keepAlive.touch(key);     
        }
        if (queue.isEmpty()) {
            outbound.remove(key);
            if (keepAlive.isClosing(key)) {
                log("[EventLoopServer] closing " + channel.getRemoteAddress()
                        + " after final response (Connection: close)");
                closeKey(key);
                return;
            }
            key.interestOps(SelectionKey.OP_READ);
            if (wasWaitingForWrite) {
                log("[EventLoopServer] write drained for " + channel.getRemoteAddress());
            }
            return;
        }
        int ops = key.interestOps() | SelectionKey.OP_WRITE;
        if (queue.pendingBytes() > PAUSE_READS_ABOVE_BYTES || keepAlive.isClosing(key)) {
            ops &= ~SelectionKey.OP_READ;
        }
        key.interestOps(ops);
        if (!wasWaitingForWrite) {
            partialWriteCount++;
            log("[EventLoopServer] partial write to " + channel.getRemoteAddress() + ": "
                    + queue.pendingBytes() + " bytes pending - OP_WRITE registered"
                    + " (partial writes so far: " + partialWriteCount + ")");
        }
    }
    private void respondTo(SelectionKey key, byte[] head) throws IOException {
        int served = keepAlive.recordRequest(key);
        HttpResponse response;
        boolean persistent;
        boolean headOnly = false;
        try {
            HttpRequest request = RequestParser.parseFromHeadBytes(head);
            KeepAliveManager.checkBodyFraming(request);      
            headOnly = request.method() == HttpMethod.HEAD;
            response = invokeHandler(request);
            persistent = KeepAliveManager.wantsKeepAlive(request)
                    && !keepAlive.isRequestQuotaReached(served);
        } catch (MalformedRequestException e) {
            response = ResponseWriter.error(e.statusCode(), e.getMessage());
            persistent = false;
        }
        keepAlive.applyHeaders(response, persistent, served);
        if (!persistent) {
            keepAlive.markClosing(key);
        }
        queueResponse(key, ResponseWriter.serialize(response, headOnly));
    }
    private HttpResponse invokeHandler(HttpRequest request) {
        HttpMethod method = request.method();
        if (method == null) {
            return ResponseWriter.error(400, "unrecognised method: " + request.rawMethodToken());
        }
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return ResponseWriter.error(405, "Method Not Allowed").header("Allow", "GET, HEAD");
        }
        try {
            return handler.handle(request);
        } catch (IOException | RuntimeException e) {
            log("[EventLoopServer] handler failed: " + e);
            return ResponseWriter.error(500, "Internal Server Error");
        }
    }
    private void closeIdleConnections() {
        for (SelectionKey key : keepAlive.collectExpired()) {
            log("[EventLoopServer] idle timeout (" + keepAlive.idleTimeoutMs()
                    + " ms) - closing " + describe(key));
            closeKey(key);
        }
    }
    private String describe(SelectionKey key) {
        try {
            return String.valueOf(((SocketChannel) key.channel()).getRemoteAddress());
        } catch (IOException e) {
            return "(unknown peer)";
        }
    }
    private void closeKey(SelectionKey key) {
        outbound.remove(key);
        keepAlive.remove(key);
        key.cancel();
        closeQuietly(key.channel());
    }
    private void closeQuietly(java.nio.channels.Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
    private void closeQuietly() {
        if (selector != null && selector.isOpen()) {
            for (SelectionKey k : selector.keys()) {
                closeQuietly(k.channel());
            }
        }
        outbound.clear();
        keepAlive.clear();
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
        }
        System.out.println("[EventLoopServer] stopped (bytes written: " + totalBytesWritten
                + ", partial writes: " + partialWriteCount + ")");
    }
    private static final class OutboundQueue {
        private final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<>();
        private long pendingBytes = 0;

        void add(byte[] data) {
            buffers.addLast(ByteBuffer.wrap(data));
            pendingBytes += data.length;
        }
        boolean isEmpty() {
            return buffers.isEmpty();
        }
        long pendingBytes() {
            return pendingBytes;
        }
        long drainTo(SocketChannel channel) throws IOException {
            long total = 0;
            while (!buffers.isEmpty()) {
                ByteBuffer head = buffers.peekFirst();
                int n = channel.write(head);   
                total += n;
                pendingBytes -= n;
                if (head.hasRemaining()) {
                    break;                     
                }
                buffers.removeFirst();
            }
            return total;
        }
    }
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + " - using " + DEFAULT_PORT);
            }
        }
        long idleTimeoutMs = KeepAliveManager.DEFAULT_IDLE_TIMEOUT_MS;
        if (args.length > 1) {
            try {
                idleTimeoutMs = Long.parseLong(args[1]) * 1000L;
                if (idleTimeoutMs <= 0) {
                    throw new NumberFormatException("must be > 0");
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid idle timeout (seconds): " + args[1] + " - using "
                        + KeepAliveManager.DEFAULT_IDLE_TIMEOUT_MS / 1000);
                idleTimeoutMs = KeepAliveManager.DEFAULT_IDLE_TIMEOUT_MS;
            }
        }
        String docRoot = args.length > 2 ? args[2] : "test-site";   // same default as ThreadPoolServer
        RequestHandler handler;
        try {
            handler = new StaticFileHandler(docRoot);
        } catch (IllegalArgumentException e) {
            System.err.println("[EventLoopServer] " + e.getMessage());
            return;
        }
        EventLoopServer server = new EventLoopServer(port, idleTimeoutMs, handler);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("[EventLoopServer] fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.closeQuietly();
        }
    }
}
