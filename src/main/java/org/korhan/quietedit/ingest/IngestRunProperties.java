package org.korhan.quietedit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What a single run is allowed to do, as opposed to {@link IngestScheduleProperties},
 * which says when one starts.
 *
 * <p>{@code maxArticles} exists because the per-host gate spaces same-host requests, so
 * an unbounded first run over a real catalogue takes hours while the scheduler's fixed
 * delay only starts counting once it ends. The default of 50 is a starting point, not a
 * measurement.
 *
 * <p>The reasoning behind {@code maxArticleFailures} is in {@link ArticleBudget}.
 */
@ConfigurationProperties("quietedit.ingest.run")
public record IngestRunProperties(@DefaultValue("50") int maxArticles,
                                  @DefaultValue("3") int maxArticleFailures) {

    public IngestRunProperties {
        if (maxArticles < 1) {
            throw new IllegalArgumentException("quietedit.ingest.run.max-articles must be >= 1");
        }
        if (maxArticleFailures < 1) {
            throw new IllegalArgumentException("quietedit.ingest.run.max-article-failures must be >= 1");
        }
    }
}
