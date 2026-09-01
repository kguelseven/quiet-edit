package org.korhan.quietedit.analysis;

import org.korhan.quietedit.versioning.DocumentDiff;
import org.korhan.quietedit.versioning.ParagraphChange;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a revision touched text that was already on the page, or only added to
 * it -- the case it exists for being a liveticker, which grows by appending, so nearly
 * every one of its revisions is new entries at the top with the older ones untouched.
 *
 * <p>A revision rewrote existing text when, after index-line rewrites are set aside,
 * anything remains that is not an addition, or when the headline changed.
 *
 * <p>Setting those aside first is what makes the rule useful rather than vacuous: a
 * ticker that gained an entry also rolls its summary line, which reaches the diff as a
 * paragraph edited in place, so on the raw diff every ticker revision would look like a
 * rewrite.
 *
 * <p>A removal counts as a rewrite even though nothing was written: a reader looking for
 * a retracted sentence is looking for exactly that.
 *
 * <p>An empty diff counts as no rewrite, and the weakness inherited from
 * {@link IndexLineRewrite} is justified in quietedit-10i.14.
 */
@Service
public class InPlaceEdit {

    private final IndexLineRewrite indexLines;

    public InPlaceEdit(IndexLineRewrite indexLines) {
        this.indexLines = indexLines;
    }

    /**
     * @return true when this revision rewrote, removed or displaced text that was
     *         already there, or changed the headline; false when it only added
     */
    public boolean rewritesExistingText(DocumentDiff diff) {
        Objects.requireNonNull(diff, "diff");
        if (diff.title().isPresent()) {
            return true;
        }
        List<ParagraphChange> content = indexLines.contentChanges(diff);
        return content.stream().anyMatch(change -> !(change instanceof ParagraphChange.Added));
    }
}
