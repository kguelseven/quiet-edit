package org.korhan.quietedit.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DateNormalizerTest {

    private static final String TABLE = "/ingest/dates/feed-dates.psv";

    /** The retrieval time the fixture table is written against. */
    private static final Instant RETRIEVED_AT = Instant.parse("2026-08-18T09:00:00Z");

    @ParameterizedTest(name = "{3}")
    @MethodSource("table")
    @DisplayName("normalises the date formats real feeds use")
    void normalisesRealWorldFormats(String raw, Instant expected, boolean exact, String rationale) {
        NormalisedDate date = DateNormalizer.normalize(raw, RETRIEVED_AT);

        assertThat(date.instant()).isEqualTo(expected);
        assertThat(date.exact()).isEqualTo(exact);
    }

    @Test
    @DisplayName("yields the same result on repeated runs over the whole table")
    void isDeterministic() {
        List<NormalisedDate> first = normalizeTable();
        List<NormalisedDate> second = normalizeTable();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("does not depend on the default locale or time zone of the machine")
    void isIndependentOfTheDefaultLocale() {
        Locale locale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(DateNormalizer.normalize("Tue, 18 Aug 2026 05:14:00 GMT", RETRIEVED_AT))
                    .isEqualTo(new NormalisedDate(Instant.parse("2026-08-18T05:14:00Z"), true));
        } finally {
            Locale.setDefault(locale);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t "})
    @DisplayName("a missing date is absent, not a guess")
    void missingDateIsAbsent(String raw) {
        assertThat(DateNormalizer.normalize(raw, RETRIEVED_AT)).isEqualTo(NormalisedDate.ABSENT);
    }

    @Test
    @DisplayName("whitespace and line breaks around the value are the publisher's, not the date's")
    void surroundingWhitespaceIsIrrelevant() {
        assertThat(DateNormalizer.normalize("\n      Tue, 18 Aug 2026 07:14:00 +0200\n    ", RETRIEVED_AT))
                .isEqualTo(new NormalisedDate(Instant.parse("2026-08-18T05:14:00Z"), true));
    }

    @Test
    @DisplayName("exactly one hour ahead is still believed; a second more is not")
    void theFutureToleranceIsOneHour() {
        assertThat(DateNormalizer.normalize("2026-08-18T10:00:00Z", RETRIEVED_AT))
                .isEqualTo(new NormalisedDate(Instant.parse("2026-08-18T10:00:00Z"), true));
        assertThat(DateNormalizer.normalize("2026-08-18T10:00:01Z", RETRIEVED_AT))
                .isEqualTo(new NormalisedDate(RETRIEVED_AT, false));
    }

    @Test
    @DisplayName("an entry that carries only an updated date gets no publication date")
    void updatedNeverStandsInForPublished() {
        FeedEntry entry = new FeedEntry("Titel", "https://example-news.com/a", null,
                null, "2026-08-18T07:12:48Z", "guid-1");

        assertThat(DateNormalizer.normalize(entry.publishedRaw(), RETRIEVED_AT))
                .isEqualTo(NormalisedDate.ABSENT);
        assertThat(DateNormalizer.normalize(entry.updatedRaw(), RETRIEVED_AT))
                .isEqualTo(new NormalisedDate(Instant.parse("2026-08-18T07:12:48Z"), true));
    }

    @Test
    @DisplayName("the two dates of one entry stay independent even when both are present")
    void publishedAndUpdatedAreNormalisedSeparately() {
        FeedEntry entry = new FeedEntry("Titel", "https://example-news.com/a", null,
                "Tue, 18 Aug 2026 07:14:00 +0200", "2026-08-18T07:12:48Z", "guid-1");

        assertThat(DateNormalizer.normalize(entry.publishedRaw(), RETRIEVED_AT).instant())
                .isEqualTo(Instant.parse("2026-08-18T05:14:00Z"));
        assertThat(DateNormalizer.normalize(entry.updatedRaw(), RETRIEVED_AT).instant())
                .isEqualTo(Instant.parse("2026-08-18T07:12:48Z"));
    }

    private static List<NormalisedDate> normalizeTable() {
        return table().map(row -> DateNormalizer.normalize((String) row.get()[0], RETRIEVED_AT)).toList();
    }

    static Stream<Arguments> table() {
        List<Arguments> rows = new ArrayList<>();
        for (String line : read(TABLE).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            if (columns.length != 4) {
                throw new IllegalStateException("Malformed fixture row: " + line);
            }
            String expected = columns[1].trim();
            rows.add(Arguments.of(
                    columns[0].trim(),
                    expected.equals("-") ? null : Instant.parse(expected),
                    Boolean.parseBoolean(columns[2].trim()),
                    columns[3].trim()));
        }
        return rows.stream();
    }

    private static String read(String resource) {
        try (InputStream in = DateNormalizerTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
