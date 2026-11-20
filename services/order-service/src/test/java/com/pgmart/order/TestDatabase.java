package com.pgmart.order;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for all repository tests of this service, with the same Flyway migrations
 * (tables only) as production. Testcontainers removes the container when the tests end.
 */
final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static DataSource dataSource;

    private TestDatabase() {
    }

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .load()
                    .migrate();
            dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        return dataSource;
    }

    static JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource());
    }
}
