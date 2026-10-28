package io.github.piper_yt.piperyt.ui;

import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;

/** Thumbnail, title, uploader and video count for one playlist, as a grid card or a row. */
final class PlaylistCard {
    private PlaylistCard() {
    }

    static Widget create(final PlaylistInfoItem item) {
        final Box card = new Box(Orientation.VERTICAL, 8);
        card.addCssClass("video-card");
        card.append(VideoCard.thumbnail(item.getThumbnailUrl(), true, badge(item)));

        final Box text = new Box(Orientation.VERTICAL, 4);
        text.setValign(Align.START);
        text.setMarginStart(4);
        text.setMarginEnd(4);
        final Label title = Ui.wrapping(item.getName(), 2, "heading");
        title.setTooltipText(item.getName());
        text.append(title);
        text.append(Ui.wrapping(item.getUploaderName(), 1, "dim-label", "caption"));
        card.append(text);
        return VideoCard.clampGridCard(card);
    }

    /** A compact row, like {@code VideoCard.Layout.ROW} (Up next's mixes and playlists). */
    static Widget row(final PlaylistInfoItem item) {
        final Box card = new Box(Orientation.HORIZONTAL, 12);
        card.addCssClass("video-card");
        card.append(VideoCard.thumbnail(item.getThumbnailUrl(), false, badge(item)));
        final Box text = new Box(Orientation.VERTICAL, 4);
        text.setHexpand(true);
        text.setValign(Align.START);
        final Label title = Ui.wrapping(item.getName(), 2, "heading");
        title.setTooltipText(item.getName());
        text.append(title);
        text.append(Ui.wrapping(item.getUploaderName(), 1, "dim-label", "caption"));
        text.append(Ui.wrapping(isMix(item) ? "Mix" : "Playlist", 1, "dim-label", "caption"));
        card.append(text);
        return card;
    }

    /** YouTube's endless auto-generated mixes have "RD" list IDs. */
    static boolean isMix(final PlaylistInfoItem item) {
        return item.getUrl() != null && item.getUrl().contains("list=RD");
    }

    private static String badge(final PlaylistInfoItem item) {
        final long count = item.getStreamCount();
        if (count >= 0) {
            return count + (count == 1 ? " video" : " videos");
        }
        return isMix(item) ? "Mix" : "Playlist";
    }
}
