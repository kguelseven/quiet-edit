package org.korhan.quietedit.ingest;

import java.time.Duration;

/**
 * Indirection over {@code Thread.sleep} so that rate limiting and retry backoff
 * stay unit-testable: real sleeps would make those tests slow and flaky.
 */
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper system() {
        return duration -> {
            if (!duration.isNegative() && !duration.isZero()) {
                Thread.sleep(duration);
            }
        };
    }
}
