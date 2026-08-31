package org.korhan.quietedit.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A subscribed RSS or Atom source.
 *
 * <p>{@code etag} and {@code lastModified} are kept as the raw header values the
 * server sent. They are only ever echoed back in conditional requests, so
 * parsing them would add a failure mode without buying anything.
 *
 * <p>{@code unconfirmedUpdatedClaims} is the only field here that is not a fact
 * about the last poll: it is what {@link RecheckPolicy} reads to decide whether this
 * publisher's {@code updated} dates are still worth acting on. Maintained by
 * {@link UpdatedClaimLog}, and its meaning is spelled out there.
 */
@Entity
@Table(name = "feed")
public class Feed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private String name;

    private String etag;

    @Column(name = "last_modified")
    private String lastModified;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "unconfirmed_updated_claims", nullable = false)
    private int unconfirmedUpdatedClaims;

    protected Feed() {
        // for JPA
    }

    public Feed(String url, String name) {
        this.url = url;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public Integer getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(Integer lastStatus) {
        this.lastStatus = lastStatus;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getUnconfirmedUpdatedClaims() {
        return unconfirmedUpdatedClaims;
    }

    public void setUnconfirmedUpdatedClaims(int unconfirmedUpdatedClaims) {
        this.unconfirmedUpdatedClaims = unconfirmedUpdatedClaims;
    }
}
