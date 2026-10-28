package io.github.piper_yt.piperyt.service;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Feed;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.net.Pacer;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Keeps the feed cache fresh from each favorite's RSS feed (one small request per channel, no
 * player or browse calls). Refreshes run through {@link Pacer}: shuffled, a random 2–20 s gap
 * per channel, in batches. In the background it refreshes channels not refreshed within the
 * interval, checking every few minutes at a jittered time; "Refresh" does all of them, paced
 * the same way.
 */
public final class FeedRefresher {
    /** Progress of the current refresh, for the Feed view's status line. */
    public record State(boolean running, int done, int total, String message) {
    }

    private static final List<Consumer<State>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("feed-timer").factory());

    private static volatile State state = new State(false, 0, 0, null);
    private static volatile boolean cancel;

    private FeedRefresher() {
    }

    /** Starts the background timer: first look after a random 1–3 minutes, then every 5±2. */
    public static void start() {
        scheduleCheck(ThreadLocalRandom.current().nextLong(60, 181));
    }

    private static void scheduleCheck(final long delaySeconds) {
        TIMER.schedule(() -> {
            try {
                if (!state.running() && !Pacer.coolingDown()) {
                    refresh(false);
                }
            } catch (final Throwable t) {
                t.printStackTrace();
            } finally {
                scheduleCheck(ThreadLocalRandom.current().nextLong(180, 421));
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public static State state() {
        return state;
    }

    public static Runnable listen(final Consumer<State> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Refreshes every favorite now (still paced), unless a refresh is already running. */
    public static void refreshNow() {
        if (state.running()) {
            return;
        }
        Thread.ofVirtual().name("feed-refresh").start(() -> refresh(true));
    }

    public static void cancel() {
        cancel = true;
    }

    /** Refreshes on the calling thread; {@code all} ignores when channels were last fetched. */
    static void refresh(final boolean all) {
        final long interval = Settings.getInt(Settings.FEED_INTERVAL_MIN) * 60_000L;
        final long now = System.currentTimeMillis();
        final List<Favorites.Favorite> due = new ArrayList<>();
        for (final Favorites.Favorite f : Favorites.refreshable()) {
            if (all || f.lastRefreshedAt() == null || now - f.lastRefreshedAt() >= interval) {
                due.add(f);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        cancel = false;
        final int[] done = {0};
        publish(new State(true, 0, due.size(), null));
        final Pacer.Plan plan = new Pacer.Plan(Settings.getInt(Settings.FEED_GAP_MIN_S),
                Settings.getInt(Settings.FEED_GAP_MAX_S), Settings.getInt(Settings.FEED_BATCH_SIZE),
                Settings.getInt(Settings.FEED_BATCH_PAUSE_S), true);
        String message = null;
        try {
            final Pacer.Result result = Pacer.run(due, plan, FeedRefresher::fetch,
                    new Pacer.Progress<>() {
                        @Override
                        public void started(final Favorites.Favorite f, final int i,
                                            final int total) {
                            done[0] = i;
                            publish(new State(true, i, total, f.name()));
                        }

                        @Override
                        public void failed(final Favorites.Favorite f, final Exception e) {
                            Feed.storeError(f.id(), e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage());
                        }
                    }, () -> cancel);
            if (result.blocked()) {
                message = "YouTube is bot-checking this exit; the feed will retry later";
            } else if (result.failed() > 0) {
                message = result.failed() + " of " + due.size() + " channels couldn't be refreshed";
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            publish(new State(false, done[0], due.size(), message));
            Library.changed(Library.Topic.FEED, Library.Topic.FAVORITES);
        }
    }

    private static void fetch(final Favorites.Favorite favorite) throws Exception {
        String url = favorite.url();
        if (!url.contains("/channel/")) {
            // RSS needs the channel ID; resolve a handle URL once and keep the canonical one
            final ChannelInfo channel = ChannelInfo.getInfo(Extractor.youtube(), url);
            url = channel.getUrl();
            Favorites.replaceUrl(favorite.id(), url);
        }
        final FeedInfo info = FeedInfo.getInfo(Extractor.youtube(), url);
        final List<Video> uploads = new ArrayList<>();
        for (final InfoItem item : info.getRelatedItems()) {
            if (item instanceof StreamInfoItem stream) {
                uploads.add(Video.from(stream));
            }
        }
        Feed.store(favorite.id(), uploads);
        if (Settings.get(Settings.FEED_DETECT_LIVE)) {
            learnLive(url);
        }
        Library.changed(Library.Topic.FEED);
    }

    /**
     * RSS can't say which uploads are livestreams; the channel's Live tab can. A second
     * request, so it's opt-in, and a failure (many channels have no Live tab) is ignored.
     */
    private static void learnLive(final String channelUrl) {
        try {
            Pacer.sleepJitter(1, 3);
            final ListLinkHandler handler = Extractor.youtube().getChannelTabLHFactory()
                    .fromUrl(channelUrl.replaceAll("/+$", "") + "/streams");
            final ChannelTabInfo tab = ChannelTabInfo.getInfo(Extractor.youtube(), handler);
            for (final InfoItem item : tab.getRelatedItems()) {
                if (item instanceof StreamInfoItem stream && stream.getStreamType() != null) {
                    Feed.learnStreamType(Video.canonicalUrl(stream.getUrl()),
                            stream.getStreamType().name());
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Exception e) {
            System.err.println("Live tab of " + channelUrl + ": " + e.getMessage());
        }
    }

    private static void publish(final State s) {
        state = s;
        for (final Consumer<State> l : LISTENERS) {
            l.accept(s);
        }
    }
}
