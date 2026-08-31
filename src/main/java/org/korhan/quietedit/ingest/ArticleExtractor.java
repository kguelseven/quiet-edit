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
 * Extracts the article prose out of a fetched page: the headline, and the body
 * paragraphs in reading order, with navigation, consent banners, ad slots,
 * newsletter promos, related-article boxes, comment sections and footers gone.
 *
 * <h2>Determinism is the requirement, not an implementation detail</h2>
 * The result is content-hashed and diffed, so the same bytes in must produce the
 * same bytes out -- otherwise every re-check of an unchanged article would report
 * a change. Every step here is therefore a pure function of the input HTML: no
 * clock, no randomness, no default locale ({@link Locale#ROOT} everywhere), no
 * network. Sets are used for membership tests only; nothing that reaches the
 * output is ever ordered by a hash set's iteration order. Output order is jsoup's
 * document order, which follows the source.
 *
 * <h2>Four stages</h2>
 * <ol>
 *   <li><b>Title first, before anything is removed.</b> The page's own
 *       {@code <header>} is furniture and gets deleted; reading the headline first
 *       is what keeps that removal safe.</li>
 *   <li><b>Prune by structure, then by name.</b> Tags that cannot carry prose
 *       ({@code script}, {@code form}, {@code nav}, {@code aside}, {@code figure},
 *       ...) go first, then elements whose class, id or {@code data-*} attributes
 *       name them as furniture.</li>
 *   <li><b>Pick one content root</b> -- declared by the publisher if possible,
 *       otherwise the densest container.</li>
 *   <li><b>Prune by name once more inside that root, then collect its prose
 *       blocks.</b> The second pass is what removes a related-stories box that the
 *       first had to spare; a trailing run of short headings goes with it, because
 *       an emptied box leaves its caption behind.</li>
 * </ol>
 *
 * <h2>Where a page says what a block is</h2>
 * Class and id are the first place, and on a page built with utility CSS they are
 * the wrong place: nothing but Tailwind classes reaches the containers, and the
 * functional label sits in a {@code data-*} attribute the analytics layer needs.
 * NZZ names a recommendation rail {@code data-ct-type="teaser container title"} and
 * its games strip {@code data-source-element-widget="Widget-Slider"} while their
 * class attributes say only how they are painted. Those values are therefore read
 * as names too -- but only outside a declared content root, and only when the value
 * looks like a name rather than a payload. Both restrictions are false-positive
 * guards, spelled out at {@link #namedAsBoilerplate} and {@link #declaredNames}.
 *
 * <h2>Why the publisher's own markup decides the content root</h2>
 * When a page declares {@code [itemprop=articleBody]}, {@code <article>},
 * {@code <main>} or {@code [role=main]}, that declaration is used directly instead
 * of scoring containers -- the same reasoning as trusting {@code rel=canonical} for
 * identity: a publisher's explicit statement about its own page beats a heuristic
 * guess about it. Density scoring is the fallback for pages that declare nothing,
 * where the container holding the most qualifying prose wins and, on a tie, the
 * tightest of the tied containers wins (a wrapper that adds no text of its own
 * adds no information either).
 *
 * <h2>Thresholds, and why these values</h2>
 * <ul>
 *   <li>{@value #MIN_PROSE_CHARS} characters minimum for a body block. Captions,
 *       bylines, credits, tag chips and button labels are almost always shorter
 *       than a real sentence; this single cut-off removes more furniture than any
 *       name-based rule.</li>
 *   <li>A shorter block survives anyway if it looks like a real sentence -- at
 *       least {@value #MIN_SHORT_SENTENCE_CHARS} characters, containing a space
 *       and ending in sentence punctuation. Newsrooms do write "Er schwieg."; the
 *       cost is that a caption written as a full sentence also survives.</li>
 *   <li>Link density above {@value #MAX_LINK_DENSITY} drops the block. Real prose
 *       cites links but rarely spends half its text inside them, while teaser
 *       rows, tag lists and "most read" items sit near 1.0.</li>
 *   <li>Named labels ({@code Anzeige}, {@code Mehr zum Thema}, {@code Read more},
 *       ...) are matched only against blocks of at most
 *       {@value #MAX_LABEL_CHARS} characters, so a paragraph that merely discusses
 *       advertising or cookies is never mistaken for one.</li>
 * </ul>
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>The vocabulary is curated (English and German) and will miss a newsroom
 *       that names its furniture differently. The failure is visible rather than
 *       silent: the extra block shows up in the extraction fixture.</li>
 *   <li>A ticker's newest entries are told from an emptied rail's caption by the
 *       heading shape the page reuses for entries that do carry prose, so a rail
 *       caption a publisher styles exactly like its body subheadings survives.
 *       Measured over the stored captures this keeps one such caption per page on
 *       one watson.ch layout and costs nothing anywhere else. It is furniture that
 *       never changes, so it does not report an edit; it only lengthens the
 *       revision.</li>
 *   <li>A ticker entry's body reaches the extraction only when the publisher wraps
 *       it in a prose block. watson.ch puts it in a {@code div}, so a transfer
 *       ticker extracts to its entry headlines plus the embedded posts, and an edit
 *       to an entry's text goes unnoticed. Tracked separately.</li>
 *   <li>The same vocabulary matched against a whole element name could remove real
 *       content: a body wrapper called {@code content-share-wrapper} names itself
 *       like furniture. Token matching (split on separators and camel-case
 *       boundaries) keeps that from firing on {@code lead-in} or {@code headline},
 *       and the content root is never removed, but a wrapper <em>inside</em> the
 *       root that is named like furniture still takes its text with it.</li>
 *   <li>{@code <figure>} is removed wholesale, so a pull quote that a publisher
 *       marked up as a figure is lost with the image captions.</li>
 *   <li>Text that sits in no prose block at all is dropped -- a quote attribution
 *       written as {@code <blockquote><footer>} being the common case. The quote
 *       survives, its source does not.</li>
 *   <li>A teaser box that is named like content and marked up like content is
 *       indistinguishable from content here; only its length and link density can
 *       still catch it, and a wordy teaser passes both.</li>
 *   <li>Only the innermost prose block is emitted, so text sitting directly in an
 *       outer {@code <li>} that also contains a nested list is dropped.</li>
 *   <li>The {@code <title>} fallback is used verbatim, including any
 *       " | Site Name" suffix. Guessing the separator would truncate a headline
 *       that legitimately contains a dash, and the fallback is only reached when a
 *       page declares neither {@code og:title} nor an {@code h1}.</li>
 *   <li>Text injected by JavaScript is invisible here: only the fetched HTML is
 *       parsed, by design.</li>
 * </ul>
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
     * Containers a publisher declares to be the article, in the order they are
     * trusted. {@code <article>} comes before {@code [itemprop=articleBody]} on
     * purpose: the microdata property usually wraps only the body proper, so
     * preferring it would silently drop the standfirst and the subheadings, and a
     * later edit to the standfirst would go unnoticed.
     */
    private static final List<String> CONTENT_ROOTS =
            List.of("article", "[itemprop=articleBody]", "main", "[role=main]");

    /** Wrappers the density fallback is allowed to choose between. */
    private static final String FALLBACK_CONTAINERS = "div, section, td";

    /** Blocks that can carry prose. {@code h1} is absent on purpose: it is the title. */
    private static final String PROSE_BLOCKS = "p, h2, h3, h4, h5, h6, li, blockquote";

    private static final String HEADINGS = "h2, h3, h4, h5, h6";

    /** Tags that never contain article prose, or contain only its decoration. */
    private static final String NON_PROSE_TAGS = String.join(", ",
            "script", "style", "noscript", "template", "svg", "math", "iframe", "object",
            "embed", "video", "audio", "canvas", "map", "form", "button", "input", "select",
            "textarea", "label", "fieldset", "nav", "aside", "figure", "figcaption",
            "dialog");

    /** ARIA and HTML markers that say "not the main content" without naming a class. */
    private static final String NON_PROSE_ROLES = String.join(", ",
            "[aria-hidden=true]", "[hidden]", "[role=navigation]", "[role=banner]",
            "[role=complementary]", "[role=contentinfo]", "[role=dialog]", "[role=alert]",
            "[role=search]", "[role=menu]", "[role=menubar]", "[role=toolbar]",
            "[role=tablist]", "[role=form]");

    /**
     * Whole class/id tokens that mark furniture. Matched as tokens rather than as
     * substrings, because {@code ad} as a substring hits {@code lead-in} and
     * {@code headline} and would delete the article.
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

    /**
     * Prefixes for the open-ended members of the same families
     * ({@code advertising}, {@code anzeigen}, {@code empfehlungen}, ...).
     */
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

    /**
     * Whitespace jsoup's own normalisation leaves in place -- non-breaking and
     * typographic spaces. Two spellings of the same gap must not hash differently.
     */
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
     * Characters that mark a {@code data-*} value as a payload rather than a name:
     * a URL, a JSON blob, embedded markup. Such a value is skipped, because the
     * words inside it describe something the element merely points at -- an image
     * path containing {@code /video/} would otherwise delete the paragraph around
     * it.
     */
    private static final Pattern ATTRIBUTE_PAYLOAD = Pattern.compile("[/{}<>\"]");

    /**
     * @param html the fetched page, may be {@code null} or blank
     * @return the extracted prose; {@link ArticleContent#empty()} for input that
     *         yields nothing. Never throws: a page this fails on is a page without
     *         content, which the caller has to handle either way.
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
     * Order of authority: the machine-readable headline, then the {@code h1} inside
     * a declared content root, then the social-preview headline, then any
     * {@code h1}, then the browser title.
     *
     * <p>A content-root {@code h1} outranks {@code og:title} because the rendered
     * headline is the thing a silent edit changes, while {@code og:title} is a
     * copy that CMSs sometimes leave stale. A free-standing {@code h1} ranks
     * <em>below</em> {@code og:title} for the opposite reason: outside the article
     * an {@code h1} is as likely to be the site logo as the headline.
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
     * Removes furniture in two passes. The name-based pass never removes an element
     * that is, or contains, a declared content root -- without that guard a body
     * wrapper whose class happens to read like furniture would take the article
     * with it.
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
     * {@code <header>} and {@code <footer>} are page furniture outside the article
     * and article content inside it: the standfirst is routinely inside the
     * article's own {@code <header>}. Removing them unconditionally would cost the
     * standfirst, so the position decides.
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
     * The class and id vocabulary applies everywhere; the {@code data-*} vocabulary
     * only outside a declared content root. Inside one, the publisher has already
     * said which element is the article, and the same word can mean the opposite
     * there: sueddeutsche.de marks its standfirst {@code data-manual="teaserText"},
     * where NZZ marks a recommendation rail {@code data-ct-type="teaser container
     * title"}. Reading {@code data-*} inside the declared article would drop the
     * standfirst -- the very sentence a silent edit is most likely to touch.
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
     * What the page's own {@code data-*} attributes say this block is. Analytics and
     * testing hooks carry that statement long after the class attribute stopped
     * carrying it: a Tailwind page has nothing but utility classes on its
     * containers, so NZZ's recommendation rail is named only by
     * {@code data-ct-type="teaser container title"}.
     *
     * <p>Attribute <em>names</em> are deliberately not read, only values: a name is
     * chosen by the framework ({@code data-testid}), a value by the page author, and
     * only the second describes this block.
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
     * Second name-based pass, without the content-root guard, inside the container
     * that was actually chosen. This is what removes a related-stories box that the
     * guarded pass had to spare because it wraps teasers marked up as nested
     * {@code <article>} elements. The root itself is never removed: at this point it
     * is the article by decision, whatever its class is called.
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

    /**
     * The declared content root with the most prose wins; ties go to the first in
     * document order. Only a page that declares none of them reaches the density
     * fallback.
     */
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
     * Falls back to {@code body} so a page with prose directly under it is not
     * lost. A tie is resolved towards the tighter container: an ancestor that
     * contributes no prose of its own beyond its descendant's contributes no
     * information either, and picking it would drag in whatever else it wraps.
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

    /** Scores a container by the prose it would actually contribute, not by its raw text. */
    private int proseLength(Element container) {
        int length = 0;
        for (String paragraph : prose(container, "")) {
            length += paragraph.length();
        }
        return length;
    }

    /**
     * Collects the innermost prose blocks in document order. Innermost, so that a
     * {@code blockquote} wrapping paragraphs contributes its paragraphs once
     * instead of twice.
     *
     * @param title already-extracted headline, dropped from the body when a page
     *              repeats it as a paragraph
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
     * How a page styles one heading, as an equality key: the tag plus the verbatim
     * class attribute. Two headings share a shape when the page renders them the
     * same way, which is as close to "these are the same kind of heading" as markup
     * gets without naming a publisher. The class attribute is used as it stands
     * rather than as a token set, because a set would have to be ordered before it
     * could be compared and ordering it by anything but the source would make the
     * key depend on a hash iteration -- see the determinism note on this class.
     */
    private String headingShape(Element block) {
        return block.normalName() + "|" + block.className();
    }

    /**
     * A heading with nothing left under it is the caption of a box that pruning
     * already emptied, not a section of the article: "Mehr zum Thema", "Neueste
     * Artikel", "Artikel von NZZ Bellevue" are what remains once their teasers are
     * gone. Matching them by name would need one entry per publisher, while the
     * shape -- heading, then no prose -- is the same everywhere and needs none.
     *
     * <p>Four guards keep this from eating article text.
     * <ul>
     *   <li>Only <em>trailing</em> headings qualify. A heading in the middle of the
     *       body is followed by prose by definition, and a rail between two halves
     *       of an article is rare enough not to trade the risk for it.</li>
     *   <li>Only headings of at most {@value #MAX_LABEL_CHARS} characters, the same
     *       cut-off the named labels use. Newsrooms do put body text in a heading tag
     *       -- 20min.ch writes the whole text of a video page into an {@code h4} --
     *       and a block that long is prose whatever tag carries it.</li>
     *   <li>Only while the trailing headings stay outnumbered by the paragraphs on
     *       the page -- paragraphs, not blocks, because a heading is a label only if
     *       there is prose it could have labelled. A page that is mostly headings is
     *       a list and its headings are its content: a liveblog whose entry bodies
     *       load later leaves nothing but entry titles.</li>
     *   <li>Only headings whose {@link #headingShape shape} the page does not also
     *       use for a section that <em>does</em> carry prose. This is what keeps a
     *       ticker's newest entries: watson.ch renders every entry title as the same
     *       {@code h5.liveticker__entry__title}, so a trailing one whose body is
     *       missing from the fetched HTML is still recognisably an entry title, while
     *       NZZ's rail captions share a shape only with each other and with nothing
     *       that has prose under it. Per shape rather than per page, so a ticker that
     *       ends with rail captions still loses the captions and keeps the entries:
     *       the backward scan drops captions until it reaches the first entry
     *       title.</li>
     * </ul>
     * What is left to lose is a short article that genuinely ends on a heading whose
     * shape appears nowhere else on the page, and a rail caption a publisher happens
     * to style exactly like its body subheadings.
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
     * The heading shapes the page uses to introduce prose: a shape qualifies once
     * any heading carrying it is immediately followed by a paragraph. One witness is
     * enough -- a shape used for content anywhere on the page is a content shape,
     * and a ticker only ever offers one witness per entry.
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
            // A block that is mostly link text is a teaser or a tag row, and that
            // holds for headings too: a linked headline belongs to another article.
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
     * jsoup already collapses ASCII whitespace in {@code text()}; what remains are
     * the characters it does not treat as whitespace at all. They are folded here
     * so that two spellings of the same visible text produce one string -- the
     * property the content hash depends on.
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
