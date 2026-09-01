package org.korhan.quietedit.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the headline and the body paragraphs of a fetched page, with navigation,
 * consent banners, ad slots, promos, teaser rails, comments and footers gone.
 *
 * <p>The result is content-hashed and diffed, so the same bytes in must produce the
 * same bytes out -- otherwise every re-check of an unchanged article would report a
 * change. Every step is therefore a pure function of the input HTML: no clock, no
 * randomness, no default locale, no network, and nothing that reaches the output is
 * ordered by a hash set's iteration.
 *
 * <p>A container the publisher declares to be the article is trusted over density
 * scoring, the same reasoning as trusting {@code rel=canonical} for identity.
 *
 * <p>The threshold values, the curated furniture vocabulary and the known weaknesses
 * are justified in quietedit-aph.
 */
@Component
public class ArticleExtractor {

    static final int MIN_PROSE_CHARS = 30;
    static final int MIN_SHORT_SENTENCE_CHARS = 8;
    static final int MIN_HEADING_CHARS = 3;
    static final int MAX_LABEL_CHARS = 80;
    static final double MAX_LINK_DENSITY = 0.5;
    static final int MAX_ATTRIBUTE_VALUE_CHARS = 100;

    /**
     * {@code <article>} comes before {@code [itemprop=articleBody]} because the
     * microdata property usually wraps only the body proper, so preferring it would
     * drop the standfirst and the subheadings.
     */
    private static final List<String> CONTENT_ROOTS =
            List.of("article", "[itemprop=articleBody]", "main", "[role=main]");

    private static final String FALLBACK_CONTAINERS = "div, section, td";

    /** Blocks that can carry prose. {@code h1} is absent on purpose: it is the title. */
    private static final String PROSE_BLOCKS = "p, h2, h3, h4, h5, h6, li, blockquote";

    private static final String HEADINGS = "h2, h3, h4, h5, h6";

    private static final String NON_PROSE_TAGS = String.join(", ",
            "script", "style", "noscript", "template", "svg", "math", "iframe", "object",
            "embed", "video", "audio", "canvas", "map", "form", "button", "input", "select",
            "textarea", "label", "fieldset", "nav", "aside", "figure", "figcaption",
            "dialog");

    /** Markers that say "not the main content" without naming a class. */
    private static final String NON_PROSE_ROLES = String.join(", ",
            "[aria-hidden=true]", "[hidden]", "[role=navigation]", "[role=banner]",
            "[role=complementary]", "[role=contentinfo]", "[role=dialog]", "[role=alert]",
            "[role=search]", "[role=menu]", "[role=menubar]", "[role=toolbar]",
            "[role=tablist]", "[role=form]");

    /**
     * Matched as whole tokens rather than as substrings, because {@code ad} as a
     * substring hits {@code lead-in} and {@code headline} and would delete the article.
     */
    private static final Set<String> BOILERPLATE_TOKENS = Set.of(
            "ad", "ads", "adslot", "adbox", "adunit", "advert", "banner", "sponsor",
            "promo", "promotion", "outbrain", "taboola", "plista",
            "cookie", "cookies", "consent", "gdpr", "paywall", "subscribe", "subscription",
            "abo", "newsletter", "signup", "register", "login", "logout", "account",
            "nav", "navigation", "navbar", "menu", "submenu", "breadcrumb", "breadcrumbs",
            "sidebar", "masthead", "toolbar", "utility", "skiplink", "skip",
            "related", "recommended", "recommendations", "teaser", "teasers", "trending",
            "mostread", "viewed", "popular", "meistgelesen", "readmore", "weiterlesen",
            "morelink", "forum", "discussion", "diskussion",
            "comment", "comments", "kommentar", "kommentare", "disqus", "livefyre",
            "social", "share", "sharing", "sharebar", "teilen", "follow", "followus",
            "tags", "taglist", "topics", "byline", "credit", "credits", "copyright",
            "impressum", "datenschutz", "sitemap", "search", "suche", "pagination",
            "pager", "popup", "modal", "overlay", "lightbox", "widget", "player",
            "gallery", "slideshow", "carousel", "footer");

    /** Prefixes for the open-ended members of the same families ({@code empfehlungen}, ...). */
    private static final List<String> BOILERPLATE_PREFIXES = List.of(
            "advert", "anzeig", "werbung", "sponsored", "recommend", "empfehl",
            "abonn", "newslett", "kommentar", "socialmedia");

