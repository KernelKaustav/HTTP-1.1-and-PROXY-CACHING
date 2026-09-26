package server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A case-insensitive HTTP header container.
 *
 * RFC 7230 §3.2 says header field names are case-insensitive ("Host",
 * "host", "HOST" all name the same field) and §3.2.2 says a field that
 * appears more than once is equivalent to one field containing all the
 * values joined with a comma. This class exists so that logic lives in
 * exactly one place instead of every caller remembering to
 * .toLowerCase() before every lookup.
 *
 * Internally header names are stored lowercased. Values for a repeated
 * header are combined with ", " at insertion time (add()), matching
 * RFC 7230 semantics, so callers never see the same header name twice.
 */
public final class HttpHeaders {

    private final Map<String, String> byLowerName = new LinkedHashMap<>();

    /**
     * Adds a header value. If a header with this name (case-insensitive)
     * already exists, the new value is appended to the existing one with
     * ", " per RFC 7230 §3.2.2, rather than overwriting it.
     */
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

    /**
     * View as a plain Map<String,String> (lowercased keys), for callers
     * (like HttpRequest today) that were built against that shape before
     * this class existed. Unmodifiable — callers should go through add()
     * to change anything, not mutate the map directly.
     */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byLowerName));
    }

    @Override
    public String toString() {
        return byLowerName.toString();
    }
}