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
class FlywayDropLegacyDevicesMigrationMySqlTest {

	@Container
	static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("airs_drop_devices_test")
		.withUsername("test")
		.withPassword("test");

	@Test
	void dropLegacyDevicesTableAfterBaseline() throws Exception {
		try (Connection connection = getConnection();
			 Statement statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE devices (
					node_id VARCHAR(100) PRIMARY KEY
				)
				""");
		}

		Flyway flyway = Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.target("2")
			.locations("classpath:db/migration")
			.load();

		flyway.migrate();

		try (Connection connection = getConnection();
			 Statement statement = connection.createStatement()) {
			assertThat(tableExists(statement, "devices")).isFalse();
			assertThat(countFlywayHistory(statement, "1", "BASELINE")).isEqualTo(1);
			assertThat(countFlywayHistory(statement, "2", "SQL")).isEqualTo(1);
			assertThat(countFlywayHistory(statement, "3", "SQL")).isZero();
		}
	}

	private static Connection getConnection() throws Exception {
		return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
	}

	private static boolean tableExists(Statement statement, String tableName) throws Exception {
		try (ResultSet resultSet = statement.executeQuery("SHOW TABLES LIKE '%s'".formatted(tableName))) {
			return resultSet.next();
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
