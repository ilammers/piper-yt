package io.github.piper_yt.piperyt.player;

import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.YtDlp;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fetches YouTube's captions for playback. The caption URLs the extractor finds come back
 * empty without a PO token, so yt-dlp downloads them, in the background while the video
 * plays. Only the languages asked for are fetched, and nothing at all while that's blank.
 */
public final class Subtitles {
    /**
     * A track for mpv's {@code sub-add}: a local file, its menu title and language.
     * {@code select} shows it straight away.
     */
    public record Track(String location, String title, String lang, boolean select) {
    }

    /** yt-dlp names each file {@code sub.<lang>.<ext>}. */
    private static final String FILE_PREFIX = "sub.";
    private static final long TIMEOUT_SECONDS = 90;

    private Subtitles() {
    }

    /**
     * Fetches the tracks in {@code languages} (e.g. "en, de"; blank: none) that the video has,
     * in that order; the first is selected. Completes with an empty list when there are none
     * or yt-dlp fails. Cancelling it stops yt-dlp.
     */
    public static CompletableFuture<List<Track>> fetch(final StreamInfo info,
                                                       final String languages) {
        // Language tag -> whether YouTube only has auto-generated captions for it
        final Map<String, Boolean> wanted = available(info, parseLanguages(languages));
        if (wanted.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (!DownloadManager.isAvailable()) {
            System.err.println("Subtitles need yt-dlp: sudo dnf install yt-dlp");
            return CompletableFuture.completedFuture(List.of());
        }
        final CompletableFuture<List<Track>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("yt-dlp-subs").start(() -> {
            try {
                result.complete(download(info.getUrl(), wanted, result));
            } catch (final IOException | RuntimeException e) {
                System.err.println("Subtitles: " + e.getMessage());
                result.complete(List.of());
            } catch (final InterruptedException e) {
                result.complete(List.of());
            }
        });
        return result;
    }

    private static List<Track> download(final String url, final Map<String, Boolean> wanted,
                                        final CompletableFuture<?> result)
            throws IOException, InterruptedException {
        final Path dir = Files.createTempDirectory("piper-subs-");
        dir.toFile().deleteOnExit();
        final Process process = new ProcessBuilder(YtDlp.subtitlesCommand(url,
                List.copyOf(wanted.keySet()), dir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        result.whenComplete((tracks, error) -> {
            if (result.isCancelled()) {
                process.destroy();
            }
        });
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("yt-dlp took too long to fetch subtitles");
        }
        if (result.isCancelled()) {
            return List.of();
        }

        final Map<String, Path> files = new LinkedHashMap<>();
        try (var listing = Files.list(dir)) {
            listing.forEach(file -> {
                file.toFile().deleteOnExit();
                final String lang = langOf(file.getFileName().toString());
                if (lang != null) {
                    files.putIfAbsent(lang, file);
                }
            });
        }
        final List<Track> tracks = new ArrayList<>();
        wanted.forEach((lang, autoOnly) -> {
            final Path file = files.get(lang);
            if (file != null) {
                tracks.add(new Track(file.toString(),
                        displayName(lang) + (autoOnly ? " (auto-generated)" : ""), lang,
                        tracks.isEmpty()));
            }
        });
        if (tracks.isEmpty() && process.exitValue() != 0) {
            throw new IOException("yt-dlp couldn't fetch subtitles (exit "
                    + process.exitValue() + ")");
        }
        return tracks;
    }

    /** "sub.en.vtt" -> "en", "sub.pt-BR.vtt" -> "pt-BR"; null for anything else. */
    static String langOf(final String fileName) {
        if (!fileName.startsWith(FILE_PREFIX)) {
            return null;
        }
        final int dot = fileName.lastIndexOf('.');
        return dot <= FILE_PREFIX.length() ? null : fileName.substring(FILE_PREFIX.length(), dot);
    }

    static List<String> parseLanguages(final String languages) {
        if (languages == null) {
            return List.of();
        }
        return Arrays.stream(languages.toLowerCase(Locale.ROOT).split("[,\\s]+"))
                // Accept yt-dlp's "en.*" too, as used for downloads
                .map(l -> l.replaceAll("[.*]+$", ""))
                .filter(l -> !l.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * The video's caption languages that match {@code wanted} ("en" matches "en-GB"), in the
     * order asked for, each mapped to whether only auto-generated captions exist for it.
     */
    static Map<String, Boolean> available(final StreamInfo info, final List<String> wanted) {
        final Map<String, Boolean> result = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        for (final String w : wanted) {
            for (final SubtitlesStream s : info.getSubtitles()) {
                final String tag = s.getLanguageTag();
                if (tag == null || !matches(w, tag)) {
                    continue;
                }
                final boolean autoOnly = s.isAutoGenerated();
                result.merge(tag, autoOnly, Boolean::logicalAnd);
            }
        }
        return result;
    }

    private static boolean matches(final String wanted, final String tag) {
        final String code = tag.toLowerCase(Locale.ROOT).replace('_', '-');
        return code.equals(wanted) || code.startsWith(wanted + "-");
    }

    private static String displayName(final String tag) {
        final String name = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }
}
