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
import org.korhan.quietedit.support.TestDatabase;
import org.korhan.quietedit.versioning.CharsetSource;
import org.korhan.quietedit.versioning.Document;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.korhan.quietedit.versioning.DocumentVersion;
import org.korhan.quietedit.versioning.DocumentVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
    private TestDatabase database;

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
        // The curve's floor, effectively removed: these tests poll the same article
        // several times a second, and every one of those looks has to happen. What the
        // curve actually decides is asserted in RecheckPolicyTest, over days rather
        // than milliseconds; what the tests below assert is that the run asks it at
        // all -- which the observation window, left at its default, is enough for.
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
        database.reset();
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
        assertThat(ingested.encoding()).isNotNull();
        assertThat(ingested.encoding().charset()).isEqualTo("UTF-8");
        assertThat(ingested.encoding().source()).isEqualTo(CharsetSource.HTTP_HEADER);
        assertThat(ingested.encoding().replaced()).isFalse();

        // Nothing was decoded for a link the run declined, so there is nothing to claim.
        assertThat(article(run, "/dossier.pdf").encoding()).isNull();

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
        assertThat(document.getVersionCount()).isEqualTo(1);
        // Nothing to differ from yet, so the first observation is not a change.
        assertThat(document.getLastChangedAt()).isNull();

        // Every usable article was versioned, and nothing else was.
        assertThat(versions.count()).isEqualTo(2);
        DocumentVersion version = versions
                .findByDocumentIdAndVersionNumber(document.getId(), 1).orElseThrow();
        assertThat(version.getPageTitle()).isEqualTo("Steuerreform beschlossen");
        assertThat(version.getParagraphs()).hasSize(2);
        assertThat(version.getContentHash()).hasSize(64);
        assertThat(version.getRawHtmlRef()).isEqualTo(ingested.rawHtmlRef());
        assertThat(version.getHttpStatus()).isEqualTo(200);
        assertThat(version.getEncoding()).isEqualTo(ingested.encoding());
        // The feed's pubDate reached the row as an instant in UTC, and as an exact one:
        // the publisher wrote a real offset, so nothing had to be assumed.
        assertThat(version.getPublishedAt()).isEqualTo(Instant.parse("2026-08-24T05:14:00Z"));
        assertThat(version.isPublishedAtExact()).isTrue();
        assertThat(ingested.versionId()).isEqualTo(version.getId());
        assertThat(ingested.versionNumber()).isEqualTo(1);
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
        assertThat(second.count(ArticleIngestOutcome.CHANGED)).isZero();
        assertThat(second.count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(1);
        assertThat(documents.count()).isEqualTo(1);

        Document document = documents.findByCanonicalUrl(canonical("/steuerreform")).orElseThrow();
        assertThat(document.getLastCheckedAt()).isAfterOrEqualTo(document.getFirstSeenAt());

        // The page did not move, so the second look added no revision and changed nothing.
        assertThat(versions.count()).isEqualTo(1);
        assertThat(document.getVersionCount()).isEqualTo(1);
        assertThat(document.getLastChangedAt()).isNull();
        assertThat(article(second, "/steuerreform").versionNumber()).isEqualTo(1);
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

    /**
     * The verdict has to leave the resolver. A page whose bytes contradict its declared
     * charset is decoded with replacement characters, and the run has to say so on the
     * result -- otherwise the mojibake reaches the version store as prose and the day
     * the publisher fixes the header the whole article reads as rewritten.
     */
    @Test
    void anArticleDecodedWithReplacementCharactersSaysSoOnTheResult() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/mojibake", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/mojibake").willReturn(latin1PageDeclaringUtf8()));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestRun run = ingestService.runOnce();

        ArticleIngestResult mojibake = article(run, "/mojibake");
        assertThat(mojibake.outcome()).isEqualTo(ArticleIngestOutcome.NEW);
        assertThat(mojibake.encoding().charset()).isEqualTo("UTF-8");
        assertThat(mojibake.encoding().source()).isEqualTo(CharsetSource.DOCUMENT);
        assertThat(mojibake.encoding().replaced()).isTrue();

        // The article next to it in the same feed is untouched by the neighbour's problem.
        assertThat(article(run, "/steuerreform").encoding().replaced()).isFalse();

        // Readable over REST too, because deciding a page needs a look is a human's job.
        IngestController.ArticleItem item = IngestController.ArticleItem.of(mojibake);
        assertThat(item.encoding()).isEqualTo("UTF-8 (document declaration), with replacement characters");
    }

    /**
     * The whole point of the system, end to end: an article whose text moves between
     * two runs gains a revision, and both revisions stay readable in full.
     */
    @Test
    void anEditedArticleGainsARevisionAndKeepsTheOldOne() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        IngestRun first = ingestService.runOnce();
        assertThat(first.count(ArticleIngestOutcome.NEW)).isEqualTo(1);

        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform gescheitert")));
        IngestRun second = ingestService.runOnce();

        assertThat(second.count(ArticleIngestOutcome.CHANGED)).isEqualTo(1);
        assertThat(second.count(ArticleIngestOutcome.UNCHANGED)).isZero();
        assertThat(second.count(ArticleIngestOutcome.NEW)).isZero();
        assertThat(article(second, "/steuerreform").versionNumber()).isEqualTo(2);

        Document document = documents.findByCanonicalUrl(canonical("/steuerreform")).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(2);
        assertThat(document.getLastChangedAt()).isNotNull();

        // Stored in full, not as a delta: the first headline is still there to be read.
        List<DocumentVersion> history = versions.findByDocumentIdOrderByVersionNumberAsc(document.getId());
        assertThat(history).extracting(DocumentVersion::getPageTitle)
                .containsExactly("Steuerreform beschlossen", "Steuerreform gescheitert");
        assertThat(history.getFirst().getContentHash()).isNotEqualTo(history.getLast().getContentHash());
        assertThat(history.getFirst().getParagraphs()).hasSize(2);
    }

    /**
     * A, B, A end to end: a headline that is changed and then changed back leaves three
     * revisions, the third repeating the first one's content hash. This is the case the
     * store could not record until Flyway V5, and it is the one an editor is most
     * likely to produce -- publish, correct, revert after the correction is disputed.
     */
    @Test
    void anArticleThatReturnsToItsEarlierWordingGainsAThirdRevision() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));

        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));
        ingestService.runOnce();

        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform gescheitert")));
        ingestService.runOnce();

        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));
        IngestRun third = ingestService.runOnce();

        // Back to the first wording is a change like any other, not "unchanged".
        assertThat(third.count(ArticleIngestOutcome.CHANGED)).isEqualTo(1);
        assertThat(third.count(ArticleIngestOutcome.UNCHANGED)).isZero();
        assertThat(article(third, "/steuerreform").versionNumber()).isEqualTo(3);
        assertThat(article(third, "/steuerreform").reason()).isNull();

        Document document = documents.findByCanonicalUrl(canonical("/steuerreform")).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(3);

        List<DocumentVersion> history = versions.findByDocumentIdOrderByVersionNumberAsc(document.getId());
        assertThat(history).extracting(DocumentVersion::getPageTitle).containsExactly(
                "Steuerreform beschlossen", "Steuerreform gescheitert", "Steuerreform beschlossen");
        assertThat(history.getLast().getContentHash()).isEqualTo(history.getFirst().getContentHash());
        assertThat(history.getLast().getId()).isNotEqualTo(history.getFirst().getId());

        // A fourth look at the unchanged page still adds nothing.
        IngestRun fourth = ingestService.runOnce();
        assertThat(fourth.count(ArticleIngestOutcome.UNCHANGED)).isEqualTo(1);
        assertThat(versions.count()).isEqualTo(3);
    }

    /**
     * The re-check policy, reached through a run: an article nothing has been observed
     * to do for a month is not fetched again, and the run says why rather than staying
     * silent about a link it declined.
     *
     * <p>The document is back-dated rather than the clock advanced, which is the same
     * state from the policy's point of view and needs no clock of its own. Driving this
     * test from a fixed clock is tracked separately.
     */
    @Test
    void aFeedLinkWhoseArticleHasBeenStableForAMonthIsNotFetchedAgain() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        assertThat(ingestService.runOnce().count(ArticleIngestOutcome.NEW)).isEqualTo(1);
        server.verify(1, getRequestedFor(urlEqualTo("/steuerreform")));
        retireDocument(canonical("/steuerreform"));

        IngestRun second = ingestService.runOnce();

        ArticleIngestResult declined = article(second, "/steuerreform");
        assertThat(declined.outcome()).isEqualTo(ArticleIngestOutcome.NOT_DUE);
        assertThat(declined.reason()).contains("retired");
        assertThat(second.count(ArticleIngestOutcome.NOT_DUE)).isEqualTo(1);
        // The feed advertised it and the run still spent nothing on it.
        server.verify(1, getRequestedFor(urlEqualTo("/steuerreform")));
        assertThat(versions.count()).isEqualTo(1);
    }

    /**
     * The one claim a publisher makes that this system acts on: a feed saying the entry
     * was updated after the last look brings even a retired article back for one fetch.
     * What changed is still decided by comparing the text -- here nothing did, so the
     * look costs a request and adds no revision.
     */
    @Test
    void anUpdatedDateInTheFeedBringsARetiredArticleBackForOneLook() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press", "/steuerreform"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));
        server.stubFor(get("/steuerreform").willReturn(articlePage("Steuerreform beschlossen")));

        ingestService.runOnce();
        retireDocument(canonical("/steuerreform"));

        server.stubFor(get("/press.xml").willReturn(ok(feedBodyClaimingAnUpdate(
                "Press", "/steuerreform", Instant.now().minus(Duration.ofDays(1))))));
        IngestRun second = ingestService.runOnce();

        assertThat(article(second, "/steuerreform").outcome()).isEqualTo(ArticleIngestOutcome.UNCHANGED);
        server.verify(2, getRequestedFor(urlEqualTo("/steuerreform")));
        assertThat(versions.count()).isEqualTo(1);
    }

    /**
     * The half of the policy a feed cannot supply. A publisher's feed carries about a
     * day of articles, so without offering the store's own documents the curve would
     * stop being applied exactly where a feed drops an entry -- days before this system
     * stops caring. Two documents no feed mentions: one three days old, one a month
     * old. Only the first is still worth a request.
     *
     * <p>The three-day-old candidate is asserted as a candidate rather than as a
     * fetched article: a re-check requests the document's <em>canonical</em> URL, which
     * {@code UrlCanonicalizer} has upgraded to https, and WireMock here serves plain
     * http. What the test is for is which documents the run offers the policy, and that
     * is decided before anything is fetched.
     */
    @Test
    void documentsNoFeedAdvertisesAnyMoreAreOfferedUntilTheirWindowCloses() {
        server.stubFor(get("/broken.xml").willReturn(aResponse().withStatus(500)));
        server.stubFor(get("/press.xml").willReturn(ok(feedBody("Press"))));
        server.stubFor(get("/wire.xml").willReturn(ok(feedBody("Wire"))));

        // One run to seed the catalogue, so the seeded documents have a feed to belong to.
        ingestService.runOnce();
        UUID feedId = feeds.findByUrl(url("/press.xml")).orElseThrow().getId();
        Instant now = Instant.now();
        seedDocument(canonical("/vorwoche"), feedId, now.minus(Duration.ofDays(3)));
        seedDocument(canonical("/archiv"), feedId, now.minus(Duration.ofDays(30)));

        IngestRun run = ingestService.runOnce();

        assertThat(run.rechecks()).extracting(ArticleIngestResult::link)
                .containsExactly(canonical("/vorwoche"));
        // No feed carried either link, so neither shows up in a feed's answer.
        assertThat(run.feeds()).allSatisfy(feed -> assertThat(feed.articles()).isEmpty());
        server.verify(0, getRequestedFor(urlEqualTo("/archiv")));
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
        assertThat(response.articleSummary().changed()).isZero();
        assertThat(response.feeds()).hasSize(3);
        assertThat(documents.count()).isEqualTo(1);
    }

    /**
     * Back-dates a document past its observation window. The same state the policy
     * would see after a month of quiet, without a month of waiting.
     */
    private void retireDocument(String canonicalUrl) {
        Document document = documents.findByCanonicalUrl(canonicalUrl).orElseThrow();
        Instant longAgo = document.getFirstSeenAt().minus(Duration.ofDays(30));
        document.setFirstSeenAt(longAgo);
        document.setLastCheckedAt(longAgo);
        documents.save(document);
    }

    /** A document this system knows and no feed mentions, discovered and last looked at then. */
    private void seedDocument(String canonicalUrl, UUID feedId, Instant discoveredAt) {
        documents.save(new Document(canonicalUrl, feedId, discoveredAt, discoveredAt));
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

    /** One entry, with the {@code updated} field the re-check policy reads. */
    private static String feedBodyClaimingAnUpdate(String title, String path, Instant updatedAt) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>%s</title>
                  <item>
                    <title>%s</title>
                    <link>%s</link>
                    <pubDate>Mon, 24 Aug 2026 07:14:00 +0200</pubDate>
                    <updated>%s</updated>
                  </item>
                </channel></rss>
                """.formatted(title, path, url(path), DateTimeFormatter.ISO_INSTANT.format(updatedAt));
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

    /**
     * Latin-1 bytes under a {@code utf-8} declaration and no charset in the header, so
     * the document's own declaration wins and its own bytes refute it. Built as bytes
     * on purpose: a string would already have been decoded.
     */
    private static ResponseDefinitionBuilder latin1PageDeclaringUtf8() {
        byte[] body = """
                <!doctype html>
                <html lang="de"><head><meta charset="utf-8"><title>Grüße aus Köln</title></head>
                <body>
                  <article>
                    <h1>Grüße aus Köln</h1>
                    <p>Der Bürgermeister sprach über Straßenbahnen und Fußgänger am Rheinufer.</p>
                    <p>Die Opposition kritisierte das Vorhaben am Abend in scharfen Worten.</p>
                  </article>
                </body></html>
                """.getBytes(Charset.forName("windows-1252"));
        return aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body);
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
