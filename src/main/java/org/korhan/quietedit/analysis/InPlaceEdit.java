package org.korhan.quietedit.analysis;

import org.korhan.quietedit.versioning.DocumentDiff;
import org.korhan.quietedit.versioning.ParagraphChange;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a revision touched text that was already on the page, or only
 * added to it.
 *
 * <p>The case this exists for: a liveticker or a developing story grows by
 * appending. Every re-check of a watson.ch ticker produces a revision, and nearly
 * all of them are new entries arriving at the top with the older ones untouched.
 * That is the publisher doing exactly what a ticker is for, and it is not the thing
 * this system looks for. What is worth a reader's attention is text that was
 * rewritten where it stood: a sentence rephrased, a claim walked back, a headline
 * changed, a paragraph deleted.
 *
 * <h2>The rule</h2>
 * A revision rewrote existing text when, after index-line rewrites are set aside,
 * anything remains that is not an addition -- a {@link ParagraphChange.Changed},
 * {@link ParagraphChange.Removed} or {@link ParagraphChange.Moved} -- or when the
 * headline changed.
 *
 * <p>Index-line rewrites are set aside first, via {@link IndexLineRewrite}, and that
 * step is what makes the rule useful rather than vacuous. A ticker that gained an
 * entry does not only gain paragraphs: it also rolls the summary line at the top,
 * which reaches the diff as a paragraph edited in place. Judged on the raw diff,
 * every ticker revision would therefore look like a rewrite and the filter would
 * hide nothing. Judged on the content changes, a rolled index line is what it is --
 * a list of pointers to the page's own entries -- and the revision is an addition.
 *
 * <p>A removal counts as a rewrite even though nothing was written: a paragraph that
 * disappeared is a change to what the article says, and a reader looking for a
 * retracted sentence is looking for exactly that.
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>Inherited from {@link IndexLineRewrite}: a ticker whose index line was
 *       replaced in full has no surviving entry to anchor on, so that revision reads
 *       as a rewrite and stays visible. The error direction is the safe one -- a
 *       ticker revision shown costs a glance, a suppressed correction costs the
 *       edit.</li>
 *   <li>An empty diff counts as no rewrite. Two versions can be stored with no
 *       content difference between them (a re-decode that folds away), and such a
 *       revision is not text rewritten in place.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * A pure function of the diff: no clock, no randomness, and the underlying partition
 * walks the change list in reading order.
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
