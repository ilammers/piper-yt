package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Feed;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.service.FeedRefresher;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.localization.DateWrapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Uploads from all favorites, newest first, from the local cache. The cache refreshes in the
 * background (see {@link FeedRefresher}); the view updates in place as channels come in.
 */
final class FeedView implements Shell.View {
    private static final int PAGE = 60;

    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final Stack stack = new Stack();
    private final StatusPage empty = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ItemGrid grid;
    private final Label status = Ui.label("", "dim-label", "caption");
    private final Spinner spinner = new Spinner();
    private final Button refresh = Button.fromIconName("view-refresh-symbolic");
    private final DropDown groups = new DropDown(null, null);
    private final CheckButton hideWatched = CheckButton.withLabel("Hide watched");
    private List<Favorites.Group> groupList = List.of();
    private boolean visible;
    private boolean dirty = true;
    private boolean reloadQueued;

    FeedView(final MainWindow main) {
        this.main = main;
        grid = new ItemGrid(main, VideoCard.Layout.GRID, true);

        refresh.setTooltipText("Refresh now (paced: a few seconds per channel)");
        refresh.onClicked(() -> {
            if (FeedRefresher.state().running()) {
                FeedRefresher.cancel();
            } else {
                FeedRefresher.refreshNow();
            }
        });
        spinner.setVisible(false);
        groups.setTooltipText("Group");
        fillGroups();
        groups.onNotify("selected", pspec -> reload());
        box.append(Ui.viewHeader("Feed", status, spinner, groups, filterButton(), refresh));

        empty.setIconName("view-grid-symbolic");
        empty.setTitle("No uploads yet");
        empty.setDescription("Uploads from your Favorites show up here. Add a channel to "
                + "Favorites, then refresh.");

        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(grid.widget());
        scroller.onEdgeReached(pos -> {
            if (pos == PositionType.BOTTOM) {
                grid.loadMore();
            }
        });
        stack.addNamed(empty, "empty");
        stack.addNamed(scroller, "grid");
        stack.setVexpand(true);
        box.append(stack);

        Library.listen(Library.Topic.FEED, this::changed);
        Library.listen(Library.Topic.HISTORY, this::changed);
        Library.listen(Library.Topic.FAVORITES, this::fillGroups);
        FeedRefresher.listen(state -> Ui.runOnMain(() -> showState(state)));
        showState(FeedRefresher.state());
    }

    @Override
    public Widget widget() {
        return box;
    }

    @Override
    public void shown() {
        visible = true;
        hideWatched.setSensitive(History.enabled());
        if (dirty) {
            reload();
        }
    }

    private Widget filterButton() {
        final Box filters = new Box(Orientation.VERTICAL, 4);
        filters.setMarginTop(6);
        filters.setMarginBottom(6);
        filters.setMarginStart(6);
        filters.setMarginEnd(6);
        filters.append(filter("Hide shorts", Settings.FEED_HIDE_SHORTS));
        filters.append(filter("Hide livestreams", Settings.FEED_HIDE_LIVE));
        hideWatched.setActive(Settings.get(Settings.FEED_HIDE_WATCHED));
        hideWatched.setTooltipText("Needs watch history");
        hideWatched.onToggled(() -> {
            Settings.set(Settings.FEED_HIDE_WATCHED, hideWatched.getActive());
            reload();
        });
        filters.append(hideWatched);
        filters.append(Ui.wrapping("RSS doesn't mark livestreams; ones you open are learned.",
                0, "dim-label", "caption"));
        final Popover popover = new Popover();
        popover.setChild(filters);
        final MenuButton button = new MenuButton();
        button.setLabel("Filter");
        button.setTooltipText("Filter");
        button.setPopover(popover);
        return button;
    }

    private CheckButton filter(final String label, final Settings.Key<Boolean> key) {
        final CheckButton check = CheckButton.withLabel(label);
        check.setActive(Settings.get(key));
        check.onToggled(() -> {
            Settings.set(key, check.getActive());
            reload();
        });
        return check;
    }

    private void fillGroups() {
        groupList = Favorites.groups();
        final List<String> names = new ArrayList<>();
        names.add("All favorites");
        for (final Favorites.Group g : groupList) {
            names.add(g.name());
        }
        final int selected = (int) groups.getSelected();
        groups.setModel(new StringList(names.toArray(String[]::new)));
        groups.setSelected(selected < names.size() ? selected : 0);
        groups.setVisible(!groupList.isEmpty());
    }

    /** Coalesces the per-channel updates of a refresh into one reload a second or so. */
    private void changed() {
        dirty = true;
        if (!visible || reloadQueued) {
            return;
        }
        reloadQueued = true;
        org.gnome.glib.GLib.timeoutAddOnce(1200, () -> {
            reloadQueued = false;
            reload();
        });
    }

    private void reload() {
        dirty = false;
        final int g = (int) groups.getSelected();
        final Long group = g > 0 && g <= groupList.size() ? groupList.get(g - 1).id() : null;
        final Feed.Filter filter = Feed.Filter.fromSettings(group);
        final double scroll = scroller.getVadjustment().getValue();
        grid.resetLocal((offset, size) -> Feed.list(filter, size, offset).stream()
                .map(Video::toItem).toList(), PAGE);
        scroller.getVadjustment().setValue(scroll);
        stack.setVisibleChildName(grid.isEmpty() ? "empty" : "grid");
    }

    private void showState(final FeedRefresher.State state) {
        spinner.setVisible(state.running());
        refresh.setIconName(state.running() ? "process-stop-symbolic" : "view-refresh-symbolic");
        refresh.setTooltipText(state.running() ? "Stop refreshing"
                : "Refresh now (paced: a few seconds per channel)");
        if (state.running()) {
            status.setText("Refreshing " + (state.done() + 1) + " of " + state.total()
                    + (state.message() == null ? "" : " · " + state.message()));
        } else if (state.message() != null) {
            status.setText(state.message());
        } else {
            final long last = lastRefresh();
            status.setText(last > 0 ? "Updated " + Format.relativeDate(new DateWrapper(
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(last), ZoneOffset.UTC))) : "");
        }
    }

    private static long lastRefresh() {
        long newest = 0;
        for (final Favorites.Favorite f : Favorites.refreshable()) {
            if (f.lastRefreshedAt() != null) {
                newest = Math.max(newest, f.lastRefreshedAt());
            }
        }
        return newest;
    }
}
