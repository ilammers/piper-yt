package io.github.piper_yt.piperyt.db;

import java.util.ArrayList;
import java.util.List;

/** The cached uploads of favorite channels, newest first. */
public final class Feed {
    /** How long an upload stays in the cache. RSS only lists a channel's latest 15. */
    static final long KEEP_MS = 120L * 24 * 3600 * 1000;

    /** Which uploads to show. */
    public record Filter(boolean hideShorts, boolean hideLive, boolean hideWatched, Long group) {
        public static Filter fromSettings(final Long group) {
            return new Filter(Settings.get(Settings.FEED_HIDE_SHORTS),
                    Settings.get(Settings.FEED_HIDE_LIVE),
                    Settings.get(Settings.FEED_HIDE_WATCHED), group);
        }
    }

    private Feed() {
    }

    /** Replaces what is known about {@code favoriteId}'s latest uploads. */
    public static void store(final long favoriteId, final List<Video> uploads) {
        final long now = System.currentTimeMillis();
        Db.tx(() -> {
            Long newest = null;
            for (final Video v : uploads) {
                final Object[] bind = v.bind();
                final Object[] args = new Object[bind.length + 2];
                args[0] = favoriteId;
                System.arraycopy(bind, 0, args, 1, bind.length);
                args[args.length - 1] = now;
                // RSS doesn't know durations or live status; keep what a video page taught us
                Db.update("INSERT INTO feed_items (favorite_id, " + Video.COLUMNS
                        + ", fetched_at) VALUES (?, " + Video.PLACEHOLDERS + ", ?) "
                        + "ON CONFLICT (video_url) DO UPDATE SET title = excluded.title, "
                        + "thumbnail_url = excluded.thumbnail_url, "
                        + "view_count = excluded.view_count, "
                        + "is_short = MAX(is_short, excluded.is_short), "
                        + "upload_date = COALESCE(excluded.upload_date, upload_date), "
                        + "duration_s = CASE WHEN excluded.duration_s > 0 "
                        + "THEN excluded.duration_s ELSE duration_s END", args);
                if (v.uploadDate() != null && (newest == null || v.uploadDate() > newest)) {
                    newest = v.uploadDate();
                }
            }
            Db.update("UPDATE favorites SET last_upload_at = MAX(COALESCE(last_upload_at, 0), ?),"
                            + " last_refreshed_at = ?, refresh_error = NULL WHERE id = ?",
                    newest == null ? 0 : newest, now, favoriteId);
            Db.update("DELETE FROM feed_items WHERE favorite_id = ? AND upload_date < ?",
                    favoriteId, now - KEEP_MS);
            return null;
        });
    }

    public static void storeError(final long favoriteId, final String error) {
        Favorites.markRefreshed(favoriteId, error);
    }

    /** What a video page learned (live, duration, short) that RSS couldn't say. */
    public static void learn(final Video v) {
        Db.update("UPDATE feed_items SET stream_type = ?, duration_s = CASE WHEN ? > 0 THEN ? "
                        + "ELSE duration_s END, is_short = MAX(is_short, ?) WHERE video_url = ?",
                v.streamType(), v.durationS(), v.durationS(), v.isShort(), v.url());
    }

    /** A cached upload's stream type, from a channel's Live tab (live now, or ended). */
    public static void learnStreamType(final String videoUrl, final String streamType) {
        Db.update("UPDATE feed_items SET stream_type = ? WHERE video_url = ?", streamType,
                videoUrl);
    }

    public static List<Video> list(final Filter filter, final int limit) {
        return list(filter, limit, 0);
    }

    public static List<Video> list(final Filter filter, final int limit, final int offset) {
        final StringBuilder sql = new StringBuilder("SELECT * FROM feed_items f WHERE 1 = 1");
        final List<Object> args = new ArrayList<>();
        if (filter.hideShorts()) {
            sql.append(" AND f.is_short = 0");
        }
        if (filter.hideLive()) {
            sql.append(" AND f.stream_type NOT IN ('LIVE_STREAM', 'AUDIO_LIVE_STREAM')");
        }
        if (filter.hideWatched() && History.enabled()) {
            sql.append(" AND f.video_url NOT IN (SELECT video_url FROM watch_history)");
        }
        if (filter.group() != null) {
            sql.append(" AND f.favorite_id IN (SELECT favorite_id FROM favorite_group_members"
                    + " WHERE group_id = ?)");
            args.add(filter.group());
        }
        sql.append(" ORDER BY f.upload_date DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return Db.query(sql.toString(), Video::read, args.toArray());
    }

    /** Recent uploads of favorites, for the homepage. */
    public static List<Video> recent(final long sinceMs, final int limit) {
        return Db.query("SELECT * FROM feed_items WHERE upload_date >= ? "
                + "ORDER BY upload_date DESC LIMIT ?", Video::read, sinceMs, limit);
    }

    public static long count() {
        return Db.count("SELECT COUNT(*) FROM feed_items");
    }

    /** When the oldest-refreshed favorite was last refreshed, or 0 if one never was. */
    public static long stalestRefresh() {
        final Long v = Db.one("SELECT MIN(COALESCE(last_refreshed_at, 0)) FROM favorites "
                + "WHERE service_id = 0", rs -> {
                    final long min = rs.getLong(1);
                    return rs.wasNull() ? null : min;
                });
        return v == null ? Long.MAX_VALUE : v;
    }
}
