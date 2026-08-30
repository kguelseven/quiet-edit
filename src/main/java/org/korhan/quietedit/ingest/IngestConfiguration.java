package org.korhan.quietedit.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;

/** Collaborators the ingest components cannot construct themselves. */
@Configuration(proxyBeanMethods = false)
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

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.system();
    }
}
