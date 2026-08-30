package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches, caches and applies robots.txt per origin.
 *
 * <p>The cache is what makes this affordable: one robots.txt per origin per TTL,
 * not one per article. It is keyed by origin (scheme, host, port) because that is
 * the scope robots.txt has -- {@code news.example.com} and {@code example.com} are
 * separate files, and so are http and https.
 *
 * <p>Status handling follows RFC 9309: a 2xx is parsed, a 4xx means "no rules,
 * crawl freely" (a 404 is the normal case for sites without a robots.txt, and a 401
 * or 403 on robots.txt is not a statement about articles), and anything else --
 * 5xx, a transport failure, a redirect chain that does not resolve -- means the file
 * exists but could not be read, which is a full disallow. That last case is cached
 * only briefly, so a flaky robots endpoint costs us minutes rather than an hour.
 *
 * <p>The robots.txt request itself is not gated by robots.txt (that would not
 * terminate) but it does go through the per-host rate limiter, so a run cannot
 * bypass politeness by asking for many origins at once.
 */
@Component
public class RobotsPolicy {

    private static final Logger log = LoggerFactory.getLogger(RobotsPolicy.class);

    private final PoliteHttpFetcher http;
    private final ArticleFetchProperties articleProperties;
    private final Duration defaultMinInterval;
    private final String agentToken;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RobotsPolicy(PoliteHttpFetcher http, ArticleFetchProperties articleProperties,
                        FeedFetchProperties fetchProperties, Clock clock) {
        this.http = http;
        this.articleProperties = articleProperties;
        this.defaultMinInterval = fetchProperties.minHostInterval();
        this.agentToken = productToken(fetchProperties.userAgent());
        this.clock = clock;
    }

    /**
     * The rules in force for {@code uri}'s origin, from cache when still fresh.
     *
     * <p>Not synchronised across origins: two threads racing on the same cold origin
     * may both fetch robots.txt once. Locking that away would serialise unrelated
     * hosts, and the duplicate cost is one small request.
     */
    public RobotsRules rulesFor(URI uri) {
        String origin = origin(uri);
        Cached cached = cache.get(origin);
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt)) {
            return cached.rules;
        }
        Fetched fetched = fetch(origin);
        cache.put(origin, new Cached(fetched.rules, now.plus(fetched.ttl)));
        return fetched.rules;
    }

    /**
     * The gap this host is owed: its {@code Crawl-delay} when it asks for more than
     * our configured interval, clamped by {@code maxCrawlDelay}.
     */
    public Duration minInterval(RobotsRules rules) {
        Duration requested = rules.crawlDelay();
        if (requested.compareTo(articleProperties.maxCrawlDelay()) > 0) {
            requested = articleProperties.maxCrawlDelay();
        }
        return requested.compareTo(defaultMinInterval) > 0 ? requested : defaultMinInterval;
    }

    /** The request target robots.txt patterns are written against: path plus query. */
    public static String requestTarget(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private Fetched fetch(String origin) {
        URI robotsUri = URI.create(origin + "/robots.txt");
        PoliteHttpFetcher.Trace trace = http.follow(
                robotsUri, "text/plain, */*;q=0.5", RobotsRules.MAX_BYTES,
                articleProperties.maxRedirects(),
                PoliteHttpFetcher.BodyGate.always(),
                hop -> PoliteHttpFetcher.HopDecision.allow(defaultMinInterval));

        if (trace.failureReason() != null || trace.response() == null || trace.response().failed()) {
            String reason = trace.failureReason() != null
                    ? trace.failureReason()
                    : trace.response().failureReason();
            log.warn("robots.txt for {} unreadable ({}), treating the host as disallowed", origin, reason);
            return new Fetched(RobotsRules.denyAll(), articleProperties.robotsFailureCacheTtl());
        }

        PoliteHttpFetcher.Response response = trace.response();
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            // robots.txt is defined as UTF-8; a stray non-UTF-8 byte must not cost us
            // the whole file, so it decodes leniently rather than throwing.
            String body = new String(response.body(), StandardCharsets.UTF_8);
            return new Fetched(RobotsRules.parse(body, agentToken), articleProperties.robotsCacheTtl());
        }
        if (status >= 400 && status < 500) {
            return new Fetched(RobotsRules.allowAll(), articleProperties.robotsCacheTtl());
        }
        log.warn("robots.txt for {} returned HTTP {}, treating the host as disallowed", origin, status);
        return new Fetched(RobotsRules.denyAll(), articleProperties.robotsFailureCacheTtl());
    }

    private static String origin(URI uri) {
        StringBuilder origin = new StringBuilder()
                .append(uri.getScheme().toLowerCase(Locale.ROOT))
                .append("://")
                .append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            origin.append(':').append(uri.getPort());
        }
        return origin.toString();
    }

    /**
     * {@code quietedit/0.1 (+https://...)} names the product {@code quietedit}, and
     * that token -- not the whole header -- is what a robots.txt group is written
     * against.
     */
    private static String productToken(String userAgent) {
        String token = userAgent.trim().toLowerCase(Locale.ROOT);
        int cut = token.length();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/' || c == ' ' || c == '(') {
                cut = i;
                break;
            }
        }
        return token.substring(0, cut);
    }

    private record Cached(RobotsRules rules, Instant expiresAt) {
    }

    private record Fetched(RobotsRules rules, Duration ttl) {
    }
}
