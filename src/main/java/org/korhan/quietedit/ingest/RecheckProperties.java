package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * When a known article is fetched again. Separate from {@link IngestRunProperties}
 * because that one caps how much of the catalogue one run may work through, while these
 * say which candidates are worth offering it at all.
 *
 * <p>Every number's justification is in {@link RecheckPolicy}, next to the code that acts
 * on it.
 *
 * <p>{@code maxCandidatesPerRun} is not a policy number: it caps how many stored
 * documents a run reads back, so that a backlog cannot turn one query into a full table
 * scan into memory. Most-overdue-first means a cut here delays a candidate by a run
 * rather than losing it.
 */
@ConfigurationProperties("quietedit.ingest.recheck")
public record RecheckProperties(
        @DefaultValue("10m") Duration minInterval,
        @DefaultValue("12h") Duration maxInterval,
        @DefaultValue("0.25") double ageFactor,
        @DefaultValue("7d") Duration observationWindow,
        @DefaultValue("4") int maxWindowFactor,
        @DefaultValue("120") int maxRequestsPerHostPerHour,
        @DefaultValue("20") int maxUnconfirmedUpdatedClaims,
        @DefaultValue("500") int maxCandidatesPerRun) {

    public RecheckProperties {
        if (maxCandidatesPerRun < 1) {
            throw new IllegalArgumentException("quietedit.ingest.recheck.max-candidates-per-run must be >= 1");
        }
    }

    /**
     * The policy's compact constructor is what validates these settings: the invariants
     * belong to the rule, not to the configuration format, and stating them twice would let
     * the two drift apart.
     */
    public RecheckPolicy toPolicy() {
        return new RecheckPolicy(minInterval, maxInterval, ageFactor, observationWindow,
                maxWindowFactor, maxRequestsPerHostPerHour, maxUnconfirmedUpdatedClaims);
    }
}
