package org.korhan.quietedit.analysis;

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
import java.util.Map;
import java.util.UUID;

/**
 * A detected difference between two document versions.
 *
 * <p>{@code documentId} is stored alongside the two version references even
 * though it is reachable through them: the primary read is "what changed for this
 * document", and denormalising it keeps that query off a join.
 *
 * <p>{@code classification} is mapped by name rather than ordinal so that adding
 * a kind later cannot silently re-label existing rows.
 *
 * <p>{@code rationale} is mandatory: a classification that cannot be explained is
 * not reviewable, and every heuristic in the analysis package has to be able to
 * say why it decided what it decided.
 *
 * <p>{@code diffPayload} is jsonb and deliberately schema-free here -- its shape
 * belongs to whichever diffing strategy produced it, and pinning it to columns
 * now would force a migration for every refinement of that strategy.
 */
@Entity
@Table(
        name = "change",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_change_from_to",
                columnNames = {"from_version_id", "to_version_id"}))
public class Change {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "from_version_id", nullable = false)
    private UUID fromVersionId;

    @Column(name = "to_version_id", nullable = false)
    private UUID toVersionId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false)
    private Classification classification;

    @Column(name = "title_changed", nullable = false)
    private boolean titleChanged;

    @Column(name = "rationale", nullable = false)
    private String rationale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_payload", nullable = false)
    private Map<String, Object> diffPayload;

    protected Change() {
        // for JPA
    }

    public Change(
            UUID documentId,
            UUID fromVersionId,
            UUID toVersionId,
            Instant detectedAt,
            Classification classification,
            String rationale,
            Map<String, Object> diffPayload) {
        this.documentId = documentId;
        this.fromVersionId = fromVersionId;
        this.toVersionId = toVersionId;
        this.detectedAt = detectedAt;
        this.classification = classification;
        this.rationale = rationale;
        this.diffPayload = diffPayload;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getFromVersionId() {
        return fromVersionId;
    }

    public UUID getToVersionId() {
        return toVersionId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Classification getClassification() {
        return classification;
    }

    public boolean isTitleChanged() {
        return titleChanged;
    }

    public void setTitleChanged(boolean titleChanged) {
        this.titleChanged = titleChanged;
    }

    public String getRationale() {
        return rationale;
    }

    public Map<String, Object> getDiffPayload() {
        return diffPayload;
    }
}
