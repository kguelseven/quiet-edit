package org.korhan.quietedit.versioning;

import java.util.List;

/**
 * The headline difference between two versions, reported apart from the body.
 *
 * <p>Separate because a retitled article is its own editorial event: headlines are
 * rewritten for reach, for tone and to walk a claim back, and they are what a reader
 * remembers. Folded into the paragraph list it would arrive as one removal plus one
 * addition among twenty others and lose that standing.
 *
 * <p>An absent title is the empty string on that side, never {@code null}, matching
 * {@link org.korhan.quietedit.ingest.ArticleContent}. A page that gains a headline is
 * therefore a change from {@code ""}, not a missing value.
 */
public record TitleChange(String fromTitle, String toTitle, List<WordChange> words) {

    public TitleChange {
        words = List.copyOf(words);
    }
}
