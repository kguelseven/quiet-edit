package org.korhan.quietedit.versioning;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * How one observation's bytes were turned into text: which charset won, which
 * declaration decided it, and whether the decode had to substitute replacement
 * characters because the bytes were not valid in that charset.
 *
 * <p>Lives in {@code versioning} rather than next to the resolver that produces it
 * because it is a property of the observation, not of the fetch: it is decided once
 * at fetch time, kept forever on the version, and read back by change
 * classification. Ingest already depends on this package, so the type sits at the
 * end of a dependency that exists anyway; the reverse placement would make
 * {@code versioning} depend on {@code ingest}.
 *
 * <p>{@code charset} is the canonical Java charset name, not the label the publisher
 * wrote. The label is what the page claimed -- {@code iso-8859-1} when the bytes are
 * windows-1252, a misspelling, an alias -- while the name is what the bytes were
 * actually run through, and only the latter explains the text that came out.
 *
 * <p>{@code replaced} is the load-bearing field and the reason this record exists.
 * It marks text that contains U+FFFD because the bytes contradicted the charset that
 * won: mojibake, which downstream is otherwise indistinguishable from prose. It
 * hashes as prose and versions as prose, so without this flag the day a publisher
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
     * True when the two verdicts disagree about whether the text was decodable at
     * all. That flip is the signal a repair (or a regression) leaves behind; the
     * charset name alone is not, because a page can move from windows-1252 to UTF-8
     * with both decodes clean and the text identical.
     */
    public boolean lossFlippedFrom(EncodingVerdict earlier) {
        return earlier != null && earlier.replaced != replaced;
    }

    /** Reads as prose in a warning or a rationale: {@code UTF-8 (HTTP Content-Type)}. */
    public String describe() {
        return "%s (%s)%s".formatted(charset, source.label(), replaced ? ", with replacement characters" : "");
    }
}