    /** Stand-alone labels that are furniture no matter which element carries them. */
    private static final Set<String> BOILERPLATE_LABELS = Set.of(
            "anzeige", "anzeigen", "werbung", "advertisement", "advertising",
            "sponsored", "sponsored content", "gesponsert",
            "mehr zum thema", "auch interessant", "lesen sie auch", "das könnte sie auch interessieren",
            "read more", "more on this story", "related topics", "related articles",
            "most viewed", "most read", "meistgelesen", "trending", "im trend",
            "weiterlesen", "artikel weiterlesen", "zur startseite", "zum inhalt springen",
            "kommentare", "comments", "kommentar schreiben", "leave a comment",
            "teilen", "diesen artikel teilen", "share this article", "share", "auf facebook teilen",
            "newsletter", "newsletter abonnieren", "sign up", "log in", "einloggen",
            "folgen sie uns", "follow us", "follow us on twitter",
            "alle rechte vorbehalten", "all rights reserved", "cookie-einstellungen",
            "mehr informationen", "more information", "quelle", "quellen", "source", "sources",
            "foto", "bild");

    /** Spaces jsoup leaves in place. Two spellings of one gap must not hash differently. */
    private static final Pattern EXOTIC_SPACE =
            Pattern.compile("[\\u00a0\\u1680\\u2000-\\u200a\\u202f\\u205f\\u3000]");

