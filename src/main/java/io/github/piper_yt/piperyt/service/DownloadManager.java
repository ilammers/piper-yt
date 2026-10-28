package io.github.piper_yt.piperyt.service;

import io.github.piper_yt.piperyt.db.Downloads;
import io.github.piper_yt.piperyt.db.Downloads.Download;
import io.github.piper_yt.piperyt.db.Downloads.Status;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Runs the download queue: yt-dlp processes (through {@link YtDlp}, so the app's route and no
 * cookies), at most "concurrent downloads" at a time. The queue lives in the library, so it
 * survives a restart: downloads that were running start again (yt-dlp continues the partial
 * files), paused ones stay paused.
 */
public final class DownloadManager {
    /** Live progress of a running download, for the Downloads view. */
    public record Live(double fraction, String stage) {
    }

    private enum Stop { PAUSE, CANCEL }

    private static final class Running {
        final Process process;
        volatile Stop stop;

        Running(final Process process) {
            this.process = process;
        }
    }

    private static final Map<Long, Running> RUNNING = new ConcurrentHashMap<>();
    private static final Map<Long, Live> LIVE = new ConcurrentHashMap<>();
    private static final List<BiConsumer<Long, Live>> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean started;

    private DownloadManager() {
    }

    /** Resumes the queue from the library; call once at startup. */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Downloads.requeueInterrupted();
        // yt-dlp would outlive the app and race the restarted download for the same files
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                RUNNING.values().forEach(r -> r.process.destroy())));
        schedule();
    }

    public static boolean isAvailable() {
        for (final String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (Files.isExecutable(Path.of(dir, "yt-dlp"))) {
                return true;
            }
        }
        return false;
    }

    public static Runnable listen(final BiConsumer<Long, Live> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static Live live(final long id) {
        return LIVE.get(id);
    }

    /** Queues one video with the quality and file type in {@code choice}. */
    public static long enqueue(final Video video, final YtDlp.Choice choice) {
        final long id = Downloads.enqueue(video, choice.preset().id, choice.format(),
                Settings.getString(Settings.DOWNLOAD_SPONSORBLOCK));
        schedule();
        return id;
    }

    public static int enqueueAll(final List<Video> videos, final YtDlp.Choice choice) {
        int n = 0;
        for (final Video v : videos) {
            if (v.serviceId() == Video.YOUTUBE && !v.isLive()) {
                Downloads.enqueue(v, choice.preset().id, choice.format(),
                        Settings.getString(Settings.DOWNLOAD_SPONSORBLOCK));
                n++;
            }
        }
        schedule();
        return n;
    }

    /** Fetches a whole YouTube playlist (paced) and queues its videos. Blocking. */
    public static int enqueueRemotePlaylist(final String url, final YtDlp.Choice choice)
            throws Exception {
        return enqueueAll(RemotePlaylist.fetch(url).videos(), choice);
    }

    public static void pause(final long id) {
        final Running r = RUNNING.get(id);
        if (r != null) {
            r.stop = Stop.PAUSE;
            r.process.destroy();
        } else {
            Downloads.setStatus(id, Status.PAUSED, null);
        }
    }

    public static void resume(final long id) {
        Downloads.setStatus(id, Status.QUEUED, null);
        schedule();
    }

    public static void retry(final long id) {
        resume(id);
    }

    /** Stops a download and deletes its partial files and its entry. */
    public static void cancel(final long id) {
        final Running r = RUNNING.get(id);
        if (r != null) {
            r.stop = Stop.CANCEL;
            r.process.destroy();
            return;
        }
        final Download d = Downloads.get(id);
        if (d != null) {
            deletePartials(d);
            Downloads.delete(id);
        }
    }

    /** Deletes a finished download's file and its entry. */
    public static void deleteFile(final long id) throws IOException {
        final Download d = Downloads.get(id);
        if (d == null) {
            return;
        }
        if (d.filePath() != null) {
            Files.deleteIfExists(Path.of(d.filePath()));
        }
        Downloads.delete(id);
    }

    /** Starts queued downloads while there's room. */
    static synchronized void schedule() {
        if (!started) {
            return;
        }
        final int limit = Math.max(1, Settings.getInt(Settings.DOWNLOAD_CONCURRENCY));
        while (RUNNING.size() < limit) {
            final Download next = Downloads.nextQueued();
            if (next == null) {
                return;
            }
            launch(next);
        }
    }

    private static void launch(final Download d) {
        final Path dir = Settings.downloadDir();
        // The file type picked for this download, else the one in Preferences
        final boolean audio = d.isAudioOnly();
        final String container = !audio && d.format() != null ? d.format()
                : Settings.getString(Settings.DOWNLOAD_CONTAINER);
        final String audioFormat = audio && d.format() != null ? d.format()
                : Settings.getString(Settings.DOWNLOAD_AUDIO_FORMAT);
        final YtDlp.Options options = new YtDlp.Options(YtDlp.Preset.of(d.preset()),
                container, Settings.getString(Settings.DOWNLOAD_CODEC), audioFormat,
                d.sponsorBlock(),
                Settings.sponsorBlockCategories(), Settings.get(Settings.DOWNLOAD_SUBTITLES),
                Settings.getString(Settings.DOWNLOAD_SUB_LANGS), dir);
        final Process process;
        try {
            Files.createDirectories(dir);
            process = new ProcessBuilder(YtDlp.command(d.video().url(), options))
                    .redirectErrorStream(true)
                    .start();
        } catch (final IOException e) {
            Downloads.setStatus(d.id(), Status.FAILED, isAvailable() ? e.getMessage()
                    : "yt-dlp is not installed. Install it with: sudo dnf install yt-dlp");
            return;
        }
        final Running running = new Running(process);
        RUNNING.put(d.id(), running);
        Downloads.setStatus(d.id(), Status.RUNNING, null);
        update(d.id(), new Live(d.progress(), "Starting"));
        Thread.ofVirtual().name("yt-dlp-" + d.id()).start(() -> watch(d, running, dir));
    }

    /** Follows one yt-dlp run to its end. */
    private static void watch(final Download d, final Running running, final Path dir) {
        final ProgressParser parser = new ProgressParser();
        String file = null;
        String lastError = null;
        long lastUpdate = 0;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(
                running.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith(YtDlp.FILE + " ")) {
                    file = line.substring(YtDlp.FILE.length() + 1).strip();
                } else if (line.startsWith("ERROR:")) {
                    lastError = line.substring("ERROR:".length()).strip();
                } else if (parser.accept(line)) {
                    final long now = System.nanoTime();
                    if (now - lastUpdate > 250_000_000L) {
                        lastUpdate = now;
                        update(d.id(), new Live(parser.fraction(), parser.stage()));
                        Downloads.setProgress(d.id(), parser.fraction());
                    }
                }
            }
            running.process.waitFor();
        } catch (final IOException | InterruptedException e) {
            lastError = e.getMessage();
        } finally {
            RUNNING.remove(d.id());
            LIVE.remove(d.id());
        }

        if (running.stop == Stop.CANCEL) {
            deletePartials(d);
            Downloads.delete(d.id());
        } else if (running.stop == Stop.PAUSE) {
            Downloads.setStatus(d.id(), Status.PAUSED, null);
        } else {
            final Path result = file != null && Files.isRegularFile(Path.of(file))
                    ? Path.of(file) : findFinished(d, dir);
            if (running.process.exitValue() == 0 && result != null) {
                long size = 0;
                try {
                    size = Files.size(result);
                } catch (final IOException ignored) {
                    // Size is only shown in the library
                }
                Downloads.finish(d.id(), result, size);
            } else {
                Downloads.setStatus(d.id(), Status.FAILED, lastError != null ? lastError
                        : "yt-dlp exited with status " + running.process.exitValue());
            }
        }
        update(d.id(), null);
        schedule();
    }

    private static void update(final long id, final Live live) {
        if (live != null) {
            LIVE.put(id, live);
        }
        for (final BiConsumer<Long, Live> l : LISTENERS) {
            l.accept(id, live);
        }
    }

    /** The finished file of {@code d} in {@code dir}, found by its video ID, or null. */
    private static Path findFinished(final Download d, final Path dir) {
        final String id = Video.youtubeId(d.video().url());
        if (id == null) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                final String name = p.getFileName().toString();
                return name.contains("[" + id + "].") && !isPartial(name);
            }).findFirst().orElse(null);
        } catch (final IOException e) {
            return null;
        }
    }

    /** Deletes yt-dlp's leftovers for {@code d}: .part, .ytdl, per-format and temp files. */
    private static void deletePartials(final Download d) {
        final String id = Video.youtubeId(d.video().url());
        if (id == null) {
            return;
        }
        try (Stream<Path> files = Files.list(Settings.downloadDir())) {
            files.filter(p -> {
                final String name = p.getFileName().toString();
                return name.contains("[" + id + "]") && isPartial(name);
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException e) {
                    System.err.println("Couldn't delete " + p + ": " + e.getMessage());
                }
            });
        } catch (final IOException ignored) {
            // No download folder yet: nothing to clean
        }
    }

    static boolean isPartial(final String name) {
        return name.endsWith(".part") || name.endsWith(".ytdl") || name.contains(".part-Frag")
                || name.contains(".temp.") || name.matches(".*\\.f\\d+(-\\d+)?\\.\\w+(\\.part)?$");
    }

    /** Turns yt-dlp progress lines into one fraction across its video and audio downloads. */
    static final class ProgressParser {
        private int stages = 1;
        private final List<String> files = new ArrayList<>();
        private double fraction;
        private String stage = "Starting";

        /** True if {@code line} was one of ours. */
        boolean accept(final String line) {
            if (line.startsWith(YtDlp.FORMAT + " ")) {
                final String formats = line.substring(YtDlp.FORMAT.length() + 1).strip();
                stages = Math.max(1, formats.split("\\+").length);
                return true;
            }
            if (!line.startsWith(YtDlp.PROGRESS + " ")) {
                return false;
            }
            final String[] parts = line.substring(YtDlp.PROGRESS.length() + 1).split(" ", 4);
            if (parts.length < 4) {
                return false;
            }
            final double done = number(parts[0]);
            double total = number(parts[1]);
            if (total <= 0) {
                total = number(parts[2]);
            }
            final String file = parts[3];
            if (!files.contains(file)) {
                files.add(file);
            }
            final int index = Math.min(files.indexOf(file), stages - 1);
            final double part = total > 0 ? Math.min(1, done / total) : 0;
            fraction = Math.min(0.99, (index + part) / stages);
            stage = part >= 1 && index == stages - 1 ? "Processing"
                    : stages > 1 ? (index == 0 ? "Video" : "Audio") : "Downloading";
            return true;
        }

        double fraction() {
            return fraction;
        }

        String stage() {
            return stage;
        }

        private static double number(final String s) {
            try {
                return Double.parseDouble(s);
            } catch (final NumberFormatException e) {
                return -1;
            }
        }
    }
}
