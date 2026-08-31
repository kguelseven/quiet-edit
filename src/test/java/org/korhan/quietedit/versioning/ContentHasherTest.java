package org.korhan.quietedit.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.korhan.quietedit.ingest.ArticleContent;
import org.korhan.quietedit.ingest.ArticleExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHasherTest {

    private static final String FIXTURES = "/versioning/hash/";
    private static final String EQUIVALENT = FIXTURES + "equivalent-texts.psv";
    private static final String DIFFERING = FIXTURES + "differing-texts.psv";

    /** Spelled from code points: a literal in the source would be invisible to a reviewer. */
    private static final String NBSP = String.valueOf((char) 0x00a0);
    private static final String NARROW_NBSP = String.valueOf((char) 0x202f);
    private static final String SOFT_HYPHEN = String.valueOf((char) 0x00ad);
    private static final String ZERO_WIDTH_SPACE = String.valueOf((char) 0x200b);
    private static final String BOM = String.valueOf((char) 0xfeff);
    private static final String BELL = String.valueOf((char) 0x0007);
    private static final String COMBINING_DIAERESIS = String.valueOf((char) 0x0308);
    private static final String U_UMLAUT = String.valueOf((char) 0x00fc);

    private final ContentHasher hasher = new ContentHasher();
    private final ArticleExtractor extractor = new ArticleExtractor();

    @Test
    @DisplayName("the same article fetched twice with different ad and session ids hashes equal")
    void markupAndIdentifierChurnDoesNotChangeTheHash() {
        assertThat(hashOfFixture("fetch-2.html")).isEqualTo(hashOfFixture("fetch-1.html"));
    }

    @Test
    @DisplayName("one changed word in the body changes the hash")
    void changedWordChangesTheHash() {
        assertThat(hashOfFixture("fetch-3-word-changed.html"))
                .isNotEqualTo(hashOfFixture("fetch-1.html"));
    }

    @Test
    @DisplayName("an added paragraph changes the hash")
    void addedParagraphChangesTheHash() {
        assertThat(hashOfFixture("fetch-4-paragraph-added.html"))
                .isNotEqualTo(hashOfFixture("fetch-1.html"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("equivalentTexts")
    @DisplayName("two spellings of the same content hash equal")
    void equivalentTextsHashEqual(String left, String right, String rationale) {
        assertThat(hashOf(right)).isEqualTo(hashOf(left));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("differingTexts")
    @DisplayName("a real edit changes the hash")
    void differingTextsHashDifferently(String left, String right, String rationale) {
        assertThat(hashOf(right)).isNotEqualTo(hashOf(left));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("invisibleVariants")
    @DisplayName("invisible characters never reach the hash")
    void invisibleCharactersAreFolded(String plain, String variant, String rationale) {
        assertThat(hashOf(variant)).isEqualTo(hashOf(plain));
    }

    @Test
    @DisplayName("paragraph boundaries are part of the identity")
    void paragraphBoundariesCannotCollide() {
        String split = hasher.hash(new ArticleContent("Titel", List.of("Der Bericht", "liegt vor.")));
        String joined = hasher.hash(new ArticleContent("Titel", List.of("Der", "Bericht liegt vor.")));
        assertThat(split).isNotEqualTo(joined);
    }

    @Test
    @DisplayName("a headline moved into the body is not the same content")
    void titleAndBodyAreDistinctFields() {
        String asTitle = hasher.hash(new ArticleContent("Der Netzausbau stockt", List.of()));
        String asBody = hasher.hash(new ArticleContent("", List.of("Der Netzausbau stockt")));
        assertThat(asTitle).isNotEqualTo(asBody);
    }

    @Test
    @DisplayName("a changed headline changes the hash even when the body is identical")
    void headlineIsHashed() {
        List<String> body = List.of("Die Entscheidung wurde erneut verschoben.");
        assertThat(hasher.hash(new ArticleContent("Netzausbau vertagt", body)))
                .isNotEqualTo(hasher.hash(new ArticleContent("Netzausbau beschlossen", body)));
    }

    @Test
    @DisplayName("reordering paragraphs changes the hash")
    void paragraphOrderIsHashed() {
        List<String> first = List.of("Der Entwurf liegt vor.", "Die Laender fordern Ausgleich.");
        assertThat(hasher.hash(new ArticleContent("Titel", first.reversed())))
                .isNotEqualTo(hasher.hash(new ArticleContent("Titel", first)));
    }

    @Test
    @DisplayName("removing a paragraph changes the hash")
    void removedParagraphChangesTheHash() {
        List<String> full = List.of("Der Entwurf liegt vor.", "Die Laender fordern Ausgleich.");
        assertThat(hasher.hash(new ArticleContent("Titel", full.subList(0, 1))))
                .isNotEqualTo(hasher.hash(new ArticleContent("Titel", full)));
    }

    @Test
    @DisplayName("a block that is nothing but an identifier does not count as a paragraph")
    void identifierOnlyParagraphIsIgnored() {
        List<String> prose = List.of("Der Entwurf liegt vor.");
        List<String> withAdBlock = List.of("Der Entwurf liegt vor.", "adSlotId=div-gpt-ad-1712345678901-0");
        assertThat(hasher.hash(new ArticleContent("Titel", withAdBlock)))
                .isEqualTo(hasher.hash(new ArticleContent("Titel", prose)));
    }

    @Test
    @DisplayName("the hash is a lowercase hex SHA-256, the width content_hash declares")
    void hashShapeMatchesTheColumn() {
        assertThat(hashOfFixture("fetch-1.html")).matches("[0-9a-f]{64}");
        assertThat(hasher.hash(emptyContent())).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("repeated hashing of the same content yields the same value")
    void hashingIsStableAcrossRuns() {
        ArticleContent content = extractor.extract(fixture("fetch-1.html"));
        assertThat(hasher.hash(content)).isEqualTo(hasher.hash(content));
        assertThat(hasher.hash(emptyContent())).isEqualTo(hasher.hash(emptyContent()));
    }

    /** {@code ArticleContent.empty()} is package-private in {@code ingest}; this is the same value. */
    private static ArticleContent emptyContent() {
        return new ArticleContent("", List.of());
    }

    private String hashOfFixture(String name) {
        return hasher.hash(extractor.extract(fixture(name)));
    }

    private String hashOf(String paragraph) {
        return hasher.hash(new ArticleContent("Titel", List.of(paragraph)));
    }

    private static Stream<Arguments> equivalentTexts() {
        return rows(EQUIVALENT, 18);
    }

    private static Stream<Arguments> differingTexts() {
        return rows(DIFFERING, 10);
    }

    private static Stream<Arguments> invisibleVariants() {
        return Stream.of(
                Arguments.of("Der Bericht liegt vor.", "Der" + NBSP + "Bericht liegt vor.",
                        "non-breaking space"),
                Arguments.of("Mehr als 10 000 Menschen kamen.",
                        "Mehr als 10" + NARROW_NBSP + "000 Menschen kamen.",
                        "narrow no-break space in a figure"),
                Arguments.of("Die Ministerpraesidentin schwieg.",
                        "Die Ministerpraesi" + SOFT_HYPHEN + "dentin schwieg.",
                        "soft hyphen from CMS hyphenation"),
                Arguments.of("Der Bericht liegt vor.", "Der Beri" + ZERO_WIDTH_SPACE + "cht liegt vor.",
                        "zero-width space inside a word"),
                Arguments.of("Der Bericht liegt vor.", BOM + "Der Bericht liegt vor.",
                        "byte order mark leaking into the text"),
                Arguments.of("Der Bericht liegt vor.", "Der Bericht" + BELL + " liegt vor.",
                        "stray control character"),
                Arguments.of("Viele Gruesse, sagte er.", "Viele" + NBSP + NBSP + " Gruesse, sagte er.",
                        "a run of exotic and ASCII spaces is one space"),
                Arguments.of("Gruesse aus Z" + U_UMLAUT + "rich",
                        "Gruesse aus Zu" + COMBINING_DIAERESIS + "rich",
                        "decomposed umlaut is normalised onto the precomposed one"));
    }

    private static Stream<Arguments> rows(String resource, int minimum) {
        List<Arguments> rows = new ArrayList<>();
        for (String line : readFixture(resource).lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\s*\\|\\s*");
            if (columns.length != 3) {
                throw new IllegalStateException("malformed fixture row: " + line);
            }
            rows.add(Arguments.of(columns[0], columns[1], columns[2]));
        }
        if (rows.size() < minimum) {
            throw new IllegalStateException(
                    "the table must pin down at least " + minimum + " pairs, found " + rows.size());
        }
        return rows.stream();
    }

    private static String fixture(String name) {
        return readFixture(FIXTURES + name);
    }

    private static String readFixture(String resource) {
        try (InputStream in = ContentHasherTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
