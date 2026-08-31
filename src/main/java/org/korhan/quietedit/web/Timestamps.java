package org.korhan.quietedit.web;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The one way a timestamp is written on the pages, reachable from a template as
 * {@code ${@timestamps.format(...)}}.
 *
 * <p>It exists because the templates otherwise print an {@code Instant} through its
 * {@code toString()} -- {@code 2026-08-27T13:26:31.574126Z}, microseconds and all --
 * next to a formatted fetch time, two renderings of the same kind of value on one page.
 * Putting the format and the zone in a bean rather than repeating a pattern in each
 * template means the next page cannot disagree with these two.
 *
 * <p>{@code format} stops at minutes: that is the resolution an editorial event has,
 * and seconds add width without adding meaning while scanning a listing. Where the
 * seconds do matter -- two revisions of one article can be seconds apart, and minute
 * precision makes them look simultaneous -- {@link #exact(Instant)} supplies them for a
 * {@code title} attribute, so the detail is available on hover without being on screen.
 *
 * <p>Null in, null out: an absent timestamp is the template's business, and it already
 * says what it wants to show instead.
 */
@Component("timestamps")
public class Timestamps {

    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final ZoneId zone;

    public Timestamps(WebProperties properties) {
        this.zone = properties.zoneId();
    }

    /** The reading format: local date and time to the minute. */
    public String format(Instant at) {
        return at == null ? null : MINUTES.format(at.atZone(zone));
    }

    /** The same moment to the second, for a title attribute. */
    public String exact(Instant at) {
        return at == null ? null : SECONDS.format(at.atZone(zone));
    }

    /** The zone everything above is rendered in, so a page can name it. */
    public ZoneId zone() {
        return zone;
    }
}
