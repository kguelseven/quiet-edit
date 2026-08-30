package org.korhan.quietedit.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.korhan.quietedit.PostgresTestContainerConfig;
import org.korhan.quietedit.analysis.Change;
import org.korhan.quietedit.analysis.ChangeRepository;
import org.korhan.quietedit.analysis.Classification;
import org.korhan.quietedit.ingest.Feed;
import org.korhan.quietedit.ingest.FeedRepository;
import org.korhan.quietedit.versioning.CharsetSource;
import org.korhan.quietedit.versioning.Document;
import org.korhan.quietedit.versioning.DocumentRepository;
import org.korhan.quietedit.versioning.DocumentVersion;
import org.korhan.quietedit.versioning.DocumentVersionRepository;
import org.korhan.quietedit.versioning.EncodingVerdict;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round-trips one instance of every foundation entity. This is the check that the
 * Flyway schema and the JPA mappings actually agree: {@code ddl-auto=validate}
 * catches missing columns, but only a real insert/read proves that jsonb, char(64)
 * and timestamptz map to the Java types the entities declare.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class SchemaSmokeTest {

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentVersionRepository versions;

    @Autowired
    private ChangeRepository changes;

    @Test
    void persistsAndReadsBackOneOfEachEntity() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Feed feed = new Feed("https://example.test/rss.xml", "Example");
        feed.setEtag("\"abc123\"");
        feed.setLastModified("Wed, 21 Oct 2015 07:28:00 GMT");
        feed.setLastPolledAt(now);
        feed.setLastStatus(200);
        UUID feedId = feeds.saveAndFlush(feed).getId();

        Document document = new Document("https://example.test/article", feedId, now, now);
        document.setVersionCount(2);
        UUID documentId = documents.saveAndFlush(document).getId();

        DocumentVersion first = newVersion(documentId, now, "a".repeat(64));
        DocumentVersion second = newVersion(documentId, now, "b".repeat(64));
        second.setEncoding(new EncodingVerdict("UTF-8", CharsetSource.HTTP_HEADER, true));
        UUID firstId = versions.saveAndFlush(first).getId();
        UUID secondId = versions.saveAndFlush(second).getId();

        Change change = new Change(
                documentId,
                firstId,
                secondId,
                now,
                Classification.SUBSTANTIVE,
                "Second paragraph replaced.",
                Map.of("changedParagraphs", 1));
        change.setTitleChanged(true);
        UUID changeId = changes.saveAndFlush(change).getId();

        Feed readFeed = feeds.findById(feedId).orElseThrow();
        assertThat(readFeed.getUrl()).isEqualTo("https://example.test/rss.xml");
        assertThat(readFeed.getLastModified()).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        assertThat(readFeed.getLastPolledAt()).isEqualTo(now);
        assertThat(readFeed.isActive()).isTrue();

        Document readDocument = documents.findById(documentId).orElseThrow();
        assertThat(readDocument.getCanonicalUrl()).isEqualTo("https://example.test/article");
        assertThat(readDocument.getFeedId()).isEqualTo(feedId);
        assertThat(readDocument.getFirstSeenAt()).isEqualTo(now);
        assertThat(readDocument.getLastChangedAt()).isNull();
        assertThat(readDocument.getVersionCount()).isEqualTo(2);

        DocumentVersion readVersion = versions.findById(secondId).orElseThrow();
        assertThat(readVersion.getDocumentId()).isEqualTo(documentId);
        assertThat(readVersion.getParagraphs()).containsExactly("First paragraph.", "Second paragraph.");
        assertThat(readVersion.getContentHash()).isEqualTo("b".repeat(64));
        assertThat(readVersion.getSimhash()).isEqualTo(42L);
        assertThat(readVersion.isPublishedAtExact()).isTrue();
        assertThat(readVersion.getHttpStatus()).isEqualTo(200);
        assertThat(readVersion.getEncoding())
                .isEqualTo(new EncodingVerdict("UTF-8", CharsetSource.HTTP_HEADER, true));

        // A version that recorded no verdict has to read back as "unknown", not as a
        // clean decode: getEncoding() is null while the flag column keeps its default.
        assertThat(versions.findById(firstId).orElseThrow().getEncoding()).isNull();

        Change readChange = changes.findById(changeId).orElseThrow();
        assertThat(readChange.getFromVersionId()).isEqualTo(firstId);
        assertThat(readChange.getToVersionId()).isEqualTo(secondId);
        assertThat(readChange.getClassification()).isEqualTo(Classification.SUBSTANTIVE);
        assertThat(readChange.isTitleChanged()).isTrue();
        assertThat(readChange.getRationale()).isEqualTo("Second paragraph replaced.");
        assertThat(readChange.getDiffPayload()).containsEntry("changedParagraphs", 1);
    }

    private static DocumentVersion newVersion(UUID documentId, Instant fetchedAt, String contentHash) {
        DocumentVersion version = new DocumentVersion(
                documentId,
                fetchedAt,
                List.of("First paragraph.", "Second paragraph."),
                contentHash,
                200);
        version.setPublishedAt(fetchedAt);
        version.setFeedTitle("Headline in the feed");
        version.setPageTitle("Headline on the page");
        version.setSimhash(42L);
        version.setRawHtmlRef("raw/2026/08/23/" + contentHash + ".html");
        return version;
    }
}
