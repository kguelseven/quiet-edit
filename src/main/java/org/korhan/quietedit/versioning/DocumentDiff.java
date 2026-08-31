package org.korhan.quietedit.versioning;

import java.util.List;
import java.util.Optional;

/**
 * The structured difference between two versions of a document: what happened to the
 * headline, and what happened to each paragraph.
 *
 * <p>A data structure, not rendered text. Both consumers need it in this shape: the
 * classifier counts and weighs the entries to decide what the edit means, and the web
 * interface walks them to draw the two texts side by side. A preformatted unified diff
 * would serve the second badly and the first not at all.
 *
 * <p>Directional. This is the difference "from the earlier version to the later one";
 * swapping the arguments does not swap the roles of the fields, it produces a
 * different diff -- an addition one way is a removal the other.
 *
 * @param title      the headline change, empty when both versions agree on it
 * @param paragraphs body changes in the reading order of the later version, with a
 *                   removal sitting where it used to be
 */
public record DocumentDiff(Optional<TitleChange> title, List<ParagraphChange> paragraphs) {

    public DocumentDiff {
        paragraphs = List.copyOf(paragraphs);
    }

    /**
     * True when the two versions say the same thing. Not the same question as an equal
     * content hash: the hash is computed over the folded text and so is this, but a
     * diff can be empty for a pair that was stored as two versions for some other
     * reason -- a re-decode that folded away, say.
     */
    public boolean isEmpty() {
        return title.isEmpty() && paragraphs.isEmpty();
    }
}
