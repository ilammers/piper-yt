package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.player.PlayQueue;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.RemotePlaylist;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Avatar;
import org.gnome.adw.Clamp;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.adw.ToolbarView;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;

import java.util.ArrayList;
import java.util.List;

/** A playlist: thumbnail, title and creator above its videos. */
final class PlaylistPage {
    private static final int CONTENT_WIDTH = 960;
    private static final int THUMB_WIDTH = 320;

    private final MainWindow main;
    private final String url;
    private final NavigationPage page;
    private final Stack stack = new Stack();
    private final StatusPage errorStatus = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();
    private final ItemGrid grid;
    private final Box content = new Box(Orientation.VERTICAL, 6);

    private PlaylistInfo info;

    PlaylistPage(final MainWindow main, final String url, final String name) {
        this.main = main;
        this.url = url;
        grid = new ItemGrid(main, VideoCard.Layout.ROW, true);

        final Button browser = Button.fromIconName("web-browser-symbolic");
        browser.setTooltipText("Open in browser");
        browser.onClicked(() -> main.openInBrowser(info != null ? info.getUrl() : url));
        final HeaderBar header = new HeaderBar();
        header.packEnd(browser);

        final Spinner spinner = new Spinner();
        spinner.setSizeRequest(48, 48);
        spinner.setHalign(Align.CENTER);
        spinner.setValign(Align.CENTER);

        errorStatus.setIconName("dialog-error-symbolic");
        errorStatus.setTitle("Couldn't load playlist");
        final Button retry = Button.withLabel("Retry");
        retry.addCssClass("pill");
        retry.setHalign(Align.CENTER);
        retry.onClicked(this::load);
        errorStatus.setChild(retry);

        // Parented now: java-gi frees widgets with signal handlers while they have no parent
        content.setMarginTop(18);
        content.setMarginBottom(12);
        content.append(grid.widget());
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(CONTENT_WIDTH);
        clamp.setChild(content);
        scroller.setChild(clamp);
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.onEdgeReached(pos -> {
            if (pos == PositionType.BOTTOM) {
                grid.loadMore();
            }
        });

        stack.addNamed(spinner, "loading");
        stack.addNamed(errorStatus, "error");
        stack.addNamed(scroller, "content");

        final ToolbarView toolbar = new ToolbarView();
        toolbar.addTopBar(header);
        toolbar.setContent(stack);
        page = new NavigationPage(toolbar, name == null ? "Playlist" : name);
        load();
    }

    NavigationPage page() {
        return page;
    }

    private void load() {
        stack.setVisibleChildName("loading");
        Ui.fetch(() -> PlaylistInfo.getInfo(Extractor.youtube(), url),
                this::show,
                error -> {
                    errorStatus.setDescription(Ui.errorMessage(error));
                    stack.setVisibleChildName("error");
                });
    }

    private void show(final PlaylistInfo loaded) {
        info = loaded;
        page.setTitle(info.getName());

        final String playlistUrl = info.getUrl();
        grid.reset(info.getRelatedItems(), info.getNextPage(),
                p -> PlaylistInfo.getMoreItems(Extractor.youtube(), playlistUrl, p));

        content.prepend(buildHeading());
        if (grid.isEmpty()) {
            final StatusPage empty = new StatusPage();
            empty.setIconName("view-list-symbolic");
            empty.setTitle("This playlist is empty");
            empty.addCssClass("compact");
            content.append(empty);
        }
        stack.setVisibleChildName("content");
    }

    /**
     * Adds the pages not loaded yet to the queue as they arrive (paced like a playlist copy),
     * until the queue is replaced or cleared.
     */
    private void queueRest(final String playlistUrl) {
        final Page next = grid.nextPage();
        if (next == null) {
            return;
        }
        final int generation = PlayQueue.generation();
        Ui.background(() -> {
            try {
                RemotePlaylist.fetchRest(playlistUrl, next, videos -> {
                    if (PlayQueue.generation() != generation) {
                        return false;
                    }
                    Ui.runOnMain(() -> {
                        if (PlayQueue.generation() == generation) {
                            PlayQueue.append(videos);
                        }
                    });
                    return true;
                });
            } catch (final Exception e) {
                Ui.runOnMain(() -> main.toast("Couldn't queue the rest of the playlist: "
                        + Ui.errorMessage(e)));
            }
        });
    }

    private Box buildHeading() {
        final Box box = new Box(Orientation.HORIZONTAL, 18);
        box.setMarginStart(18);
        box.setMarginEnd(18);

        final Picture thumb = new Picture();
        thumb.setContentFit(ContentFit.COVER);
        thumb.setCanShrink(true);
        thumb.setOverflow(Overflow.HIDDEN);
        thumb.addCssClass("thumbnail");
        thumb.setSizeRequest(THUMB_WIDTH, THUMB_WIDTH * 9 / 16);
        thumb.setValign(Align.START);
        Images.load(info.getThumbnailUrl(), THUMB_WIDTH * 2, thumb, thumb::setPaintable);
        final Clamp thumbClamp = new Clamp();
        thumbClamp.setMaximumSize(THUMB_WIDTH);
        thumbClamp.setTighteningThreshold(THUMB_WIDTH);
        thumbClamp.setChild(thumb);
        box.append(thumbClamp);

        final Box text = new Box(Orientation.VERTICAL, 10);
        text.setValign(Align.CENTER);
        text.setHexpand(true);
        final Label title = Ui.wrapping(info.getName(), 0, "title-2");
        title.setSelectable(true);
        text.append(title);
        final Widget uploader = buildUploader();
        if (uploader != null) {
            text.append(uploader);
        }
        final long count = info.getStreamCount();
        if (count >= 0) {
            text.append(Ui.label(Format.count(count) + (count == 1 ? " video" : " videos"),
                    "dim-label"));
        }
        text.append(buildActions());
        box.append(text);
        return box;
    }

