package org.korhan.quietedit.ingest;

/**
 * One entry of a feed, in the same shape whether it came from RSS 2.0 or Atom 1.0.
 *
 * <p>{@code publishedRaw} and {@code updatedRaw} are the date texts exactly as the feed
 * wrote them. Turning them into an instant means deciding what a missing timezone or a
 * date in the future should mean, and that decision belongs to date normalisation.
 *
 * <p>{@code summary} keeps the publisher's markup untouched: it is a teaser rather than
 * article text, so stripping HTML would destroy information for no reader's benefit.
 *
 * <p>{@code guid} is null when the feed omits it, and nothing is synthesised from the
 * link: a made-up guid would look like a publisher's promise without being one.
 */
public record FeedEntry(
        String title,
        String link,
        String summary,
        String publishedRaw,
        String updatedRaw,
        String guid) {
}
