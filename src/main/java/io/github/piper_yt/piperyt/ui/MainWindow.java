package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.player.PlayQueue;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationView;
import org.gnome.adw.Toast;
import org.gnome.adw.ToastOverlay;
import org.gnome.gtk.Label;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.UriLauncher;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.HashMap;
import java.util.Map;

/**
 * The application window: a navigation stack of pages, with the mini player in a corner, under
 * a toast overlay.
 */
public final class MainWindow {
    private final ApplicationWindow window;
    private final NavigationView navigation = new NavigationView();
    private final ToastOverlay toasts = new ToastOverlay();
    private final Map<NavigationPage, VideoPage> videoPages = new HashMap<>();
    private final Shell shell;
    private final MiniPlayer mini;
    private final SleepTimer sleepTimer = new SleepTimer(this);

    public MainWindow(final Application app) {
        window = new ApplicationWindow(app);
        window.setTitle("Piper");
        window.setDefaultSize(1250, 840);
        if (app.lookupAction("zoom-in") == null) {
            Zoom.install(app);
        }

        mini = new MiniPlayer(this);
        final Overlay root = new Overlay();
        root.setChild(navigation);
        root.addOverlay(mini.widget());
        mini.attachDrag(root);
        toasts.setChild(root);
        window.setContent(toasts);
        shell = new Shell(this);
        navigation.add(shell.page());
        // Media keys (and Bluetooth speaker/headset buttons) reach the player through MPRIS
        Mpris.start(window::present);
        navigation.onPopped(page -> {
            final VideoPage video = videoPages.remove(page);
            if (video != null) {
                video.dispose();
            }
        });
    }

    public ApplicationWindow window() {
        return window;
    }

    public void present() {
        window.present();
    }

    public void openVideo(final String url, final String title) {
        openVideo(url, title, false);
    }

    /** Opens a video page; with {@code autoplay} it starts playing once loaded. */
    public void openVideo(final String url, final String title, final boolean autoplay) {
        openVideo(url, title, autoplay, -1);
    }

    /** As above, starting at {@code startSeconds} instead of the resume position (if >= 0). */
    void openVideo(final String url, final String title, final boolean autoplay,
                   final double startSeconds) {
        final VideoPage video = new VideoPage(this, url, title, autoplay, startSeconds);
        videoPages.put(video.page(), video);
        navigation.push(video.page());
    }

    MiniPlayer mini() {
        return mini;
    }

    SleepTimer sleepTimer() {
        return sleepTimer;
    }

    /** The sleep timer ran out: pause whatever is playing (and save where it was). */
    void pauseAllForSleep() {
        for (final VideoPage page : videoPages.values()) {
            page.pauseForSleep();
        }
        mini.pauseForSleep();
    }

    /** Whether {@code page} is still in the navigation stack. */
    boolean isOpen(final VideoPage page) {
        return page != null && videoPages.containsValue(page);
    }

    /** Whether the page on top is a video page. */
    boolean isVideoPageVisible() {
        final NavigationPage top = navigation.getVisiblePage();
        return top != null && videoPages.containsKey(top);
    }

    /** Goes back to an open video page and continues the mini player's video there. */
    void showVideoPage(final VideoPage page, final MiniPlayer.Handoff handoff) {
        page.resumeFrom(handoff);
        if (!isVisible(page.page())) {
            navigation.popToPage(page.page());
        }
    }

    /**
     * Plays the queue's current video: replaces the video page on top (the one that just
     * finished) or opens a new one.
     */
    public void playQueueCurrent() {
        final Video current = PlayQueue.current();
        if (current == null) {
            return;
        }
        final NavigationPage top = navigation.getVisiblePage();
        if (top != null && videoPages.containsKey(top)) {
            navigation.pop();
        }
        openVideo(current.url(), current.title(), true);
    }

    /**
     * Advances the queue after {@code page}'s video ended, if it was the queue's; false when
     * there was nothing to continue with.
     */
    boolean videoEnded(final VideoPage page, final String url) {
        if (!PlayQueue.isCurrent(url) || !PlayQueue.hasNext() || !isVisible(page.page())) {
            return false;
        }
        PlayQueue.next();
        playQueueCurrent();
        return true;
    }

    /** Whether {@code page} is the page on top. */
    boolean isVisible(final NavigationPage page) {
        return navigation.getVisiblePage() != null
                && navigation.getVisiblePage().handle().equals(page.handle());
    }

    public void openChannel(final String url, final String name) {
        if (url == null || url.isBlank()) {
            return;
        }
        navigation.push(new ChannelPage(this, url, name).page());
    }

    public void openPlaylist(final String url, final String name) {
        navigation.push(new PlaylistPage(this, url, name).page());
    }

    public void openLocalPlaylist(final long id) {
        navigation.push(new LocalPlaylistPage(this, id).page());
    }

    /** Pops back to the sidebar and shows a library view ("downloads", "history", …). */
    void showView(final String name) {
        navigation.popToPage(shell.page());
        shell.showView(name);
    }

    /** Opens a search result, channel tab or related item on its own page. */
    public void open(final InfoItem item) {
        switch (item) {
            case StreamInfoItem stream -> openVideo(stream.getUrl(), stream.getName());
            case ChannelInfoItem channel -> openChannel(channel.getUrl(), channel.getName());
            case PlaylistInfoItem playlist -> openPlaylist(playlist.getUrl(), playlist.getName());
            default -> openInBrowser(item.getUrl());
        }
    }

    /** Opens a YouTube video, channel or playlist link in the app; false for other text. */
    public boolean openLink(final String url) {
        switch (Extractor.linkType(url)) {
            case STREAM -> openVideo(url, null);
            case CHANNEL -> openChannel(url, null);
            case PLAYLIST -> openPlaylist(url, null);
            default -> {
                return false;
            }
        }
        return true;
    }

    public void toast(final String message) {
        final Toast toast = new Toast(message);
        toast.setTimeout(5);
        toasts.addToast(toast);
    }

    /** A toast with a button, e.g. "Resuming at 12:34 [Start over]". */
    public void toast(final String message, final String button, final Runnable action) {
        final Toast toast = new Toast(message);
        toast.setTimeout(6);
        toast.setButtonLabel(button);
        // An exception escaping a GTK callback ends the JVM
        toast.onButtonClicked(() -> {
            try {
                action.run();
            } catch (final RuntimeException e) {
                e.printStackTrace();
                toast(Ui.errorMessage(e));
            }
        });
        toasts.addToast(toast);
    }

    public void openInBrowser(final String url) {
        new UriLauncher(url).launch(window, null, null);
    }

    /** Opens YouTube links inside the app; everything else goes to the browser. */
    public void handleLinks(final Label label) {
        label.onActivateLink(uri -> {
            if (!openLink(uri)) {
                openInBrowser(uri);
            }
            return true;
        });
    }
}
