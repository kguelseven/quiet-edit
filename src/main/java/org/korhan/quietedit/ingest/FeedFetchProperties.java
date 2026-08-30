package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Politeness and retry budget for outbound feed requests.
 *
 * <p>{@code minHostInterval} is per host, not per feed: several feeds of one
 * publisher share an origin, and that origin is what rate-limits us.
 *
 * <p>{@code maxAttempts} counts the first try, so 3 means "one try plus two
 * retries" -- the ticket's three attempts, not four.
 */
@ConfigurationProperties("quietedit.ingest.fetch")
public record FeedFetchProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration requestTimeout,
        @DefaultValue("2s") Duration minHostInterval,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("2") int backoffMultiplier,
        @DefaultValue("quietedit/0.1 (+https://github.com/kguelseven/quietedit)") String userAgent) {

    public FeedFetchProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("quietedit.ingest.fetch.max-attempts must be >= 1");
        }
        if (backoffMultiplier < 1) {
            throw new IllegalArgumentException("quietedit.ingest.fetch.backoff-multiplier must be >= 1");
        }
    }
}
