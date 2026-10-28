package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.net.BotWall;
import io.github.piper_yt.piperyt.net.NetworkSettings;
import io.github.piper_yt.piperyt.util.Theme;
import io.github.piper_yt.piperyt.util.Themes;
import org.gnome.adw.ColorScheme;
import org.gnome.adw.StyleManager;
import org.gnome.gdk.Display;
import org.gnome.glib.GLib;
import org.gnome.glib.Source;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Label;
import org.gnome.pango.EllipsizeMode;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Threading and widget helpers. GTK may only be touched from the main thread. */
public final class Ui {
    private static final ExecutorService BACKGROUND = Executors.newVirtualThreadPerTaskExecutor();
    private static final Queue<Runnable> MAIN_QUEUE = new ConcurrentLinkedQueue<>();
    private static Source mainDispatcher;

    private static final String CSS = """
            .thumbnail { border-radius: 12px; }
            .thumbnail-small { border-radius: 8px; }
            .duration-badge {
                border-radius: 6px;
                padding: 1px 6px;
                margin: 6px;
                font-size: smaller;
                font-weight: bold;
            }
            .video-card { padding: 6px; border-radius: 16px; }
            .saved-badge {
                border-radius: 6px;
                padding: 3px;
                margin: 6px;
            }
            .menu-box { padding: 4px; }
            .menu-item { padding: 6px 12px; font-weight: normal; }
            .view-header { padding: 12px 18px 0 18px; }
            .drag-handle { opacity: 0.55; }
            .drop-target { box-shadow: inset 0 2px 0 0 @accent_color; }
            .pick-reason { font-weight: bold; }
            progressbar.watch-progress { margin: 0 6px 4px 6px; }
            progressbar.watch-progress > trough,
            progressbar.watch-progress > trough > progress {
                min-height: 4px;
                border-radius: 2px;
            }
            .up-next-watched { opacity: 0.6; }
            .mini-player { border-radius: 12px; }
            .mini-player-video { border-radius: 12px 12px 0 0; }
            .autoplay-card { border-radius: 12px; padding: 12px 16px; }
            """;

    private Ui() {
    }

    /**
     * Sets up the main-thread dispatcher; call once on the main thread at startup.
     *
     * <p>Background threads must not pass callbacks to GLib (e.g. {@code idleAddOnce}): java-gi
     * frees a callback's arena when it has run, which can happen on the main thread before the
     * scheduling call returns on the background thread, and the JVM aborts. Instead one
     * long-lived source drains a queue, and background threads only wake it with the
     * thread-safe {@code g_source_set_ready_time}.
     */
    public static void initMainThread() {
        mainDispatcher = GLib.timeoutSourceNewSeconds(3600);
        mainDispatcher.setCallback(() -> {
            Runnable task;
            while ((task = MAIN_QUEUE.poll()) != null) {
                try {
                    task.run();
                } catch (final Throwable t) {
                    t.printStackTrace();
                }
            }
            return true;
        });
        mainDispatcher.attach(null);
    }

    /** Runs {@code task} on the GTK main thread. Safe to call from any thread. */
    public static void runOnMain(final Runnable task) {
        MAIN_QUEUE.add(task);
        mainDispatcher.setReadyTime(0);
    }

    /** Runs {@code work} off the main thread, then hands the result back on it. */
    public static <T> void async(final Callable<T> work, final Consumer<T> onDone,
                                 final Consumer<Throwable> onError) {
        BACKGROUND.submit(() -> {
            try {
                final T result = work.call();
                runOnMain(() -> onDone.accept(result));
            } catch (final Throwable t) {
                t.printStackTrace();
                runOnMain(() -> onError.accept(t));
            }
        });
    }

    /**
     * {@link #async} for extractor requests: a bot check is retried with backoff (and, if
     * enabled, a new Mullvad exit) before {@code onError} sees it.
     */
    public static <T> void fetch(final Callable<T> work, final Consumer<T> onDone,
                                 final Consumer<Throwable> onError) {
        async(() -> BotWall.retrying(work), onDone, onError);
    }

    public static void background(final Runnable work) {
        BACKGROUND.submit(work);
    }

    /**
     * Loads the app's styles and the selected theme, and follows theme changes live: the theme
     * has its own provider, reloaded in place.
     */
    public static void loadCss() {
        final CssProvider provider = new CssProvider();
        provider.loadFromString(CSS);
        Gtk.styleContextAddProviderForDisplay(Display.getDefault(), provider,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        // Above user priority: ~/.config/gtk-4.0/gtk.css themes every GTK app, but the theme
        // picked in the app should win in the app
        Gtk.styleContextAddProviderForDisplay(Display.getDefault(), THEME,
                Gtk.STYLE_PROVIDER_PRIORITY_USER + 1);
        applyTheme(Themes.current());
        Themes.listen(theme -> runOnMain(() -> applyTheme(theme)));
    }

    private static final CssProvider THEME = new CssProvider();

    private static void applyTheme(final Theme theme) {
        StyleManager.getDefault().setColorScheme(theme.dark()
                ? ColorScheme.FORCE_DARK : ColorScheme.FORCE_LIGHT);
        THEME.loadFromString(theme.css());
    }

    public static Label label(final String text, final String... cssClasses) {
        final Label label = new Label(text == null ? "" : text);
        label.setXalign(0);
        for (final String css : cssClasses) {
            label.addCssClass(css);
        }
        return label;
    }

    /** Removes every child of {@code box}. */
    public static void clear(final org.gnome.gtk.Box box) {
        while (box.getFirstChild() != null) {
            box.remove(box.getFirstChild());
        }
    }

    /** Escapes text for widgets that render Pango markup (libadwaita row titles). */
    public static String markupSafe(final String text) {
        return io.github.piper_yt.piperyt.util.Format.escape(text);
    }

    /**
     * A view's title row: a large title, then {@code actions} at the end. Every sidebar view
     * starts with one.
     */
    public static org.gnome.gtk.Box viewHeader(final String title,
                                               final org.gnome.gtk.Widget... actions) {
        final org.gnome.gtk.Box box = new org.gnome.gtk.Box(org.gnome.gtk.Orientation.HORIZONTAL, 8);
        box.addCssClass("view-header");
        final Label label = label(title, "title-2");
        label.setHexpand(true);
        box.append(label);
        for (final org.gnome.gtk.Widget w : actions) {
            w.setValign(org.gnome.gtk.Align.CENTER);
            box.append(w);
        }
        return box;
    }

    public static Label wrapping(final String text, final int maxLines, final String... css) {
        final Label label = label(text, css);
        label.setWrap(true);
        label.setWrapMode(org.gnome.pango.WrapMode.WORD_CHAR);
        if (maxLines > 0) {
            label.setLines(maxLines);
            label.setEllipsize(EllipsizeMode.END);
        }
        return label;
    }

    /** A short, user-facing description of an extraction failure. */
    public static String errorMessage(final Throwable t) {
        if (NetworkSettings.route() == NetworkSettings.Route.SOCKS5 && isConnectFailure(t)) {
            return "Can't reach the SOCKS5 proxy at " + NetworkSettings.socksAddress()
                    + ". Is Mullvad connected? Nothing was sent outside the proxy.";
        }
        Throwable cause = t;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        final String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private static boolean isConnectFailure(final Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            final String msg = String.valueOf(c.getMessage());
            if (c instanceof ConnectException || c instanceof NoRouteToHostException
                    || c instanceof SocketException && msg.contains("SOCKS")
                    || c instanceof SocketTimeoutException && msg.contains("connect")) {
                return true;
            }
        }
        return false;
    }
}
