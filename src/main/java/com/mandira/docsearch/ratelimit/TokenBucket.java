package com.mandira.docsearch.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal token bucket, implemented directly rather than pulled in as a
 * library — the algorithm is simple enough that hand-rolling it keeps the
 * prototype dependency-light and keeps the limiting logic auditable at a
 * glance for a reviewer.
 *
 * One instance per tenant (see {@link RateLimitInterceptor}). Not safe
 * across multiple app instances — a production deployment would move the
 * counters to Redis (INCR + EXPIRE, or a Lua script for atomicity) so
 * limits are enforced cluster-wide rather than per-instance.
 */
public class TokenBucket {

    private final int capacity;
    private final int refillPerSecond;
    private final AtomicInteger tokens;
    private final AtomicLong lastRefillNanos;

    /**
     * Constructs a token bucket with the specified capacity and refill rate.
     *
     * Initializes the bucket to full capacity and records the current time
     * for tracking refill intervals.
     *
     * @param capacity        the maximum token capacity
     * @param refillPerSecond the number of tokens to add per second
     */
    public TokenBucket(int capacity, int refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = new AtomicInteger(capacity);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Attempts to consume one token from the bucket.
     *
     * Refills the bucket based on elapsed time since the last refill,
     * then checks if a token is available. Returns true and decrements
     * the token count if successful; returns false if the bucket is empty.
     *
     * Thread-safe through synchronization.
     *
     * @return true if a token was successfully consumed, false if bucket is empty
     */
    public synchronized boolean tryConsume() {
        refill();
        if (tokens.get() > 0) {
            tokens.decrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Refills the bucket based on elapsed time since the last refill.
     *
     * Calculates the number of tokens to add based on the elapsed nanoseconds
     * and the refill rate. Caps the total tokens at the maximum capacity and
     * updates the last refill timestamp if any tokens were added.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos.get();
        long tokensToAdd = (elapsedNanos * refillPerSecond) / 1_000_000_000L;
        if (tokensToAdd > 0) {
            tokens.set((int) Math.min(capacity, tokens.get() + tokensToAdd));
            lastRefillNanos.set(now);
        }
    }
}
