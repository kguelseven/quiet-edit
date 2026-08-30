package org.korhan.quietedit.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.PostgresTestContainerConfig;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The full feed run against a real database and a real HTTP server.
 *
 * <p>Both feeds deliberately live on the same WireMock host, so the run also
 * exercises the per-host gate; the minimum interval is set to zero here because
 * the interval itself is asserted in {@link HostRateLimiterTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class FeedIngestRunTest {

    private static final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        server.start();
    }

    @Autowired
    private FeedFetchService feedFetchService;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private DocumentRepository documents;

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("test.wiremock.base-url", server::baseUrl);
        registry.add("quietedit.ingest.catalog.location", () -> "classpath:ingest/feeds-it.yaml");
        registry.add("quietedit.ingest.fetch.min-host-interval", () -> "0ms");
        registry.add("quietedit.ingest.fetch.initial-backoff", () -> "1ms");
        registry.add("quietedit.ingest.fetch.request-timeout", () -> "5s");
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        feeds.deleteAll();
    }

    @Test
    void seedsTheCatalogueAndSurvivesOneBrokenFeed() {
        server.stubFor(get("/healthy.xml").willReturn(ok(feedBody()).withHeader("ETag", "\"v1\"")));
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));

        FeedFetchRun run = feedFetchService.runOnce();

        assertThat(run.catalog().listed()).isEqualTo(2);
        assertThat(run.catalog().added()).isEqualTo(2);
        assertThat(run.count(FeedFetchOutcome.FETCHED)).isEqualTo(1);
        assertThat(run.count(FeedFetchOutcome.FAILED)).isEqualTo(1);

        Feed healthy = feeds.findByUrl(server.baseUrl() + "/healthy.xml").orElseThrow();
        assertThat(healthy.getLastStatus()).isEqualTo(200);
        assertThat(healthy.getEtag()).isEqualTo("\"v1\"");
        assertThat(healthy.getLastPolledAt()).isNotNull();

        Feed broken = feeds.findByUrl(server.baseUrl() + "/broken.xml").orElseThrow();
        assertThat(broken.getLastStatus()).isEqualTo(500);
        assertThat(broken.getLastPolledAt()).isNotNull();
        assertThat(broken.getEtag()).isNull();

        // Scope boundary: fetching parses nothing, so no document may appear yet.
        assertThat(documents.count()).isZero();
    }

    @Test
    void secondRunSendsTheValidatorAndTakesNoBodyFromA304() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/healthy.xml").atPriority(1)
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));
        server.stubFor(get("/healthy.xml").atPriority(2)
                .willReturn(ok(feedBody()).withHeader("ETag", "\"v1\"")));

        FeedFetchRun first = feedFetchService.runOnce();
        assertThat(first.count(FeedFetchOutcome.FETCHED)).isEqualTo(1);

        FeedFetchRun second = feedFetchService.runOnce();

        assertThat(second.catalog().added()).isZero();
        assertThat(second.count(FeedFetchOutcome.NOT_MODIFIED)).isEqualTo(1);
        FeedFetchResult healthyResult = second.results().stream()
                .filter(result -> result.url().endsWith("/healthy.xml"))
                .findFirst()
                .orElseThrow();
        assertThat(healthyResult.body()).isNull();
        assertThat(healthyResult.httpStatus()).isEqualTo(304);

        Feed healthy = feeds.findByUrl(server.baseUrl() + "/healthy.xml").orElseThrow();
        assertThat(healthy.getLastStatus()).isEqualTo(304);
        assertThat(healthy.getEtag()).isEqualTo("\"v1\"");

        server.verify(getRequestedFor(urlEqualTo("/healthy.xml"))
                .withHeader("If-None-Match", equalTo("\"v1\"")));
    }

    private static String feedBody() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Healthy</title></channel></rss>
                """;
    }
}
