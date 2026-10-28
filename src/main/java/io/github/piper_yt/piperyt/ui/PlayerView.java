package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.net.Network;
import io.github.piper_yt.piperyt.player.Mpv;
import io.github.piper_yt.piperyt.player.MpvPlayer;
import io.github.piper_yt.piperyt.player.Subtitles;
import io.github.piper_yt.piperyt.util.Theme;
import io.github.piper_yt.piperyt.util.Themes;
import org.gnome.gdk.Gdk;
import org.gnome.gdk.ModifierType;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.EventControllerScroll;
import org.gnome.gtk.EventControllerScrollFlags;
import org.gnome.gtk.GLArea;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Widget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays inside the window: libmpv renders into a {@link GLArea}, and mouse and keyboard input
 * is forwarded so mpv's own on-screen controller and key bindings work as in standalone mpv.
 */
final class PlayerView {
    /** Callbacks, all run on the main thread. */
    interface Listener {
        void fullscreenChanged(boolean fullscreen);

        void message(String message);

        /** The theater-mode key (t) was pressed. */
        void toggleTheater();

        /** mpv shut itself down (e.g. a "quit" binding); the view is no longer usable. */
        void closed();

        /** The playback position, every few seconds while playing, and on pause. */
        default void progress(final double position, final double duration) {
        }

        /** Playback reached the end of the file. */
        default void ended() {
        }

        default void pauseChanged(final boolean paused) {
        }
    }

    private static final long OBSERVE_FULLSCREEN = 1;
    private static final long OBSERVE_TIME = 2;
    private static final long OBSERVE_DURATION = 3;
    private static final long OBSERVE_EOF = 4;
    private static final long OBSERVE_PAUSE = 5;
    private static final long PROGRESS_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SKIP_DEBOUNCE_NANOS = 1_500_000_000L;

    private static final Map<String, String> KEY_NAMES = Map.ofEntries(
            Map.entry("Left", "LEFT"), Map.entry("Right", "RIGHT"),
            Map.entry("Up", "UP"), Map.entry("Down", "DOWN"),
            Map.entry("space", "SPACE"), Map.entry("Return", "ENTER"),
            Map.entry("KP_Enter", "KP_ENTER"), Map.entry("Escape", "ESC"),
            Map.entry("BackSpace", "BS"), Map.entry("Tab", "TAB"),
            Map.entry("Page_Up", "PGUP"), Map.entry("Page_Down", "PGDWN"),
            Map.entry("Home", "HOME"), Map.entry("End", "END"),
            Map.entry("Delete", "DEL"), Map.entry("Insert", "INS"),
            Map.entry("numbersign", "SHARP"));

    private final GLArea area = new GLArea();
    private final Listener listener;
    private final AtomicBoolean renderQueued = new AtomicBoolean();

    private Mpv mpv;
    private Mpv.GlRender render;
    private Thread events;
    private volatile boolean closing;
    private volatile List<MpvPlayer.SkipSegment> skipSegments = List.of();
    // Subtitles arrive (from yt-dlp) while the file plays; guarded by subtitleLock
    private final Object subtitleLock = new Object();
    private long subtitleGeneration;
    private String subtitleUrl;
    private boolean subtitleFileLoaded;
    private List<Subtitles.Track> readySubtitles = List.of();
    private CompletableFuture<List<Subtitles.Track>> subtitleFetch;
    private boolean fullscreen;
    private final boolean controls;
    private String videoTrack;
    private int pixelWidth;
    private int pixelHeight;

    // Event thread only
    private MpvPlayer.SkipSegment lastSkipped;
    private long lastSkipNanos;
    private long lastProgressNanos;
    private volatile double position;
    private volatile double duration;

    PlayerView(final Listener listener) {
        this(listener, true);
    }

