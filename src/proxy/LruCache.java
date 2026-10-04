package proxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> entries;
    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "Capacity must be positive"
            );
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<K, V> eldest
            ) {
                return size() > LruCache.this.capacity;
            }
        };
    }
    public synchronized V get(K key) {
        return entries.get(
                Objects.requireNonNull(key, "key")
        );
    }
    public synchronized V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        return entries.put(key, value);
    }
    public synchronized boolean containsKey(K key) {
        return entries.containsKey(
                Objects.requireNonNull(key, "key")
        );
    }
    public synchronized V remove(K key) {
        return entries.remove(
                Objects.requireNonNull(key, "key")
        );
    }
    public synchronized int size() {
        return entries.size();
    }
    public synchronized void clear() {
        entries.clear();
    }
}