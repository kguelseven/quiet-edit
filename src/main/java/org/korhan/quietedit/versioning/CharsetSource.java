package org.korhan.quietedit.versioning;

/**
 * Which of the competing declarations decided how an observation's bytes were read,
 * in descending order of trust. Persisted by name on a version, so the order of the
 * constants carries no meaning beyond documentation.
 *
 * <p>Recorded alongside the charset because the two answer different questions. The
 * charset says what the bytes were run through; the source says how much that is
 * worth. {@code DEFAULT} in particular means nobody declared anything usable and
 * UTF-8 was assumed -- a very different level of confidence from a byte order mark,
 * and the difference matters when a later observation disagrees.
 */
public enum CharsetSource {

    /** The bytes' own mark. Cannot be a transport-layer guess. */
    BOM("byte order mark"),

    /** The {@code charset} parameter of the response's {@code Content-Type}. */
    HTTP_HEADER("HTTP Content-Type"),

    /** An XML declaration, a {@code meta charset} or a {@code meta http-equiv}. */
    DOCUMENT("document declaration"),

    /** Nothing usable was declared and UTF-8 was assumed. */
    DEFAULT("default");

    private final String label;

    CharsetSource(String label) {
        this.label = label;
    }

    /** The wording used in warnings and rationales, so both read as prose. */
    public String label() {
        return label;
    }
}
