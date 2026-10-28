package io.github.piper_yt.piperyt.db;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opens a fresh library in a temp directory for each test. */
final class TestLibrary {
    private TestLibrary() {
    }

    static Path open() throws Exception {
        final Path dir = Files.createTempDirectory("piper-test-");
        Db.open(dir.resolve("library.db"));
        return dir;
    }

    static Video video(final String id, final String channel) {
        return new Video(0, "https://www.youtube.com/watch?v=" + id, "Video " + id,
                "Channel " + channel, "https://www.youtube.com/channel/UC" + channel,
                "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", 600, "VIDEO_STREAM", false,
                System.currentTimeMillis() - 3600_000L, 1000);
    }
}
