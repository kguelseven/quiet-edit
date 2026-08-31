package org.korhan.quietedit.versioning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * <p>That is not a convention this class is trusted to keep. Since Flyway V4 the
 * table rejects every {@code UPDATE} and {@code DELETE} outright, because a version
 * is the evidence for the claim "the article used to read like this" and evidence
 * that can be edited afterwards proves nothing. The entity is therefore written once
 * and only ever read back; it has no setters for the fields that carry the
 * observation, and the few it does have exist for values decided at write time.
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
 *
 * <p>The {@link EncodingVerdict} is stored as three plain columns rather than as an
 * embeddable, because a record cannot be one, and it is optional: it is null on a
 * version written before the verdict was carried this far. {@code encodingReplaced}
 * is not nullable and defaults to false, which reads such a row as "text was clean" --
 * the only safe reading, since the alternative would mark every historical version as
 * suspect mojibake.
 */
@Entity
@Table(
        name = "document_version",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_document_version_document_content",
                        columnNames = {"document_id", "content_hash"}),
                @UniqueConstraint(
                        name = "uq_document_version_number",
                        columnNames = {"document_id", "version_number"})
        })
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    /**
     * Position in this document's history, 1 for the first observation. Unique per
     * document in the schema, which is what makes two writers appending the same
     * "next" revision a failed insert rather than a version count that quietly
     * stops matching the history.
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

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

    /** Canonical charset name the bytes were decoded with; null when not recorded. */
    @Column(name = "charset")
    private String charset;

    @Enumerated(EnumType.STRING)
    @Column(name = "charset_source")
    private CharsetSource charsetSource;

    /**
     * True when this version's text contains U+FFFD because the bytes contradicted
     * the charset that was chosen. The one field that lets a later observation be read
     * as an encoding repair rather than as a rewrite.
     */
    @Column(name = "encoding_replaced", nullable = false)
    private boolean encodingReplaced;

    protected DocumentVersion() {
        // for JPA
    }

    public DocumentVersion(
            UUID documentId,
            int versionNumber,
            Instant fetchedAt,
            List<String> paragraphs,
            String contentHash,
            int httpStatus) {
        this.documentId = documentId;
        this.versionNumber = versionNumber;
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

    public int getVersionNumber() {
        return versionNumber;
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

    /** @return how the bytes were decoded, or null when this version did not record it */
    public EncodingVerdict getEncoding() {
        return charset == null || charsetSource == null
                ? null
                : new EncodingVerdict(charset, charsetSource, encodingReplaced);
    }

    /** A null verdict clears all three columns, so "not recorded" stays one state. */
    public void setEncoding(EncodingVerdict encoding) {
        this.charset = encoding == null ? null : encoding.charset();
        this.charsetSource = encoding == null ? null : encoding.source();
        this.encodingReplaced = encoding != null && encoding.replaced();
    }
}
