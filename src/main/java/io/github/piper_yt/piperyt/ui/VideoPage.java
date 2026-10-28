package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Downloads;
import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Feed;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.HomeStore;
import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.player.MpvPlayer;
import io.github.piper_yt.piperyt.player.PlayQueue;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Avatar;
import org.gnome.adw.Clamp;
import org.gnome.glib.GLib;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.Spinner;
import org.gnome.adw.StatusPage;
import org.gnome.adw.ToolbarView;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Expander;
import org.gnome.gtk.Grid;
import org.gnome.gtk.GridLayoutChild;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Picture;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Stack;
import org.gnome.gtk.Switch;
import org.gnome.gtk.ToggleButton;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Video details: thumbnail, play controls, description, comments, and an "Up next" list of
 * related videos (the Home picks when YouTube gives none). Wide pages show Up next in a column
 * beside the details, like YouTube's; narrow ones show it above the comments. A downloaded
 * video plays from its file (and opens even when YouTube can't be reached); playback is
 * recorded in history and resumed, when history is on.
 */
final class VideoPage {
    private static final String MPV_MISSING =
            "mpv is not installed. Install it with: sudo dnf install mpv";
    private static final String LOCAL_LABEL = "Downloaded file";
    private static final int HERO_HEIGHT = 400;
    private static final int HERO_WIDTH = HERO_HEIGHT * 16 / 9;
    private static final int CONTENT_WIDTH = 960;
    private static final int UNCLAMPED = 100_000;
    /** Room left under a theater-mode player for the title, like YouTube's. */
    private static final int THEATER_BELOW = 140;
    private static final int MARGIN = 18;
    /** Page width from which Up next sits beside the details instead of below them. */
    private static final int WIDE_MIN = 1000;
    /** The details column next to Up next. */
    private static final int PRIMARY_MAX = 1200;
    private static final int SIDEBAR_WIDTH = 402;
    private static final int SIDEBAR_MIN = 340;
    /** The player's least height beside Up next. */
    private static final int MIN_HERO = 200;
    /** Up next rows shown above the comments on a narrow page, before "Show more". */
    private static final int NARROW_UP_NEXT = 5;
    private static final int AUTOPLAY_SECONDS = 8;
    private static final Preferences PREFS = Preferences.userNodeForPackage(VideoPage.class);
    private static final String PREF_THEATER = "theater";

    private final MainWindow main;
    private final String url;
    private final boolean autoplay;
    /** Set by the mini player's "back to the video" once the page was closed; -1 if none. */
    private double startOverride;
    private final NavigationPage page;
    private final Stack stack = new Stack();
    private final StatusPage errorStatus = new StatusPage();
    private final ScrolledWindow scroller = new ScrolledWindow();

    private final ToolbarView toolbar = new ToolbarView();
    /**
     * Columns: details, then Up next. Layouts move children between cells instead of between
     * parents, since the player's GLArea must never be reparented.
     */
    private final Grid grid = new Grid();
    private final Box content = new Box(Orientation.VERTICAL, 18);
    private final Clamp contentClamp = new Clamp();
    private final Clamp commentsClamp = new Clamp();
    private final Clamp upNextClamp = new Clamp();
    private final Clamp heroClamp = new Clamp();
    private final Stack hero = new Stack();
    private final Box playerSlot = new Box(Orientation.VERTICAL, 0);
    private PlayerView player;
    private boolean fullscreen;
    private boolean wide;
    private int heroHeight;
    private boolean theater = PREFS.getBoolean(PREF_THEATER, true);
    private final ToggleButton theaterButton = new ToggleButton();

    private StreamInfo info;
    /** What the library stores about this video; from the download when offline. */
    private Video video;
    /** A downloaded copy, if there is one whose file still exists. */
    private Downloads.Download local;
    private List<MpvPlayer.Quality> qualities = List.of();
    private DropDown qualityDropDown;
    private boolean localInDropDown;
    /** Read by external mpv's progress thread. */
    private volatile long historyId = -1;
    /** What's playing in the embedded player, for "Start over". */
    private MpvPlayer.Source playing;
    private List<MpvPlayer.SkipSegment> playingSkip = List.of();
    private final Box queueBar = new Box(Orientation.HORIZONTAL, 8);
    private Runnable unlistenQueue;

    private final ListBox commentList = new ListBox();
    private final Stack commentStack = new Stack();
    private final StatusPage commentStatus = new StatusPage();
    private final Button moreComments = Button.withLabel("Load more comments");
    private Page nextCommentPage;

    private final List<InfoItem> upNext = new ArrayList<>();
    private final List<ListBoxRow> upNextRows = new ArrayList<>();
    /** Rows dismissed with "Not interested" or "Don't recommend". */
    private final Set<ListBoxRow> dismissed = new HashSet<>();
    private final Box upNextBox = new Box(Orientation.VERTICAL, 12);
    private final Button moreUpNext = Button.withLabel("Show more");
    private boolean upNextExpanded;

    private final Box autoplayCard = new Box(Orientation.VERTICAL, 6);
    private final Label autoplayCountdown = new Label("");
    private final Label autoplayTitle = Ui.wrapping("", 2);
    private final Button autoplayPlay = Button.withLabel("Play now");
    /** Bumped to cancel a running countdown. */
    private int autoplayToken;
    private StreamInfoItem autoplayNext;

    /**
     * @param startSeconds where to start when it autoplays, instead of the resume position;
     *                     negative for none (the mini player's position when it was closed)
     */
    VideoPage(final MainWindow main, final String url, final String title,
              final boolean autoplay, final double startSeconds) {
        this.main = main;
        this.url = url;
        this.autoplay = autoplay;
        this.startOverride = startSeconds;
        local = Downloads.local(url, true);

        final Button browser = Button.fromIconName("web-browser-symbolic");
        browser.setTooltipText("Open in browser");
        browser.onClicked(() -> main.openInBrowser(info != null ? info.getUrl() : url));
        theaterButton.setIconName("zoom-fit-best-symbolic");
        theaterButton.setTooltipText("Theater mode (T)");
        theaterButton.setActive(theater);
        theaterButton.onToggled(() -> setTheater(theaterButton.getActive()));
        final HeaderBar header = new HeaderBar();
        header.packEnd(browser);
        header.packEnd(theaterButton);
        header.packEnd(main.sleepTimer().button());

        final Spinner spinner = new Spinner();
        spinner.setSizeRequest(48, 48);
        spinner.setHalign(Align.CENTER);
        spinner.setValign(Align.CENTER);

        errorStatus.setIconName("dialog-error-symbolic");
        errorStatus.setTitle("Couldn't load video");
        final Button retry = Button.withLabel("Retry");
        retry.addCssClass("pill");
        retry.setHalign(Align.CENTER);
        retry.onClicked(this::load);
        errorStatus.setChild(retry);

        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);

        stack.addNamed(spinner, "loading");
        stack.addNamed(errorStatus, "error");
        stack.addNamed(scroller, "content");

        toolbar.addTopBar(header);
        toolbar.setContent(stack);
        page = new NavigationPage(toolbar, title == null ? "Video" : title);
        page.onHidden(() -> {
            cancelAutoplay();
            setFullscreen(false);
            if (player != null && !main.mini().isFrom(this) && !handOff()) {
                player.pause();
                saveProgress();
            }
            if (unlistenQueue != null) {
                unlistenQueue.run();
                unlistenQueue = null;
            }
        });
        page.onShowing(() -> {
            // Back on the page whose video the mini player has: continue it here
            if (main.mini().isFrom(this)) {
                final MiniPlayer.Handoff handoff = main.mini().reclaim();
                if (handoff != null) {
                    resumeFrom(handoff);
                }
            }
            if (unlistenQueue == null) {
                unlistenQueue = PlayQueue.listen(this::updateQueueBar);
            }
            updateQueueBar();
        });

        load();
    }

    NavigationPage page() {
        return page;
    }

    /** Releases the player once the page has been popped for good. */
    /** The sleep timer ran out. */
    void pauseForSleep() {
        cancelAutoplay();
        setFullscreen(false);
        if (player != null && !main.mini().isFrom(this)) {
            player.pause();
            saveProgress();
        }
    }

    void dispose() {
        if (player == null || main.mini().isFrom(this) || handOff()) {
            return;
        }
        saveProgress();
        player.dispose();
    }

    /**
     * Continues this page's video in the mini player, when that's on and the video is playing
     * and no other video page took over. Frees this page's player either way it goes.
     */
    private boolean handOff() {
        if (!Settings.get(Settings.MINI_PLAYER) || !isPlaying() || player.isPaused()
                || playing == null || main.isVideoPageVisible()) {
            return false;
        }
        final MiniPlayer.Handoff handoff = new MiniPlayer.Handoff(this, url,
                video == null ? page.getTitle() : video.title(), playing, playingSkip,
                player.position(), historyId);
        releasePlayer();
        main.mini().take(handoff);
        return true;
    }

    /** Plays what the mini player was playing, from where it got to. */
    void resumeFrom(final MiniPlayer.Handoff handoff) {
        if (PlayerView.isSupported()) {
            playEmbedded(handoff.source(), handoff.skip(), handoff.position());
        }
    }

    private void load() {
        stack.setVisibleChildName("loading");
        Ui.fetch(() -> StreamInfo.getInfo(Extractor.youtube(), url),
                this::show,
                error -> {
                    if (local != null && content.getFirstChild() == null) {
                        showOffline(Ui.errorMessage(error));
                        return;
                    }
                    errorStatus.setDescription(Ui.errorMessage(error));
                    stack.setVisibleChildName("error");
                });
    }

    private void show(final StreamInfo loaded) {
        info = loaded;
        video = Video.from(loaded);
        page.setTitle(info.getName());
        qualities = MpvPlayer.qualities(info);
        // RSS can't tell livestreams, shorts or durations; teach the feed cache
        Ui.background(() -> Feed.learn(video));

        content.append(buildHeading());
        content.append(buildControls());
        content.append(queueBar);

        final String description = Format.descriptionMarkup(info.getDescription());
        if (!description.isBlank()) {
            content.append(buildDescription(description));
        }
        commentsClamp.setChild(buildComments());
        finishLayout(info.getThumbnailUrl(), info.getRelatedItems());
        loadComments();
        if (autoplay) {
            play(false);
        }
    }

    /** YouTube couldn't be reached, but the video is downloaded: a page for the file. */
    private void showOffline(final String why) {
        video = local.video();
        page.setTitle(video.title());
        final Label title = Ui.wrapping(video.title(), 0, "title-2");
        title.setSelectable(true);
        content.append(title);
        content.append(Ui.wrapping(Format.dot(video.uploader(), "playing the downloaded file"),
                0, "dim-label"));
        content.append(buildControls());
        content.append(queueBar);
        content.append(Ui.wrapping("Couldn't reach YouTube: " + why, 0, "dim-label", "caption"));
        finishLayout(video.thumbnailUrl(), List.of());
        if (autoplay) {
            play(false);
        }
    }

    private void finishLayout(final String thumbnailUrl, final List<InfoItem> related) {
        contentClamp.setChild(content);
        content.setMarginTop(MARGIN);
        content.setMarginBottom(MARGIN);
        content.setMarginStart(MARGIN);
        content.setMarginEnd(MARGIN);

        for (final Widget w : new Widget[]{heroClamp, contentClamp, commentsClamp, upNextClamp}) {
            w.setValign(Align.START);
        }
        // The details column takes the width Up next leaves; the clamps keep text readable
        heroClamp.setHexpand(false);
        upNextClamp.setHexpand(false);
        contentClamp.setHexpand(true);
        commentsClamp.setHexpand(true);
        // Up next may be taller than what it spans; the comments row takes the slack
        commentsClamp.setVexpand(true);
        grid.attach(buildHero(thumbnailUrl), 0, 0, 1, 1);
        grid.attach(contentClamp, 0, 1, 1, 1);
        grid.attach(commentsClamp, 0, 2, 1, 1);
        grid.attach(buildUpNext(related), 1, 0, 1, 1);
        scroller.setChild(grid);
        stack.setVisibleChildName("content");
        updateLayout();
        updateQueueBar();
        // The layout and player height follow the page size; measured before each frame's layout
        scroller.addTickCallback((widget, clock) -> {
            final boolean nowWide = scroller.getWidth() >= WIDE_MIN;
            if (nowWide != wide) {
                wide = nowWide;
                updateLayout();
            } else if (!fullscreen) {
                final int height = heroHeightForLayout();
                if (height != heroHeight) {
                    setHeroHeight(height);
                }
            }
            return true;
        });
    }

    private Clamp buildHero(final String thumbnailUrl) {
        final Picture picture = new Picture();
        picture.setContentFit(ContentFit.COVER);
        picture.setCanShrink(true);
        picture.addCssClass("thumbnail");
        picture.setOverflow(Overflow.HIDDEN);
        // The hero's own size request sets the height; beside Up next it can be under HERO_HEIGHT
        picture.setSizeRequest(-1, MIN_HERO);
        Images.load(thumbnailUrl, picture, picture::setPaintable);

        final Button play = Button.fromIconName("media-playback-start-symbolic");
        play.addCssClass("osd");
        play.addCssClass("circular");
        play.setSizeRequest(72, 72);
        play.setHalign(Align.CENTER);
        play.setValign(Align.CENTER);
        play.setTooltipText("Play");
        play.onClicked(() -> play(false));

        final Overlay overlay = new Overlay();
        overlay.setChild(picture);
        overlay.addOverlay(play);

        playerSlot.addCssClass("thumbnail");
        playerSlot.setOverflow(Overflow.HIDDEN);
        hero.addNamed(overlay, "thumbnail");
        hero.addNamed(playerSlot, "player");
        // GTK doesn't size a Picture height-for-width, so the hero gets an explicit height
        final Overlay heroOverlay = new Overlay();
        heroOverlay.setChild(hero);
        heroOverlay.addOverlay(buildAutoplayCard());
        heroClamp.setChild(heroOverlay);
        return heroClamp;
    }

    private Box buildHeading() {
        final Box box = new Box(Orientation.VERTICAL, 12);

        final Label title = Ui.wrapping(info.getName(), 0, "title-2");
        title.setSelectable(true);
        box.append(title);

        final String views = info.getViewCount() >= 0
                ? Format.count(info.getViewCount())
                + (MpvPlayer.isLive(info) ? " watching" : " views") : "";
        final String likes = info.getLikeCount() >= 0
                ? Format.count(info.getLikeCount()) + " likes" : "";
        box.append(Ui.wrapping(Format.dot(views, Format.absoluteDate(info.getUploadDate()), likes), 0,
                "dim-label"));

        final Box channel = new Box(Orientation.HORIZONTAL, 12);
        final Avatar avatar = new Avatar(44, info.getUploaderName(), true);
        Images.load(info.getUploaderAvatarUrl(), avatar, avatar::setCustomImage);
        channel.append(avatar);
        final Box names = new Box(Orientation.VERTICAL, 2);
        names.setValign(Align.CENTER);
        names.append(Ui.label(info.getUploaderName(), "heading"));
        if (info.getUploaderSubscriberCount() >= 0) {
            names.append(Ui.label(Format.count(info.getUploaderSubscriberCount())
                    + " subscribers", "dim-label", "caption"));
        }
        channel.append(names);

        final String channelUrl = info.getUploaderUrl();
        if (channelUrl == null || channelUrl.isBlank()) {
            box.append(channel);
            return box;
        }
        final Button channelButton = new Button();
        channelButton.setChild(channel);
        channelButton.addCssClass("flat");
        channelButton.addCssClass("channel-button");
        channelButton.setHalign(Align.START);
        channelButton.setTooltipText("Go to channel");
        channelButton.onClicked(() -> main.openChannel(channelUrl, info.getUploaderName()));

        final Box row = new Box(Orientation.HORIZONTAL, 12);
        row.append(channelButton);
        final Widget favorite = FavoriteButton.create(main, channelUrl, info.getUploaderName(),
                info.getUploaderAvatarUrl(), info.getUploaderSubscriberCount(), null);
        favorite.setValign(Align.CENTER);
        row.append(favorite);
        box.append(row);
        return box;
    }

    private Box buildControls() {
        final Box box = new Box(Orientation.HORIZONTAL, 12);

        final List<String> labels = new ArrayList<>();
        localInDropDown = local != null && !local.isAudioOnly();
        if (localInDropDown) {
            labels.add(LOCAL_LABEL);
        }
        for (final MpvPlayer.Quality q : qualities) {
            labels.add(q.label());
        }
        qualityDropDown = DropDown.fromStrings(labels.isEmpty()
                ? new String[]{"No video streams"} : labels.toArray(String[]::new));
        qualityDropDown.setTooltipText(localInDropDown
                ? "Quality (the downloaded file plays from disk)" : "Quality");
        qualityDropDown.setSensitive(!labels.isEmpty());
        if (!qualities.isEmpty() && !localInDropDown) {
            qualityDropDown.setSelected(MpvPlayer.defaultQualityIndex(qualities));
        }

        final Button play = Button.withLabel("Play");
        play.addCssClass("suggested-action");
        play.addCssClass("pill");
        play.setSensitive(!labels.isEmpty() || local != null);
        play.onClicked(() -> play(false));

        final Button audio = Button.withLabel("Audio only");
        audio.addCssClass("pill");
        audio.setSensitive(local != null || info != null && (MpvPlayer.bestAudio(info) != null
                || (MpvPlayer.isLive(info) && info.getHlsUrl() != null)));
        audio.onClicked(() -> play(true));

        box.append(play);
        box.append(audio);
        box.append(qualityDropDown);

        // Library actions, aligned right
        final Box spacer = new Box(Orientation.HORIZONTAL, 0);
        spacer.setHexpand(true);
        box.append(spacer);
        final long watchLater = Playlists.watchLaterId();
        final ToggleButton later = new ToggleButton();
        later.setIconName("alarm-symbolic");
        later.setActive(Playlists.contains(watchLater, video.url()));
        later.setTooltipText(later.getActive() ? "In Watch Later" : "Save to Watch Later");
        later.setValign(Align.CENTER);
        later.addCssClass("circular");
        later.onToggled(() -> {
            if (later.getActive()) {
                Playlists.add(watchLater, video);
            } else {
                Playlists.remove(watchLater, video.url());
            }
            later.setTooltipText(later.getActive() ? "In Watch Later" : "Save to Watch Later");
        });
        box.append(later);
        final Button playlist = Button.fromIconName("list-add-symbolic");
        playlist.setTooltipText("Add to playlist");
        playlist.addCssClass("circular");
        playlist.setValign(Align.CENTER);
        playlist.onClicked(() -> Dialogs.playlistPicker(main, video));
        box.append(playlist);
        if (info != null && !MpvPlayer.isLive(info)) {
            final Button download = Button.fromIconName("folder-download-symbolic");
            download.setTooltipText(local != null ? "Downloaded · download again" : "Download");
            download.addCssClass("circular");
            download.setValign(Align.CENTER);
            download.onClicked(() -> Dialogs.download(main, choice -> {
                DownloadManager.enqueue(video, choice);
                main.toast("Downloading “" + video.title() + "”", "Show",
                        () -> main.showView("downloads"));
            }));
            box.append(download);
        }
        return box;
    }

    /** "Queue · 3 of 12 · Up next: …" with previous/next, while this video is the queue's. */
    private void updateQueueBar() {
        Ui.clear(queueBar);
        final boolean inQueue = PlayQueue.isCurrent(url) && PlayQueue.items().size() > 1;
        queueBar.setVisible(inQueue);
        if (!inQueue) {
            return;
        }
        final Button previous = Button.fromIconName("media-skip-backward-symbolic");
        previous.setTooltipText("Previous in queue");
        previous.setSensitive(PlayQueue.hasPrevious());
        previous.addCssClass("flat");
        previous.onClicked(() -> {
            if (PlayQueue.previous() != null) {
                main.playQueueCurrent();
            }
        });
        final Button next = Button.fromIconName("media-skip-forward-symbolic");
        next.setTooltipText("Next in queue");
        next.setSensitive(PlayQueue.hasNext());
        next.addCssClass("flat");
        next.onClicked(() -> {
            if (PlayQueue.next() != null) {
                main.playQueueCurrent();
            }
        });
        final List<Video> items = PlayQueue.items();
        final int index = PlayQueue.index();
        final String upNext = PlayQueue.hasNext() ? "Up next: " + items.get(index + 1).title()
                : "Last in the queue";
        final Label label = Ui.wrapping(Format.dot("Queue " + (index + 1) + " of " + items.size(),
                upNext), 1, "dim-label");
        label.setHexpand(true);
        queueBar.append(previous);
        queueBar.append(label);
        queueBar.append(next);
    }

    private Expander buildDescription(final String markup) {
        final Label label = Ui.wrapping("", 0);
        label.setMarkup(markup);
        label.setSelectable(true);
        label.setMarginTop(12);
        main.handleLinks(label);

        final Expander expander = new Expander("Description");
        expander.setChild(label);
        expander.setExpanded(markup.length() < 400);
        return expander;
    }

    private Box buildComments() {
        commentList.setSelectionMode(SelectionMode.NONE);
        commentList.addCssClass("boxed-list");
        moreComments.addCssClass("pill");
        moreComments.setHalign(Align.CENTER);
        moreComments.setVisible(false);
        moreComments.onClicked(this::loadMoreComments);
        final Box comments = new Box(Orientation.VERTICAL, 12);
        comments.append(commentList);
        comments.append(moreComments);

        final Spinner spinner = new Spinner();
        spinner.setSizeRequest(32, 32);
        spinner.setMarginTop(24);
        commentStack.addNamed(spinner, "loading");
        commentStack.addNamed(commentStatus, "status");
        commentStack.addNamed(comments, "comments");
        commentStack.setVisibleChildName("loading");
        commentStack.setVhomogeneous(false);

        final Box box = new Box(Orientation.VERTICAL, 12);
        box.setMarginStart(MARGIN);
        box.setMarginEnd(MARGIN);
        box.setMarginBottom(32);
        box.append(Ui.label("Comments", "title-4"));
        box.append(commentStack);
        return box;
    }

    /**
     * YouTube's related videos and mixes, less hidden videos and blocked channels; the Home
     * picks when YouTube gives none (or can't be reached). Videos from Favorites come first and
     * say so, and videos watched to the end go last, dimmed. Hidden when there's nothing to show.
     */
    private Clamp buildUpNext(final List<InfoItem> related) {
        final Set<String> hidden = HomeStore.hiddenUrls();
        final Set<String> blocked = HomeStore.blockedUrls();
        final String self = Video.canonicalUrl(url);
        final List<InfoItem> favorites = new ArrayList<>();
        final List<InfoItem> others = new ArrayList<>();
        final List<InfoItem> watched = new ArrayList<>();
        final java.util.Map<InfoItem, String[]> notes = new java.util.HashMap<>();
        for (final InfoItem item : related) {
            if (item instanceof PlaylistInfoItem) {
                others.add(item);
                continue;
            }
            if (!(item instanceof StreamInfoItem stream) || !Extractor.isWatchable(stream)) {
                continue;
            }
            final Video v = Video.from(stream);
            if (v.url().equals(self) || hidden.contains(v.url())
                    || blocked.contains(v.uploaderUrl())) {
                continue;
            }
            if (Badges.isWatched(v.url())) {
                watched.add(item);
            } else if (v.uploaderUrl() != null && Favorites.isFavorite(v.uploaderUrl())) {
                favorites.add(item);
                notes.put(item, new String[]{"From Favorites",
                        stream.getUploaderName() + " is in your Favorites"});
            } else {
                others.add(item);
            }
        }
        upNext.addAll(favorites);
        upNext.addAll(others);
        upNext.addAll(watched);
        final boolean fromHome = upNext.isEmpty();
        if (fromHome) {
            for (final HomeStore.Cached pick : HomeStore.loadHome()) {
                if (!pick.video().url().equals(self)) {
                    final StreamInfoItem item = pick.video().toItem();
                    upNext.add(item);
                    notes.put(item, new String[]{pick.reason(), pick.detail()});
                }
            }
        }

        final ListBox list = new ListBox();
        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");
        for (final InfoItem item : upNext) {
            final ListBoxRow row = new ListBoxRow();
            final Widget card;
            if (item instanceof StreamInfoItem stream) {
                final String[] note = notes.get(item);
                card = VideoCard.create(stream, VideoCard.Layout.ROW, main,
                        new VideoCard.Decor(note == null ? null : note[0],
                                note == null ? null : note[1],
                                anchor -> VideoMenu.show(main, anchor, Video.from(stream),
                                        upNextExtras(stream))));
                if (watched.contains(item)) {
                    card.addCssClass("up-next-watched");
                }
            } else {
                card = PlaylistCard.row((PlaylistInfoItem) item);
            }
            card.setMarginTop(4);
            card.setMarginBottom(4);
            card.setMarginStart(4);
            row.setChild(card);
            list.append(row);
            upNextRows.add(row);
        }
        list.onRowActivated(row -> {
            final InfoItem item = upNext.get(row.getIndex());
            if (item instanceof StreamInfoItem stream) {
                openUpNext(stream);
            } else {
                main.openPlaylist(item.getUrl(), item.getName());
            }
        });

        moreUpNext.addCssClass("pill");
        moreUpNext.setHalign(Align.CENTER);
        moreUpNext.onClicked(() -> {
            upNextExpanded = true;
            updateUpNextRows();
        });

        final Box header = new Box(Orientation.HORIZONTAL, 8);
        final Label heading = Ui.label(fromHome ? "From your Home" : "Up next", "title-4");
        heading.setHexpand(true);
        heading.setXalign(0);
        header.append(heading);
        final Label autoplayLabel = Ui.label("Autoplay", "dim-label");
        final Switch autoplaySwitch = new Switch();
        autoplaySwitch.setValign(Align.CENTER);
        autoplaySwitch.setActive(Settings.get(Settings.AUTOPLAY));
        autoplaySwitch.setTooltipText("When a video ends and nothing is queued, play the first "
                + "video here");
        autoplaySwitch.onNotify("active", pspec -> {
            Settings.set(Settings.AUTOPLAY, autoplaySwitch.getActive());
            if (!autoplaySwitch.getActive()) {
                cancelAutoplay();
            }
        });
        header.append(autoplayLabel);
        header.append(autoplaySwitch);

        upNextBox.setMarginBottom(MARGIN);
        upNextBox.append(header);
        upNextBox.append(list);
        upNextBox.append(moreUpNext);
        upNextClamp.setChild(upNextBox);
        upNextClamp.setVisible(!upNext.isEmpty());
        return upNextClamp;
    }

    private List<VideoMenu.Extra> upNextExtras(final StreamInfoItem item) {
        final List<VideoMenu.Extra> extras = new ArrayList<>();
        extras.add(new VideoMenu.Extra("Not interested", () -> {
            HomeStore.hide(item.getUrl());
            dismissWhere(i -> i == item);
            main.toast("Won't recommend this video");
        }));
        if (item.getUploaderUrl() != null) {
            extras.add(new VideoMenu.Extra("Don't recommend " + item.getUploaderName(), () -> {
                HomeStore.block(item.getUploaderUrl(), item.getUploaderName());
                final String channel = Favorites.canonicalChannelUrl(item.getUploaderUrl());
                dismissWhere(i -> i instanceof StreamInfoItem s
                        && channel.equals(Video.from(s).uploaderUrl()));
                main.toast("Won't recommend " + item.getUploaderName()
                        + ". Undo in Preferences → Homepage.");
            }, true));
        }
        return extras;
    }

    /** Hides rows rather than removing them, so row indices keep matching {@link #upNext}. */
    private void dismissWhere(final java.util.function.Predicate<InfoItem> test) {
        for (int i = 0; i < upNext.size(); i++) {
            if (test.test(upNext.get(i))) {
                dismissed.add(upNextRows.get(i));
            }
        }
        updateUpNextRows();
    }

    /** A narrow page shows the first few rows above the comments, until "Show more". */
    private void updateUpNextRows() {
        final boolean limit = !wide && !upNextExpanded;
        int shown = 0;
        int remaining = 0;
        for (final ListBoxRow row : upNextRows) {
            if (dismissed.contains(row)) {
                row.setVisible(false);
            } else if (limit && shown >= NARROW_UP_NEXT) {
                row.setVisible(false);
                remaining++;
            } else {
                row.setVisible(true);
                shown++;
            }
        }
        moreUpNext.setVisible(remaining > 0);
        upNextClamp.setVisible(shown > 0 && !fullscreen);
    }

    /** What autoplay would play: the first video left in Up next not already watched. */
    private StreamInfoItem autoplayCandidate() {
        for (int i = 0; i < upNext.size(); i++) {
            if (upNext.get(i) instanceof StreamInfoItem stream
                    && !dismissed.contains(upNextRows.get(i)) && !Badges.isWatched(stream.getUrl())
                    && !MpvPlayer.isLiveType(stream.getStreamType())) {
                return stream;
            }
        }
        return null;
    }

    /** After the video ended with nothing queued: count down, then play what's up next. */
    private void startAutoplay() {
        final StreamInfoItem next = Settings.get(Settings.AUTOPLAY) ? autoplayCandidate() : null;
        if (next == null || !main.isVisible(page)) {
            return;
        }
        final int token = ++autoplayToken;
        autoplayNext = next;
        autoplayTitle.setText(next.getName());
        autoplayCard.setVisible(true);
        tickAutoplay(token, next, AUTOPLAY_SECONDS);
    }

    private void tickAutoplay(final int token, final StreamInfoItem next, final int seconds) {
        if (token != autoplayToken) {
            return;
        }
        if (seconds <= 0) {
            cancelAutoplay();
            openUpNext(next);
            return;
        }
        autoplayCountdown.setText("Up next in " + seconds);
        GLib.timeoutAddSecondsOnce(1, () -> tickAutoplay(token, next, seconds - 1));
    }

    private void cancelAutoplay() {
        autoplayToken++;
        autoplayNext = null;
        autoplayCard.setVisible(false);
    }

    /** The countdown card over the player, hidden until a video ends. */
    private Widget buildAutoplayCard() {
        autoplayCard.addCssClass("autoplay-card");
        autoplayCard.setHalign(Align.CENTER);
        autoplayCard.setValign(Align.CENTER);
        autoplayCard.setSizeRequest(320, -1);
        autoplayCountdown.addCssClass("title-4");
        autoplayCountdown.setXalign(0);
        autoplayCard.append(autoplayCountdown);
        autoplayTitle.setXalign(0);
        autoplayCard.append(autoplayTitle);
        final Box buttons = new Box(Orientation.HORIZONTAL, 8);
        buttons.setHalign(Align.END);
        buttons.setMarginTop(6);
        final Button cancel = Button.withLabel("Cancel");
        cancel.addCssClass("pill");
        cancel.onClicked(this::cancelAutoplay);
        autoplayPlay.addCssClass("pill");
        autoplayPlay.addCssClass("suggested-action");
        autoplayPlay.onClicked(() -> {
            final StreamInfoItem next = autoplayNext;
            cancelAutoplay();
            if (next != null) {
                openUpNext(next);
            }
        });
        buttons.append(cancel);
        buttons.append(autoplayPlay);
        autoplayCard.append(buttons);
        autoplayCard.setVisible(false);
        return autoplayCard;
    }

    /**
     * Opens a recommendation on top of this page (Back returns here). Keeps playing if this
     * page was, and frees this page's player so a long trail of videos doesn't keep a mpv
     * instance each; Play here again resumes from history.
     */
    private void openUpNext(final StreamInfoItem item) {
        final boolean wasPlaying = isPlaying();
        releasePlayer();
        main.openVideo(item.getUrl(), item.getName(), wasPlaying);
    }

    /** Whether the embedded player is showing (playing or paused, not the thumbnail). */
    private boolean isPlaying() {
        return player != null && "player".equals(hero.getVisibleChildName());
    }

    /** Saves progress and frees mpv; the page shows its thumbnail again. */
    private void releasePlayer() {
        cancelAutoplay();
        if (player == null) {
            return;
        }
        setFullscreen(false);
        saveProgress();
        player.dispose();
        playing = null;
        hero.setVisibleChildName("thumbnail");
    }

    private void loadComments() {
        final String videoUrl = info.getUrl();
        Ui.fetch(() -> CommentsInfo.getInfo(Extractor.youtube(), videoUrl),
                comments -> {
                    if (comments.isCommentsDisabled()) {
                        showCommentStatus("Comments are turned off");
                        return;
                    }
                    addComments(comments.getRelatedItems(), comments.getNextPage());
                    if (comments.getRelatedItems().isEmpty()) {
                        showCommentStatus("No comments yet");
                    }
                },
                error -> showCommentStatus("Couldn't load comments: "
                        + Ui.errorMessage(error)));
    }

    private void loadMoreComments() {
        if (!Page.isValid(nextCommentPage)) {
            return;
        }
        moreComments.setSensitive(false);
        final Page p = nextCommentPage;
        final String videoUrl = info.getUrl();
        Ui.fetch(() -> CommentsInfo.getMoreItems(Extractor.youtube(), videoUrl, p),
                more -> {
                    moreComments.setSensitive(true);
                    addComments(more.getItems(), more.getNextPage());
                },
                error -> {
                    moreComments.setSensitive(true);
                    main.toast("Couldn't load more comments: " + Ui.errorMessage(error));
                });
    }

    private void addComments(final List<CommentsInfoItem> comments, final Page next) {
        for (final CommentsInfoItem comment : comments) {
            commentList.append(commentRow(comment));
        }
        nextCommentPage = next;
        moreComments.setVisible(Page.isValid(next));
        commentStack.setVisibleChildName("comments");
    }

    private ListBoxRow commentRow(final CommentsInfoItem comment) {
        final Box box = commentBox(comment, false);
        box.setMarginTop(12);
        box.setMarginBottom(12);
        box.setMarginStart(12);
        box.setMarginEnd(12);
        final ListBoxRow row = new ListBoxRow();
        row.setActivatable(false);
        row.setChild(box);
        return row;
    }

    /** A comment; top-level ones with replies get a button that loads them underneath. */
    private Box commentBox(final CommentsInfoItem comment, final boolean reply) {
        final Box box = new Box(Orientation.HORIZONTAL, 12);

        final Avatar avatar = new Avatar(reply ? 28 : 36, comment.getUploaderName(), true);
        avatar.setValign(Align.START);
        Images.load(comment.getUploaderAvatarUrl(), avatar, avatar::setCustomImage);
        box.append(avatar);

        final Box body = new Box(Orientation.VERTICAL, 4);
        body.setHexpand(true);

        final Label author = Ui.label("");
        author.setMarkup("<b>" + Format.escape(comment.getUploaderName()) + "</b>"
                + "  <span alpha=\"60%\">"
                + Format.escape(Format.dot(comment.isPinned() ? "Pinned" : null,
                Format.relativeDate(comment.getUploadDate()))) + "</span>");
        body.append(author);

        final Label text = Ui.wrapping("", 0);
        text.setMarkup(Format.descriptionMarkup(comment.getCommentText()));
        text.setSelectable(true);
        main.handleLinks(text);
        body.append(text);

        final String likes = comment.getLikeCount() > 0
                ? Format.count(comment.getLikeCount()) + " likes" : "";
        final boolean canExpand = !reply && Page.isValid(comment.getReplies());
        final String replies = !canExpand && comment.getReplyCount() > 0
                ? replyLabel(comment.getReplyCount()) : "";
        final String footer = Format.dot(likes,
                comment.isHeartedByUploader() ? "♥ by creator" : null, replies);
        if (!footer.isEmpty()) {
            body.append(Ui.label(footer, "dim-label", "caption"));
        }
        if (canExpand) {
            body.append(buildReplies(comment));
        }
        box.append(body);
        return box;
    }

    private static String replyLabel(final int count) {
        return count > 0 ? count + (count == 1 ? " reply" : " replies") : "Replies";
    }

    /** "12 replies" toggles the replies, fetched page by page on first open. */
    private Box buildReplies(final CommentsInfoItem comment) {
        final Box section = new Box(Orientation.VERTICAL, 12);
        final ToggleButton toggle = ToggleButton.withLabel(replyLabel(comment.getReplyCount()));
        toggle.addCssClass("flat");
        toggle.setHalign(Align.START);
        final Box list = new Box(Orientation.VERTICAL, 12);
        list.setMarginStart(6);
        list.setVisible(false);
        final Button more = Button.withLabel("More replies");
        more.addCssClass("flat");
        more.setHalign(Align.START);
        more.setVisible(false);
        section.append(toggle);
        section.append(list);
        section.append(more);

        final String videoUrl = info.getUrl();
        final Page[] next = {comment.getReplies()};
        final boolean[] loaded = {false};
        final Runnable fetch = () -> {
            final Page p = next[0];
            toggle.setSensitive(false);
            more.setSensitive(false);
            Ui.fetch(() -> CommentsInfo.getMoreItems(Extractor.youtube(), videoUrl, p),
                    page -> {
                        loaded[0] = true;
                        toggle.setSensitive(true);
                        more.setSensitive(true);
                        for (final CommentsInfoItem r : page.getItems()) {
                            list.append(commentBox(r, true));
                        }
                        next[0] = page.getNextPage();
                        more.setVisible(list.getVisible() && Page.isValid(next[0]));
                    },
                    error -> {
                        toggle.setSensitive(true);
                        more.setSensitive(true);
                        main.toast("Couldn't load replies: " + Ui.errorMessage(error));
                    });
        };
        toggle.onToggled(() -> {
            final boolean open = toggle.getActive();
            list.setVisible(open);
            more.setVisible(open && loaded[0] && Page.isValid(next[0]));
            if (open && !loaded[0]) {
                fetch.run();
            }
        });
        more.onClicked(fetch::run);
        return section;
    }

    private void showCommentStatus(final String message) {
        commentStatus.setTitle(message);
        commentStatus.addCssClass("compact");
        commentStack.setVisibleChildName("status");
    }

    /**
     * Plays the downloaded file when there is one (and the quality picker is on it, or for
     * audio only), else streams. Resumes where history says, and records the watch.
     */
    private void play(final boolean audioOnly) {
        if (video == null) {
            return;
        }
        // The file might have been deleted since the page opened
        local = Downloads.local(url, !audioOnly);
        final int selected = qualityDropDown == null ? -1 : (int) qualityDropDown.getSelected();
        final boolean useLocal = local != null
                && (audioOnly || local.isAudioOnly() || !localInDropDown || selected == 0);

        final MpvPlayer.Source source;
        final List<MpvPlayer.SkipSegment> skip;
        if (useLocal) {
            final Path file = local.existingFile();
            source = new MpvPlayer.Source(video.title(), file.toString(), null, null,
                    audioOnly && !local.isAudioOnly());
            // Removed segments are gone from the file; marked ones still need skipping
            skip = info == null || "remove".equals(local.sponsorBlock())
                    ? List.of() : MpvPlayer.skipSegments(info);
        } else if (info == null) {
            main.toast("The downloaded file is gone and YouTube can't be reached");
            return;
        } else {
            final int streamIndex = localInDropDown ? selected - 1 : selected;
            final MpvPlayer.Quality quality = qualities.isEmpty() || streamIndex < 0
                    ? null : qualities.get(Math.min(streamIndex, qualities.size() - 1));
            try {
                source = MpvPlayer.source(info, quality, audioOnly);
            } catch (final IOException e) {
                main.toast(Ui.errorMessage(e));
                return;
            }
            skip = MpvPlayer.skipSegments(info);
        }

        final long resumeMs = info != null && MpvPlayer.isLive(info) || startOverride >= 0 ? 0
                : History.resumePosition(video.url());
        final double start = startOverride >= 0 ? startOverride : resumeMs / 1000.0;
        startOverride = -1;
        cancelAutoplay();
        // One video at a time
        main.mini().close();
        final Video watched = video;
        final List<Video> related = relatedVideos();
        Ui.async(() -> {
            final long id = History.recordWatch(watched);
            // The page already has this video's related videos; Home would fetch them later
            if (id >= 0 && related != null) {
                HomeStore.storeRelated(watched.url(), watched.title(),
                        HomeStore.SeedSource.HISTORY, related);
            }
            return id;
        }, id -> historyId = id, error -> System.err.println("History: " + error.getMessage()));

        if (PlayerView.isSupported()) {
            playEmbedded(source, skip, start);
        } else {
            Ui.async(() -> {
                if (MpvPlayer.findMpv() == null) {
                    throw new IllegalStateException(MPV_MISSING);
                }
                MpvPlayer.playExternal(source, skip, start, this::saveExternalProgress);
                return null;
            }, done -> main.toast(audioOnly ? "Playing audio in mpv" : "Opening in mpv"),
                    error -> main.toast(Ui.errorMessage(error)));
        }
        if (resumeMs > 0) {
            main.toast("Resuming at " + Format.duration(resumeMs / 1000), "Start over",
                    this::startOver);
        } else if (useLocal) {
            main.toast("Playing the downloaded file");
        }
    }

    /** The related videos YouTube gave, for Home's cache; null when offline. */
    private List<Video> relatedVideos() {
        if (info == null) {
            return null;
        }
        final List<Video> related = new ArrayList<>();
        for (final InfoItem item : info.getRelatedItems()) {
            if (item instanceof StreamInfoItem stream && Extractor.isWatchable(stream)) {
                related.add(Video.from(stream));
            }
        }
        return related;
    }

    private void playEmbedded(final MpvPlayer.Source source,
                              final List<MpvPlayer.SkipSegment> skip, final double start) {
        if (player == null) {
            player = new PlayerView(new PlayerView.Listener() {
                @Override
                public void fullscreenChanged(final boolean value) {
                    setFullscreen(value);
                }

                @Override
                public void message(final String message) {
                    main.toast(message);
                }

                @Override
                public void toggleTheater() {
                    if (!fullscreen) {
                        theaterButton.setActive(!theater);
                    }
                }

                @Override
                public void closed() {
                    setFullscreen(false);
                    hero.setVisibleChildName("thumbnail");
                }

                @Override
                public void progress(final double position, final double duration) {
                    saveProgress(position, duration);
                    // Seeking back after the end calls off the countdown
                    if (autoplayCard.getVisible() && position < duration - 2) {
                        cancelAutoplay();
                    }
                }

                @Override
                public void ended() {
                    setFullscreen(false);
                    // The sleep timer was waiting for this video: no autoplay or next in queue
                    if (main.sleepTimer().videoEnded()) {
                        return;
                    }
                    if (!main.videoEnded(VideoPage.this, url)) {
                        startAutoplay();
                    }
                }
            });
            playerSlot.append(player.widget());
        }
        hero.setVisibleChildName("player");
        playing = source;
        playingSkip = skip;
        try {
            player.play(source, skip, start);
        } catch (final IllegalStateException e) {
            main.toast(Ui.errorMessage(e));
        }
    }

    /**
     * Plays the current video again from 0:00. Reloads instead of seeking, so it also works
     * while a resume is still loading (mpv refuses seeks then).
     */
    private void startOver() {
        if (player == null || playing == null) {
            return;
        }
        try {
            player.play(playing, playingSkip, 0);
        } catch (final IllegalStateException e) {
            main.toast(Ui.errorMessage(e));
        }
    }

    private void saveProgress() {
        if (player != null) {
            saveProgress(player.position(), player.duration());
        }
    }

    /** External mpv's position, reported on its output thread. */
    private void saveExternalProgress(final double position, final double duration) {
        final long id = historyId;
        if (id >= 0 && position > 0) {
            History.updateProgress(id, Math.round(position * 1000), Math.round(duration));
        }
    }

    /** Stores the position for resuming; a no-op with history off (History checks). */
    private void saveProgress(final double position, final double duration) {
        final long id = historyId;
        if (id < 0 || position <= 0) {
            return;
        }
        Ui.background(() -> History.updateProgress(id, Math.round(position * 1000),
                Math.round(duration)));
    }

    private void setTheater(final boolean value) {
        if (value == theater) {
            return;
        }
        theater = value;
        PREFS.putBoolean(PREF_THEATER, value);
        updateLayout();
    }

    /**
     * Fullscreen grows the player in place, hiding the rest of the page. Moving the GLArea to
     * another parent would recreate its GL context, and mpv drops the video track when its
     * render context goes away.
     */
    private void setFullscreen(final boolean value) {
        if (value == fullscreen || player == null) {
            return;
        }
        fullscreen = value;
        updateLayout();
        if (value) {
            scroller.getVadjustment().setValue(0);
            main.window().fullscreen();
        } else {
            main.window().unfullscreen();
        }
        toolbar.setRevealTopBars(!value);
        player.setFullscreen(value);
    }

    /**
     * Places the player, details, Up next and comments for the page width and mode:
     * <ul>
     * <li>wide: Up next in a column on the right, from the top (beside the player) or, in
     * theater mode, from under the full-width player;</li>
     * <li>narrow: one column, with Up next between the details and the comments;</li>
     * <li>fullscreen: the player alone.</li>
     * </ul>
     */
    private void updateLayout() {
        // Theater and fullscreen run the player edge to edge
        final boolean edge = theater || fullscreen;
        final boolean spanHero = edge || !wide;
        place(heroClamp, 0, 0, spanHero ? 2 : 1, 1);
        if (wide) {
            place(upNextClamp, 1, theater ? 1 : 0, 1, theater ? 2 : 3);
            place(commentsClamp, 0, 2, 1, 1);
        } else {
            place(upNextClamp, 0, 2, 1, 1);
            place(commentsClamp, 0, 3, 1, 1);
        }
        contentClamp.setVisible(!fullscreen);
        commentsClamp.setVisible(!fullscreen && info != null);
        updateUpNextRows();

        // No tightening: it depends on each child's minimum width, which would misalign them
        final int column = wide ? PRIMARY_MAX : CONTENT_WIDTH;
        for (final Clamp clamp : new Clamp[]{contentClamp, commentsClamp}) {
            clamp.setMaximumSize(column);
            clamp.setTighteningThreshold(column);
        }
        upNextClamp.setMaximumSize(wide ? SIDEBAR_WIDTH : CONTENT_WIDTH);
        upNextClamp.setTighteningThreshold(wide ? SIDEBAR_WIDTH : CONTENT_WIDTH);
        upNextClamp.setSizeRequest(wide ? SIDEBAR_MIN : -1, -1);
        // Beside the player it starts level with it; under it, level with the title
        upNextClamp.setMarginTop(MARGIN);
        // Inside the clamp, like the details' margins, so a narrow page lines them up
        upNextBox.setMarginStart(wide ? 0 : MARGIN);
        upNextBox.setMarginEnd(MARGIN);

        final int margin = edge ? 0 : MARGIN;
        heroClamp.setMarginTop(margin);
        heroClamp.setMarginStart(margin);
        heroClamp.setMarginEnd(margin);
        heroClamp.setValign(fullscreen ? Align.FILL : Align.START);
        hero.setVexpand(fullscreen);
        setHeroHeight(fullscreen ? -1 : heroHeightForLayout());
        // NEVER makes the page exactly as tall as the window, so the player fills it
        scroller.setPolicy(PolicyType.NEVER, fullscreen ? PolicyType.NEVER : PolicyType.AUTOMATIC);
        if (edge) {
            playerSlot.removeCssClass("thumbnail");
            hero.addCssClass("player-backdrop");
        } else {
            playerSlot.addCssClass("thumbnail");
            hero.removeCssClass("player-backdrop");
        }
    }

    /** Moves a grid child to another cell; it keeps its parent. */
    private void place(final Widget child, final int column, final int row, final int width,
                       final int height) {
        final GridLayoutChild cell =
                (GridLayoutChild) grid.getLayoutManager().getLayoutChild(child);
        cell.setColumn(column);
        cell.setRow(row);
        cell.setColumnSpan(width);
        cell.setRowSpan(height);
    }

    private void setHeroHeight(final int height) {
        heroHeight = height;
        hero.setSizeRequest(-1, height);
        // Beside Up next the player fills its column at 16:9, narrower when height-capped
        final int max = theater || fullscreen ? UNCLAMPED
                : wide ? Math.max(height, MIN_HERO) * 16 / 9 : HERO_WIDTH;
        heroClamp.setMaximumSize(max);
        heroClamp.setTighteningThreshold(max);
    }

    private int heroHeightForLayout() {
        if (theater) {
            return theaterHeight();
        }
        if (!wide) {
            return HERO_HEIGHT;
        }
        // The details column, less the player's margins
        final int width = contentClamp.getWidth() - 2 * MARGIN;
        if (width <= 0) {
            return HERO_HEIGHT;
        }
        final int maxHeight = scroller.getHeight() - THEATER_BELOW;
        return Math.max(MIN_HERO, Math.min(width * 9 / 16, maxHeight));
    }

    /** Full width at 16:9, but leaving part of the window for the title below. */
    private int theaterHeight() {
        final int width = scroller.getWidth();
        final int maxHeight = scroller.getHeight() - THEATER_BELOW;
        return Math.max(HERO_HEIGHT, Math.min(width * 9 / 16, maxHeight));
    }
}
