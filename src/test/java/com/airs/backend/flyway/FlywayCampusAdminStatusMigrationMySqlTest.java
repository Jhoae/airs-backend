package com.airs.backend.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FlywayCampusAdminStatusMigrationMySqlTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("airs_admin_status_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void replaceApprovedColumnWithStatusColumn() throws Exception {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE campus_admins (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        campus_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        approved BOOLEAN NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO campus_admins (campus_id, user_id, approved)
                    VALUES (1, 10, TRUE), (1, 11, FALSE)
                    """);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(columnExists(statement, "campus_admins", "approved")).isFalse();
            assertThat(columnExists(statement, "campus_admins", "status")).isTrue();
            assertThat(countRowsByStatus(statement, "APPROVED")).isEqualTo(1);
            assertThat(countRowsByStatus(statement, "PENDING")).isEqualTo(1);
            assertThat(countFlywayHistory(statement, "3", "SQL")).isEqualTo(1);
        }
    }

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
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

    private static int countRowsByStatus(Statement statement, String status) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM campus_admins
                WHERE status = '%s'
                """.formatted(status))) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countFlywayHistory(Statement statement, String version, String type) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '%s'
                  AND type = '%s'
                  AND success = 1
                """.formatted(version, type))) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
