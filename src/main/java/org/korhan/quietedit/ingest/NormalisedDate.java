package org.korhan.quietedit.ingest;

import java.time.Instant;

/**
 * A feed date after normalisation: an instant in UTC, plus whether that instant is
 * what the publisher actually wrote.
 *
 * <p>{@code exact} is false whenever something had to be assumed -- a missing
 * timezone, a date without a time, a date so far in the future that it was replaced
 * by the retrieval time. The instant is still the best available answer in those
 * cases; the flag is what stops later analysis from reading a guess as a fact.
 *
 * <p>{@code instant} is null only when no date could be derived at all. {@code
 * exact} is then false as well: the absence of a date is not an exact date.
 */
public record NormalisedDate(Instant instant, boolean exact) {

    /** No date at all: the field was missing, blank, or in no recognisable format. */
    public static final NormalisedDate ABSENT = new NormalisedDate(null, false);

    public NormalisedDate {
        if (instant == null && exact) {
            throw new IllegalArgumentException("A date that does not exist cannot be exact");
        }
    }

    public boolean isPresent() {
        return instant != null;
    }
}
