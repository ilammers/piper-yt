package io.github.piper_yt.piperyt.net;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorTest {
    @Test
    void membersOnlyVideosAreNotWatchable() {
        final StreamInfoItem open = new StreamInfoItem(0,
                "https://www.youtube.com/watch?v=aaaaaaaaaaa", "Open", StreamType.VIDEO_STREAM);
        final StreamInfoItem members = new StreamInfoItem(0,
                "https://www.youtube.com/watch?v=bbbbbbbbbbb", "Members", StreamType.VIDEO_STREAM);
        members.setRequiresMembership(true);
        assertTrue(Extractor.isWatchable(open));
        assertFalse(Extractor.isWatchable(members));
        // Playlists and channels aren't filtered
        assertTrue(Extractor.isWatchable(new PlaylistInfoItem(0,
                "https://www.youtube.com/playlist?list=PL1", "List")));
    }
}
