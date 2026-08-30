package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;

/**
 * Turns a fetched body into text, using the charset the response declared.
 *
 * <p>Deliberately minimal: only the HTTP {@code Content-Type} is consulted, and
 * anything unreadable falls back to UTF-8. Weighing that declaration against a BOM
 * and against the document's own XML declaration or {@code meta charset} -- and
 * logging the contradictions instead of silently picking a winner -- is its own
 * decision with its own fixtures, and it is a separate ticket (encoding
 * resolution). This class exists so that orchestration has a defined, single place
 * to replace when that lands, rather than a {@code new String(..., UTF_8)} spread
 * across the run.
 *
 * <p>Decoding is lenient by construction: {@code new String} replaces malformed
 * input rather than throwing, because a page with one bad byte is still a page we
 * can monitor.
 */
final class ResponseText {

    private static final Logger log = LoggerFactory.getLogger(ResponseText.class);

    private ResponseText() {
    }

    static String decode(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, charset(contentType));
    }

    private static Charset charset(String contentType) {
        String declared = charsetParameter(contentType);
        if (declared == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(declared);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            log.debug("Unknown charset '{}', decoding as UTF-8", declared);
            return StandardCharsets.UTF_8;
        }
    }

    private static String charsetParameter(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String parameter = part.trim();
            if (parameter.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String value = parameter.substring("charset=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
