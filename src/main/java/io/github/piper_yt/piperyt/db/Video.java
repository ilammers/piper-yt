package io.github.piper_yt.piperyt.db;

import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the library stores about a video: enough to show a card and open it, with no network.
 * {@code uploadDate} is epoch milliseconds or null; {@code durationS} is -1 when unknown.
 */
public record Video(int serviceId, String url, String title, String uploader, String uploaderUrl,
                    String thumbnailUrl, long durationS, String streamType, boolean isShort,
                    Long uploadDate, long viewCount) {

    public static final int YOUTUBE = 0;
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})");

    /** Columns matching {@link #bind()}, for INSERTs. */
    public static final String COLUMNS = "service_id, video_url, title, uploader, uploader_url, "
            + "thumbnail_url, duration_s, stream_type, is_short, upload_date, view_count";
    public static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

    public Video {
        title = title == null ? "" : title;
        uploader = uploader == null ? "" : uploader;
        streamType = streamType == null || streamType.isBlank() ? "VIDEO_STREAM" : streamType;
        uploaderUrl = Favorites.canonicalChannelUrl(uploaderUrl);
    }

    public static Video from(final StreamInfoItem item) {
        final boolean isShort = item.isShortFormContent()
                || item.getUrl() != null && item.getUrl().contains("/shorts/");
        return new Video(item.getServiceId(), canonicalUrl(item.getUrl()), item.getName(),
                item.getUploaderName(), item.getUploaderUrl(), item.getThumbnailUrl(),
                item.getDuration(), item.getStreamType() == null ? null
                : item.getStreamType().name(), isShort, millis(item.getUploadDate()),
                item.getViewCount());
    }

    public static Video from(final StreamInfo info) {
        return new Video(info.getServiceId(), canonicalUrl(info.getUrl()), info.getName(),
                info.getUploaderName(), info.getUploaderUrl(), info.getThumbnailUrl(),
                info.getDuration(), info.getStreamType() == null ? null
                : info.getStreamType().name(), info.isShortFormContent(),
                millis(info.getUploadDate()), info.getViewCount());
    }

    /** Reads the {@link #COLUMNS} of the current row. */
    public static Video read(final ResultSet rs) throws SQLException {
        return new Video(rs.getInt("service_id"), rs.getString("video_url"),
                rs.getString("title"), rs.getString("uploader"), rs.getString("uploader_url"),
                rs.getString("thumbnail_url"), rs.getLong("duration_s"),
                rs.getString("stream_type"), rs.getInt("is_short") != 0,
                Db.getLong(rs, "upload_date"), rs.getLong("view_count"));
    }

    /** Values for {@link #COLUMNS}, in order. */
    public Object[] bind() {
        return new Object[]{serviceId, url, title, uploader, uploaderUrl, thumbnailUrl,
                durationS, streamType, isShort, uploadDate, viewCount};
    }

    /** An extractor item for the existing cards and grids. */
    public StreamInfoItem toItem() {
        final StreamInfoItem item = new StreamInfoItem(serviceId, url, title, type());
        item.setUploaderName(uploader);
        item.setUploaderUrl(uploaderUrl);
        item.setThumbnailUrl(thumbnailUrl);
        item.setDuration(durationS);
        item.setViewCount(viewCount);
        item.setShortFormContent(isShort);
        if (uploadDate != null) {
            item.setUploadDate(new DateWrapper(OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(uploadDate), ZoneOffset.UTC)));
        }
        return item;
    }

    public StreamType type() {
        try {
            return StreamType.valueOf(streamType);
        } catch (final IllegalArgumentException e) {
            return StreamType.VIDEO_STREAM;
        }
    }

    public boolean isLive() {
        return type() == StreamType.LIVE_STREAM || type() == StreamType.AUDIO_LIVE_STREAM;
    }

    /** The same video with a better title/thumbnail/duration from {@code newer}, if it has one. */
    public Video mergedWith(final Video newer) {
        return new Video(serviceId, url,
                newer.title.isBlank() ? title : newer.title,
                newer.uploader.isBlank() ? uploader : newer.uploader,
                newer.uploaderUrl == null ? uploaderUrl : newer.uploaderUrl,
                newer.thumbnailUrl == null ? thumbnailUrl : newer.thumbnailUrl,
                newer.durationS > 0 ? newer.durationS : durationS,
                newer.streamType, isShort || newer.isShort,
                newer.uploadDate == null ? uploadDate : newer.uploadDate,
                newer.viewCount >= 0 ? newer.viewCount : viewCount);
    }

    /** The 11-character YouTube video ID in {@code url}, or null. */
    public static String youtubeId(final String url) {
        if (url == null) {
            return null;
        }
        final Matcher m = YOUTUBE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * One URL per YouTube video ({@code /shorts/ID}, {@code youtu.be/ID} and
     * {@code watch?v=ID&t=..} all become {@code https://www.youtube.com/watch?v=ID}), so
     * history, playlists and downloads agree on what is the same video. Other URLs are kept.
     */
    public static String canonicalUrl(final String url) {
        final String id = youtubeId(url);
        return id == null ? url : "https://www.youtube.com/watch?v=" + id;
    }

    public static Long millis(final DateWrapper date) {
        return date == null ? null : date.offsetDateTime().toInstant().toEpochMilli();
    }
}
