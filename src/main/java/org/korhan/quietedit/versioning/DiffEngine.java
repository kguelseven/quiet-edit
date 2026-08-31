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
 * Computes the structured difference between two versions of a document: which
 * paragraphs were added, removed, edited or displaced, and which words moved inside
 * the edited ones.
 *
 * <h2>What it does not do</h2>
 * It makes no judgement. Nothing here decides whether an edit is cosmetic, a
 * correction or a rewrite, and nothing here reads the encoding verdicts; the result
 * is evidence for the classifier, not a verdict of its own.
 *
 * <h2>Comparison basis</h2>
 * Paragraphs and words are compared on their folded form -- the same folding
 * {@link ContentHasher} hashes -- so that a CMS switching on smart quotes or a
 * publisher re-wrapping a line does not surface as an edit. What the result carries
 * is always the original text, because the reader must see what the publisher wrote.
 * Paragraphs that fold away to nothing (a block that was only an ad identifier) take
 * no part in the diff, matching the hasher's view of what a paragraph is; the indices
 * they occupy in the stored list are still the indices reported for their neighbours.
 *
 * <p>One seam is deliberate: paragraph identity folds the whole paragraph, while word
 * identity folds each whitespace-separated token on its own. A masked span covering
 * several words ("vor drei Stunden") therefore does not fold at word level, so it can
 * appear as a word change inside a paragraph that was edited for another reason. It
 * cannot produce a diff by itself, because such a pair never becomes two versions.
 * Sharing one folding implementation across extractor, hasher and diff engine is
 * tracked separately.
 *
 * <h2>How pairs are found</h2>
 * A Myers diff over the folded paragraph lists yields the runs that differ. Every
 * paragraph in those runs is a candidate on one side or the other, and the candidates
 * are then paired in two passes:
 * <ol>
 *   <li><b>Identical text, different position</b> -- a {@link ParagraphChange.Moved}.
 *       Run first, so a paragraph that merely travelled is never spent on a
 *       similarity match with something else.</li>
 *   <li><b>Similar text</b> -- a {@link ParagraphChange.Changed}, carrying the
 *       word-level detail. Each unmatched removal takes its best remaining
 *       counterpart.</li>
 * </ol>
 * What is left over is an outright removal or addition.
 *
 * <h2>Thresholds, and why this value</h2>
 * Two paragraphs are paired as an edit at a word overlap of
 * {@value #MIN_SIMILARITY} or more, measured as {@code 2 * unchanged words /
 * (words before + words after)} -- the share of the pair that survived. Half is the
 * point where the word diff still reads as an edit of one sentence rather than as an
 * alignment of two unrelated ones: below it the surviving overlap is mostly
 * function words, and the resulting entry claims a lineage between paragraphs that
 * have none. The failure it accepts is the cheaper one -- a heavily rewritten
 * paragraph is reported as a removal plus an addition, which is true, just less
 * specific -- while the failure it avoids, a bogus pairing, hands the classifier a
 * fabricated edit.
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>Two paragraphs with identical text (a repeated disclaimer, a stock sentence)
 *       are interchangeable to the move pass, which takes them in order. When one of
 *       several copies is deleted, the move it reports may name a different copy than
 *       the editor touched. The set of paragraphs is right either way.</li>
 *   <li>A paragraph that was split in two, or two that were merged into one, is not
 *       recognised as such: the halves match the whole only if one of them clears the
 *       similarity bar, and the rest is reported as an addition or a removal.</li>
 *   <li>Pairing is greedy in reading order, not globally optimal. A later removal
 *       could in principle have been a better counterpart for an addition already
 *       taken. Global assignment would cost determinism-by-inspection for a gain that
 *       only shows on wholesale reshuffles, which are not the edits this system looks
 *       for.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * A pure function of the two texts: no clock, no randomness, no locale-dependent
 * comparison, and every pass iterates in a fixed order with ties resolved by the
 * lowest index. The result list is sorted by position in the later version, so the
 * same pair of versions always yields the same diff, and the diff of A to B is not
 * the diff of B to A.
 */
@Service
public class DiffEngine {

    static final double MIN_SIMILARITY = 0.5;

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

    /**
     * @param from the earlier version, {@code to} the later one -- the order decides
     *             which side of the diff is an addition and which a removal
     */
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
     * The page's own headline, not the feed's: the feed title is what a syndication
     * entry claimed at discovery time and is not re-observed on a re-fetch, so
     * comparing it across versions would report the publisher's title edits as nothing
     * at all.
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
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> source = delta.getSource();
            Chunk<String> target = delta.getTarget();
            for (int i = 0; i < source.size(); i++) {
                removed.add(new Candidate(from.get(source.getPosition() + i),
                        source.getPosition() + i, target.getPosition()));
            }
            for (int i = 0; i < target.size(); i++) {
                added.add(new Candidate(to.get(target.getPosition() + i),
                        source.getPosition(), target.getPosition() + i));
            }
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
                // The later spelling: the two fold alike, so any difference between
                // them is typography, and the current page is what a reader sees.
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
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = a;
                }
            }
            if (best < 0 || bestSimilarity < MIN_SIMILARITY) {
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
     * The share of the two word sequences that survived unchanged, counted over both
     * sides so that a paragraph merely appended to does not score the same as one
     * rewritten to the same length.
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
     * Keeps the position a paragraph has in the stored list while dropping the ones
     * that fold to nothing, so an ad container between two paragraphs neither counts
     * as a change nor shifts the indices the result reports.
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

    /** A paragraph as compared: where it sits, what it says, and what it says folded. */
    private record Block(int index, String text, String folded) {
    }

    /**
     * A paragraph inside a differing run, with both positions the diff gave it: its own
     * index on its side, and where the run sits on the other. The second is what lets a
     * lone removal be sorted into the later version's reading order.
     */
    private record Candidate(Block block, int sourcePosition, int targetPosition) {
    }

    private record Ranked(int targetPosition, int sourcePosition, int rank, ParagraphChange change) {
    }
}
