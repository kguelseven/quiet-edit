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
 * <p>This exists because {@code document} can only record success. A link that is
 * fetched but yields nothing -- refused by robots.txt, a paywall stub, a binary, an
 * unusable URL -- leaves no trace there, so {@link ArticleBudget} could not tell
 * "never tried" from "tried and declined" and kept ranking such a link ahead of
 * every real article, run after run.
 *
 * <p>Written after the outcome of an attempt is known, read before the next run
 * ranks anything. Both sides use the <em>provisional</em> identity of a link -- what
 * {@code UrlCanonicalizer} makes of the URL the feed advertised -- because that is
 * the only identity available before a fetch, and ranking has to happen before the
 * fetch. Recording under the document's real canonical URL instead would leave the
 * provisional identity of every redirecting link permanently untried.
 */
@Service
public class ArticleAttemptLog {

    private final ArticleAttemptRepository attempts;

    public ArticleAttemptLog(ArticleAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * What is known about each of these link identities.
     *
     * <p>One query rather than a lookup per link: the caller is the ingest run's
     * budget, which ranks every candidate of a run before it fetches any of them,
     * and a per-candidate round trip would put hundreds of them in front of the
     * first fetch.
     *
     * @return a history for every identity asked about, {@link AttemptHistory#NEVER}
     *         where this system has no record of the link
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
     * Records one finished attempt. A success clears the strike count rather than
     * decrementing it: three strikes is about a link that is consistently unusable,
     * and one good fetch says it is not.
     *
     * <p>One short transaction per attempt, like document registration: an ingest run
     * spends nearly all of its wall clock in network I/O, and a transaction spanning
     * the run would pin a connection for all of it.
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
