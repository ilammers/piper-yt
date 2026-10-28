package io.github.piper_yt.piperyt.util;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A color theme for both the GTK widgets (as libadwaita's named colors) and mpv's on-screen
 * controller. Ember Muted, a warm low-contrast dark palette, is the default;
 * Gruvbox Dark and Light are built in too, and users can make their own ({@link Themes}).
 *
 * @param dark       whether it's a dark theme (libadwaita's color scheme follows it)
 * @param playerFont font for mpv's on-screen text; mpv falls back if it isn't installed
 */
public record Theme(String id, String name, boolean dark, Map<Role, String> colors,
                    String playerFont) {

    /** The colors a theme sets. Every one is a {@code #rrggbb} value. */
    public enum Role {
        BG("View background", "Lists, grids and pages"),
        BG_ALT("Header bar", "Also the player's backdrop and badges"),
        BG_FLOAT("Window and sidebar", "Window, sidebar, dialog and popover background"),
        BG_LINE("Cards and rows", null),
        BORDER("Borders", null),
        FG("Text", null),
        ACCENT("Accent", "Suggested buttons, switches, selection"),
        ACCENT_TEXT("Accent text", "Links, homepage reasons"),
        ON_ACCENT("Text on accent", "Labels on accent-colored buttons"),
        RED("Destructive and errors", null),
        GREEN("Success", "Also the downloaded badge"),
        YELLOW("Warnings", null);

        public final String label;
        public final String description;

        Role(final String label, final String description) {
            this.label = label;
            this.description = description;
        }

        /** "bg_alt", for JSON. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final String DEFAULT_FONT = "MesloLGM Nerd Font Mono";
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    public static final Theme EMBER_MUTED = builtIn("ember-muted", "Ember Muted", true,
            "#0e1515", "#0c1110", "#151e1d", "#172120", "#232f2e", "#e0d9d1",
            "#c27861", "#c4826e", "#0e1515", "#d45c54", "#68b18c", "#e0be7b");

    public static final Theme GRUVBOX_DARK = builtIn("gruvbox-dark", "Gruvbox Dark", true,
            "#282828", "#1d2021", "#32302f", "#3c3836", "#504945", "#ebdbb2",
            "#fe8019", "#fe8019", "#282828", "#fb4934", "#b8bb26", "#fabd2f");

    public static final Theme GRUVBOX_LIGHT = builtIn("gruvbox-light", "Gruvbox Light", false,
            "#fbf1c7", "#f2e5bc", "#f9f5d7", "#ebdbb2", "#d5c4a1", "#3c3836",
            "#af3a03", "#af3a03", "#fbf1c7", "#9d0006", "#79740e", "#b57614");

    public static final List<Theme> BUILT_IN = List.of(EMBER_MUTED, GRUVBOX_DARK, GRUVBOX_LIGHT);

    public Theme {
        final Map<Role, String> complete = new EnumMap<>(Role.class);
        final Theme fallback = id == null || EMBER_MUTED == null ? null
                : dark ? EMBER_MUTED : GRUVBOX_LIGHT;
        for (final Role r : Role.values()) {
            final String c = colors == null ? null : colors.get(r);
            if (isColor(c)) {
                complete.put(r, c.toLowerCase(Locale.ROOT));
            } else if (fallback != null) {
                complete.put(r, fallback.color(r));
            }
        }
        colors = Map.copyOf(complete);
        playerFont = playerFont == null || playerFont.isBlank() ? DEFAULT_FONT : playerFont.strip();
    }

    private static Theme builtIn(final String id, final String name, final boolean dark,
                                 final String... hex) {
        final Map<Role, String> colors = new EnumMap<>(Role.class);
        for (int i = 0; i < hex.length; i++) {
            colors.put(Role.values()[i], hex[i]);
        }
        return new Theme(id, name, dark, colors, DEFAULT_FONT);
    }

    public static boolean isColor(final String s) {
        return s != null && HEX.matcher(s).matches();
    }

    public String color(final Role role) {
        return colors.get(role);
    }

    public boolean isBuiltIn() {
        return BUILT_IN.stream().anyMatch(t -> t.id().equals(id));
    }

    public Theme withName(final String newName) {
        return new Theme(id, newName, dark, colors, playerFont);
    }

    public Theme withDark(final boolean newDark) {
        return new Theme(id, name, newDark, colors, playerFont);
    }

    public Theme withFont(final String font) {
        return new Theme(id, name, dark, colors, font);
    }

    public Theme withColor(final Role role, final String hex) {
        final Map<Role, String> c = new EnumMap<>(colors);
        c.put(role, hex);
        return new Theme(id, name, dark, c, playerFont);
    }

    public Theme withId(final String newId) {
        return new Theme(newId, name, dark, colors, playerFont);
    }

    /**
     * libadwaita's named colors, the sidebar's row highlight, and the app's own classes. Loaded
     * just above user priority, so it beats a theme in ~/.config/gtk-4.0/gtk.css (which sets
     * these for every GTK app); everything that file may set is set here too.
     */
    public String css() {
        // Shadows are black either way, just lighter on light backgrounds
        final String shade = dark ? "rgba(0, 0, 0, 0.36)" : "rgba(0, 0, 0, 0.07)";
        final String softShade = dark ? "rgba(0, 0, 0, 0.30)" : "rgba(0, 0, 0, 0.12)";
        return """
                :root {
                  --window-bg-color: @bg_float@;
                  --window-fg-color: @fg@;
                  --headerbar-bg-color: @bg_alt@;
                  --headerbar-fg-color: @fg@;
                  --headerbar-backdrop-color: @bg@;
                  --headerbar-shade-color: @shade@;
                  --headerbar-border-color: @fg@;
                  --view-bg-color: @bg@;
                  --view-fg-color: @fg@;
                  --card-bg-color: @bg_line@;
                  --card-fg-color: @fg@;
                  --card-shade-color: @soft_shade@;
                  --dialog-bg-color: @bg_float@;
                  --dialog-fg-color: @fg@;
                  --popover-bg-color: @bg_float@;
                  --popover-fg-color: @fg@;
                  --popover-shade-color: @soft_shade@;
                  --accent-bg-color: @accent@;
                  --accent-fg-color: @on_accent@;
                  --accent-color: @accent_text@;
                  --destructive-bg-color: @red@;
                  --destructive-fg-color: @bg@;
                  --destructive-color: @red@;
                  --success-bg-color: @green@;
                  --success-fg-color: @bg@;
                  --success-color: @green@;
                  --warning-bg-color: @yellow@;
                  --warning-fg-color: @bg@;
                  --warning-color: @yellow@;
                  --error-bg-color: @red@;
                  --error-fg-color: @bg@;
                  --error-color: @red@;
                  --borders: @border@;
                  --shade-color: @shade@;
                  --scrollbar-outline-color: @bg_alt@;
                  --sidebar-bg-color: @bg_float@;
                  --sidebar-fg-color: @fg@;
                  --sidebar-backdrop-color: @bg_alt@;
                  --sidebar-shade-color: @shade@;
                  --sidebar-border-color: @border@;
                  --secondary-sidebar-bg-color: @bg_float@;
                  --secondary-sidebar-fg-color: @fg@;
                  --secondary-sidebar-border-color: @border@;
                  --secondary-sidebar-shade-color: @shade@;
                }
                columnview listview > row:selected,
                gridview > child:selected,
                .navigation-sidebar > row:selected {
                  background-color: mix(@bg_line@, @fg@, 0.07);
                  color: @fg@;
                }
                columnview listview > row:hover,
                gridview > child:hover,
                .navigation-sidebar > row:hover { background-color: @bg_line@; }
                .duration-badge { background-color: alpha(@bg_alt@, 0.85); color: @fg@; }
                .saved-badge { background-color: alpha(@bg_alt@, 0.85); color: @green@; }
                .pick-reason { color: @accent_text@; }
                .destructive-label { color: @red@; }
                .player-backdrop { background-color: @bg_alt@; }
                progressbar.watch-progress > trough { background-color: alpha(@bg_alt@, 0.7); }
                progressbar.watch-progress > trough > progress { background-color: @accent@; }
                .mini-player, .autoplay-card {
                  background-color: @bg_float@;
                  box-shadow: 0 2px 10px 2px @shade@;
                }
                .mini-player-video { background-color: @bg_alt@; }
                .uploader-link > link { color: inherit; text-decoration-line: none; }
                .uploader-link > link:hover { color: @accent_text@; text-decoration-line: underline; }
                .channel-banner { border-radius: 12px; }
                .channel-button { padding: 6px 12px 6px 6px; border-radius: 999px; }
                """
                .replace("@soft_shade@", softShade).replace("@shade@", shade)
                .replace("@bg_alt@", color(Role.BG_ALT)).replace("@bg_float@", color(Role.BG_FLOAT))
                .replace("@bg_line@", color(Role.BG_LINE)).replace("@bg@", color(Role.BG))
                .replace("@border@", color(Role.BORDER)).replace("@fg@", color(Role.FG))
                .replace("@accent_text@", color(Role.ACCENT_TEXT))
                .replace("@on_accent@", color(Role.ON_ACCENT))
                .replace("@accent@", color(Role.ACCENT)).replace("@red@", color(Role.RED))
                .replace("@green@", color(Role.GREEN)).replace("@yellow@", color(Role.YELLOW));
    }

    /** mpv options for the video backdrop and on-screen text. */
    public Map<String, String> mpvOptions() {
        final Map<String, String> options = new LinkedHashMap<>();
        options.put("background-color", color(Role.BG_ALT));
        options.put("osd-font", playerFont);
        options.put("osd-color", color(Role.FG));
        options.put("osd-outline-color", color(Role.BG_ALT));
        return options;
    }

    /** {@code script-opts} entries that recolor mpv's on-screen controller (osc.lua). */
    public String oscScriptOpts() {
        final Map<String, String> osc = new LinkedHashMap<>();
        osc.put("background_color", color(Role.BG_ALT));
        osc.put("boxalpha", "64");
        // Fills the seekbar as well as coloring the timecodes
        osc.put("timecode_color", color(Role.ACCENT));
        osc.put("title_color", color(Role.FG));
        osc.put("time_pos_color", color(Role.FG));
        osc.put("time_pos_outline_color", color(Role.BG_ALT));
        osc.put("buttons_color", color(Role.FG));
        osc.put("small_buttonsL_color", color(Role.FG));
        osc.put("small_buttonsR_color", color(Role.FG));
        osc.put("top_buttons_color", color(Role.FG));
        osc.put("held_element_color", color(Role.ACCENT));
        return osc.entrySet().stream()
                .map(e -> "osc-" + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    public JSONObject toJson() {
        final JSONObject colorsJson = new JSONObject();
        for (final Role r : Role.values()) {
            colorsJson.put(r.key(), color(r));
        }
        return new JSONObject().put("format", "piper-yt-theme").put("id", id)
                .put("name", name).put("dark", dark).put("player_font", playerFont)
                .put("colors", colorsJson);
    }

    /** Reads a theme; missing or invalid colors come from Ember Muted or Gruvbox Light. */
    public static Theme fromJson(final JSONObject o) {
        final Map<Role, String> colors = new EnumMap<>(Role.class);
        final JSONObject c = o.optJSONObject("colors", new JSONObject());
        for (final Role r : Role.values()) {
            final String v = c.optString(r.key(), null);
            if (isColor(v)) {
                colors.put(r, v);
            }
        }
        return new Theme(o.optString("id", "custom"), o.optString("name", "Imported theme"),
                o.optBoolean("dark", true), colors, o.optString("player_font", null));
    }
}
