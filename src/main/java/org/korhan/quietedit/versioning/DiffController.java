package org.korhan.quietedit.versioning;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads diffs out of the version store, so that checking whether a stored change is a
 * real edit does not mean writing a lateral join against the paragraph jsonb by hand.
 *
 * <p>Three endpoints, because finding an edit takes three steps: the changed documents
 * (there is no way to guess a document id), that document's revisions, and one diff --
 * which with no parameters is the newest pair.
 *
 * <p>Read-only and therefore {@code GET}: asking for a diff twice must cost nothing and
 * change nothing.
 *
 * <p>The response is not the engine's own types, because {@link ParagraphChange} and
 * {@link WordChange} carry their kind as a Java type and that does not survive JSON; the
 * records here name it in a field, mapped by an exhaustive {@code switch}, so a fifth
 * kind of change fails to compile here rather than serialising as something else.
 *
 * <p>Absent fields mean "does not apply to this kind", not "unknown": a removal is in no
 * later position, and an addition had nothing there before.
 */
@RestController
@RequestMapping("/api/documents")
public class DiffController {

    private final DiffService service;

    public DiffController(DiffService service) {
        this.service = service;
    }

    @GetMapping("/changed")
    public ChangedDocumentsResponse changed(@RequestParam(defaultValue = "50") int limit) {
        List<DocumentHistorySummary> documents = service.changedDocuments(limit);
        return new ChangedDocumentsResponse(documents.size(),
                documents.stream().map(ChangedDocument::of).toList());
    }

    /**
     * Oldest first, so that the ordinals it prints read as the {@code from}/{@code to} of
     * the diff endpoint in the same direction.
     */
    @GetMapping("/{documentId}/revisions")
    public RevisionsResponse revisions(@PathVariable UUID documentId) {
        return RevisionsResponse.of(service.revisions(documentId));
    }

    /**
     * @param from version ordinal of the earlier revision; defaults to {@code to - 1}
     * @param to   version ordinal of the later revision; defaults to the newest
     */
    @GetMapping("/{documentId}/diff")
    public DiffResponse diff(@PathVariable UUID documentId,
                             @RequestParam(required = false) Integer from,
                             @RequestParam(required = false) Integer to) {
        return DiffResponse.of(service.diff(documentId, from, to));
    }

    public record ChangedDocumentsResponse(int count, List<ChangedDocument> documents) {

        // The size of this page, not of the table: a total would mean a second query.
    }

    public record ChangedDocument(
            UUID documentId,
            String canonicalUrl,
            UUID feedId,
            Instant firstSeenAt,
            Instant lastCheckedAt,
            Instant lastChangedAt,
            int versionCount,
            int latestVersionNumber,
            String latestTitle) {

        static ChangedDocument of(DocumentHistorySummary summary) {
            return new ChangedDocument(summary.documentId(), summary.canonicalUrl(), summary.feedId(),
                    summary.firstSeenAt(), summary.lastCheckedAt(), summary.lastChangedAt(),
                    summary.versionCount(), summary.latestVersionNumber(), summary.latestTitle());
        }
    }

    /**
     * Carries no paragraph text: the point is to pick a pair, and every revision's full
     * text would cost more than the diff it precedes. {@code contentHash} and the
     * paragraph count are what distinguish two revisions without reading them.
     */
    public record RevisionsResponse(
            UUID documentId,
            String canonicalUrl,
            int count,
            List<Revision> revisions) {

        static RevisionsResponse of(DocumentRevisions revisions) {
            List<Revision> rows = revisions.revisions().stream().map(Revision::of).toList();
            return new RevisionsResponse(revisions.document().getId(),
                    revisions.document().getCanonicalUrl(), rows.size(), rows);
        }
    }

    public record DiffResponse(
            UUID documentId,
            String canonicalUrl,
            Revision from,
            Revision to,
            /* True when the two revisions say the same thing. Not the same question as
             * two equal content hashes; see DocumentDiff#isEmpty. */
            boolean unchanged,
            TitleDiff title,
            List<ParagraphDiff> paragraphs) {

        static DiffResponse of(RevisionDiff diff) {
            return new DiffResponse(
                    diff.document().getId(),
                    diff.document().getCanonicalUrl(),
                    Revision.of(diff.from()),
                    Revision.of(diff.to()),
                    diff.diff().isEmpty(),
                    diff.diff().title().map(TitleDiff::of).orElse(null),
                    diff.diff().paragraphs().stream().map(ParagraphDiff::of).toList());
        }
    }

    /**
     * Named the same way on both endpoints: a side of a diff and a row of the revision
     * listing are the same thing seen twice.
     *
     * <p>{@code encoding} is prose rather than three fields, matching the ingest endpoint:
     * an edit that is really a re-decode is the first thing to rule out, and here it is
     * visible before any diff is requested.
     */
    public record Revision(
            UUID versionId,
            int versionNumber,
            Instant fetchedAt,
            Instant publishedAt,
            String contentHash,
            int paragraphs,
            String encoding) {

        static Revision of(DocumentVersion version) {
            return new Revision(version.getId(), version.getVersionNumber(), version.getFetchedAt(),
                    version.getPublishedAt(), version.getContentHash(), version.getParagraphs().size(),
                    version.getEncoding() == null ? null : version.getEncoding().describe());
        }
    }

    public record TitleDiff(String fromTitle, String toTitle, List<WordDiff> words) {

        static TitleDiff of(TitleChange change) {
            return new TitleDiff(change.fromTitle(), change.toTitle(),
                    change.words().stream().map(WordDiff::of).toList());
        }
    }

    public enum ParagraphChangeKind {
        ADDED, REMOVED, CHANGED, MOVED
    }

    public record ParagraphDiff(
            ParagraphChangeKind kind,
            Integer fromIndex,
            Integer toIndex,
            String fromText,
            String toText,
            List<WordDiff> words) {

        static ParagraphDiff of(ParagraphChange change) {
            return switch (change) {
                case ParagraphChange.Added added -> new ParagraphDiff(
                        ParagraphChangeKind.ADDED, null, added.toIndex(), null, added.text(), List.of());
                case ParagraphChange.Removed removed -> new ParagraphDiff(
                        ParagraphChangeKind.REMOVED, removed.fromIndex(), null, removed.text(), null, List.of());
                case ParagraphChange.Changed changed -> new ParagraphDiff(
                        ParagraphChangeKind.CHANGED, changed.fromIndex(), changed.toIndex(),
                        changed.fromText(), changed.toText(),
                        changed.words().stream().map(WordDiff::of).toList());
                case ParagraphChange.Moved moved -> new ParagraphDiff(
                        ParagraphChangeKind.MOVED, moved.fromIndex(), moved.toIndex(), null, moved.text(),
                        List.of());
            };
        }
    }

    public enum WordChangeKind {
        ADDED, REMOVED, CHANGED
    }

    public record WordDiff(
            WordChangeKind kind,
            Integer fromIndex,
            Integer toIndex,
            List<String> fromWords,
            List<String> toWords) {

        static WordDiff of(WordChange change) {
            return switch (change) {
                case WordChange.Added added -> new WordDiff(
                        WordChangeKind.ADDED, null, added.toIndex(), List.of(), added.words());
                case WordChange.Removed removed -> new WordDiff(
                        WordChangeKind.REMOVED, removed.fromIndex(), null, removed.words(), List.of());
                case WordChange.Changed changed -> new WordDiff(
                        WordChangeKind.CHANGED, changed.fromIndex(), changed.toIndex(),
                        changed.fromWords(), changed.toWords());
            };
        }
    }
}
