package io.github.piper_yt.piperyt.ui;

import io.github.jwharm.javagi.gobject.types.Types;
import io.github.piper_yt.piperyt.db.Settings;
import org.gnome.adw.Application;
import org.gnome.gio.SimpleAction;
import org.gnome.gobject.Value;

/**
 * Ctrl+plus / Ctrl+minus / Ctrl+0 zoom. GTK has no whole-window zoom, so this scales the text
 * DPI: all text grows, and so does everything sized by its text (buttons, rows, headers).
 * Thumbnails and other fixed-size images keep their size.
 */
final class Zoom {
    private static final int MIN = 50;
    private static final int MAX = 200;
    private static final int STEP = 10;
    /** GTK's DPI unit: dots per inch times 1024. -1 means "unset", i.e. 96 DPI. */
    private static final int DEFAULT_DPI = 96 * 1024;

    /** The desktop's own text DPI, before zoom. */
    private static int baseDpi;
    /** The DPI this class last set, to tell its own changes from the desktop's. */
    private static int appliedDpi;

    private Zoom() {
    }

    static void install(final Application app) {
        final org.gnome.gtk.Settings gtk = org.gnome.gtk.Settings.getDefault();
        baseDpi = dpi(gtk);
        // The desktop changed its text scaling: zoom relative to the new value
        gtk.onNotify("gtk-xft-dpi", pspec -> {
            final int now = dpi(gtk);
            if (now != appliedDpi) {
                baseDpi = now;
                apply();
            }
        });

        action(app, "zoom-in", () -> set(Settings.getInt(Settings.UI_ZOOM) + STEP),
                "<Control>plus", "<Control>equal", "<Control>KP_Add");
        action(app, "zoom-out", () -> set(Settings.getInt(Settings.UI_ZOOM) - STEP),
                "<Control>minus", "<Control>KP_Subtract");
        action(app, "zoom-reset", () -> set(100), "<Control>0", "<Control>KP_0");
        apply();
    }

    private static void action(final Application app, final String name, final Runnable run,
                               final String... accels) {
        final SimpleAction action = new SimpleAction(name, null);
        action.onActivate(param -> run.run());
        app.addAction(action);
        app.setAccelsForAction("app." + name, accels);
    }

    private static void set(final int percent) {
        final int clamped = Math.clamp(percent, MIN, MAX);
        if (clamped != Settings.getInt(Settings.UI_ZOOM)) {
            Settings.set(Settings.UI_ZOOM, clamped);
            apply();
        }
    }

    private static void apply() {
        final int percent = Math.clamp(Settings.getInt(Settings.UI_ZOOM), MIN, MAX);
        appliedDpi = (int) ((long) baseDpi * percent / 100);
        final Value value = new Value();
        value.init(Types.INT);
        value.setInt(appliedDpi);
        org.gnome.gtk.Settings.getDefault().setProperty("gtk-xft-dpi", value);
        value.unset();
    }

    private static int dpi(final org.gnome.gtk.Settings gtk) {
        final Value value = new Value();
        value.init(Types.INT);
        gtk.getProperty("gtk-xft-dpi", value);
        final int dpi = value.getInt();
        value.unset();
        return dpi > 0 ? dpi : DEFAULT_DPI;
    }
}
