package server;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
public final class ConnectionState {
    public static final int MAX_REQUEST_LINE_BYTES = 8192;
    public static final int MAX_HEAD_BYTES = 8192 + 16384;
    private final SocketChannel channel;
    private final ByteBuffer inbound = ByteBuffer.allocate(MAX_HEAD_BYTES);
    private int scanFrom = 0;

    private long totalBytesRead = 0;
    private int requestsSeen = 0;
    private long lastActivityMillis = System.currentTimeMillis();
    public ConnectionState(SocketChannel channel) {
        this.channel = channel;
    }
    public int readFromChannel() throws IOException {
        int n = channel.read(inbound);
        if (n > 0) {
            totalBytesRead += n;
            lastActivityMillis = System.currentTimeMillis();
        }
        return n;
    }
    public int findHeadEnd() {
        int end = inbound.position();
        int i = Math.max(0, scanFrom - 3);
        for (; i < end; i++) {
            byte b = inbound.get(i);
            if (b != '\n') {
                continue;
            }
            if (i >= 1 && inbound.get(i - 1) == '\n') {
                return i + 1;                                   
            }
            if (i >= 3 && inbound.get(i - 1) == '\r' && inbound.get(i - 2) == '\n'
                    && inbound.get(i - 3) == '\r') {
                return i + 1;                                   
            }
        }
        scanFrom = end;
        return -1;
    }
    public byte[] consume(int length) {
        byte[] out = new byte[length];
        inbound.flip();          // write mode -> read mode
        inbound.get(out);        // take the head
        inbound.compact();       // keep the rest, back to write mode
        scanFrom = 0;
        requestsSeen++;
        return out;
    }
    public boolean requestLineTooLong() {
        int end = inbound.position();
        for (int i = 0; i < end; i++) {
            if (inbound.get(i) == '\n') {
                return false;
            }
        }
        return end > MAX_REQUEST_LINE_BYTES;
    }
    public boolean isInboundFull() {
        return !inbound.hasRemaining();
    }
    public int bufferedBytes() {
        return inbound.position();
    }
    public long totalBytesRead() {
        return totalBytesRead;
    }
    public int requestsSeen() {
        return requestsSeen;
    }
    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public SocketChannel channel() {
        return channel;
    }
}