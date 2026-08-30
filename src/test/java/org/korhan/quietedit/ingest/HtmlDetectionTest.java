package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * The "is this an article at all" decision. Both halves are load-bearing: the header
 * check is what lets a 40 MB PDF be skipped before it is downloaded, and the byte
 * check is what catches the servers that declare {@code text/html} for everything
 * they serve.
 */
class HtmlDetectionTest {

    @Test
    void htmlContentTypesPassTheHeaderCheck() {
        assertThat(HtmlDetection.mayBeHtml("text/html")).isTrue();
        assertThat(HtmlDetection.mayBeHtml("text/html; charset=iso-8859-1")).isTrue();
        assertThat(HtmlDetection.mayBeHtml("TEXT/HTML;charset=UTF-8")).isTrue();
        assertThat(HtmlDetection.mayBeHtml("application/xhtml+xml")).isTrue();
    }

    @Test
    void binaryAndForeignContentTypesFailTheHeaderCheck() {
        assertThat(HtmlDetection.mayBeHtml("application/pdf")).isFalse();
        assertThat(HtmlDetection.mayBeHtml("image/jpeg")).isFalse();
        assertThat(HtmlDetection.mayBeHtml("video/mp4")).isFalse();
        assertThat(HtmlDetection.mayBeHtml("application/rss+xml")).isFalse();
        assertThat(HtmlDetection.mayBeHtml("text/plain")).isFalse();
    }

    @Test
    void anUndeclaredOrOpaqueTypeIsLeftToTheBytes() {
        assertThat(HtmlDetection.mayBeHtml(null)).isTrue();
        assertThat(HtmlDetection.mayBeHtml("  ")).isTrue();
        assertThat(HtmlDetection.mayBeHtml("application/octet-stream")).isTrue();
    }

    @Test
    void knownBinaryHeadersAreRecognisedInTheBody() {
        assertThat(HtmlDetection.binarySignature(ascii("%PDF-1.7"))).isEqualTo("PDF");
        assertThat(HtmlDetection.binarySignature(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10})).isEqualTo("PNG");
        assertThat(HtmlDetection.binarySignature(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                .isEqualTo("JPEG");
        assertThat(HtmlDetection.binarySignature(ascii("GIF89a"))).isEqualTo("GIF");
        assertThat(HtmlDetection.binarySignature(ascii("    ftypisom"))).contains("mp4");
        assertThat(HtmlDetection.binarySignature(new byte[]{'P', 'K', 3, 4})).contains("docx");
    }

    @Test
    void htmlIsNotMistakenForABinary() {
        assertThat(HtmlDetection.binarySignature(ascii("<!doctype html><html>"))).isNull();
        assertThat(HtmlDetection.binarySignature(ascii("  <html lang=\"de\">"))).isNull();
        assertThat(HtmlDetection.binarySignature(ascii("<?xml version=\"1.0\"?><html/>"))).isNull();
        assertThat(HtmlDetection.binarySignature(new byte[0])).isNull();
        assertThat(HtmlDetection.binarySignature(null)).isNull();
    }

    @Test
    void mediaTypeDropsParametersAndCase() {
        assertThat(HtmlDetection.mediaType("Text/HTML; charset=UTF-8")).isEqualTo("text/html");
        assertThat(HtmlDetection.mediaType(null)).isEmpty();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
