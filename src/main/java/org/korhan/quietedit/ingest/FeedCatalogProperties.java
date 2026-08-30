package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the feed catalogue lives. A Spring {@code Resource} location rather than a
 * plain path, so tests can point at a classpath fixture instead of the checked-in
 * {@code feeds.yaml} and never reach the real feeds.
 */
@ConfigurationProperties("quietedit.ingest.catalog")
public record FeedCatalogProperties(@DefaultValue("file:./feeds.yaml") String location) {
}
