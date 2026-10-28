package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.HomeStore;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.service.HomeRefresher;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The landing page: recommendations computed locally from favorites (and, with history on,
 * what you watch). Shows the cached page instantly and refreshes it when it's older than the
 * configured TTL.
 */
final class HomeView implements Shell.View {
    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final Stack stack = new Stack();
    private final StatusPage empty = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ItemGrid grid;
    private final Label status = Ui.label("", "dim-label", "caption");
    private final Spinner spinner = new Spinner();
    private final Button refresh = Button.fromIconName("view-refresh-symbolic");
    private final Map<String, HomeStore.Cached> picks = new HashMap<>();
    private boolean visible;
    private boolean dirty = true;

    HomeView(final MainWindow main) {
        this.main = main;
        grid = new ItemGrid(main, VideoCard.Layout.GRID, true);
        grid.setNotes((item, index) -> {
            final HomeStore.Cached pick = picks.get(item.getUrl());
            return pick == null ? null : new String[]{pick.reason(), pick.detail()};
        });
        grid.setMenuExtras(this::extras);

        refresh.setTooltipText("Refresh recommendations");
        refresh.onClicked(() -> HomeRefresher.refresh());
        spinner.setVisible(false);
        box.append(Ui.viewHeader("Home", status, spinner, refresh));

        empty.setIconName("starred-symbolic");
        empty.setTitle("Nothing to recommend yet");
        empty.setDescription("Add channels to Favorites (from a channel or video page), or "
                + "import a PipePipe backup in Preferences → Data. Everything here is "
                + "computed on this computer.");
        final Button favorites = Button.withLabel("Go to Favorites");
        favorites.addCssClass("pill");
        favorites.setHalign(Align.CENTER);
        favorites.onClicked(() -> main.showView("favorites"));
        empty.setChild(favorites);

        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(grid.widget());
        scroller.setVexpand(true);
        stack.addNamed(empty, "empty");
        stack.addNamed(scroller, "grid");
        stack.setVexpand(true);
        box.append(stack);

        Library.listen(Library.Topic.HOME, () -> {
            dirty = true;
            if (visible) {
                reload();
            }
        });
        HomeRefresher.listen(state -> Ui.runOnMain(() -> showState(state)));
    }

    @Override
    public Widget widget() {
        return box;
    }

    @Override
    public void shown() {
        visible = true;
        if (dirty) {
            reload();
        }
        if (HomeRefresher.isStale() && (!Favorites.list().isEmpty()
                || History.enabled() && History.count() > 0)) {
            HomeRefresher.refresh();
        }
    }

    private void reload() {
        dirty = false;
        final List<HomeStore.Cached> cached = HomeStore.loadHome();
        picks.clear();
        final List<StreamInfoItem> items = new ArrayList<>();
        for (final HomeStore.Cached c : cached) {
            picks.put(c.video().url(), c);
            items.add(c.video().toItem());
        }
        final double scroll = scroller.getVadjustment().getValue();
        grid.reset(items, null, null);
        scroller.getVadjustment().setValue(scroll);
        stack.setVisibleChildName(items.isEmpty() ? "empty" : "grid");
    }

    private void showState(final HomeRefresher.State state) {
        spinner.setVisible(state.running());
        refresh.setSensitive(!state.running());
        status.setText(state.running() ? "Updating…"
                : state.message() == null ? "" : state.message());
    }

    private List<VideoMenu.Extra> extras(final StreamInfoItem item) {
        final List<VideoMenu.Extra> extras = new ArrayList<>();
        extras.add(new VideoMenu.Extra("Not interested", () -> {
            HomeStore.hide(item.getUrl());
            main.toast("Hidden from Home");
        }));
        if (item.getUploaderUrl() != null) {
            extras.add(new VideoMenu.Extra("Don't recommend " + item.getUploaderName(), () -> {
                HomeStore.block(item.getUploaderUrl(), item.getUploaderName());
                main.toast("Won't recommend " + item.getUploaderName()
                        + ". Undo in Preferences → Homepage.");
            }, true));
        }
        return extras;
    }
}
