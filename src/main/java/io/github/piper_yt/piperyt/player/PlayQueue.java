package io.github.piper_yt.piperyt.player;

import io.github.piper_yt.piperyt.db.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What plays next: "Play all", "Shuffle" and "Queue all" from playlists. The video page asks
 * it for the next video when one ends. Main thread only.
 */
public final class PlayQueue {
    private static final List<Video> ITEMS = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static int index = -1;
    /** Bumped when the queue is replaced or cleared, so late additions can tell. */
    private static volatile int generation;

    private PlayQueue() {
    }

    public static Runnable listen(final Runnable listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Replaces the queue with {@code videos} and makes {@code start} current. */
    public static Video play(final List<Video> videos, final int start, final boolean shuffle) {
        generation++;
        ITEMS.clear();
        ITEMS.addAll(playable(videos));
        if (ITEMS.isEmpty()) {
            index = -1;
            changed();
            return null;
        }
        if (shuffle) {
            Collections.shuffle(ITEMS);
            index = 0;
        } else {
            index = Math.clamp(start, 0, ITEMS.size() - 1);
        }
        changed();
        return ITEMS.get(index);
    }

    /** Adds {@code videos} at the end; returns how many were added. */
    public static int append(final List<Video> videos) {
        final List<Video> add = playable(videos);
        ITEMS.addAll(add);
        changed();
        return add.size();
    }

    /** Puts {@code video} right after the current one. */
    public static void playNext(final Video video) {
        ITEMS.add(index + 1, video);
        if (index < 0) {
            index = 0;
        }
        changed();
    }

    /** Changes whenever the queue is replaced or cleared. */
    public static int generation() {
        return generation;
    }

    public static List<Video> items() {
        return List.copyOf(ITEMS);
    }

    public static int index() {
        return index;
    }

    public static boolean isEmpty() {
        return ITEMS.isEmpty();
    }

    public static Video current() {
        return index >= 0 && index < ITEMS.size() ? ITEMS.get(index) : null;
    }

    /** Whether {@code url} is the queue's current video (so its page continues the queue). */
    public static boolean isCurrent(final String url) {
        final Video v = current();
        return v != null && v.url().equals(Video.canonicalUrl(url));
    }

    public static boolean hasNext() {
        return index + 1 < ITEMS.size();
    }

    public static boolean hasPrevious() {
        return index > 0;
    }

    /** Moves to the next video and returns it, or null at the end. */
    public static Video next() {
        if (!hasNext()) {
            return null;
        }
        index++;
        changed();
        return ITEMS.get(index);
    }

    public static Video previous() {
        if (!hasPrevious()) {
            return null;
        }
        index--;
        changed();
        return ITEMS.get(index);
    }

    public static Video jump(final int i) {
        if (i < 0 || i >= ITEMS.size()) {
            return null;
        }
        index = i;
        changed();
        return ITEMS.get(i);
    }

    public static void remove(final int i) {
        if (i < 0 || i >= ITEMS.size()) {
            return;
        }
        ITEMS.remove(i);
        if (i < index || index >= ITEMS.size()) {
            index--;
        }
        changed();
    }

    public static void clear() {
        generation++;
        ITEMS.clear();
        index = -1;
        changed();
    }

    private static List<Video> playable(final List<Video> videos) {
        return videos.stream().filter(v -> v.url() != null && !v.url().isBlank()).toList();
    }

    private static void changed() {
        for (final Runnable l : LISTENERS) {
            l.run();
        }
    }
}
