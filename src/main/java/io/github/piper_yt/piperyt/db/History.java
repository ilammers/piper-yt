package io.github.piper_yt.piperyt.db;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watch and search history. With "Save watch history" off nothing is written here at all,
 * resume positions included, and nothing reads it for recommendations.
 *
 * <p>Watch counts, completion rates and channel affinity are computed from
 * {@code watch_history} when needed, never stored, so there is no copy to forget. What is
 * stored and derived from history (related videos of watched videos, channels discovered
 * through them, the computed homepage) is deleted by {@link #purgeDerived()} whenever history
 * is deleted.
 */
public final class History {
    /** A rewatch within this long continues the last entry instead of adding one. */
    static final long SAME_SESSION_MS = 15 * 60 * 1000;
    /** This close to the end counts as watched to the end. */
    private static final long END_MARGIN_MS = 15_000;

    /** Bumped on every write, so caches of {@link #progressFractions()} know to reload. */
    private static volatile int version;

    /** One video in the history view: its latest watch and how often it was watched. */
    public record Entry(Video video, long lastWatchedAt, int watchCount, long progressMs) {
    }

    /** What the history says about a channel, for the homepage's affinity score. */
    public record ChannelStats(int watchCount, double completion) {
    }

    /** Time ranges for "Clear history". */
    public enum Range {
        HOUR("Last hour", 3600_000L), DAY("Last 24 hours", 86_400_000L),
        WEEK("Last 7 days", 7 * 86_400_000L), ALL("All time", 0);

        public final String label;
        final long millis;

        Range(final String label, final long millis) {
            this.label = label;
            this.millis = millis;
        }

        /** Entries at or after this time are deleted; 0 means everything. */
        public long since(final long now) {
            return millis == 0 ? 0 : now - millis;
        }
    }

    private History() {
    }

    public static boolean enabled() {
        return Settings.get(Settings.SAVE_WATCH_HISTORY);
    }

    public static boolean searchEnabled() {
        return Settings.get(Settings.SAVE_SEARCH_HISTORY);
    }

    /**
     * Records that {@code video} started playing and returns the entry's id for
     * {@link #updateProgress}, or -1 (and writes nothing) when history is off.
     */
    public static long recordWatch(final Video video) {
        if (!enabled()) {
            return -1;
        }
        final long now = System.currentTimeMillis();
        final long id = Db.tx(() -> {
            final Long recent = Db.one("SELECT id FROM watch_history WHERE video_url = ? AND "
                    + "watched_at >= ? ORDER BY watched_at DESC", rs -> rs.getLong(1),
                    video.url(), now - SAME_SESSION_MS);
            if (recent != null) {
                Db.update("UPDATE watch_history SET watched_at = ? WHERE id = ?", now, recent);
                return recent;
            }
            final Object[] bind = video.bind();
            final Object[] args = new Object[bind.length + 1];
            System.arraycopy(bind, 0, args, 0, bind.length);
            args[bind.length] = now;
            return Db.insert("INSERT INTO watch_history (" + Video.COLUMNS + ", watched_at) "
                    + "VALUES (" + Video.PLACEHOLDERS + ", ?)", args);
        });
        version++;
        Library.changed(Library.Topic.HISTORY);
        return id;
    }

    /** Saves the playback position of an entry; does nothing when history is off. */
    public static void updateProgress(final long entryId, final long progressMs,
                                      final long durationS) {
        if (entryId < 0 || !enabled()) {
            return;
        }
        Db.update("UPDATE watch_history SET progress_ms = ?, duration_s = CASE WHEN ? > 0 "
                + "THEN ? ELSE duration_s END WHERE id = ?", Math.max(0, progressMs), durationS,
                durationS, entryId);
        version++;
    }

    public static int version() {
        return version;
    }

    /**
     * How far into each video the latest watch got, from just above 0 to 1 (1 also for
     * anything within the last few seconds), for progress bars. Videos without a known
     * duration or position are left out.
     */
    public static Map<String, Double> progressFractions() {
        final Map<String, Double> map = new HashMap<>();
        Db.query("SELECT h.video_url, h.progress_ms, h.duration_s FROM watch_history h JOIN ("
                        + "SELECT video_url AS url, MAX(watched_at) AS last_at FROM watch_history "
                        + "GROUP BY video_url) agg ON h.video_url = agg.url AND h.watched_at = "
                        + "agg.last_at WHERE h.duration_s > 0 AND h.progress_ms > 0", rs -> {
                    final long progress = rs.getLong(2);
                    final long durationMs = rs.getLong(3) * 1000;
                    return map.put(rs.getString(1), progress >= durationMs - END_MARGIN_MS
                            ? 1.0 : (double) progress / durationMs);
                });
        return map;
    }

    /**
     * Where to resume {@code videoUrl}, in milliseconds, or 0. Nothing near the start or the
     * end, and nothing at all with history or resume turned off.
     */
    public static long resumePosition(final String videoUrl) {
        if (!enabled() || !Settings.get(Settings.RESUME_PLAYBACK)) {
            return 0;
        }
        final long[] row = Db.one("SELECT progress_ms, duration_s FROM watch_history WHERE "
                + "video_url = ? ORDER BY watched_at DESC", rs -> new long[]{rs.getLong(1),
                rs.getLong(2)}, Video.canonicalUrl(videoUrl));
        if (row == null) {
            return 0;
        }
        final long progress = row[0];
        final long durationMs = row[1] * 1000;
        if (progress < 10_000 || durationMs > 0 && progress > durationMs - END_MARGIN_MS) {
            return 0;
        }
        return progress;
    }

    /** One entry per video, most recently watched first, filtered by title or channel. */
    public static List<Entry> list(final String filter, final int limit) {
        return list(filter, limit, 0);
    }

    public static List<Entry> list(final String filter, final int limit, final int offset) {
        final String like = "%" + (filter == null ? "" : filter.strip()) + "%";
        return Db.query("SELECT h.*, agg.last_at, agg.n FROM watch_history h JOIN ("
                        + "SELECT video_url AS url, MAX(watched_at) AS last_at, "
                        + "SUM(repeat_count) AS n FROM watch_history GROUP BY video_url) agg "
                        + "ON h.video_url = agg.url AND h.watched_at = agg.last_at "
                        + "WHERE h.title LIKE ? OR h.uploader LIKE ? "
                        + "GROUP BY h.video_url ORDER BY agg.last_at DESC LIMIT ? OFFSET ?",
                rs -> new Entry(Video.read(rs), rs.getLong("last_at"), rs.getInt("n"),
                        rs.getLong("progress_ms")), like, like, limit, offset);
    }

    public static Set<String> watchedUrls() {
        return new HashSet<>(Db.query("SELECT DISTINCT video_url FROM watch_history",
                rs -> rs.getString(1)));
    }

    /** Most recently watched distinct videos (homepage seeds). */
    public static List<Video> recentVideos(final int limit) {
        return Db.query("SELECT h.* FROM watch_history h JOIN (SELECT video_url AS url, "
                + "MAX(watched_at) AS last_at FROM watch_history GROUP BY video_url) agg "
                + "ON h.video_url = agg.url AND h.watched_at = agg.last_at "
                + "GROUP BY h.video_url ORDER BY h.watched_at DESC LIMIT ?", Video::read, limit);
    }

    /** Watch count and average completion per channel, computed from history. */
    public static Map<String, ChannelStats> channelStats() {
        final Map<String, ChannelStats> stats = new HashMap<>();
        Db.query("SELECT uploader_url, SUM(repeat_count), AVG(CASE WHEN duration_s > 0 THEN "
                        + "MIN(1.0, progress_ms / (duration_s * 1000.0)) END) FROM watch_history "
                        + "WHERE uploader_url IS NOT NULL GROUP BY uploader_url",
                rs -> stats.put(Favorites.canonicalChannelUrl(rs.getString(1)),
                        new ChannelStats(rs.getInt(2), rs.getDouble(3))));
        return stats;
    }

    /** Removes every watch of one video, and what was derived from it. */
    public static void remove(final String videoUrl) {
        Db.tx(() -> {
            Db.update("DELETE FROM watch_history WHERE video_url = ?",
                    Video.canonicalUrl(videoUrl));
            purgeDerived();
            return null;
        });
        version++;
        Library.changed(Library.Topic.HISTORY, Library.Topic.HOME);
    }

    /**
     * Deletes watch and search history from {@code since} on (0: all of it) and everything
     * derived from the deleted part. With {@code blocklist}, also the hidden videos and blocked
     * channels.
     */
    public static void clear(final long since, final boolean blocklist) {
        Db.tx(() -> {
            Db.update("DELETE FROM watch_history WHERE watched_at >= ?", since);
            Db.update("DELETE FROM search_history WHERE searched_at >= ?", since);
            purgeDerived();
            if (blocklist) {
                Db.update("DELETE FROM hidden_videos WHERE hidden_at >= ?", since);
                Db.update("DELETE FROM blocked_channels WHERE blocked_at >= ?", since);
            }
            return null;
        });
        version++;
        Library.changed(Library.Topic.HISTORY, Library.Topic.HOME, Library.Topic.FEED,
                Library.Topic.BLOCKLIST);
    }

    /**
     * Deletes stored data computed from history that no longer has a source: related videos of
     * watched videos that are gone from history, and the discover and homepage caches (which
     * may have been built from them; they are rebuilt on the next refresh).
     */
    static void purgeDerived() {
        Db.update("DELETE FROM related_cache WHERE seed_source = 'history' AND seed_url NOT IN "
                + "(SELECT video_url FROM watch_history)");
        Db.update("DELETE FROM discover_cache");
        Db.update("DELETE FROM home_cache");
    }

    // Search history

    /** Remembers a search, only when "Save search history" is on. */
    public static void recordSearch(final String query) {
        if (!searchEnabled() || query == null || query.isBlank()) {
            return;
        }
        Db.tx(() -> {
            Db.update("DELETE FROM search_history WHERE query = ?", query.strip());
            Db.update("INSERT INTO search_history (query, searched_at) VALUES (?, ?)",
                    query.strip(), System.currentTimeMillis());
            return null;
        });
        Library.changed(Library.Topic.HISTORY);
    }

    public static List<String> recentSearches(final int limit) {
        return Db.query("SELECT query FROM search_history ORDER BY searched_at DESC LIMIT ?",
                rs -> rs.getString(1), limit);
    }

    public static void removeSearch(final String query) {
        Db.update("DELETE FROM search_history WHERE query = ?", query);
        Library.changed(Library.Topic.HISTORY);
    }

    public static long count() {
        return Db.count("SELECT COUNT(*) FROM watch_history")
                + Db.count("SELECT COUNT(*) FROM search_history");
    }
}
