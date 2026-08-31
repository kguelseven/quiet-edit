package org.korhan.quietedit.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;

/**
 * Settings of the reading interface. Only one so far: the zone every timestamp on
 * the pages is rendered in.
 *
 * <p>The stored {@code Instant}s are UTC, and printing UTC without saying so reads as
 * wrong by an hour or two to the person the pages are for. The zone is therefore the
 * reader's, not the database's, and it is a setting rather than {@code systemDefault()}
 * because the reader and the JVM need not sit in the same place -- a container runs on
 * UTC while the operator does not.
 *
 * <p>Bound as text, not as {@link ZoneId}, so that an unknown region fails at startup
 * with the offending value named instead of on the first page render.
 */
@ConfigurationProperties("quietedit.web")
public record WebProperties(@DefaultValue("Europe/Zurich") String zone) {

    public WebProperties {
        try {
            ZoneId.of(zone);
        } catch (ZoneRulesException | IllegalArgumentException e) {
            throw new IllegalArgumentException("quietedit.web.zone is not a known zone: " + zone, e);
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
