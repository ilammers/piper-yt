package io.github.piper_yt.piperyt.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtitlesTest {
    @Test
    void convertsYoutubeTtmlToVtt() {
        final String ttml = """
                <?xml version="1.0" encoding="utf-8" ?><tt xml:lang="en"><body><div>
                <p begin="00:00:01.200" end="00:00:03.000" style="s2">Hello &amp; <span>welcome</span></p>
                <p begin="00:00:03.000" end="00:01:04.5">it&#39;s a<br />test</p>
                <p begin="00:00:05.000" end="00:00:05.000">empty duration</p>
                </div></body></tt>""";
        assertEquals("""
                WEBVTT

                00:00:01.200 --> 00:00:03.000
                Hello &amp; welcome

                00:00:03.000 --> 00:01:04.500
                it's a
                test

                """, Subtitles.ttmlToVtt(ttml));
    }

    @Test
    void parsesLanguageLists() {
        assertEquals(List.of("en", "de"), Subtitles.parseLanguages(" en.*, de  EN "));
        assertEquals(List.of(), Subtitles.parseLanguages("  "));
        assertEquals(List.of(), Subtitles.parseLanguages(null));
    }
}
