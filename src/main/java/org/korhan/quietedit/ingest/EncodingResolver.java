package org.korhan.quietedit.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.korhan.quietedit.versioning.CharsetSource;
import org.korhan.quietedit.versioning.EncodingVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which charset a fetched body is actually in: byte order mark, then the HTTP
 * header, then the document's own declaration, then UTF-8 -- the document's declaration
 * last of the three because it travels with a copy of the document and survives a
 * re-encoding that changed the bytes underneath it.
 *
 * <p>Disagreements are reported, never quietly resolved: {@link #read} returns the
 * warnings so they can be asserted on, {@link #resolve} logs them.
 *
 * <p>Bytes that are not valid in the chosen charset produce U+FFFD rather than a second
 * guess, because inferring an encoding from the shape of the bytes is content detection;
 * the substitution is deterministic, so the content hash stays stable and no phantom
 * change is reported.
 *
 * <p>The {@link EncodingVerdict} carries that substitution as a flag, because once the
 * U+FFFD are in the text nothing downstream can tell them from characters the publisher
 * meant to write.
 *
 * <p>The label rules taken from the WHATWG Encoding Standard and the known weaknesses
 * are justified in quietedit-l2s.
 */
final class EncodingResolver {

    private static final Logger log = LoggerFactory.getLogger(EncodingResolver.class);

    /**
     * WHATWG stops at 1024; we are not a browser and can afford four times that, which
     * covers the CMS templates that push a long comment ahead of the meta tag.
     */
    private static final int PRESCAN_BYTES = 4096;

    /** Anchored: an XML declaration is only one if it is the first thing in the document. */
    private static final Pattern XML_DECLARATION = Pattern.compile(
            "^\\s*<\\?xml\\s[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Charset WINDOWS_1252 = charsetOrNull("windows-1252");

    private EncodingResolver() {
    }

    /**
     * The verdict is returned rather than only logged because a warning is read by a
     * human, once, if anyone is looking, while the same fact has to survive to the version
     * store, where it is what separates mojibake from prose.
     */
    record Decoded(String text, EncodingVerdict verdict, List<String> warnings) {

        Decoded {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param origin the feed or article URL, so that a warning names the page it is about
     */
    static Decoded resolve(byte[] body, String contentType, String origin) {
        Decoded decoded = read(body, contentType);
        for (String warning : decoded.warnings()) {
            log.warn("{}: {}", origin, warning);
        }
        return decoded;
    }

    /** The decision itself, with no logging, so that a test can assert on the warnings. */
    static Decoded read(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return new Decoded("", new EncodingVerdict(StandardCharsets.UTF_8, CharsetSource.DEFAULT, false),
                    List.of());
        }

        List<String> warnings = new ArrayList<>();
        Bom bom = Bom.of(body);
        List<Declaration> declarations = new ArrayList<>();
        add(declarations, CharsetSource.BOM, bom == null ? null : bom.charsetName(), warnings);
        add(declarations, CharsetSource.HTTP_HEADER, charsetParameter(contentType), warnings);
        add(declarations, CharsetSource.DOCUMENT, documentLabel(body, bom), warnings);

        Declaration winner = declarations.isEmpty()
                ? new Declaration(CharsetSource.DEFAULT, "utf-8", StandardCharsets.UTF_8)
                : declarations.getFirst();
        for (Declaration loser : declarations.subList(declarations.isEmpty() ? 0 : 1, declarations.size())) {
            if (!loser.charset().equals(winner.charset())) {
                warnings.add("charset conflict: %s says %s, %s says %s; decoding as %s (%s wins)".formatted(
                        winner.source().label(), winner.charset().name(),
                        loser.source().label(), loser.charset().name(),
                        winner.charset().name(), winner.source().label()));
            }
        }

        int offset = bom == null ? 0 : bom.length();
        Text text = decodeText(body, offset, winner, warnings);
        return new Decoded(text.value(),
                new EncodingVerdict(winner.charset(), winner.source(), text.replaced()), warnings);
    }

    /** Decoded text and whether producing it cost any characters. */
    private record Text(String value, boolean replaced) {
    }

    /**
     * Strict first, so that "these bytes are not what the document says they are" is
     * noticed at all; replacement second, because a page with a handful of bad bytes is
     * still a page worth monitoring.
     */
    private static Text decodeText(byte[] body, int offset, Declaration winner, List<String> warnings) {
        ByteBuffer bytes = ByteBuffer.wrap(body, offset, body.length - offset);
        try {
            return new Text(stripLeadingBom(winner.charset().newDecoder().decode(bytes.duplicate()).toString()),
                    false);
        } catch (CharacterCodingException e) {
            warnings.add("the bytes are not valid %s, which %s declared; decoding with replacement characters"
                    .formatted(winner.charset().name(), winner.source().label()));
        }
        CharsetDecoder lenient = winner.charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return new Text(stripLeadingBom(lenient.decode(bytes.duplicate()).toString()), true);
        } catch (CharacterCodingException impossible) {
            // A decoder set to REPLACE cannot report an error; UTF-8 is the last resort.
            return new Text(
                    stripLeadingBom(new String(body, offset, body.length - offset, StandardCharsets.UTF_8)), true);
        }
    }

    /**
     * A skipped BOM leaves nothing behind, but a {@code UTF-16} decoder consumes one into
     * a U+FEFF, which must stay out of the text the parsers and the hash see.
     */
    private static String stripLeadingBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static void add(List<Declaration> declarations, CharsetSource source, String label,
                            List<String> warnings) {
        if (label == null) {
            return;
        }
        Charset charset = charsetFor(source, label, warnings);
        if (charset != null) {
            declarations.add(new Declaration(source, label, charset));
        }
    }

    /**
     * @return the charset to use for {@code label}, or null when the label is unusable
     *         and the source should be treated as if it had said nothing
     */
    private static Charset charsetFor(CharsetSource source, String label, List<String> warnings) {
        String normalised = label.trim().toLowerCase(Locale.ROOT);
        if (source == CharsetSource.DOCUMENT && normalised.startsWith("utf-16")) {
            warnings.add(("the document declares %s, but its declaration was readable as single-byte text, "
                    + "so it cannot be UTF-16; ignoring it").formatted(label));
            return null;
        }
        if (WINDOWS_1252 != null && isLatin1Label(normalised)) {
            return WINDOWS_1252;
        }
        Charset charset = charsetOrNull(label);
        if (charset == null) {
            warnings.add("%s declares the unknown charset '%s'; ignoring it".formatted(source.label(), label));
        }
        return charset;
    }

    /**
     * Matched by name rather than by {@code Charset.equals}, so that the WHATWG mapping to
     * windows-1252 is decided before the JDK's own alias table folds them together.
     */
    private static boolean isLatin1Label(String normalised) {
        return switch (normalised) {
            case "iso-8859-1", "iso8859-1", "iso8859_1", "iso_8859-1", "iso_8859_1",
                 "latin1", "l1", "8859_1", "819", "cp819", "ibm819", "ibm-819", "csisolatin1" -> true;
            default -> false;
        };
    }

    private static Charset charsetOrNull(String label) {
        try {
            return Charset.forName(label.trim());
        } catch (RuntimeException e) {
            // RuntimeException: the two charset exceptions, plus what a blank label throws.
            return null;
        }
    }

    /**
     * The prescan is decoded with the BOM's charset when there is one, so that a UTF-16
     * document's contradicting meta tag is still reportable, and as ISO-8859-1 otherwise,
     * which maps every byte to a character and so cannot throw.
     */
    private static String documentLabel(byte[] body, Bom bom) {
        int offset = bom == null ? 0 : bom.length();
        Charset prescanCharset = StandardCharsets.ISO_8859_1;
        if (bom != null) {
            Charset bomCharset = charsetOrNull(bom.charsetName());
            prescanCharset = bomCharset == null ? StandardCharsets.ISO_8859_1 : bomCharset;
        }
        int length = Math.min(PRESCAN_BYTES, body.length - offset);
        CharsetDecoder decoder = prescanCharset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String prescan;
        try {
            prescan = decoder.decode(ByteBuffer.wrap(body, offset, length)).toString();
        } catch (CharacterCodingException impossible) {
            return null;
        }

        Matcher xml = XML_DECLARATION.matcher(prescan);
        if (xml.find()) {
            return trimToNull(xml.group(1));
        }
        return metaLabel(prescan);
    }

    /**
     * Both spellings, whichever comes first. Parsed with jsoup rather than by regex
     * because the prescan is truncated markup by definition.
     */
    private static String metaLabel(String prescan) {
        for (Element meta : Jsoup.parse(prescan).select("meta")) {
            String label = trimToNull(meta.attr("charset"));
            if (label != null) {
                return label;
            }
            if (meta.attr("http-equiv").trim().equalsIgnoreCase("content-type")) {
                label = charsetParameter(meta.attr("content"));
                if (label != null) {
                    return label;
                }
            }
        }
        return null;
    }

    /** The {@code charset} parameter of a {@code Content-Type}, header or meta alike. */
    private static String charsetParameter(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String parameter = part.trim();
            if (parameter.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String value = parameter.substring("charset=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return trimToNull(value);
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** One source's claim, with the charset it resolved to. */
    private record Declaration(CharsetSource source, String label, Charset charset) {
    }

    /**
     * Longest first: {@code FF FE 00 00} is UTF-32LE and also starts with the UTF-16LE
     * mark, so the wider one has to be tested first.
     */
    private enum Bom {

        UTF_8(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "UTF-8"),
        UTF_32BE(new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF}, "UTF-32BE"),
        UTF_32LE(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}, "UTF-32LE"),
        UTF_16BE(new byte[]{(byte) 0xFE, (byte) 0xFF}, "UTF-16BE"),
        UTF_16LE(new byte[]{(byte) 0xFF, (byte) 0xFE}, "UTF-16LE");

        private final byte[] magic;
        private final String charsetName;

        Bom(byte[] magic, String charsetName) {
            this.magic = magic;
            this.charsetName = charsetName;
        }

        static Bom of(byte[] body) {
            for (Bom bom : values()) {
                if (bom.matches(body)) {
                    return bom;
                }
            }
            return null;
        }

        private boolean matches(byte[] body) {
            if (body.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (body[i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }

        String charsetName() {
            return charsetName;
        }

        int length() {
            return magic.length;
        }
    }
}
