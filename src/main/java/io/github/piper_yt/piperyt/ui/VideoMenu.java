package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Favorites;
import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.player.PlayQueue;
import io.github.piper_yt.piperyt.service.DownloadManager;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Widget;

import java.util.List;

/**
 * The "⋮" menu of a video card: queue, Watch Later, playlists, download, favorite the
 * channel, plus whatever the current view adds (remove from history, not interested…).
 */
final class VideoMenu {
    /** A view-specific entry; {@code destructive} styles it red. */
    record Extra(String label, Runnable action, boolean destructive) {
        Extra(final String label, final Runnable action) {
            this(label, action, false);
        }
    }

    private VideoMenu() {
    }

    static void show(final MainWindow main, final Widget anchor, final Video video,
                     final List<Extra> extras) {
        final Popover popover = new Popover();
        final Box box = new Box(Orientation.VERTICAL, 0);
        box.addCssClass("menu-box");
        popover.setChild(box);
        popover.setPosition(PositionType.BOTTOM);
        popover.setHasArrow(false);
        // Parented before any handler is connected (see README: unparented widgets get freed)
        popover.setParent(anchor);
        popover.onClosed(() -> Ui.runOnMain(popover::unparent));

        final boolean youtube = video.serviceId() == Video.YOUTUBE;
        item(box, popover, "Play next", () -> {
            PlayQueue.playNext(video);
            if (PlayQueue.items().size() == 1) {
                main.playQueueCurrent();
            } else {
                main.toast("Plays next");
            }
        });
        item(box, popover, "Add to queue", () -> {
            final boolean wasEmpty = PlayQueue.isEmpty();
            PlayQueue.append(List.of(video));
            if (wasEmpty) {
                main.playQueueCurrent();
            } else {
                main.toast("Added to the queue");
            }
        });
        box.append(new Separator(Orientation.HORIZONTAL));

        final long watchLater = Playlists.watchLaterId();
        if (Playlists.contains(watchLater, video.url())) {
            item(box, popover, "Remove from Watch Later", () -> {
                Playlists.remove(watchLater, video.url());
                main.toast("Removed from Watch Later");
            });
        } else {
            item(box, popover, "Save to Watch Later", () -> {
                Playlists.add(watchLater, video);
                main.toast("Saved to Watch Later");
            });
        }
        item(box, popover, "Add to playlist…", () -> Dialogs.playlistPicker(main, video));
        if (youtube && !video.isLive()) {
            item(box, popover, "Download…", () -> Dialogs.download(main, choice -> {
                DownloadManager.enqueue(video, choice);
                main.toast("Downloading “" + video.title() + "”");
            }));
        }

        final String channel = video.uploaderUrl();
        if (youtube && channel != null && !channel.isBlank()) {
            box.append(new Separator(Orientation.HORIZONTAL));
            final String name = video.uploader().isBlank() ? "channel" : video.uploader();
            if (Favorites.isFavorite(channel)) {
                item(box, popover, "Remove " + name + " from Favorites", () -> {
                    Favorites.remove(channel);
                    main.toast("Removed " + name + " from Favorites");
                });
            } else {
                item(box, popover, "Add " + name + " to Favorites", () -> {
                    Favorites.add(Video.YOUTUBE, channel, video.uploader(), null, -1, null);
                    main.toast("Added " + name + " to Favorites");
                });
            }
            item(box, popover, "Go to channel", () -> main.openChannel(channel,
                    video.uploader()));
        }

        if (!extras.isEmpty()) {
            box.append(new Separator(Orientation.HORIZONTAL));
            for (final Extra e : extras) {
                final Button b = item(box, popover, e.label(), e.action());
                if (e.destructive()) {
                    b.addCssClass("destructive-label");
                }
            }
        }
        popover.popup();
    }

    private static Button item(final Box box, final Popover popover, final String label,
                               final Runnable action) {
        final Label text = Ui.label(label);
        final Button button = new Button();
        button.setChild(text);
        button.addCssClass("flat");
        button.addCssClass("menu-item");
        box.append(button);
        button.onClicked(() -> {
            popover.popdown();
            action.run();
        });
        return button;
    }
}
