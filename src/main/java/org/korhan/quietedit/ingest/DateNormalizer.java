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
 * Turns a feed's date text into an instant in UTC, and says how much of that instant
 * the publisher actually supplied.
 *
 * <h2>Why the raw text gets this far at all</h2>
 * {@link FeedParser} hands dates on verbatim. Every interesting decision about a
 * feed date -- what a missing timezone means, what a date in the future means, what
 * to do with a format nobody standardised -- needs the characters the publisher
 * wrote, and a parser that returned an {@code Instant} would have made all of those
 * decisions silently and thrown the evidence away. They are made here instead, once,
 * and the {@code exact} flag on {@link NormalisedDate} carries the uncertainty
 * forward instead of hiding it.
 *
 * <h2>Published and updated are never conflated</h2>
 * This class normalises <em>one</em> date text and knows nothing about any other
 * field of the entry. There is deliberately no method that takes an entry and picks
 * a date from it: the moment one existed, "fall back to {@code updated} when {@code
 * published} is missing" would be one line away, and a document whose publication
 * date silently tracks its last edit is exactly the document this system can no
 * longer reason about. An entry with only an {@code updated} date therefore has no
 * publication date, and says so.
 *
 * <h2>Which formats are accepted</h2>
 * Two families, tried in order:
 * <ul>
 *   <li>ISO 8601 / RFC 3339, as Atom uses it: {@code 2026-08-18T06:02:00Z},
 *       {@code +02:00}, {@code +0200} and {@code +02} offsets, optional seconds,
 *       optional fractional seconds, a bare date, and the space-instead-of-T
 *       spelling that appears in hand-built feeds.</li>
 *   <li>RFC 822 / RFC 2822, as RSS uses it: {@code Tue, 18 Aug 2026 07:14:00 +0200},
 *       with an optional day-of-week, one- or two-digit day, two- or four-digit
 *       year, optional seconds, and the alphabetic zone names of RFC 822 §5.1.</li>
 * </ul>
 * Both are parsed case-insensitively and in {@link Locale#ENGLISH}: English month
 * and day abbreviations are what the two formats specify, and resolving them against
 * the server's default locale would make ingest depend on where it runs.
 *
 * <h2>The assumptions, stated</h2>
 * <ul>
 *   <li><b>Missing timezone</b> -- assumed UTC, result flagged inexact. The
 *       alternatives are worse: the server's zone would make the same feed parse
 *       differently on two machines, and the publisher's zone is not knowable from
 *       the feed. The error is bounded by the range of real offsets, so at most
 *       roughly half a day, and the flag says it is there.</li>
 *   <li><b>Date without a time</b> -- midnight UTC, flagged inexact, for the same
 *       reason: it is an interval, and its start is the one point in it that does
 *       not drift.</li>
 *   <li><b>More than {@value #MAX_FUTURE_SKEW_HOURS} hour(s) in the future</b> --
 *       discarded in favour of the retrieval time and logged. A publisher's clock
 *       skew, a template placeholder, or an embargo date would otherwise put an
 *       article ahead of everything real. One hour of tolerance absorbs ordinary
 *       clock drift and the "posted a minute from now" scheduling that CMSs do,
 *       without letting a genuinely wrong date through. The retrieval time is not a
 *       publication date, so the result is flagged inexact.</li>
 *   <li><b>An unrecognised alphabetic zone</b> -- dropped, which lands the value in
 *       the missing-timezone case above. RFC 822's military zones are famously
 *       sign-reversed and RFC 2822 §4.3 says to treat them as unknown; local
 *       inventions such as {@code MEZ} are unknown by definition. Assuming UTC and
 *       flagging it beats discarding an otherwise perfectly good timestamp.</li>
 * </ul>
 *
 * <h2>Known weaknesses</h2>
 * A two-digit year is read into 1970-2069 ({@link #RFC_822}'s reduced year), so a
 * feed republishing something from 1969 would date it 2069. Feeds that old do not
 * exist and the alternative -- a windowing rule of our own -- would be a guess with
 * more moving parts. Dates in a non-Gregorian calendar, or written in a language's
 * own month names, are not parsed at all and come back absent; they are vanishingly
 * rare in RSS and Atom, both of which specify English.
 */
public final class DateNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DateNormalizer.class);

    static final int MAX_FUTURE_SKEW_HOURS = 1;

    /** How far ahead of the retrieval time a publisher's date is still believed. */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(MAX_FUTURE_SKEW_HOURS);

    /** What a date without a zone is read as. Documented in the class Javadoc. */
    private static final ZoneOffset ASSUMED_ZONE = ZoneOffset.UTC;

    /**
     * The alphabetic zones of RFC 822 §5.1 that carry a defined offset. The North
     * American abbreviations are fixed offsets, not zone ids, on purpose: {@code EST}
     * in a feed means UTC-5, and resolving it to a zone would apply a summer-time rule
     * the publisher already applied by writing {@code EDT} instead.
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
            // Three spellings of the same offset. Each is optional and they are tried in
            // turn, so the first one that fits consumes the text and the rest find nothing
            // left to read; an offset can therefore never be parsed twice.
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH", "Z").optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter RFC_822 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            // The day-of-week is redundant with the date and publishers write it with or
            // without its comma, so it is read and discarded rather than checked.
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
     * @param raw         one date field exactly as the feed wrote it, or null
     * @param retrievedAt when this entry was fetched; the reference point for "in the
     *                    future" and the replacement for a date that is
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
        // An offset the publisher wrote is the only thing that makes a date exact: a
        // date alone is an interval, and an assumed zone is a guess with a name.
        return atMost(instant, zoned && timed, raw, retrievedAt);
    }

    /**
     * A date ahead of the retrieval time by more than the tolerance says more about
     * the publisher's clock or CMS than about the article, so the one timestamp we
     * measured ourselves replaces it.
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
     * Everything the formatters should not have to know about: whitespace a publisher
     * left in the element, RFC 2822's parenthesised comments, the alphabetic zone
     * names that {@link DateTimeFormatter} has no locale-free way to read, and the
     * space-for-{@code T} spelling.
     *
     * <p>An unknown alphabetic zone is removed rather than kept, which leaves a text
     * with no zone at all -- and that is exactly the case the assumed-UTC rule already
     * handles, flag included.
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
