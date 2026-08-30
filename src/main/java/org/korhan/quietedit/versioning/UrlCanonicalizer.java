package org.korhan.quietedit.versioning;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the stable identity of an article from the many URL forms under which
 * the same article is served: tracking-tagged, AMP, AMP-cached, mobile-host,
 * trailing-slash and parameter-reordered variants all have to collapse onto one
 * {@code canonicalUrl}, because that string is the unique key of
 * {@link Document} and therefore decides whether two observations are two
 * versions of one article or two unrelated articles.
 *
 * <h2>Why a denylist for query parameters, not an allowlist</h2>
 * The two possible mistakes are not symmetric. Dropping a parameter that
 * actually selects content ({@code ?id=4711}, {@code ?page=3}) merges distinct
 * articles into one document and produces permanent, invisible false "changes"
 * as the two pages alternate — corrupt data that no later stage can repair.
 * Keeping a tracking parameter that should have been dropped only splits one
 * article into two documents; the content hash still recognises the duplicate
 * content, and the damage is bounded and visible. So unknown parameters are
 * preserved and only known-tracking parameters are removed.
 *
 * <h2>Stability</h2>
 * Canonicalisation is a pure function of its input and uses no clock, locale or
 * randomness, so the same input yields the same output across runs. Remaining
 * query parameters are sorted by name, which makes the result independent of the
 * order in which parameters happened to arrive, and the function is idempotent:
 * feeding a canonical URL back in returns it unchanged. Percent-escapes are
 * upper-cased and the default port is dropped for the same reason — two spellings
 * of one address must not survive as two identities.
 *
 * <h2>Deliberate normalisations</h2>
 * <ul>
 *   <li>{@code http} is folded to {@code https}. News sites redirect one to the
 *       other; treating the schemes as separate identities would double every
 *       document the day a site flips its redirect.</li>
 *   <li>The fragment is always dropped: it is never sent to the server and
 *       cannot select a different article.</li>
 *   <li>User info is dropped — credentials are not identity.</li>
 *   <li>AMP-cache URLs ({@code *.cdn.ampproject.org/c/s/...}) are unwrapped back
 *       to the publisher URL they embed.</li>
 * </ul>
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>{@code index.html} versus the bare directory is <em>not</em> folded: on
 *       some hosts only one of the two exists, and guessing wrong invents a URL
 *       that never resolves.</li>
 *   <li>Path-based mobile variants beyond a leading {@code /m/} segment
 *       (e.g. {@code /mobile-app/...}) are left alone — the segment is too often
 *       part of a real slug.</li>
 *   <li>Hashbang routes ({@code #!/story/1}) lose their identity with the
 *       fragment. No newsroom in the current feed set uses them.</li>
 *   <li>A {@code rel=canonical} pointing at another publisher (syndication) is
 *       trusted and folds the copy onto the original, which is right for
 *       identity but hides the possibility that the two copies are edited
 *       independently.</li>
 *   <li>The tracking denylist is a curated list and will lag behind new
 *       campaign parameters; the cost of that lag is the bounded one described
 *       above.</li>
 * </ul>
 */
@Service
public class UrlCanonicalizer {

    /**
     * Prefixes of campaign and analytics parameter families. Matching by prefix
     * covers the open-ended members of each family ({@code utm_source},
     * {@code utm_term}, {@code at_custom4}, {@code wt_zmc}, ...) without having
     * to enumerate them.
     */
    private static final Set<String> TRACKING_PREFIXES = Set.of(
            "utm_",      // Google Analytics campaign tagging, near-universal
            "at_",       // BBC
            "ns_",       // BBC / FT
            "wt_",       // Webtrekk: Zeit, Spiegel, Heise
            "etcc_",     // etracker: Heise
            "pk_",       // Piwik
            "piwik_",
            "matomo_",
            "mtm_",      // Matomo, current spelling
            "hsa_",      // HubSpot ads
            "oly_",      // Omniture / Vox
            "amp_",      // parameters added by the AMP runtime and its caches
            "guce_",     // Yahoo consent round-trip
            "_hs"        // HubSpot email
    );

    /** Single-purpose click, share and campaign identifiers. */
    private static final Set<String> TRACKING_NAMES = Set.of(
            "fbclid", "gclid", "gclsrc", "gbraid", "wbraid", "dclid", "msclkid",
            "twclid", "ttclid", "igshid", "igsh", "yclid", "li_fat_id", "epik",
            "mc_cid", "mc_eid", "_ga", "_gl", "usqp",
            "ref", "ref_src", "refsrc", "referrer", "share", "feature",
            "xtor",              // AT Internet: Le Monde, Les Echos
            "cmp", "cmpid",      // Guardian, Bloomberg
            "icid", "ito",       // Guardian, Daily Mail
            "smid", "smtyp", "partner",  // New York Times
            "mbid",              // Condé Nast
            "ncid",              // TechCrunch / AOL
            "srnd", "taid",      // Bloomberg
            "guccounter",
            "__twitter_impression"
    );

    /**
     * Parameters that select a rendition of the same article rather than a
     * different article. Unlike the tracking lists these are value-sensitive:
     * {@code format=amp} is a variant marker, {@code format=pdf} is not.
     */
    private static final Set<String> AMP_MARKER_NAMES = Set.of("output", "outputtype", "format");

    /** Host labels that only ever front an alternate rendition of the same site. */
    private static final List<String> STRIPPABLE_HOST_PREFIXES = List.of(
            "www.", "www1.", "www2.", "m.", "mobile.", "mobil.", "touch.", "wap.", "amp.");

    private static final Pattern AMP_CACHE_PATH = Pattern.compile("^/[cv]/(?:s/)?(.+)$");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/{2,}");

    /**
     * Canonicalises a fetched URL, preferring the {@code rel=canonical} link the
     * page declares about itself. The publisher's own declaration is the most
     * authoritative statement of identity available, so it wins over the URL we
     * happened to follow — but only if it survives {@link #declaredCanonical}'s
     * plausibility check.
     *
     * @param fetchedUrl the absolute URL the document was retrieved from
     * @param html       the retrieved HTML, may be {@code null} or blank
     * @throws IllegalArgumentException if {@code fetchedUrl} is not a usable
     *                                  absolute http(s) URL
     */
    public String canonicalize(String fetchedUrl, String html) {
        String fromFetchedUrl = canonicalize(fetchedUrl);
        return declaredCanonical(html, fetchedUrl, fromFetchedUrl).orElse(fromFetchedUrl);
    }

    /**
     * Canonicalises an absolute http(s) URL.
     *
     * @throws IllegalArgumentException if the input cannot be parsed, is not
     *                                  absolute, is not http(s) or carries no host
     */
    public String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        URI uri = parse(url.trim());
        requireHttpScheme(uri);
        URI unwrapped = unwrapAmpCache(uri);

        String host = normalizeHost(hostOf(unwrapped));
        String path = normalizePath(unwrapped.getRawPath());
        String query = normalizeQuery(unwrapped.getRawQuery());

        StringBuilder canonical = new StringBuilder("https://").append(host);
        int port = unwrapped.getPort();
        if (port != -1 && port != 80 && port != 443) {
            // A non-default port is part of the address, not decoration: dropping
            // it would produce a canonicalUrl that no longer resolves.
            canonical.append(':').append(port);
        }
        canonical.append(path);
        if (!query.isEmpty()) {
            canonical.append('?').append(query);
        }
        return canonical.toString();
    }

    /**
     * Extracts and canonicalises the {@code rel=canonical} link of a page.
     *
     * <p>Two guards keep a misconfigured CMS from destroying identity: a
     * declaration is rejected if it does not canonicalise at all, and it is
     * rejected if it points at the site root while the fetched URL has a real
     * path. The second case is a common template bug — every article claiming to
     * be the homepage — and honouring it would collapse a whole site into one
     * document.
     *
     * @return the canonicalised declared URL, or empty if there is none to trust
     */
    private Optional<String> declaredCanonical(String html, String baseUrl, String canonicalFetchedUrl) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        Optional<String> declared = findCanonicalHref(html, baseUrl);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        String candidate;
        try {
            candidate = canonicalize(declared.get());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (isSiteRoot(candidate) && !isSiteRoot(canonicalFetchedUrl)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * The {@code rel} attribute is a space-separated token list, so
     * {@code rel="canonical shortlink"} has to match as well; that is why the
     * tokens are inspected instead of relying on an attribute-value selector.
     */
    private Optional<String> findCanonicalHref(String html, String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl;
        for (Element link : Jsoup.parse(html, base).select("link[rel][href]")) {
            for (String token : link.attr("rel").trim().split("\\s+")) {
                if (token.equalsIgnoreCase("canonical")) {
                    String href = link.absUrl("href");
                    return href.isBlank() ? Optional.empty() : Optional.of(href);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSiteRoot(String canonicalUrl) {
        int schemeEnd = canonicalUrl.indexOf("://") + 3;
        int slash = canonicalUrl.indexOf('/', schemeEnd);
        return slash < 0 || slash == canonicalUrl.length() - 1;
    }

    private URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a parseable URL: " + url, e);
        }
    }

    private void requireHttpScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("URL is not absolute: " + uri);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }
    }

    /**
     * {@link URI#getHost()} returns {@code null} for authorities RFC 3986 calls
     * invalid but that occur in the wild (an underscore in the host label being
     * the usual one), so the authority is stripped by hand as a fallback.
     */
    private String hostOf(URI uri) {
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + uri);
        }
        int at = authority.lastIndexOf('@');
        String hostAndPort = at < 0 ? authority : authority.substring(at + 1);
        int colon = hostAndPort.lastIndexOf(':');
        String host = colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
        if (host.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + uri);
        }
        return host;
    }

    /**
     * Rewrites {@code www-example-com.cdn.ampproject.org/c/s/example.com/story}
     * back to {@code example.com/story}. The cache prefix carries the real URL in
     * the path, so unwrapping is exact rather than a guess at the publisher host.
     */
    private URI unwrapAmpCache(URI uri) {
        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".cdn.ampproject.org")) {
            return uri;
        }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        Matcher matcher = AMP_CACHE_PATH.matcher(rawPath);
        if (!matcher.matches()) {
            return uri;
        }
        StringBuilder inner = new StringBuilder("https://").append(matcher.group(1));
        if (uri.getRawQuery() != null) {
            inner.append('?').append(uri.getRawQuery());
        }
        try {
            URI unwrapped = new URI(inner.toString());
            return unwrapped.getHost() == null ? uri : unwrapped;
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    private String normalizeHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : STRIPPABLE_HOST_PREFIXES) {
                // Only strip while a registrable-looking host remains, so that a
                // one-label host is never eaten by a coincidental prefix.
                if (host.startsWith(prefix) && host.substring(prefix.length()).contains(".")) {
                    host = host.substring(prefix.length());
                    stripped = true;
                    break;
                }
            }
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("URL has no host");
        }
        return host;
    }

    private String normalizePath(String rawPath) {
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        path = resolveDotSegments(path);
        path = MULTIPLE_SLASHES.matcher(path).replaceAll("/");
        path = upperCaseEscapes(path);
        path = stripAmpFromPath(path);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * Resolves {@code .} and {@code ..} without ever decoding the path: the
     * multi-argument {@link URI} constructors re-encode what they are given, which
     * would turn an existing {@code %C3%BC} into {@code %25C3%25BC} and break
     * idempotence, so the path is re-parsed as raw text instead.
     */
    private String resolveDotSegments(String rawPath) {
        try {
            String resolved = new URI("https://h" + rawPath).normalize().getRawPath();
            return resolved == null || resolved.isEmpty() ? "/" : resolved;
        } catch (URISyntaxException e) {
            return rawPath;
        }
    }

    private String upperCaseEscapes(String value) {
        return PERCENT_ESCAPE.matcher(value).replaceAll(m -> m.group().toUpperCase(Locale.ROOT));
    }

    /**
     * Removes the path-shaped AMP and mobile markers: a standalone {@code amp}
     * segment anywhere in the path, a leading {@code m} segment, and the
     * {@code .amp} infix some CMSs put before the file extension.
     */
    private String stripAmpFromPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (!segment.equalsIgnoreCase("amp")) {
                segments.add(segment);
            }
        }
        if (segments.size() > 1 && segments.get(1).equalsIgnoreCase("m")) {
            segments.remove(1);
        }
        if (!segments.isEmpty()) {
            int last = segments.size() - 1;
            String tail = segments.get(last);
            if (tail.regionMatches(true, Math.max(0, tail.length() - 9), ".amp.html", 0, 9)) {
                segments.set(last, tail.substring(0, tail.length() - 9) + ".html");
            } else if (tail.regionMatches(true, Math.max(0, tail.length() - 4), ".amp", 0, 4)) {
                segments.set(last, tail.substring(0, tail.length() - 4));
            }
        }
        String joined = String.join("/", segments);
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    /**
     * Keeps each surviving parameter's raw text rather than decoding and
     * re-encoding it: a round-trip through a decoder would have to guess an
     * encoding for the value and could change bytes we are only meant to pass
     * through. Only the name is decoded, and only to match it against the lists.
     */
    private String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        record Param(String sortKey, String raw) {
        }
        List<Param> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            String name = decode(rawName).toLowerCase(Locale.ROOT);
            if (name.isEmpty() || isTracking(name) || isVariantMarker(name, decode(rawValue))) {
                continue;
            }
            kept.add(new Param(name, upperCaseEscapes(pair)));
        }
        kept.sort(Comparator.comparing(Param::sortKey).thenComparing(Param::raw));
        return String.join("&", kept.stream().map(Param::raw).toList());
    }

    private boolean isTracking(String name) {
        if (TRACKING_NAMES.contains(name)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVariantMarker(String name, String value) {
        if (name.equals("amp")) {
            return true;
        }
        if (AMP_MARKER_NAMES.contains(name)) {
            return value.equalsIgnoreCase("amp");
        }
        if (name.equals("m") || name.equals("mobile")) {
            return value.equals("1") || value.equalsIgnoreCase("true");
        }
        return false;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed escape: the raw text is still a usable name for matching.
            return value;
        }
    }
}
