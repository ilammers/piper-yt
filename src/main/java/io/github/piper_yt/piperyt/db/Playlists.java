package io.github.piper_yt.piperyt.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Local playlists (including the built-in Watch Later), local snapshots of YouTube playlists
 * (local playlists with a {@code remoteUrl}, re-syncable) and bookmarks of YouTube playlists.
 */
public final class Playlists {
    public static final String WATCH_LATER = "watch_later";

    public enum Kind { LOCAL, BOOKMARK }

    public record Playlist(long id, Kind kind, String systemKey, String name, int serviceId,
                           String remoteUrl, String uploader, String thumbnailUrl,
                           long streamCount, int position, long createdAt, Long syncedAt,
                           int itemCount) {
        public boolean isWatchLater() {
            return WATCH_LATER.equals(systemKey);
        }

        public boolean isSnapshot() {
            return kind == Kind.LOCAL && remoteUrl != null;
        }
    }

    public record Item(long id, int position, Video video, long addedAt) {
    }

    private static final String SELECT = "SELECT p.*, (SELECT COUNT(*) FROM playlist_items i "
            + "WHERE i.playlist_id = p.id) AS item_count FROM playlists p";

    private Playlists() {
    }

    /** Creates the built-in Watch Later playlist if it's missing. */
    public static long watchLaterId() {
        final Long id = Db.one("SELECT id FROM playlists WHERE system_key = ?",
                rs -> rs.getLong(1), WATCH_LATER);
        if (id != null) {
            return id;
        }
        return Db.insert("INSERT INTO playlists (kind, system_key, name, position, created_at) "
                + "VALUES ('local', ?, 'Watch Later', -1, ?)", WATCH_LATER,
                System.currentTimeMillis());
    }

    /** Watch Later first, then by position and name. */
    public static List<Playlist> list() {
        watchLaterId();
        return Db.query(SELECT + " ORDER BY p.system_key IS NULL, p.position, "
                + "p.name COLLATE NOCASE", Playlists::read);
    }

    /** Local playlists only (what "add to playlist" can add to). */
    public static List<Playlist> local() {
        return list().stream().filter(p -> p.kind() == Kind.LOCAL).toList();
    }

    public static Playlist get(final long id) {
        return Db.one(SELECT + " WHERE p.id = ?", Playlists::read, id);
    }

    public static long create(final String name) {
        final long id = Db.insert("INSERT INTO playlists (kind, name, position, created_at) "
                + "VALUES ('local', ?, (SELECT COALESCE(MAX(position) + 1, 0) FROM playlists), ?)",
                name.strip(), System.currentTimeMillis());
        Library.changed(Library.Topic.PLAYLISTS);
        return id;
    }

    public static void rename(final long id, final String name) {
        Db.update("UPDATE playlists SET name = ? WHERE id = ? AND system_key IS NULL",
                name.strip(), id);
        Library.changed(Library.Topic.PLAYLISTS);
    }

    /** Deletes a playlist and its items. Watch Later can only be emptied. */
    public static void delete(final long id) {
        Db.update("DELETE FROM playlists WHERE id = ? AND system_key IS NULL", id);
        Library.changed(Library.Topic.PLAYLISTS);
    }

    public static List<Item> items(final long playlistId) {
        return Db.query("SELECT * FROM playlist_items WHERE playlist_id = ? "
                + "ORDER BY position, id", rs -> new Item(rs.getLong("id"),
                rs.getInt("position"), Video.read(rs), rs.getLong("added_at")), playlistId);
    }

    public static List<Video> videos(final long playlistId) {
        return items(playlistId).stream().map(Item::video).toList();
    }

    public static boolean contains(final long playlistId, final String videoUrl) {
        return Db.count("SELECT COUNT(*) FROM playlist_items WHERE playlist_id = ? "
                + "AND video_url = ?", playlistId, Video.canonicalUrl(videoUrl)) > 0;
    }

    /** Appends {@code video}; false if it's already in the playlist. */
    public static boolean add(final long playlistId, final Video video) {
        final boolean added = Db.tx(() -> {
            if (contains(playlistId, video.url())) {
                return false;
            }
            appendRaw(playlistId, video);
            return true;
        });
        if (added) {
            Library.changed(Library.Topic.PLAYLISTS);
        }
        return added;
    }

    /** Appends without the duplicate check or notification (imports). */
    static void appendRaw(final long playlistId, final Video video) {
        final Object[] bind = video.bind();
        final Object[] args = new Object[bind.length + 3];
        args[0] = playlistId;
        args[1] = playlistId;
        System.arraycopy(bind, 0, args, 2, bind.length);
        args[args.length - 1] = System.currentTimeMillis();
        Db.update("INSERT INTO playlist_items (playlist_id, position, " + Video.COLUMNS
                + ", added_at) VALUES (?, (SELECT COALESCE(MAX(position) + 1, 0) FROM "
                + "playlist_items WHERE playlist_id = ?), " + Video.PLACEHOLDERS + ", ?)", args);
    }

