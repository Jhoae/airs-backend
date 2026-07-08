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
class FlywayBaselineMySqlTest {

	@Container
	static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("airs_baseline_test")
		.withUsername("test")
		.withPassword("test");

	@Test
	void baselineExistingSchemaAndApplyNextMigration() throws Exception {
		try (Connection connection = getConnection();
			 Statement statement = connection.createStatement()) {
			statement.execute("""
				CREATE TABLE users (
					id BIGINT PRIMARY KEY,
					email VARCHAR(50) NOT NULL
				)
				""");
			statement.execute("INSERT INTO users (id, email) VALUES (1, 'existing@airs.test')");
		}

		Flyway flyway = Flyway.configure()
			.dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.locations("classpath:db/migration/flyway-baseline-test")
			.load();

		flyway.migrate();

		try (Connection connection = getConnection();
			 Statement statement = connection.createStatement()) {
			assertThat(countRows(statement, "users")).isEqualTo(1);
			assertThat(countRows(statement, "flyway_probe")).isEqualTo(1);
			assertThat(countFlywayHistory(statement, "1", "BASELINE")).isEqualTo(1);
			assertThat(countFlywayHistory(statement, "2", "SQL")).isEqualTo(1);
		}
	}

	private static Connection getConnection() throws Exception {
		return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
	}

	private static int countRows(Statement statement, String tableName) throws Exception {
		try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
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
