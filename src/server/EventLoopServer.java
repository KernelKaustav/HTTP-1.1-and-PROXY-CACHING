package server;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import java.util.Set;
public class EventLoopServer {
    public static final int DEFAULT_PORT = 8081;
    private static final long SELECT_TIMEOUT_MS = 1000L;

    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;

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
        if (key.isAcceptable()) {
            System.out.println("[EventLoopServer] OP_ACCEPT ready - handler lands 24 Sep");
        } else if (key.isReadable()) {
            System.out.println("[EventLoopServer] OP_READ ready - handler lands 28 Sep");
        } else if (key.isWritable()) {
            System.out.println("[EventLoopServer] OP_WRITE ready - handler lands 02 Oct");
        }
    }
    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
    private void closeQuietly() {
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.            
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