package org.korhan.quietedit.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Collaborators the ingest components cannot construct themselves.
 *
 * <p>Scheduling is enabled here rather than on the application class: the only
 * scheduled work in this system is the ingest poll, and keeping the switch next to
 * it means the feature area owns its own timer.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IngestConfiguration {

    /**
     * One shared client: it pools connections, and a per-request client would
     * defeat both keep-alive and the per-host politeness this package promises.
     * Redirects are followed here rather than in {@link FeedFetcher} so that a
     * moved feed needs no catalogue edit.
     */
    @Bean
    HttpClient feedHttpClient(FeedFetchProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * A second client, identical except that it never follows a redirect itself:
     * article fetching walks the chain hop by hop so that it can record the final URL
     * and re-check robots.txt before each hop. A client-followed redirect would
     * already have sent the request the guard was meant to prevent.
     */
    @Bean
    HttpClient articleHttpClient(FeedFetchProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.system();
    }
}
