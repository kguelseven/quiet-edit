package org.korhan.quietedit.versioning;

/**
 * No diff can be produced for what was asked. Carries the reason as an enum rather
 * than only as prose so the web layer can map each case to its own problem type
 * without parsing a message.
 */
public class DiffUnavailableException extends RuntimeException {

    public enum Reason {

        /** No document with that id. */
        UNKNOWN_DOCUMENT("unknown-document"),

        /** The document exists but was never observed at that version number. */
        UNKNOWN_REVISION("unknown-revision"),

        /**
         * The document exists and has been observed once. There is nothing to compare
         * it against, which is the ordinary state of most documents rather than an
         * error in the request.
         */
        NO_REVISION_PAIR("no-revision-pair");

        private final String token;

        Reason(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    private final Reason reason;

    public DiffUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
