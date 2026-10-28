package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import org.gnome.gtk.Align;
import org.gnome.gtk.FlowBox;
import org.gnome.gtk.FlowBoxChild;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Videos, channels and playlists as cards that open their page when clicked, loading further
 * pages on demand. The owner puts {@link #widget()} in a scroller and calls {@link #loadMore()}
 * when it reaches the bottom. Video cards get a "⋮" menu ({@link VideoMenu}).
 */
final class ItemGrid {
    /** Fetches the items of {@code page}; called off the main thread. */
    interface Pager {
        InfoItemsPage<? extends InfoItem> fetch(Page page) throws Exception;
    }

    /** One local page of {@code size} items from {@code offset}; called off the main thread. */
    interface LocalSource {
        List<? extends InfoItem> load(int offset, int size) throws Exception;
    }

    /** Pages in a row that may turn out empty after filtering before it stops asking. */
    private static final int MAX_EMPTY_PAGES = 3;

    private final MainWindow main;
    private final VideoCard.Layout layout;
    private final boolean linkUploaders;
    private final FlowBox flow = new FlowBox();
    private final List<InfoItem> items = new ArrayList<>();

    private Pager pager;
    private Page nextPage;
    private boolean loadingMore;
    /** Incremented by {@link #reset} so late pages from earlier contents are dropped. */
    private int generation;
    private int emptyPages;
    private Function<StreamInfoItem, List<VideoMenu.Extra>> menuExtras = item -> List.of();
    private BiFunction<StreamInfoItem, Integer, String[]> notes;

    /**
     * @param layout        {@code ROW} lists videos one per line; other items are always cards
     * @param linkUploaders whether video cards link their uploader's channel; off on a
     *                      channel's own tabs
     */
    ItemGrid(final MainWindow main, final VideoCard.Layout layout, final boolean linkUploaders) {
        this.main = main;
        this.layout = layout;
        this.linkUploaders = linkUploaders;

        final boolean grid = layout == VideoCard.Layout.GRID;
        flow.setSelectionMode(SelectionMode.NONE);
        flow.setActivateOnSingleClick(true);
        flow.setHomogeneous(true);
        flow.setMinChildrenPerLine(1);
        flow.setMaxChildrenPerLine(grid ? 6 : 1);
        flow.setColumnSpacing(6);
        flow.setRowSpacing(grid ? 12 : 4);
        flow.setValign(Align.START);
        flow.setMarginTop(12);
        flow.setMarginBottom(24);
        flow.setMarginStart(12);
        flow.setMarginEnd(12);
        flow.onChildActivated(child -> main.open(items.get(child.getIndex())));
    }

    /** View-specific entries for the cards' menus ("Not interested", "Remove from…"). */
    void setMenuExtras(final Function<StreamInfoItem, List<VideoMenu.Extra>> extras) {
        menuExtras = extras;
    }

    /** A note line and tooltip per video (item, index) → {note, tooltip}, or null. */
    void setNotes(final BiFunction<StreamInfoItem, Integer, String[]> notes) {
        this.notes = notes;
    }

    Widget widget() {
        return flow;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    List<InfoItem> items() {
        return List.copyOf(items);
    }

    /** The loaded videos, for "Play all". */
    List<Video> videos() {
        final List<Video> videos = new ArrayList<>();
        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem s) {
                videos.add(Video.from(s));
            }
        }
        return videos;
    }

    /** Replaces the contents with {@code first}; {@code pager} fetches from {@code next} on. */
    void reset(final List<? extends InfoItem> first, final Page next, final Pager pager) {
        generation++;
        flow.removeAll();
        items.clear();
        this.pager = pager;
        nextPage = next;
        loadingMore = false;
        emptyPages = 0;
        if (add(first) == 0) {
            skipEmptyPage();
        }
    }

    /**
     * Replaces the contents with local items, loaded {@code pageSize} at a time as the user
     * scrolls (the first page is loaded here, on the main thread).
     */
    void resetLocal(final LocalSource source, final int pageSize) {
        final List<? extends InfoItem> first;
        try {
            first = source.load(0, pageSize);
        } catch (final Exception e) {
            main.toast(Ui.errorMessage(e));
            return;
        }
        reset(first, first.size() < pageSize ? null : new Page("local:" + pageSize),
                page -> {
                    final int offset = Integer.parseInt(page.getUrl().substring(6));
                    final List<InfoItem> more = new ArrayList<>(source.load(offset, pageSize));
                    return new InfoItemsPage<>(more, more.size() < pageSize ? null
                            : new Page("local:" + (offset + pageSize)), List.of());
                });
    }

    /** The next page to load, or null when everything is loaded. */
    Page nextPage() {
        return Page.isValid(nextPage) ? nextPage : null;
    }

    void loadMore() {
        if (loadingMore || pager == null || !Page.isValid(nextPage)) {
            return;
        }
        loadingMore = true;
        final int gen = generation;
        final Pager p = pager;
        final Page page = nextPage;
        Ui.fetch(() -> p.fetch(page),
                more -> {
                    if (gen != generation) {
                        return;
                    }
                    nextPage = more.getNextPage();
                    final int added = add(more.getItems());
                    loadingMore = false;
                    if (added == 0) {
                        skipEmptyPage();
                    } else {
                        emptyPages = 0;
                    }
                },
                error -> {
                    if (gen != generation) {
                        return;
                    }
                    loadingMore = false;
                    main.toast("Couldn't load more: " + Ui.errorMessage(error));
                });
    }

    /**
     * A page whose items were all filtered out (members-only) adds nothing to scroll past, so
     * the scroller's edge wouldn't ask for the next one; ask here, a few pages at most.
     */
    private void skipEmptyPage() {
        if (++emptyPages <= MAX_EMPTY_PAGES) {
            loadMore();
        }
    }

    /** Adds the watchable items; returns how many. */
    private int add(final List<? extends InfoItem> newItems) {
        int added = 0;
        for (final InfoItem item : newItems) {
            if (!Extractor.isWatchable(item)) {
                continue;
            }
            final Widget card = switch (item) {
                case StreamInfoItem stream -> videoCard(stream);
                case PlaylistInfoItem playlist -> PlaylistCard.create(playlist);
                case ChannelInfoItem channel -> ChannelCard.create(channel, main);
                default -> null;
            };
            if (card == null) {
                continue;
            }
            items.add(item);
            final FlowBoxChild child = new FlowBoxChild();
            child.setChild(card);
            flow.append(child);
            added++;
        }
        return added;
    }

    private Widget videoCard(final StreamInfoItem stream) {
        final String[] note = notes == null ? null : notes.apply(stream, items.size());
        final VideoCard.Decor decor = new VideoCard.Decor(note == null ? null : note[0],
                note == null ? null : note[1],
                anchor -> VideoMenu.show(main, anchor, Video.from(stream),
                        menuExtras.apply(stream)));
        return VideoCard.create(stream, layout, linkUploaders ? main : null, decor);
    }
}
