package com.stocktracker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SETUP.md §10: "A trivial Testcontainers test spins up Postgres and passes."
 * Requires a running Docker daemon — that's the point of the check.
 */
class PostgresSmokeTest {

    @Test
    void postgresStartsAndAnswersQueries() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            try (Connection conn = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).startsWith("PostgreSQL 17"));
            }
        }
    }
}
