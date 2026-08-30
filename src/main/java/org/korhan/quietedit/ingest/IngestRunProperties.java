package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What a single run is allowed to do. Separate from
 * {@link IngestScheduleProperties} because that one says when a run starts and
 * this one says how much of the catalogue it may work through once it has.
 *
 * <p>{@code maxArticles} is the ceiling on article fetches per run. It exists
 * because the per-host gate spaces same-host requests, so an unbounded first run
 * over a real catalogue -- roughly feeds times entries -- takes hours, and the
 * scheduler's fixed delay only starts counting once it finally ends. The default
 * of 50 is a starting point, not a measurement: with a two second host interval it
 * keeps a run in the low minutes even when every article sits on one host.
 */
@ConfigurationProperties("quietedit.ingest.run")
public record IngestRunProperties(@DefaultValue("50") int maxArticles) {

    public IngestRunProperties {
        if (maxArticles < 1) {
            throw new IllegalArgumentException("quietedit.ingest.run.max-articles must be >= 1");
        }
    }
}
