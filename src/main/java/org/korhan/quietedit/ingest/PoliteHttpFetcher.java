package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * One GET, walked hop by hop: rate limited per host, retried on 5xx and transport
 * failures, and never throwing at its caller.
 *
 * <p>Redirects are followed here rather than by {@link HttpClient} because the caller
 * needs the URL the chain ended at and a veto before each hop -- a redirect can cross
 * into a host or path robots.txt forbids, and a client-followed redirect would already
 * have sent that request.
 *
 * <p>The body is only read when {@link BodyGate} says it is worth reading, which is taken
 * from the headers with the stream still unread, so a 40 MB PDF costs the headers only.
 *
 * <p>The retry policy matches {@link FeedFetcher} but is still a separate implementation;
 * unifying the two loops is its own ticket.
 */
@Component
public class PoliteHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpFetcher.class);

    /** Redirects we follow. 300 and 305 are excluded: neither names a single target to follow. */
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final HttpClient articleHttpClient;
    private final FeedFetchProperties properties;
    private final HostRateLimiter rateLimiter;
    private final Sleeper sleeper;

    public PoliteHttpFetcher(HttpClient articleHttpClient, FeedFetchProperties properties,
                             HostRateLimiter rateLimiter, Sleeper sleeper) {
        this.articleHttpClient = articleHttpClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.sleeper = sleeper;
    }

    /**
     * @param maxBytes the largest body that may be read; a bigger one is a failure, not a
     *                 truncated success, because half an article would hash as a change
     *                 that never happened
     */
    public Trace follow(URI startUri, String accept, long maxBytes, int maxHops,
                        BodyGate bodyGate, HopGuard hopGuard) {
        List<URI> chain = new ArrayList<>();
        Set<URI> visited = new LinkedHashSet<>();
        URI current = startUri;

        for (int hop = 0; hop <= maxHops; hop++) {
            if (!visited.add(current)) {
                return Trace.failed(chain, "redirect loop at " + current);
            }
            chain.add(current);

            HopDecision decision = hopGuard.inspect(current);
            if (decision.refused()) {
                return Trace.refused(chain, decision.refusalReason());
            }

            Response response = attempt(current, accept, maxBytes, bodyGate, decision.minInterval());
            if (response.failed() || !REDIRECT_STATUSES.contains(response.statusCode())) {
                return Trace.completed(chain, response);
            }
            URI target = redirectTarget(current, response);
            if (target == null) {
                return Trace.failed(chain, "HTTP " + response.statusCode() + " without a usable Location");
            }
            current = target;
        }
        return Trace.failed(chain, "more than " + maxHops + " redirects");
    }

    private Response attempt(URI uri, String accept, long maxBytes, BodyGate bodyGate, Duration minInterval) {
        Duration backoff = properties.initialBackoff();
        String lastFailure = "no attempt made";

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            int attemptNumber = attempt;
            try {
                Response response = rateLimiter.call(uri.getHost(), minInterval,
                        () -> send(uri, accept, maxBytes, bodyGate, attemptNumber));
                if (response.failed() || response.statusCode() < 500) {
                    return response;
                }
                lastFailure = "HTTP " + response.statusCode();
                if (attempt == properties.maxAttempts()) {
                    return response;
                }
            } catch (HttpTimeoutException e) {
                lastFailure = "timeout after " + properties.requestTimeout();
            } catch (IOException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Response.failed("interrupted", attempt);
            }

            if (attempt < properties.maxAttempts()) {
                log.warn("{} attempt {}/{} failed ({}), retrying in {}",
                        uri, attempt, properties.maxAttempts(), lastFailure, backoff);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Response.failed("interrupted", attempt);
                }
                backoff = backoff.multipliedBy(properties.backoffMultiplier());
            }
        }
        return Response.failed(lastFailure, properties.maxAttempts());
    }

    /**
     * The stream is closed on every path, the refused-body one included: an unread,
     * unclosed response body would keep the connection out of the pool for the whole run.
     */
    private Response send(URI uri, String accept, long maxBytes, BodyGate bodyGate, int attempt)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", accept)
                // Explicit: java.net.http never decodes an encoding we did not ask for.
                .header("Accept-Encoding", "gzip, identity")
                .build();

        HttpResponse<InputStream> response = articleHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        HttpHeaders headers = response.headers();
        try (InputStream stream = response.body()) {
            if (!bodyGate.readBody(status, headers)) {
                return Response.bodySkipped(status, headers, attempt);
            }
            byte[] body = readAtMost(stream, maxBytes);
            if (body == null) {
                return Response.failed(tooLarge(maxBytes), attempt);
            }
            if (!gzipped(headers)) {
                return Response.withBody(status, headers, body, attempt);
            }
            byte[] decoded;
            try {
                decoded = readAtMost(new GZIPInputStream(new ByteArrayInputStream(body)), maxBytes);
            } catch (IOException e) {
                // Not a transport error: the bytes arrived, and a retry would arrive at the same place.
                return Response.failed("unreadable content encoding: gzip", attempt);
            }
            return decoded == null
                    ? Response.failed(tooLarge(maxBytes), attempt)
                    : Response.withBody(status, headers, decoded, attempt);
        }
    }

    /**
     * The limit is enforced on both sides of the decoder: on the wire so that a huge
     * response cannot fill memory, and after decoding so that a small, highly
     * compressed body cannot slip past it either.
     */
    private static String tooLarge(long maxBytes) {
        return "body larger than " + maxBytes + " bytes";
    }

    /**
     * We advertise gzip to save the publisher bandwidth and {@code java.net.http} hands the
     * encoded bytes over untouched, so undoing it once here keeps it out of every caller.
     */
    private static boolean gzipped(HttpHeaders headers) {
        String encoding = headers.firstValue("Content-Encoding").orElse("").trim().toLowerCase(Locale.ROOT);
        return encoding.equals("gzip") || encoding.equals("x-gzip");
    }

    /** @return the bytes, or {@code null} once {@code maxBytes} is exceeded */
    private static byte[] readAtMost(InputStream stream, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * A relative {@code Location} is resolved against the URL that produced it, which is
     * what browsers do; anything not an absolute http(s) URL afterwards is refused.
     */
    private static URI redirectTarget(URI from, Response response) {
        String location = response.header("Location");
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            URI target = from.resolve(location.trim());
            String scheme = target.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return target.getHost() == null ? null : target;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Decides, from the response headers alone, whether the body is worth downloading. */
    public interface BodyGate {

        boolean readBody(int status, HttpHeaders headers);

        static BodyGate always() {
            return (status, headers) -> true;
        }
    }

    /** Consulted before every hop, so that a redirect cannot smuggle us past robots.txt. */
    public interface HopGuard {

        HopDecision inspect(URI uri);
    }

    /**
     * Either a refusal with its reason, or permission plus the minimum gap this host
     * is owed -- which may exceed the configured default when robots.txt asks for it.
     */
    public record HopDecision(String refusalReason, Duration minInterval) {

        public static HopDecision allow(Duration minInterval) {
            return new HopDecision(null, minInterval);
        }

        public static HopDecision refuse(String reason) {
            return new HopDecision(reason, Duration.ZERO);
        }

        public boolean refused() {
            return refusalReason != null;
        }
    }

    /**
     * {@code bodySkipped} distinguishes "there was nothing to read" from "we chose not to
     * read it", which is what lets a caller report a skipped PDF as skipped.
     */
    public record Response(
            Integer statusCode,
            HttpHeaders headers,
            byte[] body,
            boolean bodySkipped,
            int attempts,
            String failureReason) {

        static Response withBody(int statusCode, HttpHeaders headers, byte[] body, int attempts) {
            return new Response(statusCode, headers, body, false, attempts, null);
        }

        static Response bodySkipped(int statusCode, HttpHeaders headers, int attempts) {
            return new Response(statusCode, headers, null, true, attempts, null);
        }

        static Response failed(String failureReason, int attempts) {
            return new Response(null, null, null, false, attempts, failureReason);
        }

        public boolean failed() {
            return failureReason != null;
        }

        public String header(String name) {
            return headers == null ? null : headers.firstValue(name).orElse(null);
        }
    }

    /**
     * The chain is kept even for a refusal or a failure, because "where did it go before it
     * broke" is the first question asked of a redirect problem.
     */
    public record Trace(List<URI> chain, Response response, String refusalReason, String failureReason) {

        public Trace {
            chain = List.copyOf(chain);
        }

        static Trace completed(List<URI> chain, Response response) {
            return new Trace(chain, response, null, null);
        }

        static Trace refused(List<URI> chain, String refusalReason) {
            return new Trace(chain, null, refusalReason, null);
        }

        static Trace failed(List<URI> chain, String failureReason) {
            return new Trace(chain, null, null, failureReason);
        }

        public boolean refused() {
            return refusalReason != null;
        }

        /** The last URL requested, which for a completed trace is the final URL. */
        public URI finalUri() {
            return chain.getLast();
        }

        public int hops() {
            return chain.size() - 1;
        }
    }
}
