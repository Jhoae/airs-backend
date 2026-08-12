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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayAlertTypeMigrationMySqlTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("airs_alert_type_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void makeAlertTypeColumnExtensible() throws Exception {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE alerts (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        alert_type ENUM('INFO','NODE_OFFLINE','SENSOR_ABNORMAL','VENTILATION_RECOMMENDED','WEAK_WIFI') NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("4")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(alertTypeColumnType(statement)).startsWith("varchar");
            assertThat(countFlywayHistory(statement, "4", "SQL")).isEqualTo(1);
        }
    }

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static String alertTypeColumnType(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT column_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'alerts'
                  AND column_name = 'alert_type'
                """)) {
            resultSet.next();
            return resultSet.getString(1);
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
