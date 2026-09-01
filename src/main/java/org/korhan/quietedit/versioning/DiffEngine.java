package org.korhan.quietedit.versioning;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;

import org.korhan.quietedit.ingest.ArticleContent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The structured difference between two versions: which paragraphs were added, removed,
 * edited or displaced, and which words moved inside the edited ones. It makes no
 * judgement -- the result is evidence for the classifier, not a verdict of its own.
 *
 * <p>Paragraphs and words are compared on their folded form, the same folding
 * {@link ContentHasher} hashes, so that a CMS switching on smart quotes is not an edit;
 * the result always carries the original text, because the reader must see what the
 * publisher wrote.
 *
 * <p>Moves are paired before edits, so a paragraph that merely travelled is never spent
 * on a similarity match with something else.
 *
 * <p>Pairing is greedy in reading order with ties resolved to the lowest index, which is
 * what makes the diff a pure function of the two texts; the similarity bars, their
 * calibration and the known weaknesses are justified in quietedit-1hs.
 */
@Service
public class DiffEngine {

    static final double MIN_SIMILARITY = 0.5;

    /** The bar for a pair that replaced each other in place; calibrated in quietedit-1hs. */
    static final double MIN_IN_SLOT_SIMILARITY = 1.0 / 3;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Sort ranks, so that at one position a removal reads before the addition replacing it. */
    private static final int RANK_REMOVED = 0;
    private static final int RANK_CHANGED = 1;
    private static final int RANK_MOVED = 2;
    private static final int RANK_ADDED = 3;

    private final ContentHasher folding;

    public DiffEngine(ContentHasher folding) {
        this.folding = folding;
    }

    /** The order decides which side of the diff is an addition and which a removal. */
    public DocumentDiff diff(DocumentVersion from, DocumentVersion to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return diff(contentOf(from), contentOf(to));
    }

    public DocumentDiff diff(ArticleContent from, ArticleContent to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return new DocumentDiff(
                titleChange(from.title(), to.title()),
                paragraphChanges(blocks(from.paragraphs()), blocks(to.paragraphs())));
    }

    /**
     * The page's own headline, not the feed's: a feed title is claimed at discovery and
     * not re-observed on a re-fetch, so comparing it would report no title edits at all.
     */
    private static ArticleContent contentOf(DocumentVersion version) {
        return new ArticleContent(version.getPageTitle(), version.getParagraphs());
    }

    private Optional<TitleChange> titleChange(String from, String to) {
        if (folding.normalize(from).equals(folding.normalize(to))) {
            return Optional.empty();
        }
        List<String> fromWords = words(from);
        List<String> toWords = words(to);
        return Optional.of(new TitleChange(from, to, wordChanges(fromWords, toWords)));
    }

    private List<ParagraphChange> paragraphChanges(List<Block> from, List<Block> to) {
        Patch<String> patch = DiffUtils.diff(folded(from), folded(to));
        List<Candidate> removed = new ArrayList<>();
        List<Candidate> added = new ArrayList<>();
        int deltaIndex = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> source = delta.getSource();
            Chunk<String> target = delta.getTarget();
            boolean balanced = source.size() == target.size();
            for (int i = 0; i < source.size(); i++) {
                removed.add(new Candidate(from.get(source.getPosition() + i),
                        source.getPosition() + i, target.getPosition(), deltaIndex, i, balanced));
            }
            for (int i = 0; i < target.size(); i++) {
                added.add(new Candidate(to.get(target.getPosition() + i),
                        source.getPosition(), target.getPosition() + i, deltaIndex, i, balanced));
            }
            deltaIndex++;
        }

        boolean[] removedTaken = new boolean[removed.size()];
        boolean[] addedTaken = new boolean[added.size()];
        List<Ranked> changes = new ArrayList<>();
        pairMoves(removed, added, removedTaken, addedTaken, changes);
        pairEdits(removed, added, removedTaken, addedTaken, changes);
        collectUnpaired(removed, added, removedTaken, addedTaken, changes);

