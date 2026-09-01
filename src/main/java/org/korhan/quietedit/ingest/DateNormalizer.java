package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a feed's date text into an instant in UTC, and says how much of that instant the
 * publisher actually supplied.
 *
 * <p>The raw text gets this far because every interesting decision about a feed date --
 * what a missing timezone means, what a date in the future means -- needs the characters
 * the publisher wrote, and the {@code exact} flag on {@link NormalisedDate} carries the
 * remaining uncertainty forward instead of hiding it.
 *
 * <p>One date text at a time, with deliberately no method that takes an entry: the moment
 * one existed, "fall back to {@code updated} when {@code published} is missing" would be
 * one line away, and a publication date that silently tracks the last edit is exactly the
 * document this system can no longer reason about.
 *
 * <p>Both formats are parsed in {@link Locale#ENGLISH}, which is what they specify;
 * resolving month names against the server's default locale would make ingest depend on
 * where it runs.
 *
 * <p>The assumption behind each inexact case, the future-skew tolerance and the known
 * weaknesses are justified in quietedit-m4p.
 */
public final class DateNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DateNormalizer.class);

    static final int MAX_FUTURE_SKEW_HOURS = 1;

    /** How far ahead of the retrieval time a publisher's date is still believed. */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(MAX_FUTURE_SKEW_HOURS);

    /** What a date without a zone is read as; the result is flagged inexact. */
    private static final ZoneOffset ASSUMED_ZONE = ZoneOffset.UTC;

    /**
     * The North American abbreviations are fixed offsets, not zone ids: {@code EST} in a
     * feed means UTC-5, and resolving it to a zone would apply a summer-time rule the
     * publisher already applied by writing {@code EDT} instead.
     */
    private static final Map<String, String> NAMED_ZONES = Map.ofEntries(
            Map.entry("UT", "+0000"), Map.entry("GMT", "+0000"), Map.entry("UTC", "+0000"),
            Map.entry("Z", "+0000"),
            Map.entry("EST", "-0500"), Map.entry("EDT", "-0400"),
            Map.entry("CST", "-0600"), Map.entry("CDT", "-0500"),
            Map.entry("MST", "-0700"), Map.entry("MDT", "-0600"),
            Map.entry("PST", "-0800"), Map.entry("PDT", "-0700"));

    /** A trailing {@code (CEST)}-style comment, which RFC 2822 permits and nothing needs. */
    private static final Pattern TRAILING_COMMENT = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

    /** {@code GMT+0200}: the offset is the information, the prefix is noise. */
    private static final Pattern PREFIXED_OFFSET = Pattern.compile("(?i)\\bGMT\\s*(?=[+-]\\d)");

    /** The space that some hand-built feeds write where ISO 8601 wants a {@code T}. */
    private static final Pattern ISO_DATE_TIME_SPACE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:)");

    /** A zone written as letters, at the end, after the time. */
    private static final Pattern TRAILING_ALPHA_ZONE = Pattern.compile("\\s+([A-Za-z]{1,5})$");

    private static final DateTimeFormatter ISO_8601 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart()
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            // Three optional spellings of one offset, tried in turn, so the first that fits consumes it.
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH", "Z").optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter RFC_822 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            // Redundant with the date, written with or without its comma, so it is discarded.
            .optionalStart()
            .appendPattern("EEE")
            .optionalStart().appendLiteral(',').optionalEnd()
            .appendLiteral(' ')
            .optionalEnd()
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral(' ')
            .appendPattern("MMM")
            .appendLiteral(' ')
            // RFC 822 wrote two-digit years, RFC 2822 four. Both still occur.
            .appendValueReduced(ChronoField.YEAR, 2, 4, 1970)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
            .optionalStart()
            .appendLiteral(' ')
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ENGLISH);

    private static final List<DateTimeFormatter> FORMATS = List.of(ISO_8601, RFC_822);

    private DateNormalizer() {
    }

    /**
     * @param retrievedAt the reference point for "in the future", and what replaces a date
     *                    that is
     * @return never null; {@link NormalisedDate#ABSENT} when nothing usable was there
     */
    public static NormalisedDate normalize(String raw, Instant retrievedAt) {
        if (raw == null || raw.isBlank()) {
            return NormalisedDate.ABSENT;
        }
        String cleaned = clean(raw);
        for (DateTimeFormatter format : FORMATS) {
            NormalisedDate parsed = parse(format, cleaned, raw, retrievedAt);
            if (parsed != null) {
                return parsed;
            }
        }
        log.debug("Unparseable feed date \"{}\"; the entry gets no publication date", raw);
        return NormalisedDate.ABSENT;
    }

    /**
     * @return null when this format does not fit, which is not a failure -- the next
     *         format gets its turn.
     */
    private static NormalisedDate parse(DateTimeFormatter format, String cleaned, String raw,
                                        Instant retrievedAt) {
        TemporalAccessor parsed;
        try {
            parsed = format.parse(cleaned);
        } catch (DateTimeParseException e) {
            return null;
        }
        boolean zoned = parsed.isSupported(ChronoField.OFFSET_SECONDS);
        boolean timed = parsed.isSupported(ChronoField.HOUR_OF_DAY);

        Instant instant;
        if (zoned) {
            instant = OffsetDateTime.from(parsed).toInstant();
        } else if (timed) {
            instant = LocalDateTime.from(parsed).toInstant(ASSUMED_ZONE);
        } else {
            instant = LocalDate.from(parsed).atStartOfDay().toInstant(ASSUMED_ZONE);
        }
        // An offset the publisher wrote is the only thing that makes a date exact.
        return atMost(instant, zoned && timed, raw, retrievedAt);
    }

    /**
     * A date that far ahead says more about the publisher's clock or CMS than about the
     * article, so the one timestamp we measured ourselves replaces it.
     */
    private static NormalisedDate atMost(Instant instant, boolean exact, String raw, Instant retrievedAt) {
        if (instant.isAfter(retrievedAt.plus(MAX_FUTURE_SKEW))) {
            log.warn("Feed date \"{}\" resolves to {}, more than {} ahead of the retrieval time {}; "
                            + "using the retrieval time instead",
                    raw, instant, MAX_FUTURE_SKEW, retrievedAt);
            return new NormalisedDate(retrievedAt, false);
        }
        return new NormalisedDate(instant, exact);
    }

    /**
     * Everything the formatters should not have to know about. An unknown alphabetic zone
     * is removed rather than kept, which leaves the text in the assumed-UTC case that is
     * already handled, flag included.
     */
    private static String clean(String raw) {
        String text = raw.strip().replaceAll("\\s+", " ");
        text = TRAILING_COMMENT.matcher(text).replaceAll("");
        text = PREFIXED_OFFSET.matcher(text).replaceAll("");
        text = ISO_DATE_TIME_SPACE.matcher(text).replaceFirst("$1T$2");

        Matcher zone = TRAILING_ALPHA_ZONE.matcher(text);
        if (!zone.find()) {
            return text;
        }
        String offset = NAMED_ZONES.get(zone.group(1).toUpperCase(Locale.ROOT));
        if (offset != null) {
            return text.substring(0, zone.start()) + " " + offset;
        }
        log.debug("Feed date \"{}\" carries the unknown zone {}; reading it as {}",
                raw, zone.group(1), ASSUMED_ZONE);
        return text.substring(0, zone.start());
    }
}
