package org.korhan.quietedit.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One article link's attempt record: when this system last tried to turn it into a
 * document, and how many consecutive tries failed.
 *
 * <p>The primary key is the link identity itself, not a surrogate UUID. The row
 * <em>is</em> a counter for one link, so a surrogate key would make it possible to
 * hold two disagreeing counters for the same link, which is the one state this
 * table must not be able to reach.
 *
 * <p>Deliberately not a column on {@code document}: the links this record exists
 * for are the ones that never produce a document at all -- a robots.txt refusal, a
 * paywall stub, a binary, an unusable URL -- so hanging the counter off the document
 * row would leave exactly the starving links untracked.
 */
@Entity
@Table(name = "article_attempt")
public class ArticleAttempt {

    /**
     * The canonicalised feed link, which is the identity {@link ArticleBudget} ranks
     * by. Not the document's canonical URL: that one is only known after a
     * successful fetch, and a failing link has to be identifiable before it.
     */
    @Id
    @Column(name = "link_identity", nullable = false, updatable = false)
    private String linkIdentity;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    protected ArticleAttempt() {
        // for JPA
    }

    public ArticleAttempt(String linkIdentity, Instant lastAttemptAt) {
        this.linkIdentity = linkIdentity;
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLinkIdentity() {
        return linkIdentity;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public AttemptHistory history() {
        return new AttemptHistory(lastAttemptAt, failureCount);
    }
}
