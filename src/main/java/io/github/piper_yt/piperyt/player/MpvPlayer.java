package io.github.piper_yt.piperyt.player;

import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.net.Network;
import io.github.piper_yt.piperyt.util.Theme;
import io.github.piper_yt.piperyt.util.Themes;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockAction;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockCategory;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockSegment;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Picks streams for playback and, when libmpv isn't available, plays them in an external mpv
 * window. YouTube serves most qualities as separate video and audio streams; mpv merges them
 * with {@code --audio-file}.
 */
public final class MpvPlayer {
    /**
     * FFmpeg options for mpv's HTTP streams ({@code stream-lavf-o}). Through an HTTP proxy
     * (our {@code ProxyBridge}) FFmpeg 8.1 seeks badly, so resuming, seeking and SponsorBlock
     * skips hung:
     * <ul>
     *   <li>it decides between reading forward and reconnecting from the socket's receive
     *   buffer, which is huge on the loopback connection to the bridge, so it read forward
     *   through megabytes that YouTube throttles to playback speed. {@code short_seek_size=1}
     *   makes it reconnect, as it does without a proxy;</li>
     *   <li>it reuses connections, and reports one the server closed as an I/O error instead of
     *   end-of-stream, so it never reconnected. {@code reconnect_on_network_error} retries on a
     *   fresh connection, and {@code multiple_requests} asks for keep-alive so a reused
     *   connection is answered;</li>
     *   <li>{@code rw_timeout} gives up on a connection silent for 15 s (mpv reconnects)
     *   instead of waiting forever.</li>
     * </ul>
     */
    public static final String STREAM_OPTIONS = "short_seek_size=1,reconnect_on_network_error=1,"
            + "multiple_requests=1,rw_timeout=15000000";

    private static List<String> command;

    private MpvPlayer() {
    }

