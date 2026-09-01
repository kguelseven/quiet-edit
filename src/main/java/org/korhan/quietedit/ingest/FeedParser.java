package org.korhan.quietedit.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a feed body into a uniform list of {@link FeedEntry}; RSS 2.0, Atom 1.0 and the
 * older dialects that share their element names come out indistinguishable.
 *
 * <p>{@code link} is the one mandatory field, because this system exists to re-fetch and
 * compare an article; an entry that fails that check is dropped with a reason and the
 * rest of the feed is kept.
 *
 * <p>Never throws: a body that is not a feed becomes a failed result, the same way
 * {@link FeedFetcher} turns a dead publisher into one.
 *
 * <p>Input is already-decoded text rather than the fetcher's bytes, because which charset
 * those bytes are in is its own decision with its own conflicting evidence; date fields
 * are handed on verbatim for the same reason, since what a missing timezone means belongs
 * to date normalisation.
 *
 * <p>One traversal of one {@link Parser#xmlParser()} document, so an entry's dates are
 * that entry's by construction rather than by matching two parsers' results by position;
 * the forgiving-parser trade-off and how truncation is recognised without a strict XML
 * parser are justified in quietedit-94i.
 */
@Component
public class FeedParser {

    private static final Logger log = LoggerFactory.getLogger(FeedParser.class);

    /** {@code dc:date} and {@code issued} are how the pre-2.0 dialects say "published". */
    private static final Set<String> PUBLISHED_ELEMENTS = Set.of("pubdate", "date", "published", "issued", "created");

    private static final Set<String> UPDATED_ELEMENTS = Set.of("updated", "modified");

    /** Both spellings of "one article", RSS first and Atom second. */
    private static final Set<String> ENTRY_ELEMENTS = Set.of("item", "entry");

    private static final Set<String> SUMMARY_ELEMENTS = Set.of("description", "summary");

    /** The feed's own identifier: {@code <guid>} in RSS, {@code <id>} in Atom. */
    private static final Set<String> GUID_ELEMENTS = Set.of("guid", "id");

    /** Link relations that point at something other than the article itself. */
    private static final Set<String> NON_ARTICLE_RELS = Set.of("self", "enclosure", "edit", "replies", "hub");

    public FeedParseResult parse(String feedUrl, String body) {
        if (body == null || body.isBlank()) {
            return FeedParseResult.failed("empty body");
        }

        Document document;
        try {
            document = Jsoup.parse(body, "", Parser.xmlParser().setTrackPosition(true));
        } catch (RuntimeException e) {
            return failed(feedUrl, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Element root = document.children().isEmpty() ? null : document.child(0);
        String feedType = feedTypeOf(root);
        if (feedType == null) {
            return failed(feedUrl, "not a feed: root element is "
                    + (root == null ? "absent" : "<" + root.tagName() + ">"));
        }
        Element unclosed = firstUnclosed(document);
        if (unclosed != null) {
            return failed(feedUrl, "truncated XML: <" + unclosed.tagName() + "> is never closed");
        }

        List<FeedEntry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int index = 0;
        for (Element item : document.getAllElements()) {
            if (!ENTRY_ELEMENTS.contains(localName(item.tagName()))) {
                continue;
            }
            String link = linkOf(item);
            if (link == null) {
                String reason = "entry %d has no link (title=%s)".formatted(index, firstChildText(item, Set.of("title")));
                log.warn("Feed {}: skipping {}", feedUrl, reason);
                skipped.add(reason);
            } else {
                entries.add(new FeedEntry(
                        firstChildText(item, Set.of("title")),
                        link,
                        summaryOf(item),
                        firstChildText(item, PUBLISHED_ELEMENTS),
                        firstChildText(item, UPDATED_ELEMENTS),
                        firstChildText(item, GUID_ELEMENTS)));
            }
            index++;
        }

        log.debug("Feed {} parsed as {}: {} entries, {} skipped", feedUrl, feedType, entries.size(), skipped.size());
        return FeedParseResult.parsed(feedType, entries, skipped);
    }

    private static FeedParseResult failed(String feedUrl, String reason) {
        log.warn("Feed {} is not parseable: {}", feedUrl, reason);
        return FeedParseResult.failed(reason);
    }

    /**
     * Kept in rome's spelling so that removing rome stays invisible from the outside.
     *
     * @return null when the root element is not a feed root at all, which is how an error
     *         page served with a feed content type is recognised
     */
    private static String feedTypeOf(Element root) {
        if (root == null) {
            return null;
        }
        return switch (localName(root.tagName())) {
            // RSS carries its dialect in the version attribute: 0.91, 0.92, 2.0.
            case "rss" -> "rss_" + defaultIfBlank(root.attr("version"), "2.0");
            // RSS 1.0 is an RDF document; its version lives nowhere but the namespace.
            case "rdf" -> "rss_1.0";
            case "feed" -> root.attributes().asList().stream()
                    .anyMatch(a -> a.getValue().contains("http://purl.org/atom/ns#"))
                    ? "atom_0.3" : "atom_1.0";
            default -> null;
        };
    }

    /**
     * @return the first element the source never closes, or null when every element has a
     *         real end tag. A self-closing tag has a zero-width end range too, but on its
     *         own start position; an element closed only because the input ran out does not
     */
    private static Element firstUnclosed(Document document) {
        for (Element element : document.getAllElements()) {
            if (element == document) {
                continue;
            }
            Range end = element.endSourceRange();
            if (end.start().pos() == end.end().pos() && end.start().pos() != element.sourceRange().start().pos()) {
                return element;
            }
        }
        return null;
    }

    /**
     * Atom permits several links per entry and only the alternate representation is the
     * article: {@code related} points at a different one, {@code self} at the feed. A
     * non-article relation is never used even as a last resort -- monitoring the wrong URL
     * is worse than skipping the entry.
     */
    private static String linkOf(Element item) {
        String fallback = null;
        for (Element child : item.children()) {
            if (!localName(child.tagName()).equals("link")) {
                continue;
            }
            if (!child.hasAttr("href")) {
                String text = trimToNull(child.wholeText());
                if (text != null) {
                    return text;
                }
                continue;
            }
            String href = trimToNull(child.attr("href"));
            String rel = child.attr("rel").trim().toLowerCase();
            if (href == null || NON_ARTICLE_RELS.contains(rel)) {
                continue;
            }
            if (rel.isEmpty() || rel.equals("alternate")) {
                return href;
            }
            fallback = fallback == null ? href : fallback;
        }
        return fallback;
    }

    /**
     * The description is the teaser a publisher wrote for the feed; full content is a copy
     * of the article and belongs to the fetcher's job.
     */
    private static String summaryOf(Element item) {
        String summary = firstChildValue(item, SUMMARY_ELEMENTS);
        return summary != null ? summary : firstChildValue(item, Set.of("content"));
    }

    /**
     * Matched on the local name: the same field arrives as {@code dc:date}, {@code date}
     * or some publisher's own prefix, and the prefix carries nothing we act on.
     */
    private static String firstChildText(Element item, Set<String> names) {
        for (Element child : item.children()) {
            if (names.contains(localName(child.tagName()))) {
                String text = trimToNull(child.wholeText());
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Escaped or CDATA-wrapped HTML is text to an XML parser and comes back verbatim;
     * markup left unescaped has been parsed into child elements and is serialised back
     * rather than flattened, so the entry keeps what was published either way.
     */
    private static String firstChildValue(Element item, Set<String> names) {
        for (Element child : item.children()) {
            if (names.contains(localName(child.tagName()))) {
                String value = trimToNull(child.children().isEmpty() ? child.wholeText() : child.html());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String localName(String tagName) {
        String lower = tagName.toLowerCase();
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }

    private static String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
