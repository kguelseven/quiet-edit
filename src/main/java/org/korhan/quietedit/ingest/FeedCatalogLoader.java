package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the feed catalogue.
 *
 * <p>Loaded explicitly instead of via {@code spring.config.import}: the location
 * has to be overridable per test run, and an import is resolved before test
 * properties can redirect it -- an integration test would then have fired at the
 * twelve real publishers in {@code feeds.yaml}. Reusing Boot's own YAML loader and
 * {@link Binder} keeps relaxed binding and {@code ${...}} placeholders working.
 *
 * <p>A malformed entry is skipped with a warning rather than failing the load: one
 * typo in the catalogue must not stop every other feed from being polled.
 */
@Component
public class FeedCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(FeedCatalogLoader.class);
    private static final String FEEDS_KEY = "feeds";

    private final ResourceLoader resourceLoader;
    private final Environment environment;

    public FeedCatalogLoader(ResourceLoader resourceLoader, Environment environment) {
        this.resourceLoader = resourceLoader;
        this.environment = environment;
    }

    public List<FeedDefinition> load(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Feed catalogue {} does not exist, no feeds to ingest", location);
            return List.of();
        }
        List<FeedDefinition> bound;
        try {
            bound = bind(resource, location);
        } catch (IOException e) {
            throw new IllegalStateException("Feed catalogue " + location + " could not be read", e);
        }
        return sanitise(bound, location);
    }

    private List<FeedDefinition> bind(Resource resource, String location) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(location, resource)) {
            sources.addLast(source);
        }
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(environment));
        return binder.bind(FEEDS_KEY, Bindable.listOf(FeedDefinition.class)).orElseGet(List::of);
    }

    private static List<FeedDefinition> sanitise(List<FeedDefinition> definitions, String location) {
        List<FeedDefinition> accepted = new ArrayList<>(definitions.size());
        Set<String> seenUrls = new LinkedHashSet<>();
        for (FeedDefinition definition : definitions) {
            String url = trimToNull(definition.url());
            String name = trimToNull(definition.name());
            if (url == null || name == null) {
                log.warn("Skipping incomplete entry in {}: url={}, name={}", location, definition.url(), definition.name());
                continue;
            }
            if (!seenUrls.add(url)) {
                log.warn("Skipping duplicate url in {}: {}", location, url);
                continue;
            }
            accepted.add(new FeedDefinition(url, name));
        }
        return List.copyOf(accepted);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
