package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.net.Extractor;
import org.gnome.adw.Clamp;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.FlowBox;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.StreamingService.LinkType;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Search: the entry and type picker live in the shell's header bar (so typing anywhere
 * searches); results show in this view, an infinitely scrolling grid.
 */
final class SearchView {
    private final MainWindow main;
    private final Runnable onSearch;
    private final SearchEntry entry = new SearchEntry();
    private final Clamp entryClamp = new Clamp();
    private final Stack stack = new Stack();
    private final StatusPage status = new StatusPage();
    private final FlowBox recent = new FlowBox();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ItemGrid grid;
    private final DropDown type = DropDown.fromStrings(Arrays.stream(Extractor.SearchType.values())
            .map(t -> t.label).toArray(String[]::new));
    private final MenuButton filterButton = new MenuButton();
    private final Box videoOnlyFilters = new Box(Orientation.VERTICAL, 12);
    private final CheckButton[] sortChecks;
    private final CheckButton[] dateChecks;
    private final CheckButton[] lengthChecks;
    private Extractor.SortBy sort = Extractor.SortBy.RELEVANCE;
    private Extractor.UploadDate date = Extractor.UploadDate.ANY;
    private Extractor.Length length = Extractor.Length.ANY;

    /** Set while {@link #resetFilters} flips radio buttons, so it searches once, not thrice. */
    private boolean suppressRerun;

    /** Incremented per search so late results from an older search are dropped. */
    private int generation;

    /** @param onSearch called when a search starts, so the shell shows this view */
    SearchView(final MainWindow main, final Runnable onSearch) {
        this.main = main;
        this.onSearch = onSearch;
        grid = new ItemGrid(main, VideoCard.Layout.GRID, true);

        entry.setPlaceholderText("Search YouTube or paste a link");
        entry.setHexpand(true);
        entry.setKeyCaptureWidget(main.window());
        entry.onActivate(this::search);
        entryClamp.setMaximumSize(560);
        entryClamp.setChild(entry);

        type.setTooltipText("Search for");
        type.onNotify("selected", pspec -> {
            updateFilterButton();
            rerun();
        });

        final Box filters = new Box(Orientation.VERTICAL, 12);
        filters.setMarginTop(6);
        filters.setMarginBottom(6);
        filters.setMarginStart(6);
        filters.setMarginEnd(6);
        sortChecks = radioGroup(filters, "Sort by", Extractor.SortBy.values(), v -> v.label,
                v -> sort = v);
        dateChecks = radioGroup(videoOnlyFilters, "Upload date",
                Extractor.UploadDate.values(), v -> v.label, v -> date = v);
        lengthChecks = radioGroup(videoOnlyFilters, "Length", Extractor.Length.values(),
                v -> v.label, v -> length = v);
        filters.append(videoOnlyFilters);
        final Button reset = Button.withLabel("Reset");
        reset.onClicked(this::resetFilters);
        filters.append(reset);
        final Popover popover = new Popover();
        popover.setChild(filters);
        filterButton.setPopover(popover);
        filterButton.setIconName("view-sort-descending-symbolic");
        updateFilterButton();

        status.setIconName("system-search-symbolic");
        status.setTitle("Search YouTube");
        status.setDescription("Type to search, or paste a video, channel or playlist link");
        recent.setSelectionMode(SelectionMode.NONE);
        recent.setHalign(Align.CENTER);
        recent.setMaxChildrenPerLine(4);
        recent.setColumnSpacing(6);
        recent.setRowSpacing(6);
        status.setChild(recent);
        fillRecent();
        Library.listen(Library.Topic.HISTORY, this::fillRecent);

        final Spinner spinner = new Spinner();
        spinner.setSizeRequest(48, 48);
        spinner.setHalign(Align.CENTER);
        spinner.setValign(Align.CENTER);

        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(grid.widget());
        scroller.onEdgeReached(pos -> {
            if (pos == PositionType.BOTTOM) {
                grid.loadMore();
            }
        });

        stack.addNamed(status, "status");
        stack.addNamed(spinner, "loading");
        stack.addNamed(scroller, "results");
        stack.setVisibleChildName("status");
    }

    Widget widget() {
        return stack;
    }

    Widget entryWidget() {
        return entryClamp;
    }

    Widget typeWidget() {
        return type;
    }

    Widget filterWidget() {
        return filterButton;
    }

