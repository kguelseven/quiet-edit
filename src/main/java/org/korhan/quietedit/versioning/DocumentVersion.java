package org.korhan.quietedit.versioning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One observed revision of a document. Immutable and append-only once written:
 * a later observation never updates a version, it adds one.
 *
 * <p>The paragraph list is stored as jsonb rather than a child table because a
 * version is only ever read and written whole; a join table would buy queryable
 * paragraphs that nothing needs and cost an ordering column plus N inserts per
 * observation.
 *
 * <p>{@code contentHash} is {@code char(64)} — a hex-encoded SHA-256 — and is
 * unique per document, which is what makes "unchanged content produces no new
 * version" a database guarantee rather than a convention. {@code simhash} is the
 * near-duplicate counterpart and stays nullable: it is only meaningful once a
 * fingerprinting strategy exists.
 *
 * <p>{@code rawHtmlRef} holds a path or storage key, never the HTML. Keeping
 * megabytes of markup out of the row keeps the version table scannable.
 */
@Entity
@Table(
        name = "document_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_document_version_document_content",
                columnNames = {"document_id", "content_hash"}))
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** False when the publication date was inferred or truncated rather than read verbatim. */
    @Column(name = "published_at_exact", nullable = false)
    private boolean publishedAtExact = true;

    @Column(name = "feed_title")
    private String feedTitle;

    @Column(name = "page_title")
    private String pageTitle;

    /** Ordered list of paragraph texts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paragraphs", nullable = false)
    private List<String> paragraphs;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "simhash")
    private Long simhash;

    @Column(name = "raw_html_ref")
    private String rawHtmlRef;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    protected DocumentVersion() {
        // for JPA
    }

    public DocumentVersion(
            UUID documentId,
            Instant fetchedAt,
            List<String> paragraphs,
            String contentHash,
            int httpStatus) {
        this.documentId = documentId;
        this.fetchedAt = fetchedAt;
        this.paragraphs = paragraphs;
        this.contentHash = contentHash;
        this.httpStatus = httpStatus;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public boolean isPublishedAtExact() {
        return publishedAtExact;
    }

    public void setPublishedAtExact(boolean publishedAtExact) {
        this.publishedAtExact = publishedAtExact;
    }

    public String getFeedTitle() {
        return feedTitle;
    }

    public void setFeedTitle(String feedTitle) {
        this.feedTitle = feedTitle;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public void setPageTitle(String pageTitle) {
        this.pageTitle = pageTitle;
    }

    public List<String> getParagraphs() {
        return paragraphs;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Long getSimhash() {
        return simhash;
    }

    public void setSimhash(Long simhash) {
        this.simhash = simhash;
    }

    public String getRawHtmlRef() {
        return rawHtmlRef;
    }

    public void setRawHtmlRef(String rawHtmlRef) {
        this.rawHtmlRef = rawHtmlRef;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
