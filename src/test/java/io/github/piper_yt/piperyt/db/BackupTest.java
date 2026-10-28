package io.github.piper_yt.piperyt.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupTest {
    /** NewPipe's schema 9 statements, verbatim from its app/schemas/…/9.json. */
    private static final List<String> NEWPIPE_9 = List.of(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (`uid` INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT NOT NULL, `service_id` INTEGER NOT NULL, `url` TEXT, `na"
                    + "me` TEXT, `avatar_url` TEXT, `subscriber_count` INTEGER, `description`"
                    + " TEXT, `notification_mode` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_service_id_url`"
                    + " ON `subscriptions` (`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `search_history` (`creation_date` INTEGER, "
                    + "`service_id` INTEGER NOT NULL, `search` TEXT, `id` INTEGER PRIMARY KEY"
                    + " AUTOINCREMENT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_search_history_search` ON `search_hi"
                    + "story` (`search`)",
            "CREATE TABLE IF NOT EXISTS `streams` (`uid` INTEGER PRIMARY KEY AUTOIN"
                    + "CREMENT NOT NULL, `service_id` INTEGER NOT NULL, `url` TEXT NOT NULL, "
                    + "`title` TEXT NOT NULL, `stream_type` TEXT NOT NULL, `duration` INTEGER"
                    + " NOT NULL, `uploader` TEXT NOT NULL, `uploader_url` TEXT, `thumbnail_u"
                    + "rl` TEXT, `view_count` INTEGER, `textual_upload_date` TEXT, `upload_da"
                    + "te` INTEGER, `is_upload_date_approximation` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_streams_service_id_url` ON `s"
                    + "treams` (`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `stream_history` (`stream_id` INTEGER NOT N"
                    + "ULL, `access_date` INTEGER NOT NULL, `repeat_count` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`stream_id`, `access_date`), FOREIGN KEY(`stream_id`) REFE"
                    + "RENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_stream_history_stream_id` ON `stream"
                    + "_history` (`stream_id`)",
            "CREATE TABLE IF NOT EXISTS `stream_state` (`stream_id` INTEGER NOT NUL"
                    + "L, `progress_time` INTEGER NOT NULL, PRIMARY KEY(`stream_id`), FOREIGN"
                    + " KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DEL"
                    + "ETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `playlists` (`uid` INTEGER PRIMARY KEY AUTO"
                    + "INCREMENT NOT NULL, `name` TEXT, `is_thumbnail_permanent` INTEGER NOT "
                    + "NULL, `thumbnail_stream_id` INTEGER NOT NULL, `display_index` INTEGER "
                    + "NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `playlist_stream_join` (`playlist_id` INTEG"
                    + "ER NOT NULL, `stream_id` INTEGER NOT NULL, `join_index` INTEGER NOT NU"
                    + "LL, PRIMARY KEY(`playlist_id`, `join_index`), FOREIGN KEY(`playlist_id"
                    + "`) REFERENCES `playlists`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE D"
                    + "EFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`stream_id`) REFERENCES `str"
                    + "eams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY "
                    + "DEFERRED)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_stream_join_playlist"
                    + "_id_join_index` ON `playlist_stream_join` (`playlist_id`, `join_index`"
                    + ")",
            "CREATE INDEX IF NOT EXISTS `index_playlist_stream_join_stream_id` ON `"
                    + "playlist_stream_join` (`stream_id`)",
            "CREATE TABLE IF NOT EXISTS `remote_playlists` (`uid` INTEGER PRIMARY K"
                    + "EY AUTOINCREMENT NOT NULL, `service_id` INTEGER NOT NULL, `name` TEXT,"
                    + " `url` TEXT, `thumbnail_url` TEXT, `uploader` TEXT, `display_index` IN"
                    + "TEGER NOT NULL, `stream_count` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_playlists_service_id_u"
                    + "rl` ON `remote_playlists` (`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `feed` (`stream_id` INTEGER NOT NULL, `subs"
                    + "cription_id` INTEGER NOT NULL, PRIMARY KEY(`stream_id`, `subscription_"
                    + "id`), FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE C"
                    + "ASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`s"
                    + "ubscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE O"
                    + "N DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
            "CREATE INDEX IF NOT EXISTS `index_feed_subscription_id` ON `feed` (`su"
                    + "bscription_id`)",
            "CREATE TABLE IF NOT EXISTS `feed_group` (`uid` INTEGER PRIMARY KEY AUT"
                    + "OINCREMENT NOT NULL, `name` TEXT NOT NULL, `icon_id` INTEGER NOT NULL,"
                    + " `sort_order` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_feed_group_sort_order` ON `feed_grou"
                    + "p` (`sort_order`)",
            "CREATE TABLE IF NOT EXISTS `feed_group_subscription_join` (`group_id` "
                    + "INTEGER NOT NULL, `subscription_id` INTEGER NOT NULL, PRIMARY KEY(`gro"
                    + "up_id`, `subscription_id`), FOREIGN KEY(`group_id`) REFERENCES `feed_g"
                    + "roup`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY "
                    + "DEFERRED, FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`u"
                    + "id`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED"
                    + ")",
            "CREATE INDEX IF NOT EXISTS `index_feed_group_subscription_join_subscri"
                    + "ption_id` ON `feed_group_subscription_join` (`subscription_id`)",
            "CREATE TABLE IF NOT EXISTS `feed_last_updated` (`subscription_id` INTE"
                    + "GER NOT NULL, `last_updated` INTEGER, PRIMARY KEY(`subscription_id`), "
                    + "FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UP"
                    + "DATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)");

    @AfterEach
    void close() {
        Db.close();
    }

    private static void fill() {
        Favorites.add(0, "https://www.youtube.com/channel/UCaaa", "Alpha", null, 10, null);
        Favorites.add(0, "https://www.youtube.com/channel/UCbbb", "Beta", null, -1, null);
        final long g = Favorites.createGroup("Music");
        Favorites.setInGroup(Favorites.byUrl("https://www.youtube.com/channel/UCaaa").id(), g,
                true);
        final long p = Playlists.create("Mix");
        Playlists.add(p, TestLibrary.video("aaaaaaaaaa1", "aaa"));
        Playlists.add(p, TestLibrary.video("aaaaaaaaaa2", "bbb"));
        Playlists.add(Playlists.watchLaterId(), TestLibrary.video("aaaaaaaaaa3", "aaa"));
        Playlists.bookmark(0, "Remote", "https://www.youtube.com/playlist?list=PL1", "Up",
                null, 5);
        final long h = History.recordWatch(TestLibrary.video("aaaaaaaaaa1", "aaa"));
        History.updateProgress(h, 42_000, 600);
        Settings.set(Settings.SAVE_SEARCH_HISTORY, true);
        History.recordSearch("cats");
    }

    @Test
    void pipePipeZipRoundTrips() throws Exception {
        final Path dir = TestLibrary.open();
        fill();
        final Path zip = dir.resolve("backup.zip");
        Backup.exportZip(zip);

        // The database inside is PipePipe's schema 901, with Room's identity hash
        final Path extracted = dir.resolve("newpipe.db");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            final ZipEntry e = in.getNextEntry();
            assertEquals("newpipe.db", e.getName());
            Files.copy(in, extracted);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + extracted);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                assertEquals(Backup.PIPEPIPE_DB_VERSION, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT identity_hash FROM room_master_table")) {
                assertEquals(Backup.PIPEPIPE_IDENTITY_HASH, rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM subscriptions")) {
                assertEquals(2, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT progress_time FROM stream_state")) {
                assertEquals(42_000, rs.getLong(1));
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                assertTrue(!rs.next(), "dangling foreign key in export");
            }
        }

        Backup.wipe(false);
        assertEquals(0, Favorites.list().size());
        Settings.set(Settings.SAVE_SEARCH_HISTORY, true);
        final Backup.Report report = Backup.importZip(zip);
        assertEquals(2, report.favorites());

        assertEquals(2, Favorites.list().size());
        assertEquals(1, Favorites.groups().size());
        assertEquals(1, Favorites.groups().get(0).memberCount());
        final Playlists.Playlist mix = Playlists.list().stream()
                .filter(p -> p.name().equals("Mix")).findFirst().orElseThrow();
        assertEquals(List.of("aaaaaaaaaa1", "aaaaaaaaaa2"), Playlists.videos(mix.id()).stream()
                .map(v -> Video.youtubeId(v.url())).toList());
        assertEquals(1, Playlists.items(Playlists.watchLaterId()).size());
        assertTrue(Playlists.bookmarkId("https://www.youtube.com/playlist?list=PL1") != null);
        assertEquals(1, History.list("", 10).size());
        assertEquals(42_000, History.resumePosition("https://youtu.be/aaaaaaaaaa1"));
        assertEquals(List.of("cats"), History.recentSearches(10));
    }

    @Test
    void newPipeZipMatchesNewPipeSchema() throws Exception {
        final Path dir = TestLibrary.open();
        fill();
        final Path zip = dir.resolve("newpipe.zip");
        Backup.exportZip(zip, Backup.Flavor.NEWPIPE);

        final Path extracted = dir.resolve("newpipe-9.db");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            final ZipEntry e = in.getNextEntry();
            assertEquals("newpipe.db", e.getName());
            Files.copy(in, extracted);
            // PipePipe's Android settings stay out of a NewPipe backup
            assertEquals(null, in.getNextEntry());
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + extracted);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                assertEquals(9, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT identity_hash FROM room_master_table")) {
                assertEquals(Backup.NEWPIPE_IDENTITY_HASH, rs.getString(1));
            }
            // Every table and index exactly as NewPipe's Room expects, and nothing more
            final java.util.Set<String> actual = new java.util.HashSet<>();
            try (ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT "
                    + "NULL AND name NOT IN ('sqlite_sequence', 'room_master_table')")) {
                while (rs.next()) {
                    actual.add(rs.getString(1));
                }
            }
            final java.util.Set<String> expected = new java.util.HashSet<>();
            for (final String sql : NEWPIPE_9) {
                expected.add(sql.replace(" IF NOT EXISTS", ""));
            }
            assertEquals(expected, actual);
            try (ResultSet rs = st.executeQuery("SELECT p.thumbnail_stream_id = s.uid FROM "
                    + "playlists p JOIN playlist_stream_join j ON j.playlist_id = p.uid AND "
                    + "j.join_index = 0 JOIN streams s ON s.uid = j.stream_id WHERE p.name = 'Mix'")) {
                assertTrue(rs.getBoolean(1), "thumbnail is the first video's");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                assertTrue(!rs.next(), "dangling foreign key in export");
            }
        }

        // And it reads back in
        Backup.wipe(false);
        Settings.set(Settings.SAVE_SEARCH_HISTORY, true);
        assertEquals(2, Backup.importZip(zip).favorites());
        assertEquals(42_000, History.resumePosition("https://youtu.be/aaaaaaaaaa1"));
        assertEquals(2, Playlists.items(Playlists.list().stream()
                .filter(p -> p.name().equals("Mix")).findFirst().orElseThrow().id()).size());
    }

    @Test
    void importingTwiceDoesNotDuplicate() throws Exception {
        final Path dir = TestLibrary.open();
        fill();
        final Path zip = dir.resolve("backup.zip");
        Backup.exportZip(zip);
        Backup.importZip(zip);
        Backup.importZip(zip);
        assertEquals(2, Favorites.list().size());
        assertEquals(1, Db.count("SELECT COUNT(*) FROM watch_history"));
        assertEquals(2, Playlists.items(Playlists.list().stream()
                .filter(p -> p.name().equals("Mix")).findFirst().orElseThrow().id()).size());
    }

    @Test
    void historyIsNotImportedWhileTurnedOff() throws Exception {
        final Path dir = TestLibrary.open();
        fill();
        final Path zip = dir.resolve("backup.zip");
        Backup.exportZip(zip);
        Backup.wipe(false);
        Settings.set(Settings.SAVE_WATCH_HISTORY, false);
        final Backup.Report report = Backup.importZip(zip);
        assertEquals(0, report.historyEntries());
        assertEquals(0, Db.count("SELECT COUNT(*) FROM watch_history"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM search_history"));
    }

    @Test
    void readsOlderNewPipeSchema() throws Exception {
        final Path dir = TestLibrary.open();
        // NewPipe 0.2x: playlists point at a thumbnail stream, no thumbnail_url column
        final Path db = dir.resolve("old.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY, service_id INTEGER, "
                    + "url TEXT, name TEXT, avatar_url TEXT, subscriber_count INTEGER, "
                    + "description TEXT)");
            st.execute("CREATE TABLE streams (uid INTEGER PRIMARY KEY, service_id INTEGER, "
                    + "url TEXT, title TEXT, stream_type TEXT, duration INTEGER, uploader TEXT, "
                    + "uploader_url TEXT, thumbnail_url TEXT, view_count INTEGER, "
                    + "textual_upload_date TEXT, upload_date INTEGER, "
                    + "is_upload_date_approximation INTEGER)");
            st.execute("CREATE TABLE playlists (uid INTEGER PRIMARY KEY, name TEXT, "
                    + "is_thumbnail_permanent INTEGER, thumbnail_stream_id INTEGER)");
            st.execute("CREATE TABLE playlist_stream_join (playlist_id INTEGER, stream_id "
                    + "INTEGER, join_index INTEGER)");
            st.execute("INSERT INTO subscriptions VALUES (1, 0, "
                    + "'https://www.youtube.com/channel/UCold', 'Old', NULL, 5, NULL)");
            st.execute("INSERT INTO subscriptions VALUES (2, 5, "
                    + "'https://www.bilibili.com/space/1', 'Bili', NULL, 5, NULL)");
            st.execute("INSERT INTO streams VALUES (1, 0, "
                    + "'https://www.youtube.com/watch?v=bbbbbbbbbb1', 'T', 'VIDEO_STREAM', 60, "
                    + "'U', NULL, NULL, 1, NULL, NULL, NULL)");
            st.execute("INSERT INTO playlists VALUES (1, 'Old list', 0, 1)");
            st.execute("INSERT INTO playlist_stream_join VALUES (1, 1, 0)");
        }
        final Path zip = dir.resolve("old.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("newpipe.db"));
            Files.copy(db, out);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("newpipe.settings"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }
        final Backup.Report report = Backup.importZip(zip);
        assertEquals(2, report.favorites());
        assertEquals(1, Favorites.refreshable().size(), "only YouTube favorites refresh");
        assertEquals(1, report.playlistItems());

        // The phone's settings ride along into the next export unchanged
        final Path out = dir.resolve("again.zip");
        Backup.exportZip(out);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(out))) {
            ZipEntry e;
            byte[] settings = null;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("newpipe.settings")) {
                    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    in.transferTo(bytes);
                    settings = bytes.toByteArray();
                }
            }
            assertEquals(3, settings.length);
        }
    }

    @Test
    void jsonRoundTrips() throws Exception {
        final Path dir = TestLibrary.open();
        fill();
        HomeStore.block("https://www.youtube.com/channel/UCzzz", "Z");
        final Path json = dir.resolve("export.json");
        Backup.exportJson(json);
        Backup.wipe(false);
        Settings.set(Settings.SAVE_SEARCH_HISTORY, true);
        Backup.importJson(json);
        assertEquals(2, Favorites.list().size());
        assertEquals(1, History.list("", 10).size());
        assertEquals(1, HomeStore.blocked().size());
        assertEquals(1, Playlists.items(Playlists.watchLaterId()).size());
    }
}