    /**
     * A heading and one radio button per value in {@code box}; picking one stores it with
     * {@code set} and re-runs the search. The first value starts selected.
     */
    private <T> CheckButton[] radioGroup(final Box box, final String heading, final T[] values,
                                         final Function<T, String> label,
                                         final Consumer<T> set) {
        final Box group = new Box(Orientation.VERTICAL, 2);
        final Label title = new Label(heading);
        title.setXalign(0);
        title.addCssClass("heading");
        group.append(title);
        final CheckButton[] checks = new CheckButton[values.length];
        for (int i = 0; i < values.length; i++) {
            final T value = values[i];
            final CheckButton check = CheckButton.withLabel(label.apply(value));
            if (i > 0) {
                check.setGroup(checks[0]);
            }
            check.setActive(i == 0);
            check.onToggled(() -> {
                if (check.getActive()) {
                    set.accept(value);
                    updateFilterButton();
                    rerun();
                }
            });
            checks[i] = check;
            group.append(check);
        }
        box.append(group);
        return checks;
    }

    private void resetFilters() {
        if (filters().isDefault()) {
            return;
        }
        sort = Extractor.SortBy.RELEVANCE;
        date = Extractor.UploadDate.ANY;
        length = Extractor.Length.ANY;
        suppressRerun = true;
        sortChecks[0].setActive(true);
        dateChecks[0].setActive(true);
        lengthChecks[0].setActive(true);
        suppressRerun = false;
        updateFilterButton();
        rerun();
    }

    /** Filters that apply to the selected type: date and length are video-only. */
    private Extractor.SearchFilters filters() {
        return selectedType() == Extractor.SearchType.VIDEOS
                ? new Extractor.SearchFilters(sort, date, length)
                : new Extractor.SearchFilters(sort, Extractor.UploadDate.ANY,
                        Extractor.Length.ANY);
    }

    private Extractor.SearchType selectedType() {
        return Extractor.SearchType.values()[type.getSelected()];
    }

    /** Highlights the button while a filter is on, and hides video-only filters. */
    private void updateFilterButton() {
        videoOnlyFilters.setVisible(selectedType() == Extractor.SearchType.VIDEOS);
        if (filters().isDefault()) {
            filterButton.removeCssClass("accent");
            filterButton.setTooltipText("Sort and filter");
        } else {
            filterButton.addCssClass("accent");
            filterButton.setTooltipText("Sort and filter (filters on)");
        }
    }

    /** Re-runs the current search with new options, but doesn't reopen a pasted link. */
    private void rerun() {
        if (!suppressRerun && !entry.getText().isBlank()
                && Extractor.linkType(entry.getText().strip()) == LinkType.NONE) {
            search();
        }
    }

    void focus() {
        entry.grabFocus();
    }

    /** Recent searches as buttons, only when "Save search history" is on. */
    private void fillRecent() {
        recent.removeAll();
        if (!History.searchEnabled()) {
            return;
        }
        for (final String q : History.recentSearches(8)) {
            final Box chip = new Box(Orientation.HORIZONTAL, 0);
            chip.addCssClass("linked");
            final Button search = Button.withLabel(q);
            search.onClicked(() -> {
                entry.setText(q);
                search();
            });
            final Button remove = Button.fromIconName("window-close-symbolic");
            remove.setTooltipText("Remove from search history");
            remove.onClicked(() -> History.removeSearch(q));
            chip.append(search);
            chip.append(remove);
            recent.append(chip);
        }
    }

    private void search() {
        final String text = entry.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        if (main.openLink(text)) {
            return;
        }
        onSearch.run();

        final SearchQueryHandler handler;
        try {
            handler = Extractor.search(text, selectedType(), filters());
        } catch (final ParsingException e) {
            showStatus("dialog-error-symbolic", "Search failed", Ui.errorMessage(e));
            return;
        }
        Ui.background(() -> History.recordSearch(text));

        final int gen = ++generation;
        stack.setVisibleChildName("loading");
        Ui.fetch(() -> SearchInfo.getInfo(Extractor.youtube(), handler),
                info -> {
                    if (gen != generation) {
                        return;
                    }
                    grid.reset(info.getRelatedItems(), info.getNextPage(),
                            page -> SearchInfo.getMoreItems(Extractor.youtube(), handler, page));
                    if (grid.isEmpty()) {
                        showStatus("edit-find-symbolic", "No results",
                                "Try a different search");
                    } else {
                        scroller.getVadjustment().setValue(0);
                        stack.setVisibleChildName("results");
                    }
                },
                error -> {
                    if (gen == generation) {
                        showStatus("dialog-error-symbolic", "Search failed",
                                Ui.errorMessage(error));
                    }
                });
    }

    private void showStatus(final String icon, final String title, final String description) {
        status.setIconName(icon);
        status.setTitle(title);
        status.setDescription(description);
        recent.setVisible(false);
        stack.setVisibleChildName("status");
    }
}
