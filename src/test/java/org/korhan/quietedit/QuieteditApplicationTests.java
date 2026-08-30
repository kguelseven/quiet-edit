package org.korhan.quietedit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class QuieteditApplicationTests {

    @Autowired
    private DataSource dataSource;

    /**
     * Context load plus proof that Flyway actually ran against the empty schema:
     * a successful startup alone would not distinguish "migrated" from "skipped".
     */
    @Test
    void contextLoadsAndFlywayHasInitialisedTheSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*) from information_schema.tables "
                             + "where table_schema = 'public' and table_name = 'flyway_schema_history'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }
}
