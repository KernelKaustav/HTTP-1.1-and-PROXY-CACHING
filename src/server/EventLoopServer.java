package server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
public class EventLoopServer {
    public static final int DEFAULT_PORT = 8081;
    private static final long SELECT_TIMEOUT_MS = 1000L;
    private static final long PAUSE_READS_ABOVE_BYTES = 1L << 20;   // 1 MiB
    private static final int MAX_TEST_BODY_BYTES = 16 * 1024 * 1024;
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;
    private long acceptedCount = 0;
    private long partialWriteCount = 0;
    private long totalBytesWritten = 0;
    private final Map<SelectionKey, OutboundQueue> outbound = new HashMap<>();
    public EventLoopServer(int port) {
        this.port = port;
    }
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        serverChannel.socket().setReuseAddress(true);
        serverChannel.socket().bind(new InetSocketAddress(port), 1024);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        System.out.println("[EventLoopServer] listening on port " + port);
        loop();
    }
    private void loop() throws IOException {
        while (running) {
            int ready = selector.select(SELECT_TIMEOUT_MS);
            if (ready == 0) {
                continue;
            }
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
        } catch (IOException e) {
            System.err.println("[EventLoopServer] I/O error, closing connection: " + e.getMessage());
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
                client.register(selector, SelectionKey.OP_READ, new ConnectionState(client));
                acceptedCount++;
                System.out.println("[EventLoopServer] accepted " + client.getRemoteAddress()
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
        if (n == -1) {
            System.out.println("[EventLoopServer] client closed " + state.channel().getRemoteAddress()
                    + " (" + state.requestsSeen() + " request(s), " + state.totalBytesRead() + " bytes)");
            closeKey(key);
            return;
        }
        if (n == 0 && !state.isInboundFull()) {
            return;
        }
        int headLength;
        while ((headLength = state.findHeadEnd()) != -1) {
            byte[] head = state.consume(headLength);
            System.out.println("[EventLoopServer] request head #" + state.requestsSeen()
                    + " complete (" + head.length + " bytes)");
            queueResponse(key, buildTestResponse(head));
        }
        if (state.isInboundFull()) {
            System.err.println("[EventLoopServer] request head exceeds " + ConnectionState.MAX_HEAD_BYTES
                    + " bytes without terminating - closing connection");
            closeKey(key);
        }
    }
    private void queueResponse(SelectionKey key, byte[] response) throws IOException {
        OutboundQueue queue = outbound.computeIfAbsent(key, k -> new OutboundQueue());
        queue.add(response);
        flush(key, queue);
    }
    private void handleWrite(SelectionKey key) throws IOException {
        OutboundQueue queue = outbound.get(key);
        if (queue == null) {
            // Nothing to send 
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            return;
        }
        flush(key, queue);
    }
    private void flush(SelectionKey key, OutboundQueue queue) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        boolean wasWaitingForWrite = (key.interestOps() & SelectionKey.OP_WRITE) != 0;
        totalBytesWritten += queue.drainTo(channel);
        if (queue.isEmpty()) {
            outbound.remove(key);
            key.interestOps(SelectionKey.OP_READ);
            if (wasWaitingForWrite) {
                System.out.println("[EventLoopServer] write drained for " + channel.getRemoteAddress());
            }
            return;
        }
        int ops = key.interestOps() | SelectionKey.OP_WRITE;
        if (queue.pendingBytes() > PAUSE_READS_ABOVE_BYTES) {
            ops &= ~SelectionKey.OP_READ;
        }
        key.interestOps(ops);
        if (!wasWaitingForWrite) {
            partialWriteCount++;
            System.out.println("[EventLoopServer] partial write to " + channel.getRemoteAddress() + ": "
                    + queue.pendingBytes() + " bytes pending - OP_WRITE registered"
                    + " (partial writes so far: " + partialWriteCount + ")");
        }
    }
    private byte[] buildTestResponse(byte[] head) throws IOException {
        String requestLine = new String(head, StandardCharsets.ISO_8859_1).split("\r?\n", 2)[0];
        String[] parts = requestLine.split(" ");
        HttpResponse response = new HttpResponse().header("Content-Type", "text/plain");
        if (parts.length != 3) {
            response.status(400).body("Bad Request\n");
        } else if (parts[1].startsWith("/big/")) {
            int size;
            try {
                size = Integer.parseInt(parts[1].substring(5));
            } catch (NumberFormatException e) {
                size = 0;
            }
            size = Math.max(0, Math.min(size, MAX_TEST_BODY_BYTES));
            byte[] body = new byte[size];
            Arrays.fill(body, (byte) 'x');
            response.status(200).body(body);
        } else {
            response.status(200).body("event-loop placeholder response\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.writeTo(out);
        return out.toByteArray();
    }
    private void closeKey(SelectionKey key) {
        outbound.remove(key);
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
                int n = channel.write(head);   // may write only part of it or 0
                total += n;
                pendingBytes -= n;
                if (head.hasRemaining()) {
                    break;                     // kernel send buffer is full 
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
        EventLoopServer server = new EventLoopServer(port);
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