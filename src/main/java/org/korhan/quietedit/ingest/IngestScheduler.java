package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calls {@link IngestService#runOnce()} on a timer and holds no decision of its own, so
 * everything a run decides stays reachable and testable without a scheduler.
 *
 * <p>A fixed <em>delay</em>, not a fixed rate: the gap is measured from the end of one
 * run, so a poll that overruns the interval delays the next one instead of stacking a
 * second run on top of it.
 *
 * <p>Exceptions are deliberately not caught -- {@code runOnce()} contains its own
 * failures, and Spring logs and reschedules anything that escapes.
 *
 * <p>The one exception is a collision with a manually triggered run, which is an expected
 * outcome and would otherwise be logged as an error every time an operator polls by hand.
 */
@Component
@ConditionalOnProperty(prefix = "quietedit.ingest.schedule", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestService service;

    public IngestScheduler(IngestService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${quietedit.ingest.schedule.interval:15m}")
    void poll() {
        try {
            service.runOnce();
        } catch (IngestAlreadyRunningException e) {
            log.info("Scheduled ingest skipped: {}", e.getMessage());
        }
    }
}
