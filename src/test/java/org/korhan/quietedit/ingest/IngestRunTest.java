package org.korhan.quietedit.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.PostgresTestContainerConfig;
import org.korhan.quietedit.versioning.Document;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.korhan.quietedit.versioning.DocumentVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * The whole path from outside to inside, against a real database and a real HTTP
 * server: catalogue sync, feed poll, parse, article fetch, boilerplate removal and
 * document identity, driven only by {@code runOnce()} with the scheduler switched
 * off in {@code application-test.yml}.
 *
 * <p>Every feed lives on the same WireMock host, so the run also crosses the
 * per-host gate. The interval is zeroed here because the gate's timing is asserted
 * in {@link HostRateLimiterTest}; what matters for this test is that a shared host
 * does not deadlock or drop work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class IngestRunTest {

    private static final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());

    private static final Path storageRoot = createStorageRoot();

    static {
        server.start();
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private IngestController controller;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentVersionRepository versions;

    @Autowired
    private ArticleAttemptRepository attempts;

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("test.wiremock.base-url", server::baseUrl);
        registry.add("quietedit.ingest.catalog.location", () -> "classpath:ingest/feeds-ingest-it.yaml");
        registry.add("quietedit.ingest.fetch.min-host-interval", () -> "0ms");
        registry.add("quietedit.ingest.fetch.initial-backoff", () -> "1ms");
        registry.add("quietedit.ingest.fetch.request-timeout", () -> "5s");
        registry.add("quietedit.ingest.article.storage-root", storageRoot::toString);
        // Never cached: robots.txt differs per test, and one test's answer must not
        // still be in force in the next one.
        registry.add("quietedit.ingest.article.robots-cache-ttl", () -> "0ms");
        registry.add("quietedit.ingest.article.robots-failure-cache-ttl", () -> "0ms");
    }

    @AfterAll
    static void stopServerAndCleanUp() throws IOException {
        server.stop();
        deleteRecursively(storageRoot);
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        documents.deleteAll();
        attempts.deleteAll();
        feeds.deleteAll();
        server.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(404)));
    }

    @Test
    void ingestsEveryUsableArticleAndSurvivesABrokenFeed() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform", "/leitartikel"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire", "/dossier.pdf", "/paywall"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));
        server.stubFor(get("/leitartikel").willReturn(articlePage("Ein Kommentar")));
        server.stubFor(get("/dossier.pdf").willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody("%PDF-1.7")));
        server.stubFor(get("/paywall").willReturn(okForContentType("text/html",
                "<!doctype html><html><body><div class=\"paywall\"></div></body></html>")));

        IngestRun run = ingestService.runOnce();

        assertThat(run.catalog().listed()).isEqualTo(3);
        assertThat(run.catalog().added()).isEqualTo(3);
        assertThat(run.feedCount(FeedFetchOutcome.FETCHED)).isEqualTo(2);
        assertThat(run.feedCount(FeedFetchOutcome.FAILED)).isEqualTo(1);

        assertThat(run.checked()).isEqualTo(4);
        assertThat(run.count(ArticleIngestOutcome.NEW)).isEqualTo(2);
        assertThat(run.count(ArticleIngestOutcome.UNCHANGED)).isZero();
        assertThat(run.count(ArticleIngestOutcome.SKIPPED)).isEqualTo(2);
        assertThat(run.count(ArticleIngestOutcome.FAILED)).isZero();

        // The counts are the per-feed detail, summarised -- not a parallel tally.
        assertThat(run.count(ArticleIngestOutcome.NEW)
                + run.count(ArticleIngestOutcome.UNCHANGED)
                + run.count(ArticleIngestOutcome.SKIPPED)
                + run.count(ArticleIngestOutcome.FAILED)).isEqualTo(run.checked());

        ArticleIngestResult ingested = article(run, "/steuerreform");
        assertThat(ingested.outcome()).isEqualTo(ArticleIngestOutcome.NEW);
        assertThat(ingested.canonicalUrl()).isEqualTo(canonical("/steuerreform"));
        assertThat(ingested.paragraphs()).isEqualTo(2);
        assertThat(ingested.rawHtmlRef()).isNotNull();
        assertThat(ingested.documentId()).isNotNull();

        // One item in each feed carries no link. The parser drops it and keeps the rest.
        assertThat(feed(run, "/press.xml").entries()).isEqualTo(2);
        assertThat(feed(run, "/press.xml").skippedEntries()).isEqualTo(1);
        assertThat(feed(run, "/press.xml").feedType()).isNotNull();

        // Neither non-HTML nor an unreadable page is an error: the run declined, correctly.
        assertThat(article(run, "/dossier.pdf").outcome()).isEqualTo(ArticleIngestOutcome.SKIPPED);
        assertThat(article(run, "/paywall").outcome()).isEqualTo(ArticleIngestOutcome.SKIPPED);
        assertThat(article(run, "/paywall").reason()).isEqualTo("no extractable content");

        // The broken feed reports itself and costs the run nothing.
        FeedIngestResult broken = feed(run, "/broken.xml");
        assertThat(broken.fetchOutcome()).isEqualTo(FeedFetchOutcome.FAILED);
        assertThat(broken.articles()).isEmpty();
        assertThat(broken.failureReason()).isNotNull();

        assertThat(documents.findAll())
                .extracting(Document::getCanonicalUrl)
                .containsExactlyInAnyOrder(canonical("/steuerreform"), canonical("/leitartikel"));
        Document document = documents.findByCanonicalUrl(canonical("/steuerreform")).orElseThrow();
        assertThat(document.getFeedId()).isEqualTo(feeds.findByUrl(url("/press.xml")).orElseThrow().getId());
        assertThat(document.getVersionCount()).isZero();

        // Scope boundary: orchestration establishes identity, the version store does not exist yet.
        assertThat(versions.count()).isZero();
    }

    @Test
    void aSecondRunRecognisesTheDocumentItAlreadyKnows() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestRun first = ingestService.runOnce();
        assertThat(first.count(ArticleIngestOutcome.NEW)).isEqualTo(1);

        IngestRun second = ingestService.runOnce();

        assertThat(second.catalog().added()).isZero();
        assertThat(second.count(ArticleIngestOutcome.NEW)).isZero();
        assertThat(second.count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(1);
        assertThat(documents.count()).isEqualTo(1);

        Document document = documents.findByCanonicalUrl(canonical("/steuerreform")).orElseThrow();
        assertThat(document.getLastCheckedAt()).isAfterOrEqualTo(document.getFirstSeenAt());
    }

    @Test
    void aLinkAdvertisedByTwoFeedsIsFetchedOnce() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire", "/steuerreform"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestRun run = ingestService.runOnce();

        assertThat(run.checked()).isEqualTo(2);
        assertThat(run.count(ArticleIngestOutcome.NEW)).isEqualTo(1);
        assertThat(run.count(ArticleIngestOutcome.SKIPPED)).isEqualTo(1);
        assertThat(article(run, "/steuerreform").reason()).isNull();
        assertThat(documents.count()).isEqualTo(1);
        server.verify(1, getRequestedFor(urlEqualTo("/steuerreform")));
    }

    @Test
    void robotsTxtRefusalIsSkippedNotFailed() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain",
                "User-agent: quietedit\nDisallow: /steuerreform\n")));
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));

        IngestRun run = ingestService.runOnce();

        assertThat(run.count(ArticleIngestOutcome.SKIPPED)).isEqualTo(1);
        assertThat(run.count(ArticleIngestOutcome.FAILED)).isZero();
        assertThat(article(run, "/steuerreform").reason()).contains("robots.txt");
        assertThat(documents.count()).isZero();
        server.verify(0, getRequestedFor(urlEqualTo("/steuerreform")));
    }

    @Test
    void aFeedBodyThatIsNotAFeedCostsOnlyThatFeed() {
        server.stubFor(get("/broken.xml").willReturn(ok("<html><body>Wartungsarbeiten</body></html>")));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestRun run = ingestService.runOnce();

        FeedIngestResult notAFeed = feed(run, "/broken.xml");
        assertThat(notAFeed.fetchOutcome()).isEqualTo(FeedFetchOutcome.FETCHED);
        assertThat(notAFeed.failureReason()).contains("not a feed");
        assertThat(notAFeed.articles()).isEmpty();
        assertThat(run.count(ArticleIngestOutcome.NEW)).isEqualTo(1);
    }

    /**
     * Three strikes, end to end: a link that never yields a document is tried three
     * times and then stops being a candidate, while the real article next to it in the
     * same feed is fetched by every run throughout.
     *
     * <p>The article ceiling is the default here and never in reach, so this is the
     * abandonment rule on its own -- which is the case that would otherwise re-fetch a
     * permanently broken link on every single poll forever.
     */
    @Test
    void aLinkThatNeverYieldsADocumentIsAbandonedAfterThreeAttempts() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/verschwunden", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/verschwunden").willReturn(aResponse().withStatus(404)));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        for (int run = 1; run <= 3; run++) {
            IngestRun ingest = ingestService.runOnce();

            assertThat(article(ingest, "/verschwunden").outcome())
                    .as("attempt %d is still made", run).isEqualTo(ArticleIngestOutcome.FAILED);
            assertThat(attempts.findById(canonical("/verschwunden")).orElseThrow().getFailureCount())
                    .isEqualTo(run);
        }

        IngestRun fourth = ingestService.runOnce();

        ArticleIngestResult abandoned = article(fourth, "/verschwunden");
        assertThat(abandoned.outcome()).isEqualTo(ArticleIngestOutcome.ABANDONED);
        assertThat(abandoned.reason()).contains("3 consecutive failed attempts");
        assertThat(fourth.count(ArticleIngestOutcome.ABANDONED)).isEqualTo(1);
        // Never asked for a fifth time, and the strike count does not keep growing.
        server.verify(3, getRequestedFor(urlEqualTo("/verschwunden")));
        assertThat(attempts.findById(canonical("/verschwunden")).orElseThrow().getFailureCount()).isEqualTo(3);

        // The article behind it was reached by every one of the four runs.
        assertThat(fourth.count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(1);
        server.verify(4, getRequestedFor(urlEqualTo("/steuerreform")));
        ArticleAttempt succeeded = attempts.findById(canonical("/steuerreform")).orElseThrow();
        assertThat(succeeded.getFailureCount()).isZero();
        assertThat(succeeded.getLastAttemptAt()).isNotNull();
    }

    /**
     * A refusal is a failure for the purposes of giving up: robots.txt saying no is
     * correct behaviour, but the link still produces nothing, so it must not keep the
     * front of the queue on that account.
     */
    @Test
    void aRobotsRefusalCountsAsAFailedAttempt() {
        server.resetAll();
        server.stubFor(get("/robots.txt").willReturn(okForContentType("text/plain",
                "User-agent: quietedit\nDisallow: /steuerreform\n")));
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));

        IngestRun run = ingestService.runOnce();

        assertThat(run.count(ArticleIngestOutcome.SKIPPED)).isEqualTo(1);
        assertThat(attempts.findById(canonical("/steuerreform")).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    /** The endpoint is the same run, so it only has to agree with it. */
    @Test
    void theEndpointReportsTheSameRun() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestController.RunResponse response = controller.run();

        assertThat(response.catalog().listed()).isEqualTo(3);
        assertThat(response.feedSummary().polled()).isEqualTo(3);
        assertThat(response.feedSummary().failed()).isEqualTo(1);
        assertThat(response.articleSummary().checked()).isEqualTo(1);
        assertThat(response.articleSummary().created()).isEqualTo(1);
        assertThat(response.feeds()).hasSize(3);
        assertThat(documents.count()).isEqualTo(1);
    }

    private static ArticleIngestResult article(IngestRun run, String path) {
        return run.articles().stream()
                .filter(result -> result.link().equals(url(path)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no article result for " + path));
    }

    private static FeedIngestResult feed(IngestRun run, String path) {
        return run.feeds().stream()
                .filter(result -> result.url().equals(url(path)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no feed result for " + path));
    }

    private static String url(String path) {
        return server.baseUrl() + path;
    }

    /** What {@code UrlCanonicalizer} makes of a WireMock URL: https, port preserved. */
    private static String canonical(String path) {
        return "https://localhost:" + server.port() + path;
    }

    private static String feedBody(String title, String... paths) {
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
        // The trailing item has no link at all: the parser must drop it and keep the rest.
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>%s</title>
                  %s
                  <item><title>Ohne Link</title></item>
                </channel></rss>
                """.formatted(title, items);
    }

    private static ResponseDefinitionBuilder articlePage(String headline) {
        return okForContentType("text/html; charset=utf-8", """
                <!doctype html>
                <html lang="de"><head><title>%s</title></head>
                <body>
                  <nav><a href="/">Startseite</a></nav>
                  <article>
                    <h1>%s</h1>
                    <p>Der Bundestag hat am Montag nach langer Debatte einen Beschluss gefasst.</p>
                    <p>Die Opposition kritisierte das Vorhaben am Abend in scharfen Worten.</p>
                  </article>
                  <footer>Alle Rechte vorbehalten.</footer>
                </body></html>
                """.formatted(headline, headline));
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("quietedit-ingest-it");
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
