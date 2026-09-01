package org.korhan.quietedit.web;

import org.korhan.quietedit.analysis.InPlaceEdit;
import org.korhan.quietedit.ingest.Feed;
import org.korhan.quietedit.ingest.FeedRepository;
import org.korhan.quietedit.versioning.ChangedDocumentRow;
import org.korhan.quietedit.versioning.DiffEngine;
import org.korhan.quietedit.versioning.DiffService;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.korhan.quietedit.versioning.DocumentRevisions;
import org.korhan.quietedit.versioning.DocumentVersion;
import org.korhan.quietedit.versioning.DocumentVersionRepository;
import org.korhan.quietedit.versioning.ParagraphChange;
import org.korhan.quietedit.versioning.RevisionDiff;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds the two pages of the reading interface out of the data the REST endpoints
 * already serve.
 *
 * <p>Nothing here computes a diff of its own; what this class adds is the shape a
 * template can iterate.
 *
 * <p>The rewritten-only filter runs after the query, because whether a revision only
 * added paragraphs is a property of its diff and a diff is computed rather than stored;
 * the cost is one extra revision read and one diff per row, paid only when it is on.
 *
 * <p>That filter reports what it hid, because ten rows out of a hundred fetched is a
 * different answer from ten out of ten and the count is the only thing that says which.
 *
 * <p>Every method is read-only: looking at a diff must not become part of the history of
 * the article.
 */
@Service
public class ChangeBrowserService {

    /**
     * Higher than the REST listing's default because scrolling is the whole interaction
     * here, and the rewritten-only filter removes rows after the fact -- a small page would
     * leave a reader with three rows and no way to see more.
     */
    static final int MAX_ROWS = 200;

    /**
     * Five, because it is a glance beside the filters and not a second table: the busiest
     * handful is what says whether articles are arriving.
     */
    static final int SUMMARISED_FEEDS = 5;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final FeedRepository feeds;
    private final DiffService diffs;
    private final DiffEngine engine;
    private final InPlaceEdit inPlaceEdit;
    private final Clock clock;

