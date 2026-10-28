package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.Main;
import io.github.jwharm.javagi.base.GErrorException;
import org.gnome.gio.BusNameOwnerFlags;
import org.gnome.gio.BusType;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusMethodInvocation;
import org.gnome.gio.DBusNodeInfo;
import org.gnome.gio.Gio;
import org.gnome.glib.GError;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;

import java.lang.foreign.Arena;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the embedded player over MPRIS, so media keys reach it: the desktop sends a
 * Bluetooth speaker's or headset's play/pause button, and keyboard media keys, to MPRIS
 * players. Controls whichever {@link PlayerView} played last. Main thread only (GDBus calls
 * back on the thread that registered the object).
 */
final class Mpris {
    private static final String BUS_NAME = "org.mpris.MediaPlayer2.piper_yt";
    private static final String PATH = "/org/mpris/MediaPlayer2";
    private static final String ROOT = "org.mpris.MediaPlayer2";
    private static final String PLAYER = "org.mpris.MediaPlayer2.Player";
    private static final String NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack";
    private static final String XML = """
            <node>
              <interface name="org.mpris.MediaPlayer2">
                <method name="Raise"/>
                <method name="Quit"/>
                <property name="CanQuit" type="b" access="read"/>
                <property name="CanRaise" type="b" access="read"/>
                <property name="HasTrackList" type="b" access="read"/>
                <property name="Identity" type="s" access="read"/>
                <property name="DesktopEntry" type="s" access="read"/>
                <property name="SupportedUriSchemes" type="as" access="read"/>
                <property name="SupportedMimeTypes" type="as" access="read"/>
              </interface>
              <interface name="org.mpris.MediaPlayer2.Player">
                <method name="Next"/>
                <method name="Previous"/>
                <method name="Pause"/>
                <method name="PlayPause"/>
                <method name="Stop"/>
                <method name="Play"/>
                <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
                <method name="SetPosition">
                  <arg name="TrackId" type="o" direction="in"/>
                  <arg name="Position" type="x" direction="in"/>
                </method>
                <method name="OpenUri"><arg name="Uri" type="s" direction="in"/></method>
                <signal name="Seeked"><arg name="Position" type="x"/></signal>
                <property name="PlaybackStatus" type="s" access="read"/>
                <property name="Rate" type="d" access="read"/>
                <property name="Metadata" type="a{sv}" access="read"/>
                <property name="Volume" type="d" access="read"/>
                <property name="Position" type="x" access="read"/>
                <property name="MinimumRate" type="d" access="read"/>
                <property name="MaximumRate" type="d" access="read"/>
                <property name="CanGoNext" type="b" access="read"/>
                <property name="CanGoPrevious" type="b" access="read"/>
                <property name="CanPlay" type="b" access="read"/>
                <property name="CanPause" type="b" access="read"/>
                <property name="CanSeek" type="b" access="read"/>
                <property name="CanControl" type="b" access="read"/>
              </interface>
            </node>
            """;

    private static DBusConnection connection;
    private static Runnable raise;
    private static PlayerView active;
    private static String title = "";
    private static long trackNumber;

    private Mpris() {
    }

    /** Registers on the session bus; {@code raiseWindow} brings the window to the front. */
    static void start(final Runnable raiseWindow) {
        if (connection != null) {
            return;
        }
        raise = raiseWindow;
        try {
            final DBusConnection bus = Gio.busGetSync(BusType.SESSION, null);
            final DBusNodeInfo node = DBusNodeInfo.forXml(XML);
            // The vtable and its callbacks must outlive the registration (the whole run)
            final DBusInterfaceVTable vtable = new DBusInterfaceVTable(
                    Mpris::call, Mpris::property, null, Arena.global());
            bus.registerObject(PATH, node.lookupInterface(ROOT), vtable, null);
            bus.registerObject(PATH, node.lookupInterface(PLAYER), vtable, null);
            Gio.busOwnNameOnConnection(bus, BUS_NAME, BusNameOwnerFlags.DO_NOT_QUEUE, null, null);
            connection = bus;
        } catch (final GErrorException | RuntimeException e) {
            // Media keys just won't work; not worth failing the app over
            System.err.println("MPRIS: " + e.getMessage());
        }
    }

    /** {@code view} started a video titled {@code videoTitle}; it's now the one controlled. */
    static void playing(final PlayerView view, final String videoTitle) {
        active = view;
        title = videoTitle == null ? "" : videoTitle;
        trackNumber++;
        changed("PlaybackStatus", "Metadata", "CanPlay", "CanPause", "CanSeek");
    }

