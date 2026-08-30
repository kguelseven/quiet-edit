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
 * <p>Redirects are followed here rather than by {@link HttpClient} because the
 * caller needs two things the client cannot give it: the URL the chain actually
 * ended at, and a veto before each hop -- a redirect can cross into a host, or a
 * path, that robots.txt forbids, and a client-followed redirect would already have
 * sent that request. Every hop therefore passes through {@link HopGuard} and
 * through the per-host gate again.
 *
 * <p>The body is only read when {@link BodyGate} says it is worth reading. That is
 * what makes "non-HTML responses are skipped" cheap: the decision is taken from the
 * response headers, with the stream still unread, so a 40 MB PDF costs us the
 * headers and nothing else.
 *
 * <p>Retry policy matches {@link FeedFetcher}: 5xx and transport errors are
 * retried with exponential backoff, a 4xx is a verdict and is returned as it is.
 * The two loops are still separate implementations -- unifying them is its own
 * ticket, since this one may not rewrite the feed path.
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
     * Follows {@code startUri} through at most {@code maxHops} redirects.
     *
     * @param accept   the {@code Accept} header to send on every hop
     * @param maxBytes the largest body that may be read; a bigger one is a failure,
     *                 not a truncated success, because half an article would hash
     *                 as a change that never happened
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

    /** The retry loop for a single hop. */
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
     * The stream is closed on every path, including the one where the gate refuses
     * the body: an unread, unclosed response body would keep the connection out of
     * the pool for the rest of the run.
     */
    private Response send(URI uri, String accept, long maxBytes, BodyGate bodyGate, int attempt)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", accept)
                // Explicit: without this header a server may choose any encoding,
                // and java.net.http never decodes one for us.
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
                // Not retried as a transport error: the bytes arrived, they just are
                // not the encoding the server promised, and a retry would arrive at
                // the same place.
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
     * We advertise gzip because it saves the publisher bandwidth, and
     * {@code java.net.http} hands the encoded bytes over untouched -- so undoing it
     * here, once, is what keeps every caller from having to know about it.
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
     * A relative {@code Location} is resolved against the URL that produced it,
     * which is what browsers do and what several CMSs rely on. Anything that is not
     * an absolute http(s) URL afterwards is refused rather than guessed at.
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
     * One response. {@code bodySkipped} distinguishes "there was nothing to read"
     * from "we chose not to read it" -- the caller needs that difference to report a
     * skipped PDF as skipped rather than as an empty article.
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
     * The whole walk: every URL requested in order, and how it ended. The chain is
     * kept even for a refusal or a failure, because "where did it go before it broke"
     * is the first question asked of a redirect problem.
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
