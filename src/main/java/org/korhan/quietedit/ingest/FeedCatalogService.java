package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Brings the {@code feed} table in line with the catalogue file.
 *
 * <p>Additive on purpose: removing a feed from {@code feeds.yaml} does not
 * deactivate its row. Documents and versions reference the feed, so silently
 * retiring it on a file edit would strand history; deactivation stays a deliberate
 * operator action on the row itself.
 */
@Service
public class FeedCatalogService {

    private static final Logger log = LoggerFactory.getLogger(FeedCatalogService.class);

    private final FeedCatalogLoader loader;
    private final FeedCatalogProperties properties;
    private final FeedRepository feeds;

    public FeedCatalogService(FeedCatalogLoader loader, FeedCatalogProperties properties, FeedRepository feeds) {
        this.loader = loader;
        this.properties = properties;
        this.feeds = feeds;
    }

    public CatalogSync sync() {
        List<FeedDefinition> definitions = loader.load(properties.location());
        int added = 0;
        int renamed = 0;
        for (FeedDefinition definition : definitions) {
            Optional<Feed> existing = feeds.findByUrl(definition.url());
            if (existing.isEmpty()) {
                feeds.save(new Feed(definition.url(), definition.name()));
                added++;
            } else if (!definition.name().equals(existing.get().getName())) {
                Feed feed = existing.get();
                feed.setName(definition.name());
                feeds.save(feed);
                renamed++;
            }
        }
        if (added > 0 || renamed > 0) {
            log.info("Feed catalogue synced: {} listed, {} added, {} renamed", definitions.size(), added, renamed);
        }
        return new CatalogSync(definitions.size(), added, renamed);
    }

    public record CatalogSync(int listed, int added, int renamed) {
    }
}
