package io.github.piper_yt.piperyt.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadsTest {
    @BeforeEach
    void open() throws Exception {
        TestLibrary.open();
    }

    @AfterEach
    void close() {
        Db.close();
    }

    @Test
    void keepsThePickedFileType() {
        final Video v = TestLibrary.video("a", "x");
        final long mp3 = Downloads.enqueue(v, "audio", "mp3", "off");
        assertEquals("mp3", Downloads.get(mp3).format());
        // The same video and type again is the same download
        assertEquals(mp3, Downloads.enqueue(v, "audio", "mp3", "off"));
        // Another file type is a separate download
        assertNotEquals(mp3, Downloads.enqueue(v, "audio", "m4a", "off"));
        // No type: use the one in Preferences
        final long dflt = Downloads.enqueue(v, "audio", null, "off");
        assertNull(Downloads.get(dflt).format());
        assertEquals(dflt, Downloads.enqueue(v, "audio", null, "off"));
    }
}
