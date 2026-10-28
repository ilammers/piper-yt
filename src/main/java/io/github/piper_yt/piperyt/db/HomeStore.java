package io.github.piper_yt.piperyt.db;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The homepage's local data: hidden videos, blocked channels, and the caches its
 * recommendations are computed from (related videos, discovered channels' uploads, the last
 * computed page).
 */
public final class HomeStore {
    public enum SeedSource { HISTORY, FAVORITE }

    /** A related video, and the video it was related to. */
    public record Related(String seedUrl, String seedTitle, SeedSource source, Video video) {
    }

    public record Blocked(String channelUrl, String name, long blockedAt) {
    }

    /** A channel seen in related videos, and how often. */
    public record ChannelCount(String channelUrl, String name, int count) {
    }

    /** A homepage entry as computed last time. */
    public record Cached(Video video, double score, String reason, String detail) {
    }

    private HomeStore() {
    }

    // "Not interested" and "Don't recommend channel"

    public static void hide(final String videoUrl) {
        Db.update("INSERT OR REPLACE INTO hidden_videos (video_url, hidden_at) VALUES (?, ?)",
                Video.canonicalUrl(videoUrl), System.currentTimeMillis());
        Db.update("DELETE FROM home_cache WHERE video_url = ?", Video.canonicalUrl(videoUrl));
        Library.changed(Library.Topic.HOME, Library.Topic.BLOCKLIST);
    }

    public static Set<String> hiddenUrls() {
        return new HashSet<>(Db.query("SELECT video_url FROM hidden_videos",
                rs -> rs.getString(1)));
    }

    public static long hiddenCount() {
        return Db.count("SELECT COUNT(*) FROM hidden_videos");
    }

    public static void clearHidden() {
        Db.update("DELETE FROM hidden_videos");
        Library.changed(Library.Topic.HOME, Library.Topic.BLOCKLIST);
    }

    public static void block(final String channelUrl, final String name) {
        final String url = Favorites.canonicalChannelUrl(channelUrl);
        Db.update("INSERT OR REPLACE INTO blocked_channels (channel_url, name, blocked_at) "
                + "VALUES (?, ?, ?)", url, name == null ? "" : name, System.currentTimeMillis());
        Db.update("DELETE FROM home_cache WHERE uploader_url = ?", url);
        Library.changed(Library.Topic.HOME, Library.Topic.BLOCKLIST);
    }

    public static void unblock(final String channelUrl) {
        Db.update("DELETE FROM blocked_channels WHERE channel_url = ?", channelUrl);
        Library.changed(Library.Topic.HOME, Library.Topic.BLOCKLIST);
    }

    public static List<Blocked> blocked() {
        return Db.query("SELECT * FROM blocked_channels ORDER BY name COLLATE NOCASE",
                rs -> new Blocked(rs.getString("channel_url"), rs.getString("name"),
                        rs.getLong("blocked_at")));
    }

    public static Set<String> blockedUrls() {
        return new HashSet<>(Db.query("SELECT channel_url FROM blocked_channels",
                rs -> rs.getString(1)));
    }

    // Related videos

    /** When related videos of {@code seedUrl} were fetched, or null if never. */
    public static Long relatedFetchedAt(final String seedUrl) {
        return Db.one("SELECT MAX(fetched_at) FROM related_cache WHERE seed_url = ?", rs -> {
            final long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }, seedUrl);
    }

