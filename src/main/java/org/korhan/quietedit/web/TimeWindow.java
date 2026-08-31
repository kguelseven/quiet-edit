package org.korhan.quietedit.web;

import java.time.Duration;
import java.time.Instant;

/**
 * How far back the listing looks. A fixed set of windows rather than a free-form
 * duration: the interface offers them as a dropdown, an unparseable duration in a URL
 * has no useful answer, and binding an enum makes a wrong value a 400 without any
 * parsing code of its own.
 */
public enum TimeWindow {

    LAST_HOUR("Last hour", Duration.ofHours(1)),
    LAST_DAY("Last 24 hours", Duration.ofDays(1)),
    LAST_WEEK("Last 7 days", Duration.ofDays(7)),
    LAST_MONTH("Last 30 days", Duration.ofDays(30)),
    ALL("Any time", null);

    private final String label;
    private final Duration span;

    TimeWindow(String label, Duration span) {
        this.label = label;
        this.span = span;
    }

    public String label() {
        return label;
    }

    /**
     * The earliest instant this window keeps. {@link #ALL} answers the epoch rather
     * than null: no observation in this system predates it, so it is the same filter
     * without an optional parameter to bind -- see
     * {@code DocumentRepository.changedDocumentsFiltered}.
     */
    public Instant startFrom(Instant now) {
        return span == null ? Instant.EPOCH : now.minus(span);
    }
}
