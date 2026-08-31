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
 * Reads diffs out of the version store, so that checking whether a stored change is
 * a real edit does not mean writing a lateral join against the paragraph jsonb by
 * hand.
 *
 * <p>Two endpoints, because finding an edit takes two steps:
 * <ul>
 *   <li>{@code GET /api/documents/changed} -- the documents observed to change,
 *       newest change first. The entry point: there is no way to guess a document id.</li>
 *   <li>{@code GET /api/documents/{id}/diff} -- one diff. With no parameters it is the
 *       newest pair, which is what "what changed" means for an article being watched.</li>
 * </ul>
 *
 * <p>Read-only and therefore {@code GET}: a diff is a view of stored evidence, so
 * asking for one twice must cost nothing and change nothing.
 *
 * <h2>Why the response is not the engine's own types</h2>
 * {@link ParagraphChange} and {@link WordChange} are sealed interfaces whose kind is
 * the Java type. That does not survive JSON: a consumer would have to infer the kind
 * from which fields happen to be present. The records here name the kind in a field
 * instead and flatten the four variants into one shape, which is what a client
 * switching on a discriminator needs. Mapping is an exhaustive {@code switch}, so a
 * fifth kind of change added to the engine fails to compile here rather than
 * silently serialising as something else.
 *
 * <p>Absent fields are null and mean "does not apply to this kind", not "unknown":
 * a removal has no {@code toIndex} because it is in no later position, and an
 * addition has no {@code fromText} because there was nothing there. A {@code MOVED}
 * carries its text as {@code toText} only -- the two spellings fold alike, and the
 * current page is what a reader sees.
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

        // count is the size of this page, not of the table: the listing is capped, and
        // claiming a total would mean a second query nothing here needs.
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
     * Which observation this side of the diff is. {@code contentHash} is included so a
     * reader can tell two revisions apart without reading their text, and
     * {@code encoding} as prose rather than three fields, matching the ingest
     * endpoint: an edit that is really a re-decode is the first thing to rule out.
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
