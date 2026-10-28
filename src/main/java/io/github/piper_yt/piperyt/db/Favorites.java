package io.github.piper_yt.piperyt.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Favorite channels (local subscriptions) and their optional groups. */
public final class Favorites {
    public enum Sort {
        NAME("Name"), LAST_UPLOAD("Last upload"), DATE_ADDED("Date added");

        public final String label;

        Sort(final String label) {
            this.label = label;
        }
    }

    public record Favorite(long id, int serviceId, String url, String name, String avatarUrl,
                           String description, long subscriberCount, long addedAt,
                           Long lastUploadAt, Long lastRefreshedAt, String refreshError) {
    }

    public record Group(long id, String name, int position, int memberCount) {
    }

    private static final String SELECT = "SELECT id, service_id, url, name, avatar_url, "
            + "description, subscriber_count, added_at, last_upload_at, last_refreshed_at, "
            + "refresh_error FROM favorites";

    private Favorites() {
    }

    /**
     * One URL per channel, so a favorite added from a video page matches the channel page.
     * The extractor's canonical form is {@code https://www.youtube.com/channel/UC…}.
     */
    public static String canonicalChannelUrl(final String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        u = u.replaceFirst("^http://", "https://")
                .replaceFirst("^https://(m\\.|music\\.)?youtube\\.com/", "https://www.youtube.com/");
        final int query = u.indexOf('?');
        if (query > 0) {
            u = u.substring(0, query);
        }
        // A trailing tab ("/videos") is still the channel
        u = u.replaceFirst("(/channel/[A-Za-z0-9_-]+)/.*$", "$1");
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static boolean isFavorite(final String channelUrl) {
        if (channelUrl == null || !Db.isOpen()) {
            return false;
        }
        return Db.count("SELECT COUNT(*) FROM favorites WHERE url = ?",
                canonicalChannelUrl(channelUrl)) > 0;
    }

    /** Adds or refreshes a favorite; returns its id. */
    public static long add(final int serviceId, final String url, final String name,
                           final String avatarUrl, final long subscriberCount,
                           final String description) {
        final String canonical = canonicalChannelUrl(url);
        final long id = Db.tx(() -> {
            Db.update("INSERT INTO favorites (service_id, url, name, avatar_url, subscriber_count,"
                            + " description, added_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (service_id, url) DO UPDATE SET "
                            + "name = CASE WHEN excluded.name = '' THEN name ELSE excluded.name END,"
                            + " avatar_url = COALESCE(excluded.avatar_url, avatar_url),"
                            + " subscriber_count = CASE WHEN excluded.subscriber_count < 0"
                            + " THEN subscriber_count ELSE excluded.subscriber_count END,"
                            + " description = COALESCE(excluded.description, description)",
                    serviceId, canonical, name == null ? "" : name, avatarUrl, subscriberCount,
                    description, System.currentTimeMillis());
            return Db.one("SELECT id FROM favorites WHERE service_id = ? AND url = ?",
                    rs -> rs.getLong(1), serviceId, canonical);
        });
        Library.changed(Library.Topic.FAVORITES);
        return id;
    }

    /** Removes the favorite, its groups membership and its cached feed items. */
    public static void remove(final String channelUrl) {
        Db.update("DELETE FROM favorites WHERE url = ?", canonicalChannelUrl(channelUrl));
        Library.changed(Library.Topic.FAVORITES, Library.Topic.FEED, Library.Topic.HOME);
    }

    public static Favorite byUrl(final String channelUrl) {
        return Db.one(SELECT + " WHERE url = ?", Favorites::read,
                canonicalChannelUrl(channelUrl));
    }

    public static List<Favorite> list() {
        return Db.query(SELECT, Favorites::read);
    }

    /** Favorites matching {@code filter} (by name), in {@code group} if not null, sorted. */
    public static List<Favorite> list(final String filter, final Long group, final Sort sort) {
        final List<Favorite> all = group == null ? list()
                : Db.query(SELECT + " WHERE id IN (SELECT favorite_id FROM "
                + "favorite_group_members WHERE group_id = ?)", Favorites::read, group);
        final String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        final List<Favorite> result = new ArrayList<>();
        for (final Favorite f : all) {
            if (needle.isEmpty() || f.name().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(f);
            }
        }
        result.sort(switch (sort) {
            case NAME -> Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT));
            case LAST_UPLOAD -> Comparator.comparing((Favorite f) ->
                    f.lastUploadAt() == null ? 0 : f.lastUploadAt()).reversed();
            case DATE_ADDED -> Comparator.comparingLong(Favorite::addedAt).reversed();
        });
        return result;
    }

    /** Favorites the feed can fetch (YouTube only; other services' are kept for export). */
    public static List<Favorite> refreshable() {
        return Db.query(SELECT + " WHERE service_id = ?", Favorites::read, Video.YOUTUBE);
    }

    /** Points a favorite at its canonical URL (a handle URL resolved to the channel ID). */
    public static void replaceUrl(final long id, final String url) {
        final String canonical = canonicalChannelUrl(url);
        if (Db.count("SELECT COUNT(*) FROM favorites WHERE url = ?", canonical) == 0) {
            Db.update("UPDATE favorites SET url = ? WHERE id = ?", canonical, id);
        }
    }

    static void markRefreshed(final long id, final String error) {
        Db.update("UPDATE favorites SET last_refreshed_at = ?, refresh_error = ? WHERE id = ?",
                System.currentTimeMillis(), error, id);
    }

    // Groups

    public static List<Group> groups() {
        return Db.query("SELECT g.id, g.name, g.position, COUNT(m.favorite_id) "
                + "FROM favorite_groups g LEFT JOIN favorite_group_members m ON m.group_id = g.id "
                + "GROUP BY g.id ORDER BY g.position, g.name COLLATE NOCASE",
                rs -> new Group(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
    }

    public static long createGroup(final String name) {
        final long id = Db.insert("INSERT INTO favorite_groups (name, position) VALUES (?, "
                + "(SELECT COALESCE(MAX(position) + 1, 0) FROM favorite_groups))", name.strip());
        Library.changed(Library.Topic.FAVORITES);
        return id;
    }

    public static void renameGroup(final long id, final String name) {
        Db.update("UPDATE favorite_groups SET name = ? WHERE id = ?", name.strip(), id);
        Library.changed(Library.Topic.FAVORITES);
    }

    public static void deleteGroup(final long id) {
        Db.update("DELETE FROM favorite_groups WHERE id = ?", id);
        Library.changed(Library.Topic.FAVORITES);
    }

    public static List<Long> groupsOf(final long favoriteId) {
        return Db.query("SELECT group_id FROM favorite_group_members WHERE favorite_id = ?",
                rs -> rs.getLong(1), favoriteId);
    }

    public static void setInGroup(final long favoriteId, final long groupId, final boolean in) {
        if (in) {
            Db.update("INSERT OR IGNORE INTO favorite_group_members (group_id, favorite_id) "
                    + "VALUES (?, ?)", groupId, favoriteId);
        } else {
            Db.update("DELETE FROM favorite_group_members WHERE group_id = ? AND favorite_id = ?",
                    groupId, favoriteId);
        }
        Library.changed(Library.Topic.FAVORITES, Library.Topic.FEED);
    }

    private static Favorite read(final ResultSet rs) throws SQLException {
        return new Favorite(rs.getLong("id"), rs.getInt("service_id"), rs.getString("url"),
                rs.getString("name"), rs.getString("avatar_url"), rs.getString("description"),
                rs.getLong("subscriber_count"), rs.getLong("added_at"),
                Db.getLong(rs, "last_upload_at"), Db.getLong(rs, "last_refreshed_at"),
                rs.getString("refresh_error"));
    }
}
