package org.korhan.quietedit;

import org.korhan.quietedit.support.TestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Provides the database for integration tests. Deliberately a fresh, throwaway
 * container per test JVM rather than the docker-compose instance: tests must not
 * depend on -- or mutate -- the local development database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfig {

    static final String POSTGRES_IMAGE = "postgres:18.6";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    TestDatabase testDatabase(JdbcTemplate jdbc) {
        return new TestDatabase(jdbc);
    }
}
