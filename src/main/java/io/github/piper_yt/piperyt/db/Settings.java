package io.github.piper_yt.piperyt.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Library settings, kept in the {@code settings} table so they travel with the JSON export.
 * The network/privacy route stays in {@code NetworkSettings} ({@code java.util.prefs}), which
 * the proxy layer reads for every connection.
 *
 * <p>Defaults are privacy-first: watch history is on (it powers resume and the homepage, and
 * never leaves the machine), search history is off, "discover" is off.
 */
public final class Settings {
    /** A setting and its default. */
    public record Key<T>(String name, T fallback) {
    }

    public static final Key<Boolean> SAVE_WATCH_HISTORY = new Key<>("history.watch", true);
    public static final Key<Boolean> SAVE_SEARCH_HISTORY = new Key<>("history.search", false);
    public static final Key<Boolean> RESUME_PLAYBACK = new Key<>("history.resume", true);

    /** Plays the first Up next video when one ends and nothing is queued. */
    public static final Key<Boolean> AUTOPLAY = new Key<>("player.autoplay", true);
    /** Keeps a playing video going in a corner when you leave its page. */
    public static final Key<Boolean> MINI_PLAYER = new Key<>("player.mini", true);
    /** The window corner the mini player snaps to, e.g. "bottom-right". */
    public static final Key<String> MINI_PLAYER_CORNER =
            new Key<>("player.mini_corner", "bottom-right");
    /** Caption languages to load when playing, e.g. "en, de". Blank: no subtitles. */
    public static final Key<String> PLAYER_SUB_LANGS = new Key<>("player.sub_langs", "");

    public static final Key<Integer> FEED_INTERVAL_MIN = new Key<>("feed.interval_min", 90);
    public static final Key<Integer> FEED_GAP_MIN_S = new Key<>("feed.gap_min_s", 2);
    public static final Key<Integer> FEED_GAP_MAX_S = new Key<>("feed.gap_max_s", 20);
    public static final Key<Integer> FEED_BATCH_SIZE = new Key<>("feed.batch_size", 12);
    public static final Key<Integer> FEED_BATCH_PAUSE_S = new Key<>("feed.batch_pause_s", 90);
    public static final Key<Boolean> FEED_HIDE_SHORTS = new Key<>("feed.hide_shorts", false);
    public static final Key<Boolean> FEED_HIDE_LIVE = new Key<>("feed.hide_live", false);
    public static final Key<Boolean> FEED_HIDE_WATCHED = new Key<>("feed.hide_watched", false);
    /** RSS can't tell livestreams; this also fetches each channel's Live tab. Off: one request. */
    public static final Key<Boolean> FEED_DETECT_LIVE = new Key<>("feed.detect_live", false);

    public static final Key<Boolean> HOME_DISCOVER = new Key<>("home.discover", false);
    public static final Key<Integer> HOME_TTL_MIN = new Key<>("home.ttl_min", 180);
    public static final Key<Boolean> HOME_HIDE_SHORTS = new Key<>("home.hide_shorts", true);

    public static final Key<String> DOWNLOAD_DIR = new Key<>("download.dir", "");
    public static final Key<String> DOWNLOAD_PRESET = new Key<>("download.preset", "1080p");
    public static final Key<String> DOWNLOAD_CONTAINER = new Key<>("download.container", "mkv");
    public static final Key<String> DOWNLOAD_CODEC = new Key<>("download.codec", "any");
    public static final Key<String> DOWNLOAD_AUDIO_FORMAT =
            new Key<>("download.audio_format", "mp3"); // plays and imports everywhere
    public static final Key<Integer> DOWNLOAD_CONCURRENCY = new Key<>("download.concurrency", 2);
    public static final Key<String> DOWNLOAD_SPONSORBLOCK =
            new Key<>("download.sponsorblock", "mark");
    public static final Key<Boolean> DOWNLOAD_SUBTITLES = new Key<>("download.subtitles", false);
    public static final Key<String> DOWNLOAD_SUB_LANGS = new Key<>("download.sub_langs", "en.*");

