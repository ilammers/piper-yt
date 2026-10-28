package io.github.piper_yt.piperyt.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpTest {
    private static YtDlp.Options options(final YtDlp.Preset preset, final String sponsorBlock) {
        return new YtDlp.Options(preset, "mkv", "vp9", "opus", sponsorBlock,
                List.of("sponsor", "selfpromo"), false, "en.*", Path.of("/tmp/dl"));
    }

    @Test
    void alwaysPrivate() throws Exception {
        for (final YtDlp.Preset p : YtDlp.Preset.values()) {
            final List<String> cmd = YtDlp.command("https://www.youtube.com/watch?v=x", options(p,
                    "mark"));
            assertTrue(cmd.containsAll(List.of("--ignore-config", "--no-cookies",
                    "--no-cache-dir", "--proxy")), cmd.toString());
            assertTrue(cmd.stream().noneMatch(a -> a.contains("cookies-from-browser")));
            // Through the local bridge, which takes the app's route and logs connections
            assertTrue(cmd.get(cmd.indexOf("--proxy") + 1).startsWith("http://127.0.0.1:"));
            assertTrue(cmd.containsAll(List.of("--embed-metadata", "--embed-thumbnail",
                    "--embed-chapters")));
            // The URL comes after "--", so a URL can't be read as an option
            assertEquals("--", cmd.get(cmd.size() - 2));
        }
    }

    @Test
    void presetsPickFormats() throws Exception {
        final List<String> p720 = YtDlp.command("u", options(YtDlp.Preset.P720, "off"));
        assertTrue(p720.contains("bv*[height<=720]+ba/b[height<=720]/b"));
        assertTrue(p720.contains("--merge-output-format"));
        final List<String> audio = YtDlp.command("u", options(YtDlp.Preset.AUDIO, "off"));
        assertTrue(audio.contains("-x"));
        assertFalse(audio.contains("--merge-output-format"));
        assertFalse(audio.contains("--audio-quality")); // opus is copied, not re-encoded
    }

    @Test
    void mp3IsBestQuality() throws Exception {
        final List<String> mp3 = YtDlp.command("u", new YtDlp.Options(YtDlp.Preset.AUDIO, "mkv",
                "vp9", "mp3", "off", List.of(), false, "en.*", Path.of("/tmp/dl")));
        assertEquals("mp3", mp3.get(mp3.indexOf("--audio-format") + 1));
        assertEquals("0", mp3.get(mp3.indexOf("--audio-quality") + 1));
    }

    @Test
    void sponsorBlockModes() throws Exception {
        final List<String> remove = YtDlp.command("u", options(YtDlp.Preset.BEST, "remove"));
        assertEquals("sponsor,selfpromo", remove.get(remove.indexOf("--sponsorblock-remove") + 1));
        final List<String> mark = YtDlp.command("u", options(YtDlp.Preset.BEST, "mark"));
        assertTrue(mark.contains("--sponsorblock-mark"));
        final List<String> off = YtDlp.command("u", options(YtDlp.Preset.BEST, "off"));
        assertTrue(off.contains("--no-sponsorblock"));
        assertTrue(off.stream().noneMatch(a -> a.startsWith("--sponsorblock-")));
    }

    @Test
    void progressAcrossVideoAndAudio() {
        final DownloadManager.ProgressParser p = new DownloadManager.ProgressParser();
        assertTrue(p.accept("[ppfmt] 137+251"));
        assertTrue(p.accept("[ppdl] 50 100 NA /d/a [x].f137.mp4"));
        assertEquals(0.25, p.fraction(), 1e-9);
        assertTrue(p.accept("[ppdl] 100 NA 200 /d/a [x].f251.webm"));
        assertEquals(0.75, p.fraction(), 1e-9);
        assertEquals("Audio", p.stage());
        assertFalse(p.accept("[download] something else"));
    }

    @Test
    void partialFiles() {
        assertTrue(DownloadManager.isPartial("A - B [abc].f137.mp4"));
        assertTrue(DownloadManager.isPartial("A - B [abc].mkv.part"));
        assertTrue(DownloadManager.isPartial("A - B [abc].f251.webm.part"));
        assertFalse(DownloadManager.isPartial("A - B [abc].mkv"));
        assertFalse(DownloadManager.isPartial("A - B [abc].opus"));
    }
}
