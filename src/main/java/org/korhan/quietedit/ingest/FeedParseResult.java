package org.korhan.quietedit.ingest;

import java.util.List;

/**
 * What one feed body yielded: the entries that survived, the ones that did not,
 * and -- if the body was not a feed at all -- why.
 *
 * <p>Skipped entries are carried as reasons rather than only logged so that the
 * "one bad item does not cost us the feed" promise is assertable in a test instead
 * of only observable in a log file.
 */
public record FeedParseResult(
        String feedType,
        List<FeedEntry> entries,
        List<String> skipped,
        String failureReason) {

    public static FeedParseResult parsed(String feedType, List<FeedEntry> entries, List<String> skipped) {
        return new FeedParseResult(feedType, List.copyOf(entries), List.copyOf(skipped), null);
    }

    public static FeedParseResult failed(String failureReason) {
        return new FeedParseResult(null, List.of(), List.of(), failureReason);
    }

    public boolean failed() {
        return failureReason != null;
    }
}
