package org.korhan.quietedit.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a response is an HTML article or something we have no business
 * versioning.
 *
 * <p>Two independent checks, because either alone is wrong on real sites: the declared
 * {@code Content-Type}, which is what lets a PDF be skipped before its body is
 * downloaded, and the first bytes, for the server that declares nothing and the one that
 * declares {@code text/html} while serving a PDF.
 *
 * <p>Sniffing only ever rejects. A recognised binary signature proves "not an article",
 * while the absence of one proves nothing, so an undeclared body without a known
 * signature is accepted and left to the parser.
 */
final class HtmlDetection {

    /** Content types an article may legitimately arrive as. */
    private static final List<String> HTML_TYPES = List.of("text/html", "application/xhtml+xml");

    /** Declarations that say nothing, and therefore have to be settled by sniffing. */
    private static final List<String> UNDECLARED_TYPES = List.of(
            "application/octet-stream", "binary/octet-stream", "unknown/unknown", "*/*");

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("PDF", bytes("%PDF-")),
            new Signature("PNG", new byte[]{(byte) 0x89, 'P', 'N', 'G'}),
            new Signature("JPEG", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            new Signature("GIF", bytes("GIF8")),
            new Signature("BMP", bytes("BM")),
            new Signature("RIFF container (WebP, WAV)", bytes("RIFF")),
            new Signature("ZIP container (docx, epub)", new byte[]{'P', 'K', 0x03, 0x04}),
            new Signature("gzip", new byte[]{(byte) 0x1F, (byte) 0x8B}),
            new Signature("ISO media (mp4)", bytes("ftyp"), 4),
            new Signature("Ogg", bytes("OggS")),
            new Signature("ICO", new byte[]{0x00, 0x00, 0x01, 0x00}),
            new Signature("ELF", new byte[]{0x7F, 'E', 'L', 'F'}));

    private HtmlDetection() {
    }

    /**
     * @return true if the declared type is HTML, or is missing/opaque enough that
     *         only the body can decide
     */
    static boolean mayBeHtml(String contentType) {
        String mediaType = mediaType(contentType);
        if (mediaType.isEmpty() || UNDECLARED_TYPES.contains(mediaType)) {
            return true;
        }
        return HTML_TYPES.contains(mediaType);
    }

    /** The media type without parameters, lower-cased; empty when nothing was declared. */
    static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the name of the recognised binary format, or {@code null} if the bytes
     *         could be HTML
     */
    static String binarySignature(byte[] body) {
        if (body == null) {
            return null;
        }
        for (Signature signature : SIGNATURES) {
            if (signature.matches(body)) {
                return signature.name();
            }
        }
        return null;
    }

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * {@code offset} exists for the one signature that is not at the start of the file: an
     * ISO media file begins with a four-byte length, then {@code ftyp}.
     */
    private record Signature(String name, byte[] magic, int offset) {

        Signature(String name, byte[] magic) {
            this(name, magic, 0);
        }

        boolean matches(byte[] body) {
            if (body.length < offset + magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (body[offset + i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