    /** @param controls whether mpv shows its on-screen controller on hover */
    PlayerView(final Listener listener, final boolean controls) {
        this.listener = listener;
        this.controls = controls;
        area.setHexpand(true);
        area.setVexpand(true);
        area.setHasDepthBuffer(false);
        area.setFocusable(true);
        area.setFocusOnClick(true);
        area.onRealize(this::createRender);
        area.onUnrealize(this::freeRender);
        area.onResize((width, height) -> {
            pixelWidth = width;
            pixelHeight = height;
        });
        area.onRender(context -> {
            if (render != null && pixelWidth > 0) {
                render.render(pixelWidth, pixelHeight);
            } else {
                Mpv.clearGl();
            }
            return true;
        });
        setUpInput();
    }

    /** True when libmpv is installed, so playback can be embedded. */
    static boolean isSupported() {
        return Mpv.isAvailable();
    }

    Widget widget() {
        return area;
    }

    /** Plays {@code source}, starting {@code startSeconds} in (0: from the start). */
    void play(final MpvPlayer.Source source, final List<MpvPlayer.SkipSegment> skip,
              final double startSeconds) {
        ensureCore();
        skipSegments = skip;
        lastSkipped = null;
        position = startSeconds;
        duration = 0;
        // An option set as a property applies to the next loadfile
        mpv.setProperty("start", startSeconds > 0
                ? String.format(java.util.Locale.ROOT, "%.1f", startSeconds) : "none");
        mpv.setProperty("force-media-title", source.title());
        mpv.setProperty("audio-files",
                source.audioUrl() == null ? List.of() : List.of(source.audioUrl()));
        mpv.setProperty("cover-art-files",
                source.coverUrl() == null ? List.of() : List.of(source.coverUrl()));
        videoTrack = null;
        mpv.setProperty("vid", source.noVideo() ? "no" : "auto");
        expectSubtitles(source);
        mpv.command("loadfile", source.url());
        mpv.setProperty("pause", "no");
        area.grabFocus();
        Mpris.playing(this, source.title());
    }

    // Called from GTK signal handlers, where an exception would end the JVM: mpv refusing a
    // command (e.g. a seek while the file is still loading) is logged, not thrown.

    void pause() {
        quietly(() -> mpv.setProperty("pause", "yes"));
    }

    void resume() {
        quietly(() -> mpv.setProperty("pause", "no"));
    }

    /** Paused, or ended (keep-open pauses at the end), or not started. */
    boolean isPaused() {
        if (mpv == null) {
            return true;
        }
        try {
            return !"no".equals(mpv.getProperty("pause"));
        } catch (final IllegalStateException e) {
            return true;
        }
    }

    void togglePause() {
        quietly(() -> mpv.command("cycle", "pause"));
    }

    void seek(final double seconds) {
        quietly(() -> mpv.command("seek", String.valueOf(seconds), "absolute"));
    }

    private void quietly(final Runnable command) {
        if (mpv == null) {
            return;
        }
        try {
            command.run();
        } catch (final IllegalStateException e) {
            System.err.println("mpv: " + e.getMessage());
        }
    }

    /** The last known playback position and duration, in seconds. */
    double position() {
        return position;
    }

    double duration() {
        return duration;
    }

    /** Keeps mpv's fullscreen property, which its controller and bindings toggle, in sync. */
    void setFullscreen(final boolean value) {
        fullscreen = value;
        quietly(() -> mpv.setProperty("fullscreen", value ? "yes" : "no"));
        area.grabFocus();
    }

