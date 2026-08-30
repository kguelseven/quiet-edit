package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The robots.txt rules, from fixtures that look like the files real newsrooms
 * serve. The cases that matter are the ones where a naive parser is confidently
 * wrong: a specific group replacing the wildcard group instead of adding to it, the
 * longest pattern winning rather than the first, and an empty {@code Disallow}
 * meaning "everything is allowed" rather than "nothing is".
 */
class RobotsRulesTest {

    private static final String AGENT = "quietedit";

    @Test
    void aSpecificGroupReplacesTheWildcardGroup() {
        RobotsRules rules = parse("news-example.txt");

        // Ours: /staff/ is forbidden ...
        assertThat(rules.allows("/staff/imprint")).isFalse();
        // ... while /search, which only the * group forbids, is not our rule.
        assertThat(rules.allows("/search?q=merz")).isTrue();
    }

    @Test
    void groupsNamingTheSameAgentAreMerged() {
        RobotsRules rules = parse("news-example.txt");

        assertThat(rules.allows("/paywalled/story-1")).isFalse();
        assertThat(rules.allows("/staff/imprint")).isFalse();
    }

    @Test
    void theLongestMatchingPatternWinsAndAllowBreaksTheTie() {
        RobotsRules rules = parse("news-example.txt");

        assertThat(rules.allows("/staff/columns/kolumne-42")).isTrue();
        assertThat(rules.allows("/staff/columns")).isFalse();
    }

    @Test
    void wildcardAndEndAnchorAreHonoured() {
        RobotsRules rules = parse("news-example.txt");

        assertThat(rules.allows("/2026/08/report.pdf")).isFalse();
        // The anchor makes the rule about the end of the target only.
        assertThat(rules.allows("/2026/08/report.pdf.html")).isTrue();
    }

    @Test
    void aWildcardGroupAppliesWhenNoGroupNamesUs() {
        RobotsRules rules = RobotsRules.parse(read("news-example.txt"), "otherbot");

        assertThat(rules.allows("/search?q=merz")).isFalse();
        assertThat(rules.allows("/story/1?print=1")).isFalse();
        assertThat(rules.allows("/archive/2015/story")).isFalse();
        assertThat(rules.allows("/archive/2026/story")).isTrue();
        assertThat(rules.allows("/staff/imprint")).isTrue();
    }

    @Test
    void crawlDelayComesFromTheGroupThatAppliesToUs() {
        assertThat(parse("news-example.txt").crawlDelay()).isEqualTo(Duration.ofSeconds(4));
        assertThat(RobotsRules.parse(read("news-example.txt"), "otherbot").crawlDelay())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void anEmptyDisallowIsNotARule() {
        assertThat(parse("empty-disallow.txt").allows("/anything")).isTrue();
    }

    @Test
    void aFullBlockAppliesToEveryPath() {
        RobotsRules rules = parse("full-block.txt");

        assertThat(rules.allows("/")).isFalse();
        assertThat(rules.allows("/story/1")).isFalse();
    }

    @Test
    void fieldNamesAndAgentsAreCaseInsensitive() {
        RobotsRules rules = RobotsRules.parse("""
                USER-AGENT: QuietEdit
                DISALLOW: /secret
                """, AGENT);

        assertThat(rules.allows("/secret/file")).isFalse();
        assertThat(rules.allows("/public")).isTrue();
    }

    @Test
    void commentsAndUnknownDirectivesAreIgnored() {
        RobotsRules rules = RobotsRules.parse("""
                # everything below is for us
                User-agent: *          # even here
                Host: www.example.com
                Disallow: /secret      # trailing comment
                """, AGENT);

        assertThat(rules.allows("/secret")).isFalse();
        assertThat(rules.allows("/host")).isTrue();
    }

    @Test
    void rulesBeforeAnyUserAgentLineAreDiscarded() {
        RobotsRules rules = RobotsRules.parse("""
                Disallow: /
                User-agent: *
                Disallow: /admin
                """, AGENT);

        assertThat(rules.allows("/story/1")).isTrue();
        assertThat(rules.allows("/admin/login")).isFalse();
    }

    @Test
    void anUnparseableFileAllowsEverythingRatherThanNothing() {
        // A file we cannot make sense of is not a prohibition; a publisher who wants
        // us out says so in a syntax we do understand.
        assertThat(RobotsRules.parse("<html>404 not found</html>", AGENT).allows("/story/1")).isTrue();
        assertThat(RobotsRules.parse("", AGENT).allows("/story/1")).isTrue();
    }

    @Test
    void anAbsurdCrawlDelayIsStillReportedAsWritten() {
        // Clamping is a policy decision and belongs to RobotsPolicy, not here.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Crawl-delay: 900
                """, AGENT);

        assertThat(rules.crawlDelay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aNonNumericCrawlDelayIsIgnored() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Crawl-delay: slowly
                """, AGENT);

        assertThat(rules.crawlDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void denyAllBlocksEverythingAndAllowAllBlocksNothing() {
        assertThat(RobotsRules.denyAll().allows("/story/1")).isFalse();
        assertThat(RobotsRules.allowAll().allows("/story/1")).isTrue();
    }

    private static RobotsRules parse(String fixture) {
        return RobotsRules.parse(read(fixture), AGENT);
    }

    private static String read(String fixture) {
        try (InputStream in = RobotsRulesTest.class.getResourceAsStream("/robots/" + fixture)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
