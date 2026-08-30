package org.korhan.quietedit.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises outbound requests per host and spaces them by a minimum interval.
 *
 * <p>The lock is held for the whole action, not just for the wait: that is what
 * makes "at most one concurrent request per host" true. The next slot is stamped
 * <em>after</em> the action returns, so a slow response pushes the following
 * request out rather than letting it fire immediately -- the interval is meant as
 * a gap between requests, not as a fixed rate we owe the server.
 *
 * <p>The interval is a parameter of the call rather than of the limiter, so that a
 * host asking for a longer gap in robots.txt gets it without a second limiter.
 *
 * <p>Gates are never evicted. The key space is the set of hosts in the feed
 * catalogue, so it is bounded by configuration, not by traffic.
 */
@Component
public class HostRateLimiter {

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration defaultMinInterval;
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();

    public HostRateLimiter(FeedFetchProperties properties, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.defaultMinInterval = properties.minHostInterval();
    }

    public <T> T call(String host, HostAction<T> action) throws IOException, InterruptedException {
        return call(host, defaultMinInterval, action);
    }

    /**
     * Same gate, but with the interval decided by the caller. Article fetching needs
     * that: a host's {@code Crawl-delay} may ask for more than our configured
     * default, and honouring it per host is the whole point of reading robots.txt.
     */
    public <T> T call(String host, Duration minInterval, HostAction<T> action)
            throws IOException, InterruptedException {
        Gate gate = gates.computeIfAbsent(host, key -> new Gate());
        gate.lock.lockInterruptibly();
        try {
            Duration wait = Duration.between(clock.instant(), gate.nextAllowedAt);
            if (wait.isPositive()) {
                sleeper.sleep(wait);
            }
            try {
                return action.run();
            } finally {
                gate.nextAllowedAt = clock.instant().plus(minInterval);
            }
        } finally {
            gate.lock.unlock();
        }
    }

    /** The guarded work. Narrower than {@code Callable} so callers keep typed catches. */
    public interface HostAction<T> {
        T run() throws IOException, InterruptedException;
    }

    private static final class Gate {
        private final ReentrantLock lock = new ReentrantLock(true);
        private volatile Instant nextAllowedAt = Instant.EPOCH;
    }
}
