package org.korhan.quietedit.ingest;

import java.util.List;

/**
 * The prose of one article: its headline and its body, in reading order.
 *
 * <p>Paragraphs are a list, not one joined string, because a moved, inserted or
 * deleted paragraph is the change this system exists to detect. Joining them
 * first would turn a paragraph insertion into an unbounded character-level diff
 * and lose the block boundaries the classifier reasons about.
 *
 * <p>Absent values are empty, never {@code null}: this record is hashed
 * downstream, and "no title" must have exactly one representation or two
 * observations of the same title-less page would hash differently.
 */
public record ArticleContent(String title, List<String> paragraphs) {

    public ArticleContent {
        title = title == null ? "" : title;
        paragraphs = List.copyOf(paragraphs);
    }

    static ArticleContent empty() {
        return new ArticleContent("", List.of());
    }

    /** True when nothing extractable was found -- a paywall stub, a JS-only shell, an error page. */
    public boolean isEmpty() {
        return title.isEmpty() && paragraphs.isEmpty();
    }
}
