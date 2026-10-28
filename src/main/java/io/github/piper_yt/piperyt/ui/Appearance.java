package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.util.Theme;
import io.github.piper_yt.piperyt.util.Themes;
import org.gnome.adw.ActionRow;
import org.gnome.adw.ComboRow;
import org.gnome.adw.EntryRow;
import org.gnome.adw.PreferencesGroup;
import org.gnome.adw.PreferencesPage;
import org.gnome.adw.SwitchRow;
import org.gnome.gdk.RGBA;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColorDialog;
import org.gnome.gtk.ColorDialogButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.StringList;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preferences → Appearance: pick a theme, and edit your own. Built-in themes (Ember Muted,
 * Gruvbox Dark and Light) are read-only; "Duplicate" makes an editable copy. Every change
 * applies at once; the player picks up new colors the next time it starts.
 */
final class Appearance {
    private final MainWindow main;
    private final ComboRow chooser = new ComboRow();
    private final Button delete = Button.withLabel("Delete");
    private final PreferencesGroup editor = new PreferencesGroup();
    private final EntryRow name = new EntryRow();
    private final SwitchRow dark = new SwitchRow();
    private final EntryRow font = new EntryRow();
    private final Map<Theme.Role, ColorDialogButton> colorButtons =
            new EnumMap<>(Theme.Role.class);
    private List<Theme> themes = List.of();
    /** What the chooser's list shows now, so it's only replaced when that changes. */
    private List<String> chooserLabels = List.of();
    /** Set while the page fills its own widgets, so their change handlers stay quiet. */
    private boolean filling;

    private Appearance(final MainWindow main) {
        this.main = main;
    }

    static PreferencesPage page(final MainWindow main) {
        return new Appearance(main).build();
    }

    private PreferencesPage build() {
        final PreferencesGroup pick = new PreferencesGroup();
        pick.setTitle("Theme");
        chooser.setTitle("Theme");
        pick.add(chooser);
        chooser.onNotify("selected", pspec -> {
            if (filling || chooser.getSelected() >= themes.size()) {
                return;
            }
            final String id = themes.get((int) chooser.getSelected()).id();
            // Already in use: nothing to do. Re-filling here would replace the list while the
            // dropdown is still handling the click, which selects again, endlessly.
            if (!id.equals(Themes.current().id())) {
                Themes.select(id);
                fill();
            }
        });

        final Box actions = new Box(Orientation.HORIZONTAL, 6);
        actions.setHalign(Align.END);
        actions.setMarginTop(6);
        final Button duplicate = Button.withLabel("Duplicate");
        duplicate.setTooltipText("Make an editable copy of this theme");
        duplicate.onClicked(() -> {
            Themes.duplicate(Themes.current());
            fill();
        });
        final Button importTheme = Button.withLabel("Import…");
        importTheme.onClicked(() -> Dialogs.openFile(main, "Import theme", "Theme (.json)",
                new String[]{"*.json"}, path -> {
                    try {
                        final JSONObject json = new JSONObject(
                                Files.readString(path, StandardCharsets.UTF_8));
                        if (!json.has("colors")) {
                            throw new IllegalArgumentException("not a Piper theme");
                        }
                        final Theme t = Themes.add(Theme.fromJson(json));
                        fill();
                        main.toast("Imported " + t.name());
                    } catch (final Exception e) {
                        main.toast("Couldn't import the theme: " + Ui.errorMessage(e));
                    }
                }));
        final Button export = Button.withLabel("Export…");
        export.onClicked(() -> {
            final Theme t = Themes.current();
            Dialogs.saveFile(main, "Export theme",
                    t.name().replaceAll("[/\\\\]", "_") + ".json", path -> {
                        try {
                            Files.writeString(path, t.toJson().toString(2),
                                    StandardCharsets.UTF_8);
                            main.toast("Exported " + t.name());
                        } catch (final Exception e) {
                            main.toast("Couldn't export: " + Ui.errorMessage(e));
                        }
                    });
        });
        delete.addCssClass("destructive-action");
        delete.onClicked(() -> Dialogs.confirm(main.window(), "Delete “"
                + Themes.current().name() + "”?", "Ember Muted is used instead.", "Delete",
                true, () -> {
                    Themes.delete(Themes.current().id());
                    fill();
                }));
        actions.append(duplicate);
        actions.append(importTheme);
        actions.append(export);
        actions.append(delete);
        pick.add(actions);

        name.setTitle("Name");
        name.setShowApplyButton(true);
        name.onApply(() -> {
            final String text = name.getText().strip();
            if (!text.isEmpty()) {
                Themes.update(Themes.current().withName(text));
                fill();
            }
        });
        editor.add(name);
        dark.setTitle("Dark theme");
        dark.setSubtitle("Dark or light variants of the standard widgets and icons");
        dark.onNotify("active", pspec -> edit(Themes.current().withDark(dark.getActive())));
        editor.add(dark);

        for (final Theme.Role role : Theme.Role.values()) {
            final ActionRow row = new ActionRow();
            row.setTitle(role.label);
            if (role.description != null) {
                row.setSubtitle(role.description);
            }
            final ColorDialog dialog = new ColorDialog();
            dialog.setWithAlpha(false);
            dialog.setTitle(role.label);
            final ColorDialogButton button = new ColorDialogButton(dialog);
            button.setValign(Align.CENTER);
            row.addSuffix(button);
            row.setActivatableWidget(button);
            editor.add(row);
            button.onNotify("rgba", pspec -> {
                if (filling) {
                    return;
                }
                // An exception escaping a GTK callback would end the JVM
                try {
                    edit(Themes.current().withColor(role, hex(button.getRgba())));
                } catch (final RuntimeException e) {
                    main.toast("Couldn't read the color: " + Ui.errorMessage(e));
                }
            });
            colorButtons.put(role, button);
        }

        font.setTitle("Player font (on-screen text)");
        font.setShowApplyButton(true);
        font.onApply(() -> edit(Themes.current().withFont(font.getText())));
        editor.add(font);

        fill();
        final PreferencesPage page = new PreferencesPage();
        page.setTitle("Appearance");
        page.setIconName("applications-graphics-symbolic");
        page.add(pick);
        page.add(editor);
        return page;
    }

