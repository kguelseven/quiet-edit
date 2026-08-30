package org.korhan.quietedit.ingest;

/**
 * One entry of a feed, in the same shape whether it came from RSS 2.0 or Atom 1.0.
 *
 * <p>{@code publishedRaw} and {@code updatedRaw} are the date texts exactly as the
 * feed wrote them -- {@code "Tue, 18 Aug 2026 07:14:00 +0200"}, {@code
 * "2026-08-18T07:14:00Z"}, or whatever else a publisher considers a date. They are
 * deliberately not timestamps: turning them into an instant means deciding what a
 * missing timezone or a date in the future should mean, and that decision belongs
 * to date normalisation, not here. Keeping the text means that decision still has
 * everything it needs.
 *
 * <p>{@code summary} keeps the publisher's markup untouched. It is a teaser, not
 * article text -- the article body comes from fetching {@code link} -- so stripping
 * HTML here would only destroy information for no reader's benefit.
 *
 * <p>{@code guid} is the feed's own identifier ({@code <guid>} or Atom {@code
 * <id>}) and is null when the feed omits it. No identifier is synthesised from the
 * link: document identity in this system is the canonical URL, and a made-up guid
 * would look like a publisher's promise without being one.
 */
public record FeedEntry(
        String title,
        String link,
        String summary,
        String publishedRaw,
        String updatedRaw,
        String guid) {
}
