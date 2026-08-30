package org.korhan.quietedit.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The fetcher's contract against a real HTTP server: conditional requests, the
 * retry budget, and the promise that no response shape produces an exception.
 * Backoff is recorded instead of slept, so the retry tests stay fast.
 */
class FeedFetcherTest {

    private static final String FEED_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Example</title></channel></rss>
            """;

    @RegisterExtension
    static final WireMockExtension server = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final List<Duration> backoffs = new ArrayList<>();

    @Test
    void storesBodyStatusAndValidators() {
        server.stubFor(get("/feed.xml").willReturn(ok(FEED_BODY)
                .withHeader("Content-Type", "application/rss+xml; charset=iso-8859-1")
                .withHeader("ETag", "\"v1\"")
                .withHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")));

        FeedFetchResult result = fetcher().fetch(feed("/feed.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FETCHED);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.etag()).isEqualTo("\"v1\"");
        assertThat(result.lastModified()).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        assertThat(result.contentType()).startsWith("application/rss+xml");
        assertThat(result.fetchedAt()).isNotNull();
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(FEED_BODY);
    }

    @Test
    void sendsNoConditionalHeadersOnTheFirstEverRequest() {
        server.stubFor(get("/feed.xml").willReturn(ok(FEED_BODY)));

        fetcher().fetch(feed("/feed.xml"));

        server.verify(getRequestedFor(urlEqualTo("/feed.xml"))
                .withHeader("If-None-Match", absent())
                .withHeader("If-Modified-Since", absent()));
    }

    @Test
    void echoesStoredValidatorsBackAsConditionalHeaders() {
        server.stubFor(get("/feed.xml").willReturn(aResponse().withStatus(304)));
        Feed feed = feed("/feed.xml");
        feed.setEtag("\"v1\"");
        feed.setLastModified("Wed, 21 Oct 2015 07:28:00 GMT");

        FeedFetchResult result = fetcher().fetch(feed);

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.NOT_MODIFIED);
        server.verify(getRequestedFor(urlEqualTo("/feed.xml"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .withHeader("If-Modified-Since", equalTo("Wed, 21 Oct 2015 07:28:00 GMT")));
    }

    @Test
    void notModifiedCarriesNoBody() {
        server.stubFor(get("/feed.xml").willReturn(aResponse().withStatus(304)));

        FeedFetchResult result = fetcher().fetch(feed("/feed.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.NOT_MODIFIED);
        assertThat(result.httpStatus()).isEqualTo(304);
        assertThat(result.bodySize()).isZero();
        assertThat(result.body()).isNull();
    }

    @Test
    void serverErrorIsRetriedUpToTheAttemptBudgetWithGrowingBackoff() {
        server.stubFor(get("/feed.xml").willReturn(aResponse().withStatus(503)));

        FeedFetchResult result = fetcher().fetch(feed("/feed.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FAILED);
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.failureReason()).isEqualTo("HTTP 503");
        assertThat(backoffs).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
        server.verify(3, getRequestedFor(urlEqualTo("/feed.xml")));
    }

    @Test
    void transientServerErrorSucceedsOnALaterAttempt() {
        server.stubFor(get("/feed.xml").inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        server.stubFor(get("/feed.xml").inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(ok(FEED_BODY)));

        FeedFetchResult result = fetcher().fetch(feed("/feed.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FETCHED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(backoffs).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void timeoutIsRetriedThenReportedAsFailure() {
        server.stubFor(get("/slow.xml").willReturn(ok(FEED_BODY).withFixedDelay(2_000)));

        FeedFetchResult result = fetcher(Duration.ofMillis(300)).fetch(feed("/slow.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.failureReason()).startsWith("timeout after");
        server.verify(3, getRequestedFor(urlEqualTo("/slow.xml")));
    }

    @Test
    void redirectIsFollowed() {
        server.stubFor(get("/moved.xml").willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", server.baseUrl() + "/final.xml")));
        server.stubFor(get("/final.xml").willReturn(ok(FEED_BODY).withHeader("ETag", "\"final\"")));

        FeedFetchResult result = fetcher().fetch(feed("/moved.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FETCHED);
        assertThat(result.etag()).isEqualTo("\"final\"");
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo(FEED_BODY);
        server.verify(getRequestedFor(urlEqualTo("/final.xml")));
    }

    @Test
    void clientErrorIsNotRetried() {
        server.stubFor(get("/gone.xml").willReturn(aResponse().withStatus(404)));

        FeedFetchResult result = fetcher().fetch(feed("/gone.xml"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FAILED);
        assertThat(result.httpStatus()).isEqualTo(404);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(backoffs).isEmpty();
        server.verify(1, getRequestedFor(urlEqualTo("/gone.xml")));
    }

    @Test
    void unreachableHostFailsWithoutThrowing() {
        FeedFetchResult result = fetcher().fetch(new Feed("not-a-url", "Broken"));

        assertThat(result.outcome()).isEqualTo(FeedFetchOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("url has no host");
        assertThat(result.attempts()).isZero();
    }

    private Feed feed(String path) {
        return new Feed(server.baseUrl() + path, "Example");
    }

    private FeedFetcher fetcher() {
        return fetcher(Duration.ofSeconds(10));
    }

    private FeedFetcher fetcher(Duration requestTimeout) {
        FeedFetchProperties properties = new FeedFetchProperties(
                Duration.ofSeconds(2), requestTimeout, Duration.ZERO, 3, Duration.ofMillis(500), 2, "quietedit-test");
        Clock clock = Clock.systemUTC();
        Sleeper sleeper = backoffs::add;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new FeedFetcher(httpClient, properties, new HostRateLimiter(properties, clock, sleeper), clock, sleeper);
    }
}
