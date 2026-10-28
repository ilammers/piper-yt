package io.github.piper_yt.piperyt.player;

import io.github.piper_yt.piperyt.service.YtDlp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitlesTest {
    @Test
    void parsesLanguageLists() {
        assertEquals(List.of("en", "de"), Subtitles.parseLanguages(" en.*, de  EN "));
        assertEquals(List.of(), Subtitles.parseLanguages("  "));
        assertEquals(List.of(), Subtitles.parseLanguages(null));
    }

    @Test
    void readsLanguageFromFileName() {
        assertEquals("en", Subtitles.langOf("sub.en.vtt"));
        assertEquals("pt-BR", Subtitles.langOf("sub.pt-BR.vtt"));
        assertNull(Subtitles.langOf("sub.vtt"));
        assertNull(Subtitles.langOf("other.en.vtt"));
    }

    @Test
    void fetchesOnlySubtitlesThroughTheAppRoute() throws Exception {
        final List<String> cmd = YtDlp.subtitlesCommand("https://www.youtube.com/watch?v=x",
                List.of("en", "de"), Path.of("/tmp/subs"));
        assertTrue(cmd.contains("--skip-download"));
        assertTrue(cmd.contains("--no-cookies"));
        assertTrue(cmd.contains("--proxy"));
        assertEquals("en,de", cmd.get(cmd.indexOf("--sub-langs") + 1));
        assertEquals("https://www.youtube.com/watch?v=x", cmd.getLast());
    }
}
