package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * When the scheduler polls. Kept apart from the fetch and article properties
 * because it is the only setting that says anything about <em>us</em> rather than
 * about how we treat a publisher.
 *
 * <p>{@code enabled} exists so tests can switch the timer off while still wiring
 * the same application context: a run that fires on its own would race the run a
 * test triggers deliberately.
 */
@ConfigurationProperties("quietedit.ingest.schedule")
public record IngestScheduleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15m") Duration interval) {

    public IngestScheduleProperties {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("quietedit.ingest.schedule.interval must be positive");
        }
    }
}
