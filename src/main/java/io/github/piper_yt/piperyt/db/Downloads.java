package io.github.piper_yt.piperyt.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** The download queue and library. Files are tracked by path; missing ones are detected. */
public final class Downloads {
    public enum Status { QUEUED, RUNNING, PAUSED, FAILED, DONE }

    public record Download(long id, Video video, String preset, String format,
                           String sponsorBlock,
                           Status status, double progress, String filePath, Long fileSize,
                           String error, long createdAt, Long finishedAt) {
        public boolean isAudioOnly() {
            return "audio".equals(preset);
        }

        /** The file, if the download finished and the file is still there. */
        public Path existingFile() {
            if (status != Status.DONE || filePath == null) {
                return null;
            }
            final Path p = Path.of(filePath);
            return Files.isRegularFile(p) ? p : null;
        }
    }

    private Downloads() {
    }

    /**
     * Queues {@code video}; if the same video with the same preset and file type is already
     * queued, running or downloaded, returns that one's id instead. A null {@code format}
     * means the file type set in Preferences.
     */
    public static long enqueue(final Video video, final String preset, final String format,
                               final String sponsorBlock) {
        final long id = Db.tx(() -> {
            final Long existing = Db.one("SELECT id FROM downloads WHERE video_url = ? AND "
                    + "preset = ? AND format IS ? AND status != 'failed'", rs -> rs.getLong(1),
                    video.url(), preset, format);
            if (existing != null) {
                final Download d = get(existing);
                if (d.status() != Status.DONE || d.existingFile() != null) {
                    if (d.status() == Status.PAUSED) {
                        setStatus(existing, Status.QUEUED, null);
                    }
                    return existing;
                }
                // Downloaded, but the file is gone: download it again
                Db.update("DELETE FROM downloads WHERE id = ?", existing);
            }
            Db.update("DELETE FROM downloads WHERE video_url = ? AND preset = ? AND "
                    + "format IS ? AND status = 'failed'", video.url(), preset, format);
            final Object[] bind = video.bind();
            final Object[] args = new Object[bind.length + 4];
            System.arraycopy(bind, 0, args, 0, bind.length);
            args[bind.length] = preset;
            args[bind.length + 1] = format;
            args[bind.length + 2] = sponsorBlock;
            args[bind.length + 3] = System.currentTimeMillis();
            return Db.insert("INSERT INTO downloads (" + Video.COLUMNS + ", preset, format,"
                    + " sponsorblock, status, created_at) VALUES (" + Video.PLACEHOLDERS
                    + ", ?, ?, ?, 'queued', ?)", args);
        });
        Library.changed(Library.Topic.DOWNLOADS);
        return id;
    }

    public static Download get(final long id) {
        return Db.one("SELECT * FROM downloads WHERE id = ?", Downloads::read, id);
    }

    /** Everything not finished, oldest first (the queue). */
    public static List<Download> pending() {
        return Db.query("SELECT * FROM downloads WHERE status != 'done' ORDER BY created_at, id",
                Downloads::read);
    }

    /** The next queued download, or null. */
    public static Download nextQueued() {
        return Db.one("SELECT * FROM downloads WHERE status = 'queued' ORDER BY created_at, id",
                Downloads::read);
    }

    /** Finished downloads, newest first, filtered by title or channel. */
    public static List<Download> finished(final String filter) {
        final String like = "%" + (filter == null ? "" : filter.strip()) + "%";
        return Db.query("SELECT * FROM downloads WHERE status = 'done' AND (title LIKE ? OR "
                + "uploader LIKE ?) ORDER BY finished_at DESC", Downloads::read, like, like);
    }

    /**
     * The best local copy of {@code videoUrl} whose file still exists: a video file if
     * {@code wantVideo} and there is one, else any.
     */
    public static Download local(final String videoUrl, final boolean wantVideo) {
        if (videoUrl == null || !Db.isOpen()) {
            return null;
        }
        Download fallback = null;
        for (final Download d : Db.query("SELECT * FROM downloads WHERE video_url = ? AND "
                + "status = 'done' ORDER BY finished_at DESC", Downloads::read,
                Video.canonicalUrl(videoUrl))) {
            if (d.existingFile() == null) {
                continue;
            }
            if (!wantVideo || !d.isAudioOnly()) {
                return d;
            }
            fallback = d;
        }
        return fallback;
    }

    /** Video URLs with a finished download whose file still exists (for card badges). */
    public static java.util.Set<String> downloadedUrls() {
        final java.util.Set<String> urls = new java.util.HashSet<>();
        Db.query("SELECT video_url, file_path FROM downloads WHERE status = 'done'", rs -> {
            final String path = rs.getString(2);
            if (path != null && Files.isRegularFile(Path.of(path))) {
                urls.add(rs.getString(1));
            }
            return null;
        });
        return urls;
    }

    public static void setStatus(final long id, final Status status, final String error) {
        Db.update("UPDATE downloads SET status = ?, error = ? WHERE id = ?",
                status.name().toLowerCase(), error, id);
        Library.changed(Library.Topic.DOWNLOADS);
    }

    /** Progress is frequent; listeners poll instead of being told every time. */
    public static void setProgress(final long id, final double progress) {
        Db.update("UPDATE downloads SET progress = ? WHERE id = ?", progress, id);
    }

    public static void finish(final long id, final Path file, final long size) {
        Db.update("UPDATE downloads SET status = 'done', progress = 1, file_path = ?, "
                        + "file_size = ?, error = NULL, finished_at = ? WHERE id = ?",
                file.toString(), size, System.currentTimeMillis(), id);
        Library.changed(Library.Topic.DOWNLOADS);
    }

    public static void delete(final long id) {
        Db.update("DELETE FROM downloads WHERE id = ?", id);
        Library.changed(Library.Topic.DOWNLOADS);
    }

    /** After a restart nothing is running: put interrupted downloads back in the queue. */
    public static void requeueInterrupted() {
        Db.update("UPDATE downloads SET status = 'queued' WHERE status = 'running'");
    }

    private static Download read(final ResultSet rs) throws SQLException {
        return new Download(rs.getLong("id"), Video.read(rs), rs.getString("preset"),
                rs.getString("format"), rs.getString("sponsorblock"),
                Status.valueOf(rs.getString("status").toUpperCase()),
                rs.getDouble("progress"), rs.getString("file_path"),
                Db.getLong(rs, "file_size"), rs.getString("error"), rs.getLong("created_at"),
                Db.getLong(rs, "finished_at"));
    }
}
