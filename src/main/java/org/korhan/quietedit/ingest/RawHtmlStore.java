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
 * Keeps raw article HTML on the filesystem and hands back the reference that goes
 * into {@code document_version.raw_html_ref}. The database never sees the markup:
 * a news page is 100 KB to 2 MB of it, and a version table that carried that would
 * stop being scannable within weeks.
 *
 * <p>The reference is content-addressed -- the SHA-256 of the uncompressed bytes,
 * sharded two levels deep so no directory grows past a few thousand entries. Three
 * things follow from that, all of them wanted:
 * <ul>
 *   <li>Writing the same HTML twice yields the same ref and stores one file, so a
 *       re-check that finds an unchanged page costs no disk.</li>
 *   <li>A ref is verifiable: re-hashing the file must reproduce its own name.</li>
 *   <li>The store has no clock and no counter in it, so a write is idempotent and
 *       replaying a run cannot produce a second copy under a new name.</li>
 * </ul>
 *
 * <p>Files are gzipped. HTML compresses to roughly a fifth, and this is the one
 * place in the system that grows without bound. The hash stays over the
 * <em>uncompressed</em> bytes so that it does not depend on a compressor's output
 * being stable across JDK versions.
 *
 * <p>Writes go to a temporary file and are then moved into place, so a crash
 * mid-write cannot leave a truncated file under a name that promises its content.
 * Nothing here deletes: retention is deliberately out of scope and tracked
 * separately.
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

    /**
     * Stores {@code html} and returns its reference, e.g.
     * {@code 3f/2a/3f2a...c1.html.gz}. An identical body already in the store is
     * left alone and its ref returned.
     */
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
     * Resolves a ref to its file, rejecting anything that would escape the store
     * root: a ref is data that reached us from a database row, and a traversing one
     * must not be able to read arbitrary files.
     */
    public Path resolve(String ref) {
        Path resolved = root.resolve(ref).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("raw html ref escapes the store root: " + ref);
        }
        return resolved;
    }

    /**
     * The temporary file must share a filesystem with the target for the move to be
     * atomic, which is why it is created under the store root rather than in the
     * system temp directory.
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
            // Another thread stored the same body first; identical content, so the
            // loser of the race just drops its copy.
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
