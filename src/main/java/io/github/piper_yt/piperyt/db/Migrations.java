package io.github.piper_yt.piperyt.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Schema versions, applied in order. The file's {@code PRAGMA user_version} records the last
 * one applied; each runs in its own transaction, so a failure leaves the previous version
 * intact. Never edit a released migration: add a new one.
 */
final class Migrations {
    /** One step from {@code version - 1} to {@code version}. */
    record Migration(int version, String sql) {
    }

    /**
     * Video metadata columns shared by every table that lists videos. Each table keeps its own
     * copy so deleting from one (history!) never leaves rows elsewhere pointing at it.
     */
    private static final String VIDEO_COLUMNS = """
            service_id    INTEGER NOT NULL DEFAULT 0,
            video_url     TEXT    NOT NULL,
            title         TEXT    NOT NULL DEFAULT '',
            uploader      TEXT    NOT NULL DEFAULT '',
            uploader_url  TEXT,
            thumbnail_url TEXT,
            duration_s    INTEGER NOT NULL DEFAULT -1,
            stream_type   TEXT    NOT NULL DEFAULT 'VIDEO_STREAM',
            is_short      INTEGER NOT NULL DEFAULT 0,
            upload_date   INTEGER,
            view_count    INTEGER NOT NULL DEFAULT -1""";

