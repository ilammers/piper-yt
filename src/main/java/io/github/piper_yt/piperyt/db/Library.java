package io.github.piper_yt.piperyt.db;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Change notifications for the library, so open views update in place. Stores stay free of
 * GTK: the UI installs a dispatcher that moves listeners onto the main thread.
 */
public final class Library {
    /** What changed. */
    public enum Topic { FAVORITES, FEED, PLAYLISTS, HISTORY, DOWNLOADS, HOME, BLOCKLIST }

    private record Listener(Topic topic, Runnable action) {
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Consumer<Runnable> dispatcher = Runnable::run;

    private Library() {
    }

    public static void setDispatcher(final Consumer<Runnable> d) {
        dispatcher = d;
    }

    /** Runs {@code action} (through the dispatcher) whenever {@code topic} changes. */
    public static Runnable listen(final Topic topic, final Runnable action) {
        final Listener l = new Listener(topic, action);
        LISTENERS.add(l);
        return () -> LISTENERS.remove(l);
    }

    public static void changed(final Topic... topics) {
        for (final Listener l : LISTENERS) {
            for (final Topic t : topics) {
                if (l.topic == t) {
                    dispatcher.accept(l.action);
                    break;
                }
            }
        }
    }
}
