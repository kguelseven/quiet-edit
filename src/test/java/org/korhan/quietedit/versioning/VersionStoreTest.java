package org.korhan.quietedit.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.PostgresTestContainerConfig;
import org.korhan.quietedit.ingest.ArticleContent;
import org.korhan.quietedit.ingest.Feed;
import org.korhan.quietedit.ingest.FeedRepository;
import org.korhan.quietedit.support.TestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The version store against a real database, because its two load-bearing
 * properties are the database's: the append-only guarantee is enforced by triggers,
 * and "counters and version in one transaction" is only a claim until a transaction
 * actually commits.
 *
 * <p>Timestamps are truncated to microseconds throughout -- that is what
 * {@code timestamptz} stores, and an assertion on a nanosecond that Postgres never
 * kept would fail for a reason that has nothing to do with versioning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class VersionStoreTest {

    private static final Instant T0 = Instant.parse("2026-08-20T08:00:00Z");

    @Autowired
    private VersionStore store;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestDatabase database;

    private UUID documentId;

    @BeforeEach
    void reset() {
        database.reset();
        UUID feedId = feeds.save(new Feed("https://example.test/rss.xml", "Example")).getId();
        documentId = documents.save(
                new Document("https://example.test/artikel", feedId, T0, T0)).getId();
    }

    @Test
    void theFirstObservationBecomesVersionOneAndIsNotYetAChange() {
        VersionStore.Stored stored = store.record(documentId, observation(T0, "Erste Fassung."));

        assertThat(stored.outcome()).isEqualTo(VersionOutcome.APPENDED);
        assertThat(stored.versionNumber()).isEqualTo(1);
        assertThat(stored.previousVersionId()).isNull();
        assertThat(stored.contentHash()).hasSize(64);

        Document document = documents.findById(documentId).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(1);
        // Nothing to differ from, so nothing changed.
        assertThat(document.getLastChangedAt()).isNull();

        DocumentVersion version = store.version(documentId, 1).orElseThrow();
        assertThat(version.getId()).isEqualTo(stored.versionId());
        assertThat(version.getPageTitle()).isEqualTo("Kopfzeile");
        assertThat(version.getParagraphs()).containsExactly("Erste Fassung.");
        assertThat(version.getFeedTitle()).isEqualTo("Wie der Feed es nannte");
        assertThat(version.getRawHtmlRef()).isEqualTo("raw/artikel.html");
        assertThat(version.getHttpStatus()).isEqualTo(200);
        assertThat(version.getEncoding())
                .isEqualTo(new EncodingVerdict("UTF-8", CharsetSource.HTTP_HEADER, false));
    }

    /** The common case by far: a re-check of text that did not move writes nothing. */
    @Test
    void unchangedTextAppendsNothingAndLeavesTheCountersAlone() {
        VersionStore.Stored first = store.record(documentId, observation(T0, "Erste Fassung."));

        VersionStore.Stored second = store.record(
                documentId, observation(T0.plus(1, ChronoUnit.HOURS), "Erste Fassung."));

        assertThat(second.outcome()).isEqualTo(VersionOutcome.UNCHANGED);
        assertThat(second.versionId()).isEqualTo(first.versionId());
        assertThat(second.versionNumber()).isEqualTo(1);

        assertThat(store.history(documentId)).hasSize(1);
        Document document = documents.findById(documentId).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(1);
        assertThat(document.getLastChangedAt()).isNull();
    }

    /**
     * Insensitivity is the hasher's, not the store's, but the store is where it has
     * to hold: a changed ad identifier must not mint a revision.
     */
    @Test
    void textThatOnlyTheAdIdentifierDistinguishesIsNotANewVersion() {
        store.record(documentId, observation(T0, "Anzeige div-gpt-ad-1712345678901-0 im Text."));

        VersionStore.Stored second = store.record(
                documentId, observation(T0.plusSeconds(60), "Anzeige div-gpt-ad-1799999999999-3 im Text."));

        assertThat(second.outcome()).isEqualTo(VersionOutcome.UNCHANGED);
        assertThat(store.history(documentId)).hasSize(1);
    }

    @Test
    void changedTextAppendsARevisionAndMarksTheDocumentChanged() {
        VersionStore.Stored first = store.record(documentId, observation(T0, "Erste Fassung."));
        Instant later = T0.plus(2, ChronoUnit.HOURS);

        VersionStore.Stored second = store.record(documentId, observation(later, "Zweite Fassung."));

        assertThat(second.outcome()).isEqualTo(VersionOutcome.APPENDED);
        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(second.previousVersionId()).isEqualTo(first.versionId());

        Document document = documents.findById(documentId).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(2);
        assertThat(document.getLastChangedAt()).isEqualTo(later);

        // Full, not a delta: the superseded text is still there in its entirety.
        assertThat(store.history(documentId))
                .extracting(version -> version.getParagraphs().getFirst())
                .containsExactly("Erste Fassung.", "Zweite Fassung.");
    }

    /** Any two revisions, not just adjacent ones, are readable in full and side by side. */
    @Test
    void anyTwoRevisionsAreComparable() {
        store.record(documentId, observation(T0, "Fassung eins."));
        store.record(documentId, observation(T0.plusSeconds(3600), "Fassung zwei."));
        store.record(documentId, observation(T0.plusSeconds(7200), "Fassung drei."));

        DocumentVersion first = store.version(documentId, 1).orElseThrow();
        DocumentVersion third = store.version(documentId, 3).orElseThrow();

        assertThat(first.getParagraphs()).containsExactly("Fassung eins.");
        assertThat(third.getParagraphs()).containsExactly("Fassung drei.");
        assertThat(first.getContentHash()).isNotEqualTo(third.getContentHash());

        assertThat(store.history(documentId))
                .extracting(DocumentVersion::getVersionNumber)
                .containsExactly(1, 2, 3);
        assertThat(store.latest(documentId).orElseThrow().getVersionNumber()).isEqualTo(3);
        assertThat(store.version(documentId, 4)).isEmpty();
    }

    @Test
    void readsBackTheRevisionThatWasCurrentAtAGivenInstant() {
        store.record(documentId, observation(T0, "Fassung eins."));
        store.record(documentId, observation(T0.plusSeconds(3600), "Fassung zwei."));
        store.record(documentId, observation(T0.plusSeconds(7200), "Fassung drei."));

        assertThat(store.asOf(documentId, T0.minusSeconds(1))).isEmpty();
        assertThat(store.asOf(documentId, T0).orElseThrow().getVersionNumber()).isEqualTo(1);
        assertThat(store.asOf(documentId, T0.plusSeconds(3599)).orElseThrow().getVersionNumber()).isEqualTo(1);
        assertThat(store.asOf(documentId, T0.plusSeconds(3600)).orElseThrow().getVersionNumber()).isEqualTo(2);
        assertThat(store.asOf(documentId, T0.plusSeconds(999_999)).orElseThrow().getVersionNumber())
                .isEqualTo(3);
    }

    /**
     * The append-only guarantee, at the level that actually holds it. Not "the service
     * never calls update" -- the table refuses.
     */
    @Test
    void theDatabaseRefusesToOverwriteOrDeleteAVersion() {
        VersionStore.Stored stored = store.record(documentId, observation(T0, "Erste Fassung."));

        assertThatThrownBy(() -> jdbc.update(
                "update document_version set page_title = 'umgeschrieben' where id = ?", stored.versionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "delete from document_version where id = ?", stored.versionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        DocumentVersion version = store.version(documentId, 1).orElseThrow();
        assertThat(version.getPageTitle()).isEqualTo("Kopfzeile");
        assertThat(store.history(documentId)).hasSize(1);
    }

    /**
     * The known gap, asserted so it stays a decision rather than a surprise: an
     * article that goes back to a wording it already had cannot be recorded while
     * {@code (document_id, content_hash)} is unique. Reported, never silently
     * swallowed and never a lost transaction -- see quietedit-cca.2.
     */
    @Test
    void textThatReturnsToAnEarlierRevisionIsReportedRatherThanStored() {
        store.record(documentId, observation(T0, "Fassung eins."));
        store.record(documentId, observation(T0.plusSeconds(3600), "Fassung zwei."));

        VersionStore.Stored back = store.record(
                documentId, observation(T0.plusSeconds(7200), "Fassung eins."));

        assertThat(back.outcome()).isEqualTo(VersionOutcome.REVERTED);
        assertThat(store.history(documentId)).hasSize(2);
        Document document = documents.findById(documentId).orElseThrow();
        assertThat(document.getVersionCount()).isEqualTo(2);
        assertThat(document.getLastChangedAt()).isEqualTo(T0.plusSeconds(3600));
    }

    private static Observation observation(Instant fetchedAt, String paragraph) {
        return Observation.of(
                fetchedAt,
                new ArticleContent("Kopfzeile", List.of(paragraph)),
                200,
                "Wie der Feed es nannte",
                "raw/artikel.html",
                new EncodingVerdict("UTF-8", CharsetSource.HTTP_HEADER, false));
    }
}
