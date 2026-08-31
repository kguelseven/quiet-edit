package org.korhan.quietedit.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the schema between tests.
 *
 * <p>{@code truncate} rather than {@code deleteAll()}, because {@code
 * document_version} rejects every {@code delete} since Flyway V4 -- and that is the
 * point of the triggers, not an obstacle to work around. Truncating a table is an
 * administrative act on the whole table, which is what a test reset is; rewriting
 * single rows is what must never happen unnoticed.
 */
public class TestDatabase {

    private final JdbcTemplate jdbc;

    public TestDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every table but {@code flyway_schema_history}, in one statement so order does not matter. */
    public void reset() {
        jdbc.execute("truncate table change, document_version, document, article_attempt, feed cascade");
    }
}
