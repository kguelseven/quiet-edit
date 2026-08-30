package org.korhan.quietedit.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UrlCanonicalizerTest {

    private static final String FIXTURES = "/versioning/url/";
    private static final String PAIRS = FIXTURES + "canonicalisation-pairs.psv";

    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

    @ParameterizedTest(name = "{2}")
    @MethodSource("pairs")
    @DisplayName("collapses real URL forms onto one identity")
    void canonicalisesRealWorldUrls(String input, String expected, String rationale) {
        assertThat(canonicalizer.canonicalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("pairs")
    @DisplayName("is idempotent, so a stored canonicalUrl survives re-canonicalisation")
    void isIdempotent(String input, String expected, String rationale) {
        String once = canonicalizer.canonicalize(input);
        assertThat(canonicalizer.canonicalize(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("yields the same result on repeated runs over the whole table")
    void isStableAcrossRuns() {
        List<String> first = pairs().map(a -> canonicalizer.canonicalize((String) a.get()[0])).toList();
        List<String> second = pairs().map(a -> canonicalizer.canonicalize((String) a.get()[0])).toList();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("parameter order in the input never reaches the identity")
    void queryOrderIsIrrelevant() {
        String a = canonicalizer.canonicalize("https://example-news.com/s?z=1&a=2&m=hello");
        String b = canonicalizer.canonicalize("https://example-news.com/s?m=hello&z=1&a=2");
        assertThat(a).isEqualTo(b).isEqualTo("https://example-news.com/s?a=2&m=hello&z=1");
    }

    @Test
    @DisplayName("rel=canonical wins over the fetched URL")
    void declaredCanonicalTakesPrecedence() {
        String fetched = "https://www.spiegel.de/politik/deutschland/artikel-a-123.html"
                + "?utm_source=rss&dre=aktuell";
        assertThat(canonicalizer.canonicalize(fetched, fixture("canonical-link.html")))
                .isEqualTo("https://spiegel.de/politik/deutschland/artikel-a-123.html");
    }

    @Test
    @DisplayName("a relative rel=canonical is resolved against the fetched URL")
    void relativeDeclaredCanonicalIsResolved() {
        String fetched = "https://www.spiegel.de/politik/deutschland/artikel-a-123.html?utm_medium=mail";
        assertThat(canonicalizer.canonicalize(fetched, fixture("canonical-link-relative.html")))
                .isEqualTo("https://spiegel.de/politik/deutschland/artikel-a-123.html");
    }

    @Test
    @DisplayName("rel is read as a token list, so 'canonical shortlink' still counts")
    void relTokenListIsRecognised() {
        String fetched = "https://amp.theguardian.com/world/2026/aug/23/story/amp?CMP=twt";
        assertThat(canonicalizer.canonicalize(fetched, fixture("canonical-link-token-list.html")))
                .isEqualTo("https://theguardian.com/world/2026/aug/23/story");
    }

    @Test
    @DisplayName("a rel=canonical pointing at the homepage is rejected as a template bug")
    void homepageDeclarationIsRejected() {
        String fetched = "https://www.spiegel.de/politik/deutschland/artikel-a-123.html?utm_source=rss";
        assertThat(canonicalizer.canonicalize(fetched, fixture("canonical-link-homepage.html")))
                .isEqualTo("https://spiegel.de/politik/deutschland/artikel-a-123.html");
    }

    @Test
    @DisplayName("an unusable rel=canonical falls back to the fetched URL")
    void brokenDeclarationFallsBack() {
        String fetched = "https://www.spiegel.de/politik/deutschland/artikel-a-123.html?fbclid=x";
        assertThat(canonicalizer.canonicalize(fetched, fixture("canonical-link-broken.html")))
                .isEqualTo("https://spiegel.de/politik/deutschland/artikel-a-123.html");
    }

    @Test
    @DisplayName("without a rel=canonical the fetched URL decides")
    void missingDeclarationFallsBack() {
        String fetched = "https://m.faz.net/aktuell/politik/story-19834.html?utm_source=rss";
        assertThat(canonicalizer.canonicalize(fetched, fixture("no-canonical-link.html")))
                .isEqualTo("https://faz.net/aktuell/politik/story-19834.html");
        assertThat(canonicalizer.canonicalize(fetched, null))
                .isEqualTo("https://faz.net/aktuell/politik/story-19834.html");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "  ",
            "/politik/artikel-1",
            "ftp://example-news.com/artikel",
            "mailto:redaktion@example-news.com",
            "https:///artikel-1"
    })
    @DisplayName("rejects input that cannot carry an identity")
    void rejectsUnusableInput(String url) {
        assertThatIllegalArgumentException().isThrownBy(() -> canonicalizer.canonicalize(url));
    }

    private static Stream<Arguments> pairs() {
        List<Arguments> rows = new ArrayList<>();
        for (String line : readFixture(PAIRS).lines().toList()) {
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
        if (rows.size() < 20) {
            throw new IllegalStateException("the table must pin down at least 20 pairs, found " + rows.size());
        }
        return rows.stream();
    }

    private static String fixture(String name) {
        return readFixture(FIXTURES + name);
    }

    private static String readFixture(String resource) {
        try (InputStream in = UrlCanonicalizerTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
