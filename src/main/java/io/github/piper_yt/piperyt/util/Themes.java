package io.github.piper_yt.piperyt.util;

import io.github.piper_yt.piperyt.db.Db;
import io.github.piper_yt.piperyt.db.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The selected theme and the user's own themes, kept in the library's settings (so they travel
 * with the JSON export). Built-in themes can't be changed; duplicate one to customize it.
 */
public final class Themes {
    static final Settings.Key<String> SELECTED = new Settings.Key<>("theme.selected",
            Theme.EMBER_MUTED.id());
    static final Settings.Key<String> CUSTOM = new Settings.Key<>("theme.custom", "[]");

    private static final List<Consumer<Theme>> LISTENERS = new CopyOnWriteArrayList<>();

    private Themes() {
    }

    /** Called with the new current theme whenever it, or its colors, change. */
    public static Runnable listen(final Consumer<Theme> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** The theme in use: the selected one, or Ember Muted if it's gone. */
    public static Theme current() {
        if (!Db.isOpen()) {
            return Theme.EMBER_MUTED;
        }
        final Theme t = byId(Settings.getString(SELECTED));
        return t == null ? Theme.EMBER_MUTED : t;
    }

    /** Built-in themes, then the user's, in the order they were made. */
    public static List<Theme> all() {
        final List<Theme> all = new ArrayList<>(Theme.BUILT_IN);
        all.addAll(custom());
        return all;
    }

    public static List<Theme> custom() {
        final List<Theme> list = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(Settings.getString(CUSTOM));
            for (int i = 0; i < array.length(); i++) {
                list.add(Theme.fromJson(array.getJSONObject(i)));
            }
        } catch (final RuntimeException e) {
            System.err.println("Custom themes unreadable: " + e.getMessage());
        }
        return list;
    }

    public static Theme byId(final String id) {
        for (final Theme t : all()) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    public static void select(final String id) {
        if (byId(id) != null) {
            Settings.set(SELECTED, id);
            changed();
        }
    }

    /** A custom copy of {@code base} (named "… copy"), selected. */
    public static Theme duplicate(final Theme base) {
        final Theme copy = base.withId(newId()).withName(uniqueName(base.name() + " copy"));
        final List<Theme> list = custom();
        list.add(copy);
        save(list);
        select(copy.id());
        return copy;
    }

    /** Adds an imported theme as a new custom theme (never overwriting one), selected. */
    public static Theme add(final Theme imported) {
        final Theme t = imported.withId(newId()).withName(uniqueName(imported.name()));
        final List<Theme> list = custom();
        list.add(t);
        save(list);
        select(t.id());
        return t;
    }

    /** Replaces a custom theme with {@code updated} (same id). Built-ins are left alone. */
    public static void update(final Theme updated) {
        if (updated.isBuiltIn()) {
            return;
        }
        final List<Theme> list = custom();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(updated.id())) {
                list.set(i, updated);
            }
        }
        save(list);
        if (current().id().equals(updated.id())) {
            changed();
        }
    }

    /** Deletes a custom theme; if it was in use, Ember Muted takes over. */
    public static void delete(final String id) {
        final List<Theme> list = custom();
        list.removeIf(t -> t.id().equals(id));
        save(list);
        if (Settings.getString(SELECTED).equals(id)) {
            select(Theme.EMBER_MUTED.id());
        }
    }

    private static void save(final List<Theme> list) {
        final JSONArray array = new JSONArray();
        for (final Theme t : list) {
            array.put(t.toJson());
        }
        Settings.set(CUSTOM, array.toString());
    }

    private static String newId() {
        return "custom-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueName(final String wanted) {
        String name = wanted == null || wanted.isBlank() ? "My theme" : wanted.strip();
        int n = 2;
        final String base = name;
        while (nameTaken(name)) {
            name = base + " " + n++;
        }
        return name;
    }

    private static boolean nameTaken(final String name) {
        return all().stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
    }

    private static void changed() {
        final Theme t = current();
        for (final Consumer<Theme> l : LISTENERS) {
            l.accept(t);
        }
    }
}
