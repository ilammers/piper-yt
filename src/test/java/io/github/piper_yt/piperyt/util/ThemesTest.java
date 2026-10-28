package io.github.piper_yt.piperyt.util;

import io.github.piper_yt.piperyt.db.Db;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemesTest {
    @BeforeEach
    void open() throws Exception {
        Db.open(Files.createTempDirectory("piper-theme-").resolve("library.db"));
    }

    @AfterEach
    void close() {
        Db.close();
    }

    @Test
    void emberMutedIsTheDefaultAndUnchanged() {
        final Theme t = Themes.current();
        assertEquals("ember-muted", t.id());
        assertTrue(t.dark());
        assertEquals("#0e1515", t.color(Theme.Role.BG));
        assertEquals("#c27861", t.color(Theme.Role.ACCENT));
        assertTrue(t.oscScriptOpts().contains("osc-timecode_color=#c27861"));
    }

    @Test
    void builtInsAreCompleteAndGenerateCss() {
        assertEquals(List.of("Ember Muted", "Gruvbox Dark", "Gruvbox Light"),
                Theme.BUILT_IN.stream().map(Theme::name).toList());
        assertFalse(Theme.GRUVBOX_LIGHT.dark());
        for (final Theme t : Theme.BUILT_IN) {
            assertEquals(Theme.Role.values().length, t.colors().size());
            final String css = t.css();
            assertFalse(css.contains("@"), t.name() + " left a placeholder: " + css);
            assertTrue(css.contains("--view-bg-color: " + t.color(Theme.Role.BG)));
        }
    }

    @Test
    void customThemeLifecycle() {
        final AtomicReference<Theme> heard = new AtomicReference<>();
        Themes.listen(heard::set);

        final Theme copy = Themes.duplicate(Theme.GRUVBOX_DARK);
        assertEquals("Gruvbox Dark copy", copy.name());
        assertEquals(copy.id(), Themes.current().id());
        assertFalse(copy.isBuiltIn());

        Themes.update(copy.withColor(Theme.Role.ACCENT, "#123456").withName("Mine"));
        assertEquals("#123456", Themes.current().color(Theme.Role.ACCENT));
        assertEquals("Mine", Themes.current().name());
        assertEquals("#123456", heard.get().color(Theme.Role.ACCENT));

        // Built-ins can't be edited
        Themes.update(Theme.EMBER_MUTED.withColor(Theme.Role.BG, "#ffffff"));
        assertEquals("#0e1515", Themes.byId("ember-muted").color(Theme.Role.BG));

        // Deleting the theme in use falls back to Ember Muted
        Themes.delete(copy.id());
        assertNull(Themes.byId(copy.id()));
        assertEquals("ember-muted", Themes.current().id());
    }

    @Test
    void jsonRoundTripAndBadInput() {
        final Theme t = Theme.GRUVBOX_LIGHT.withId("x").withName("Light-ish")
                .withColor(Theme.Role.RED, "#ABCDEF").withFont("Iosevka");
        final Theme back = Theme.fromJson(new JSONObject(t.toJson().toString()));
        assertEquals(t, back);
        assertEquals("#abcdef", back.color(Theme.Role.RED));

        // Missing or invalid colors fall back to a built-in of the same kind
        final Theme partial = Theme.fromJson(new JSONObject()
                .put("name", "Half").put("dark", false)
                .put("colors", new JSONObject().put("bg", "#000000").put("fg", "red")));
        assertEquals("#000000", partial.color(Theme.Role.BG));
        assertEquals(Theme.GRUVBOX_LIGHT.color(Theme.Role.FG), partial.color(Theme.Role.FG));

        // Importing never overwrites: same name gets a suffix
        final Theme a = Themes.add(partial);
        final Theme b = Themes.add(partial);
        assertEquals("Half", a.name());
        assertEquals("Half 2", b.name());
    }
}
