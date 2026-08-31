package org.korhan.quietedit.versioning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One article, identified by its canonical URL.
 *
 * <p>The owning feed is held as a bare {@code feedId} rather than a
 * {@code @ManyToOne} association: nothing in the ingest or versioning path needs
 * to traverse from a document to its feed, and keeping the reference flat avoids
 * dragging a lazy proxy through the append-only version store. The foreign key
 * itself is enforced in the schema.
 *
 * <p>The canonical URL is identity; {@code observedOriginUrl} is where the text was
 * read. The two differ only under syndication, and keeping both is what stops a
 * syndicated copy from being asked for at two addresses under one identity.
 *
 * <p>{@code versionCount} and {@code lastChangedAt} are denormalised counters
 * maintained by the version store, not derived on read: the re-check policy has
 * to rank thousands of documents by staleness without aggregating over every
 * version row.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "canonical_url", nullable = false, unique = true)
    private String canonicalUrl;

    @Column(name = "feed_id", nullable = false)
    private UUID feedId;

    /**
     * The page this document's text was actually fetched from, already canonicalised
     * so that it compares equal to the identity a feed link resolves to before any
     * fetch. Equal to {@code canonicalUrl} for every article that declares itself
     * canonical, and different exactly where a publisher syndicates another's copy.
     *
     * <p>Null until the first run that resolves the document records it. Null means
     * "unknown, so the canonical URL is the best guess", which is what every re-check
     * assumed before this column existed.
     */
    @Column(name = "observed_origin_url")
    private String observedOriginUrl;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_checked_at", nullable = false)
    private Instant lastCheckedAt;

    /** Null until a change has ever been detected for this document. */
    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

    @Column(name = "version_count", nullable = false)
    private int versionCount;

    protected Document() {
        // for JPA
    }

    public Document(String canonicalUrl, UUID feedId, Instant firstSeenAt, Instant lastCheckedAt) {
        this.canonicalUrl = canonicalUrl;
        this.feedId = feedId;
        this.firstSeenAt = firstSeenAt;
        this.lastCheckedAt = lastCheckedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public UUID getFeedId() {
        return feedId;
    }

    public void setFeedId(UUID feedId) {
        this.feedId = feedId;
    }

    public String getObservedOriginUrl() {
        return observedOriginUrl;
    }

    public void setObservedOriginUrl(String observedOriginUrl) {
        this.observedOriginUrl = observedOriginUrl;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Instant getLastChangedAt() {
        return lastChangedAt;
    }

    public void setLastChangedAt(Instant lastChangedAt) {
        this.lastChangedAt = lastChangedAt;
    }

    public int getVersionCount() {
        return versionCount;
    }

    public void setVersionCount(int versionCount) {
        this.versionCount = versionCount;
    }
}
