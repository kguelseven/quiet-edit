package org.korhan.quietedit.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimestampsTest {

    private final Timestamps timestamps = new Timestamps(new WebProperties("Europe/Zurich"));

    @Test
    @DisplayName("a stored UTC instant is shown in the configured zone, to the minute")
    void rendersInTheConfiguredZone() {
        // 13:26 UTC is 15:26 in Zurich in August; the seconds and microseconds are dropped.
        Instant at = Instant.parse("2026-08-27T13:26:31.574126Z");

        assertThat(timestamps.format(at)).isEqualTo("27.08.2026 15:26");
    }

    @Test
    @DisplayName("the exact rendering keeps the seconds for a title attribute")
    void exactKeepsSeconds() {
        assertThat(timestamps.exact(Instant.parse("2026-08-27T13:26:31.574126Z")))
                .isEqualTo("27.08.2026 15:26:31");
    }

    @Test
    @DisplayName("winter and summer of the same zone differ by the offset in force")
    void followsTheZoneRules() {
        assertThat(timestamps.format(Instant.parse("2026-01-15T13:26:00Z"))).isEqualTo("15.01.2026 14:26");
        assertThat(timestamps.format(Instant.parse("2026-07-15T13:26:00Z"))).isEqualTo("15.07.2026 15:26");
    }

    @Test
    @DisplayName("an absent timestamp stays absent, so the template decides what to show")
    void nullInNullOut() {
        assertThat(timestamps.format(null)).isNull();
        assertThat(timestamps.exact(null)).isNull();
    }

    @Test
    @DisplayName("an unknown zone is rejected where it is configured, not where it is used")
    void unknownZoneIsRejectedAtBinding() {
        assertThatThrownBy(() -> new WebProperties("Europe/Zurick"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quietedit.web.zone");
    }
}