    /** SponsorBlock categories (API names), for playback skipping and for downloads. */
    public static final Key<String> SPONSORBLOCK_CATEGORIES =
            new Key<>("sponsorblock.categories", "sponsor,selfpromo,interaction");

    /** Text zoom in percent (Ctrl+plus/minus). */
    public static final Key<Integer> UI_ZOOM = new Key<>("ui.zoom_percent", 100);

    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private Settings() {
    }

    public static boolean get(final Key<Boolean> key) {
        final String v = raw(key.name());
        return v == null ? key.fallback() : Boolean.parseBoolean(v);
    }

    public static int getInt(final Key<Integer> key) {
        final String v = raw(key.name());
        try {
            return v == null ? key.fallback() : Integer.parseInt(v);
        } catch (final NumberFormatException e) {
            return key.fallback();
        }
    }

    public static String getString(final Key<String> key) {
        final String v = raw(key.name());
        return v == null ? key.fallback() : v;
    }

    public static <T> void set(final Key<T> key, final T value) {
        Db.update("INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value", key.name(),
                String.valueOf(value));
        for (final Consumer<String> l : LISTENERS) {
            l.accept(key.name());
        }
    }

    /** Called with a setting's name after it changes, on the thread that changed it. */
    public static void addListener(final Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static Map<String, String> all() {
        final Map<String, String> map = new LinkedHashMap<>();
        Db.query("SELECT key, value FROM settings ORDER BY key",
                rs -> map.put(rs.getString(1), rs.getString(2)));
        return map;
    }

    static void putRaw(final String key, final String value) {
        Db.update("INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
    }

    private static String raw(final String key) {
        if (!Db.isOpen()) {
            return null;
        }
        return Db.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), key);
    }

    // Typed helpers for settings with structure

    public static List<String> sponsorBlockCategories() {
        final List<String> list = new ArrayList<>();
        for (final String c : getString(SPONSORBLOCK_CATEGORIES).split(",")) {
            if (!c.isBlank()) {
                list.add(c.strip());
            }
        }
        return list;
    }

    public static void setSponsorBlockCategories(final List<String> categories) {
        set(SPONSORBLOCK_CATEGORIES, String.join(",", categories));
    }

    /** The configured download folder, defaulting to ~/Videos/Piper-YT (XDG videos dir). */
    public static Path downloadDir() {
        final String configured = getString(DOWNLOAD_DIR);
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return xdgVideosDir().resolve("Piper-YT");
    }

    static Path xdgVideosDir() {
        final Path home = Path.of(System.getProperty("user.home"));
        final Path dirs = home.resolve(".config/user-dirs.dirs");
        try {
            for (final String line : Files.readAllLines(dirs)) {
                if (line.startsWith("XDG_VIDEOS_DIR=")) {
                    final String v = line.substring("XDG_VIDEOS_DIR=".length())
                            .replace("\"", "").replace("$HOME", home.toString());
                    if (!v.isBlank()) {
                        return Path.of(v);
                    }
                }
            }
        } catch (final Exception ignored) {
            // No user-dirs file: use the default
        }
        return home.resolve("Videos");
    }

    /** SponsorBlock's API names with labels, in display order. */
    public static final Map<String, String> SPONSORBLOCK_LABELS = new LinkedHashMap<>();

    static {
        SPONSORBLOCK_LABELS.put("sponsor", "Sponsors");
        SPONSORBLOCK_LABELS.put("selfpromo", "Self-promotion");
        SPONSORBLOCK_LABELS.put("interaction", "Interaction reminders");
        SPONSORBLOCK_LABELS.put("intro", "Intros");
        SPONSORBLOCK_LABELS.put("outro", "Endcards and credits");
        SPONSORBLOCK_LABELS.put("preview", "Previews and recaps");
        SPONSORBLOCK_LABELS.put("filler", "Filler tangents");
        SPONSORBLOCK_LABELS.put("music_offtopic", "Non-music in music videos");
    }

    public static boolean isKnownCategory(final String c) {
        return SPONSORBLOCK_LABELS.containsKey(c);
    }
}
