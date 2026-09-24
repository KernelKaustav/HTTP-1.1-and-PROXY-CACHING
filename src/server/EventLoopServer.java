package server;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
public class EventLoopServer {
    public static final int DEFAULT_PORT = 8081;
    private static final long SELECT_TIMEOUT_MS = 1000L;
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;
    private long acceptedCount = 0; 

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
            } else if (key.isReadable()) {
                handleReadPlaceholder(key);
            } else if (key.isWritable()) {
                System.out.println("[EventLoopServer] OP_WRITE ready - handler lands 02 Oct");
            }
        } catch (CancelledKeyException e) {
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
                client.register(selector, SelectionKey.OP_READ);
                acceptedCount++;
                System.out.println("[EventLoopServer] accepted " + client.getRemoteAddress()
                        + " (total accepted: " + acceptedCount + ")");
            } catch (IOException e) {
                System.err.println("[EventLoopServer] failed to register client: " + e.getMessage());
                closeQuietly(client);
            }
        }
    }
    private void handleReadPlaceholder(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer scratch = ByteBuffer.allocate(4096);
        int n = client.read(scratch);
        if (n == -1) {
            System.out.println("[EventLoopServer] client closed " + client.getRemoteAddress());
            closeKey(key);
        } else if (n > 0) {
            System.out.println("[EventLoopServer] OP_READ: " + n + " bytes (discarded until 28 Sep)");
        }
    }
    private void closeKey(SelectionKey key) {
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
        System.out.println("[EventLoopServer] stopped");
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