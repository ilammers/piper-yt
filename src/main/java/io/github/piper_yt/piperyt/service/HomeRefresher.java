package io.github.piper_yt.piperyt.service;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Feed;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.HomeStore;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.net.Pacer;
import io.github.piper_yt.piperyt.rec.RecWeights;
import io.github.piper_yt.piperyt.rec.Scorer;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Builds the homepage. Gathering candidates is the only part that touches the network, and
 * all of it goes through {@link Pacer} (jittered, one at a time): related videos of a few
 * seeds (recently watched videos with history on, and favorites' newest uploads), capped per
 * refresh and cached, and with "discover" on, the RSS feeds of channels that keep turning up
 * in them. Scoring ({@link Scorer}) is local.
 */
public final class HomeRefresher {
    /** For the homepage's status line. */
    public record State(boolean running, String message) {
    }

    private static final List<Consumer<State>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile State state = new State(false, null);
    /** The scoring knobs; see {@link RecWeights}. */
    private static final RecWeights WEIGHTS = RecWeights.DEFAULT;

    private HomeRefresher() {
    }

    public static State state() {
        return state;
    }

    public static Runnable listen(final Consumer<State> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Whether the cached homepage is older than the configured TTL (or missing). */
    public static boolean isStale() {
        final long ttl = Settings.getInt(Settings.HOME_TTL_MIN) * 60_000L;
        return System.currentTimeMillis() - HomeStore.homeComputedAt() > ttl;
    }

    /** Re-ranks from what's cached, without fetching (after hiding or blocking something). */
    public static void rescore() {
        Thread.ofVirtual().start(HomeRefresher::computeAndSave);
    }

    /** Fetches new candidates (paced) and re-ranks, unless a refresh is running. */
    public static synchronized void refresh() {
        if (state.running()) {
            return;
        }
        publish(new State(true, null));
        Thread.ofVirtual().name("home-refresh").start(() -> {
            String message = null;
            try {
                // Show what's cached right away, then improve it
                computeAndSave();
                message = gather();
                computeAndSave();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                e.printStackTrace();
                message = "Couldn't refresh: " + e.getMessage();
            } finally {
                publish(new State(false, message));
            }
        });
    }

    /** Fetches related videos and discover uploads that are missing or stale. */
    private static String gather() throws InterruptedException {
        final RecWeights w = WEIGHTS;
        final long now = System.currentTimeMillis();
        final boolean history = History.enabled();
        final long relatedTtl = w.relatedTtlHours() * 3_600_000L;

        // Seeds: alternate recently watched and favorites' newest, skipping fresh ones
        final List<Seed> watched = new ArrayList<>();
        if (history) {
            for (final Video v : History.recentVideos(12)) {
                watched.add(new Seed(v, HomeStore.SeedSource.HISTORY));
            }
        }
        final List<Seed> uploads = new ArrayList<>();
        for (final Video v : Feed.recent(now - 7L * 86_400_000, 12)) {
            if (!v.isShort()) {
                uploads.add(new Seed(v, HomeStore.SeedSource.FAVORITE));
            }
        }
        final List<Seed> seeds = new ArrayList<>();
        for (int i = 0; seeds.size() < w.maxRelatedFetches()
                && (i < watched.size() || i < uploads.size()); i++) {
            for (final List<Seed> list : List.of(watched, uploads)) {
                if (i < list.size() && seeds.size() < w.maxRelatedFetches()) {
                    final Long at = HomeStore.relatedFetchedAt(list.get(i).video().url());
                    if (at == null || now - at > relatedTtl) {
                        seeds.add(list.get(i));
                    }
                }
            }
        }

        final Pacer.Plan plan = new Pacer.Plan(Settings.getInt(Settings.FEED_GAP_MIN_S),
                Settings.getInt(Settings.FEED_GAP_MAX_S), 100, 0, true);
        final Pacer.Result related = Pacer.run(seeds, plan, HomeRefresher::fetchRelated,
                new Pacer.Progress<>() {
                }, () -> false);
        if (related.blocked()) {
            return "YouTube is bot-checking this exit; showing what's cached";
        }
        HomeStore.pruneRelated(now - relatedTtl * 4);

        if (Settings.get(Settings.HOME_DISCOVER)) {
            final long discoverTtl = w.discoverTtlHours() * 3_600_000L;
            final List<HomeStore.ChannelCount> channels = new ArrayList<>();
            for (final HomeStore.ChannelCount c : HomeStore.frequentRelatedChannels(history,
                    w.discoverMinAppearances(), w.maxDiscoverChannels() * 3)) {
                final Long at = HomeStore.discoverFetchedAt(c.channelUrl());
                if ((at == null || now - at > discoverTtl) && c.channelUrl().contains("/channel/")
                        && channels.size() < w.maxDiscoverChannels()) {
                    channels.add(c);
                }
            }
            final Pacer.Result discover = Pacer.run(channels, plan, c -> {
                final List<Video> videos = new ArrayList<>();
                for (final InfoItem item : FeedInfo.getInfo(Extractor.youtube(),
                        c.channelUrl()).getRelatedItems()) {
                    if (item instanceof StreamInfoItem s) {
                        videos.add(Video.from(s));
                    }
                }
                HomeStore.storeDiscover(c.channelUrl(), videos);
            }, new Pacer.Progress<>() {
            }, () -> false);
            if (discover.blocked()) {
                return "YouTube is bot-checking this exit; showing what's cached";
            }
        } else {
            HomeStore.clearDiscover();
        }
        return related.failed() > 0 ? related.failed() + " related-video fetches failed" : null;
    }

    private record Seed(Video video, HomeStore.SeedSource source) {
    }

    /** The video page's related list, without the stream extraction StreamInfo would add. */
    private static void fetchRelated(final Seed seed) throws Exception {
        final StreamExtractor extractor = Extractor.youtube().getStreamExtractor(
                seed.video().url());
        extractor.fetchPage();
        final List<Video> related = new ArrayList<>();
        final InfoItemsCollector<? extends InfoItem, ?> items = extractor.getRelatedItems();
        if (items != null) {
            for (final InfoItem item : items.getItems()) {
                if (item instanceof StreamInfoItem s && Extractor.isWatchable(s)) {
                    related.add(Video.from(s));
                }
            }
        }
        HomeStore.storeRelated(seed.video().url(), seed.video().title(), seed.source(), related);
    }

    /** Ranks the cached candidates and saves the result as the homepage. */
    static void computeAndSave() {
        HomeStore.saveHome(compute(System.currentTimeMillis()).stream()
                .map(p -> new HomeStore.Cached(p.video(), p.score(), p.reason(), p.detail()))
                .toList());
    }

    static List<Scorer.Pick> compute(final long now) {
        final RecWeights w = WEIGHTS;
        final boolean history = History.enabled();
        final List<Scorer.Candidate> candidates = new ArrayList<>();
        for (final Video v : Feed.recent(now - w.favoriteWindowDays() * 86_400_000L, 400)) {
            candidates.add(new Scorer.Candidate(v, Scorer.Source.FAVORITE, null));
        }
        // With history off, related videos of watched videos aren't used even if still cached
        for (final HomeStore.Related r : HomeStore.related(history)) {
            candidates.add(new Scorer.Candidate(r.video(), Scorer.Source.RELATED, r.seedTitle()));
        }
        if (Settings.get(Settings.HOME_DISCOVER)) {
            for (final Video v : HomeStore.discover()) {
                candidates.add(new Scorer.Candidate(v, Scorer.Source.DISCOVER, null));
            }
        }
        final Set<String> favorites = new HashSet<>();
        for (final Favorites.Favorite f : Favorites.list()) {
            favorites.add(f.url());
        }
        final Map<String, History.ChannelStats> stats = history
                ? History.channelStats() : new LinkedHashMap<>();
        final Scorer.Signals signals = new Scorer.Signals(favorites, stats, history,
                history ? History.watchedUrls() : Set.of(), HomeStore.hiddenUrls(),
                HomeStore.blockedUrls(), Settings.get(Settings.HOME_HIDE_SHORTS));
        return Scorer.rank(candidates, signals, w, now);
    }

    private static void publish(final State s) {
        state = s;
        for (final Consumer<State> l : LISTENERS) {
            l.accept(s);
        }
    }
}
