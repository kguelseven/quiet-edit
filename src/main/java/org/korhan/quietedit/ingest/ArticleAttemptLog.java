package org.korhan.quietedit.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers that a link was tried and whether the try produced a document.
 *
 * <p>{@code document} can only record success, so without this a link that is fetched
 * and yields nothing leaves no trace and {@link ArticleBudget} cannot tell "never tried"
 * from "tried and declined" -- which kept such links ranked ahead of every real article.
 *
 * <p>Both sides key by the <em>provisional</em> identity of a link, because that is the
 * only identity available before a fetch and ranking happens before the fetch. Recording
 * under the real canonical URL would leave every redirecting link permanently untried.
 */
@Service
public class ArticleAttemptLog {

    private final ArticleAttemptRepository attempts;

    public ArticleAttemptLog(ArticleAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * One query rather than a lookup per link: the budget ranks every candidate of a run
     * before it fetches any of them.
     *
     * @return a history for every identity asked about, {@link AttemptHistory#NEVER} where
     *         this system has no record of the link
     */
    @Transactional(readOnly = true)
    public Map<String, AttemptHistory> historyOf(Collection<String> linkIdentities) {
        Map<String, AttemptHistory> history = new HashMap<>(linkIdentities.size());
        for (String identity : linkIdentities) {
            history.put(identity, AttemptHistory.NEVER);
        }
        for (ArticleAttempt attempt : attempts.findAllById(linkIdentities)) {
            history.put(attempt.getLinkIdentity(), attempt.history());
        }
        return history;
    }

    /**
     * A success clears the strike count rather than decrementing it: three strikes is about
     * a link that is consistently unusable, and one good fetch says it is not.
     *
     * <p>One short transaction per attempt, like document registration.
     */
    @Transactional
    public AttemptHistory record(String linkIdentity, Instant attemptedAt, boolean yieldedDocument) {
        Optional<ArticleAttempt> existing = attempts.findById(linkIdentity);
        ArticleAttempt attempt = existing.orElseGet(() -> new ArticleAttempt(linkIdentity, attemptedAt));
        attempt.setLastAttemptAt(attemptedAt);
        attempt.setFailureCount(yieldedDocument ? 0 : attempt.getFailureCount() + 1);
        return attempts.save(attempt).history();
    }
}
