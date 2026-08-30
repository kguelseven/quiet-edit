package org.korhan.quietedit.ingest;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a feed body into a uniform list of {@link FeedEntry}. RSS 2.0 and Atom 1.0
 * -- and the older RSS dialects rome understands -- come out indistinguishable;
 * nothing downstream needs to know which format a publisher chose.
 *
 * <p>Mandatory field, singular: {@code link}. This system exists to re-fetch and
 * compare an article, so an entry without a link is not an incomplete entry, it is
 * no entry at all. A missing title, summary or date is normal in the wild and is
 * carried through as null instead of being treated as a defect. An entry that fails
 * the link check is dropped with a reason and the rest of the feed is kept: one
 * broken item in a hundred must not cost us the other ninety-nine.
 *
 * <p>Never throws. A body that is not a feed -- truncated XML, an error page served
 * with a feed content type -- becomes a failed result, the same way {@link
 * FeedFetcher} turns a dead publisher into a result rather than an exception.
 *
 * <p>Input is a {@code String}, not the fetcher's {@code byte[]}: which charset
 * those bytes are in is its own decision, with its own conflicting evidence (BOM,
 * Content-Type, XML declaration), and this class would have to guess. Taking
 * already-decoded text keeps that guess out of here. Because the text is decoded,
 * the XML declaration's own {@code encoding} attribute is correctly ignored.
 *
 * <p>Known weakness: rome parses date fields into {@code Date} and discards the
 * original text, which would silently commit us to its interpretation of a missing
 * timezone. So the raw date texts are lifted from a second pass over the body with
 * jsoup's XML parser and matched to rome's entries by document position. Both
 * parsers walk the items in document order, so the positions line up; if the two
 * counts disagree the raw dates are dropped rather than guessed at, and that is
 * logged. The cost is parsing the body twice, which is cheap next to the network
 * round trip that produced it.
 */
@Component
public class FeedParser {

    private static final Logger log = LoggerFactory.getLogger(FeedParser.class);

    /** {@code dc:date} and {@code issued} are how the pre-2.0 dialects say "published". */
    private static final Set<String> PUBLISHED_ELEMENTS = Set.of("pubdate", "date", "published", "issued", "created");

    private static final Set<String> UPDATED_ELEMENTS = Set.of("updated", "modified");

    public FeedParseResult parse(String feedUrl, String body) {
        if (body == null || body.isBlank()) {
            return FeedParseResult.failed("empty body");
        }

        SyndFeed feed;
        try {
            feed = new SyndFeedInput().build(new StringReader(body));
        } catch (FeedException | IllegalArgumentException e) {
            // IllegalArgumentException is how rome reports "well-formed XML, but not a feed".
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Feed {} is not parseable: {}", feedUrl, reason);
            return FeedParseResult.failed(reason);
        }

        List<SyndEntry> syndEntries = feed.getEntries();
        List<Element> rawItems = rawItemsAlignedWith(feedUrl, syndEntries.size(), body);

        List<FeedEntry> entries = new ArrayList<>(syndEntries.size());
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < syndEntries.size(); i++) {
            SyndEntry syndEntry = syndEntries.get(i);
            String link = trimToNull(syndEntry.getLink());
            if (link == null) {
                String reason = "entry %d has no link (title=%s)".formatted(i, trimToNull(syndEntry.getTitle()));
                log.warn("Feed {}: skipping {}", feedUrl, reason);
                skipped.add(reason);
                continue;
            }
            Element rawItem = rawItems.isEmpty() ? null : rawItems.get(i);
            entries.add(new FeedEntry(
                    trimToNull(syndEntry.getTitle()),
                    link,
                    summaryOf(syndEntry),
                    firstChildText(rawItem, PUBLISHED_ELEMENTS),
                    firstChildText(rawItem, UPDATED_ELEMENTS),
                    trimToNull(syndEntry.getUri())));
        }

        log.debug("Feed {} parsed as {}: {} entries, {} skipped",
                feedUrl, feed.getFeedType(), entries.size(), skipped.size());
        return FeedParseResult.parsed(feed.getFeedType(), entries, skipped);
    }

    /**
     * @return the item elements in document order, or an empty list when they cannot
     *         be trusted to correspond one-to-one with rome's entries.
     */
    private List<Element> rawItemsAlignedWith(String feedUrl, int expected, String body) {
        List<Element> items = new ArrayList<>();
        for (Element element : Jsoup.parse(body, "", Parser.xmlParser()).getAllElements()) {
            String name = localName(element.tagName());
            if (name.equals("item") || name.equals("entry")) {
                items.add(element);
            }
        }
        if (items.size() != expected) {
            log.warn("Feed {}: found {} raw items but {} parsed entries, dropping raw dates",
                    feedUrl, items.size(), expected);
            return List.of();
        }
        return items;
    }

    /**
     * The description is the teaser a publisher wrote for the feed; full content, if
     * present at all, is a copy of the article and belongs to the fetcher's job.
     * Preferring the description keeps this field one consistent thing across feeds
     * that carry both.
     */
    private static String summaryOf(SyndEntry entry) {
        String description = entry.getDescription() == null ? null : trimToNull(entry.getDescription().getValue());
        if (description != null) {
            return description;
        }
        for (SyndContent content : entry.getContents()) {
            String value = trimToNull(content.getValue());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Matched on the local name, ignoring the namespace prefix: the same date field
     * arrives as {@code dc:date}, {@code date} or some publisher's own prefix, and
     * the prefix carries no information we act on.
     */
    private static String firstChildText(Element item, Set<String> names) {
        if (item == null) {
            return null;
        }
        for (Element child : item.children()) {
            if (names.contains(localName(child.tagName()))) {
                String text = trimToNull(child.text());
                if (text != null) {
                    return text;
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
