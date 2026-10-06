package server;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
public final class KeepAliveManager {
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 5_000L;
    public static final int DEFAULT_MAX_REQUESTS = 100;

    private final long idleTimeoutMs;
    private final int maxRequests;

    private final LinkedHashMap<SelectionKey, Entry> entries = new LinkedHashMap<>();
    private long expiredCount = 0;

    private static final class Entry {
        long lastActiveMillis;
        int requestsServed = 0;
        boolean closing = false;   // final response queued; close once it is flushed
        Entry(long now) {
            this.lastActiveMillis = now;
        }
    }
    public KeepAliveManager() {
        this(DEFAULT_IDLE_TIMEOUT_MS, DEFAULT_MAX_REQUESTS);
    }
    public KeepAliveManager(long idleTimeoutMs, int maxRequests) {
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be > 0");
        }
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0");
        }
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxRequests = maxRequests;
    }
    public void register(SelectionKey key) {
        entries.put(key, new Entry(System.currentTimeMillis()));
    }
    public void remove(SelectionKey key) {
        entries.remove(key);
    }
    public void clear() {
        entries.clear();
    }
    public void touch(SelectionKey key) {
        Entry e = entries.remove(key);
        if (e == null) {
            return;
        }
        e.lastActiveMillis = System.currentTimeMillis();
        entries.put(key, e);   // re-inserted at the back = most recently active
    }
    public int recordRequest(SelectionKey key) {
        Entry e = entries.get(key);
        return (e == null) ? 1 : ++e.requestsServed;
    }
    public static boolean wantsKeepAlive(HttpRequest request) {
        String connection = request.header("connection");
        boolean hasClose = false;
        boolean hasKeepAlive = false;
        if (connection != null) {
            for (String token : connection.split(",")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.equals("close")) {
                    hasClose = true;
                } else if (t.equals("keep-alive")) {
                    hasKeepAlive = true;
                }
            }
        }
        if (hasClose) {
            return false;                       
        }
        if ("HTTP/1.1".equals(request.httpVersion())) {
            return true;                        
        }
        return hasKeepAlive;                    
    }
    public static void checkBodyFraming(HttpRequest request) throws MalformedRequestException {
        if (request.header("transfer-encoding") != null) {
            throw new MalformedRequestException("Transfer-Encoding request bodies are not supported", 501);
        }
        String cl = request.header("content-length");
        if (cl == null) {
            return;
        }
        String v = cl.trim();
        if (!v.matches("[0-9]{1,18}")) {          // also rejects "5, 5" (duplicate header)
            throw new MalformedRequestException("invalid Content-Length", 400);
        }
        if (!v.matches("0+")) {
            throw new MalformedRequestException("request bodies are not supported", 413);
        }
    }
    public boolean isRequestQuotaReached(int served) {
        return served >= maxRequests;
    }
    public void applyHeaders(HttpResponse response, boolean persistent, int served) {
        if (persistent) {
            int remaining = Math.max(0, maxRequests - served);
            response.header("Connection", "keep-alive");
            response.header("Keep-Alive", "timeout=" + (idleTimeoutMs / 1000) + ", max=" + remaining);
        } else {
            response.header("Connection", "close");
        }
    }
    public void markClosing(SelectionKey key) {
        Entry e = entries.get(key);
        if (e != null) {
            e.closing = true;
        }
    }
    public boolean isClosing(SelectionKey key) {
        Entry e = entries.get(key);
        return e != null && e.closing;
    }
    public List<SelectionKey> collectExpired() {
        long cutoff = System.currentTimeMillis() - idleTimeoutMs;
        List<SelectionKey> expired = null;
        Iterator<Map.Entry<SelectionKey, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SelectionKey, Entry> me = it.next();
            if (me.getValue().lastActiveMillis > cutoff) {
                break;                         
            }
            if (expired == null) {
                expired = new ArrayList<>();
            }
            expired.add(me.getKey());
            it.remove();
            expiredCount++;
        }
        return (expired == null) ? java.util.Collections.emptyList() : expired;
    }
    public long idleTimeoutMs() {
        return idleTimeoutMs;
    }
    public int maxRequests() {
        return maxRequests;
    }
    public int trackedConnections() {
        return entries.size();
    }
    public long expiredCount() {
        return expiredCount;
    }
}