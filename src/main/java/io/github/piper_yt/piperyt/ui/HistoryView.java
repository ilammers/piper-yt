package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Banner;
import org.gnome.adw.Clamp;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.localization.DateWrapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Watch history, newest first: search it, remove entries, clear it. */
final class HistoryView implements Shell.View {
    private static final int PAGE = 50;

    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final Banner off = new Banner("Watch history is off: nothing is being saved");
    private final SearchEntry filter = new SearchEntry();
    private final Stack stack = new Stack();
    private final StatusPage empty = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ItemGrid grid;
    private final Map<String, History.Entry> entries = new ConcurrentHashMap<>();
    private boolean dirty = true;
    private boolean visible;

    HistoryView(final MainWindow main) {
        this.main = main;
        grid = new ItemGrid(main, VideoCard.Layout.ROW, true);
        grid.setNotes((item, index) -> {
            final History.Entry e = entries.get(item.getUrl());
            return e == null ? null : new String[]{describe(e), null};
        });
        grid.setMenuExtras(item -> List.of(new VideoMenu.Extra("Remove from history",
                () -> History.remove(item.getUrl()), true)));

        filter.setPlaceholderText("Search history");
        filter.onSearchChanged(this::reload);
        final Button clear = Button.withLabel("Clear history…");
        clear.addCssClass("destructive-action");
        clear.onClicked(() -> Dialogs.clearHistory(main));
        box.append(off);
        box.append(Ui.viewHeader("History", filter, clear));

        off.setButtonLabel("Turn on");
        off.onButtonClicked(() -> Settings.set(Settings.SAVE_WATCH_HISTORY, true));

        empty.setIconName("document-open-recent-symbolic");
        empty.setTitle("No history");
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(960);
        clamp.setChild(grid.widget());
        scroller.setChild(clamp);
        scroller.onEdgeReached(pos -> {
            if (pos == PositionType.BOTTOM) {
                grid.loadMore();
            }
        });
        stack.addNamed(empty, "empty");
        stack.addNamed(scroller, "list");
        stack.setVexpand(true);
        box.append(stack);

        Library.listen(Library.Topic.HISTORY, () -> {
            dirty = true;
            if (visible) {
                reload();
            }
        });
        Settings.addListener(key -> {
            if (key.equals(Settings.SAVE_WATCH_HISTORY.name())) {
                Ui.runOnMain(this::updateBanner);
            }
        });
    }

    @Override
    public Widget widget() {
        return box;
    }

    @Override
    public void shown() {
        visible = true;
        updateBanner();
        if (dirty) {
            reload();
        }
    }

    private void updateBanner() {
        off.setRevealed(!History.enabled());
        empty.setDescription(History.enabled()
                ? "Videos you play show up here. History stays on this computer."
                : "Turn on “Save watch history” to keep track of what you watch and resume "
                + "where you left off.");
    }

    private void reload() {
        dirty = false;
        entries.clear();
        final String text = filter.getText();
        grid.resetLocal((offset, size) -> {
            // Later pages load in the background; the notes map is concurrent for that
            final List<History.Entry> page = History.list(text, size, offset);
            page.forEach(e -> entries.put(e.video().url(), e));
            return page.stream().map(e -> e.video().toItem()).toList();
        }, PAGE);
        stack.setVisibleChildName(grid.isEmpty() ? "empty" : "list");
    }

    private static String describe(final History.Entry e) {
        final String when = "Watched " + Format.relativeDate(new DateWrapper(OffsetDateTime
                .ofInstant(Instant.ofEpochMilli(e.lastWatchedAt()), ZoneOffset.UTC)));
        final String times = e.watchCount() > 1 ? e.watchCount() + " times" : null;
        final long durationMs = e.video().durationS() * 1000;
        final String progress = durationMs > 0 && e.progressMs() > 0
                ? Math.min(100, Math.round(100.0 * e.progressMs() / durationMs)) + "% watched"
                : null;
        return Format.dot(when, times, progress);
    }
}
