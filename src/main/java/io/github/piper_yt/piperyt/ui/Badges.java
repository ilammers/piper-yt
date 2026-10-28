package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Downloads;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Video;

import java.util.Map;
import java.util.Set;

/**
 * Which videos are downloaded and how far each was watched, cached so cards don't query the
 * library one by one.
 */
final class Badges {
    private static Set<String> downloaded;
    private static Map<String, Double> progress;
    private static int progressVersion = -1;

    private Badges() {
    }

    static boolean isDownloaded(final String url) {
        if (downloaded == null) {
            downloaded = Downloads.downloadedUrls();
            Library.listen(Library.Topic.DOWNLOADS, () -> downloaded = Downloads.downloadedUrls());
            // Imports and wipes write history without going through History's counter
            Library.listen(Library.Topic.HISTORY, () -> progressVersion = -1);
        }
        return url != null && downloaded.contains(Video.canonicalUrl(url));
    }

    /** How far {@code url} was watched last time (up to 1 for the end), or 0. */
    static double progress(final String url) {
        if (url == null) {
            return 0;
        }
        if (progressVersion != History.version() || progress == null) {
            progressVersion = History.version();
            progress = History.progressFractions();
        }
        return progress.getOrDefault(Video.canonicalUrl(url), 0.0);
    }

    /** Watched to (nearly) the end. */
    static boolean isWatched(final String url) {
        return progress(url) >= 1.0;
    }
}