    /** {@code view}'s pause state or duration changed. */
    static void updated(final PlayerView view) {
        if (view == active) {
            changed("PlaybackStatus", "Metadata");
        }
    }

    /** {@code view} was disposed. */
    static void released(final PlayerView view) {
        if (view == active) {
            active = null;
            title = "";
            changed("PlaybackStatus", "Metadata", "CanPlay", "CanPause", "CanSeek");
        }
    }

    private static void call(final DBusConnection bus, final String sender, final String path,
                             final String iface, final String method, final Variant params,
                             final DBusMethodInvocation invocation) {
        // An exception escaping a GLib callback ends the JVM
        try {
            final PlayerView view = active;
            switch (method) {
                case "Raise" -> raise.run();
                case "PlayPause" -> {
                    if (view != null && view.isPaused()) {
                        play(view);
                    } else if (view != null) {
                        view.pause();
                    }
                }
                case "Play" -> play(view);
                case "Pause", "Stop" -> {
                    if (view != null) {
                        view.pause();
                    }
                }
                case "Seek" -> {
                    if (view != null) {
                        final double offset = params.getChildValue(0).getInt64() / 1e6;
                        view.seek(Math.max(0, view.position() + offset));
                    }
                }
                case "SetPosition" -> {
                    if (view != null) {
                        view.seek(Math.max(0, params.getChildValue(1).getInt64() / 1e6));
                    }
                }
                default -> {
                    // Quit, Next, Previous, OpenUri: not supported (the Can* properties say so)
                }
            }
        } catch (final RuntimeException e) {
            e.printStackTrace();
        }
        invocation.returnValue(null);
    }

    /**
     * Resumes only a player that's on screen: a video page hidden behind another page keeps
     * its paused player, and starting it would play audio from nowhere.
     */
    private static void play(final PlayerView view) {
        if (view != null && view.widget().getMapped()) {
            view.resume();
        }
    }

    private static Variant property(final DBusConnection bus, final String sender,
                                    final String path, final String iface, final String name,
                                    final GError[] error) {
        return value(name);
    }

    private static Variant value(final String name) {
        final PlayerView view = active;
        return switch (name) {
            case "CanQuit", "HasTrackList", "CanGoNext", "CanGoPrevious" -> Variant.boolean_(false);
            case "CanRaise", "CanControl" -> Variant.boolean_(true);
            case "CanPlay", "CanPause", "CanSeek" -> Variant.boolean_(view != null);
            case "Identity" -> Variant.string("Piper");
            case "DesktopEntry" -> Variant.string(Main.APP_ID);
            case "SupportedUriSchemes", "SupportedMimeTypes" -> Variant.strv(new String[0]);
            case "PlaybackStatus" -> Variant.string(view == null ? "Stopped"
                    : view.isPaused() ? "Paused" : "Playing");
            case "Rate", "Volume", "MinimumRate", "MaximumRate" -> Variant.double_(1);
            case "Position" -> Variant.int64(view == null ? 0 : Math.round(view.position() * 1e6));
            case "Metadata" -> metadata(view);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Variant metadata(final PlayerView view) {
        final Map<String, Variant> entries = new LinkedHashMap<>();
        if (view == null) {
            entries.put("mpris:trackid", Variant.objectPath(NO_TRACK));
        } else {
            entries.put("mpris:trackid", Variant.objectPath(PATH + "/Track/" + trackNumber));
            entries.put("xesam:title", Variant.string(title));
            if (view.duration() > 0) {
                entries.put("mpris:length", Variant.int64(Math.round(view.duration() * 1e6)));
            }
        }
        return dict(entries);
    }

    private static Variant dict(final Map<String, Variant> entries) {
        final Variant[] items = entries.entrySet().stream()
                .map(e -> Variant.dictEntry(Variant.string(e.getKey()), Variant.variant(e.getValue())))
                .toArray(Variant[]::new);
        return Variant.array(new VariantType("{sv}"), items);
    }

    private static void changed(final String... names) {
        if (connection == null) {
            return;
        }
        final Map<String, Variant> values = new LinkedHashMap<>();
        for (final String name : names) {
            values.put(name, value(name));
        }
        try {
            connection.emitSignal(null, PATH, "org.freedesktop.DBus.Properties",
                    "PropertiesChanged", Variant.tuple(new Variant[] {
                            Variant.string(PLAYER), dict(values), Variant.strv(new String[0])}));
        } catch (final GErrorException e) {
            System.err.println("MPRIS: " + e.getMessage());
        }
    }
}
