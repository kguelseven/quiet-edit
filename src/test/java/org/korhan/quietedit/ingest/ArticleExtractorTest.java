package org.korhan.quietedit.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleExtractorTest {

    private static final String FIXTURES = "/boilerplate/";

    /** One page per source layout, each with its expected extraction next to it. */
    private static final List<String> SOURCES = List.of(
            "spiegel-style",        // <article> plus itemprop=articleBody, consent layer, ads, comments
            "guardian-style",       // <main> only, share bar, promo boxes, most-viewed
            "bbc-style",            // teasers marked up as nested <article> elements
            "legacy-nosemantics",   // table layout, no semantic container: density fallback
            "paywall-stub",         // headline but no prose at all
            "entity-noise");        // non-breaking spaces, soft hyphens, zero-width characters

    private final ArticleExtractor extractor = new ArticleExtractor();

    static Stream<String> sources() {
        return SOURCES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("extracts the prose and drops the furniture")
    void extractsExpectedProse(String source) {
        ArticleContent expected = expected(source);
        ArticleContent actual = extractor.extract(fixture(source + ".html"));

        assertThat(actual.title()).isEqualTo(expected.title());
        assertThat(actual.paragraphs()).containsExactlyElementsOf(expected.paragraphs());
    }

    /**
     * The property T10 hashes and T12 diffs: identical input must produce identical
     * bytes, across repeated calls and across instances. Compared as bytes, not as
     * objects, because that is what the hash will see.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("repeated extraction is byte-identical")
    void isDeterministic(String source) {
        String html = fixture(source + ".html");

        byte[] first = serialize(extractor.extract(html));
        byte[] again = serialize(extractor.extract(html));
        byte[] freshInstance = serialize(new ArticleExtractor().extract(html));

        assertThat(again).isEqualTo(first);
        assertThat(freshInstance).isEqualTo(first);
    }

    @Test
    @DisplayName("nothing to extract is empty content, not an exception")
    void handlesInputWithoutContent() {
        assertThat(extractor.extract(null).isEmpty()).isTrue();
        assertThat(extractor.extract("   ").isEmpty()).isTrue();
        assertThat(extractor.extract("<html><body><div></div></body></html>").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the headline inside the article outranks og:title")
    void contentRootHeadlineWins() {
        String html = """
                <html><head><meta property="og:title" content="Stale social title"></head>
                <body><header><h1>The Daily Ledger</h1></header>
                <article><h1>Rendered headline</h1><p>Body text that is long enough to be kept as prose.</p></article>
                </body></html>
                """;

        assertThat(extractor.extract(html).title()).isEqualTo("Rendered headline");
    }

    @Test
    @DisplayName("og:title outranks a free-standing h1, which is as likely to be the logo")
    void socialTitleBeatsFreeStandingHeadline() {
        String html = """
                <html><head><meta property="og:title" content="Actual headline"></head>
                <body><h1 class="logo">The Daily Ledger</h1>
                <div><p>Body text that is long enough to be kept as prose.</p></div></body></html>
                """;

        assertThat(extractor.extract(html).title()).isEqualTo("Actual headline");
    }

    @Test
    @DisplayName("the browser title is the last resort, suffix and all")
    void browserTitleIsTheFallback() {
        String html = "<html><head><title>Headline | The Daily Ledger</title></head>"
                + "<body><div><p>Body text that is long enough to be kept as prose.</p></div></body></html>";

        assertThat(extractor.extract(html).title()).isEqualTo("Headline | The Daily Ledger");
    }

    @Test
    @DisplayName("a label is furniture, a passage about the same subject is not")
    void labelsAreMatchedOnlyOnShortBlocks() {
        String html = """
                <article>
                  <p>Anzeige</p>
                  <p>Cookie-Einstellungen</p>
                  <p>Die Anzeige von Werbung neben redaktionellen Inhalten ist seit Jahren umstritten, und die
                     Verlage verteidigen sie mit dem Hinweis auf fehlende Alternativen.</p>
                </article>
                """;

        assertThat(extractor.extract(html).paragraphs()).containsExactly(
                "Die Anzeige von Werbung neben redaktionellen Inhalten ist seit Jahren umstritten, und die "
                        + "Verlage verteidigen sie mit dem Hinweis auf fehlende Alternativen.");
    }

    @Test
    @DisplayName("prose may cite links, a teaser row is mostly link")
    void linkDensityDropsTeaserRows() {
        String html = """
                <article>
                  <p>Der <a href="/b">Bericht des Rechnungshofs</a> nennt drei Projekte, deren Kosten sich
                     verdoppelt haben.</p>
                  <p><a href="/1">Rechnungshof rügt Hafenausbau</a> <a href="/2">Kosten verdoppelt</a></p>
                </article>
                """;

        assertThat(extractor.extract(html).paragraphs()).containsExactly(
                "Der Bericht des Rechnungshofs nennt drei Projekte, deren Kosten sich verdoppelt haben.");
    }

    @Test
    @DisplayName("a short block survives only if it reads as a sentence")
    void shortBlocksNeedSentenceShape() {
        String html = """
                <article>
                  <p>Er schwieg.</p>
                  <p>Foto: dpa</p>
                  <p>Mehr zum Thema</p>
                </article>
                """;

        assertThat(extractor.extract(html).paragraphs()).containsExactly("Er schwieg.");
    }

    private ArticleContent expected(String source) {
        List<String> paragraphs = new ArrayList<>();
        String title = "";
        for (String line : fixture(source + ".expected.txt").split("\n")) {
            if (line.startsWith("TITLE\t")) {
                title = line.substring("TITLE\t".length());
            } else if (line.startsWith("PARA\t")) {
                paragraphs.add(line.substring("PARA\t".length()));
            } else if (!line.isBlank()) {
                throw new IllegalStateException("unreadable expectation line: " + line);
            }
        }
        return new ArticleContent(title, paragraphs);
    }

    private byte[] serialize(ArticleContent content) {
        return (content.title() + "\n" + String.join("\n", content.paragraphs()))
                .getBytes(StandardCharsets.UTF_8);
    }

    private String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + FIXTURES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
