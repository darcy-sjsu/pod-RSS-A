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

class RemoveBilibiliSupportMigrationTest {

  private static final String MIGRATION_RESOURCE =
      "db/migration/V54__Remove_bilibili_support.sql";

  @Test
  void preservesYoutubeFeedsAndEpisodesSharedWithYoutubePlaylists() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      createSchema(connection);
      seedPlatformData(connection);

      executeMigration(connection);

      assertEquals(1, countById(connection, "channel", "youtube-channel"));
      assertEquals(0, countById(connection, "channel", "bilibili-channel"));
      assertEquals(1, countById(connection, "playlist", "youtube-playlist"));
      assertEquals(0, countById(connection, "playlist", "bilibili-playlist"));
      assertEquals("YOUTUBE", selectSource(connection, "channel", "youtube-channel"));
      assertEquals("YOUTUBE", selectSource(connection, "playlist", "youtube-playlist"));
      assertEquals(1, countById(connection, "cookie_config", "YOUTUBE"));
      assertEquals(0, countById(connection, "cookie_config", "BILIBILI"));

      assertEquals(1, countById(connection, "episode", "youtube-episode"));
      assertEquals(0, countById(connection, "episode", "bilibili-only-episode"));
      assertEquals(0, countById(connection, "episode", "bilibili-playlist-only-episode"));

      assertEquals(1, countById(connection, "episode", "shared-channel-episode"));
      assertNull(selectChannelId(connection, "shared-channel-episode"));
      assertEquals(1, countMapping(connection, "youtube-playlist", "shared-channel-episode"));
      assertEquals(0, countMapping(connection, "bilibili-playlist", "shared-channel-episode"));

      assertEquals(1, countById(connection, "episode", "shared-playlist-episode"));
      assertEquals(1, countMapping(connection, "youtube-playlist", "shared-playlist-episode"));
      assertEquals(0, countMapping(connection, "bilibili-playlist", "shared-playlist-episode"));
    }
  }

  private void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE channel (id TEXT PRIMARY KEY, source TEXT NOT NULL)");
      statement.execute("CREATE TABLE playlist (id TEXT PRIMARY KEY, source TEXT NOT NULL)");
      statement.execute("CREATE TABLE episode (id TEXT PRIMARY KEY, channel_id TEXT NULL)");
      statement.execute("""
          CREATE TABLE playlist_episode (
            playlist_id TEXT NOT NULL,
            episode_id TEXT NOT NULL
          )
          """);
      statement.execute("CREATE TABLE cookie_config (platform TEXT PRIMARY KEY)");
    }
  }

  private void seedPlatformData(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          INSERT INTO channel (id, source) VALUES
            ('youtube-channel', 'youtube'),
            ('bilibili-channel', 'BILIBILI')
          """);
      statement.execute("""
          INSERT INTO playlist (id, source) VALUES
            ('youtube-playlist', 'YouTube'),
            ('bilibili-playlist', 'BILIBILI')
          """);
      statement.execute("""
          INSERT INTO episode (id, channel_id) VALUES
            ('youtube-episode', 'youtube-channel'),
            ('bilibili-only-episode', 'bilibili-channel'),
            ('bilibili-playlist-only-episode', NULL),
            ('shared-channel-episode', 'bilibili-channel'),
            ('shared-playlist-episode', NULL)
          """);
      statement.execute("""
          INSERT INTO playlist_episode (playlist_id, episode_id) VALUES
            ('bilibili-playlist', 'bilibili-playlist-only-episode'),
            ('bilibili-playlist', 'shared-channel-episode'),
            ('youtube-playlist', 'shared-channel-episode'),
            ('bilibili-playlist', 'shared-playlist-episode'),
            ('youtube-playlist', 'shared-playlist-episode')
          """);
      statement.execute("""
          INSERT INTO cookie_config (platform) VALUES
            ('YOUTUBE'),
            ('BILIBILI')
          """);
    }
  }

  private void executeMigration(Connection connection) throws IOException, SQLException {
    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
      assertNotNull(inputStream);
      String migrationSql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
      try (Statement statement = connection.createStatement()) {
        for (String sqlStatement : migrationSql.split(";")) {
          if (!sqlStatement.isBlank()) {
            statement.execute(sqlStatement);
          }
        }
      }
    }
  }

  private long countById(Connection connection, String table, String id) throws SQLException {
    String idColumn = "cookie_config".equals(table) ? "platform" : "id";
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private long countMapping(Connection connection, String playlistId, String episodeId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM playlist_episode WHERE playlist_id = ? AND episode_id = ?")) {
      statement.setString(1, playlistId);
      statement.setString(2, episodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private String selectChannelId(Connection connection, String episodeId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT channel_id FROM episode WHERE id = ?")) {
      statement.setString(1, episodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private String selectSource(Connection connection, String table, String id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT source FROM " + table + " WHERE id = ?")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }
}
