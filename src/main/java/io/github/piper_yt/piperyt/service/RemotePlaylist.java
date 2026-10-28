package io.github.piper_yt.piperyt.service;

import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.BotWall;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.net.Pacer;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** A whole YouTube playlist, for local copies, re-syncs and "download all". */
public record RemotePlaylist(PlaylistInfo info, List<Video> videos) {
    private static final int MAX_VIDEOS = 5000;

    /**
     * Fetches every page, one at a time with a random 1–4 s gap, each retried at a bot check.
     * Blocking; call off the main thread.
     */
    public static RemotePlaylist fetch(final String url) throws Exception {
        final PlaylistInfo info = BotWall.retrying(() ->
                PlaylistInfo.getInfo(Extractor.youtube(), url));
        final List<Video> videos = new ArrayList<>();
        add(info.getRelatedItems(), videos);
        Page next = info.getNextPage();
        while (Page.isValid(next) && videos.size() < MAX_VIDEOS) {
            Pacer.sleepJitter(1, 4);
            final Page page = next;
            final InfoItemsPage<StreamInfoItem> more = BotWall.retrying(() ->
                    PlaylistInfo.getMoreItems(Extractor.youtube(), info.getUrl(), page));
            add(more.getItems(), videos);
            next = more.getNextPage();
        }
        return new RemotePlaylist(info, videos);
    }

    /**
     * Fetches the pages from {@code next} on, the same careful way as {@link #fetch}, handing
     * each page's videos to {@code onPage} as it arrives. Stops early when {@code onPage}
     * returns false. Blocking; call off the main thread.
     */
    public static void fetchRest(final String url, final Page next,
                                 final Predicate<List<Video>> onPage) throws Exception {
        Page page = next;
        int count = 0;
        while (Page.isValid(page) && count < MAX_VIDEOS) {
            Pacer.sleepJitter(1, 4);
            final Page current = page;
            final InfoItemsPage<StreamInfoItem> more = BotWall.retrying(() ->
                    PlaylistInfo.getMoreItems(Extractor.youtube(), url, current));
            final List<Video> videos = new ArrayList<>();
            add(more.getItems(), videos);
            count += videos.size();
            if (!onPage.test(videos)) {
                return;
            }
            page = more.getNextPage();
        }
    }

    private static void add(final List<? extends InfoItem> items, final List<Video> out) {
        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem s && Extractor.isWatchable(s)) {
                out.add(Video.from(s));
            }
        }
    }
}
