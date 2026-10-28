package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.service.RemotePlaylist;
import org.gnome.adw.ActionRow;
import org.gnome.adw.AlertDialog;
import org.gnome.adw.Clamp;
import org.gnome.adw.ResponseAppearance;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Entry;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.StreamingService.LinkType;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;

import java.util.List;

/**
 * Local playlists (Watch Later first), local copies of YouTube playlists, and bookmarked
 * YouTube playlists.
 */
final class PlaylistsView implements Shell.View {
    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final ListBox list = new ListBox();
    private List<Playlists.Playlist> playlists = List.of();
    private boolean dirty = true;
    private boolean visible;

    PlaylistsView(final MainWindow main) {
        this.main = main;
        final Button create = Button.withLabel("New playlist");
        create.addCssClass("suggested-action");
        create.onClicked(() -> Dialogs.prompt(main.window(), "New playlist", null, "", "Create",
                name -> main.openLocalPlaylist(Playlists.create(name))));
        final Button importRemote = Button.withLabel("Import from YouTube…");
        importRemote.onClicked(() -> importRemote(main));
        box.append(Ui.viewHeader("Playlists", importRemote, create));

        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");
        list.setValign(Align.START);
        list.onRowActivated(row -> open(playlists.get(row.getIndex())));
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
        scroller.setVexpand(true);
        box.append(scroller);

        Library.listen(Library.Topic.PLAYLISTS, () -> {
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

    private void open(final Playlists.Playlist p) {
        if (p.kind() == Playlists.Kind.BOOKMARK) {
            main.openPlaylist(p.remoteUrl(), p.name());
        } else {
            main.openLocalPlaylist(p.id());
        }
    }

    private void reload() {
        dirty = false;
        playlists = Playlists.list();
        list.removeAll();
        for (final Playlists.Playlist p : playlists) {
            final ActionRow row = new ActionRow();
            row.setTitle(Ui.markupSafe(p.name()));
            row.setActivatable(true);
            final String count = p.itemCount() + (p.itemCount() == 1 ? " video" : " videos");
            row.setSubtitle(Ui.markupSafe(switch (p.kind()) {
                case BOOKMARK -> "YouTube playlist" + (p.uploader() == null ? ""
                        : " by " + p.uploader()) + (p.streamCount() >= 0
                        ? " · " + p.streamCount() + " videos" : "") + " · bookmark";
                case LOCAL -> p.isSnapshot() ? count + " · local copy of a YouTube playlist"
                        : count;
            }));
            final String thumb = p.thumbnailUrl() != null ? p.thumbnailUrl()
                    : p.itemCount() > 0 ? Playlists.videos(p.id()).get(0).thumbnailUrl() : null;
            final Widget picture = VideoCard.thumbnail(thumb, false, null);
            picture.setMarginTop(6);
            picture.setMarginBottom(6);
            row.addPrefix(picture);
            if (p.isWatchLater()) {
                row.addSuffix(org.gnome.gtk.Image.fromIconName("alarm-symbolic"));
            }
            if (p.kind() == Playlists.Kind.BOOKMARK) {
                final Button remove = Button.fromIconName("user-trash-symbolic");
                remove.addCssClass("flat");
                remove.setValign(Align.CENTER);
                remove.setTooltipText("Remove bookmark");
                remove.onClicked(() -> Playlists.delete(p.id()));
                row.addSuffix(remove);
            }
            list.append(row);
        }
    }

    /** Asks for a YouTube playlist link, then copies it locally or bookmarks it. */
    static void importRemote(final MainWindow main) {
        final AlertDialog dialog = new AlertDialog("Import a YouTube playlist",
                "A local copy keeps the videos here (re-sync it any time). A bookmark only "
                        + "keeps the link; the videos load from YouTube when you open it.");
        final Entry entry = new Entry();
        entry.setPlaceholderText("https://www.youtube.com/playlist?list=…");
        dialog.setExtraChild(entry);
        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("bookmark", "Bookmark");
        dialog.addResponse("copy", "Make a local copy");
        dialog.setResponseAppearance("copy", ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse("copy");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            final String url = entry.getText().strip();
            if ("cancel".equals(response) || url.isEmpty()) {
                return;
            }
            if (Extractor.linkType(url) != LinkType.PLAYLIST) {
                main.toast("That isn't a YouTube playlist link");
                return;
            }
            if ("bookmark".equals(response)) {
                Ui.fetch(() -> PlaylistInfo.getInfo(Extractor.youtube(), url), info -> {
                    Playlists.bookmark(Video.YOUTUBE, info.getName(), info.getUrl(),
                            info.getUploaderName(), info.getThumbnailUrl(),
                            info.getStreamCount());
                    main.toast("Bookmarked " + info.getName());
                }, error -> main.toast(Ui.errorMessage(error)));
            } else {
                main.toast("Copying playlist…");
                Ui.async(() -> RemotePlaylist.fetch(url), copy -> {
                    final long id = Playlists.createSnapshot(copy.info().getName(),
                            copy.info().getUrl(), copy.info().getUploaderName(),
                            copy.info().getThumbnailUrl(), copy.videos());
                    main.toast("Copied " + copy.videos().size() + " videos");
                    main.openLocalPlaylist(id);
                }, error -> main.toast(Ui.errorMessage(error)));
            }
        });
        dialog.present(main.window());
    }
}
