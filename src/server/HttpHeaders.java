package server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public final class HttpHeaders {

    private final Map<String, String> byLowerName = new LinkedHashMap<>();

    
    public void add(String name, String value) {
        String key = name.toLowerCase();
        String existing = byLowerName.get(key);
        if (existing != null) {
            byLowerName.put(key, existing + ", " + value);
        } else {
            byLowerName.put(key, value);
        }
    }

    /** Case-insensitive lookup. Returns null if the header isn't present. */
    public String get(String name) {
        return byLowerName.get(name.toLowerCase());
    }

    public boolean contains(String name) {
        return byLowerName.containsKey(name.toLowerCase());
    }

    public int size() {
        return byLowerName.size();
    }

    
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byLowerName));
    }

    @Override
    public String toString() {
        return byLowerName.toString();
    }
}