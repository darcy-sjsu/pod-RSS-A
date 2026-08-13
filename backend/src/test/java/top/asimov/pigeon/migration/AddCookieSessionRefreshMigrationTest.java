package top.asimov.pigeon.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class AddCookieSessionRefreshMigrationTest {

  private static final String MIGRATION_RESOURCE =
      "db/migration/V55__Add_cookie_session_refresh.sql";

  @Test
  void existingCookiesKeepTheirContentAndBecomeDueForAnImmediateRefresh() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      createSchema(connection);
      seedExistingCookie(connection);

      executeMigration(connection);

      assertEquals("cookie-content", selectString(connection, "cookies_content"));
      assertEquals("UNKNOWN", selectString(connection, "session_status"));
      assertEquals(1L, selectLong(connection, "auto_refresh_enabled"));
      assertEquals(600L, selectLong(connection, "rotate_interval_seconds"));
      assertEquals(0L, selectLong(connection, "rotate_failure_count"));
      assertNull(selectString(connection, "last_rotated_at"));
      assertNull(selectString(connection, "last_failure_reason"));
      // NULL means "due now", so the first scan after the upgrade probes the session.
      assertNull(selectString(connection, "next_rotate_at"));
    }
  }

  private void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE cookie_config (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            platform        TEXT NOT NULL,
            cookies_content TEXT NULL,
            enabled         INTEGER NOT NULL DEFAULT 1,
            source_type     TEXT NOT NULL DEFAULT 'UPLOAD',
            created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            UNIQUE(platform)
          )
          """);
    }
  }

  private void seedExistingCookie(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO cookie_config (platform, cookies_content) VALUES ('YOUTUBE', 'cookie-content')");
    }
  }

  private void executeMigration(Connection connection) throws IOException, SQLException {
    try (InputStream inputStream =
        getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
      assertNotNull(inputStream);
      String migrationSql = stripLineComments(
          new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
      for (String sqlStatement : migrationSql.split(";")) {
        if (sqlStatement.isBlank()) {
          continue;
        }
        // Each ALTER TABLE changes the schema, which finalizes any statement still open on the
        // SQLite connection, so every one of them needs a fresh statement.
        try (Statement statement = connection.createStatement()) {
          statement.execute(sqlStatement);
        }
      }
    }
  }

  /**
   * Flyway parses SQL comments properly, this test does not: splitting on {@code ;} would break on
   * a comment that contains one. Dropping comment lines first keeps the naive splitter honest.
   */
  private String stripLineComments(String sql) {
    return sql.lines()
        .filter(line -> !line.stripLeading().startsWith("--"))
        .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
            StringBuilder::append)
        .toString();
  }

  private String selectString(Connection connection, String column) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT " + column + " FROM cookie_config WHERE platform = 'YOUTUBE'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private long selectLong(Connection connection, String column) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT " + column + " FROM cookie_config WHERE platform = 'YOUTUBE'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }
}
