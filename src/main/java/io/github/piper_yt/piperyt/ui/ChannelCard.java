package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Avatar;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;

/** Avatar, name, subscriber count and description for one channel, as a grid card. */
final class ChannelCard {
    private static final int AVATAR_SIZE = 112;

    private ChannelCard() {
    }

    static Widget create(final ChannelInfoItem item, final MainWindow main) {
        final Box card = new Box(Orientation.VERTICAL, 6);
        card.addCssClass("video-card");

        final Avatar avatar = new Avatar(AVATAR_SIZE, item.getName(), true);
        avatar.setHalign(Align.CENTER);
        avatar.setMarginTop(12);
        avatar.setMarginBottom(6);
        Images.load(largerAvatar(item.getThumbnailUrl()), avatar, avatar::setCustomImage);
        card.append(avatar);

        final Label name = centered(Ui.wrapping(item.getName(), 1, "heading"));
        name.setTooltipText(item.getName());
        card.append(name);
        final String subscribers = item.getSubscriberCount() >= 0
                ? Format.count(item.getSubscriberCount()) + " subscribers" : "";
        final String videos = item.getStreamCount() >= 0
                ? Format.count(item.getStreamCount()) + " videos" : "";
        card.append(centered(Ui.wrapping(Format.dot(subscribers, videos), 1,
                "dim-label", "caption")));
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            card.append(centered(Ui.wrapping(item.getDescription(), 2, "dim-label", "caption")));
        }
        if (main != null) {
            final Button favorite = FavoriteButton.create(main, item.getUrl(), item.getName(),
                    item.getThumbnailUrl(), item.getSubscriberCount(), item.getDescription());
            favorite.setHalign(Align.CENTER);
            favorite.setMarginTop(6);
            favorite.setMarginBottom(6);
            card.append(favorite);
        }
        return VideoCard.clampGridCard(card);
    }

    private static Label centered(final Label label) {
        label.setXalign(0.5f);
        label.setJustify(org.gnome.gtk.Justification.CENTER);
        return label;
    }

    /** Search results link 88px avatars; YouTube serves any size from the same URL. */
    static String largerAvatar(final String url) {
        return url == null ? null : url.replaceFirst("=s\\d+-", "=s240-");
    }
}
