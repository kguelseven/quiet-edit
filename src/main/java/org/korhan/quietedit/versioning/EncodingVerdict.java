package org.korhan.quietedit.versioning;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * How one observation's bytes were turned into text: which charset won, which declaration
 * decided it, and whether the decode had to substitute replacement characters.
 *
 * <p>Lives in {@code versioning} because it is a property of the observation rather than
 * of the fetch, and the reverse placement would make {@code versioning} depend on
 * {@code ingest}.
 *
 * <p>{@code charset} is the canonical Java name, not the label the publisher wrote: only
 * what the bytes were actually run through explains the text that came out.
 *
 * <p>{@code replaced} is the load-bearing field and the reason this record exists. It
 * marks mojibake, which hashes and versions as prose, so without it the day a publisher
 * fixes their charset header the whole article reads as rewritten.
 */
public record EncodingVerdict(String charset, CharsetSource source, boolean replaced) {

    public EncodingVerdict {
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(source, "source");
    }

    public EncodingVerdict(Charset charset, CharsetSource source, boolean replaced) {
        this(charset.name(), source, replaced);
    }

    /**
     * That flip is the signal a repair or a regression leaves behind; the charset name is
     * not, because a page can move from windows-1252 to UTF-8 with both decodes clean.
     */
    public boolean lossFlippedFrom(EncodingVerdict earlier) {
        return earlier != null && earlier.replaced != replaced;
    }

    /** Reads as prose in a warning or a rationale: {@code UTF-8 (HTTP Content-Type)}. */
    public String describe() {
        return "%s (%s)%s".formatted(charset, source.label(), replaced ? ", with replacement characters" : "");
    }
}