    /** Play/shuffle/queue what's loaded; copy, bookmark or download the whole playlist. */
    private Widget buildActions() {
        final org.gnome.gtk.FlowBox actions = new org.gnome.gtk.FlowBox();
        actions.setSelectionMode(org.gnome.gtk.SelectionMode.NONE);
        actions.setMaxChildrenPerLine(6);
        actions.setColumnSpacing(6);
        actions.setRowSpacing(6);
        final String playlistUrl = info.getUrl();
        actions.append(action("Play all", "media-playback-start-symbolic", true, () -> {
            if (PlayQueue.play(grid.videos(), 0, false) != null) {
                main.playQueueCurrent();
                queueRest(playlistUrl);
            }
        }));
        actions.append(action("Shuffle", "media-playlist-shuffle-symbolic", false, () -> {
            final Page next = grid.nextPage();
            if (next == null) {
                if (PlayQueue.play(grid.videos(), 0, true) != null) {
                    main.playQueueCurrent();
                }
                return;
            }
            // A fair shuffle needs every video first
            main.toast("Loading the whole playlist to shuffle it…");
            final List<Video> all = new ArrayList<>(grid.videos());
            Ui.async(() -> {
                RemotePlaylist.fetchRest(playlistUrl, next, page -> {
                    all.addAll(page);
                    return true;
                });
                return all;
            }, videos -> {
                if (PlayQueue.play(videos, 0, true) != null) {
                    main.playQueueCurrent();
                }
            }, error -> main.toast("Couldn't load the playlist: " + Ui.errorMessage(error)));
        }));
        actions.append(action("Queue all", "view-list-ordered-symbolic", false, () -> {
            final boolean wasEmpty = PlayQueue.isEmpty();
            final int n = PlayQueue.append(grid.videos());
            if (wasEmpty && n > 0) {
                main.playQueueCurrent();
            } else {
                main.toast("Added " + n + " videos to the queue"
                        + (grid.nextPage() != null ? ", more on the way" : ""));
            }
            queueRest(playlistUrl);
        }));
        actions.append(action("Save a copy", "document-save-symbolic", false, () -> {
            final Long existing = Playlists.snapshotId(playlistUrl);
            if (existing != null) {
                main.openLocalPlaylist(existing);
                return;
            }
            main.toast("Copying playlist…");
            Ui.async(() -> RemotePlaylist.fetch(playlistUrl), copy -> {
                final long id = Playlists.createSnapshot(info.getName(), playlistUrl,
                        info.getUploaderName(), info.getThumbnailUrl(), copy.videos());
                main.toast("Saved " + copy.videos().size() + " videos", "Open",
                        () -> main.openLocalPlaylist(id));
            }, error -> main.toast(Ui.errorMessage(error)));
        }));
        final org.gnome.gtk.ToggleButton bookmark = new org.gnome.gtk.ToggleButton();
        final org.gnome.adw.ButtonContent bookmarkContent = new org.gnome.adw.ButtonContent();
        bookmarkContent.setLabel("Bookmark");
        bookmarkContent.setIconName("user-bookmarks-symbolic");
        bookmark.setChild(bookmarkContent);
        bookmark.addCssClass("pill");
        bookmark.setActive(Playlists.bookmarkId(playlistUrl) != null);
        bookmark.setTooltipText("Keep a link to this playlist in Playlists");
        bookmark.onToggled(() -> {
            final Long id = Playlists.bookmarkId(playlistUrl);
            if (bookmark.getActive() && id == null) {
                Playlists.bookmark(Video.YOUTUBE, info.getName(), playlistUrl,
                        info.getUploaderName(), info.getThumbnailUrl(), info.getStreamCount());
            } else if (!bookmark.getActive() && id != null) {
                Playlists.delete(id);
            }
        });
        actions.append(bookmark);
        actions.append(action("Download all", "folder-download-symbolic", false,
                () -> Dialogs.download(main, choice -> {
                    main.toast("Fetching the playlist to download it…");
                    Ui.async(() -> DownloadManager.enqueueRemotePlaylist(playlistUrl, choice),
                            n -> main.toast("Downloading " + n + " videos", "Show",
                                    () -> main.showView("downloads")),
                            error -> main.toast(Ui.errorMessage(error)));
                })));
        return actions;
    }

    private static Button action(final String label, final String icon, final boolean primary,
                                 final Runnable onClick) {
        final org.gnome.adw.ButtonContent content = new org.gnome.adw.ButtonContent();
        content.setLabel(label);
        content.setIconName(icon);
        final Button button = new Button();
        button.setChild(content);
        button.addCssClass("pill");
        if (primary) {
            button.addCssClass("suggested-action");
        }
        button.onClicked(onClick::run);
        return button;
    }

    /** The creator as a button to their channel, or null for playlists without one (mixes). */
    private Widget buildUploader() {
        final String name = info.getUploaderName();
        if (name == null || name.isBlank()) {
            return null;
        }
        final Box content = new Box(Orientation.HORIZONTAL, 8);
        final Avatar avatar = new Avatar(28, name, true);
        Images.load(info.getUploaderAvatarUrl(), avatar, avatar::setCustomImage);
        content.append(avatar);
        content.append(Ui.label(name, "heading"));

        final String channelUrl = info.getUploaderUrl();
        if (channelUrl == null || channelUrl.isBlank()) {
            return content;
        }
        final Button button = new Button();
        button.setChild(content);
        button.addCssClass("flat");
        button.addCssClass("channel-button");
        button.setHalign(Align.START);
        button.setTooltipText("Go to channel");
        button.onClicked(() -> main.openChannel(channelUrl, name));
        return button;
    }
}