    /** Saves a change to the current (custom) theme; ignored while filling. */
    private void edit(final Theme changed) {
        if (!filling && !changed.isBuiltIn()) {
            Themes.update(changed);
        }
    }

    /** Shows the current theme in every widget. */
    private void fill() {
        filling = true;
        try {
            themes = Themes.all();
            final Theme current = Themes.current();
            // Only rebuild the list when themes were added, removed or renamed
            final List<String> labels = themes.stream()
                    .map(t -> t.isBuiltIn() ? t.name() : t.name() + " (custom)")
                    .toList();
            if (!labels.equals(chooserLabels)) {
                chooserLabels = labels;
                chooser.setModel(new StringList(labels.toArray(String[]::new)));
            }
            for (int i = 0; i < themes.size(); i++) {
                if (themes.get(i).id().equals(current.id()) && chooser.getSelected() != i) {
                    chooser.setSelected(i);
                }
            }
            final boolean editable = !current.isBuiltIn();
            delete.setSensitive(editable);
            editor.setTitle("Colors");
            editor.setDescription(editable
                    ? "Changes apply right away. The player uses them from the next video on."
                    : current.name() + " is built in. Duplicate it to make your own version.");
            name.setText(current.name());
            dark.setActive(current.dark());
            font.setText(current.playerFont());
            for (final Map.Entry<Theme.Role, ColorDialogButton> e : colorButtons.entrySet()) {
                final RGBA rgba = new RGBA();
                rgba.parse(current.color(e.getKey()));
                e.getValue().setRgba(rgba);
            }
            name.setSensitive(editable);
            dark.setSensitive(editable);
            font.setSensitive(editable);
            colorButtons.values().forEach(b -> b.setSensitive(editable));
        } finally {
            filling = false;
        }
    }

    /**
     * "#rrggbb" for a color from GTK. java-gi 0.12 hands out the button's color with a
     * zero-length segment, so its fields can't be read; GDK's own {@code to_string}
     * ("rgb(194,120,97)") can.
     */
    static String hex(final RGBA c) {
        return hex(c.toString());
    }

    static String hex(final String rgb) {
        final Matcher m = RGB.matcher(rgb);
        if (!m.find()) {
            throw new IllegalArgumentException("Unexpected color " + rgb);
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(m.group(1)),
                channel(m.group(2)), channel(m.group(3)));
    }

    private static int channel(final String v) {
        return Math.clamp(Math.round(Double.parseDouble(v)), 0, 255);
    }

    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)");
}
