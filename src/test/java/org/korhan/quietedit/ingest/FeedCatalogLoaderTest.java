package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

/**
 * Fixture-driven: what the catalogue file may contain, and what the loader is
 * allowed to do with the bad parts of it.
 */
class FeedCatalogLoaderTest {

    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("test.catalog.base-url", "https://placeholder.test");
    private final FeedCatalogLoader loader = new FeedCatalogLoader(new DefaultResourceLoader(), environment);

    @Test
    void loadsEntriesResolvesPlaceholdersAndDropsUnusableOnes() {
        List<FeedDefinition> definitions = loader.load("classpath:ingest/feed-catalog.yaml");

        assertThat(definitions).containsExactly(
                new FeedDefinition("https://example.test/rss.xml", "Example"),
                new FeedDefinition("https://placeholder.test/atom.xml", "From placeholder"),
                new FeedDefinition("https://example.test/untrimmed.xml", "Untrimmed"));
    }

    @Test
    void aMissingCatalogueIsEmptyRatherThanFatal() {
        assertThat(loader.load("classpath:ingest/there-is-no-such-file.yaml")).isEmpty();
    }
}
