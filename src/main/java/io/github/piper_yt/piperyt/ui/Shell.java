package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.player.PlayQueue;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.OverlaySplitView;
import org.gnome.adw.ToolbarView;
import org.gnome.adw.WindowTitle;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StackTransitionType;
import org.gnome.gtk.ToggleButton;
import org.gnome.gtk.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * The root page: a sidebar of library views (Home first, the landing page) next to the
 * current view, with search in the header bar.
 */
final class Shell {
    /** A sidebar view. {@link #shown()} runs each time it becomes visible. */
    interface View {
        Widget widget();

        default void shown() {
        }
    }

    private record Entry(String name, String title, String icon, View view) {
    }

    private static final int COLLAPSE_BELOW = 820;

    private final MainWindow main;
    private final NavigationPage page;
    private final OverlaySplitView split = new OverlaySplitView();
    private final ListBox sidebar = new ListBox();
    private final Stack stack = new Stack();
    private final List<Entry> entries = new ArrayList<>();
    private final SearchView search;
    private final Button queueButton = Button.fromIconName("view-list-ordered-symbolic");

    Shell(final MainWindow main) {
        this.main = main;
        search = new SearchView(main, this::showSearch);

        entries.add(new Entry("home", "Home", "go-home-symbolic", new HomeView(main)));
        entries.add(new Entry("feed", "Feed", "view-grid-symbolic", new FeedView(main)));
        entries.add(new Entry("favorites", "Favorites", "starred-symbolic",
                new FavoritesView(main)));
        entries.add(new Entry("playlists", "Playlists", "view-list-symbolic",
                new PlaylistsView(main)));
        entries.add(new Entry("downloads", "Downloads", "folder-download-symbolic",
                new DownloadsView(main)));
        entries.add(new Entry("history", "History", "document-open-recent-symbolic",
                new HistoryView(main)));

        stack.setTransitionType(StackTransitionType.CROSSFADE);
        sidebar.setSelectionMode(SelectionMode.SINGLE);
        sidebar.addCssClass("navigation-sidebar");
        for (final Entry e : entries) {
            stack.addNamed(e.view().widget(), e.name());
            final Box row = new Box(Orientation.HORIZONTAL, 12);
            row.setMarginTop(4);
            row.setMarginBottom(4);
            row.append(Image.fromIconName(e.icon()));
            row.append(new Label(e.title()));
            final ListBoxRow listRow = new ListBoxRow();
            listRow.setChild(row);
            sidebar.append(listRow);
        }
        stack.addNamed(search.widget(), "search");
        sidebar.onRowSelected(row -> {
            if (row != null) {
                show(entries.get(row.getIndex()));
            }
        });

        final Button preferences = Button.fromIconName("emblem-system-symbolic");
        preferences.setTooltipText("Preferences");
        preferences.onClicked(() -> Preferences.present(main));
        final HeaderBar sidebarHeader = new HeaderBar();
        sidebarHeader.setTitleWidget(new WindowTitle("Piper", ""));
        sidebarHeader.packEnd(preferences);
        final ScrolledWindow sidebarScroller = new ScrolledWindow();
        sidebarScroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        sidebarScroller.setChild(sidebar);
        final ToolbarView sidebarView = new ToolbarView();
        sidebarView.addTopBar(sidebarHeader);
        sidebarView.setContent(sidebarScroller);

        final ToggleButton toggle = new ToggleButton();
        toggle.setIconName("sidebar-show-symbolic");
        toggle.setTooltipText("Sidebar");
        toggle.setActive(true);
        toggle.onToggled(() -> split.setShowSidebar(toggle.getActive()));
        split.onNotify("show-sidebar", pspec -> toggle.setActive(split.getShowSidebar()));

        queueButton.setTooltipText("Queue");
        queueButton.onClicked(this::showQueue);
        queueButton.setVisible(!PlayQueue.isEmpty());
        PlayQueue.listen(() -> queueButton.setVisible(!PlayQueue.isEmpty()));

        final HeaderBar header = new HeaderBar();
        header.packStart(toggle);
        header.setTitleWidget(search.entryWidget());
        header.packEnd(search.filterWidget());
        header.packEnd(search.typeWidget());
        header.packEnd(queueButton);
        header.packEnd(main.sleepTimer().button());
        final ToolbarView content = new ToolbarView();
        content.addTopBar(header);
        content.setContent(stack);

        split.setSidebar(sidebarView);
        split.setContent(content);
        split.setMinSidebarWidth(200);
        split.setMaxSidebarWidth(240);
        // Narrow windows overlay the sidebar instead of squeezing the grid
        split.addTickCallback((widget, clock) -> {
            final boolean narrow = split.getWidth() > 0 && split.getWidth() < COLLAPSE_BELOW;
            if (narrow != split.getCollapsed()) {
                split.setCollapsed(narrow);
                split.setShowSidebar(!narrow);
            }
            return true;
        });

        page = new NavigationPage(split, "Piper");
        sidebar.selectRow(sidebar.getRowAtIndex(0));
    }

    NavigationPage page() {
        return page;
    }

    /** Shows the view called {@code name} ("home", "feed", …). */
    void showView(final String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                sidebar.selectRow(sidebar.getRowAtIndex(i));
            }
        }
    }

    private void show(final Entry e) {
        stack.setVisibleChildName(e.name());
        e.view().shown();
        if (split.getCollapsed()) {
            split.setShowSidebar(false);
        }
    }

    private void showSearch() {
        sidebar.unselectAll();
        stack.setVisibleChildName("search");
    }

    /** The queue in a popover: jump to a video, remove one, or clear it. */
    private void showQueue() {
        final Popover popover = new Popover();
        popover.setParent(queueButton);
        popover.onClosed(() -> Ui.runOnMain(popover::unparent));
        final Box box = new Box(Orientation.VERTICAL, 6);
        box.setMarginTop(6);
        box.setMarginBottom(6);
        box.setMarginStart(6);
        box.setMarginEnd(6);
        final ListBox list = new ListBox();
        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");
        final List<Video> items = PlayQueue.items();
        for (int i = 0; i < items.size(); i++) {
            final Box row = new Box(Orientation.HORIZONTAL, 8);
            row.setMarginStart(8);
            row.setMarginEnd(4);
            row.setMarginTop(4);
            row.setMarginBottom(4);
            final Label title = Ui.wrapping((i + 1) + ". " + items.get(i).title(), 1,
                    i == PlayQueue.index() ? "heading" : "body");
            title.setHexpand(true);
            title.setMaxWidthChars(48);
            row.append(title);
            final int index = i;
            final Button remove = Button.fromIconName("window-close-symbolic");
            remove.addCssClass("flat");
            remove.setTooltipText("Remove from queue");
            remove.onClicked(() -> {
                popover.popdown();
                PlayQueue.remove(index);
            });
            row.append(remove);
            final ListBoxRow listRow = new ListBoxRow();
            listRow.setChild(row);
            list.append(listRow);
        }
        list.onRowActivated(row -> {
            popover.popdown();
            if (PlayQueue.jump(row.getIndex()) != null) {
                main.playQueueCurrent();
            }
        });
        final ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setPropagateNaturalHeight(true);
        scroller.setMaxContentHeight(420);
        scroller.setChild(list);
        box.append(scroller);
        box.append(new Separator(Orientation.HORIZONTAL));
        final Button clear = Button.withLabel("Clear queue");
        clear.addCssClass("flat");
        clear.onClicked(() -> {
            popover.popdown();
            PlayQueue.clear();
        });
        box.append(clear);
        popover.setChild(box);
        popover.popup();
    }
}
