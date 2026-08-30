package org.korhan.quietedit.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The robots.txt rules that apply to us for one origin, and the verdict they give
 * for a path.
 *
 * <h2>Which group applies</h2>
 * Groups are selected as RFC 9309 describes: the group whose {@code User-agent}
 * value is the most specific match for our product token wins, and only that group
 * is read -- a specific group replaces the {@code *} group, it does not add to it.
 * Several groups naming the same agent are merged, because publishers do split
 * their rules across repeated blocks. A group is matched case-insensitively and by
 * substring, which is what crawlers in the wild do and what publishers therefore
 * write their rules against.
 *
 * <h2>Which rule wins</h2>
 * The longest matching pattern decides, and {@code Allow} wins a tie. That order
 * matters: a site that disallows {@code /} and allows {@code /news/} means the
 * second, and reading the rules top to bottom instead would lock us out of the
 * whole site. {@code *} and a trailing {@code $} are honoured. An empty
 * {@code Disallow} is not a rule at all -- it is the documented way of saying "no
 * restrictions" -- so it is dropped rather than treated as "disallow everything".
 *
 * <h2>Known weaknesses</h2>
 * <ul>
 *   <li>Paths are compared as written. A rule spelling a character percent-encoded
 *       while the URL spells it literally (or the reverse) will not match. Encoding
 *       both sides consistently needs a decision about which bytes are safe to
 *       re-encode, and getting that wrong silently widens what we crawl.</li>
 *   <li>{@code Sitemap} and every other non-group directive is ignored; nothing in
 *       this system discovers URLs from a sitemap.</li>
 *   <li>{@code Crawl-delay} is not part of RFC 9309 but is respected anyway,
 *       clamped by configuration -- see {@link ArticleFetchProperties}.</li>
 * </ul>
 */
public record RobotsRules(List<Rule> rules, Duration crawlDelay) {

    /** Longest robots.txt we read; RFC 9309 asks for at least 500 KiB. */
    static final int MAX_BYTES = 512 * 1024;

    public RobotsRules {
        rules = List.copyOf(rules);
    }

    /** No robots.txt, or one that says nothing about us. */
    public static RobotsRules allowAll() {
        return new RobotsRules(List.of(), Duration.ZERO);
    }

    /**
     * Everything is off limits. Used when robots.txt exists but could not be read
     * (5xx, transport failure): RFC 9309 treats an unreachable robots.txt as a full
     * disallow, and guessing "probably fine" is exactly the guess a publisher would
     * mind.
     */
    public static RobotsRules denyAll() {
        return new RobotsRules(List.of(new Rule(false, "/")), Duration.ZERO);
    }

    /**
     * @param body       the robots.txt text
     * @param agentToken our product token, lower-cased (e.g. {@code quietedit})
     */
    public static RobotsRules parse(String body, String agentToken) {
        List<Group> groups = groups(body);
        int best = -1;
        for (Group group : groups) {
            best = Math.max(best, group.specificity(agentToken));
        }
        if (best < 0) {
            return allowAll();
        }
        List<Rule> rules = new ArrayList<>();
        Duration crawlDelay = Duration.ZERO;
        for (Group group : groups) {
            if (group.specificity(agentToken) == best) {
                rules.addAll(group.rules);
                if (group.crawlDelay.compareTo(crawlDelay) > 0) {
                    crawlDelay = group.crawlDelay;
                }
            }
        }
        return new RobotsRules(rules, crawlDelay);
    }

    /**
     * @param pathAndQuery the request target as sent on the wire, i.e. path plus
     *                     query string -- robots.txt patterns are written against
     *                     that form, not against the path alone
     */
    public boolean allows(String pathAndQuery) {
        String target = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule winner = null;
        for (Rule rule : rules) {
            if (!rule.matches(target)) {
                continue;
            }
            if (winner == null || rule.weight() > winner.weight()
                    || (rule.weight() == winner.weight() && rule.allow())) {
                winner = rule;
            }
        }
        return winner == null || winner.allow();
    }

    /** One {@code Allow} or {@code Disallow} line. */
    public record Rule(boolean allow, String pattern, Pattern regex) {

        Rule(boolean allow, String pattern) {
            this(allow, pattern, compile(pattern));
        }

        boolean matches(String target) {
            return regex.matcher(target).lookingAt();
        }

        /** Specificity is the length of the pattern as written, wildcards included. */
        int weight() {
            return pattern.length();
        }

        /**
         * Patterns are prefix matches, so the regex is anchored at the start only
         * ({@code lookingAt}) unless the pattern ends in {@code $}.
         */
        private static Pattern compile(String pattern) {
            boolean anchored = pattern.endsWith("$");
            String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (char c : body.toCharArray()) {
                if (c == '*') {
                    flush(regex, literal);
                    regex.append(".*");
                } else {
                    literal.append(c);
                }
            }
            flush(regex, literal);
            if (anchored) {
                regex.append('$');
            }
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        }

        private static void flush(StringBuilder regex, StringBuilder literal) {
            if (!literal.isEmpty()) {
                regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
        }
    }

    /**
     * Splits the file into groups. Consecutive {@code User-agent} lines belong to
     * one group; the first rule line ends the header, and the next
     * {@code User-agent} after a rule starts a new group.
     */
    private static List<Group> groups(String body) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean inHeader = false;
        for (String rawLine : body.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    if (!inHeader || current == null) {
                        current = new Group();
                        groups.add(current);
                        inHeader = true;
                    }
                    if (!value.isEmpty()) {
                        current.agents.add(value.toLowerCase(Locale.ROOT));
                    }
                }
                case "disallow", "allow" -> {
                    if (current == null) {
                        continue;
                    }
                    inHeader = false;
                    if (!value.isEmpty()) {
                        current.rules.add(new Rule(field.equals("allow"), value));
                    }
                }
                case "crawl-delay" -> {
                    if (current == null) {
                        continue;
                    }
                    inHeader = false;
                    Group group = current;
                    crawlDelay(value).ifPresent(delay -> group.crawlDelay = delay);
                }
                default -> {
                    // Sitemap, Host, and anything else: not a group directive.
                }
            }
        }
        return groups;
    }

    private static Optional<Duration> crawlDelay(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofMillis(Math.round(seconds * 1000)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static final class Group {
        private final List<String> agents = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private Duration crawlDelay = Duration.ZERO;

        /** -1 no match, 0 the {@code *} group, otherwise the matched token's length. */
        int specificity(String agentToken) {
            int best = -1;
            for (String agent : agents) {
                if (agent.equals("*")) {
                    best = Math.max(best, 0);
                } else if (agentToken.contains(agent)) {
                    best = Math.max(best, agent.length());
                }
            }
            return best;
        }
    }
}
