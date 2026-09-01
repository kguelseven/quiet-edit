package org.korhan.quietedit.analysis;

import org.korhan.quietedit.versioning.ContentHasher;
import org.korhan.quietedit.versioning.DocumentDiff;
import org.korhan.quietedit.versioning.ParagraphChange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a paragraph change is a ticker restructuring its own index line rather
 * than anyone editing prose: a watson.ch liveticker opens with a rolling summary of its
 * newest entries joined with {@code ++}, and every re-check that drops one of them
 * reaches {@link org.korhan.quietedit.versioning.DiffEngine} as a paragraph edited by
 * removing seven words.
 *
 * <p>Here and not in the extractor, because the line is real page content and belongs in
 * the extracted text; what is wrong is only the verdict drawn from its movement, and a
 * verdict is this package's business.
 *
 * <p>A pair is an index rewriting itself when one side splits into two or more items on
 * the separator, at least one item survives folded-identical on both sides, and no
 * dropped item shares {@value #MIN_ITEM_OVERLAP} or more of its words with an arriving
 * one -- two items that similar are one item reworded, which is the one thing about this
 * line worth reporting.
 *
 * <p>Overlap is measured order-blind, where the diff engine's is not, because an index
 * item is a headline and a reworded headline keeps its words while moving them.
 *
 * <p>The threshold and the known weaknesses are justified in quietedit-10i.14.
 */
@Service
public class IndexLineRewrite {

    /** The share of two items' words that has to match for them to be one item reworded. */
    static final double MIN_ITEM_OVERLAP = 0.5;

    /**
     * The joiner, whitespace-delimited on both sides. Two or more plus signs, because
     * publishers spell it {@code ++} and {@code +++} interchangeably on the same page.
     */
    private static final Pattern JOINER = Pattern.compile("\\s\\++\\s");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ContentHasher folding;

    public IndexLineRewrite(ContentHasher folding) {
        this.folding = folding;
    }

    /**
     * @return the rationale for treating the pair as an index rewriting itself, or
     *         empty when the difference is a content change
     */
    public Optional<String> explain(String fromText, String toText) {
        List<String> from = items(fromText);
        List<String> to = items(toText);
        if (Math.max(from.size(), to.size()) < 2 || from.equals(to)) {
            return Optional.empty();
        }

        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>(from);
        List<String> arrived = new ArrayList<>(to);
        for (String item : from) {
            if (arrived.remove(item)) {
                kept.add(item);
                dropped.remove(item);
            }
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        for (String gone : dropped) {
            for (String added : arrived) {
                if (overlap(gone, added) >= MIN_ITEM_OVERLAP) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(rationale(from.size(), to.size(), kept.size(), dropped.size(), arrived.size()));
    }

    /** Whether the pair is an index rewriting itself; see {@link #explain} for the reasoning. */
    public boolean isIndexRewrite(String fromText, String toText) {
        return explain(fromText, toText).isPresent();
    }

    /**
     * Two shapes are recognised, because a restructure reaches the diff as either one
     * depending on how much of the line survived: a {@link ParagraphChange.Changed} whose
     * two texts are the same index, and a {@link ParagraphChange.Removed} paired with a
     * {@link ParagraphChange.Added} where too little survived for the engine to pair them.
     * Moves are never index rewrites -- a move means the text is unchanged.
     */
    public List<ParagraphChange> contentChanges(DocumentDiff diff) {
        Objects.requireNonNull(diff, "diff");
        List<ParagraphChange> paragraphs = diff.paragraphs();
        boolean[] setAside = new boolean[paragraphs.size()];
        for (int r = 0; r < paragraphs.size(); r++) {
            if (paragraphs.get(r) instanceof ParagraphChange.Changed changed
                    && isIndexRewrite(changed.fromText(), changed.toText())) {
                setAside[r] = true;
            }
            if (!(paragraphs.get(r) instanceof ParagraphChange.Removed removed) || setAside[r]) {
                continue;
            }
            for (int a = 0; a < paragraphs.size(); a++) {
                if (!setAside[a] && paragraphs.get(a) instanceof ParagraphChange.Added added
                        && isIndexRewrite(removed.text(), added.text())) {
                    setAside[r] = true;
                    setAside[a] = true;
                    break;
                }
            }
        }

        List<ParagraphChange> remaining = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (!setAside[i]) {
                remaining.add(paragraphs.get(i));
            }
        }
        return List.copyOf(remaining);
    }

    /** The items of a joined line, folded, with the ones that fold to nothing dropped. */
    private List<String> items(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : JOINER.split(text)) {
            String folded = folding.normalize(part);
            if (!folded.isEmpty()) {
                items.add(folded);
            }
        }
        return items;
    }

    /**
     * Counted over the word multisets, so that a headline whose words were reordered still
     * scores as the same headline.
     */
    private double overlap(String gone, String added) {
        List<String> goneWords = List.of(WHITESPACE.split(gone));
        List<String> addedWords = List.of(WHITESPACE.split(added));
        Map<String, Integer> remaining = new HashMap<>();
        for (String word : goneWords) {
            remaining.merge(word, 1, Integer::sum);
        }
        int shared = 0;
        for (String word : addedWords) {
            Integer left = remaining.get(word);
            if (left != null && left > 0) {
                remaining.put(word, left - 1);
                shared++;
            }
        }
        return 2.0 * shared / (goneWords.size() + addedWords.size());
    }

    private static String rationale(int from, int to, int kept, int dropped, int arrived) {
        return "the paragraph is the page's own index line, %d joined entries before and %d after;"
                .formatted(from, to)
                + " %d entries are unchanged, %d were dropped and %d arrived, and no entry was reworded"
                        .formatted(kept, dropped, arrived);
    }
}