    /** Replaces the related videos of a seed. History seeds are only stored with history on. */
    public static void storeRelated(final String seedUrl, final String seedTitle,
                                    final SeedSource source, final List<Video> related) {
        if (source == SeedSource.HISTORY && !History.enabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        Db.tx(() -> {
            Db.update("DELETE FROM related_cache WHERE seed_url = ?", seedUrl);
            if (related.isEmpty()) {
                // Remember the attempt so an empty result isn't refetched every time
                Db.update("INSERT INTO related_cache (seed_url, seed_title, seed_source, "
                        + "video_url, fetched_at) VALUES (?, ?, ?, '', ?)", seedUrl,
                        seedTitle, source.name().toLowerCase(), now);
            }
            for (final Video v : related) {
                final Object[] bind = v.bind();
                final Object[] args = new Object[bind.length + 4];
                args[0] = seedUrl;
                args[1] = seedTitle;
                args[2] = source.name().toLowerCase();
                System.arraycopy(bind, 0, args, 3, bind.length);
                args[args.length - 1] = now;
                Db.update("INSERT OR IGNORE INTO related_cache (seed_url, seed_title, seed_source, "
                        + Video.COLUMNS + ", fetched_at) VALUES (?, ?, ?, " + Video.PLACEHOLDERS
                        + ", ?)", args);
            }
            return null;
        });
    }

    /** Cached related videos; history seeds only when {@code includeHistory}. */
    public static List<Related> related(final boolean includeHistory) {
        return Db.query("SELECT * FROM related_cache WHERE video_url != ''"
                        + (includeHistory ? "" : " AND seed_source = 'favorite'"),
                rs -> new Related(rs.getString("seed_url"), rs.getString("seed_title"),
                        "history".equals(rs.getString("seed_source"))
                                ? SeedSource.HISTORY : SeedSource.FAVORITE, Video.read(rs)));
    }

    /** Drops related videos fetched before {@code before}. */
    public static void pruneRelated(final long before) {
        Db.update("DELETE FROM related_cache WHERE fetched_at < ?", before);
    }

    // Discover

    /**
     * Channels that turn up at least {@code min} times in related videos, aren't favorites and
     * aren't blocked, most frequent first.
     */
    public static List<ChannelCount> frequentRelatedChannels(final boolean includeHistory,
                                                             final int min, final int limit) {
        return Db.query("SELECT uploader_url, MAX(uploader), COUNT(DISTINCT video_url) AS n "
                        + "FROM related_cache WHERE video_url != '' AND uploader_url IS NOT NULL"
                        + (includeHistory ? "" : " AND seed_source = 'favorite'")
                        + " AND uploader_url NOT IN (SELECT url FROM favorites)"
                        + " AND uploader_url NOT IN (SELECT channel_url FROM blocked_channels)"
                        + " GROUP BY uploader_url HAVING n >= ? ORDER BY n DESC LIMIT ?",
                rs -> new ChannelCount(rs.getString(1), rs.getString(2), rs.getInt(3)),
                min, limit);
    }

    public static Long discoverFetchedAt(final String channelUrl) {
        return Db.one("SELECT MAX(fetched_at) FROM discover_cache WHERE channel_url = ?", rs -> {
            final long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }, channelUrl);
    }

    public static void storeDiscover(final String channelUrl, final List<Video> uploads) {
        final long now = System.currentTimeMillis();
        Db.tx(() -> {
            Db.update("DELETE FROM discover_cache WHERE channel_url = ?", channelUrl);
            for (final Video v : uploads) {
                final Object[] bind = v.bind();
                final Object[] args = new Object[bind.length + 2];
                args[0] = channelUrl;
                System.arraycopy(bind, 0, args, 1, bind.length);
                args[args.length - 1] = now;
                Db.update("INSERT OR IGNORE INTO discover_cache (channel_url, " + Video.COLUMNS
                        + ", fetched_at) VALUES (?, " + Video.PLACEHOLDERS + ", ?)", args);
            }
            return null;
        });
    }

    public static List<Video> discover() {
        return Db.query("SELECT * FROM discover_cache WHERE channel_url NOT IN "
                + "(SELECT url FROM favorites) AND channel_url NOT IN "
                + "(SELECT channel_url FROM blocked_channels)", Video::read);
    }

    public static void clearDiscover() {
        Db.update("DELETE FROM discover_cache");
    }

    // The computed homepage

    public static void saveHome(final List<Cached> picks) {
        final long now = System.currentTimeMillis();
        Db.tx(() -> {
            Db.update("DELETE FROM home_cache");
            for (int i = 0; i < picks.size(); i++) {
                final Cached p = picks.get(i);
                final Object[] bind = p.video().bind();
                final Object[] args = new Object[bind.length + 5];
                args[0] = i;
                System.arraycopy(bind, 0, args, 1, bind.length);
                args[bind.length + 1] = p.score();
                args[bind.length + 2] = p.reason();
                args[bind.length + 3] = p.detail();
                args[bind.length + 4] = now;
                Db.update("INSERT INTO home_cache (position, " + Video.COLUMNS + ", score, reason,"
                        + " detail, computed_at) VALUES (?, " + Video.PLACEHOLDERS
                        + ", ?, ?, ?, ?)", args);
            }
            return null;
        });
        Library.changed(Library.Topic.HOME);
    }

    public static List<Cached> loadHome() {
        return Db.query("SELECT * FROM home_cache ORDER BY position", rs -> new Cached(
                Video.read(rs), rs.getDouble("score"), rs.getString("reason"),
                rs.getString("detail")));
    }

    /** When the homepage was last computed, or 0. */
    public static long homeComputedAt() {
        return Db.count("SELECT COALESCE(MAX(computed_at), 0) FROM home_cache");
    }
}