        return changes.stream()
                .sorted(Comparator.comparingInt(Ranked::targetPosition)
                        .thenComparingInt(Ranked::sourcePosition)
                        .thenComparingInt(Ranked::rank))
                .map(Ranked::change)
                .toList();
    }

    /** Equal folded text on both sides: the paragraph travelled, it was not rewritten. */
    private void pairMoves(List<Candidate> removed, List<Candidate> added,
            boolean[] removedTaken, boolean[] addedTaken, List<Ranked> changes) {
        for (int r = 0; r < removed.size(); r++) {
            for (int a = 0; a < added.size(); a++) {
                if (addedTaken[a] || !removed.get(r).block().folded().equals(added.get(a).block().folded())) {
                    continue;
                }
                removedTaken[r] = true;
                addedTaken[a] = true;
                Block before = removed.get(r).block();
                Block after = added.get(a).block();
                // The later spelling: the two fold alike, so any difference is typography.
                changes.add(new Ranked(added.get(a).targetPosition(), removed.get(r).sourcePosition(),
                        RANK_MOVED, new ParagraphChange.Moved(before.index(), after.index(), after.text())));
                break;
            }
        }
    }

    private void pairEdits(List<Candidate> removed, List<Candidate> added,
            boolean[] removedTaken, boolean[] addedTaken, List<Ranked> changes) {
        for (int r = 0; r < removed.size(); r++) {
            if (removedTaken[r]) {
                continue;
            }
            List<String> before = words(removed.get(r).block().text());
            int best = -1;
            double bestSimilarity = 0;
            for (int a = 0; a < added.size(); a++) {
                if (addedTaken[a]) {
                    continue;
                }
                // Strictly greater, so equally good candidates resolve to the earliest.
                double similarity = similarity(before, words(added.get(a).block().text()));
                if (similarity > bestSimilarity && similarity >= minSimilarity(removed.get(r), added.get(a))) {
                    bestSimilarity = similarity;
                    best = a;
                }
            }
            if (best < 0) {
                continue;
            }
            removedTaken[r] = true;
            addedTaken[best] = true;
            Block from = removed.get(r).block();
            Block to = added.get(best).block();
            changes.add(new Ranked(added.get(best).targetPosition(), removed.get(r).sourcePosition(),
                    RANK_CHANGED, new ParagraphChange.Changed(from.index(), to.index(), from.text(), to.text(),
                            wordChanges(before, words(to.text())))));
        }
    }

    private void collectUnpaired(List<Candidate> removed, List<Candidate> added,
            boolean[] removedTaken, boolean[] addedTaken, List<Ranked> changes) {
        for (int r = 0; r < removed.size(); r++) {
            if (!removedTaken[r]) {
                Candidate candidate = removed.get(r);
                changes.add(new Ranked(candidate.targetPosition(), candidate.sourcePosition(), RANK_REMOVED,
                        new ParagraphChange.Removed(candidate.block().index(), candidate.block().text())));
            }
        }
        for (int a = 0; a < added.size(); a++) {
            if (!addedTaken[a]) {
                Candidate candidate = added.get(a);
                changes.add(new Ranked(candidate.targetPosition(), candidate.sourcePosition(), RANK_ADDED,
                        new ParagraphChange.Added(candidate.block().index(), candidate.block().text())));
            }
        }
    }

    /**
     * Lower for two blocks that replaced each other in place, where the position is
     * evidence of its own. Per pair, not per removal, so a weaker candidate in the
     * removal's own slot can beat a stronger one that fails the higher cross-slot bar.
     */
    private static double minSimilarity(Candidate removed, Candidate added) {
        return inSameSlot(removed, added) ? MIN_IN_SLOT_SIMILARITY : MIN_SIMILARITY;
    }

    /**
     * Both conditions matter: an unbalanced run has no offset-to-offset correspondence to
     * appeal to, and two different offsets inside a balanced one are two different slots.
     */
    private static boolean inSameSlot(Candidate removed, Candidate added) {
        return removed.balanced()
                && removed.deltaIndex() == added.deltaIndex()
                && removed.deltaOffset() == added.deltaOffset();
    }

    /**
     * Counted over both sides, so that a paragraph merely appended to does not score the
     * same as one rewritten to the same length.
     */
    private double similarity(List<String> before, List<String> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return 0;
        }
        int replaced = 0;
        for (AbstractDelta<String> delta : DiffUtils.diff(keys(before), keys(after)).getDeltas()) {
            replaced += delta.getSource().size();
        }
        return 2.0 * (before.size() - replaced) / (before.size() + after.size());
    }

    private List<WordChange> wordChanges(List<String> before, List<String> after) {
        List<WordChange> changes = new ArrayList<>();
        for (AbstractDelta<String> delta : DiffUtils.diff(keys(before), keys(after)).getDeltas()) {
            Chunk<String> source = delta.getSource();
            Chunk<String> target = delta.getTarget();
            List<String> gone = slice(before, source);
            List<String> arrived = slice(after, target);
            if (gone.isEmpty()) {
                changes.add(new WordChange.Added(target.getPosition(), arrived));
            } else if (arrived.isEmpty()) {
                changes.add(new WordChange.Removed(source.getPosition(), gone));
            } else {
                changes.add(new WordChange.Changed(source.getPosition(), target.getPosition(), gone, arrived));
            }
        }
        return changes;
    }

    private static List<String> slice(List<String> words, Chunk<String> chunk) {
        return words.subList(chunk.getPosition(), chunk.getPosition() + chunk.size());
    }

    /**
     * Paragraphs that fold to nothing are dropped but keep their stored index, so an ad
     * container between two paragraphs neither counts as a change nor shifts the indices.
     */
    private List<Block> blocks(List<String> paragraphs) {
        List<Block> blocks = new ArrayList<>();
        for (int index = 0; index < paragraphs.size(); index++) {
            String folded = folding.normalize(paragraphs.get(index));
            if (!folded.isEmpty()) {
                blocks.add(new Block(index, paragraphs.get(index), folded));
            }
        }
        return blocks;
    }

    private static List<String> folded(List<Block> blocks) {
        return blocks.stream().map(Block::folded).toList();
    }

    private static List<String> words(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() ? List.of() : List.of(WHITESPACE.split(stripped));
    }

    private List<String> keys(List<String> words) {
        return words.stream().map(folding::normalize).toList();
    }

    private record Block(int index, String text, String folded) {
    }

    /**
     * Carries both positions the diff gave it -- its own index, and where the run sits on
     * the other side -- because the second is what sorts a lone removal into the later
     * version's reading order.
     *
     * <p>{@code deltaIndex}, {@code deltaOffset} and {@code balanced} describe the run,
     * not the paragraph; they are not positions and never reach the result.
     */
    private record Candidate(Block block, int sourcePosition, int targetPosition,
                             int deltaIndex, int deltaOffset, boolean balanced) {
    }

    private record Ranked(int targetPosition, int sourcePosition, int rank, ParagraphChange change) {
    }
}
