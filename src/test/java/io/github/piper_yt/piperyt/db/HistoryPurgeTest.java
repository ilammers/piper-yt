package io.github.piper_yt.piperyt.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryPurgeTest {
    private static final long HOUR = 3600_000L;

    @BeforeEach
    void open() throws Exception {
        TestLibrary.open();
    }

    @AfterEach
    void close() {
        Db.close();
    }

    /** History plus everything derived from it, the old part a week back. */
    private static void fill() {
        final long now = System.currentTimeMillis();
        final Video old = TestLibrary.video("oldoldold01", "aaa");
        final Video fresh = TestLibrary.video("newnewnew01", "bbb");
        History.recordWatch(old);
        Db.update("UPDATE watch_history SET watched_at = ? WHERE video_url = ?", now - 8 * 24 * HOUR,
                old.url());
        final long id = History.recordWatch(fresh);
        History.updateProgress(id, 300_000, 600);
        Settings.set(Settings.SAVE_SEARCH_HISTORY, true);
        History.recordSearch("recent search");

        HomeStore.storeRelated(old.url(), old.title(), HomeStore.SeedSource.HISTORY,
                List.of(TestLibrary.video("relold00001", "ccc")));
        HomeStore.storeRelated(fresh.url(), fresh.title(), HomeStore.SeedSource.HISTORY,
                List.of(TestLibrary.video("relnew00001", "ddd")));
        final Video upload = TestLibrary.video("favupload01", "eee");
        HomeStore.storeRelated(upload.url(), upload.title(), HomeStore.SeedSource.FAVORITE,
                List.of(TestLibrary.video("relfav00001", "fff")));
        HomeStore.storeDiscover("https://www.youtube.com/channel/UCddd",
                List.of(TestLibrary.video("disc0000001", "ddd")));
        HomeStore.saveHome(List.of(new HomeStore.Cached(TestLibrary.video("relnew00001", "ddd"),
                1.0, "Related to " + fresh.title(), "")));
        HomeStore.hide(TestLibrary.video("hidden00001", "ggg").url());
        HomeStore.block("https://www.youtube.com/channel/UChhh", "H");
    }

    @Test
    void clearingAllTimeLeavesNothingDerivedFromHistory() {
        fill();
        History.clear(History.Range.ALL.since(System.currentTimeMillis()), false);

        assertEquals(0, Db.count("SELECT COUNT(*) FROM watch_history"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM search_history"));
        assertTrue(History.channelStats().isEmpty(), "watch counts survived");
        assertTrue(History.watchedUrls().isEmpty());
        assertEquals(0, History.resumePosition("https://www.youtube.com/watch?v=newnewnew01"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM related_cache WHERE seed_source = "
                + "'history'"), "related videos of watched videos survived");
        assertEquals(0, Db.count("SELECT COUNT(*) FROM discover_cache"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM home_cache"));
        // Not history: related videos of favorites' uploads, and the user's own blocklist
        assertEquals(1, Db.count("SELECT COUNT(*) FROM related_cache WHERE seed_source = "
                + "'favorite'"));
        assertEquals(1, HomeStore.hiddenCount());
        assertEquals(1, HomeStore.blocked().size());
    }

    @Test
    void clearingWithBlocklistAlsoClearsHiddenAndBlocked() {
        fill();
        History.clear(0, true);
        assertEquals(0, HomeStore.hiddenCount());
        assertEquals(0, HomeStore.blocked().size());
    }

    @Test
    void clearingLastHourKeepsOlderHistoryAndItsDerivedData() {
        fill();
        History.clear(History.Range.HOUR.since(System.currentTimeMillis()), false);

        assertEquals(List.of("https://www.youtube.com/watch?v=oldoldold01"),
                History.list("", 10).stream().map(e -> e.video().url()).toList());
        assertEquals(0, Db.count("SELECT COUNT(*) FROM search_history"));
        // The fresh watch's related videos go, the week-old one's stay
        assertEquals(List.of("https://www.youtube.com/watch?v=oldoldold01"),
                Db.query("SELECT DISTINCT seed_url FROM related_cache WHERE seed_source = "
                        + "'history'", rs -> rs.getString(1)));
        assertEquals(1, History.channelStats().get("https://www.youtube.com/channel/UCaaa")
                .watchCount());
        assertEquals(null, History.channelStats().get("https://www.youtube.com/channel/UCbbb"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM home_cache"));
    }

    @Test
    void removingOneVideoRemovesItsDerivedData() {
        fill();
        History.remove("https://youtu.be/newnewnew01");
        assertEquals(1, History.list("", 10).size());
        assertEquals(0, Db.count("SELECT COUNT(*) FROM related_cache WHERE seed_url = ?",
                "https://www.youtube.com/watch?v=newnewnew01"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM home_cache"));
    }

    @Test
    void historyOffWritesNothing() {
        Settings.set(Settings.SAVE_WATCH_HISTORY, false);
        final Video v = TestLibrary.video("offoffoff01", "aaa");
        final long id = History.recordWatch(v);
        History.updateProgress(id, 100_000, 600);
        History.updateProgress(1, 100_000, 600);
        History.recordSearch("anything");
        HomeStore.storeRelated(v.url(), v.title(), HomeStore.SeedSource.HISTORY,
                List.of(TestLibrary.video("rel00000001", "bbb")));

        assertEquals(-1, id);
        assertEquals(0, Db.count("SELECT COUNT(*) FROM watch_history"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM search_history"));
        assertEquals(0, Db.count("SELECT COUNT(*) FROM related_cache"));
        assertEquals(0, History.resumePosition(v.url()));
    }

    @Test
    void historyOffHidesKeptHistoryFromResume() {
        final Video v = TestLibrary.video("keptkept001", "aaa");
        History.updateProgress(History.recordWatch(v), 100_000, 600);
        assertEquals(100_000, History.resumePosition(v.url()));
        Settings.set(Settings.SAVE_WATCH_HISTORY, false);
        assertEquals(0, History.resumePosition(v.url()));
    }

    @Test
    void searchHistoryIsOffByDefault() {
        History.recordSearch("not saved");
        assertEquals(0, Db.count("SELECT COUNT(*) FROM search_history"));
    }
}
