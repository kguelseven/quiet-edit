package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The store's two promises: what goes in comes back out byte for byte, and the same
 * bytes always land under the same name. The second one is what keeps re-checks from
 * filling a disk with copies of an unchanged page.
 */
class RawHtmlStoreTest {

    private static final byte[] HTML = "<html><body><p>Ein Satz.</p></body></html>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    @Test
    void writtenHtmlReadsBackUnchanged() {
        RawHtmlStore store = store();

        String ref = store.write(HTML);

        assertThat(store.read(ref)).isEqualTo(HTML);
    }

    @Test
    void nonUtf8BytesSurviveTheRoundTrip() {
        // Byte-oriented: a store that decoded would destroy what encoding resolution needs.
        byte[] latin1 = "<p>Gruesse</p>".getBytes(StandardCharsets.ISO_8859_1);
        RawHtmlStore store = store();

        assertThat(store.read(store.write(latin1))).isEqualTo(latin1);
    }

    @Test
    void theSameBodyYieldsTheSameRefAndOnlyOneFile() throws IOException {
        RawHtmlStore store = store();

        String first = store.write(HTML);
        String second = store.write(HTML);

        assertThat(second).isEqualTo(first);
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void differentBodiesYieldDifferentRefs() {
        RawHtmlStore store = store();

        assertThat(store.write(HTML))
                .isNotEqualTo(store.write("<html>other</html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRefIsTheShardedSha256OfTheUncompressedBytes() {
        String hash = RawHtmlStore.sha256Hex(HTML);

        String ref = store().write(HTML);

        assertThat(ref).isEqualTo(hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash + ".html.gz");
    }

    @Test
    void theStoredFileIsCompressedAndSmallerThanTheSource() throws IOException {
        byte[] repetitive = "<p>Immer derselbe Satz.</p>".repeat(500).getBytes(StandardCharsets.UTF_8);
        RawHtmlStore store = store();

        String ref = store.write(repetitive);

        assertThat(Files.size(store.resolve(ref))).isLessThan(repetitive.length);
        assertThat(store.read(ref)).isEqualTo(repetitive);
    }

    @Test
    void concurrentWritesOfTheSameBodyProduceOneFile() throws Exception {
        RawHtmlStore store = store();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> refs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                refs.add(executor.submit(() -> store.write(HTML)));
            }
            for (Future<String> ref : refs) {
                assertThat(ref.get()).isEqualTo(refs.getFirst().get());
            }
        }
        assertThat(storedFiles()).hasSize(1);
        assertThat(store.read(store.write(HTML))).isEqualTo(HTML);
    }

    @Test
    void aRefThatEscapesTheRootIsRejected() {
        assertThatThrownBy(() -> store().resolve("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the store root");
    }

    /** Files under the shard directories, i.e. excluding the temp directory. */
    private List<Path> storedFiles() throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getParent().getFileName().toString().equals("tmp"))
                    .toList();
        }
    }

    private RawHtmlStore store() {
        return new RawHtmlStore(new ArticleFetchProperties(
                5, DataSize.ofMegabytes(8), Duration.ofHours(1), Duration.ofMinutes(5),
                Duration.ofSeconds(30), root));
    }
}
