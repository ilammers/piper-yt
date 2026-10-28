package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.player.MpvPlayer;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.EventSequenceState;
import org.gnome.gtk.GestureDrag;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import java.util.List;

/**
 * A small player in the window's corner that keeps a video going after you leave its page.
 * The page's GLArea can't move here (mpv would drop the video), so the page hands over what it
 * was playing and where, and this player continues it with its own mpv. Expanding hands it
 * back the same way. Drag it to move it; let go and it snaps to the nearest corner.
 */
final class MiniPlayer {
    /** What a video page hands over. {@code page} may have been popped since. */
    record Handoff(VideoPage page, String url, String title, MpvPlayer.Source source,
                   List<MpvPlayer.SkipSegment> skip, double position, long historyId) {
    }

    private static final int WIDTH = 320;
    private static final int VIDEO_HEIGHT = WIDTH * 9 / 16;
    /** Gap between the player and the window's edges. */
    private static final int MARGIN = 18;
    /** Top corners sit below the header bar, so its buttons stay usable. */
    private static final int TOP_MARGIN = 64;
    /** How far the pointer moves before a press counts as a drag, not a click. */
    private static final double DRAG_THRESHOLD = 8;

    private final MainWindow main;
    private final Box widget = new Box(Orientation.VERTICAL, 0);
    private final Box videoSlot = new Box(Orientation.VERTICAL, 0);
    private final Label title = new Label("");
    private final Button pause = Button.fromIconName("media-playback-pause-symbolic");
    private PlayerView player;
    private Handoff current;
    private boolean left;
    private boolean top;
    /** Where the drag started, as the player's top-left inside the window. */
    private int dragX;
    private int dragY;
    private boolean dragging;

    MiniPlayer(final MainWindow main) {
        this.main = main;
        widget.addCssClass("mini-player");
        widget.setOverflow(Overflow.HIDDEN);
        final String corner = Settings.getString(Settings.MINI_PLAYER_CORNER);
        snapTo(corner.endsWith("left"), corner.startsWith("top"));
        widget.setSizeRequest(WIDTH, -1);
        widget.setVisible(false);

        videoSlot.addCssClass("mini-player-video");
        videoSlot.setOverflow(Overflow.HIDDEN);
        videoSlot.setSizeRequest(WIDTH, VIDEO_HEIGHT);
        widget.append(videoSlot);

        final Box controls = new Box(Orientation.HORIZONTAL, 4);
        controls.setMarginTop(6);
        controls.setMarginBottom(6);
        controls.setMarginStart(12);
        controls.setMarginEnd(6);
        title.setEllipsize(EllipsizeMode.END);
        // An ellipsized label still asks for its whole text's width; keep the player narrow
        title.setMaxWidthChars(1);
        title.setXalign(0);
        title.setHexpand(true);
        title.addCssClass("heading");
        controls.append(title);
        pause.addCssClass("flat");
        pause.addCssClass("circular");
        pause.setTooltipText("Play/pause");
        pause.onClicked(() -> {
            if (player != null) {
                player.togglePause();
            }
        });
        controls.append(pause);
        final Button expand = Button.fromIconName("view-fullscreen-symbolic");
        expand.addCssClass("flat");
        expand.addCssClass("circular");
        expand.setTooltipText("Back to the video");
        expand.onClicked(this::expand);
        controls.append(expand);
        final Button close = Button.fromIconName("window-close-symbolic");
        close.addCssClass("flat");
        close.addCssClass("circular");
        close.setTooltipText("Stop");
        close.onClicked(this::close);
        controls.append(close);
        widget.append(controls);
    }

    /**
     * Lets the player be dragged around {@code overlay}, the overlay it sits in. The gesture
     * lives on the overlay, not the player: offsets measured on a widget that moves under the
     * pointer feed back into themselves and make it jitter between two spots.
     */
    void attachDrag(final Widget overlay) {
        overlay.addController(dragGesture());
    }

