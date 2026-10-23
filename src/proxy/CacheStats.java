package proxy;

import java.util.concurrent.atomic.LongAdder;

public final class CacheStats {
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public void recordHit() {
        hits.increment();
    }

    public void recordMiss() {
        misses.increment();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long requests() {
        return hits() + misses();
    }

    public double hitRate() {
        long requests = requests();
        return requests == 0 ? 0.0 : (double) hits() / requests;
    }

    public void reset() {
        hits.reset();
        misses.reset();
    }
}