    public ChangeBrowserService(DocumentRepository documents,
                               DocumentVersionRepository versions,
                               FeedRepository feeds,
                               DiffService diffs,
                               DiffEngine engine,
                               InPlaceEdit inPlaceEdit,
                               Clock clock) {
        this.documents = documents;
        this.versions = versions;
        this.feeds = feeds;
        this.diffs = diffs;
        this.engine = engine;
        this.inPlaceEdit = inPlaceEdit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChangeIndexView index(ChangeFilter filter) {
        Instant changedSince = filter.within().startFrom(clock.instant());
        List<ChangedDocumentRow> found = documents.changedDocumentsFiltered(
                filter.feedId(), changedSince, filter.minRevisions(), Limit.of(MAX_ROWS));

        List<ChangeIndexView.Row> rows = new ArrayList<>();
        int hidden = 0;
        for (ChangedDocumentRow row : found) {
            if (filter.rewrittenOnly() && !rewritesExistingText(row.latestRevision())) {
                hidden++;
                continue;
            }
            rows.add(row(row));
        }
        return new ChangeIndexView(List.copyOf(rows), feedOptions(), filter, hidden,
                found.size() == MAX_ROWS, feeds.coverage(Limit.of(SUMMARISED_FEEDS)));
    }

    /**
     * @param from ordinal of the earlier revision, or null for the one before {@code to}
     * @param to   ordinal of the later revision, or null for the newest
     * @throws org.korhan.quietedit.versioning.DiffUnavailableException if the document
     *         or either ordinal is unknown, or the document has been seen only once
     */
    @Transactional(readOnly = true)
    public DiffPageView diff(UUID documentId, Integer from, Integer to) {
        RevisionDiff diff = diffs.diff(documentId, from, to);
        DocumentRevisions history = diffs.revisions(documentId);
        return new DiffPageView(
                diff.document().getId(),
                diff.document().getCanonicalUrl(),
                hostOf(diff.document().getCanonicalUrl()),
                diff.to().getPageTitle(),
                revision(diff.from()),
                revision(diff.to()),
                diff.diff().isEmpty(),
                diff.diff().title()
                        .map(change -> new DiffPageView.TitleDiff(
                                WordMarkup.before(change.fromTitle(), change.words()),
                                WordMarkup.after(change.toTitle(), change.words())))
                        .orElse(null),
                diff.diff().paragraphs().stream().map(ChangeBrowserService::paragraph).toList(),
                pairs(history, diff.from().getVersionNumber(), diff.to().getVersionNumber()));
    }

    /**
     * Compared against the revision before it, which exists for every ordinal above one
     * because the version store numbers contiguously. A missing predecessor is unjudgeable
     * rather than a data state, and an unjudgeable row stays visible.
     */
    private boolean rewritesExistingText(DocumentVersion latest) {
        return versions
                .findByDocumentIdAndVersionNumber(latest.getDocumentId(), latest.getVersionNumber() - 1)
                .map(previous -> inPlaceEdit.rewritesExistingText(engine.diff(previous, latest)))
                .orElse(true);
    }

    private static ChangeIndexView.Row row(ChangedDocumentRow row) {
        DocumentVersion latest = row.latestRevision();
        return new ChangeIndexView.Row(
                row.document().getId(),
                latest.getPageTitle(),
                hostOf(row.document().getCanonicalUrl()),
                row.document().getCanonicalUrl(),
                row.document().getVersionCount(),
                row.document().getLastChangedAt(),
                latest.getParagraphs().size(),
                latest.getVersionNumber() - 1,
                latest.getVersionNumber());
    }

    private List<ChangeIndexView.FeedOption> feedOptions() {
        return feeds.findAll().stream()
                .sorted(Comparator.comparing(Feed::getName, String.CASE_INSENSITIVE_ORDER))
                .map(feed -> new ChangeIndexView.FeedOption(feed.getId(), feed.getName()))
                .toList();
    }

    private static DiffPageView.Revision revision(DocumentVersion version) {
        return new DiffPageView.Revision(
                version.getVersionNumber(),
                version.getFetchedAt(),
                version.getPublishedAt(),
                version.getParagraphs().size(),
                version.getContentHash(),
                version.getEncoding() == null ? null : version.getEncoding().describe());
    }

    private static DiffPageView.Paragraph paragraph(ParagraphChange change) {
        return switch (change) {
            case ParagraphChange.Added added -> new DiffPageView.Paragraph(
                    DiffPageView.Kind.ADDED, null, added.toIndex(),
                    List.of(), Segment.whole(added.text()));
            case ParagraphChange.Removed removed -> new DiffPageView.Paragraph(
                    DiffPageView.Kind.REMOVED, removed.fromIndex(), null,
                    Segment.whole(removed.text()), List.of());
            case ParagraphChange.Changed changed -> new DiffPageView.Paragraph(
                    DiffPageView.Kind.CHANGED, changed.fromIndex(), changed.toIndex(),
                    WordMarkup.before(changed.fromText(), changed.words()),
                    WordMarkup.after(changed.toText(), changed.words()));
            case ParagraphChange.Moved moved -> new DiffPageView.Paragraph(
                    DiffPageView.Kind.MOVED, moved.fromIndex(), moved.toIndex(),
                    List.of(), Segment.plain(moved.text()));
        };
    }

    /**
     * Adjacent pairs only: any pair is a legal diff, but the list a reader walks is the
     * sequence of edits that happened, and every combination of a ten-revision history
     * would be forty-five links to choose between.
     */
    private static List<DiffPageView.Pair> pairs(DocumentRevisions history, int shownFrom, int shownTo) {
        List<DocumentVersion> revisions = history.revisions();
        List<DiffPageView.Pair> pairs = new ArrayList<>();
        for (int i = revisions.size() - 1; i > 0; i--) {
            DocumentVersion later = revisions.get(i);
            int from = revisions.get(i - 1).getVersionNumber();
            int to = later.getVersionNumber();
            pairs.add(new DiffPageView.Pair(from, to, later.getFetchedAt(),
                    from == shownFrom && to == shownTo));
        }
        return List.copyOf(pairs);
    }

    /**
     * Falls back to the whole URL rather than to null: a row whose host cannot be parsed is
     * still worth showing, and the URL says as much as anything.
     */
    private static String hostOf(String canonicalUrl) {
        try {
            String host = new URI(canonicalUrl).getHost();
            return host == null ? canonicalUrl : host;
        } catch (URISyntaxException e) {
            return canonicalUrl;
        }
    }
}
