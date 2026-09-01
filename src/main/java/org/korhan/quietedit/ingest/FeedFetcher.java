package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Performs one conditional GET per feed and never throws: a broken feed is a result with
 * {@link FeedFetchOutcome#FAILED}, so one dead publisher cannot abort a run.
 *
 * <p>Only 5xx and transport failures are retried, with exponential backoff. A 4xx is a
 * verdict about our request and retrying it only burns the host's patience; a 304 is a
 * success.
 *
 * <p>Redirects are followed by the client itself ({@link HttpClient.Redirect#NORMAL}),
 * which keeps a permanently moved feed working without rewriting the catalogue.
 */
@Component
public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private final HttpClient feedHttpClient;
    private final FeedFetchProperties properties;
    private final HostRateLimiter rateLimiter;
    private final Clock clock;
    private final Sleeper sleeper;

    // Named after the bean: the article HttpClient must never be injected here, it follows no redirects.
    public FeedFetcher(HttpClient feedHttpClient, FeedFetchProperties properties,
                       HostRateLimiter rateLimiter, Clock clock, Sleeper sleeper) {
        this.feedHttpClient = feedHttpClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public FeedFetchResult fetch(Feed feed) {
        URI uri;
        try {
            uri = URI.create(feed.getUrl());
        } catch (IllegalArgumentException e) {
            return FeedFetchResult.failed(feed, clock.instant(), null, "malformed url: " + e.getMessage(), 0);
        }
        if (uri.getHost() == null) {
            return FeedFetchResult.failed(feed, clock.instant(), null, "url has no host", 0);
        }

        Duration backoff = properties.initialBackoff();
        String lastFailure = "no attempt made";
        Integer lastStatus = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                HttpResponse<byte[]> response = rateLimiter.call(
                        uri.getHost(), () -> feedHttpClient.send(request(feed, uri), HttpResponse.BodyHandlers.ofByteArray()));
                int status = response.statusCode();
                Instant fetchedAt = clock.instant();

                if (status == 304) {
                    return FeedFetchResult.notModified(feed, fetchedAt, status,
                            header(response, "ETag"), header(response, "Last-Modified"), attempt);
                }
                if (status >= 200 && status < 300) {
                    return FeedFetchResult.fetched(feed, fetchedAt, status, response.body(),
                            header(response, "ETag"), header(response, "Last-Modified"),
                            header(response, "Content-Type"), attempt);
                }
                lastStatus = status;
                lastFailure = "HTTP " + status;
                if (status < 500) {
                    log.warn("Feed {} returned {}, not retrying", feed.getUrl(), status);
                    return FeedFetchResult.failed(feed, fetchedAt, status, lastFailure, attempt);
                }
            } catch (HttpTimeoutException e) {
                lastStatus = null;
                lastFailure = "timeout after " + properties.requestTimeout();
            } catch (IOException e) {
                lastStatus = null;
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FeedFetchResult.failed(feed, clock.instant(), null, "interrupted", attempt);
            }

            if (attempt < properties.maxAttempts()) {
                log.warn("Feed {} attempt {}/{} failed ({}), retrying in {}",
                        feed.getUrl(), attempt, properties.maxAttempts(), lastFailure, backoff);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return FeedFetchResult.failed(feed, clock.instant(), lastStatus, "interrupted", attempt);
                }
                backoff = backoff.multipliedBy(properties.backoffMultiplier());
            }
        }

        log.warn("Feed {} failed after {} attempts: {}", feed.getUrl(), properties.maxAttempts(), lastFailure);
        return FeedFetchResult.failed(feed, clock.instant(), lastStatus, lastFailure, properties.maxAttempts());
    }

    private HttpRequest request(Feed feed, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8");
        if (feed.getEtag() != null) {
            builder.header("If-None-Match", feed.getEtag());
        }
        if (feed.getLastModified() != null) {
            builder.header("If-Modified-Since", feed.getLastModified());
        }
        return builder.build();
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
