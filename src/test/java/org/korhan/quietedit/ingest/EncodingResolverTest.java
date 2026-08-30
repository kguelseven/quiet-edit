package org.korhan.quietedit.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.versioning.CharsetSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case here is a body whose declarations disagree with each other, or with its
 * own bytes. The fixtures are byte files, not strings, because a fixture in a
 * {@code .java} source file would already have been decoded by the compiler.
 */
class EncodingResolverTest {

    private static final String FIXTURES = "/encoding/";

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Test
    @DisplayName("a byte order mark outranks both declarations")
    void bomWinsOverEveryDeclaration() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(
                fixture("bom-utf8-meta-latin1.html"), "text/html; charset=iso-8859-1");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.BOM);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.verdict().replaced()).isFalse();
        assertThat(decoded.text()).startsWith("<!doctype html>").contains("Bürgermeister", "Fußgänger");
        assertThat(decoded.warnings()).hasSize(2).allSatisfy(w -> assertThat(w).startsWith("charset conflict"));
    }

    @Test
    @DisplayName("the HTTP header outranks the document's own declaration")
    void headerWinsOverTheDocumentDeclaration() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(
                fixture("header-utf8-meta-latin1.html"), "text/html; charset=\"UTF-8\"");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.HTTP_HEADER);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.text()).contains("Bürgermeister", "Straßenbahnen");
        assertThat(decoded.warnings()).singleElement().asString()
                .contains("HTTP Content-Type says UTF-8", "document declaration says windows-1252");
    }

    @Test
    @DisplayName("an XML declaration decides when the header names no charset")
    void theXmlDeclarationIsUsedWhenTheHeaderIsSilent() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(
                fixture("feed-xmldecl-latin1.xml"), "application/rss+xml");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.DOCUMENT);
        assertThat(decoded.verdict().charset()).isEqualTo(WINDOWS_1252.name());
        assertThat(decoded.text()).contains("Straßenbahn fährt wieder", "Über Köln und Düsseldorf");
        assertThat(decoded.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a header charset beats the XML declaration, even when it is the wrong one")
    void theHeaderBeatsTheXmlDeclaration() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(
                fixture("feed-xmldecl-latin1.xml"), "application/rss+xml; charset=utf-8");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.HTTP_HEADER);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.verdict().replaced()).isTrue();
        assertThat(decoded.warnings()).hasSize(2);
        assertThat(decoded.warnings().getFirst()).startsWith("charset conflict");
        assertThat(decoded.warnings().getLast()).isEqualTo(
                "the bytes are not valid UTF-8, which HTTP Content-Type declared; "
                        + "decoding with replacement characters");
    }

    /**
     * The case named in the ticket. Nothing here repairs the page: guessing the real
     * encoding from the bytes is content detection and out of scope, so the loss is
     * made visible instead -- and it has to be the same loss every time, or the article
     * would hash differently on every fetch and be reported as changed.
     */
    @Test
    @DisplayName("a latin-1 page that declares utf-8 is reported, not repaired")
    void aLatin1PageDeclaringUtf8IsReportedNotRepaired() {
        byte[] body = fixture("latin1-declaring-utf8.html");

        EncodingResolver.Decoded decoded = EncodingResolver.read(body, "text/html");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.DOCUMENT);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.warnings()).singleElement().asString()
                .isEqualTo("the bytes are not valid UTF-8, which document declaration declared; "
                        + "decoding with replacement characters");
        assertThat(decoded.text()).contains("�").doesNotContain("Bürgermeister");
        assertThat(EncodingResolver.read(body, "text/html").text()).isEqualTo(decoded.text());
    }

    @Test
    @DisplayName("an unusable charset name is ignored and utf-8 is used")
    void anUnknownCharsetFallsBackToUtf8() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(fixture("bogus-meta-charset.html"), "text/html");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.DEFAULT);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.text()).contains("Bürgermeister");
        assertThat(decoded.warnings()).singleElement().asString()
                .isEqualTo("document declaration declares the unknown charset 'utf-8859-1'; ignoring it");
    }

    @Test
    @DisplayName("iso-8859-1 is decoded as windows-1252, so smart quotes survive")
    void latin1IsDecodedAsWindows1252() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(fixture("cp1252-quotes.html"), "text/html");

        assertThat(decoded.verdict().charset()).isEqualTo(WINDOWS_1252.name());
        assertThat(decoded.text()).contains("“ein Versehen” – mehr nicht");
        assertThat(decoded.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a document that declares utf-16 in readable ascii is refuting itself")
    void aUtf16DeclarationInTheDocumentIsRefused() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(fixture("meta-utf16.html"), "text/html");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.DEFAULT);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(decoded.text()).contains("Bürgermeister");
        assertThat(decoded.warnings()).singleElement().asString().contains("cannot be UTF-16");
    }

    @Test
    @DisplayName("a utf-16 body is decoded from its mark and loses it")
    void aUtf16BomSurvivesAConflictingHeader() {
        EncodingResolver.Decoded decoded = EncodingResolver.read(
                fixture("utf16le-bom.xml"), "application/rss+xml; charset=utf-8");

        assertThat(decoded.verdict().source()).isEqualTo(CharsetSource.BOM);
        assertThat(decoded.verdict().charset()).isEqualTo(StandardCharsets.UTF_16LE.name());
        assertThat(decoded.text()).startsWith("<?xml").contains("<title>Grüße</title>");
        assertThat(decoded.warnings()).singleElement().asString().startsWith("charset conflict");
    }

    /**
     * The flag, not the warning, is what survives the fetch. Asserted against a clean
     * body of the same shape so that the only difference between the two verdicts is
     * whether characters were lost.
     */
    @Test
    @DisplayName("the verdict says whether characters were lost, not only the log")
    void theVerdictRecordsWhetherCharactersWereLost() {
        EncodingResolver.Decoded lossy = EncodingResolver.read(fixture("latin1-declaring-utf8.html"), "text/html");
        EncodingResolver.Decoded clean = EncodingResolver.read(
                fixture("latin1-declaring-utf8.html"), "text/html; charset=iso-8859-1");

        assertThat(lossy.verdict().replaced()).isTrue();
        assertThat(lossy.verdict().describe())
                .isEqualTo("UTF-8 (document declaration), with replacement characters");

        assertThat(clean.verdict().replaced()).isFalse();
        assertThat(clean.verdict().charset()).isEqualTo(WINDOWS_1252.name());
        assertThat(clean.text()).contains("Bürgermeister").doesNotContain("�");
        assertThat(clean.verdict().lossFlippedFrom(lossy.verdict())).isTrue();
    }

    @Test
    @DisplayName("an empty body needs no charset")
    void anEmptyBodyIsEmptyText() {
        assertThat(EncodingResolver.read(null, "text/html").text()).isEmpty();
        assertThat(EncodingResolver.read(new byte[0], null).text()).isEmpty();
        assertThat(EncodingResolver.read(new byte[0], null).warnings()).isEmpty();
    }

    private static byte[] fixture(String name) {
        try (InputStream in = EncodingResolverTest.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
