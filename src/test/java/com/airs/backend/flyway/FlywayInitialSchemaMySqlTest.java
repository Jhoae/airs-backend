package com.airs.backend.flyway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayInitialSchemaMySqlTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("airs_initial_schema_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void recreateFinalSchemaFromEmptyDatabaseUsingOnlyMigrations() throws Exception {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(existingTables(statement)).containsExactlyInAnyOrder(
                    "alerts",
                    "airs_nodes",
                    "buildings",
                    "campus_admins",
                    "campuses",
                    "flyway_schema_history",
                    "node_installations",
                    "node_status_snapshots",
                    "space_status_snapshots",
                    "spaces",
                    "telemetry_ingestion_states",
                    "telemetry_outbox",
                    "user_preferences",
                    "users"
            );
            assertThat(tableExists(statement, "devices")).isFalse();
            assertThat(columnExists(statement, "campus_admins", "approved")).isFalse();
            assertThat(columnExists(statement, "campus_admins", "status")).isTrue();
            assertThat(columnType(statement, "alerts", "alert_type")).startsWith("varchar");
            assertThat(successfulSqlMigrationCount(statement)).isEqualTo(5);
        }
    }

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static List<String> existingTables(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SHOW TABLES")) {
            java.util.ArrayList<String> tables = new java.util.ArrayList<>();
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
            return tables;
        }
    }

    private static boolean tableExists(Statement statement, String tableName) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SHOW TABLES LIKE '%s'".formatted(tableName))) {
            return resultSet.next();
        }
    }

    private static boolean columnExists(Statement statement, String tableName, String columnName) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = '%s'
                  AND column_name = '%s'
                """.formatted(tableName, columnName))) {
            resultSet.next();
            return resultSet.getInt(1) == 1;
        }
    }

    private static String columnType(Statement statement, String tableName, String columnName) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT column_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = '%s'
                  AND column_name = '%s'
                """.formatted(tableName, columnName))) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static int successfulSqlMigrationCount(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE type = 'SQL'
                  AND success = 1
                """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
