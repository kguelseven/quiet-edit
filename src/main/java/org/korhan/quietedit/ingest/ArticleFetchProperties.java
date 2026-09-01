package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Limits that only apply to article requests. Timeouts, the retry budget and the
 * per-host interval are deliberately <em>not</em> repeated here: articles and feeds
 * hit the same publishers, so they share {@link FeedFetchProperties}.
 *
 * <p>{@code maxCrawlDelay} caps what a {@code Crawl-delay} in robots.txt can cost
 * us. The directive is honoured because respecting robots.txt is the point, but an
 * unbounded value -- whether hostile or a typo -- would let one host stall a whole
 * run, so anything above the cap is clamped to it.
 *
 * <p>{@code robotsFailureCacheTtl} is shorter than {@code robotsCacheTtl} on
 * purpose: a 5xx on robots.txt blocks the host (RFC 9309), and a host must not stay
 * blocked for a full hour because its robots endpoint hiccupped once.
 */
@ConfigurationProperties("quietedit.ingest.article")
public record ArticleFetchProperties(
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("8MB") DataSize maxBodySize,
        @DefaultValue("1h") Duration robotsCacheTtl,
        @DefaultValue("5m") Duration robotsFailureCacheTtl,
        @DefaultValue("30s") Duration maxCrawlDelay,
        @DefaultValue("./data/raw-html") Path storageRoot) {

    public ArticleFetchProperties {
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("quietedit.ingest.article.max-redirects must be >= 0");
        }
        if (maxBodySize.toBytes() < 1) {
            throw new IllegalArgumentException("quietedit.ingest.article.max-body-size must be positive");
        }
    }
}
