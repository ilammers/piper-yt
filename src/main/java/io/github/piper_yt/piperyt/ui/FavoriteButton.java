package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Video;
import org.gnome.adw.ButtonContent;
import org.gnome.gtk.Button;

/** "Add to Favorites" / "In Favorites": toggles a channel in the local favorites. */
final class FavoriteButton {
    private FavoriteButton() {
    }

    static Button create(final MainWindow main, final String channelUrl, final String name,
                         final String avatarUrl, final long subscribers,
                         final String description) {
        final Button button = new Button();
        final ButtonContent content = new ButtonContent();
        button.setChild(content);
        button.addCssClass("pill");
        final Runnable update = () -> {
            final boolean favorite = Favorites.isFavorite(channelUrl);
            content.setLabel(favorite ? "In Favorites" : "Add to Favorites");
            content.setIconName(favorite ? "starred-symbolic" : "non-starred-symbolic");
            if (favorite) {
                button.removeCssClass("suggested-action");
            } else {
                button.addCssClass("suggested-action");
            }
            button.setTooltipText(favorite ? "Remove from Favorites"
                    : "Follow this channel locally: its uploads appear in Feed and Home");
        };
        update.run();
        button.onClicked(() -> {
            if (Favorites.isFavorite(channelUrl)) {
                Favorites.remove(channelUrl);
                main.toast("Removed " + name + " from Favorites");
            } else {
                Favorites.add(Video.YOUTUBE, channelUrl, name, avatarUrl, subscribers,
                        description);
                main.toast("Added " + name + " to Favorites");
            }
            update.run();
        });
        return button;
    }
}
