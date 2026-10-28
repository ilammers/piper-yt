package io.github.piper_yt.piperyt.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationsTest {
    @AfterEach
    void close() {
        Db.close();
    }

    @Test
    void freshLibraryHasEveryTableAtLatestVersion() throws Exception {
        TestLibrary.open();
        final Set<String> tables = new HashSet<>(Db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'", rs -> rs.getString(1)));
        for (final String t : List.of("settings", "favorites", "favorite_groups",
                "favorite_group_members", "feed_items", "playlists", "playlist_items",
                "watch_history", "search_history", "downloads", "hidden_videos",
                "blocked_channels", "related_cache", "discover_cache", "home_cache")) {
            assertTrue(tables.contains(t), "missing table " + t);
        }
        assertEquals(Migrations.latest(Migrations.ALL),
                Db.count("PRAGMA user_version"));
    }

    @Test
    void reopeningDoesNotReapplyMigrations() throws Exception {
        final Path dir = TestLibrary.open();
        Favorites.add(0, "https://www.youtube.com/channel/UCabc", "A", null, -1, null);
        Db.close();
        Db.open(dir.resolve("library.db"));
        assertEquals(1, Favorites.list().size());
    }

    @Test
    void migrationsRunInOrderAndKeepData() throws Exception {
        final Path file = Files.createTempDirectory("piper-mig-").resolve("m.db");
        final List<Migrations.Migration> v1 = List.of(
                new Migrations.Migration(1, "CREATE TABLE t (a INTEGER);"));
        final List<Migrations.Migration> v2 = List.of(v1.get(0),
                new Migrations.Migration(2, "ALTER TABLE t ADD COLUMN b TEXT DEFAULT 'x';\n"
                        + "CREATE INDEX t_a ON t (a);"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Migrations.migrate(c, v1);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO t (a) VALUES (7)");
            }
            Migrations.migrate(c, v2);
            assertEquals(2, Migrations.version(c));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT a, b FROM t")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
                assertEquals("x", rs.getString(2));
            }
        }
    }

    @Test
    void failedMigrationRollsBackAndKeepsVersion() throws Exception {
        final Path file = Files.createTempDirectory("piper-mig-").resolve("m.db");
        final List<Migrations.Migration> good = List.of(
                new Migrations.Migration(1, "CREATE TABLE t (a INTEGER);"));
        final List<Migrations.Migration> bad = List.of(good.get(0),
                new Migrations.Migration(2, "CREATE TABLE u (a INTEGER);\nNOT VALID SQL;"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Migrations.migrate(c, good);
            assertThrows(SQLException.class, () -> Migrations.migrate(c, bad));
            assertEquals(1, Migrations.version(c));
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'u'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "half-applied migration left table u behind");
            }
        }
    }

    @Test
    void refusesLibraryFromNewerVersion() throws Exception {
        final Path file = Files.createTempDirectory("piper-mig-").resolve("m.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 999");
            assertThrows(SQLException.class, () -> Migrations.migrate(c, Migrations.ALL));
        }
    }

    @Test
    void splitKeepsStatementsAndDropsComments() {
        final List<String> parts = Migrations.split("""
                -- a comment; with a semicolon
                CREATE TABLE a (x TEXT DEFAULT ';');
                CREATE TABLE b (y INTEGER);
                """);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).startsWith("CREATE TABLE a"));
    }
}