    /** Invisible characters CMSs sprinkle into text: soft hyphen, zero width, BOM. */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u00ad\\u200b\\u200c\\u200d\\u2060\\ufeff]");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final Pattern EDGE_PUNCTUATION =
            Pattern.compile("^[\\p{Punct}\\s\\u00bb\\u00ab\\u201c\\u201d\\u2018\\u2019\\u2013\\u2014]+"
                    + "|[\\p{Punct}\\s\\u00bb\\u00ab\\u201c\\u201d\\u2018\\u2019\\u2013\\u2014]+$");

    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?\\u2026][\"'\\u00bb\\u201d]?$");

    /** Splits {@code article_related-box} and {@code articleRelatedBox} alike. */
    private static final Pattern TOKEN_BOUNDARY =
            Pattern.compile("[^\\p{Alnum}]+|(?<=[\\p{Lower}\\p{Digit}])(?=\\p{Upper})");

    /**
     * Marks a {@code data-*} value as a payload rather than a name. Its words describe
     * something the element merely points at: an image path containing {@code /video/}
     * would otherwise delete the paragraph around it.
     */
    private static final Pattern ATTRIBUTE_PAYLOAD = Pattern.compile("[/{}<>\"]");

    /**
     * Never throws: a page this fails on is a page without content, which the caller has
     * to handle either way.
     */
    public ArticleContent extract(String html) {
        if (html == null || html.isBlank()) {
            return ArticleContent.empty();
        }
        Document document = Jsoup.parse(html);
        String title = extractTitle(document);
        prune(document);
        Element root = contentRoot(document);
        pruneNamed(root);
        return new ArticleContent(title, prose(root, title));
    }

    /**
     * A content-root {@code h1} outranks {@code og:title} because the rendered headline
     * is what a silent edit changes while {@code og:title} is a copy CMSs leave stale; a
     * free-standing {@code h1} ranks below it because outside the article an {@code h1}
     * is as likely to be the site logo.
     *
     * <p>Called before pruning, because publishers put the headline inside a
     * {@code <header>} that pruning removes.
     */
    private String extractTitle(Document document) {
        String declared = firstNonBlankText(document.select("[itemprop=headline]"));
        if (!declared.isEmpty()) {
            return declared;
        }
        for (String selector : CONTENT_ROOTS) {
            for (Element root : document.select(selector)) {
                String headline = firstNonBlankText(root.select("h1"));
                if (!headline.isEmpty()) {
                    return headline;
                }
            }
        }
        for (String selector : List.of("meta[property=og:title]", "meta[name=twitter:title]",
                "meta[name=title]")) {
            Element meta = document.selectFirst(selector);
            if (meta != null) {
                String content = normalize(meta.attr("content"));
                if (!content.isEmpty()) {
                    return content;
                }
            }
        }
        String anyHeadline = firstNonBlankText(document.select("h1"));
        return anyHeadline.isEmpty() ? normalize(document.title()) : anyHeadline;
    }

    private String firstNonBlankText(Elements elements) {
        for (Element element : elements) {
            String text = normalize(element.text());
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /**
     * The name-based pass spares any element that is, or contains, a declared content
     * root: without that guard a body wrapper whose class reads like furniture would
     * take the article with it.
     */
    private void prune(Document document) {
        document.select(NON_PROSE_TAGS).remove();
        document.select(NON_PROSE_ROLES).remove();
        removeOutsideContentRoots(document, "header, footer");
        Element body = document.body();
        if (body == null) {
            return;
        }
        for (Element element : body.select("*")) {
            if (element.parent() == null || holdsContentRoot(element)) {
                continue;
            }
            if (namedAsBoilerplate(element)) {
                element.remove();
            }
        }
    }

    /**
     * {@code <header>} and {@code <footer>} are furniture outside the article and
     * content inside it -- the standfirst routinely sits in the article's own
     * {@code <header>} -- so the position decides.
     */
    private void removeOutsideContentRoots(Document document, String selector) {
        for (Element element : document.select(selector)) {
            if (!insideContentRoot(element)) {
                element.remove();
            }
        }
    }

    private boolean insideContentRoot(Element element) {
        for (Element ancestor : element.parents()) {
            for (String selector : CONTENT_ROOTS) {
                if (ancestor.is(selector)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean holdsContentRoot(Element element) {
        for (String selector : CONTENT_ROOTS) {
            if (element.is(selector) || !element.select(selector).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code data-*} vocabulary applies only outside a declared content root, where
     * the same word means the opposite: sueddeutsche.de marks its standfirst
     * {@code data-manual="teaserText"} and NZZ marks a recommendation rail
     * {@code data-ct-type="teaser container title"}.
     */
    private boolean namedAsBoilerplate(Element element) {
        if (hasBoilerplateToken(element.className() + " " + element.id())) {
            return true;
        }
        String declared = declaredNames(element);
        return !declared.isBlank() && !insideContentRoot(element) && hasBoilerplateToken(declared);
    }

    private boolean hasBoilerplateToken(String names) {
        if (names.isBlank()) {
            return false;
        }
        for (String token : TOKEN_BOUNDARY.split(names)) {
            if (token.isEmpty()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (BOILERPLATE_TOKENS.contains(lower)) {
                return true;
            }
            for (String prefix : BOILERPLATE_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * On a utility-CSS page the class attribute says only how a block is painted, and
     * the functional label survives only in the analytics hooks.
     *
     * <p>Values are read, never attribute names: a name is chosen by the framework
     * ({@code data-testid}), a value by the page author.
     */
    private String declaredNames(Element element) {
        StringBuilder names = new StringBuilder();
        for (Attribute attribute : element.attributes()) {
            if (!attribute.getKey().startsWith("data-")) {
                continue;
            }
            String value = attribute.getValue();
            if (value.isEmpty() || value.length() > MAX_ATTRIBUTE_VALUE_CHARS
                    || ATTRIBUTE_PAYLOAD.matcher(value).find()) {
                continue;
            }
            names.append(' ').append(value);
        }
        return names.toString();
    }

    /**
     * Second pass, without the content-root guard, which is what removes a
     * related-stories box the guarded pass had to spare because it wraps teasers marked
     * up as nested {@code <article>} elements. The root itself is the article by
     * decision, whatever its class is called.
     */
    private void pruneNamed(Element root) {
        for (Element element : root.select("*")) {
            if (element == root || element.parent() == null) {
                continue;
            }
            if (namedAsBoilerplate(element)) {
                element.remove();
            }
        }
    }

    /** The declared root with the most prose wins; ties go to the first in document order. */
    private Element contentRoot(Document document) {
        for (String selector : CONTENT_ROOTS) {
            Element best = null;
            int bestLength = 0;
            for (Element candidate : document.select(selector)) {
                int length = proseLength(candidate);
                if (length > bestLength) {
                    best = candidate;
                    bestLength = length;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return densestContainer(document);
    }

    /**
     * A tie goes to the tighter container: an ancestor that adds no prose of its own adds
     * no information either, and picking it would drag in whatever else it wraps.
     */
    private Element densestContainer(Document document) {
        Element body = document.body();
        if (body == null) {
            return document;
        }
        Element best = body;
        int bestLength = proseLength(body);
        for (Element candidate : body.select(FALLBACK_CONTAINERS)) {
            int length = proseLength(candidate);
            if (length > bestLength || (length == bestLength && candidate.parents().contains(best))) {
                best = candidate;
                bestLength = length;
            }
        }
        return best;
    }

    /** Scored by the prose the container would contribute, not by its raw text. */
    private int proseLength(Element container) {
        int length = 0;
        for (String paragraph : prose(container, "")) {
            length += paragraph.length();
        }
        return length;
    }

    /**
     * Innermost blocks only, so that a {@code blockquote} wrapping paragraphs
     * contributes them once instead of twice.
     *
     * @param title dropped from the body when a page repeats the headline as a paragraph
     */
    private List<String> prose(Element root, String title) {
        Elements blocks = root.select(PROSE_BLOCKS);
        Set<Element> selectable = new HashSet<>(blocks);
        List<String> paragraphs = new ArrayList<>();
        List<Boolean> heading = new ArrayList<>();
        List<String> shape = new ArrayList<>();
        for (Element block : blocks) {
            if (hasProseDescendant(block, selectable)) {
                continue;
            }
            String text = normalize(block.text());
            if (keep(block, text) && !text.equals(title)) {
                paragraphs.add(text);
                boolean isHeading = block.is(HEADINGS);
                heading.add(isHeading);
                shape.add(isHeading ? headingShape(block) : "");
            }
        }
        dropTrailingHeadings(paragraphs, heading, shape);
        return paragraphs;
    }

    /**
     * Two headings share a shape when the page renders them the same way, which is as
     * close to "the same kind of heading" as markup gets without naming a publisher. The
     * class attribute is compared verbatim rather than as a token set, which would have
     * to be ordered and would make the key depend on a hash iteration.
     */
    private String headingShape(Element block) {
        return block.normalName() + "|" + block.className();
    }

    /**
     * A heading with nothing left under it is the caption of a box pruning already
     * emptied. Matching such captions by name would need one entry per publisher, while
     * the shape -- heading, then no prose -- is the same everywhere.
     *
     * <p>Four guards keep this off article text: only trailing headings, only short
     * ones, only while they stay outnumbered by the page's paragraphs, and only shapes
     * the page does not also use for a heading that introduces prose. The last is what
     * keeps a ticker's newest entries; the guards and what they still cost are worked
     * out in quietedit-10i.11.
     */
    private void dropTrailingHeadings(List<String> paragraphs, List<Boolean> heading, List<String> shape) {
        Set<String> labelsProse = shapesLabellingProse(heading, shape);
        int trailing = 0;
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            if (!heading.get(i) || paragraphs.get(i).length() > MAX_LABEL_CHARS) {
                break;
            }
            if (labelsProse.contains(shape.get(i))) {
                break;
            }
            trailing++;
        }
        int prose = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (!heading.get(i)) {
                prose++;
            }
        }
        if (trailing >= prose) {
            return;
        }
        paragraphs.subList(paragraphs.size() - trailing, paragraphs.size()).clear();
    }

    /**
     * One witness is enough: a shape used for content anywhere on the page is a content
     * shape, and a ticker only ever offers one witness per entry.
     */
    private Set<String> shapesLabellingProse(List<Boolean> heading, List<String> shape) {
        Set<String> shapes = new HashSet<>();
        for (int i = 0; i < heading.size() - 1; i++) {
            if (heading.get(i) && !heading.get(i + 1)) {
                shapes.add(shape.get(i));
            }
        }
        return shapes;
    }

    private boolean hasProseDescendant(Element block, Set<Element> selectable) {
        for (Element descendant : block.select(PROSE_BLOCKS)) {
            if (descendant != block && selectable.contains(descendant)) {
                return true;
            }
        }
        return false;
    }

    private boolean keep(Element block, String text) {
        if (text.isEmpty() || isBoilerplateLabel(text)) {
            return false;
        }
        if (linkDensity(block) > MAX_LINK_DENSITY) {
            // Holds for headings too: a linked headline belongs to another article.
            return false;
        }
        if (block.is(HEADINGS)) {
            return text.length() >= MIN_HEADING_CHARS;
        }
        return text.length() >= MIN_PROSE_CHARS || looksLikeSentence(text);
    }

    private boolean isBoilerplateLabel(String text) {
        if (text.length() > MAX_LABEL_CHARS) {
            return false;
        }
        String label = EDGE_PUNCTUATION.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        return BOILERPLATE_LABELS.contains(label);
    }

    private boolean looksLikeSentence(String text) {
        return text.length() >= MIN_SHORT_SENTENCE_CHARS
                && text.indexOf(' ') > 0
                && SENTENCE_END.matcher(text).matches();
    }

    /** Share of the block's text that sits inside links. */
    private double linkDensity(Element block) {
        int total = normalize(block.text()).length();
        if (total == 0) {
            return 1.0;
        }
        int linked = 0;
        for (Element link : block.select("a")) {
            linked += normalize(link.text()).length();
        }
        return Math.min(1.0, (double) linked / total);
    }

    /**
     * Folds what jsoup does not treat as whitespace at all, so that two spellings of the
     * same visible text produce one string -- the property the content hash depends on.
     */
    private String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = INVISIBLE.matcher(text).replaceAll("");
        cleaned = EXOTIC_SPACE.matcher(cleaned).replaceAll(" ");
        return WHITESPACE_RUN.matcher(cleaned).replaceAll(" ").trim();
    }
}
