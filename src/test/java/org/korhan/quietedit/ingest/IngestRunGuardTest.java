package org.korhan.quietedit.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.PostgresTestContainerConfig;
import org.korhan.quietedit.support.TestDatabase;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The two guards on a run: how much one run may do, and how many runs may happen
 * at once. Both are driven through {@code runOnce()} and the endpoint, never
 * through the scheduler, which is switched off in {@code application-test.yml}.
 *
 * <p>The ceiling is set to two articles here so that a four-entry feed exceeds it.
 * The number is arbitrary; what the test is about is that the run stops at it and
 * that the next run continues from where it stopped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class IngestRunGuardTest {

    private static final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());

    private static final Path storageRoot = createStorageRoot();

    static {
        server.start();
    }

    @Autowired
    private TestDatabase database;

    @Autowired
    private IngestService ingestService;

    @LocalServerPort
    private int port;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private ArticleAttemptRepository attempts;

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("test.wiremock.base-url", server::baseUrl);
        registry.add("quietedit.ingest.catalog.location", () -> "classpath:ingest/feeds-guard-it.yaml");
        registry.add("quietedit.ingest.run.max-articles", () -> "2");
        registry.add("quietedit.ingest.fetch.min-host-interval", () -> "0ms");
        registry.add("quietedit.ingest.fetch.initial-backoff", () -> "1ms");
        registry.add("quietedit.ingest.fetch.request-timeout", () -> "10s");
        registry.add("quietedit.ingest.article.storage-root", storageRoot::toString);
        registry.add("quietedit.ingest.article.robots-cache-ttl", () -> "0ms");
        registry.add("quietedit.ingest.article.robots-failure-cache-ttl", () -> "0ms");
        // The re-check curve's floor, effectively removed. This test is about the
        // budget's rotation: what it asserts is which candidates a run reaches, and
        // runs a fraction of a second apart must all be allowed to reach theirs. What
        // the curve decides is asserted in RecheckPolicyTest.
        registry.add("quietedit.ingest.recheck.min-interval", () -> "1ms");
    }

    @AfterAll
    static void stopServerAndCleanUp() throws IOException {
        server.stop();
        deleteRecursively(storageRoot);
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        // The whole schema, not just the documents: a leftover attempt row would rank
        // this test's candidates by what the previous test tried, not by what this one
        // did, and versions cannot be deleted at all.
        database.reset();
        server.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(404)));
    }

    @Test
    void aRunStopsAtItsCeilingAndTheNextRunTakesTheRest() {
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("/eins", "/zwei", "/drei", "/vier"))));
        for (String path : List.of("/eins", "/zwei", "/drei", "/vier")) {
            server.stubFor(get(path).willReturn(articlePage(path)));
        }

        IngestRun first = ingestService.runOnce();

        assertThat(first.checked()).isEqualTo(4);
        assertThat(first.count(ArticleIngestOutcome.NEW)).isEqualTo(2);
        assertThat(first.count(ArticleIngestOutcome.DEFERRED)).isEqualTo(2);
        // Nothing above the ceiling was touched, not even fetched and thrown away.
        assertThat(deferredLinks(first)).containsExactly(url("/drei"), url("/vier"));
        server.verify(0, getRequestedFor(urlEqualTo("/drei")));
        assertThat(documents.count()).isEqualTo(2);

        IngestRun second = ingestService.runOnce();

        // The leftovers were not lost: never-fetched candidates outrank the two the
        // first run already has, so the second run continues where the first stopped.
        assertThat(second.count(ArticleIngestOutcome.NEW)).isEqualTo(2);
        assertThat(second.count(ArticleIngestOutcome.UNCHANGED)).isZero();
        assertThat(deferredLinks(second)).containsExactly(url("/eins"), url("/zwei"));
        assertThat(documents.count()).isEqualTo(4);

        // Everything is known now, so the ranking falls back to least recently checked.
        IngestRun third = ingestService.runOnce();
        assertThat(third.count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(2);
        assertThat(deferredLinks(third)).containsExactly(url("/drei"), url("/vier"));
        assertThat(documents.count()).isEqualTo(4);
    }

    /**
     * Two triggers at once. The article fetch is delayed so that the first run is
     * provably still inside {@code runOnce()} while the second one is attempted;
     * the WireMock listener is what makes "still inside" an observation rather than
     * a guess about timing.
     */
    @Test
    void aSecondTriggerIsRefusedWhileARunIsInFlight() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        server.addMockServiceRequestListener((request, response) -> inFlight.countDown());
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("/eins"))));
        server.stubFor(get("/eins").willReturn(articlePage("/eins").withFixedDelay(2000)));

        ExecutorService trigger = Executors.newSingleThreadExecutor();
        try {
            Future<IngestRun> first = trigger.submit(ingestService::runOnce);
            assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(ingestService::runOnce).isInstanceOf(IngestAlreadyRunningException.class);

            HttpResponse<String> refusal = triggerOverHttp();
            assertThat(refusal.statusCode()).isEqualTo(409);
            assertThat(refusal.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
            JsonNode problem = JsonMapper.builder().build().readTree(refusal.body());
            assertThat(problem.path("status").asInt()).isEqualTo(409);
            assertThat(problem.path("title").asText()).isEqualTo("Ingest run already in progress");
            assertThat(problem.path("detail").asText()).isNotBlank();
            assertThat(problem.path("type").asText()).isEqualTo("urn:quietedit:problem:ingest-already-running");

            assertThat(first.get(30, TimeUnit.SECONDS).count(ArticleIngestOutcome.NEW)).isEqualTo(1);
        } finally {
            trigger.shutdownNow();
        }

        // The refused attempts fetched nothing, and the permit is free again afterwards.
        server.verify(1, getRequestedFor(urlEqualTo("/eins")));
        assertThat(ingestService.runOnce().count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(1);
    }

    /** The endpoint over real HTTP, so that the refusal is asserted as a client sees it. */
    private HttpResponse<String> triggerOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ingest/run"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> deferredLinks(IngestRun run) {
        return run.articles().stream()
                .filter(article -> article.outcome() == ArticleIngestOutcome.DEFERRED)
                .map(ArticleIngestResult::link)
                .toList();
    }

    private static String url(String path) {
        return server.baseUrl() + path;
    }

    private static String feedBody(String... paths) {
        StringBuilder items = new StringBuilder();
        for (String path : paths) {
            items.append("""
                    <item>
                      <title>%s</title>
                      <link>%s</link>
                      <pubDate>Mon, 24 Aug 2026 07:14:00 +0200</pubDate>
                    </item>
                    """.formatted(path, url(path)));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>Press</title>
                  %s
                </channel></rss>
                """.formatted(items);
    }

    private static ResponseDefinitionBuilder articlePage(String headline) {
        return okForContentType("text/html; charset=utf-8", """
                <!doctype html>
                <html lang="de"><head><title>%s</title></head>
                <body>
                  <article>
                    <h1>%s</h1>
                    <p>Der Bundestag hat am Montag nach langer Debatte einen Beschluss gefasst.</p>
                    <p>Die Opposition kritisierte das Vorhaben am Abend in scharfen Worten.</p>
                  </article>
                </body></html>
                """.formatted(headline, headline));
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("quietedit-guard-it");
        } catch (IOException e) {
            throw new IllegalStateException("could not create a raw html store for the test", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }
}
