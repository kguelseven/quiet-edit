package org.korhan.quietedit.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Test clock that only moves when a test moves it, so waits become assertable. */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now) {
        this(now, ZoneId.of("UTC"));
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }
}
