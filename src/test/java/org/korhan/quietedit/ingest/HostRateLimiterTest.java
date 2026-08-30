package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.korhan.quietedit.support.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The politeness contract, checked without real sleeping: the sleeper advances the
 * test clock, so every wait the limiter asks for is recorded and exact.
 */
class HostRateLimiterTest {

    private static final Instant START = Instant.parse("2026-08-23T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final List<Duration> waits = new ArrayList<>();

    @Test
    void firstRequestToAHostIsNotDelayed() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ofSeconds(2));

        assertThat(limiter.call("a.test", () -> "body")).isEqualTo("body");
        assertThat(waits).isEmpty();
    }

    @Test
    void secondRequestToTheSameHostWaitsOutTheMinimumInterval() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ofSeconds(2));

        limiter.call("a.test", () -> "first");
        limiter.call("a.test", () -> "second");

        assertThat(waits).containsExactly(Duration.ofSeconds(2));
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(2));
    }

    @Test
    void hostsAreThrottledIndependently() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ofSeconds(2));

        limiter.call("a.test", () -> "a");
        limiter.call("b.test", () -> "b");
        limiter.call("c.test", () -> "c");

        assertThat(waits).isEmpty();
    }

    /**
     * The interval is a gap between requests, not a rate: a response that itself
     * took longer than the interval must still be followed by a full gap.
     */
    @Test
    void theIntervalIsMeasuredFromTheEndOfThePreviousRequest() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ofSeconds(2));

        limiter.call("a.test", () -> {
            clock.advance(Duration.ofSeconds(30));
            return "slow";
        });
        limiter.call("a.test", () -> "next");

        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void oneRequestAtATimePerHost() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ZERO);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                executor.submit(() -> {
                    limiter.call("a.test", () -> {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inFlight.decrementAndGet();
                        return "done";
                    });
                    done.countDown();
                    return null;
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(peak.get()).isEqualTo(1);
    }

    /** Different hosts must not queue behind each other: the barrier only trips if they run together. */
    @Test
    void differentHostsRunConcurrently() throws Exception {
        HostRateLimiter limiter = limiter(Duration.ZERO);
        CyclicBarrier bothInFlight = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> limiter.call("a.test", () -> meet(bothInFlight)));
            var second = executor.submit(() -> limiter.call("b.test", () -> meet(bothInFlight)));

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("met");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("met");
        }
    }

    private static String meet(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return "met";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HostRateLimiter limiter(Duration minInterval) {
        FeedFetchProperties properties = new FeedFetchProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), minInterval, 3, Duration.ZERO, 2, "test");
        return new HostRateLimiter(properties, clock, duration -> {
            waits.add(duration);
            clock.advance(duration);
        });
    }
}
