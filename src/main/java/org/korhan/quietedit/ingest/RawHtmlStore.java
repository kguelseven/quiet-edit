package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps raw article HTML on the filesystem and hands back the reference that goes into
 * {@code document_version.raw_html_ref}, because a news page is 100 KB to 2 MB of markup
 * and a version table carrying that stops being scannable.
 *
 * <p>The reference is the SHA-256 of the uncompressed bytes, sharded two levels deep, so
 * a re-check that finds an unchanged page costs no disk, a ref is verifiable by
 * re-hashing, and a write is idempotent because the store has no clock and no counter.
 *
 * <p>Over the uncompressed bytes, so that the ref does not depend on a compressor's
 * output being stable across JDK versions.
 *
 * <p>Writes go to a temporary file and are then moved into place, so a crash mid-write
 * cannot leave a truncated file under a name that promises its content.
 *
 * <p>Nothing here deletes: retention is out of scope and tracked separately.
 */
@Component
public class RawHtmlStore {

    private static final Logger log = LoggerFactory.getLogger(RawHtmlStore.class);

    private static final String SUFFIX = ".html.gz";
    private static final String TEMP_DIR = "tmp";

    private final Path root;

    public RawHtmlStore(ArticleFetchProperties properties) {
        this.root = properties.storageRoot().toAbsolutePath().normalize();
    }

    /** An identical body already in the store is left alone and its ref returned. */
    public String write(byte[] html) {
        String hash = sha256Hex(html);
        String ref = hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash + SUFFIX;
        Path target = resolve(ref);
        if (Files.exists(target)) {
            return ref;
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(tempDir(), hash, ".part");
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                out.write(html);
            }
            move(temp, target);
            return ref;
        } catch (IOException e) {
            throw new UncheckedIOException("could not store raw html " + ref, e);
        }
    }

    /** Reads back the uncompressed bytes of a stored reference. */
    public byte[] read(String ref) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(resolve(ref)))) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read raw html " + ref, e);
        }
    }

    /**
     * Rejects anything that would escape the store root: a ref is data that reached us from
     * a database row, and a traversing one must not be able to read arbitrary files.
     */
    public Path resolve(String ref) {
        Path resolved = root.resolve(ref).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("raw html ref escapes the store root: " + ref);
        }
        return resolved;
    }

    /**
     * Created under the store root rather than in the system temp directory, because the
     * temporary file must share a filesystem with the target for the move to be atomic.
     */
    private Path tempDir() throws IOException {
        return Files.createDirectories(root.resolve(TEMP_DIR));
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("atomic move unavailable for {}, falling back to a plain move", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Another thread stored the same body first; identical content, so this copy is dropped.
            Files.deleteIfExists(temp);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
