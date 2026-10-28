package io.github.piper_yt.piperyt.service;

import io.github.piper_yt.piperyt.net.Network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds yt-dlp command lines. Every run starts with {@link Network#ytDlpArgs()}: the app's
 * route, no cookies, no cache, no config files. Never {@code --cookies-from-browser}.
 */
public final class YtDlp {
    /** Marks our progress lines in yt-dlp's output. */
    static final String PROGRESS = "[ppdl]";
    static final String FORMAT = "[ppfmt]";
    static final String FILE = "[ppfile]";
    /** SponsorBlock's API, the same one playback uses. */
    static final String SPONSORBLOCK_API = "https://sponsor.ajay.app";

    /** Quality presets. */
    public enum Preset {
        BEST("best", "Best"), P1080("1080p", "1080p"), P720("720p", "720p"),
        AUDIO("audio", "Audio only");

        public final String id;
        public final String label;

        Preset(final String id, final String label) {
            this.id = id;
            this.label = label;
        }

        public static Preset of(final String id) {
            for (final Preset p : values()) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
            return P1080;
        }
    }

    /** A file type the user can pick: yt-dlp's name for it and what the UI calls it. */
    public record FileType(String id, String label) {
    }

    /** Video containers, for {@code --merge-output-format}. */
    public static final List<FileType> VIDEO_TYPES = List.of(
            new FileType("mkv", "MKV (fits any codec)"), new FileType("mp4", "MP4"),
            new FileType("webm", "WebM"), new FileType("any", "Whatever yt-dlp picks"));

    /** Audio-only formats, for {@code --audio-format}. */
    public static final List<FileType> AUDIO_TYPES = List.of(
            new FileType("mp3", "MP3"), new FileType("m4a", "M4A (AAC)"),
            new FileType("opus", "Opus"), new FileType("best", "Original"));

    /** The file types that fit {@code preset}. */
    public static List<FileType> typesFor(final Preset preset) {
        return preset == Preset.AUDIO ? AUDIO_TYPES : VIDEO_TYPES;
    }

    /** What the download dialog picked: a quality and a file type that fits it. */
    public record Choice(Preset preset, String format) {
    }

    /** Everything a download's command line depends on. */
    public record Options(Preset preset, String container, String codec, String audioFormat,
                          String sponsorBlockMode, List<String> sponsorBlockCategories,
                          boolean subtitles, String subLangs, Path directory) {
    }

    private YtDlp() {
    }

    public static List<String> command(final String url, final Options o)
            throws java.io.IOException {
        final List<String> cmd = new ArrayList<>();
        cmd.add("yt-dlp");
        cmd.addAll(Network.ytDlpArgs());
        cmd.addAll(List.of("--no-playlist", "--newline", "--no-colors", "--progress",
                "--no-mtime", "--continue"));
        cmd.addAll(List.of("--progress-template", "download:" + PROGRESS
                + " %(progress.downloaded_bytes)s %(progress.total_bytes)s"
                + " %(progress.total_bytes_estimate)s %(progress.filename)s"));
        cmd.addAll(List.of("--print", "before_dl:" + FORMAT + " %(format_id)s"));
        cmd.addAll(List.of("--print", "after_move:" + FILE + " %(filepath)s"));

        if (o.preset() == Preset.AUDIO) {
            final String format = o.audioFormat() == null || o.audioFormat().isBlank() ? "best"
                    : o.audioFormat();
            cmd.addAll(List.of("-f", "bestaudio/best", "-x", "--audio-format", format));
            if (format.equals("mp3")) {
                // mp3 is a re-encode; yt-dlp's default is ~128k, so use the best VBR (V0)
                cmd.addAll(List.of("--audio-quality", "0"));
            }
        } else {
            final String limit = switch (o.preset()) {
                case P1080 -> "[height<=1080]";
                case P720 -> "[height<=720]";
                default -> "";
            };
            cmd.addAll(List.of("-f", "bv*" + limit + "+ba/b" + limit + "/b"));
            final List<String> sort = new ArrayList<>();
            if (o.codec() != null && !o.codec().equals("any")) {
                sort.add("vcodec:" + o.codec());
            }
            if (o.container() != null && !o.container().equals("any")) {
                cmd.addAll(List.of("--merge-output-format", o.container()));
                if (!o.container().equals("mkv")) {
                    // mp4/webm can't hold every codec; prefer streams that fit
                    sort.add("ext:" + o.container() + ":" + (o.container().equals("mp4")
                            ? "m4a" : "webm"));
                }
            }
            if (!sort.isEmpty()) {
                cmd.addAll(List.of("-S", String.join(",", sort)));
            }
        }

        cmd.addAll(List.of("--embed-metadata", "--embed-thumbnail", "--embed-chapters"));
        if (o.subtitles() && o.preset() != Preset.AUDIO) {
            cmd.addAll(List.of("--write-subs", "--embed-subs", "--sub-langs",
                    o.subLangs() == null || o.subLangs().isBlank() ? "en.*" : o.subLangs()));
        }

        final String categories = String.join(",", o.sponsorBlockCategories());
        final String mode = o.sponsorBlockMode() == null ? "off"
                : o.sponsorBlockMode().toLowerCase(Locale.ROOT);
        if (!categories.isEmpty() && (mode.equals("remove") || mode.equals("mark"))) {
            cmd.addAll(List.of(mode.equals("remove") ? "--sponsorblock-remove"
                    : "--sponsorblock-mark", categories, "--sponsorblock-api", SPONSORBLOCK_API));
        } else {
            cmd.add("--no-sponsorblock");
        }

        cmd.addAll(List.of("-P", o.directory().toString(),
                "-o", "%(uploader|Unknown)s - %(title).150B [%(id)s].%(ext)s"));
        cmd.add("--");
        cmd.add(url);
        return cmd;
    }
}
