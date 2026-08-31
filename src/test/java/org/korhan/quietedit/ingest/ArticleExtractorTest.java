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
            "entity-noise",         // non-breaking spaces, soft hyphens, zero-width characters
            "nzz-rails-fetch1",     // real capture: furniture named only by data-* attributes
            "nzz-rails-fetch2",     // the same article one fetch later, different rails
            "watson-ticker");       // real capture: a ticker whose last entries end on a heading

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

    /**
     * The two NZZ fixtures are the raw captures of two consecutive fetches of the
     * same article (nzz.ch/...angelica-moser...ld.10021232). Its prose did not change
     * between them; its "Neueste Artikel" rail did. Anything the rails contribute to
     * the extraction is therefore a change report about an article nobody edited,
     * which is indistinguishable from the silent edits this system exists to find.
     */
    @Test
    @DisplayName("two fetches whose rails differ extract to the same bytes")
    void furnitureChurnIsNotAChange() {
        ArticleContent first = extractor.extract(fixture("nzz-rails-fetch1.html"));
        ArticleContent second = extractor.extract(fixture("nzz-rails-fetch2.html"));

        assertThat(fixture("nzz-rails-fetch1.html")).isNotEqualTo(fixture("nzz-rails-fetch2.html"));
        assertThat(serialize(second)).isEqualTo(serialize(first));
    }

    /**
     * The four blocks NZZ stored as article paragraphs before the {@code data-*}
     * vocabulary existed. Their class attributes carry nothing but Tailwind
     * utilities, so class and id alone can never reach them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("a Tailwind page's rails reach no revision")
    void railsAreNeverProse(String source) {
        ArticleContent content = extractor.extract(fixture(source + ".html"));

        assertThat(content.paragraphs()).noneMatch(paragraph ->
                paragraph.startsWith("Für Sie empfohlen")
                        || paragraph.startsWith("Mehr zum Thema")
                        || paragraph.startsWith("Artikel von NZZ Bellevue")
                        || paragraph.equals("Solitär"));
    }

    /**
     * sueddeutsche.de marks its standfirst {@code data-manual="teaserText"} where NZZ
     * marks a rail {@code data-ct-type="teaser container title"}. The declared content
     * root is what tells the two apart, and the standfirst is the sentence a silent
     * edit is most likely to touch.
     */
    @Test
    @DisplayName("data-* names furniture around the article, never inside a declared one")
    void declaredNamesStopAtTheContentRoot() {
        String html = """
                <body>
                  <div data-ct-type="teaser container title"><h2>Für Sie empfohlen</h2></div>
                  <article>
                    <p data-manual="teaserText">Der Konzern zahlt bis zu 16,68 Milliarden Dollar an die Bundesstaaten.</p>
                    <p>Die Einigung bewahrt das Unternehmen vor einem Prozess, der im August begonnen hatte.</p>
                  </article>
                </body>
                """;

        assertThat(extractor.extract(html).paragraphs()).containsExactly(
                "Der Konzern zahlt bis zu 16,68 Milliarden Dollar an die Bundesstaaten.",
                "Die Einigung bewahrt das Unternehmen vor einem Prozess, der im August begonnen hatte.");
    }

    /**
     * A heading left behind by a box pruning emptied is a caption, but a page that
     * is mostly headings is a list whose headings are its content -- a liveblog
     * whose entry bodies load later. Both shapes end on a heading; only the ratio
     * separates them.
     */
    @Test
    @DisplayName("a trailing heading goes only where prose outnumbers it")
    void trailingHeadingsGoOnlyWhenProseOutweighsThem() {
        String article = """
                <article>
                  <p>Der Rechnungshof nennt drei Projekte, deren Kosten sich seither verdoppelt haben.</p>
                  <p>Das Ministerium widerspricht der Darstellung und verweist auf gestiegene Materialpreise.</p>
                  <h2>Mehr zum Thema Netzausbau</h2>
                </article>
                """;
        String liveblog = """
                <article>
                  <p>Für unseren Liveblog verwenden wir Material der Nachrichtenagenturen dpa und Reuters.</p>
                  <h2>Kabinett vertagt den Netzausbau</h2>
                  <h2>Länder fordern einen Ausgleich</h2>
                </article>
                """;

        assertThat(extractor.extract(article).paragraphs()).hasSize(2);
        assertThat(extractor.extract(liveblog).paragraphs()).hasSize(3);
    }

    /**
     * The ratio guard alone cannot separate a ticker's newest entries from a rail
     * caption: both are a short trailing heading on a page that has prose. What
     * separates them is that the ticker uses one heading shape for every entry, and
     * the earlier entries do carry prose, while the caption's shape introduces prose
     * nowhere. Both shapes appear here on one page, in the order a ticker page puts
     * them: the entries end and the rails follow.
     */
    @Test
    @DisplayName("a trailing entry headline stays where a rail caption of its own shape goes")
    void aHeadingShapeUsedForContentSurvivesAtTheEnd() {
        String ticker = """
                <article>
                  <h3 class="entry__title">Bouaddi wechselt zu ManCity</h3>
                  <p>Der Mittelfeldspieler kostet 95 Millionen Euro und unterschreibt bis 2031.</p>
                  <h3 class="entry__title">Palhinha verlässt Bayern</h3>
                  <p>Der Portugiese kehrt nach zwei Jahren in die Premier League zurück.</p>
                  <h3 class="entry__title">Bremen holt Füllkrug zurück</h3>
                  <h3 class="entry__title">Junger Spanier für die Grasshoppers</h3>
                  <h2 class="container__title">Mehr zum Thema Transfers</h2>
                </article>
                """;

        assertThat(extractor.extract(ticker).paragraphs()).containsExactly(
                "Bouaddi wechselt zu ManCity",
                "Der Mittelfeldspieler kostet 95 Millionen Euro und unterschreibt bis 2031.",
                "Palhinha verlässt Bayern",
                "Der Portugiese kehrt nach zwei Jahren in die Premier League zurück.",
                "Bremen holt Füllkrug zurück",
                "Junger Spanier für die Grasshoppers");
    }

    /**
     * The four entry headlines quietedit-10i.11 was about, on the capture that lost
     * them. Spelled out next to the goldset because the goldset would still pass with
     * them missing from both sides of the comparison.
     */
    @Test
    @DisplayName("a ticker capture keeps the entries its last headlines belong to")
    void tickerEntryHeadlinesReachTheRevision() {
        ArticleContent content = extractor.extract(fixture("watson-ticker.html"));

        assertThat(content.paragraphs()).endsWith(
                "Patrick Vieira neuer Nationaltrainer des Senegal",
                "Luzern holt wohl neue Nummer 1",
                "Junger Spanier für die Grasshoppers",
                "Bremen holt Füllkrug zurück");
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
