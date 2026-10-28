package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Backup;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.player.PlayQueue;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.RemotePlaylist;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Clamp;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.StatusPage;
import org.gnome.adw.ToolbarView;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.EventSequenceState;
import org.gnome.gtk.GestureDrag;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Widget;
import io.github.jwharm.javagi.base.Out;

import java.util.List;

/**
 * A local playlist: play, shuffle or queue it, download it, drag its videos into order, and
 * rename, export, re-sync (local copies of YouTube playlists) or delete it.
 */
final class LocalPlaylistPage {
    private final MainWindow main;
    private final long id;
    private final NavigationPage page;
    private final Box content = new Box(Orientation.VERTICAL, 12);
    private final Label title = Ui.wrapping("", 0, "title-2");
    private final Label subtitle = Ui.label("", "dim-label");
    private final ListBox list = new ListBox();
    private final StatusPage empty = new StatusPage();
    private final Popover menu = new Popover();
    private List<Playlists.Item> items = List.of();
    private Playlists.Playlist playlist;
    private Runnable unlisten;

    // Drag state
    private int dragFrom = -1;
    private double dragStartY;
    private ListBoxRow dropRow;

    LocalPlaylistPage(final MainWindow main, final long id) {
        this.main = main;
        this.id = id;

        final MenuButton more = new MenuButton();
        more.setIconName("view-more-symbolic");
        more.setTooltipText("Playlist actions");
        more.setPopover(menu);
        final HeaderBar header = new HeaderBar();
        header.packEnd(more);

        final Box actions = new Box(Orientation.HORIZONTAL, 8);
        actions.append(button("Play all", "media-playback-start-symbolic", true,
                () -> play(0, false)));
        actions.append(button("Shuffle", "media-playlist-shuffle-symbolic", false,
                () -> play(0, true)));
        actions.append(button("Queue all", "view-list-ordered-symbolic", false, () -> {
            final boolean wasEmpty = PlayQueue.isEmpty();
            final int n = PlayQueue.append(videos());
            if (wasEmpty && n > 0) {
                main.playQueueCurrent();
            } else {
                main.toast("Added " + n + " videos to the queue");
            }
        }));
        actions.append(button("Download all", "folder-download-symbolic", false,
                () -> Dialogs.download(main, choice -> {
                    final int n = DownloadManager.enqueueAll(videos(), choice);
                    main.toast("Downloading " + n + " videos");
                })));

        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");
        list.onRowActivated(row -> play(row.getIndex(), false));
        empty.setIconName("view-list-symbolic");
        empty.setTitle("This playlist is empty");
        empty.setDescription("Add videos with “Add to playlist…” in any video's ⋮ menu.");
        empty.addCssClass("compact");

        content.setMarginTop(18);
        content.setMarginBottom(24);
        content.setMarginStart(18);
        content.setMarginEnd(18);
        content.append(title);
        content.append(subtitle);
        content.append(actions);
        content.append(list);
        content.append(empty);
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(960);
        clamp.setChild(content);
        final ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(clamp);

        final ToolbarView toolbar = new ToolbarView();
        toolbar.addTopBar(header);
        toolbar.setContent(scroller);
        page = new NavigationPage(toolbar, "Playlist");
        reload();
        // Only while shown: a popped page's widgets may already be freed (see README)
        page.onShowing(() -> {
            reload();
            unlisten = Library.listen(Library.Topic.PLAYLISTS, this::reload);
        });
        page.onHidden(() -> {
            if (unlisten != null) {
                unlisten.run();
                unlisten = null;
            }
        });
    }

    NavigationPage page() {
        return page;
    }

    private List<Video> videos() {
        return items.stream().map(Playlists.Item::video).toList();
    }

    private void play(final int start, final boolean shuffle) {
        if (PlayQueue.play(videos(), start, shuffle) != null) {
            main.playQueueCurrent();
        }
    }

    private void reload() {
        playlist = Playlists.get(id);
        if (playlist == null) {
            return;
        }
        items = Playlists.items(id);
        page.setTitle(playlist.name());
        title.setText(playlist.name());
        long seconds = 0;
        for (final Playlists.Item i : items) {
            seconds += Math.max(0, i.video().durationS());
        }
        subtitle.setText(Format.dot(items.size() + (items.size() == 1 ? " video" : " videos"),
                seconds > 0 ? Format.duration(seconds) : null,
                playlist.isSnapshot() ? "copy of a YouTube playlist" : null));
        list.removeAll();
        for (int i = 0; i < items.size(); i++) {
            list.append(row(items.get(i).video()));
        }
        list.setVisible(!items.isEmpty());
        empty.setVisible(items.isEmpty());
        fillMenu();
    }

