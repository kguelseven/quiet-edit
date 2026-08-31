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
 * Decides whether a paragraph change is a ticker restructuring its own index line
 * rather than anyone editing prose.
 *
 * <p>The case this exists for: a watson.ch liveticker opens with a rolling summary of
 * its newest entries, several headlines joined with {@code ++}. One re-check later the
 * line read {@code "Nepal: Zahl der Vermissten steigt auf ueber 1300 ++ Keine
 * Informationen zu Schweizer Opfern"}, the next just {@code "Keine Informationen zu
 * Schweizer Opfern"}. To {@link org.korhan.quietedit.versioning.DiffEngine} that is a
 * paragraph edited by removing seven words, and it recurs on every re-check of every
 * ticker. Nothing was edited: the page dropped one item from a list of pointers to
 * itself.
 *
 * <h2>Why here and not in the extractor</h2>
 * The line is real page content and belongs in the extracted text -- it is what the
 * page said at that moment, and dropping it would hide the day a publisher rewords the
 * summary itself. What is wrong is only the verdict drawn from its movement, and a
 * verdict is this package's business. The engine below stays free of judgement.
 *
 * <h2>The rule</h2>
 * A pair of texts is an index rewriting itself when all of the following hold:
 * <ol>
 *   <li><b>One side is a joined list.</b> At least one of the two splits into two or
 *       more items on the {@code ++} separator. A separator surrounded by whitespace
 *       on both sides, so {@code C++ ist alt} is prose, not a list of two.</li>
 *   <li><b>An item survived.</b> At least one item is present, folded-identical, on
 *       both sides. Without an anchor the line was replaced wholesale, which is
 *       indistinguishable from a rewritten paragraph that happens to contain a
 *       {@code ++}.</li>
 *   <li><b>No item was edited.</b> No dropped item and no arriving item share
 *       {@value #MIN_ITEM_OVERLAP} or more of their words. Two items that similar are
 *       one item reworded, and rewording the summary <em>is</em> an edit -- it is the
 *       one thing about this line worth reporting.</li>
 * </ol>
 * Items are compared on their folded form, the same folding {@link ContentHasher}
 * hashes, so a ticker that re-renders its list with typographic quotes still matches
 * item for item.
 *
 * <h2>Thresholds, and why this value</h2>
 * Item overlap is measured as {@code 2 * shared words / (words before + words after)}
 * over the folded word multisets -- the same share-of-the-pair measure the diff engine
 * pairs paragraphs on, and the same bar of a half. It is deliberately order-blind
 * where the engine's is not: an index item is a headline, and a headline reworded
 * ("Nepal: 1300 Vermisste" to "1300 Vermisste in Nepal") keeps its words while moving
 * them, which is exactly the case that must not be waved through. Below a half two
 * headlines about the same event share little more than the event's name, and calling
 * them one edited item would suppress a genuine drop-plus-add.
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li><b>An index replaced in full is not recognised.</b> When every item changes at
 *       once there is no anchor, so the pair is reported as an ordinary change. That
 *       is the intended direction of error: reporting a restructure that turns out to
 *       be noise costs a reader one glance, suppressing a rewritten paragraph costs
 *       them the edit.</li>
 *   <li><b>A prose paragraph built out of {@code ++} is claimed.</b> A line like
 *       {@code "Bilanz ++ die Zahlen im Ueberblick"} losing a half is a restructure by
 *       this rule whether or not the page is a ticker. Nothing in
 *       {@link org.korhan.quietedit.ingest.ArticleContent} says which blocks are entry
 *       headlines, so the shape of the line is the only evidence available; telling a
 *       page's own index from prose that imitates it needs the per-host furniture
 *       comparison tracked separately.</li>
 *   <li><b>An item edited and another dropped in the same revision is claimed as a
 *       restructure</b> only if the edited pair falls below the overlap bar; above it
 *       the whole change is reported, including the drop. The rule is all-or-nothing
 *       per paragraph, because half a paragraph change is not a thing the classifier
 *       can render.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * A pure function of the two texts: no clock, no randomness, no locale-dependent
 * comparison. Pairing a removal with an addition walks both lists in reading order and
 * takes the first match, so the same diff always yields the same partition.
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
     * The paragraph changes of a diff that remain once the index rewrites are set
     * aside, in the order the diff reported them.
     *
     * <p>Two shapes are recognised, because a restructure reaches the diff as either
     * one depending on how much of the line survived: a {@link ParagraphChange.Changed}
     * whose two texts are the same index, and a {@link ParagraphChange.Removed} paired
     * with a {@link ParagraphChange.Added} for the case where too little survived for
     * the engine to pair them itself. Moves are never index rewrites -- a move means
     * the text is unchanged.
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
     * The share of two items' words that both carry, counted over the word multisets so
     * that a headline whose words were reordered still scores as the same headline.
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
