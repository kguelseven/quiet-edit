package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * When a known article is fetched again. Separate from {@link IngestRunProperties}
 * because that one caps how much of the catalogue <em>one run</em> may work through,
 * while these say which candidates are worth offering it in the first place.
 *
 * <p>Every number's justification is in {@link RecheckPolicy}, next to the code that
 * acts on it. Two of them are worth repeating here because they are the ones an
 * operator would reach for: {@code ageFactor} is the whole curve -- a candidate is
 * re-checked after that fraction of its age -- and {@code maxRequestsPerHostPerHour}
 * is the only setting that can refuse work the curve generates.
 *
 * <p>{@code maxCandidatesPerRun} is not a policy number: it is the ceiling on how
 * many stored documents a run reads back to ask the policy about. It exists so that a
 * backlog cannot turn one query into a full table scan into memory. Reading them
 * most-overdue-first means a cut here delays a candidate by a run rather than losing
 * it, and it is deliberately far above {@code quietedit.ingest.run.max-articles},
 * because the policy will say no to many of the rows it sees.
 */
@ConfigurationProperties("quietedit.ingest.recheck")
public record RecheckProperties(
        @DefaultValue("10m") Duration minInterval,
        @DefaultValue("12h") Duration maxInterval,
        @DefaultValue("0.25") double ageFactor,
        @DefaultValue("7d") Duration observationWindow,
        @DefaultValue("4") int maxWindowFactor,
        @DefaultValue("120") int maxRequestsPerHostPerHour,
        @DefaultValue("500") int maxCandidatesPerRun) {

    public RecheckProperties {
        if (maxCandidatesPerRun < 1) {
            throw new IllegalArgumentException("quietedit.ingest.recheck.max-candidates-per-run must be >= 1");
        }
    }

    /**
     * The policy these settings describe. Its compact constructor is what validates
     * the rest: the invariants belong to the rule, not to the configuration format,
     * and stating them twice would let the two drift apart.
     */
    public RecheckPolicy toPolicy() {
        return new RecheckPolicy(minInterval, maxInterval, ageFactor, observationWindow,
                maxWindowFactor, maxRequestsPerHostPerHour);
    }
}
