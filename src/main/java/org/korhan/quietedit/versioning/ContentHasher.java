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
 * Turns the extracted prose of an article into the fingerprint that decides
 * whether a re-fetch is a new revision: an equal hash means "nothing changed,
 * store nothing", a different one means "this is a new {@link DocumentVersion}".
 *
 * <h2>What is hashed, and why not the HTML</h2>
 * The input is the {@link ArticleContent} produced by the extractor -- the
 * headline plus the paragraph list -- never the raw markup. Publishers rewrite
 * markup constantly: build hashes in class names, reordered attributes, a new ad
 * container, a changed CSS bundle. Hashing that would report a change on nearly
 * every re-fetch and drown the real edits.
 *
 * <h2>Insensitive by design</h2>
 * Each field is folded before hashing, so two observations of an unchanged
 * article agree even when the page does not:
 * <ul>
 *   <li><b>Whitespace and invisible characters.</b> Runs collapse to one space,
 *       non-breaking and typographic spaces become that same space, soft hyphens,
 *       zero-width characters and stray control characters disappear. NFC first,
 *       so a precomposed umlaut and a decomposed one are one string.</li>
 *   <li><b>Typographic variants.</b> Curly quotes, guillemets and the German
 *       opening quote fold onto {@code "} and {@code '}; the dash family folds
 *       onto {@code -}; the ellipsis character becomes three dots. A CMS
 *       migration that switches on smart quotes is not an edit.</li>
 *   <li><b>Ad, session and request identifiers</b>, in the {@code name=value}
 *       form as well as bare, are replaced by one marker, so the value they
 *       carry cannot reach the hash.</li>
 *   <li><b>Short relative timestamps</b> ("3 hours ago", "vor 3 Stunden",
 *       "soeben") are replaced by one marker: they change on their own, without
 *       anyone editing the article.</li>
 * </ul>
 *
 * <h2>Sensitive by design</h2>
 * Everything else reaches the hash verbatim, including letter case and
 * punctuation, so a single changed word changes the hash. Paragraph boundaries
 * are part of the input: every field is written length-prefixed, which makes the
 * serialisation injective -- {@code ["ab", "c"]} and {@code ["a", "bc"]} cannot
 * collide, an inserted or deleted paragraph always changes the hash, and a
 * headline moved into the body is not the same content as a headline.
 *
 * <h2>Thresholds, and why these values</h2>
 * <ul>
 *   <li>An opaque token needs an alphanumeric run of at least
 *       {@value #MIN_OPAQUE_RUN} characters containing a digit, or
 *       {@value #MIN_HEX_RUN} hex characters. Below that, false positives start
 *       eating real content: at twelve characters {@code SARS-CoV-2-Variante}
 *       still survives (its longest run is {@code Variante}), while
 *       {@code div-gpt-ad-1712345678901-0} and {@code A93F2B77C1D4E5F6} do
 *       not.</li>
 *   <li>Relative timestamps are folded only up to the unit "day". Longer units
 *       are what prose uses narratively -- "vor drei Jahren begann der Krieg" is
 *       content, not a widget -- and a live counter ticking in years does not
 *       exist.</li>
 * </ul>
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>An edit confined to a masked span is invisible: correcting "vor zwei
 *       Stunden" to "vor drei Stunden" in narrative prose, or swapping one
 *       identifier for another, produces no new version. The opposite mistake --
 *       reporting a change on every fetch -- was judged worse, because it is the
 *       one that makes the output unusable.</li>
 *   <li>The identifier and relative-time vocabularies are curated for English and
 *       German. A publisher counting in another language keeps its ticking
 *       timestamp in the hash and will look edited on every re-check.</li>
 *   <li>A short numeric identifier without a {@code name=} prefix
 *       ("Anzeige 4711") is indistinguishable from a number in the text and stays
 *       in the hash.</li>
 *   <li>Absolute timestamps rendered into the page ("Aktualisiert: 14:32 Uhr")
 *       are content here. Whether such a line is a real edit is the classifier's
 *       question, and it needs the diff that this hash only gates.</li>
 *   <li>The hash answers "identical or not", nothing else. Two paraphrases of one
 *       sentence are as different as two unrelated articles; near-duplicate
 *       detection belongs to the clustering stage.</li>
 * </ul>
 *
 * <h2>Stability</h2>
 * Hashing is a pure function of its input: no clock, no randomness, no default
 * locale ({@link Locale#ROOT} throughout), no network. The {@value #SCHEME} tag in
 * the serialisation makes a future change to these rules a new scheme rather than
 * a silent reinterpretation of stored hashes.
 */
@Service
public class ContentHasher {

    static final String ALGORITHM = "SHA-256";

    /** Commits the digest to this serialisation, so a later rule change is a new scheme. */
    static final String SCHEME = "quietedit/content/1";

    static final int MIN_OPAQUE_RUN = 12;
    static final int MIN_HEX_RUN = 16;

    /**
     * Stand-ins for the spans that must not reach the digest. Control characters,
     * because normalisation strips every control character from the input before
     * inserting them: no article text can spell a marker and pass itself off as a
     * masked span.
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
     * {@code name=value} and {@code name: value} pairs whose name is an identifier
     * name. The name is matched as whole separator-delimited tokens, never as a
     * substring, so {@code shadow: dark} is not read as an {@code ad} parameter.
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

    /**
     * "3 hours ago", "vor wenigen Minuten", "an hour ago". Units stop at the day,
     * for the reason given in the class documentation.
     */
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
     * @param content extracted prose; must not be {@code null}
     * @return the lowercase hex SHA-256 of the canonical serialisation, 64
     *         characters, matching {@code document_version.content_hash}
     */
    public String hash(ArticleContent content) {
        Objects.requireNonNull(content, "content");
        StringBuilder canonical = new StringBuilder(SCHEME).append('\n');
        appendField(canonical, normalize(content.title()));
        for (String paragraph : content.paragraphs()) {
            String normalized = normalize(paragraph);
            if (!normalized.isEmpty()) {
                // A block that was nothing but an ad identifier normalises away
                // completely; keeping it as an empty field would make its mere
                // presence in the markup part of the identity.
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
     * Folds one text into the form that is hashed. The order is deliberate:
     * control characters go before markers are inserted, typographic folding
     * before identifiers are matched (so a token spelled with an en-dash is the
     * same token), and identifier assignments before bare tokens, so
     * {@code sessionId=A93F2B77C1D4E5F6} collapses to one marker instead of a
     * name followed by one.
     *
     * <p>Public: the diff engine folds paragraphs and words with it, and the
     * analysis package folds the entries of a ticker's index line, so that all
     * three agree on when two pieces of text are the same. Tests also read better
     * against it than against a digest.
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
     * A token is opaque when one of its alphanumeric runs looks generated rather
     * than written: long and containing a digit, or long and hex. Judging the runs
     * rather than the whole token is what keeps hyphenated real words
     * ({@code SARS-CoV-2-Variante}) out of the marker.
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
            // A digit inside a run this long marks a counter, an id or a hash;
            // letters alone are a compound noun and have to survive.
            if (digits || (hexOnly && run.length() >= MIN_HEX_RUN)) {
                return true;
            }
        }
        return false;
    }
}