    private ListBoxRow row(final Video video) {
        final Box box = new Box(Orientation.HORIZONTAL, 6);
        box.setMarginTop(4);
        box.setMarginBottom(4);
        box.setMarginStart(4);

        final Image handle = Image.fromIconName("list-drag-handle-symbolic");
        handle.addCssClass("drag-handle");
        handle.setTooltipText("Drag to reorder");
        handle.setValign(Align.CENTER);
        box.append(handle);

        final VideoCard.Decor decor = new VideoCard.Decor(null, null, anchor ->
                VideoMenu.show(main, anchor, video, List.of(new VideoMenu.Extra(
                        "Remove from " + playlist.name(), () -> Playlists.remove(id,
                        video.url()), true))));
        final Widget card = VideoCard.create(video.toItem(), VideoCard.Layout.ROW, main, decor);
        card.setHexpand(true);
        box.append(card);

        final ListBoxRow row = new ListBoxRow();
        row.setChild(box);
        // After the row has a parent, so the gesture's handlers can't be freed early
        final GestureDrag drag = new GestureDrag();
        drag.onDragBegin((x, y) -> {
            drag.setState(EventSequenceState.CLAIMED);
            dragFrom = row.getIndex();
            final Out<Double> lx = new Out<>();
            final Out<Double> ly = new Out<>();
            handle.translateCoordinates(list, x, y, lx, ly);
            dragStartY = ly.get() == null ? 0 : ly.get();
        });
        drag.onDragUpdate((dx, dy) -> highlight(list.getRowAtY((int) (dragStartY + dy))));
        drag.onDragEnd((dx, dy) -> {
            final ListBoxRow target = list.getRowAtY((int) (dragStartY + dy));
            highlight(null);
            final int from = dragFrom;
            dragFrom = -1;
            if (target != null && from >= 0 && target.getIndex() != from) {
                Playlists.move(id, from, target.getIndex());
            }
        });
        handle.addController(drag);
        return row;
    }

    private void highlight(final ListBoxRow row) {
        if (dropRow != null) {
            dropRow.removeCssClass("drop-target");
        }
        dropRow = row;
        if (row != null) {
            row.addCssClass("drop-target");
        }
    }

    private void fillMenu() {
        final Box box = new Box(Orientation.VERTICAL, 0);
        box.addCssClass("menu-box");
        if (!playlist.isWatchLater()) {
            box.append(menuItem("Rename…", () -> Dialogs.prompt(main.window(), "Rename playlist",
                    null, playlist.name(), "Rename", name -> Playlists.rename(id, name))));
        }
        if (playlist.isSnapshot()) {
            box.append(menuItem("Re-sync from YouTube", this::resync));
        }
        box.append(menuItem("Export…", () -> Dialogs.saveFile(main, "Export playlist",
                playlist.name().replaceAll("[/\\\\]", "_") + ".json", path ->
                        Ui.async(() -> {
                            Backup.exportPlaylist(id, path);
                            return null;
                        }, done -> main.toast("Exported to " + path.getFileName()),
                                error -> main.toast(Ui.errorMessage(error))))));
        if (playlist.isWatchLater()) {
            box.append(menuItem("Clear Watch Later", () -> Dialogs.confirm(main.window(),
                    "Clear Watch Later?", null, "Clear", true, () -> Playlists.clear(id))));
        } else {
            box.append(menuItem("Delete playlist", () -> Dialogs.confirm(main.window(),
                    "Delete “" + playlist.name() + "”?", "Its videos stay wherever else they "
                            + "are; downloads are kept.", "Delete", true, () -> {
                        Playlists.delete(id);
                        main.showView("playlists");
                    })));
        }
        menu.setChild(box);
    }

    private Button menuItem(final String label, final Runnable action) {
        final Button button = new Button();
        button.setChild(Ui.label(label));
        button.addCssClass("flat");
        button.addCssClass("menu-item");
        button.onClicked(() -> {
            menu.popdown();
            action.run();
        });
        return button;
    }

    private void resync() {
        final String url = playlist.remoteUrl();
        main.toast("Re-syncing from YouTube…");
        Ui.async(() -> RemotePlaylist.fetch(url), copy -> {
            Playlists.resync(id, copy.videos());
            main.toast("Synced: " + copy.videos().size() + " videos");
        }, error -> main.toast("Couldn't re-sync: " + Ui.errorMessage(error)));
    }

    private static Button button(final String label, final String icon, final boolean primary,
                                 final Runnable action) {
        final Button button = new Button();
        final org.gnome.adw.ButtonContent content = new org.gnome.adw.ButtonContent();
        content.setLabel(label);
        content.setIconName(icon);
        button.setChild(content);
        button.addCssClass("pill");
        if (primary) {
            button.addCssClass("suggested-action");
        }
        button.onClicked(action::run);
        return button;
    }
}