    public static void remove(final long playlistId, final String videoUrl) {
        Db.tx(() -> {
            Db.update("DELETE FROM playlist_items WHERE playlist_id = ? AND video_url = ?",
                    playlistId, Video.canonicalUrl(videoUrl));
            renumber(playlistId);
            return null;
        });
        Library.changed(Library.Topic.PLAYLISTS);
    }

    public static void clear(final long playlistId) {
        Db.update("DELETE FROM playlist_items WHERE playlist_id = ?", playlistId);
        Library.changed(Library.Topic.PLAYLISTS);
    }

    /** Moves the item at {@code from} to index {@code to}, shifting the ones between. */
    public static void move(final long playlistId, final int from, final int to) {
        if (from == to) {
            return;
        }
        Db.tx(() -> {
            final List<Item> items = items(playlistId);
            if (from < 0 || from >= items.size() || to < 0 || to >= items.size()) {
                return null;
            }
            final java.util.ArrayList<Item> order = new java.util.ArrayList<>(items);
            order.add(to, order.remove(from));
            for (int i = 0; i < order.size(); i++) {
                Db.update("UPDATE playlist_items SET position = ? WHERE id = ?", i,
                        order.get(i).id());
            }
            return null;
        });
        Library.changed(Library.Topic.PLAYLISTS);
    }

    private static void renumber(final long playlistId) {
        final List<Item> items = items(playlistId);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).position() != i) {
                Db.update("UPDATE playlist_items SET position = ? WHERE id = ?", i,
                        items.get(i).id());
            }
        }
    }

    /** A one-time local copy of a YouTube playlist; re-sync replaces its items. */
    public static long createSnapshot(final String name, final String remoteUrl,
                                      final String uploader, final String thumbnailUrl,
                                      final List<Video> videos) {
        final long id = Db.tx(() -> {
            final long pid = Db.insert("INSERT INTO playlists (kind, name, remote_url, uploader, "
                    + "thumbnail_url, stream_count, position, created_at, synced_at) VALUES "
                    + "('local', ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(position) + 1, 0) FROM "
                    + "playlists), ?, ?)", name, remoteUrl, uploader, thumbnailUrl,
                    videos.size(), System.currentTimeMillis(), System.currentTimeMillis());
            for (final Video v : videos) {
                appendRaw(pid, v);
            }
            return pid;
        });
        Library.changed(Library.Topic.PLAYLISTS);
        return id;
    }

    public static void resync(final long playlistId, final List<Video> videos) {
        Db.tx(() -> {
            Db.update("DELETE FROM playlist_items WHERE playlist_id = ?", playlistId);
            for (final Video v : videos) {
                appendRaw(playlistId, v);
            }
            Db.update("UPDATE playlists SET synced_at = ?, stream_count = ? WHERE id = ?",
                    System.currentTimeMillis(), videos.size(), playlistId);
            return null;
        });
        Library.changed(Library.Topic.PLAYLISTS);
    }

    /** Bookmarks a YouTube playlist: only the link is kept, its videos load from YouTube. */
    public static long bookmark(final int serviceId, final String name, final String remoteUrl,
                                final String uploader, final String thumbnailUrl,
                                final long streamCount) {
        final Long existing = bookmarkId(remoteUrl);
        if (existing != null) {
            return existing;
        }
        final long id = Db.insert("INSERT INTO playlists (kind, name, service_id, remote_url, "
                + "uploader, thumbnail_url, stream_count, position, created_at) VALUES "
                + "('bookmark', ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(position) + 1, 0) "
                + "FROM playlists), ?)", name, serviceId, remoteUrl, uploader, thumbnailUrl,
                streamCount, System.currentTimeMillis());
        Library.changed(Library.Topic.PLAYLISTS);
        return id;
    }

    public static Long bookmarkId(final String remoteUrl) {
        return Db.one("SELECT id FROM playlists WHERE kind = 'bookmark' AND remote_url = ?",
                rs -> rs.getLong(1), remoteUrl);
    }

    public static Long snapshotId(final String remoteUrl) {
        return Db.one("SELECT id FROM playlists WHERE kind = 'local' AND remote_url = ?",
                rs -> rs.getLong(1), remoteUrl);
    }

    private static Playlist read(final ResultSet rs) throws SQLException {
        return new Playlist(rs.getLong("id"),
                "bookmark".equals(rs.getString("kind")) ? Kind.BOOKMARK : Kind.LOCAL,
                rs.getString("system_key"), rs.getString("name"), rs.getInt("service_id"),
                rs.getString("remote_url"), rs.getString("uploader"),
                rs.getString("thumbnail_url"), rs.getLong("stream_count"),
                rs.getInt("position"), rs.getLong("created_at"), Db.getLong(rs, "synced_at"),
                rs.getInt("item_count"));
    }
}
