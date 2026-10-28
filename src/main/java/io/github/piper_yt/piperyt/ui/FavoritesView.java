package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.ActionRow;
import org.gnome.adw.Avatar;
import org.gnome.adw.Clamp;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Image;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.localization.DateWrapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Favorite channels: filter by name or group, sort, group them, remove them. */
final class FavoritesView implements Shell.View {
    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final Stack stack = new Stack();
    private final ListBox list = new ListBox();
    private final SearchEntry filter = new SearchEntry();
    private final DropDown sort = DropDown.fromStrings(Arrays.stream(Favorites.Sort.values())
            .map(s -> s.label).toArray(String[]::new));
    private final DropDown groupFilter = new DropDown(null, null);
    private final Box groupsBox = new Box(Orientation.VERTICAL, 4);
    private List<Favorites.Favorite> shown = List.of();
    private List<Favorites.Group> groups = List.of();
    private boolean dirty = true;
    private boolean visible;

    FavoritesView(final MainWindow main) {
        this.main = main;

        filter.setPlaceholderText("Filter favorites");
        filter.onSearchChanged(this::reload);
        sort.setTooltipText("Sort by");
        sort.onNotify("selected", pspec -> reload());
        groupFilter.setTooltipText("Group");
        groupFilter.onNotify("selected", pspec -> reload());
        final MenuButton manage = new MenuButton();
        manage.setIconName("folder-new-symbolic");
        manage.setTooltipText("Groups");
        final Popover popover = new Popover();
        groupsBox.setMarginTop(6);
        groupsBox.setMarginBottom(6);
        groupsBox.setMarginStart(6);
        groupsBox.setMarginEnd(6);
        popover.setChild(groupsBox);
        manage.setPopover(popover);
        box.append(Ui.viewHeader("Favorites", filter, groupFilter, sort, manage));

        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");
        list.setValign(Align.START);
        list.onRowActivated(row -> {
            final Favorites.Favorite f = shown.get(row.getIndex());
            if (f.serviceId() == Video.YOUTUBE) {
                main.openChannel(f.url(), f.name());
            } else {
                main.openInBrowser(f.url());
            }
        });
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(900);
        clamp.setChild(list);
        clamp.setMarginTop(12);
        clamp.setMarginBottom(24);
        clamp.setMarginStart(12);
        clamp.setMarginEnd(12);
        final ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(clamp);

        final StatusPage empty = new StatusPage();
        empty.setIconName("starred-symbolic");
        empty.setTitle("No favorites");
        empty.setDescription("Use “Add to Favorites” on a channel or video page, or a channel "
                + "in search results. Favorites stay on this computer; their uploads come from "
                + "RSS, without an account.");
        stack.addNamed(empty, "empty");
        stack.addNamed(scroller, "list");
        stack.setVexpand(true);
        box.append(stack);

        fillGroups();
        Library.listen(Library.Topic.FAVORITES, () -> {
            fillGroups();
            dirty = true;
            if (visible) {
                reload();
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
        if (dirty) {
            reload();
        }
    }

    private void fillGroups() {
        groups = Favorites.groups();
        final List<String> names = new ArrayList<>();
        names.add("All groups");
        for (final Favorites.Group g : groups) {
            names.add(g.name() + " (" + g.memberCount() + ")");
        }
        final int selected = (int) groupFilter.getSelected();
        groupFilter.setModel(new StringList(names.toArray(String[]::new)));
        groupFilter.setSelected(selected < names.size() ? selected : 0);
        groupFilter.setVisible(!groups.isEmpty());

        Ui.clear(groupsBox);
        for (final Favorites.Group g : groups) {
            final Box row = new Box(Orientation.HORIZONTAL, 4);
            final Widget name = Ui.label(g.name());
            name.setHexpand(true);
            row.append(name);
            final Button rename = Button.fromIconName("document-edit-symbolic");
            rename.addCssClass("flat");
            rename.setTooltipText("Rename");
            rename.onClicked(() -> Dialogs.prompt(main.window(), "Rename group", null, g.name(),
                    "Rename", text -> Favorites.renameGroup(g.id(), text)));
            final Button delete = Button.fromIconName("user-trash-symbolic");
            delete.addCssClass("flat");
            delete.setTooltipText("Delete group (keeps the channels)");
            delete.onClicked(() -> Favorites.deleteGroup(g.id()));
            row.append(rename);
            row.append(delete);
            groupsBox.append(row);
        }
        if (!groups.isEmpty()) {
            groupsBox.append(new Separator(Orientation.HORIZONTAL));
        }
        final Button create = Button.withLabel("New group…");
        create.addCssClass("flat");
        create.onClicked(() -> Dialogs.prompt(main.window(), "New group",
                "Groups filter the feed, like folders.", "", "Create",
                Favorites::createGroup));
        groupsBox.append(create);
    }

    private void reload() {
        dirty = false;
        final int g = (int) groupFilter.getSelected();
        final Long group = g > 0 && g <= groups.size() ? groups.get(g - 1).id() : null;
        shown = Favorites.list(filter.getText(), group,
                Favorites.Sort.values()[(int) sort.getSelected()]);
        list.removeAll();
        for (final Favorites.Favorite f : shown) {
            list.append(row(f));
        }
        stack.setVisibleChildName(shown.isEmpty() && filter.getText().isBlank()
                && group == null ? "empty" : "list");
    }

    private Widget row(final Favorites.Favorite f) {
        final ActionRow row = new ActionRow();
        row.setTitle(Ui.markupSafe(f.name()));
        final List<String> parts = new ArrayList<>();
        if (f.lastUploadAt() != null && f.lastUploadAt() > 0) {
            parts.add("Last upload " + Format.relativeDate(date(f.lastUploadAt())));
        }
        parts.add("Added " + Format.absoluteDate(date(f.addedAt())));
        if (f.serviceId() != Video.YOUTUBE) {
            parts.add("not YouTube: kept for export");
        }
        row.setSubtitle(Ui.markupSafe(String.join(" · ", parts)));
        row.setActivatable(true);

        final Avatar avatar = new Avatar(40, f.name(), true);
        Images.load(ChannelCard.largerAvatar(f.avatarUrl()), avatar, avatar::setCustomImage);
        row.addPrefix(avatar);

        if (f.refreshError() != null) {
            final Image warning = Image.fromIconName("dialog-warning-symbolic");
            warning.setTooltipText("Last refresh failed: " + f.refreshError());
            warning.addCssClass("warning");
            row.addSuffix(warning);
        }
        final MenuButton groupButton = new MenuButton();
        groupButton.setIconName("folder-symbolic");
        groupButton.setTooltipText("Groups");
        groupButton.addCssClass("flat");
        groupButton.setValign(Align.CENTER);
        groupButton.setPopover(groupPopover(f));
        row.addSuffix(groupButton);

        final Button remove = Button.fromIconName("starred-symbolic");
        remove.addCssClass("flat");
        remove.setValign(Align.CENTER);
        remove.setTooltipText("Remove from Favorites");
        remove.onClicked(() -> {
            final Favorites.Favorite gone = f;
            Favorites.remove(f.url());
            main.toast("Removed " + gone.name() + " from Favorites", "Undo", () ->
                    Favorites.add(gone.serviceId(), gone.url(), gone.name(), gone.avatarUrl(),
                            gone.subscriberCount(), gone.description()));
        });
        row.addSuffix(remove);
        return row;
    }

    private Popover groupPopover(final Favorites.Favorite f) {
        final Box content = new Box(Orientation.VERTICAL, 4);
        content.setMarginTop(6);
        content.setMarginBottom(6);
        content.setMarginStart(6);
        content.setMarginEnd(6);
        final List<Long> member = Favorites.groupsOf(f.id());
        for (final Favorites.Group g : groups) {
            final CheckButton check = CheckButton.withLabel(g.name());
            check.setActive(member.contains(g.id()));
            content.append(check);
            check.onToggled(() -> Favorites.setInGroup(f.id(), g.id(), check.getActive()));
        }
        if (groups.isEmpty()) {
            content.append(Ui.label("No groups yet", "dim-label"));
        }
        final Button create = Button.withLabel("New group…");
        create.addCssClass("flat");
        create.onClicked(() -> Dialogs.prompt(main.window(), "New group", null, "", "Create",
                name -> Favorites.setInGroup(f.id(), Favorites.createGroup(name), true)));
        content.append(create);
        final Popover popover = new Popover();
        popover.setChild(content);
        return popover;
    }

    private static DateWrapper date(final long millis) {
        return new DateWrapper(OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis),
                ZoneOffset.UTC));
    }
}
