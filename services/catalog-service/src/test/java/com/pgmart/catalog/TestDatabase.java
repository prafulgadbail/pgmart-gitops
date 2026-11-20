package com.pgmart.catalog;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for all repository tests of this service, with the same Flyway migrations
 * (tables and seeded books) as production. Testcontainers removes the container when the tests end.
 */
final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static JdbcClient client;

    private TestDatabase() {
    }

    static synchronized JdbcClient jdbcClient() {
        if (client == null) {
            POSTGRES.start();
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .load()
                    .migrate();
            client = JdbcClient.create(
                    new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        }
        return client;
    }
}