    /** Stops playback and releases mpv. The view can be played again afterwards. */
    void dispose() {
        if (mpv == null) {
            return;
        }
        Mpris.released(this);
        // The fetch isn't cancelled: the mini player may be taking this video over
        synchronized (subtitleLock) {
            subtitleFetch = null;
            subtitleGeneration++;
        }
        closing = true;
        mpv.wakeup();
        try {
            events.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        freeRender();
        mpv.destroy();
        mpv = null;
        events = null;
        skipSegments = List.of();
        area.queueRender();
    }

    private static String mpvProxyUrl() {
        try {
            return Network.mpvProxyUrl();
        } catch (final IOException e) {
            throw new UncheckedIOException("Couldn't start the local proxy for mpv", e);
        }
    }

    private void ensureCore() {
        if (mpv != null) {
            return;
        }
        final Mpv core = new Mpv();
        core.setOption("vo", "libmpv");
        core.setOption("hwdec", "auto-safe");
        // Frames are drawn on GTK's main thread; see Mpv.GlRender
        core.setOption("video-timing-offset", "0");
        core.setOption("ytdl", "no");
        // Streams go out the same way as the extractor's requests (and must: YouTube ties
        // stream URLs to the IP that extracted them)
        core.setOption("http-proxy", mpvProxyUrl());
        core.setOption("stream-lavf-o", MpvPlayer.STREAM_OPTIONS);
        core.setOption("osc", controls ? "yes" : "no");
        core.setOption("input-default-bindings", "yes");
        core.setOption("keep-open", "yes");
        core.setOption("audio-client-name", "Piper");
        // Colors of the theme in use when the player starts
        final Theme theme = Themes.current();
        theme.mpvOptions().forEach(core::setOption);
        // The controller sizes itself for a 1x window; match the display scale
        final int scale = Math.max(1, area.getScaleFactor());
        core.setOption("script-opts", "osc-scalewindowed=" + scale
                + ",osc-scalefullscreen=" + scale + "," + theme.oscScriptOpts());
        core.initialize();
        core.observe(OBSERVE_FULLSCREEN, "fullscreen", Mpv.FORMAT_FLAG);
        core.observe(OBSERVE_TIME, "time-pos", Mpv.FORMAT_DOUBLE);
        core.observe(OBSERVE_DURATION, "duration", Mpv.FORMAT_DOUBLE);
        // keep-open holds the last frame instead of ending the file, so watch eof-reached
        core.observe(OBSERVE_EOF, "eof-reached", Mpv.FORMAT_FLAG);
        core.observe(OBSERVE_PAUSE, "pause", Mpv.FORMAT_FLAG);

        mpv = core;
        closing = false;
        events = Thread.ofPlatform().daemon().name("mpv-events").start(() -> eventLoop(core));
        if (area.getRealized()) {
            createRender();
        }
    }

    private void eventLoop(final Mpv core) {
        while (!closing) {
            final Mpv.Event event = core.waitEvent(-1);
            switch (event.id()) {
                case Mpv.EVENT_SHUTDOWN -> {
                    Ui.runOnMain(() -> {
                        dispose();
                        listener.closed();
                    });
                    return;
                }
                case Mpv.EVENT_PROPERTY_CHANGE -> {
                    if (event.userData() == OBSERVE_FULLSCREEN && event.value() instanceof Boolean b) {
                        Ui.runOnMain(() -> listener.fullscreenChanged(b));
                    } else if (event.userData() == OBSERVE_TIME && event.value() instanceof Double t) {
                        position = t;
                        skipSponsors(core, t);
                        if (System.nanoTime() - lastProgressNanos > PROGRESS_INTERVAL_NANOS) {
                            reportProgress();
                        }
                    } else if (event.userData() == OBSERVE_DURATION
                            && event.value() instanceof Double d) {
                        duration = d;
                        Ui.runOnMain(() -> Mpris.updated(this));
                    } else if (event.userData() == OBSERVE_PAUSE && event.value() instanceof Boolean b) {
                        if (b) {
                            reportProgress();
                        }
                        Ui.runOnMain(() -> {
                            listener.pauseChanged(b);
                            Mpris.updated(this);
                        });
                    } else if (event.userData() == OBSERVE_EOF && Boolean.TRUE.equals(event.value())) {
                        reportProgress();
                        Ui.runOnMain(listener::ended);
                    }
                }
                case Mpv.EVENT_FILE_LOADED -> fileLoaded(core);
                case Mpv.EVENT_END_FILE -> {
                    if (event.endReason() == Mpv.END_FILE_REASON_ERROR) {
                        final String error = Mpv.errorString(event.endError());
                        Ui.runOnMain(() -> listener.message("Playback failed: " + error));
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Drops the last video's subtitles (stopping their fetch) and waits for {@code source}'s. */
    private void expectSubtitles(final MpvPlayer.Source source) {
        final long generation;
        final CompletableFuture<List<Subtitles.Track>> fetch = source.subtitles();
        synchronized (subtitleLock) {
            if (subtitleFetch != null && subtitleFetch != fetch) {
                subtitleFetch.cancel(true);
            }
            generation = ++subtitleGeneration;
            subtitleUrl = source.url();
            subtitleFileLoaded = false;
            readySubtitles = List.of();
            subtitleFetch = fetch;
        }
        if (source.noVideo()) {
            return;
        }
        fetch.thenAccept(tracks -> {
            // Under the lock, so dispose can't destroy mpv mid-add
            synchronized (subtitleLock) {
                if (generation != subtitleGeneration || tracks.isEmpty()) {
                    return;
                }
                if (!subtitleFileLoaded) {
                    readySubtitles = tracks;
                } else if (mpv != null && !closing) {
                    addSubtitles(mpv, tracks);
                }
            }
        });
    }

    /** Event thread: adds subtitles that arrived before the file finished loading. */
    private void fileLoaded(final Mpv core) {
        final List<Subtitles.Track> tracks;
        synchronized (subtitleLock) {
            // A late event for the file before can't take this one's subtitles
            if (subtitleUrl == null || !subtitleUrl.equals(core.getProperty("path"))) {
                return;
            }
            subtitleFileLoaded = true;
            tracks = readySubtitles;
            readySubtitles = List.of();
        }
        addSubtitles(core, tracks);
    }

    private void addSubtitles(final Mpv core, final List<Subtitles.Track> tracks) {
        for (final Subtitles.Track t : tracks) {
            try {
                core.command("sub-add", t.location(), t.select() ? "select" : "auto",
                        t.title(), t.lang() == null ? "" : t.lang());
            } catch (final IllegalStateException e) {
                System.err.println("Subtitles: couldn't load " + t.title() + ": "
                        + e.getMessage());
            }
        }
    }

    private void reportProgress() {
        lastProgressNanos = System.nanoTime();
        final double p = position;
        final double d = duration;
        Ui.runOnMain(() -> listener.progress(p, d));
    }

    private void skipSponsors(final Mpv core, final double time) {
        for (final MpvPlayer.SkipSegment segment : skipSegments) {
            if (time < segment.start() || time >= segment.end() - 0.3) {
                continue;
            }
            // time-pos keeps reporting the old position until the seek lands
            if (segment == lastSkipped && System.nanoTime() - lastSkipNanos < SKIP_DEBOUNCE_NANOS) {
                return;
            }
            lastSkipped = segment;
            lastSkipNanos = System.nanoTime();
            try {
                core.command("seek", String.valueOf(segment.end()), "absolute");
                Ui.runOnMain(() -> listener.message("Skipped " + segment.label()));
            } catch (final IllegalStateException e) {
                e.printStackTrace();
            }
            return;
        }
    }

    private void createRender() {
        if (mpv == null || render != null) {
            return;
        }
        area.makeCurrent();
        if (area.getError() != null) {
            listener.message("Couldn't start video output: " + area.getError().readMessage());
            return;
        }
        try {
            render = mpv.createGlRender(this::scheduleRender);
            if (videoTrack != null) {
                // mpv may still report the track as selected; toggle it to really reselect
                mpv.setProperty("vid", "no");
                mpv.setProperty("vid", videoTrack);
                videoTrack = null;
            }
        } catch (final IllegalStateException e) {
            listener.message(Ui.errorMessage(e));
        }
    }

    private void freeRender() {
        if (render == null) {
            return;
        }
        // Freeing the render context turns video off (if the GLArea is ever unrealized while
        // playing); remember the track to turn it back on in createRender
        videoTrack = null;
        if (mpv != null && !closing) {
            final String vid = mpv.getProperty("vid");
            if (vid != null && !"no".equals(vid)) {
                videoTrack = vid;
            }
        }
        area.makeCurrent();
        render.free();
        render = null;
    }

    /** Called on an mpv thread; coalesces frame updates into one redraw on the main thread. */
    private void scheduleRender() {
        if (renderQueued.compareAndSet(false, true)) {
            Ui.runOnMain(() -> {
                renderQueued.set(false);
                area.queueRender();
            });
        }
    }

    private void setUpInput() {
        final EventControllerMotion motion = new EventControllerMotion();
        motion.onEnter(this::mouseMoved);
        motion.onMotion(this::mouseMoved);
        motion.onLeave(() -> input("keypress", "MOUSE_LEAVE"));
        area.addController(motion);

        final GestureClick click = new GestureClick();
        click.setButton(0);
        click.onPressed((presses, x, y) -> {
            area.grabFocus();
            mouseMoved(x, y);
            input("keydown", mouseButton(click.getCurrentButton()));
        });
        click.onReleased((presses, x, y) -> {
            mouseMoved(x, y);
            input("keyup", mouseButton(click.getCurrentButton()));
        });
        // Another gesture took the press over (dragging the mini player): release it in mpv
        click.onCancel(sequence -> input("keyup", mouseButton(click.getCurrentButton())));
        area.addController(click);

        // In the page the wheel scrolls; in fullscreen it goes to mpv (seek by default)
        final EventControllerScroll scroll = new EventControllerScroll(
                EventControllerScrollFlags.VERTICAL, EventControllerScrollFlags.DISCRETE);
        scroll.onScroll((dx, dy) -> {
            if (!fullscreen || mpv == null || dy == 0) {
                return false;
            }
            input("keypress", dy < 0 ? "WHEEL_UP" : "WHEEL_DOWN");
            return true;
        });
        area.addController(scroll);

        final EventControllerKey keys = new EventControllerKey();
        keys.onKeyPressed((keyval, keycode, state) -> {
            final String key = mpvKey(keyval, state);
            if (key == null || mpv == null) {
                return false;
            }
            if ("t".equals(key)) {
                listener.toggleTheater();
                return true;
            }
            input("keypress", key);
            return true;
        });
        area.addController(keys);
    }

    private void mouseMoved(final double x, final double y) {
        if (mpv == null || area.getWidth() == 0) {
            return;
        }
        // mpv works in framebuffer pixels
        final double scale = pixelWidth > 0 ? (double) pixelWidth / area.getWidth() : 1;
        input("mouse", String.valueOf((int) (x * scale)), String.valueOf((int) (y * scale)));
    }

    private void input(final String... command) {
        if (mpv == null) {
            return;
        }
        try {
            mpv.command(command);
        } catch (final IllegalStateException ignored) {
            // Unknown key names and the like; nothing to do
        }
    }

    private static String mouseButton(final int button) {
        return switch (button) {
            case 2 -> "MBTN_MID";
            case 3 -> "MBTN_RIGHT";
            case 8 -> "MBTN_BACK";
            case 9 -> "MBTN_FORWARD";
            default -> "MBTN_LEFT";
        };
    }

    /** Translates a GDK key press to an mpv key name, or null to leave it to GTK. */
    private static String mpvKey(final int keyval, final Set<ModifierType> state) {
        final String gdkName = Gdk.keyvalName(keyval);
        String name = gdkName == null ? null : KEY_NAMES.get(gdkName);
        final boolean named = name != null
                || (gdkName != null && gdkName.matches("F([1-9]|1[0-2])"));
        if (named && name == null) {
            name = gdkName;
        }
        if (!named) {
            final int codePoint = Gdk.keyvalToUnicode(keyval);
            if (codePoint < 0x20 || codePoint == 0x7f) {
                return null;
            }
            name = Character.toString(codePoint);
        }
        // "quit" would destroy the embedded player; leave q to GTK
        if ("q".equals(name) || "Q".equals(name)) {
            return null;
        }

        final StringBuilder key = new StringBuilder();
        if (state.contains(ModifierType.CONTROL_MASK)) {
            key.append("Ctrl+");
        }
        if (state.contains(ModifierType.ALT_MASK)) {
            key.append("Alt+");
        }
        if (state.contains(ModifierType.SUPER_MASK)) {
            key.append("Meta+");
        }
        // Shift is already applied to printable characters
        if (named && state.contains(ModifierType.SHIFT_MASK)) {
            key.append("Shift+");
        }
        return key.append(name).toString();
    }
}
