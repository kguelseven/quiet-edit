package org.korhan.quietedit.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.korhan.quietedit.support.MutableClock;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The article fetcher against a real HTTP server: the redirect chain and the URL it
 * ends at, robots.txt on every hop, the non-HTML skip, and the retry budget. Backoff
 * and the per-host gap are recorded rather than slept, so the whole class stays fast.
 */
class ArticleFetcherTest {

    private static final String ARTICLE = """
            <!doctype html>
            <html lang="de"><head><title>Bericht</title></head>
            <body><p>Ein Satz.</p></body></html>
            """;

    @RegisterExtension
    static final WireMockExtension server = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path storageRoot;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-24T09:00:00Z"));
    private final List<Duration> waits = new ArrayList<>();

    private RawHtmlStore store;

    @BeforeEach
    void allowEverythingByDefault() {
        server.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(404)));
    }

    @Test
    void storesTheHtmlAndReportsWhereItCameFrom() {
        server.stubFor(get("/story-1").willReturn(okForContentType("text/html; charset=utf-8", ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/story-1"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FETCHED);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.finalUrl()).isEqualTo(url("/story-1"));
        assertThat(result.redirectChain()).containsExactly(url("/story-1"));
        assertThat(result.redirected()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.contentType()).startsWith("text/html");
        assertThat(result.contentLength()).isEqualTo(ARTICLE.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.failureReason()).isNull();
        assertThat(new String(store.read(result.rawHtmlRef()), StandardCharsets.UTF_8)).isEqualTo(ARTICLE);
    }

    @Test
    void theRedirectChainIsFollowedAndTheFinalUrlIsRecorded() {
        server.stubFor(get("/short").willReturn(aResponse().withStatus(301)
                .withHeader("Location", url("/interstitial"))));
        // A relative Location, as several CMSs emit it.
        server.stubFor(get("/interstitial").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/final?utm_source=news")));
        server.stubFor(get("/final?utm_source=news").willReturn(okForContentType("text/html", ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/short"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FETCHED);
        assertThat(result.requestedUrl()).isEqualTo(url("/short"));
        assertThat(result.finalUrl()).isEqualTo(url("/final?utm_source=news"));
        assertThat(result.redirectChain()).containsExactly(
                url("/short"), url("/interstitial"), url("/final?utm_source=news"));
        assertThat(result.redirected()).isTrue();
    }

    @Test
    void aChainLongerThanTheLimitIsAFailure() {
        server.stubFor(get("/hop-1").willReturn(redirectTo("/hop-2")));
        server.stubFor(get("/hop-2").willReturn(redirectTo("/hop-3")));
        server.stubFor(get("/hop-3").willReturn(redirectTo("/hop-4")));
        server.stubFor(get("/hop-4").willReturn(okForContentType("text/html", ARTICLE)));

        ArticleFetchResult result = fetcher(2).fetch(url("/hop-1"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("more than 2 redirects");
        assertThat(result.redirectChain()).hasSize(3);
        server.verify(0, getRequestedFor(urlEqualTo("/hop-4")));
    }

    @Test
    void aRedirectLoopIsDetectedInsteadOfWalkedToTheLimit() {
        server.stubFor(get("/loop-a").willReturn(redirectTo("/loop-b")));
        server.stubFor(get("/loop-b").willReturn(redirectTo("/loop-a")));

        ArticleFetchResult result = fetcher().fetch(url("/loop-a"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).startsWith("redirect loop at");
        server.verify(1, getRequestedFor(urlEqualTo("/loop-a")));
    }

    @Test
    void aRedirectWithoutALocationIsAFailureNotAnArticle() {
        server.stubFor(get("/moved").willReturn(aResponse().withStatus(301)));

        ArticleFetchResult result = fetcher().fetch(url("/moved"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("HTTP 301 without a usable Location");
    }

    @Test
    void aDisallowedUrlIsNeverRequested() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain", """
                User-agent: *
                Disallow: /private/
                """)));
        server.stubFor(get("/private/story").willReturn(okForContentType("text/html", ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/private/story"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.BLOCKED_BY_ROBOTS);
        assertThat(result.failureReason()).contains("robots.txt disallows");
        assertThat(result.rawHtmlRef()).isNull();
        server.verify(0, getRequestedFor(urlEqualTo("/private/story")));
    }

    @Test
    void aRedirectIntoADisallowedPathIsStoppedAtTheHop() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain", """
                User-agent: *
                Disallow: /private/
                """)));
        server.stubFor(get("/public/story").willReturn(redirectTo("/private/story")));
        server.stubFor(get("/private/story").willReturn(okForContentType("text/html", ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/public/story"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.BLOCKED_BY_ROBOTS);
        assertThat(result.redirectChain()).containsExactly(url("/public/story"), url("/private/story"));
        server.verify(0, getRequestedFor(urlEqualTo("/private/story")));
    }

    @Test
    void anUnreadableRobotsTxtBlocksTheHost() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(503)));
        server.stubFor(get("/story-1").willReturn(okForContentType("text/html", ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/story-1"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.BLOCKED_BY_ROBOTS);
        server.verify(0, getRequestedFor(urlEqualTo("/story-1")));
    }

    @Test
    void robotsTxtIsFetchedOncePerOriginAndThenCached() {
        server.stubFor(get("/story-1").willReturn(okForContentType("text/html", ARTICLE)));
        server.stubFor(get("/story-2").willReturn(okForContentType("text/html", ARTICLE)));
        ArticleFetcher fetcher = fetcher();

        fetcher.fetch(url("/story-1"));
        fetcher.fetch(url("/story-2"));

        server.verify(1, getRequestedFor(urlEqualTo("/robots.txt")));
    }

    @Test
    void aCrawlDelayLongerThanOurIntervalIsHonoured() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain", """
                User-agent: *
                Crawl-delay: 3
                """)));
        server.stubFor(get("/story-1").willReturn(okForContentType("text/html", ARTICLE)));
        server.stubFor(get("/story-2").willReturn(okForContentType("text/html", ARTICLE)));
        ArticleFetcher fetcher = fetcher();

        fetcher.fetch(url("/story-1"));
        fetcher.fetch(url("/story-2"));

        assertThat(waits).contains(Duration.ofSeconds(3));
    }

    @Test
    void aCrawlDelayIsClampedToTheConfiguredCeiling() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain", """
                User-agent: *
                Crawl-delay: 3600
                """)));
        server.stubFor(get("/story-1").willReturn(okForContentType("text/html", ARTICLE)));
        server.stubFor(get("/story-2").willReturn(okForContentType("text/html", ARTICLE)));
        ArticleFetcher fetcher = fetcher();

        fetcher.fetch(url("/story-1"));
        fetcher.fetch(url("/story-2"));

        assertThat(waits).contains(Duration.ofSeconds(30)).doesNotContain(Duration.ofHours(1));
    }

    @Test
    void aDeclaredPdfIsSkippedWithoutBecomingAVersion() {
        server.stubFor(get("/report.pdf").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/pdf")
                .withBody("%PDF-1.7 ...".getBytes(StandardCharsets.ISO_8859_1))));

        ArticleFetchResult result = fetcher().fetch(url("/report.pdf"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.SKIPPED_NOT_HTML);
        assertThat(result.failureReason()).isEqualTo("content type application/pdf");
        assertThat(result.rawHtmlRef()).isNull();
        assertThat(result.contentLength()).isZero();
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void anImageIsSkippedEvenWhenTheServerCallsItHtml() {
        server.stubFor(get("/photo").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})));

        ArticleFetchResult result = fetcher().fetch(url("/photo"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.SKIPPED_NOT_HTML);
        assertThat(result.failureReason()).isEqualTo("body is PNG");
        assertThat(result.rawHtmlRef()).isNull();
    }

    @Test
    void anUndeclaredButHtmlShapedBodyIsAccepted() {
        server.stubFor(get("/no-type").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/octet-stream")
                .withBody(ARTICLE)));

        ArticleFetchResult result = fetcher().fetch(url("/no-type"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FETCHED);
        assertThat(store.read(result.rawHtmlRef())).isEqualTo(ARTICLE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aGzippedBodyIsStoredDecompressed() {
        server.stubFor(get("/packed").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(ARTICLE))));

        ArticleFetchResult result = fetcher().fetch(url("/packed"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FETCHED);
        assertThat(new String(store.read(result.rawHtmlRef()), StandardCharsets.UTF_8)).isEqualTo(ARTICLE);
    }

    @Test
    void aBodyBeyondTheSizeLimitIsAFailureRatherThanATruncatedArticle() {
        server.stubFor(get("/huge").willReturn(okForContentType("text/html", "x".repeat(5_000))));

        ArticleFetchResult result = fetcher(5, DataSize.ofBytes(1_000)).fetch(url("/huge"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("body larger than 1000 bytes");
        assertThat(result.rawHtmlRef()).isNull();
    }

    @Test
    void anEmptyBodyIsAFailure() {
        server.stubFor(get("/blank").willReturn(okForContentType("text/html", "")));

        ArticleFetchResult result = fetcher().fetch(url("/blank"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("empty body");
    }

    @Test
    void serverErrorsAreRetriedWithGrowingBackoff() {
        server.stubFor(get("/flaky").willReturn(aResponse().withStatus(503)));

        ArticleFetchResult result = fetcher().fetch(url("/flaky"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.failureReason()).isEqualTo("HTTP 503");
        assertThat(waits).containsSubsequence(Duration.ofMillis(500), Duration.ofSeconds(1));
        server.verify(3, getRequestedFor(urlEqualTo("/flaky")));
    }

    @Test
    void aClientErrorIsNotRetried() {
        server.stubFor(get("/gone").willReturn(aResponse().withStatus(410)));

        ArticleFetchResult result = fetcher().fetch(url("/gone"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.httpStatus()).isEqualTo(410);
        assertThat(result.attempts()).isEqualTo(1);
        server.verify(1, getRequestedFor(urlEqualTo("/gone")));
    }

    @Test
    void aTimeoutIsRetriedAndThenReported() {
        server.stubFor(get("/slow").willReturn(ok(ARTICLE).withFixedDelay(2_000)));

        ArticleFetchResult result = fetcher(5, DataSize.ofMegabytes(8), Duration.ofMillis(300))
                .fetch(url("/slow"));

        assertThat(result.outcome()).isEqualTo(ArticleFetchOutcome.FAILED);
        assertThat(result.failureReason()).startsWith("timeout after");
        assertThat(result.attempts()).isEqualTo(3);
    }

    @Test
    void unusableUrlsFailWithoutTouchingTheNetwork() {
        ArticleFetcher fetcher = fetcher();

        assertThat(fetcher.fetch("not a url").failureReason()).isEqualTo("malformed url");
        assertThat(fetcher.fetch("ftp://example.com/story").failureReason())
                .isEqualTo("unsupported scheme: ftp");
        assertThat(fetcher.fetch("/relative/story").failureReason()).isEqualTo("unsupported scheme: none");
        assertThat(fetcher.fetch("https:///story").failureReason()).isEqualTo("url has no host");
        server.verify(0, getRequestedFor(urlEqualTo("/robots.txt")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder redirectTo(String path) {
        return aResponse().withStatus(302).withHeader("Location", path);
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private String url(String path) {
        return server.baseUrl() + path;
    }

    private ArticleFetcher fetcher() {
        return fetcher(5);
    }

    private ArticleFetcher fetcher(int maxRedirects) {
        return fetcher(maxRedirects, DataSize.ofMegabytes(8));
    }

    private ArticleFetcher fetcher(int maxRedirects, DataSize maxBodySize) {
        return fetcher(maxRedirects, maxBodySize, Duration.ofSeconds(10));
    }

    /**
     * The minimum host interval is zero here so that only a robots.txt
     * {@code Crawl-delay} can produce a wait; the interval itself is covered by
     * {@link HostRateLimiterTest}.
     */
    private ArticleFetcher fetcher(int maxRedirects, DataSize maxBodySize, Duration requestTimeout) {
        FeedFetchProperties fetchProperties = new FeedFetchProperties(
                Duration.ofSeconds(2), requestTimeout, Duration.ZERO, 3,
                Duration.ofMillis(500), 2, "quietedit/test (+https://example.org)");
        ArticleFetchProperties articleProperties = new ArticleFetchProperties(
                maxRedirects, maxBodySize, Duration.ofHours(1), Duration.ofMinutes(5),
                Duration.ofSeconds(30), storageRoot);
        Sleeper sleeper = duration -> {
            waits.add(duration);
            clock.advance(duration);
        };
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(fetchProperties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        PoliteHttpFetcher http = new PoliteHttpFetcher(
                httpClient, fetchProperties, new HostRateLimiter(fetchProperties, clock, sleeper), sleeper);
        store = new RawHtmlStore(articleProperties);
        return new ArticleFetcher(http, new RobotsPolicy(http, articleProperties, fetchProperties, clock),
                store, articleProperties, clock);
    }
}