    /**
     * Runs in the capture phase so it sees presses on the video and buttons too, but only
     * claims them once the pointer has moved, so clicks still reach the buttons. Presses
     * outside the player are left alone.
     */
    private GestureDrag dragGesture() {
        final GestureDrag drag = new GestureDrag();
        drag.setPropagationPhase(PropagationPhase.CAPTURE);
        drag.onDragBegin((x, y) -> {
            dragging = false;
            final Widget parent = widget.getParent();
            if (!widget.getVisible() || parent == null) {
                drag.setState(EventSequenceState.DENIED);
                return;
            }
            final int px = left ? MARGIN : parent.getWidth() - widget.getWidth() - MARGIN;
            final int py = top ? TOP_MARGIN : parent.getHeight() - widget.getHeight() - MARGIN;
            if (x < px || x > px + widget.getWidth() || y < py || y > py + widget.getHeight()) {
                drag.setState(EventSequenceState.DENIED);
            }
        });
        drag.onDragUpdate((dx, dy) -> {
            final Widget parent = widget.getParent();
            if (parent == null) {
                return;
            }
            if (!dragging) {
                if (Math.hypot(dx, dy) < DRAG_THRESHOLD) {
                    return;
                }
                dragging = true;
                drag.setState(EventSequenceState.CLAIMED);
                // Switch to top-left margins so the player can sit anywhere while dragged
                dragX = left ? MARGIN : parent.getWidth() - widget.getWidth() - MARGIN;
                dragY = top ? TOP_MARGIN : parent.getHeight() - widget.getHeight() - MARGIN;
                setMargins(0, 0, 0, 0);
                widget.setHalign(Align.START);
                widget.setValign(Align.START);
            }
            final int maxX = Math.max(0, parent.getWidth() - widget.getWidth());
            final int maxY = Math.max(0, parent.getHeight() - widget.getHeight());
            widget.setMarginStart(clamp(dragX + (int) Math.round(dx), maxX));
            widget.setMarginTop(clamp(dragY + (int) Math.round(dy), maxY));
        });
        drag.onDragEnd((dx, dy) -> {
            if (!dragging) {
                return;
            }
            dragging = false;
            final Widget parent = widget.getParent();
            final double centerX = widget.getMarginStart() + widget.getWidth() / 2.0;
            final double centerY = widget.getMarginTop() + widget.getHeight() / 2.0;
            final boolean toLeft = parent != null && centerX < parent.getWidth() / 2.0;
            final boolean toTop = parent != null && centerY < parent.getHeight() / 2.0;
            snapTo(toLeft, toTop);
            Settings.set(Settings.MINI_PLAYER_CORNER,
                    (toTop ? "top" : "bottom") + "-" + (toLeft ? "left" : "right"));
        });
        return drag;
    }

    private void snapTo(final boolean toLeft, final boolean toTop) {
        left = toLeft;
        top = toTop;
        widget.setHalign(toLeft ? Align.START : Align.END);
        widget.setValign(toTop ? Align.START : Align.END);
        setMargins(toLeft ? MARGIN : 0, toLeft ? 0 : MARGIN, toTop ? TOP_MARGIN : 0,
                toTop ? 0 : MARGIN);
    }

    private void setMargins(final int start, final int end, final int top, final int bottom) {
        widget.setMarginStart(start);
        widget.setMarginEnd(end);
        widget.setMarginTop(top);
        widget.setMarginBottom(bottom);
    }

    private static int clamp(final int value, final int max) {
        return Math.max(0, Math.min(value, max));
    }

    Widget widget() {
        return widget;
    }

    /** Continues a page's video here. */
    void take(final Handoff handoff) {
        close();
        current = handoff;
        title.setText(handoff.title() == null ? "" : handoff.title());
        title.setTooltipText(handoff.title());
        widget.setVisible(true);
        if (player == null) {
            player = new PlayerView(new PlayerView.Listener() {
                @Override
                public void fullscreenChanged(final boolean fullscreen) {
                    // mpv's own fullscreen button: go big the app's way instead
                    if (fullscreen) {
                        player.setFullscreen(false);
                        expand();
                    }
                }

                @Override
                public void message(final String message) {
                    main.toast(message);
                }

                @Override
                public void toggleTheater() {
                }

                @Override
                public void closed() {
                    current = null;
                    widget.setVisible(false);
                }

                @Override
                public void progress(final double position, final double duration) {
                    save(position, duration);
                }

                @Override
                public void pauseChanged(final boolean paused) {
                    pause.setIconName(paused ? "media-playback-start-symbolic"
                            : "media-playback-pause-symbolic");
                }

                @Override
                public void ended() {
                    main.sleepTimer().videoEnded();
                }
            }, false); // too small for mpv's controller; the buttons below do the job
            // Parented right away: java-gi frees unparented widgets that have handlers
            videoSlot.append(player.widget());
        }
        try {
            player.play(handoff.source(), handoff.skip(), handoff.position());
        } catch (final IllegalStateException e) {
            main.toast(Ui.errorMessage(e));
            close();
        }
    }

    /** Whether it's playing {@code page}'s video. */
    boolean isFrom(final VideoPage page) {
        return current != null && current.page() == page;
    }

    /** Stops here and returns what was playing, at the current position; null if nothing. */
    Handoff reclaim() {
        if (current == null) {
            return null;
        }
        final Handoff h = current;
        final double position = player.position();
        close();
        return new Handoff(h.page(), h.url(), h.title(), h.source(), h.skip(), position,
                h.historyId());
    }

    /** Back to the video's page (or a new one, if it was closed), playing from here. */
    private void expand() {
        final Handoff h = reclaim();
        if (h == null) {
            return;
        }
        if (main.isOpen(h.page())) {
            main.showVideoPage(h.page(), h);
        } else {
            main.openVideo(h.url(), h.title(), true, h.position());
        }
    }

    /** The sleep timer ran out. */
    void pauseForSleep() {
        if (current != null && player != null) {
            player.pause();
            save(player.position(), player.duration());
        }
    }

    void close() {
        if (current != null && player != null) {
            save(player.position(), player.duration());
            player.dispose();
        }
        current = null;
        widget.setVisible(false);
    }

    private void save(final double position, final double duration) {
        final Handoff h = current;
        if (h == null || h.historyId() < 0 || position <= 0) {
            return;
        }
        Ui.background(() -> History.updateProgress(h.historyId(), Math.round(position * 1000),
                Math.round(duration)));
    }
}