    static final List<Migration> ALL = List.of(
            new Migration(1, """
                    CREATE TABLE settings (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );

                    CREATE TABLE favorites (
                        id                INTEGER PRIMARY KEY,
                        service_id        INTEGER NOT NULL DEFAULT 0,
                        url               TEXT    NOT NULL,
                        name              TEXT    NOT NULL DEFAULT '',
                        avatar_url        TEXT,
                        description       TEXT,
                        subscriber_count  INTEGER NOT NULL DEFAULT -1,
                        added_at          INTEGER NOT NULL,
                        last_upload_at    INTEGER,
                        last_refreshed_at INTEGER,
                        refresh_error     TEXT,
                        UNIQUE (service_id, url)
                    );

                    CREATE TABLE favorite_groups (
                        id       INTEGER PRIMARY KEY,
                        name     TEXT    NOT NULL UNIQUE,
                        position INTEGER NOT NULL DEFAULT 0
                    );

                    CREATE TABLE favorite_group_members (
                        group_id    INTEGER NOT NULL REFERENCES favorite_groups (id) ON DELETE CASCADE,
                        favorite_id INTEGER NOT NULL REFERENCES favorites (id) ON DELETE CASCADE,
                        PRIMARY KEY (group_id, favorite_id)
                    );

                    CREATE TABLE feed_items (
                        favorite_id INTEGER NOT NULL REFERENCES favorites (id) ON DELETE CASCADE,
                    @VIDEO@,
                        fetched_at  INTEGER NOT NULL,
                        PRIMARY KEY (video_url)
                    );
                    CREATE INDEX feed_items_upload ON feed_items (upload_date DESC);
                    CREATE INDEX feed_items_favorite ON feed_items (favorite_id);

                    CREATE TABLE playlists (
                        id           INTEGER PRIMARY KEY,
                        kind         TEXT    NOT NULL CHECK (kind IN ('local', 'bookmark')),
                        system_key   TEXT UNIQUE,
                        name         TEXT    NOT NULL,
                        service_id   INTEGER NOT NULL DEFAULT 0,
                        remote_url   TEXT,
                        uploader     TEXT,
                        thumbnail_url TEXT,
                        stream_count INTEGER NOT NULL DEFAULT -1,
                        position     INTEGER NOT NULL DEFAULT 0,
                        created_at   INTEGER NOT NULL,
                        synced_at    INTEGER
                    );

                    CREATE TABLE playlist_items (
                        id          INTEGER PRIMARY KEY,
                        playlist_id INTEGER NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
                        position    INTEGER NOT NULL,
                    @VIDEO@,
                        added_at    INTEGER NOT NULL
                    );
                    CREATE INDEX playlist_items_order ON playlist_items (playlist_id, position);

                    CREATE TABLE watch_history (
                        id           INTEGER PRIMARY KEY,
                    @VIDEO@,
                        watched_at   INTEGER NOT NULL,
                        progress_ms  INTEGER NOT NULL DEFAULT 0,
                        repeat_count INTEGER NOT NULL DEFAULT 1
                    );
                    CREATE INDEX watch_history_url ON watch_history (video_url);
                    CREATE INDEX watch_history_time ON watch_history (watched_at);

                    CREATE TABLE search_history (
                        id          INTEGER PRIMARY KEY,
                        service_id  INTEGER NOT NULL DEFAULT 0,
                        query       TEXT    NOT NULL,
                        searched_at INTEGER NOT NULL
                    );
                    CREATE INDEX search_history_time ON search_history (searched_at);

                    CREATE TABLE downloads (
                        id           INTEGER PRIMARY KEY,
                    @VIDEO@,
                        preset       TEXT    NOT NULL,
                        sponsorblock TEXT    NOT NULL DEFAULT 'off',
                        status       TEXT    NOT NULL
                            CHECK (status IN ('queued', 'running', 'paused', 'failed', 'done')),
                        progress     REAL    NOT NULL DEFAULT 0,
                        file_path    TEXT,
                        file_size    INTEGER,
                        error        TEXT,
                        created_at   INTEGER NOT NULL,
                        finished_at  INTEGER
                    );
                    CREATE INDEX downloads_url ON downloads (video_url);

                    CREATE TABLE hidden_videos (
                        video_url TEXT PRIMARY KEY,
                        hidden_at INTEGER NOT NULL
                    );

                    CREATE TABLE blocked_channels (
                        channel_url TEXT PRIMARY KEY,
                        name        TEXT NOT NULL DEFAULT '',
                        blocked_at  INTEGER NOT NULL
                    );

                    -- Related videos of a seed video. Seeds from watch history are history:
                    -- clearing history deletes them (HistoryStore.clear).
                    CREATE TABLE related_cache (
                        seed_url    TEXT NOT NULL,
                        seed_title  TEXT NOT NULL DEFAULT '',
                        seed_source TEXT NOT NULL CHECK (seed_source IN ('history', 'favorite')),
                    @VIDEO@,
                        fetched_at  INTEGER NOT NULL,
                        PRIMARY KEY (seed_url, video_url)
                    );

                    -- Uploads of channels found through related videos ("discover")
                    CREATE TABLE discover_cache (
                        channel_url TEXT NOT NULL,
                    @VIDEO@,
                        fetched_at  INTEGER NOT NULL,
                        PRIMARY KEY (video_url)
                    );

                    -- The last computed homepage, shown instantly on the next open
                    CREATE TABLE home_cache (
                        position    INTEGER PRIMARY KEY,
                    @VIDEO@,
                        score       REAL NOT NULL,
                        reason      TEXT NOT NULL,
                        detail      TEXT NOT NULL DEFAULT '',
                        computed_at INTEGER NOT NULL
                    );
                    """.replace("@VIDEO@", VIDEO_COLUMNS)),
            // The file type picked in the download dialog. NULL: the one in Preferences.
            new Migration(2, """
                    ALTER TABLE downloads ADD COLUMN format TEXT;
                    """)
    );

    private Migrations() {
    }

    static int latest(final List<Migration> migrations) {
        return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
    }

    static int version(final Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Brings {@code c} up to the last of {@code migrations}. */
    static void migrate(final Connection c, final List<Migration> migrations) throws SQLException {
        final int current = version(c);
        if (current > latest(migrations)) {
            throw new SQLException("The library was written by a newer version of the app (schema "
                    + current + ", this version knows " + latest(migrations) + ")");
        }
        for (final Migration m : migrations) {
            if (m.version() <= current) {
                continue;
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (final String sql : split(m.sql())) {
                    st.execute(sql);
                }
                st.execute("PRAGMA user_version = " + m.version());
                c.commit();
            } catch (final SQLException e) {
                c.rollback();
                throw new SQLException("Migration to schema " + m.version() + " failed: "
                        + e.getMessage(), e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Splits a script on the semicolons that end statements, dropping comment-only lines. */
    static List<String> split(final String script) {
        final StringBuilder clean = new StringBuilder();
        for (final String line : script.split("\n")) {
            if (!line.strip().startsWith("--")) {
                clean.append(line).append('\n');
            }
        }
        return java.util.Arrays.stream(clean.toString().split(";\\s*\n"))
                .map(String::strip)
                .map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
