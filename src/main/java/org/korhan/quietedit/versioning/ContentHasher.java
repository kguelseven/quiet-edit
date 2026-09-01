package org.korhan.quietedit.versioning;

import org.korhan.quietedit.ingest.ArticleContent;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the extracted prose into the fingerprint that decides whether a re-fetch is a
 * new revision.
 *
 * <p>The input is the extractor's {@link ArticleContent}, never the raw markup, because
 * publishers rewrite markup constantly and hashing it would report a change on nearly
 * every re-fetch.
 *
 * <p>Each field is folded first -- whitespace, invisible characters, typographic
 * variants, identifiers and short relative timestamps -- so that two observations of an
 * unchanged article agree even when the page does not; everything else, letter case and
 * punctuation included, reaches the digest verbatim.
 *
 * <p>Every field is written length-prefixed, which makes the serialisation injective:
 * {@code ["ab", "c"]} and {@code ["a", "bc"]} cannot collide and a headline moved into
 * the body is not the same content as a headline.
 *
 * <p>The {@value #SCHEME} tag makes a future change to these rules a new scheme rather
 * than a silent reinterpretation of stored hashes; the threshold values and the known
 * weaknesses are justified in quietedit-es7.
 */
@Service
public class ContentHasher {

    static final String ALGORITHM = "SHA-256";

    /** Commits the digest to this serialisation, so a later rule change is a new scheme. */
    static final String SCHEME = "quietedit/content/1";

    static final int MIN_OPAQUE_RUN = 12;
    static final int MIN_HEX_RUN = 16;

    /**
     * Control characters, because normalisation strips every control character from the
     * input before inserting them: no article text can spell a marker and pass itself off
     * as a masked span.
     */
    private static final String IDENTIFIER = String.valueOf((char) 1);
    private static final String RELATIVE_TIME = String.valueOf((char) 2);

    /** Line and tab breaks become a space, so folding them cannot glue two words together. */
    private static final Pattern LINE_BREAK =
            Pattern.compile("[\\t\\n\\u000b\\f\\r\\u0085\\u2028\\u2029]");

    /** Non-breaking, en/em and other typographic spaces jsoup does not treat as whitespace. */
    private static final Pattern EXOTIC_SPACE =
            Pattern.compile("[\\u00a0\\u1680\\u2000-\\u200a\\u202f\\u205f\\u3000]");

    /** Invisible characters CMSs sprinkle in, plus every remaining control character. */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u00ad\\u200b-\\u200f\\u2060\\ufeff\\p{Cc}]");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final Pattern DOUBLE_QUOTE =
            Pattern.compile("[\\u00ab\\u00bb\\u201c\\u201d\\u201e\\u201f\\u2033\\u301d\\u301e\\uff02]");

    private static final Pattern SINGLE_QUOTE =
            Pattern.compile("[\\u2018\\u2019\\u201a\\u201b\\u2032\\u2039\\u203a\\uff07\\u00b4\\u0060]");

    private static final Pattern DASH =
            Pattern.compile("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2043\\u2212\\ufe58\\uff0d]");

    private static final Pattern ELLIPSIS = Pattern.compile("\\u2026");

    /** Canonical UUID spelling, matched on its own because its runs are short. */
    private static final Pattern UUID_FORM = Pattern.compile("(?i)(?<![\\p{Alnum}])"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![\\p{Alnum}])");

    /**
     * The name is matched as whole separator-delimited tokens, never as a substring, so
     * {@code shadow: dark} is not read as an {@code ad} parameter.
     */
    private static final Pattern IDENTIFIER_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![\\p{Alnum}])(?:[\\p{Alnum}]+[_-])*"
                    + "(?:ad|ads|adid|adslot|adslotid|adunit|adunitid|advert|advertid"
                    + "|session|sessionid|sid|ssid|uid|guid|uuid"
                    + "|cid|clientid|visitorid|reqid|requestid|correlationid|traceid|trackingid"
                    + "|token|nonce|impression|impressionid|creative|creativeid|placement|placementid"
                    + "|slot|slotid|campaignid|utm_[\\p{Alpha}]+)"
                    + "(?:[_-][\\p{Alnum}]+)*\\s*[=:]\\s*[\\p{Alnum}%._~+-]{2,}");

    /** Anything that could be an opaque token; {@link #looksOpaque} decides whether it is. */
    private static final Pattern TOKEN_CANDIDATE = Pattern.compile("(?<![\\p{Alnum}_-])"
            + "[\\p{Alnum}][\\p{Alnum}_-]{" + (MIN_OPAQUE_RUN - 1) + ",}(?![\\p{Alnum}_-])");

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[_-]+");

    /** Units stop at the day: longer ones are what prose uses narratively. */
    private static final Pattern RELATIVE_TIME_PHRASE = Pattern.compile("(?i)(?<![\\p{Alnum}])(?:"
            + "vor\\s+(?:\\d{1,3}|einer|einem|eine|ein\\s+paar|wenigen|etwa\\s+\\d{1,3}"
            + "|zwei|drei|vier|f\\u00fcnf|sechs|sieben|acht|neun|zehn|elf|zw\\u00f6lf)"
            + "\\s+(?:sekunden?|minuten?|stunden?|tagen|tag)"
            + "|vor\\s+(?:kurzem|wenigen\\s+augenblicken)"
            + "|soeben|gerade\\s+eben|eben\\s+erst"
            + "|(?:\\d{1,3}|a|an|few|a\\s+few|couple\\s+of|about\\s+\\d{1,3}"
            + "|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
            + "\\s+(?:seconds?|minutes?|mins?|hours?|hrs?|days?)\\s+ago"
            + "|moments\\s+ago|just\\s+now"
            + ")(?![\\p{Alnum}])");

    /**
     * @return the lowercase hex SHA-256 of the canonical serialisation, 64 characters,
     *         matching {@code document_version.content_hash}
     */
    public String hash(ArticleContent content) {
        Objects.requireNonNull(content, "content");
        StringBuilder canonical = new StringBuilder(SCHEME).append('\n');
        appendField(canonical, normalize(content.title()));
        for (String paragraph : content.paragraphs()) {
            String normalized = normalize(paragraph);
            if (!normalized.isEmpty()) {
                // Keeping an emptied block would make its presence in the markup part of the identity.
                appendField(canonical, normalized);
            }
        }
        return digest(canonical.toString());
    }

    /**
     * Length-prefixed so the serialisation is injective: without the prefix, two
     * paragraphs could be re-split without changing the concatenation.
     */
    private void appendField(StringBuilder canonical, String field) {
        canonical.append(field.length()).append(':').append(field).append('\n');
    }

    private String digest(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required by the platform", e);
        }
    }

    /**
     * The order is deliberate: control characters before markers are inserted, typographic
     * folding before identifiers are matched, and identifier assignments before bare
     * tokens, so {@code sessionId=A93F2B77C1D4E5F6} collapses to one marker rather than a
     * name followed by one.
     *
     * <p>Public because the diff engine and the analysis package fold with it too, so that
     * all three agree on when two pieces of text are the same.
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFC);
        folded = LINE_BREAK.matcher(folded).replaceAll(" ");
        folded = EXOTIC_SPACE.matcher(folded).replaceAll(" ");
        folded = INVISIBLE.matcher(folded).replaceAll("");
        folded = DOUBLE_QUOTE.matcher(folded).replaceAll("\"");
        folded = SINGLE_QUOTE.matcher(folded).replaceAll("'");
        folded = DASH.matcher(folded).replaceAll("-");
        folded = ELLIPSIS.matcher(folded).replaceAll("...");
        folded = RELATIVE_TIME_PHRASE.matcher(folded).replaceAll(RELATIVE_TIME);
        folded = UUID_FORM.matcher(folded).replaceAll(IDENTIFIER);
        folded = IDENTIFIER_ASSIGNMENT.matcher(folded).replaceAll(IDENTIFIER);
        folded = maskOpaqueTokens(folded);
        return WHITESPACE_RUN.matcher(folded).replaceAll(" ").trim();
    }

    private String maskOpaqueTokens(String text) {
        Matcher matcher = TOKEN_CANDIDATE.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            matcher.appendReplacement(masked,
                    looksOpaque(token) ? IDENTIFIER : Matcher.quoteReplacement(token));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    /**
     * Judging the alphanumeric runs rather than the whole token is what keeps hyphenated
     * real words ({@code SARS-CoV-2-Variante}) out of the marker.
     */
    private boolean looksOpaque(String token) {
        for (String run : TOKEN_SEPARATOR.split(token)) {
            if (run.length() < MIN_OPAQUE_RUN) {
                continue;
            }
            boolean digits = false;
            boolean hexOnly = true;
            for (int i = 0; i < run.length(); i++) {
                char character = run.charAt(i);
                digits |= Character.isDigit(character);
                hexOnly &= Character.digit(character, 16) >= 0;
            }
            // A digit in a run this long marks a counter or hash; letters alone are a noun.
            if (digits || (hexOnly && run.length() >= MIN_HEX_RUN)) {
                return true;
            }
        }
        return false;
    }
}
