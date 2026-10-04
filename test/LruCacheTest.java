import proxy.LruCache;
public class LruCacheTest {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    private static void expectThrows(Class<? extends Throwable> type,Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(message, error);
        }
        throw new AssertionError(message);
    }
    public static void main(String[] args) {
        LruCache<String, String> cache = new LruCache<>(2);
        check(cache.get("missing") == null, "A miss should return null");
        check(cache.put("A", "Page A") == null, "New key has no old value");
        cache.put("B", "Page B");
        check(cache.size() == 2, "Both entries should fit");
        check("Page A".equals(cache.get("A")), "A should be retrievable");
        cache.put("C", "Page C");
        check(!cache.containsKey("B"), "B should be evicted after accessing A");
        check(cache.containsKey("A") && cache.containsKey("C"), "A and C should remain");
        check(cache.size() == 2, "Eviction should preserve the capacity limit");
        check("Page A".equals(cache.put("A", "Updated A")),"Updating should return the previous value");
        check(cache.size() == 2, "Updating must not create another entry");
        cache.put("D", "Page D");
        check(!cache.containsKey("C"), "Updating A should make C oldest");
        check("Updated A".equals(cache.get("A")), "Updated value should be stored");
        check("Page D".equals(cache.remove("D")), "Removal should return its value");
        check(cache.remove("missing") == null, "Removing a missing key returns null");
        cache.clear();
        check(cache.size() == 0, "Clear should remove all entries");
        cache.put("A", "Page A");
        cache.put("B", "Page B");
        check(cache.containsKey("A"), "A should exist");
        cache.get("missing");
        cache.put("C", "Page C");
        check(!cache.containsKey("A"), "Membership checks must not change order");
        LruCache<Integer, String> single = new LruCache<>(1);
        single.put(1, "one");
        single.put(2, "two");
        check(single.size() == 1 && single.get(1) == null,
                "Capacity one should evict the previous entry");
        check("two".equals(single.get(2)), "Newest entry should remain");
        expectThrows(IllegalArgumentException.class,
                () -> new LruCache<String, String>(0), "Reject zero capacity");
        expectThrows(IllegalArgumentException.class,
                () -> new LruCache<String, String>(-1), "Reject negative capacity");
        expectThrows(NullPointerException.class,
                () -> cache.put(null, "value"), "Reject null keys");
        expectThrows(NullPointerException.class,
                () -> cache.put("key", null), "Reject null values");

        System.out.println("All LruCache tests passed.");
    }
}
