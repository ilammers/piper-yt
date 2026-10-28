package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.Clamp;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Picture;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.Widget;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.function.Consumer;

/** Thumbnail, title and metadata for one video, as a grid card or a compact row. */
public final class VideoCard {

    public enum Layout { GRID, ROW }

    /**
     * Optional extras: a line under the metadata (the homepage's "From Favorites") with a
     * longer tooltip, and the "⋮" menu, called with the button to anchor it to.
     */
    public record Decor(String note, String noteTooltip, Consumer<Widget> menu) {
        static final Decor NONE = new Decor(null, null, null);
    }

    private static final int MIN_GRID_WIDTH = 260;
    private static final int MAX_GRID_WIDTH = 340;

    private VideoCard() {
    }

    public static Widget create(final StreamInfoItem item, final Layout layout,
                                final MainWindow main) {
        return create(item, layout, main, Decor.NONE);
    }

    /**
     * @param main if non-null, the uploader's name links to their channel; null where every card
     *             is from the same uploader (a channel's own tabs)
     */
    public static Widget create(final StreamInfoItem item, final Layout layout,
                                final MainWindow main, final Decor decor) {
        final boolean grid = layout == Layout.GRID;
        final Box card = new Box(grid ? Orientation.VERTICAL : Orientation.HORIZONTAL,
                grid ? 8 : 12);
        card.addCssClass("video-card");

        final String badge = isLive(item) ? "LIVE" : Format.duration(item.getDuration());
        final Widget thumb = thumbnail(item.getThumbnailUrl(), grid, badge);
        if (Badges.isDownloaded(item.getUrl()) && thumb instanceof Overlay overlay) {
            final Image saved = Image.fromIconName("folder-download-symbolic");
            saved.addCssClass("saved-badge");
            saved.setHalign(Align.START);
            saved.setValign(Align.END);
            saved.setTooltipText("Downloaded: plays from disk");
            overlay.addOverlay(saved);
        }
        final double watched = Badges.progress(item.getUrl());
        if (watched > 0 && thumb instanceof Overlay overlay) {
            overlay.addOverlay(progressBar(watched));
        }
        card.append(thumb);

        final Box text = new Box(Orientation.VERTICAL, 4);
        text.setHexpand(true);
        text.setValign(Align.START);
        if (grid) {
            text.setMarginStart(4);
            text.setMarginEnd(4);
        }
        final Label title = Ui.wrapping(item.getName(), 2, "heading");
        title.setTooltipText(item.getName());
        title.setHexpand(true);
        if (grid && decor.menu() != null) {
            final Box titleRow = new Box(Orientation.HORIZONTAL, 2);
            titleRow.append(title);
            titleRow.append(menuButton(decor.menu()));
            text.append(titleRow);
        } else {
            text.append(title);
        }
        text.append(uploader(item.getUploaderName(), item.getUploaderUrl(), main));

        final String views = item.getViewCount() >= 0
                ? Format.count(item.getViewCount()) + (isLive(item) ? " watching" : " views")
                : "";
        text.append(Ui.wrapping(Format.dot(views, Format.relativeDate(item.getUploadDate())), 1,
                "dim-label", "caption"));
        if (decor.note() != null && !decor.note().isBlank()) {
            final Label note = Ui.wrapping(decor.note(), 1, "caption", "pick-reason");
            note.setTooltipText(decor.noteTooltip());
            text.append(note);
            card.setTooltipText(decor.noteTooltip());
        }
        card.append(text);
        if (!grid && decor.menu() != null) {
            final Button menu = menuButton(decor.menu());
            menu.setValign(Align.CENTER);
            card.append(menu);
        }
        return grid ? clampGridCard(card) : card;
    }

    private static Button menuButton(final Consumer<Widget> show) {
        final Button button = Button.fromIconName("view-more-symbolic");
        button.addCssClass("flat");
        button.addCssClass("circular");
        button.setValign(Align.START);
        button.setTooltipText("More");
        button.onClicked(() -> show.accept(button));
        return button;
    }

    /** A rounded thumbnail with an optional badge (duration, video count) in its corner. */
    static Widget thumbnail(final String url, final boolean grid, final String badge) {
        final Picture picture = new Picture();
        picture.setContentFit(ContentFit.COVER);
        picture.setCanShrink(true);
        picture.addCssClass(grid ? "thumbnail" : "thumbnail-small");
        picture.setOverflow(org.gnome.gtk.Overflow.HIDDEN);
        if (grid) {
            picture.setSizeRequest(MIN_GRID_WIDTH, 180);
        } else {
            picture.setSizeRequest(168, 94);
        }
        // Twice the card width: sharp on HiDPI screens
        Images.load(url, (grid ? MAX_GRID_WIDTH : 168) * 2, picture, picture::setPaintable);

        final Overlay overlay = new Overlay();
        // A Picture's natural width is the image's full width (1280px for hq720
        // thumbnails), so clamp it or it pushes every card to full width.
        final Clamp thumbClamp = new Clamp();
        thumbClamp.setMaximumSize(grid ? MAX_GRID_WIDTH : 168);
        thumbClamp.setTighteningThreshold(grid ? MAX_GRID_WIDTH : 168);
        thumbClamp.setChild(picture);
        overlay.setChild(thumbClamp);
        if (badge != null && !badge.isEmpty()) {
            final Label label = new Label(badge);
            label.addCssClass("duration-badge");
            label.setHalign(Align.END);
            label.setValign(Align.END);
            overlay.addOverlay(label);
        }
        overlay.setValign(Align.START);
        return overlay;
    }

    /** A thin bar along the thumbnail's bottom edge: how much of it was watched. */
    private static Widget progressBar(final double fraction) {
        final ProgressBar bar = new ProgressBar();
        bar.setFraction(Math.min(1, fraction));
        bar.addCssClass("watch-progress");
        bar.setValign(Align.END);
        bar.setCanTarget(false);
        bar.setTooltipText(fraction >= 1 ? "Watched" : "Watched " + Math.round(fraction * 100)
                + "%");
        return bar;
    }

    /** The uploader's name, as a link to their channel when {@code main} is given. */
    static Label uploader(final String name, final String url, final MainWindow main) {
        final Label label = Ui.wrapping("", 1, "dim-label", "caption");
        if (main == null || url == null || url.isBlank()) {
            label.setText(name == null ? "" : name);
            return label;
        }
        label.setMarkup("<a href=\"" + Format.escape(url) + "\">" + Format.escape(name) + "</a>");
        label.addCssClass("uploader-link");
        label.onActivateLink(uri -> {
            main.openChannel(uri, name);
            return true;
        });
        return label;
    }

    /** Keeps a grid card from stretching past a thumbnail's width. */
    static Widget clampGridCard(final Widget card) {
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(MAX_GRID_WIDTH);
        clamp.setTighteningThreshold(MAX_GRID_WIDTH);
        clamp.setChild(card);
        return clamp;
    }

    private static boolean isLive(final StreamInfoItem item) {
        return item.getStreamType() == StreamType.LIVE_STREAM
                || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
    }
}