    /** A selectable video quality. {@code stream} is null for "let mpv choose" live streams. */
    public record Quality(String label, VideoStream stream) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Returns the mpv command prefix, or null when mpv is not installed. */
    public static synchronized List<String> findMpv() {
        if (command != null) {
            return command.isEmpty() ? null : command;
        }
        command = List.of();
        for (final String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (new File(dir, "mpv").canExecute()) {
                command = List.of(new File(dir, "mpv").getPath());
                return command;
            }
        }
        try {
            final Process p = new ProcessBuilder("flatpak", "info", "io.mpv.Mpv")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                command = List.of("flatpak", "run", "io.mpv.Mpv");
            }
        } catch (final IOException | InterruptedException ignored) {
            // flatpak not installed
        }
        return command.isEmpty() ? null : command;
    }

    public static boolean isLive(final StreamInfo info) {
        return isLiveType(info.getStreamType());
    }

    public static boolean isLiveType(final StreamType type) {
        return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
    }

    /** Lists distinct qualities, best first. */
    public static List<Quality> qualities(final StreamInfo info) {
        if (isLive(info) && hasText(info.getHlsUrl())) {
            return List.of(new Quality("Auto (live)", null));
        }

        final List<VideoStream> all = new ArrayList<>();
        all.addAll(info.getVideoOnlyStreams());
        all.addAll(info.getVideoStreams());

        // One entry per height+fps, keeping the codec with the best decoder support
        final Map<String, VideoStream> best = new LinkedHashMap<>();
        all.stream()
                .filter(MpvPlayer::isPlayable)
                .sorted(Comparator.comparingInt(VideoStream::getHeight)
                        .thenComparingInt(VideoStream::getFps).reversed()
                        .thenComparingInt(s -> codecRank(s.getCodec())))
                .forEach(s -> best.putIfAbsent(s.getHeight() + "@" + s.getFps(), s));

        final List<Quality> result = new ArrayList<>();
        for (final VideoStream s : best.values()) {
            final String res = s.getHeight() > 0
                    ? s.getHeight() + "p" + (s.getFps() > 30 ? s.getFps() : "")
                    : s.getResolution();
            final String codec = s.getCodec() == null ? ""
                    : " · " + s.getCodec().split("\\.")[0].toUpperCase(Locale.ROOT);
            result.add(new Quality(res + codec, s));
        }
        return result;
    }

    /** Index of the best quality at or below 1080p. */
    public static int defaultQualityIndex(final List<Quality> qualities) {
        for (int i = 0; i < qualities.size(); i++) {
            final VideoStream s = qualities.get(i).stream();
            if (s == null || s.getHeight() <= 1080) {
                return i;
            }
        }
        return 0;
    }

    public static AudioStream bestAudio(final StreamInfo info) {
        final List<AudioStream> playable = info.getAudioStreams().stream()
                .filter(MpvPlayer::isPlayable)
                .toList();
        // Prefer the original track over auto-dubbed ones
        final List<AudioStream> original = playable.stream()
                .filter(a -> a.getAudioTrackName() != null
                        && a.getAudioTrackName().toLowerCase(Locale.ROOT).contains("original"))
                .toList();
        final List<AudioStream> pool = original.isEmpty() ? playable : original;
        return pool.stream()
                .max(Comparator.comparingInt(a -> Math.max(a.getAverageBitrate(), a.getBitrate())))
                .orElse(null);
    }

    /**
     * What to play: {@code url} plus, for YouTube's split streams, a separate audio track.
     * {@code coverUrl} is shown as the picture for audio-only playback. {@code subtitles} are
     * added once the file has loaded.
     */
    public record Source(String title, String url, String audioUrl, String coverUrl,
                         boolean noVideo, List<Subtitles.Track> subtitles) {
        public Source(final String title, final String url, final String audioUrl,
                      final String coverUrl, final boolean noVideo) {
            this(title, url, audioUrl, coverUrl, noVideo, List.of());
        }
    }

    /** A SponsorBlock segment to skip, in seconds. */
    public record SkipSegment(double start, double end, String label) {
    }

    public static Source source(final StreamInfo info, final Quality quality,
                                final boolean audioOnly) throws IOException {
        final String title = info.getName();
        if (isLive(info) && hasText(info.getHlsUrl())) {
            return new Source(title, info.getHlsUrl(), null, null, audioOnly);
        }
        if (audioOnly) {
            final AudioStream audio = bestAudio(info);
            if (audio == null) {
                throw new IOException("No playable audio stream");
            }
            return new Source(title, audio.getContent(), null, info.getThumbnailUrl(), false);
        }
        if (quality == null || quality.stream() == null) {
            throw new IOException("No playable video stream");
        }
        final VideoStream video = quality.stream();
        final AudioStream audio = video.isVideoOnly() ? bestAudio(info) : null;
        return new Source(title, video.getContent(),
                audio == null ? null : audio.getContent(), null, false,
                Subtitles.tracks(info, Settings.getString(Settings.PLAYER_SUB_LANGS)));
    }

    public static List<SkipSegment> skipSegments(final StreamInfo info) {
        final List<SkipSegment> result = new ArrayList<>();
        for (final SponsorBlockSegment s : info.getSponsorBlockSegments()) {
            if (s.action == SponsorBlockAction.SKIP && s.endTime > s.startTime) {
                result.add(new SkipSegment(s.startTime / 1000, s.endTime / 1000,
                        categoryLabel(s.category)));
            }
        }
        return result;
    }

    /** Where external mpv got to, as it reports it. */
    public interface Progress {
        void update(double position, double duration);
    }

    /** Marks external mpv's progress lines on its standard output. */
    private static final String PROGRESS_MARK = "[pppos] ";

    /**
     * Plays in a separate mpv window, for when libmpv isn't installed. {@code progress} hears
     * the position every few seconds, on pause and on quit, on a background thread.
     */
    public static void playExternal(final Source source, final List<SkipSegment> skip,
                                    final double startSeconds, final Progress progress)
            throws IOException {
        final List<String> mpv = findMpv();
        if (mpv == null) {
            throw new IOException("mpv is not installed");
        }

        final List<String> cmd = new ArrayList<>(mpv);
        cmd.add("--force-media-title=" + source.title());
        cmd.add("--ytdl=no");
        // Like embedded mpv: no user config or scripts, which could fetch around the proxy
        cmd.add("--no-config");
        cmd.add("--http-proxy=" + Network.mpvProxyUrl());
        cmd.add("--stream-lavf-o=" + STREAM_OPTIONS);
        final Theme theme = Themes.current();
        theme.mpvOptions().forEach((key, value) -> cmd.add("--" + key + "=" + value));
        cmd.add("--script-opts=" + theme.oscScriptOpts());
        if (source.noVideo()) {
            cmd.add("--no-video");
        }
        if (source.noVideo() || source.coverUrl() != null) {
            cmd.add("--force-window=immediate");
        }
        if (source.audioUrl() != null) {
            cmd.add("--audio-file=" + source.audioUrl());
        }
        if (startSeconds > 0) {
            cmd.add(String.format(Locale.ROOT, "--start=%.1f", startSeconds));
        }
        cmd.add("--script=" + externalScript(skip, source.subtitles()));
        cmd.add(source.url());

        final Process process = new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        Thread.ofVirtual().name("mpv-progress").start(() -> readProgress(process, progress));
    }

    /** Reads the script's progress lines until mpv exits; mpv's own output is dropped. */
    private static void readProgress(final Process process, final Progress progress) {
        try (var lines = process.inputReader().lines()) {
            // mpv's status line has no newline, so a mark can follow it on the same line
            lines.filter(line -> line.contains(PROGRESS_MARK)).forEach(line -> {
                final String[] parts = line.substring(line.lastIndexOf(PROGRESS_MARK)
                        + PROGRESS_MARK.length()).strip().split(" ");
                try {
                    progress.update(Double.parseDouble(parts[0]),
                            parts.length > 1 ? Double.parseDouble(parts[1]) : 0);
                } catch (final NumberFormatException ignored) {
                    // A cut-off line; the next one will do
                }
            });
        } catch (final RuntimeException e) {
            System.err.println("External mpv progress: " + e.getMessage());
        }
    }

    /**
     * Writes a throwaway Lua script for external mpv: skips the segments, and prints the
     * position for {@link #readProgress} every few seconds, on pause and on quit.
     */
    private static String externalScript(final List<SkipSegment> skip,
                                         final List<Subtitles.Track> subtitles)
            throws IOException {
        final StringBuilder lua = new StringBuilder("local segments = {\n");
        for (final SkipSegment s : skip) {
            lua.append(String.format(Locale.ROOT, "  {%.3f, %.3f, \"%s\"},%n",
                    s.start(), s.end(), s.label()));
        }
        lua.append("}\nlocal subtitles = {\n");
        for (final Subtitles.Track t : subtitles) {
            lua.append("  {").append(luaString(t.location())).append(", ")
                    .append(luaString(t.select() ? "select" : "auto")).append(", ")
                    .append(luaString(t.title())).append(", ")
                    .append(luaString(t.lang() == null ? "" : t.lang())).append("},\n");
        }
        lua.append("""
                }
                mp.register_event("file-loaded", function()
                    for _, s in ipairs(subtitles) do
                        mp.commandv("sub-add", s[1], s[2], s[3], s[4])
                    end
                end)
                mp.observe_property("time-pos", "number", function(_, t)
                    if not t then return end
                    for _, s in ipairs(segments) do
                        if t >= s[1] and t < s[2] - 0.3 then
                            mp.set_property_number("time-pos", s[2])
                            mp.osd_message("Skipped " .. s[3])
                            return
                        end
                    end
                end)
                local last = nil
                local function report()
                    local t = mp.get_property_number("time-pos")
                    if not t or t == last then return end
                    last = t
                    local d = mp.get_property_number("duration") or 0
                    io.stdout:write(string.format("@MARK@%.1f %.1f\\n", t, d))
                    io.stdout:flush()
                end
                mp.add_periodic_timer(5, report)
                mp.observe_property("pause", "bool", function(_, paused)
                    if paused then report() end
                end)
                mp.register_event("end-file", report)
                mp.register_event("shutdown", report)
                """.replace("@MARK@", PROGRESS_MARK));
        final Path script = Files.createTempFile("piper-mpv-", ".lua");
        script.toFile().deleteOnExit();
        Files.writeString(script, lua);
        return script.toString();
    }

    private static String luaString(final String s) {
        final StringBuilder out = new StringBuilder("\"");
        for (final char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String categoryLabel(final SponsorBlockCategory category) {
        return switch (category) {
            case SPONSOR -> "sponsor";
            case SELF_PROMO -> "self-promotion";
            case INTERACTION -> "interaction reminder";
            case INTRO -> "intro";
            case OUTRO -> "outro";
            case PREVIEW -> "preview";
            case FILLER -> "filler";
            case NON_MUSIC -> "non-music section";
            default -> "segment";
        };
    }

    private static boolean isPlayable(final Stream s) {
        return s.isUrl() && hasText(s.getContent())
                && (s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                || s.getDeliveryMethod() == DeliveryMethod.HLS
                || s.getDeliveryMethod() == DeliveryMethod.DASH);
    }

    private static int codecRank(final String codec) {
        if (codec == null) {
            return 9;
        }
        if (codec.startsWith("vp9") || codec.startsWith("vp09")) {
            return 0;
        }
        if (codec.startsWith("avc")) {
            return 1;
        }
        if (codec.startsWith("av01")) {
            return 2;
        }
        return 3;
    }

    private static boolean hasText(final String s) {
        return s != null && !s.isBlank();
    }
}
