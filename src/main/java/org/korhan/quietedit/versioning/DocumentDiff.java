package org.korhan.quietedit.versioning;

import java.util.List;
import java.util.Optional;

/**
 * The structured difference between two versions: what happened to the headline, and what
 * happened to each paragraph.
 *
 * <p>A data structure rather than rendered text, because both consumers need this shape:
 * the classifier weighs the entries, and the web interface walks them to draw the two
 * texts side by side.
 *
 * <p>Directional. Swapping the arguments does not swap the roles of the fields, it
 * produces a different diff -- an addition one way is a removal the other.
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
