package org.korhan.quietedit.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-driven: what real RSS and Atom look like, and what the parser is allowed
 * to do with the parts of them that are broken.
 */
class FeedParserTest {

    private static final String FIXTURES = "/feeds/";

    private final FeedParser parser = new FeedParser();

    @Test
    @DisplayName("RSS 2.0: entries come out complete, with date texts untouched")
    void parsesRss20() {
        FeedParseResult result = parse("rss20.xml");

        assertThat(result.failed()).isFalse();
        assertThat(result.feedType()).isEqualTo("rss_2.0");
        assertThat(result.skipped()).isEmpty();
        assertThat(result.entries()).hasSize(3);

        FeedEntry first = result.entries().getFirst();
        assertThat(first.title()).isEqualTo("Bundestag beschliesst Reform der Netzentgelte");
        assertThat(first.link()).isEqualTo("https://example-news.test/politik/netzentgelte-reform-a-8812394.html");
        assertThat(first.summary()).contains("412 zu 189 Stimmen");
        assertThat(first.publishedRaw()).isEqualTo("Tue, 18 Aug 2026 07:14:00 +0200");
        assertThat(first.updatedRaw()).isNull();
        assertThat(first.guid()).isEqualTo("example-news-8812394");
    }

    @Test
    @DisplayName("Atom 1.0: the same shape, from entirely different element names")
    void parsesAtom10() {
        FeedParseResult result = parse("atom10.xml");

        assertThat(result.failed()).isFalse();
        assertThat(result.feedType()).isEqualTo("atom_1.0");
        assertThat(result.skipped()).isEmpty();
        assertThat(result.entries()).hasSize(2);

        FeedEntry first = result.entries().getFirst();
        assertThat(first.title()).isEqualTo("Central bank holds rates for a third meeting");
        assertThat(first.link()).isEqualTo("https://wire.example.test/world/2026/08/18/central-bank-holds-rates");
        assertThat(first.summary()).contains("sticky services inflation");
        assertThat(first.publishedRaw()).isEqualTo("2026-08-18T06:02:00Z");
        assertThat(first.updatedRaw()).isEqualTo("2026-08-18T07:12:48Z");
        assertThat(first.guid()).isEqualTo("tag:wire.example.test,2026:world/9931204");
    }

    @Test
    @DisplayName("the alternate link wins over a related one")
    void picksTheAlternateLink() {
        FeedEntry second = parse("atom10.xml").entries().get(1);

        assertThat(second.link()).isEqualTo("https://wire.example.test/world/2026/08/17/flood-rescue-villages");
    }

    @Test
    @DisplayName("content stands in for a missing summary")
    void fallsBackFromSummaryToContent() {
        FeedEntry second = parse("atom10.xml").entries().get(1);

        assertThat(second.summary()).contains("Helicopters delivered supplies");
    }

    @Test
    @DisplayName("dc:date is read as a publication date, and its text is not reformatted")
    void readsDublinCoreDate() {
        FeedEntry third = parse("rss20.xml").entries().get(2);

        assertThat(third.publishedRaw()).isEqualTo("2026-08-17T18:05:00+02:00");
    }

    @Test
    @DisplayName("query parameters in a link survive: normalising them is not this parser's job")
    void leavesLinksAlone() {
        FeedEntry second = parse("rss20.xml").entries().get(1);

        assertThat(second.link())
                .isEqualTo("https://example-news.test/politik/vergabeverfahren-zeitplan-a-8812301.html?utm_source=rss&utm_medium=feed");
    }

    @Test
    @DisplayName("an empty description is null rather than an empty string")
    void blankFieldsBecomeNull() {
        FeedEntry third = parse("rss20.xml").entries().get(2);

        assertThat(third.summary()).isNull();
    }

    @Test
    @DisplayName("callers cannot tell the two formats apart from the entry shape")
    void bothFormatsYieldTheSameShape() {
        List<FeedEntry> rss = parse("rss20.xml").entries();
        List<FeedEntry> atom = parse("atom10.xml").entries();

        assertThat(rss).allSatisfy(entry -> assertThat(entry.link()).startsWith("https://"));
        assertThat(atom).allSatisfy(entry -> assertThat(entry.link()).startsWith("https://"));
        assertThat(rss.getFirst()).isNotEqualTo(atom.getFirst());
    }

    @Test
    @DisplayName("a linkless entry is dropped with a reason, the usable ones are kept")
    void skipsEntriesWithoutALink() {
        FeedParseResult result = parse("incomplete-entries.xml");

        assertThat(result.failed()).isFalse();
        assertThat(result.entries()).extracting(FeedEntry::link).containsExactly(
                "https://partly.example.test/usable-1.html",
                "https://partly.example.test/usable-2.html");
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped()).allSatisfy(reason -> assertThat(reason).contains("no link"));
    }

    @Test
    @DisplayName("a missing title is not a defect: only the link is mandatory")
    void keepsEntriesWithoutATitle() {
        FeedEntry last = parse("incomplete-entries.xml").entries().getLast();

        assertThat(last.title()).isNull();
        assertThat(last.publishedRaw()).isNull();
        assertThat(last.link()).isEqualTo("https://partly.example.test/usable-2.html");
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed.xml", "not-a-feed.html"})
    @DisplayName("an unparseable body is a failed result, never an exception")
    void reportsUnparseableBodiesWithoutThrowing(String fixture) {
        FeedParseResult result = parse(fixture);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isNotBlank();
        assertThat(result.entries()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t "})
    @DisplayName("an empty body fails the same way, without reaching the XML parser")
    void reportsEmptyBodies(String body) {
        FeedParseResult result = parser.parse("https://example.test/rss.xml", body);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).isEqualTo("empty body");
    }

    @Test
    @DisplayName("a null body fails rather than throwing")
    void reportsNullBody() {
        assertThat(parser.parse("https://example.test/rss.xml", null).failed()).isTrue();
    }

    @Test
    @DisplayName("parsing the same body twice yields the same entries")
    void isDeterministic() {
        assertThat(parse("rss20.xml").entries()).isEqualTo(parse("rss20.xml").entries());
        assertThat(parse("atom10.xml").entries()).isEqualTo(parse("atom10.xml").entries());
    }

    private FeedParseResult parse(String fixture) {
        return parser.parse("https://example.test/" + fixture, read(fixture));
    }

    private static String read(String fixture) {
        try (InputStream in = FeedParserTest.class.getResourceAsStream(FIXTURES + fixture)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
