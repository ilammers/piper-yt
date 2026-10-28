package io.github.piper_yt.piperyt.db;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moving the library in and out: PipePipe/NewPipe backup zips (so data round-trips with the
 * Android app), a plain JSON export of everything, and wiping it all.
 *
 * <p>A PipePipe backup is a zip holding {@code newpipe.db}, the app's Room database, and
 * optionally {@code newpipe.settings}, its serialized Android preferences. Import reads any
 * NewPipe-family database leniently, column by column. Export writes PipePipe's current schema
 * (version {@value #PIPEPIPE_DB_VERSION}) or upstream NewPipe's (version
 * {@value #NEWPIPE_DB_VERSION}) exactly, with Room's identity hash, so the app opens it without
 * a migration. Android settings from an imported backup are kept as an opaque file
 * and put back into exports, so a round trip doesn't reset the phone's settings.
 */
public final class Backup {
    static final int PIPEPIPE_DB_VERSION = 901;
    static final String PIPEPIPE_IDENTITY_HASH = "d505dd6c0be6a80da07aa980bd361064";
    private static final String DB_ENTRY = "newpipe.db";
    private static final String SETTINGS_ENTRY = "newpipe.settings";
    private static final String ANDROID_SETTINGS = "android-settings.bin";
    private static final int JSON_VERSION = 1;
    private static final String JSON_FORMAT = "piper-yt";
    /** What JSON exports were tagged under the app's earlier names. */
    private static final List<String> LEGACY_JSON_FORMATS =
            List.of("openpipe-yt", "pipepipe-fedora");

    /** PipePipe's Room schema 901, from app/schemas/…/901.json. Order matters (foreign keys). */
    private static final List<String> PIPEPIPE_SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT "
                    + "NOT NULL, `service_id` INTEGER NOT NULL, `url` TEXT, `name` TEXT, "
                    + "`avatar_url` TEXT, `subscriber_count` INTEGER, `description` TEXT, "
                    + "`notification_mode` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_service_id_url` ON "
                    + "`subscriptions` (`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `search_history` (`creation_date` INTEGER, `service_id` "
                    + "INTEGER NOT NULL, `search` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT "
                    + "NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_search_history_search` ON `search_history` "
                    + "(`search`)",
            "CREATE TABLE IF NOT EXISTS `streams` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT "
                    + "NULL, `service_id` INTEGER NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT "
                    + "NULL, `stream_type` TEXT NOT NULL, `duration` INTEGER NOT NULL, `uploader` "
                    + "TEXT NOT NULL, `uploader_url` TEXT, `thumbnail_url` TEXT, `view_count` "
                    + "INTEGER, `textual_upload_date` TEXT, `upload_date` INTEGER, "
                    + "`is_upload_date_approximation` INTEGER, `is_paid` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_streams_service_id_url` ON `streams` "
                    + "(`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `stream_history` (`stream_id` INTEGER NOT NULL, "
                    + "`access_date` INTEGER NOT NULL, `repeat_count` INTEGER NOT NULL, PRIMARY "
                    + "KEY(`stream_id`, `access_date`), FOREIGN KEY(`stream_id`) REFERENCES "
                    + "`streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_stream_history_stream_id` ON `stream_history` "
                    + "(`stream_id`)",
            "CREATE TABLE IF NOT EXISTS `stream_state` (`stream_id` INTEGER NOT NULL, "
                    + "`progress_time` INTEGER NOT NULL, PRIMARY KEY(`stream_id`), FOREIGN "
                    + "KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE "
                    + "CASCADE )",
            "CREATE TABLE IF NOT EXISTS `playlists` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT "
                    + "NULL, `name` TEXT, `thumbnail_url` TEXT, `display_index` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)",
            "CREATE TABLE IF NOT EXISTS `playlist_stream_join` (`playlist_id` INTEGER NOT NULL, "
                    + "`stream_id` INTEGER NOT NULL, `join_index` INTEGER NOT NULL, PRIMARY "
                    + "KEY(`playlist_id`, `join_index`), FOREIGN KEY(`playlist_id`) REFERENCES "
                    + "`playlists`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE "
                    + "INITIALLY DEFERRED, FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) "
                    + "ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_stream_join_playlist_id_join_index`"
                    + " ON `playlist_stream_join` (`playlist_id`, `join_index`)",
            "CREATE INDEX IF NOT EXISTS `index_playlist_stream_join_stream_id` ON "
                    + "`playlist_stream_join` (`stream_id`)",
            "CREATE TABLE IF NOT EXISTS `remote_playlists` (`uid` INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT NOT NULL, `service_id` INTEGER NOT NULL, `name` TEXT, `url` "
                    + "TEXT, `thumbnail_url` TEXT, `uploader` TEXT, `display_index` INTEGER NOT "
                    + "NULL, `stream_count` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_remote_playlists_name` ON `remote_playlists` "
                    + "(`name`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_playlists_service_id_url` ON "
                    + "`remote_playlists` (`service_id`, `url`)",
            "CREATE TABLE IF NOT EXISTS `feed` (`stream_id` INTEGER NOT NULL, `subscription_id` "
                    + "INTEGER NOT NULL, PRIMARY KEY(`stream_id`, `subscription_id`), FOREIGN "
                    + "KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE "
                    + "CASCADE DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subscription_id`) "
                    + "REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE "
                    + "DEFERRABLE INITIALLY DEFERRED)",
            "CREATE INDEX IF NOT EXISTS `index_feed_subscription_id` ON `feed` "
                    + "(`subscription_id`)",
            "CREATE TABLE IF NOT EXISTS `feed_group` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT "
                    + "NOT NULL, `name` TEXT NOT NULL, `icon_id` INTEGER NOT NULL, `sort_order` "
                    + "INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_feed_group_sort_order` ON `feed_group` "
                    + "(`sort_order`)",
            "CREATE TABLE IF NOT EXISTS `feed_group_subscription_join` (`group_id` INTEGER NOT "
                    + "NULL, `subscription_id` INTEGER NOT NULL, PRIMARY KEY(`group_id`, "
                    + "`subscription_id`), FOREIGN KEY(`group_id`) REFERENCES `feed_group`(`uid`) "
                    + "ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED, FOREIGN "
                    + "KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE "
                    + "ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
            "CREATE INDEX IF NOT EXISTS `index_feed_group_subscription_join_subscription_id` ON "
                    + "`feed_group_subscription_join` (`subscription_id`)",
            "CREATE TABLE IF NOT EXISTS `feed_last_updated` (`subscription_id` INTEGER NOT NULL, "
                    + "`last_updated` INTEGER, PRIMARY KEY(`subscription_id`), FOREIGN "
                    + "KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE "
                    + "ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash "
                    + "TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '"
                    + PIPEPIPE_IDENTITY_HASH + "')");

    /** Upstream NewPipe's current schema, version {@value #NEWPIPE_DB_VERSION}. */
    static final int NEWPIPE_DB_VERSION = 9;
    static final String NEWPIPE_IDENTITY_HASH = "7591e8039faa74d8c0517dc867af9d3e";

    /**
     * NewPipe's Room schema 9, from app/schemas/…/9.json. Differs from PipePipe's 901 in
     * {@code streams} (no {@code is_paid}), {@code playlists} (a thumbnail stream instead of a
     * thumbnail URL) and two missing name indices; the other statements are shared.
     */
    private static final List<String> NEWPIPE_SCHEMA = PIPEPIPE_SCHEMA.stream()
            .filter(sql -> !sql.contains("`index_playlists_name`")
                    && !sql.contains("`index_remote_playlists_name`"))
            .map(sql -> sql
                    .replace(", `is_paid` INTEGER NOT NULL)", ")")
                    .replace("`name` TEXT, `thumbnail_url` TEXT, `display_index` INTEGER NOT NULL)",
                            "`name` TEXT, `is_thumbnail_permanent` INTEGER NOT NULL, "
                                    + "`thumbnail_stream_id` INTEGER NOT NULL, `display_index` "
                                    + "INTEGER NOT NULL)")
                    .replace(PIPEPIPE_IDENTITY_HASH, NEWPIPE_IDENTITY_HASH))
            .toList();

    /** Which app a backup zip is for. */
    public enum Flavor {
        PIPEPIPE(PIPEPIPE_SCHEMA, PIPEPIPE_DB_VERSION),
        NEWPIPE(NEWPIPE_SCHEMA, NEWPIPE_DB_VERSION);

        final List<String> schema;
        final int version;

        Flavor(final List<String> schema, final int version) {
            this.schema = schema;
            this.version = version;
        }
    }

    /** What an import added (or skipped), for the toast. */
    public record Report(int favorites, int playlists, int playlistItems, int historyEntries,
                         int searches, List<String> notes) {
        public String summary() {
            final List<String> parts = new ArrayList<>();
            part(parts, favorites, "favorite", "favorites");
            part(parts, playlists, "playlist", "playlists");
            part(parts, historyEntries, "history entry", "history entries");
            part(parts, searches, "search", "searches");
            final String added = parts.isEmpty() ? "Nothing new to import"
                    : "Imported " + String.join(", ", parts);
            return notes.isEmpty() ? added : added + ". " + String.join(" ", notes);
        }

        private static void part(final List<String> parts, final int n, final String one,
                                 final String many) {
            if (n > 0) {
                parts.add(n + " " + (n == 1 ? one : many));
            }
        }
    }

    private Backup() {
    }

    // ---------------------------------------------------------------- PipePipe / NewPipe zip

    /** Imports a PipePipe or NewPipe backup zip, merging it into the library. */
    public static Report importZip(final Path zip) throws IOException, SQLException {
        final Path dir = Files.createTempDirectory("piper-import-");
        try {
            final Path db = dir.resolve(DB_ENTRY);
            byte[] androidSettings = null;
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (DB_ENTRY.equals(entry.getName())) {
                        Files.copy(in, db, StandardCopyOption.REPLACE_EXISTING);
                    } else if (SETTINGS_ENTRY.equals(entry.getName())) {
                        androidSettings = in.readAllBytes();
                    }
                }
            }
            if (!Files.exists(db)) {
                throw new IOException("Not a PipePipe or NewPipe backup: there is no "
                        + DB_ENTRY + " in it");
            }
            final Report report = importDatabase(db);
            if (androidSettings != null) {
                Files.write(Db.file().resolveSibling(ANDROID_SETTINGS), androidSettings);
            }
            return report;
        } finally {
            deleteTree(dir);
        }
    }

    /** Imports a NewPipe-family {@code newpipe.db}. Exposed for tests. */
    static Report importDatabase(final Path file) throws SQLException {
        final Snapshot s = new Snapshot();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            final Set<String> tables = tables(c);
            if (!tables.contains("streams") && !tables.contains("subscriptions")) {
                throw new SQLException("Not a PipePipe or NewPipe database");
            }
            if (tables.contains("subscriptions")) {
                rows(c, "SELECT * FROM subscriptions", rs -> s.favorites.add(new FavoriteRow(
                        rs.getInt("service_id"), str(rs, "url"), str(rs, "name"),
                        str(rs, "avatar_url"), lng(rs, "subscriber_count", -1),
                        str(rs, "description"), System.currentTimeMillis(), new ArrayList<>(),
                        rs.getLong("uid"))));
            }
            if (tables.contains("feed_group") && tables.contains("feed_group_subscription_join")) {
                final Map<Long, String> groupNames = new HashMap<>();
                rows(c, "SELECT * FROM feed_group ORDER BY sort_order", rs -> {
                    groupNames.put(rs.getLong("uid"), str(rs, "name"));
                    s.groups.add(str(rs, "name"));
                });
                rows(c, "SELECT * FROM feed_group_subscription_join", rs -> {
                    final String name = groupNames.get(rs.getLong("group_id"));
                    for (final FavoriteRow f : s.favorites) {
                        if (f.legacyId == rs.getLong("subscription_id") && name != null) {
                            f.groups.add(name);
                        }
                    }
                });
            }
            final Map<Long, Video> streams = new HashMap<>();
            if (tables.contains("streams")) {
                rows(c, "SELECT * FROM streams", rs -> streams.put(rs.getLong("uid"), new Video(
                        rs.getInt("service_id"), Video.canonicalUrl(str(rs, "url")),
                        str(rs, "title"), str(rs, "uploader"), str(rs, "uploader_url"),
                        str(rs, "thumbnail_url"), lng(rs, "duration", -1),
                        str(rs, "stream_type"), str(rs, "url").contains("/shorts/"),
                        nullableLong(rs, "upload_date"), lng(rs, "view_count", -1))));
            }
            if (tables.contains("stream_history")) {
                final Map<Long, Long> progress = new HashMap<>();
                if (tables.contains("stream_state")) {
                    rows(c, "SELECT * FROM stream_state", rs -> progress.put(
                            rs.getLong("stream_id"), rs.getLong("progress_time")));
                }
                final Map<Long, HistoryRow> latest = new HashMap<>();
                rows(c, "SELECT * FROM stream_history ORDER BY access_date", rs -> {
                    final Video v = streams.get(rs.getLong("stream_id"));
                    if (v != null) {
                        final HistoryRow h = new HistoryRow(v, rs.getLong("access_date"),
                                0, Math.max(1, rs.getInt("repeat_count")));
                        s.history.add(h);
                        latest.put(rs.getLong("stream_id"), h);
                    }
                });
                // The phone keeps one resume position per video; it belongs to the latest watch
                latest.forEach((stream, h) -> {
                    final Long p = progress.get(stream);
                    if (p != null) {
                        s.history.set(s.history.indexOf(h), new HistoryRow(h.video, h.watchedAt,
                                p, h.repeatCount));
                    }
                });
            }
            if (tables.contains("playlists") && tables.contains("playlist_stream_join")) {
                final Map<Long, PlaylistRow> byId = new LinkedHashMap<>();
                final Set<String> cols = columns(c, "playlists");
                rows(c, "SELECT * FROM playlists" + (cols.contains("display_index")
                        ? " ORDER BY display_index" : ""), rs -> {
                    final PlaylistRow p = new PlaylistRow(str(rs, "name"),
                            cols.contains("thumbnail_url") ? str(rs, "thumbnail_url") : null,
                            new ArrayList<>());
                    byId.put(rs.getLong("uid"), p);
                });
                rows(c, "SELECT * FROM playlist_stream_join ORDER BY playlist_id, join_index",
                        rs -> {
                            final PlaylistRow p = byId.get(rs.getLong("playlist_id"));
                            final Video v = streams.get(rs.getLong("stream_id"));
                            if (p != null && v != null) {
                                p.videos.add(v);
                            }
                        });
                s.playlists.addAll(byId.values());
            }
            if (tables.contains("remote_playlists")) {
                rows(c, "SELECT * FROM remote_playlists", rs -> s.bookmarks.add(new BookmarkRow(
                        rs.getInt("service_id"), str(rs, "name"), str(rs, "url"),
                        str(rs, "uploader"), str(rs, "thumbnail_url"),
                        lng(rs, "stream_count", -1))));
            }
            if (tables.contains("search_history")) {
                rows(c, "SELECT * FROM search_history ORDER BY creation_date", rs -> {
                    if (str(rs, "search") != null) {
                        s.searches.add(new SearchRow(str(rs, "search"),
                                lng(rs, "creation_date", System.currentTimeMillis())));
                    }
                });
            }
        }
        return merge(s);
    }

    /** Writes the library as a PipePipe backup zip. */
    public static void exportZip(final Path zip) throws IOException, SQLException {
        exportZip(zip, Flavor.PIPEPIPE);
    }

    /**
     * Writes the library as a backup zip for PipePipe or NewPipe. A NewPipe one leaves out the
     * Android settings kept from an imported PipePipe backup, which are PipePipe's.
     */
    public static void exportZip(final Path zip, final Flavor flavor)
            throws IOException, SQLException {
        final Path dir = Files.createTempDirectory("piper-export-");
        try {
            final Path db = dir.resolve(DB_ENTRY);
            writeDatabase(db, flavor);
            final Path androidSettings = Db.file().resolveSibling(ANDROID_SETTINGS);
            final Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                out.putNextEntry(new ZipEntry(DB_ENTRY));
                Files.copy(db, out);
                out.closeEntry();
                if (flavor == Flavor.PIPEPIPE && Files.isRegularFile(androidSettings)) {
                    out.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
                    Files.copy(androidSettings, out);
                    out.closeEntry();
                }
            }
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteTree(dir);
        }
    }

    /** Creates {@code file} as a PipePipe (901) or NewPipe (9) database holding the library. */
    static void writeDatabase(final Path file, final Flavor flavor) throws SQLException {
        final Snapshot s = snapshot();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (Statement st = c.createStatement()) {
                for (final String sql : flavor.schema) {
                    st.execute(sql);
                }
                st.execute("PRAGMA user_version = " + flavor.version);
            }
            c.setAutoCommit(false);
            final Map<String, Long> streamIds = new HashMap<>();
            final StreamWriter streams = new StreamWriter(c, streamIds, flavor);

            final Map<String, Long> subscriptionIds = new HashMap<>();
            try (PreparedStatement st = c.prepareStatement("INSERT OR IGNORE INTO subscriptions "
                    + "(service_id, url, name, avatar_url, subscriber_count, description, "
                    + "notification_mode) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                for (final FavoriteRow f : s.favorites) {
                    st.setInt(1, f.serviceId);
                    st.setString(2, f.url);
                    st.setString(3, f.name);
                    st.setString(4, f.avatarUrl);
                    if (f.subscriberCount >= 0) {
                        st.setLong(5, f.subscriberCount);
                    } else {
                        st.setNull(5, java.sql.Types.INTEGER);
                    }
                    st.setString(6, f.description);
                    st.executeUpdate();
                    subscriptionIds.put(f.url, lastId(c));
                }
            }
            int order = 0;
            for (final String group : s.groups) {
                final long gid;
                try (PreparedStatement st = c.prepareStatement("INSERT INTO feed_group "
                        + "(name, icon_id, sort_order) VALUES (?, 0, ?)")) {
                    st.setString(1, group);
                    st.setInt(2, order++);
                    st.executeUpdate();
                    gid = lastId(c);
                }
                for (final FavoriteRow f : s.favorites) {
                    if (f.groups.contains(group) && subscriptionIds.containsKey(f.url)) {
                        exec(c, "INSERT OR IGNORE INTO feed_group_subscription_join (group_id, "
                                + "subscription_id) VALUES (?, ?)", gid,
                                subscriptionIds.get(f.url));
                    }
                }
            }

            final Map<String, HistoryRow> latestByUrl = new HashMap<>();
            for (final HistoryRow h : s.history) {
                final long sid = streams.id(h.video);
                exec(c, "INSERT OR IGNORE INTO stream_history (stream_id, access_date, "
                        + "repeat_count) VALUES (?, ?, ?)", sid, h.watchedAt, h.repeatCount);
                final HistoryRow prev = latestByUrl.get(h.video.url());
                if (prev == null || h.watchedAt > prev.watchedAt) {
                    latestByUrl.put(h.video.url(), h);
                }
            }
            for (final HistoryRow h : latestByUrl.values()) {
                if (h.progressMs > 0) {
                    exec(c, "INSERT OR REPLACE INTO stream_state (stream_id, progress_time) "
                            + "VALUES (?, ?)", streams.id(h.video), h.progressMs);
                }
            }

            int index = 0;
            for (final PlaylistRow p : s.playlists) {
                if (flavor == Flavor.NEWPIPE) {
                    // NewPipe shows a stream's thumbnail; not permanent, so it follows the
                    // first video. -1 is its "no thumbnail".
                    exec(c, "INSERT INTO playlists (name, is_thumbnail_permanent, "
                            + "thumbnail_stream_id, display_index) VALUES (?, 0, ?, ?)", p.name,
                            p.videos.isEmpty() ? -1 : streams.id(p.videos.get(0)), index++);
                } else {
                    final String thumb = p.thumbnailUrl != null ? p.thumbnailUrl
                            : p.videos.isEmpty() ? null : p.videos.get(0).thumbnailUrl();
                    exec(c, "INSERT INTO playlists (name, thumbnail_url, display_index) VALUES "
                            + "(?, ?, ?)", p.name, thumb, index++);
                }
                final long pid = lastId(c);
                int join = 0;
                for (final Video v : p.videos) {
                    exec(c, "INSERT INTO playlist_stream_join (playlist_id, stream_id, "
                            + "join_index) VALUES (?, ?, ?)", pid, streams.id(v), join++);
                }
            }
            for (final BookmarkRow b : s.bookmarks) {
                exec(c, "INSERT OR IGNORE INTO remote_playlists (service_id, name, url, "
                        + "thumbnail_url, uploader, display_index, stream_count) VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?)", b.serviceId, b.name, b.url, b.thumbnailUrl,
                        b.uploader, index++, b.streamCount < 0 ? null : b.streamCount);
            }
            for (final SearchRow q : s.searches) {
                exec(c, "INSERT INTO search_history (creation_date, service_id, search) "
                        + "VALUES (?, 0, ?)", q.searchedAt, q.query);
            }
            c.commit();
        }
    }

    /** Assigns stream uids, inserting each distinct video once. */
    private static final class StreamWriter {
        private final Connection c;
        private final Map<String, Long> ids;
        private final Flavor flavor;

        StreamWriter(final Connection c, final Map<String, Long> ids, final Flavor flavor) {
            this.c = c;
            this.ids = ids;
            this.flavor = flavor;
        }

        long id(final Video v) throws SQLException {
            final Long known = ids.get(v.serviceId() + " " + v.url());
            if (known != null) {
                return known;
            }
            final boolean paidColumn = flavor == Flavor.PIPEPIPE;
            exec(c, "INSERT INTO streams (service_id, url, title, stream_type, duration, uploader,"
                    + " uploader_url, thumbnail_url, view_count, textual_upload_date, "
                    + "upload_date, is_upload_date_approximation" + (paidColumn ? ", is_paid" : "")
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL"
                    + (paidColumn ? ", 0" : "") + ")", v.serviceId(), v.url(),
                    v.title(), v.streamType(), Math.max(0, v.durationS()), v.uploader(),
                    v.uploaderUrl(), v.thumbnailUrl(), v.viewCount() < 0 ? null : v.viewCount(),
                    v.uploadDate());
            final long id = lastId(c);
            ids.put(v.serviceId() + " " + v.url(), id);
            return id;
        }
    }

    // ---------------------------------------------------------------- JSON

    /** Writes everything in the library, settings included, as one JSON document. */
    public static void exportJson(final Path file) throws IOException {
        final Snapshot s = snapshot();
        final JSONObject root = new JSONObject();
        root.put("format", JSON_FORMAT);
        root.put("version", JSON_VERSION);
        root.put("exported_at", System.currentTimeMillis());
        root.put("settings", new JSONObject(Settings.all()));
        root.put("favorite_groups", new JSONArray(s.groups));
        final JSONArray favorites = new JSONArray();
        for (final FavoriteRow f : s.favorites) {
            favorites.put(new JSONObject().put("service_id", f.serviceId).put("url", f.url)
                    .put("name", f.name).putOpt("avatar_url", f.avatarUrl)
                    .putOpt("description", f.description)
                    .put("subscriber_count", f.subscriberCount).put("added_at", f.addedAt)
                    .put("groups", new JSONArray(f.groups)));
        }
        root.put("favorites", favorites);
        final JSONArray playlists = new JSONArray();
        for (final Playlists.Playlist p : Playlists.list()) {
            final JSONArray items = new JSONArray();
            for (final Playlists.Item i : Playlists.items(p.id())) {
                items.put(videoJson(i.video()).put("added_at", i.addedAt()));
            }
            playlists.put(new JSONObject().put("kind", p.kind().name().toLowerCase())
                    .putOpt("system_key", p.systemKey()).put("name", p.name())
                    .put("service_id", p.serviceId()).putOpt("remote_url", p.remoteUrl())
                    .putOpt("uploader", p.uploader()).putOpt("thumbnail_url", p.thumbnailUrl())
                    .put("stream_count", p.streamCount()).put("created_at", p.createdAt())
                    .putOpt("synced_at", p.syncedAt()).put("items", items));
        }
        root.put("playlists", playlists);
        final JSONArray history = new JSONArray();
        for (final HistoryRow h : s.history) {
            history.put(videoJson(h.video).put("watched_at", h.watchedAt)
                    .put("progress_ms", h.progressMs).put("repeat_count", h.repeatCount));
        }
        root.put("watch_history", history);
        final JSONArray searches = new JSONArray();
        for (final SearchRow q : s.searches) {
            searches.put(new JSONObject().put("query", q.query).put("searched_at", q.searchedAt));
        }
        root.put("search_history", searches);
        root.put("hidden_videos", new JSONArray(Db.query("SELECT * FROM hidden_videos",
                rs -> new JSONObject().put("video_url", rs.getString("video_url"))
                        .put("hidden_at", rs.getLong("hidden_at")))));
        root.put("blocked_channels", new JSONArray(Db.query("SELECT * FROM blocked_channels",
                rs -> new JSONObject().put("channel_url", rs.getString("channel_url"))
                        .put("name", rs.getString("name"))
                        .put("blocked_at", rs.getLong("blocked_at")))));
        final JSONArray downloads = new JSONArray();
        for (final Downloads.Download d : Db.query("SELECT * FROM downloads WHERE status = 'done'",
                rs -> Downloads.get(rs.getLong("id")))) {
            downloads.put(videoJson(d.video()).put("preset", d.preset()).putOpt("format", d.format())
                    .put("sponsorblock", d.sponsorBlock()).putOpt("file_path", d.filePath())
                    .putOpt("file_size", d.fileSize()).put("created_at", d.createdAt())
                    .putOpt("finished_at", d.finishedAt()));
        }
        root.put("downloads", downloads);
        final Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, root.toString(1), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Imports this app's JSON export, or a NewPipe/PipePipe subscriptions export
     * ({@code {"subscriptions": [{"service_id", "url", "name"}]}}), merging it in.
     */
    public static Report importJson(final Path file) throws IOException {
        final JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        final Snapshot s = new Snapshot();
        if (root.has("subscriptions") && !root.has("favorites")) {
            final JSONArray subs = root.getJSONArray("subscriptions");
            for (int i = 0; i < subs.length(); i++) {
                final JSONObject o = subs.getJSONObject(i);
                s.favorites.add(new FavoriteRow(o.optInt("service_id", 0), o.getString("url"),
                        o.optString("name", ""), null, -1, null, System.currentTimeMillis(),
                        new ArrayList<>(), -1));
            }
            return merge(s);
        }
        if (!JSON_FORMAT.equals(root.optString("format"))
                && !LEGACY_JSON_FORMATS.contains(root.optString("format"))) {
            throw new IOException("Not a Piper export");
        }
        final JSONArray groups = root.optJSONArray("favorite_groups", new JSONArray());
        for (int i = 0; i < groups.length(); i++) {
            s.groups.add(groups.getString(i));
        }
        final JSONArray favorites = root.optJSONArray("favorites", new JSONArray());
        for (int i = 0; i < favorites.length(); i++) {
            final JSONObject o = favorites.getJSONObject(i);
            final List<String> member = new ArrayList<>();
            final JSONArray g = o.optJSONArray("groups", new JSONArray());
            for (int j = 0; j < g.length(); j++) {
                member.add(g.getString(j));
            }
            s.favorites.add(new FavoriteRow(o.optInt("service_id", 0), o.getString("url"),
                    o.optString("name", ""), o.optString("avatar_url", null),
                    o.optLong("subscriber_count", -1), o.optString("description", null),
                    o.optLong("added_at", System.currentTimeMillis()), member, -1));
        }
        final JSONArray playlists = root.optJSONArray("playlists", new JSONArray());
        for (int i = 0; i < playlists.length(); i++) {
            final JSONObject o = playlists.getJSONObject(i);
            if ("bookmark".equals(o.optString("kind"))) {
                s.bookmarks.add(new BookmarkRow(o.optInt("service_id", 0), o.optString("name"),
                        o.optString("remote_url"), o.optString("uploader", null),
                        o.optString("thumbnail_url", null), o.optLong("stream_count", -1)));
                continue;
            }
            final PlaylistRow p = new PlaylistRow(Playlists.WATCH_LATER.equals(
                    o.optString("system_key")) ? "Watch Later" : o.optString("name"),
                    o.optString("thumbnail_url", null), new ArrayList<>());
            final JSONArray items = o.optJSONArray("items", new JSONArray());
            for (int j = 0; j < items.length(); j++) {
                p.videos.add(videoFromJson(items.getJSONObject(j)));
            }
            s.playlists.add(p);
        }
        final JSONArray history = root.optJSONArray("watch_history", new JSONArray());
        for (int i = 0; i < history.length(); i++) {
            final JSONObject o = history.getJSONObject(i);
            s.history.add(new HistoryRow(videoFromJson(o), o.getLong("watched_at"),
                    o.optLong("progress_ms", 0), o.optInt("repeat_count", 1)));
        }
        final JSONArray searches = root.optJSONArray("search_history", new JSONArray());
        for (int i = 0; i < searches.length(); i++) {
            final JSONObject o = searches.getJSONObject(i);
            s.searches.add(new SearchRow(o.getString("query"), o.optLong("searched_at", 0)));
        }
        final Report report = merge(s);
        Db.tx(() -> {
            final JSONArray hidden = root.optJSONArray("hidden_videos", new JSONArray());
            for (int i = 0; i < hidden.length(); i++) {
                final JSONObject o = hidden.getJSONObject(i);
                Db.update("INSERT OR IGNORE INTO hidden_videos (video_url, hidden_at) VALUES "
                        + "(?, ?)", o.getString("video_url"), o.optLong("hidden_at", 0));
            }
            final JSONArray blocked = root.optJSONArray("blocked_channels", new JSONArray());
            for (int i = 0; i < blocked.length(); i++) {
                final JSONObject o = blocked.getJSONObject(i);
                Db.update("INSERT OR IGNORE INTO blocked_channels (channel_url, name, blocked_at)"
                        + " VALUES (?, ?, ?)", o.getString("channel_url"), o.optString("name", ""),
                        o.optLong("blocked_at", 0));
            }
            final JSONObject settings = root.optJSONObject("settings", new JSONObject());
            for (final String key : settings.keySet()) {
                // Turning history on behind the user's back would defeat the point of the switch
                if (!key.startsWith("history.")) {
                    Settings.putRaw(key, settings.get(key).toString());
                }
            }
            final JSONArray downloads = root.optJSONArray("downloads", new JSONArray());
            for (int i = 0; i < downloads.length(); i++) {
                final JSONObject o = downloads.getJSONObject(i);
                final String path = o.optString("file_path", null);
                if (path == null || !Files.isRegularFile(Path.of(path))
                        || Db.count("SELECT COUNT(*) FROM downloads WHERE file_path = ?", path) > 0) {
                    continue;
                }
                final Video v = videoFromJson(o);
                final Object[] bind = v.bind();
                final Object[] args = new Object[bind.length + 7];
                System.arraycopy(bind, 0, args, 0, bind.length);
                args[bind.length] = o.optString("preset", "best");
                args[bind.length + 1] = o.optString("format", null);
                args[bind.length + 2] = o.optString("sponsorblock", "off");
                args[bind.length + 3] = path;
                args[bind.length + 4] = o.optLong("file_size", 0);
                args[bind.length + 5] = o.optLong("created_at", 0);
                args[bind.length + 6] = o.optLong("finished_at", 0);
                Db.update("INSERT INTO downloads (" + Video.COLUMNS + ", preset, format, "
                        + "sponsorblock, status, progress, file_path, file_size, created_at, "
                        + "finished_at) VALUES (" + Video.PLACEHOLDERS
                        + ", ?, ?, ?, 'done', 1, ?, ?, ?, ?)", args);
            }
            return null;
        });
        Library.changed(Library.Topic.values());
        return report;
    }

    /** One playlist as JSON: {@code {"name", "items": [...]}}. */
    public static void exportPlaylist(final long playlistId, final Path file) throws IOException {
        final Playlists.Playlist p = Playlists.get(playlistId);
        final JSONArray items = new JSONArray();
        for (final Playlists.Item i : Playlists.items(playlistId)) {
            items.put(videoJson(i.video()).put("added_at", i.addedAt()));
        }
        final JSONObject root = new JSONObject().put("format", JSON_FORMAT + "-playlist")
                .put("version", JSON_VERSION).put("name", p.name())
                .putOpt("remote_url", p.remoteUrl()).put("items", items);
        Files.writeString(file, root.toString(1), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- wipe

    /**
     * Deletes the whole library (favorites, feed, playlists, history, downloads list, caches,
     * settings) and starts an empty one. Downloaded files are only deleted with
     * {@code files}.
     */
    public static void wipe(final boolean files) throws IOException, SQLException {
        final List<Path> downloaded = files ? Db.query("SELECT file_path FROM downloads WHERE "
                + "file_path IS NOT NULL", rs -> Path.of(rs.getString(1))) : List.of();
        final Path db = Db.file();
        Db.close();
        for (final Path p : downloaded) {
            Files.deleteIfExists(p);
        }
        for (final String suffix : List.of("", "-wal", "-shm", "-journal")) {
            Files.deleteIfExists(Path.of(db + suffix));
        }
        Files.deleteIfExists(db.resolveSibling(ANDROID_SETTINGS));
        Db.open(db);
        Library.changed(Library.Topic.values());
    }

    // ---------------------------------------------------------------- shared model

    private record FavoriteRow(int serviceId, String url, String name, String avatarUrl,
                               long subscriberCount, String description, long addedAt,
                               List<String> groups, long legacyId) {
    }

    private record PlaylistRow(String name, String thumbnailUrl, List<Video> videos) {
    }

    private record BookmarkRow(int serviceId, String name, String url, String uploader,
                               String thumbnailUrl, long streamCount) {
    }

    private record HistoryRow(Video video, long watchedAt, long progressMs, int repeatCount) {
    }

    private record SearchRow(String query, long searchedAt) {
    }

    /** The library (or an import) as plain rows. */
    private static final class Snapshot {
        final List<FavoriteRow> favorites = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        final List<PlaylistRow> playlists = new ArrayList<>();
        final List<BookmarkRow> bookmarks = new ArrayList<>();
        final List<HistoryRow> history = new ArrayList<>();
        final List<SearchRow> searches = new ArrayList<>();
    }

    private static Snapshot snapshot() {
        final Snapshot s = new Snapshot();
        final Map<Long, String> groupNames = new HashMap<>();
        for (final Favorites.Group g : Favorites.groups()) {
            groupNames.put(g.id(), g.name());
            s.groups.add(g.name());
        }
        for (final Favorites.Favorite f : Favorites.list().stream()
                .sorted(Comparator.comparingLong(Favorites.Favorite::addedAt)).toList()) {
            final List<String> groups = new ArrayList<>();
            for (final long gid : Favorites.groupsOf(f.id())) {
                groups.add(groupNames.get(gid));
            }
            s.favorites.add(new FavoriteRow(f.serviceId(), f.url(), f.name(), f.avatarUrl(),
                    f.subscriberCount(), f.description(), f.addedAt(), groups, f.id()));
        }
        for (final Playlists.Playlist p : Playlists.list()) {
            if (p.kind() == Playlists.Kind.BOOKMARK) {
                s.bookmarks.add(new BookmarkRow(p.serviceId(), p.name(), p.remoteUrl(),
                        p.uploader(), p.thumbnailUrl(), p.streamCount()));
            } else if (!p.isWatchLater() || p.itemCount() > 0) {
                s.playlists.add(new PlaylistRow(p.name(), p.thumbnailUrl(),
                        Playlists.videos(p.id())));
            }
        }
        s.history.addAll(Db.query("SELECT * FROM watch_history ORDER BY watched_at",
                rs -> new HistoryRow(Video.read(rs), rs.getLong("watched_at"),
                        rs.getLong("progress_ms"), rs.getInt("repeat_count"))));
        s.searches.addAll(Db.query("SELECT * FROM search_history ORDER BY searched_at",
                rs -> new SearchRow(rs.getString("query"), rs.getLong("searched_at"))));
        return s;
    }

    /**
     * Merges imported rows into the library: favorites and bookmarks by URL, playlists by name
     * (an existing local playlist of the same name gets the imported contents; "Watch Later"
     * maps to the built-in one), history entries by video and time. History is only imported
     * while it's turned on.
     */
    private static Report merge(final Snapshot s) {
        final List<String> notes = new ArrayList<>();
        final int[] n = new int[5];
        final boolean history = History.enabled();
        final boolean searches = History.searchEnabled();
        Db.tx(() -> {
            final Map<String, Long> groupIds = new HashMap<>();
            for (final Favorites.Group g : Favorites.groups()) {
                groupIds.put(g.name(), g.id());
            }
            for (final String g : s.groups) {
                if (g != null && !groupIds.containsKey(g)) {
                    groupIds.put(g, Db.insert("INSERT INTO favorite_groups (name, position) "
                            + "VALUES (?, (SELECT COALESCE(MAX(position) + 1, 0) FROM "
                            + "favorite_groups))", g));
                }
            }
            for (final FavoriteRow f : s.favorites) {
                if (f.url == null || f.url.isBlank()) {
                    continue;
                }
                final String url = Favorites.canonicalChannelUrl(f.url);
                final boolean existed = Db.count("SELECT COUNT(*) FROM favorites WHERE "
                        + "service_id = ? AND url = ?", f.serviceId, url) > 0;
                if (!existed) {
                    Db.update("INSERT INTO favorites (service_id, url, name, avatar_url, "
                            + "subscriber_count, description, added_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            f.serviceId, url, f.name == null ? "" : f.name, f.avatarUrl,
                            f.subscriberCount, f.description, f.addedAt);
                    n[0]++;
                }
                final long id = Db.one("SELECT id FROM favorites WHERE service_id = ? AND url = ?",
                        rs -> rs.getLong(1), f.serviceId, url);
                for (final String g : f.groups) {
                    if (groupIds.containsKey(g)) {
                        Db.update("INSERT OR IGNORE INTO favorite_group_members (group_id, "
                                + "favorite_id) VALUES (?, ?)", groupIds.get(g), id);
                    }
                }
            }
            for (final PlaylistRow p : s.playlists) {
                final long id;
                if ("watch later".equalsIgnoreCase(p.name.strip())) {
                    id = Playlists.watchLaterId();
                } else {
                    final Long existing = Db.one("SELECT id FROM playlists WHERE kind = 'local' "
                            + "AND system_key IS NULL AND name = ?", rs -> rs.getLong(1), p.name);
                    if (existing != null) {
                        Db.update("DELETE FROM playlist_items WHERE playlist_id = ?", existing);
                        id = existing;
                    } else {
                        id = Db.insert("INSERT INTO playlists (kind, name, thumbnail_url, "
                                + "position, created_at) VALUES ('local', ?, ?, (SELECT "
                                + "COALESCE(MAX(position) + 1, 0) FROM playlists), ?)",
                                p.name == null ? "Imported playlist" : p.name, p.thumbnailUrl,
                                System.currentTimeMillis());
                    }
                }
                n[1]++;
                for (final Video v : p.videos) {
                    if (Db.count("SELECT COUNT(*) FROM playlist_items WHERE playlist_id = ? AND "
                            + "video_url = ?", id, v.url()) == 0) {
                        Playlists.appendRaw(id, v);
                        n[2]++;
                    }
                }
            }
            for (final BookmarkRow b : s.bookmarks) {
                if (b.url != null && Db.count("SELECT COUNT(*) FROM playlists WHERE kind = "
                        + "'bookmark' AND remote_url = ?", b.url) == 0) {
                    Db.update("INSERT INTO playlists (kind, name, service_id, remote_url, "
                            + "uploader, thumbnail_url, stream_count, position, created_at) VALUES "
                            + "('bookmark', ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(position) + 1, "
                            + "0) FROM playlists), ?)", b.name == null ? b.url : b.name,
                            b.serviceId, b.url, b.uploader, b.thumbnailUrl, b.streamCount,
                            System.currentTimeMillis());
                    n[1]++;
                }
            }
            if (history) {
                for (final HistoryRow h : s.history) {
                    if (Db.count("SELECT COUNT(*) FROM watch_history WHERE video_url = ? AND "
                            + "watched_at = ?", h.video.url(), h.watchedAt) > 0) {
                        continue;
                    }
                    final Object[] bind = h.video.bind();
                    final Object[] args = new Object[bind.length + 3];
                    System.arraycopy(bind, 0, args, 0, bind.length);
                    args[bind.length] = h.watchedAt;
                    args[bind.length + 1] = h.progressMs;
                    args[bind.length + 2] = h.repeatCount;
                    Db.update("INSERT INTO watch_history (" + Video.COLUMNS + ", watched_at, "
                            + "progress_ms, repeat_count) VALUES (" + Video.PLACEHOLDERS
                            + ", ?, ?, ?)", args);
                    n[3]++;
                }
            } else if (!s.history.isEmpty()) {
                notes.add("Watch history was skipped because saving it is turned off.");
            }
            if (searches) {
                for (final SearchRow q : s.searches) {
                    if (Db.count("SELECT COUNT(*) FROM search_history WHERE query = ?",
                            q.query) == 0) {
                        Db.update("INSERT INTO search_history (query, searched_at) VALUES (?, ?)",
                                q.query, q.searchedAt);
                        n[4]++;
                    }
                }
            } else if (!s.searches.isEmpty()) {
                notes.add("Search history was skipped because saving it is turned off.");
            }
            return null;
        });
        final long other = s.favorites.stream().filter(f -> f.serviceId != Video.YOUTUBE).count();
        if (other > 0) {
            notes.add(other + " non-YouTube favorites were kept for export but won't refresh.");
        }
        Library.changed(Library.Topic.values());
        return new Report(n[0], n[1], n[2], n[3], n[4], notes);
    }

    // ---------------------------------------------------------------- JDBC helpers

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void rows(final Connection c, final String sql, final RowConsumer consumer)
            throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                consumer.accept(rs);
            }
        }
    }

    private static Set<String> tables(final Connection c) throws SQLException {
        final Set<String> names = new HashSet<>();
        rows(c, "SELECT name FROM sqlite_master WHERE type = 'table'",
                rs -> names.add(rs.getString(1)));
        return names;
    }

    private static Set<String> columns(final Connection c, final String table)
            throws SQLException {
        final Set<String> names = new HashSet<>();
        rows(c, "PRAGMA table_info(`" + table + "`)", rs -> names.add(rs.getString("name")));
        return names;
    }

    private static void exec(final Connection c, final String sql, final Object... args)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i] instanceof Boolean b ? (b ? 1 : 0) : args[i]);
            }
            st.executeUpdate();
        }
    }

    private static long lastId(final Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String str(final ResultSet rs, final String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (final SQLException e) {
            return null;
        }
    }

    private static long lng(final ResultSet rs, final String column, final long fallback) {
        try {
            final long v = rs.getLong(column);
            return rs.wasNull() ? fallback : v;
        } catch (final SQLException e) {
            return fallback;
        }
    }

    private static Long nullableLong(final ResultSet rs, final String column) {
        try {
            final long v = rs.getLong(column);
            return rs.wasNull() ? null : v;
        } catch (final SQLException e) {
            return null;
        }
    }

    private static JSONObject videoJson(final Video v) {
        return new JSONObject().put("service_id", v.serviceId()).put("url", v.url())
                .put("title", v.title()).put("uploader", v.uploader())
                .putOpt("uploader_url", v.uploaderUrl()).putOpt("thumbnail_url", v.thumbnailUrl())
                .put("duration_s", v.durationS()).put("stream_type", v.streamType())
                .put("is_short", v.isShort()).putOpt("upload_date", v.uploadDate())
                .put("view_count", v.viewCount());
    }

    private static Video videoFromJson(final JSONObject o) {
        return new Video(o.optInt("service_id", 0), Video.canonicalUrl(o.getString("url")),
                o.optString("title", ""), o.optString("uploader", ""),
                o.optString("uploader_url", null), o.optString("thumbnail_url", null),
                o.optLong("duration_s", -1), o.optString("stream_type", null),
                o.optBoolean("is_short", false),
                o.has("upload_date") ? o.getLong("upload_date") : null,
                o.optLong("view_count", -1));
    }

    private static void deleteTree(final Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                    // Temp dir; best effort
                }
            });
        } catch (final IOException ignored) {
            // Already gone
        }
    }
}
