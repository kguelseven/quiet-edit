package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Follows an article link and puts the retrieved HTML in the store.
 *
 * <p>Like {@link FeedFetcher} it never throws: one publisher's broken page must not end a
 * run. The three non-failure ways it can decline are kept apart because only one of them
 * produces a version and the other two must not look like errors in a report.
 *
 * <p>Politeness, retries and redirect walking live in {@link PoliteHttpFetcher}; what
 * this class adds is robots.txt on every hop, the HTML decision, and storing the body.
 *
 * <p>Nothing is extracted from the HTML here, which keeps the only class that touches the
 * network testable against a stub server without needing a parser to be right.
 */
@Component
public class ArticleFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);

    private static final String ACCEPT =
            "text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8";

    private final PoliteHttpFetcher http;
    private final RobotsPolicy robots;
    private final RawHtmlStore store;
    private final ArticleFetchProperties properties;
    private final Clock clock;

    public ArticleFetcher(PoliteHttpFetcher http, RobotsPolicy robots, RawHtmlStore store,
                          ArticleFetchProperties properties, Clock clock) {
        this.http = http;
        this.robots = robots;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public ArticleFetchResult fetch(String url) {
        URI start;
        try {
            start = URI.create(url.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ArticleFetchResult.failed(url, null, clock.instant(), null,
                    "malformed url", List.of(), 0);
        }
        String scheme = start.getScheme() == null ? "" : start.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return ArticleFetchResult.failed(url, null, clock.instant(), null,
                    "unsupported scheme: " + (scheme.isEmpty() ? "none" : scheme), List.of(), 0);
        }
        if (start.getHost() == null) {
            return ArticleFetchResult.failed(url, null, clock.instant(), null,
                    "url has no host", List.of(), 0);
        }

        PoliteHttpFetcher.Trace trace = http.follow(
                start, ACCEPT, properties.maxBodySize().toBytes(), properties.maxRedirects(),
                ArticleFetcher::htmlBodiesOnly, this::guard);

        return interpret(url, trace);
    }

    /**
     * A redirect has no body worth keeping and an error page's body is not an article, so
     * only a 2xx declaring something HTML-shaped is downloaded.
     */
    private static boolean htmlBodiesOnly(int status, HttpHeaders headers) {
        if (status < 200 || status >= 300) {
            return false;
        }
        return HtmlDetection.mayBeHtml(headers.firstValue("Content-Type").orElse(null));
    }

    /** robots.txt is consulted for every hop, including the ones a redirect chose. */
    private PoliteHttpFetcher.HopDecision guard(URI uri) {
        RobotsRules rules = robots.rulesFor(uri);
        if (!rules.allows(RobotsPolicy.requestTarget(uri))) {
            return PoliteHttpFetcher.HopDecision.refuse("robots.txt disallows " + uri);
        }
        return PoliteHttpFetcher.HopDecision.allow(robots.minInterval(rules));
    }

    private ArticleFetchResult interpret(String requestedUrl, PoliteHttpFetcher.Trace trace) {
        Instant fetchedAt = clock.instant();
        List<String> chain = trace.chain().stream().map(URI::toString).toList();
        String finalUrl = chain.isEmpty() ? null : chain.getLast();

        if (trace.refused()) {
            log.info("Skipping {}: {}", requestedUrl, trace.refusalReason());
            return ArticleFetchResult.blockedByRobots(requestedUrl, finalUrl, fetchedAt,
                    trace.refusalReason(), chain);
        }
        if (trace.failureReason() != null) {
            return ArticleFetchResult.failed(requestedUrl, finalUrl, fetchedAt, null,
                    trace.failureReason(), chain, 0);
        }

        PoliteHttpFetcher.Response response = trace.response();
        if (response.failed()) {
            return ArticleFetchResult.failed(requestedUrl, finalUrl, fetchedAt, response.statusCode(),
                    response.failureReason(), chain, response.attempts());
        }

        int status = response.statusCode();
        String contentType = response.header("Content-Type");
        if (status < 200 || status >= 300) {
            return ArticleFetchResult.failed(requestedUrl, finalUrl, fetchedAt, status,
                    "HTTP " + status, chain, response.attempts());
        }
        if (response.bodySkipped()) {
            return ArticleFetchResult.skippedNotHtml(requestedUrl, finalUrl, fetchedAt, status, contentType,
                    "content type " + HtmlDetection.mediaType(contentType), chain, response.attempts());
        }

        byte[] body = response.body();
        String signature = HtmlDetection.binarySignature(body);
        if (signature != null) {
            // The header claimed HTML (or claimed nothing) but the bytes are a binary.
            return ArticleFetchResult.skippedNotHtml(requestedUrl, finalUrl, fetchedAt, status, contentType,
                    "body is " + signature, chain, response.attempts());
        }
        if (body.length == 0) {
            return ArticleFetchResult.failed(requestedUrl, finalUrl, fetchedAt, status,
                    "empty body", chain, response.attempts());
        }

        String ref = store.write(body);
        if (chain.size() > 1) {
            log.debug("Article {} followed {} redirects to {}", requestedUrl, chain.size() - 1, finalUrl);
        }
        return ArticleFetchResult.fetched(requestedUrl, finalUrl, fetchedAt, status, contentType,
                ref, body.length, chain, response.attempts());
    }
}
