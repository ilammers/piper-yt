package io.github.piper_yt.piperyt.rec;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.rec.Scorer.Candidate;
import io.github.piper_yt.piperyt.rec.Scorer.Pick;
import io.github.piper_yt.piperyt.rec.Scorer.Signals;
import io.github.piper_yt.piperyt.rec.Scorer.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final RecWeights W = RecWeights.DEFAULT;

    private static Video video(final String id, final String channel, final long ageHours) {
        return new Video(0, "https://www.youtube.com/watch?v=" + id, "Video " + id,
                "Channel " + channel, "https://www.youtube.com/channel/UC" + channel, null, 300,
                "VIDEO_STREAM", false, NOW - ageHours * HOUR, 100);
    }

    private static String channel(final String c) {
        return "https://www.youtube.com/channel/UC" + c;
    }

    private static Signals signals(final Set<String> favorites) {
        return new Signals(favorites, Map.of(), false, Set.of(), Set.of(), Set.of(), false);
    }

    private static List<String> ids(final List<Pick> picks) {
        return picks.stream().map(p -> Video.youtubeId(p.video().url())).toList();
    }

    @Test
    void newerUploadsRankHigher() {
        final List<Pick> picks = Scorer.rank(List.of(
                new Candidate(video("old00000000", "a", 200), Source.FAVORITE, null),
                new Candidate(video("new00000000", "b", 2), Source.FAVORITE, null)),
                signals(Set.of(channel("a"), channel("b"))), W, NOW);
        assertEquals(List.of("new00000000", "old00000000"), ids(picks));
    }

    @Test
    void recencyHalvesEveryHalfLife() {
        final double fresh = Scorer.recency(NOW, NOW, W);
        final double halfLife = Scorer.recency(NOW - (long) (W.recencyHalfLifeHours() * HOUR),
                NOW, W);
        assertEquals(1.0, fresh, 1e-9);
        assertEquals(0.5, halfLife, 1e-9);
        assertEquals(W.unknownAgeRecency(), Scorer.recency(null, NOW, W), 1e-9);
    }

    @Test
    void favoritesBeatRelatedOfTheSameAge() {
        final List<Pick> picks = Scorer.rank(List.of(
                new Candidate(video("rel00000000", "r", 10), Source.RELATED, "Seed"),
                new Candidate(video("fav00000000", "f", 10), Source.FAVORITE, null)),
                signals(Set.of(channel("f"))), W, NOW);
        assertEquals("fav00000000", ids(picks).get(0));
        assertEquals("Related to Seed", picks.get(1).reason());
        assertEquals("From Favorites", picks.get(0).reason());
    }

    @Test
    void oneChannelDoesNotDominate() {
        final List<Candidate> candidates = new ArrayList<>();
        // A prolific favorite with 10 fresh uploads, and an older upload from another favorite
        for (int i = 0; i < 10; i++) {
            candidates.add(new Candidate(video("busy0000000" + i, "busy", 1 + i), Source.FAVORITE,
                    null));
        }
        candidates.add(new Candidate(video("quiet000000", "quiet", 30), Source.FAVORITE, null));
        final List<Pick> picks = Scorer.rank(candidates,
                signals(Set.of(channel("busy"), channel("quiet"))), W, NOW);
        assertTrue(ids(picks).subList(0, 4).contains("quiet000000"),
                "the quiet channel should surface near the top: " + ids(picks));
        assertEquals(11, picks.size());
    }

    @Test
    void withoutPenaltyBusyChannelWouldWin() {
        final RecWeights noPenalty = new RecWeights(W.sourceFavorite(), W.sourceRelated(),
                W.sourceDiscover(), W.multiSourceBonus(), W.recency(), W.recencyHalfLifeHours(),
                W.unknownAgeRecency(), W.favoriteChannel(), W.watchCount(),
                W.watchCountSaturation(), W.completion(), 0, W.maxItems(),
                W.favoriteWindowDays(), W.maxRelatedFetches(), W.relatedTtlHours(),
                W.maxDiscoverChannels(), W.discoverMinAppearances(), W.discoverTtlHours());
        final List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(new Candidate(video("busy0000000" + i, "busy", 1 + i), Source.FAVORITE,
                    null));
        }
        candidates.add(new Candidate(video("quiet000000", "quiet", 30), Source.FAVORITE, null));
        final List<Pick> picks = Scorer.rank(candidates,
                signals(Set.of(channel("busy"), channel("quiet"))), noPenalty, NOW);
        assertEquals("quiet000000", ids(picks).get(5));
    }

    @Test
    void historyAffinityOnlyCountsWithHistoryOn() {
        final Map<String, History.ChannelStats> stats =
                Map.of(channel("loved"), new History.ChannelStats(20, 0.95));
        final List<Candidate> candidates = List.of(
                new Candidate(video("loved000000", "loved", 20), Source.RELATED, "S"),
                new Candidate(video("other000000", "other", 10), Source.RELATED, "S"));

        final List<Pick> on = Scorer.rank(candidates, new Signals(Set.of(), stats, true,
                Set.of(), Set.of(), Set.of(), false), W, NOW);
        assertEquals("loved000000", ids(on).get(0));

        // History off: the stats are ignored even if passed, so recency decides
        final List<Pick> off = Scorer.rank(candidates, new Signals(Set.of(), stats, false,
                Set.of(), Set.of(), Set.of(), false), W, NOW);
        assertEquals("other000000", ids(off).get(0));
        assertTrue(off.stream().noneMatch(p -> p.detail().contains("you watched")));
    }

    @Test
    void filtersWatchedHiddenBlockedAndShorts() {
        final Video watched = video("watched0000", "a", 1);
        final Video hidden = video("hidden00000", "a", 1);
        final Video blocked = video("blocked0000", "blk", 1);
        final Video shortVideo = new Video(0, "https://www.youtube.com/watch?v=short000000",
                "S", "A", channel("a"), null, 30, "VIDEO_STREAM", true, NOW, 1);
        final Video kept = video("kept0000000", "a", 1);
        final List<Candidate> candidates = List.of(
                new Candidate(watched, Source.FAVORITE, null),
                new Candidate(hidden, Source.FAVORITE, null),
                new Candidate(blocked, Source.RELATED, "S"),
                new Candidate(shortVideo, Source.FAVORITE, null),
                new Candidate(kept, Source.FAVORITE, null));
        final List<Pick> picks = Scorer.rank(candidates, new Signals(Set.of(channel("a")),
                Map.of(), true, Set.of(watched.url()), Set.of(hidden.url()),
                Set.of(channel("blk")), true), W, NOW);
        assertEquals(List.of("kept0000000"), ids(picks));
    }

    @Test
    void watchedIsNotFilteredWithHistoryOff() {
        final Video v = video("vid00000000", "a", 1);
        final List<Pick> picks = Scorer.rank(List.of(new Candidate(v, Source.FAVORITE, null)),
                new Signals(Set.of(), Map.of(), false, Set.of(), Set.of(), Set.of(), false), W,
                NOW);
        assertEquals(1, picks.size());
    }

    @Test
    void duplicatesMergeAndGetABonus() {
        final Video v = video("dup00000000", "a", 5);
        final Video other = video("solo0000000", "b", 5);
        final List<Pick> picks = Scorer.rank(List.of(
                new Candidate(v, Source.RELATED, "Seed one"),
                new Candidate(v, Source.FAVORITE, null),
                new Candidate(other, Source.FAVORITE, null)),
                signals(Set.of(channel("a"), channel("b"))), W, NOW);
        assertEquals(2, picks.size());
        assertEquals("dup00000000", ids(picks).get(0));
        assertEquals("From Favorites", picks.get(0).reason());
        assertTrue(picks.get(0).detail().contains("2 sources"));
    }

    @Test
    void respectsMaxItems() {
        final List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < W.maxItems() + 20; i++) {
            candidates.add(new Candidate(video(String.format("v%010d", i), "c" + i, i),
                    Source.RELATED, "S"));
        }
        assertEquals(W.maxItems(), Scorer.rank(candidates, signals(Set.of()), W, NOW).size());
    }
}
