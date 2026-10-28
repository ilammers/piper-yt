package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Downloads;
import io.github.piper_yt.piperyt.db.Downloads.Download;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.YtDlp;
import io.github.piper_yt.piperyt.util.Format;
import org.gnome.adw.ActionRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.UriLauncher;
import org.gnome.gtk.Widget;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The download queue (progress, pause/resume/retry/cancel) and the downloaded library. */
final class DownloadsView implements Shell.View {
    private record QueueRow(ProgressBar bar, Label status) {
    }

    private final MainWindow main;
    private final Box box = new Box(Orientation.VERTICAL, 0);
    private final SearchEntry filter = new SearchEntry();
    private final Label queueTitle = Ui.label("Queue", "heading");
    private final ListBox queue = new ListBox();
    private final Label libraryTitle = Ui.label("Library", "heading");
    private final ListBox library = new ListBox();
    private final StatusPage empty = new StatusPage();
    private final Map<Long, QueueRow> queueRows = new HashMap<>();
    private List<Download> finished = List.of();
    private boolean dirty = true;
    private boolean visible;

    DownloadsView(final MainWindow main) {
        this.main = main;
        filter.setPlaceholderText("Filter downloads");
        filter.onSearchChanged(this::reloadLibrary);
        final Button folder = Button.fromIconName("folder-open-symbolic");
        folder.setTooltipText("Open the download folder");
        folder.onClicked(() -> new UriLauncher(Settings.downloadDir().toUri().toString())
                .launch(main.window(), null, null));
        box.append(Ui.viewHeader("Downloads", filter, folder));

        queue.setSelectionMode(SelectionMode.NONE);
        queue.addCssClass("boxed-list");
        library.setSelectionMode(SelectionMode.NONE);
        library.addCssClass("boxed-list");
        library.onRowActivated(row -> {
            final Download d = finished.get(row.getIndex());
            main.openVideo(d.video().url(), d.video().title(), true);
        });
        empty.setIconName("folder-download-symbolic");
        empty.setTitle("No downloads");
        empty.setDescription(DownloadManager.isAvailable()
                ? "Use “Download…” in a video's ⋮ menu or on its page. Downloaded videos play "
                + "from disk everywhere they appear."
                : "Downloads need yt-dlp: sudo dnf install yt-dlp");
        empty.addCssClass("compact");

        final Box content = new Box(Orientation.VERTICAL, 12);
        content.setMarginTop(12);
        content.setMarginBottom(24);
        content.setMarginStart(12);
        content.setMarginEnd(12);
        content.append(queueTitle);
        content.append(queue);
        content.append(libraryTitle);
        content.append(library);
        content.append(empty);
        final Clamp clamp = new Clamp();
        clamp.setMaximumSize(960);
        clamp.setChild(content);
        final ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setChild(clamp);
        scroller.setVexpand(true);
        box.append(scroller);

        Library.listen(Library.Topic.DOWNLOADS, () -> {
            dirty = true;
            if (visible) {
                reload();
            }
        });
        DownloadManager.listen((id, live) -> Ui.runOnMain(() -> {
            final QueueRow row = queueRows.get(id);
            if (row != null && live != null) {
                row.bar().setFraction(live.fraction());
                row.status().setText(live.stage() + " · "
                        + Math.round(live.fraction() * 100) + "%");
            }
        }));
    }

    @Override
    public Widget widget() {
        return box;
    }

    @Override
    public void shown() {
        visible = true;
        if (dirty) {
            reload();
        }
    }

    private void reload() {
        dirty = false;
        queue.removeAll();
        queueRows.clear();
        final List<Download> pending = Downloads.pending();
        for (final Download d : pending) {
            queue.append(queueRow(d));
        }
        queueTitle.setVisible(!pending.isEmpty());
        queue.setVisible(!pending.isEmpty());
        reloadLibrary();
    }

    private void reloadLibrary() {
        finished = Downloads.finished(filter.getText());
        library.removeAll();
        for (final Download d : finished) {
            library.append(libraryRow(d));
        }
        libraryTitle.setVisible(!finished.isEmpty());
        library.setVisible(!finished.isEmpty());
        empty.setVisible(finished.isEmpty() && queue.getFirstChild() == null
                && filter.getText().isBlank());
    }

    private Widget queueRow(final Download d) {
        final Box row = new Box(Orientation.VERTICAL, 6);
        row.setMarginTop(10);
        row.setMarginBottom(10);
        row.setMarginStart(12);
        row.setMarginEnd(12);
        final Box top = new Box(Orientation.HORIZONTAL, 8);
        final Box text = new Box(Orientation.VERTICAL, 2);
        text.setHexpand(true);
        text.append(Ui.wrapping(d.video().title(), 1, "heading"));
        final Label status = Ui.wrapping(statusText(d), 2, "dim-label", "caption");
        text.append(status);
        top.append(text);

        final ProgressBar bar = new ProgressBar();
        bar.setFraction(d.progress());
        final DownloadManager.Live live = DownloadManager.live(d.id());
        if (live != null) {
            bar.setFraction(live.fraction());
        }
        switch (d.status()) {
            case RUNNING, QUEUED -> top.append(iconButton("media-playback-pause-symbolic",
                    "Pause", () -> DownloadManager.pause(d.id())));
            case PAUSED -> top.append(iconButton("media-playback-start-symbolic", "Resume",
                    () -> DownloadManager.resume(d.id())));
            case FAILED -> top.append(iconButton("view-refresh-symbolic", "Retry",
                    () -> DownloadManager.retry(d.id())));
            default -> {
            }
        }
        top.append(iconButton("window-close-symbolic", "Cancel and delete partial files",
                () -> DownloadManager.cancel(d.id())));
        row.append(top);
        row.append(bar);
        queueRows.put(d.id(), new QueueRow(bar, status));

        final ListBoxRow listRow = new ListBoxRow();
        listRow.setActivatable(false);
        listRow.setChild(row);
        return listRow;
    }

    private static String statusText(final Download d) {
        final String type = d.format() == null || d.format().equals("any")
                || d.format().equals("best") ? null : d.format().toUpperCase(Locale.ROOT);
        final String preset = Format.dot(YtDlp.Preset.of(d.preset()).label, type);
        return switch (d.status()) {
            case QUEUED -> "Waiting · " + preset;
            case RUNNING -> "Downloading · " + preset;
            case PAUSED -> "Paused · " + Math.round(d.progress() * 100) + "%";
            case FAILED -> "Failed: " + (d.error() == null ? "unknown error" : d.error());
            case DONE -> "Done";
        };
    }

    private Widget libraryRow(final Download d) {
        final ActionRow row = new ActionRow();
        row.setTitle(Ui.markupSafe(d.video().title()));
        row.setTitleLines(2);
        row.setActivatable(true);
        final boolean missing = d.existingFile() == null;
        final String size = d.fileSize() == null ? null : String.format(Locale.ROOT, "%.1f MB",
                d.fileSize() / 1_048_576.0);
        final String sponsor = switch (d.sponsorBlock()) {
            case "remove" -> "sponsors removed";
            case "mark" -> "sponsors marked";
            default -> null;
        };
        row.setSubtitle(Ui.markupSafe(missing ? "File missing: plays from YouTube. "
                + d.filePath() : Format.dot(d.video().uploader(), size,
                YtDlp.Preset.of(d.preset()).label, sponsor)));
        final Widget thumb = VideoCard.thumbnail(d.video().thumbnailUrl(), false,
                Format.duration(d.video().durationS()));
        thumb.setMarginTop(6);
        thumb.setMarginBottom(6);
        row.addPrefix(thumb);
        if (missing) {
            final Button again = iconButton("folder-download-symbolic", "Download again", () -> {
                Downloads.delete(d.id());
                DownloadManager.enqueue(d.video(),
                        new YtDlp.Choice(YtDlp.Preset.of(d.preset()), d.format()));
            });
            row.addSuffix(again);
        }
        row.addSuffix(iconButton("user-trash-symbolic", "Delete file", () ->
                Dialogs.confirm(main.window(), "Delete “" + d.video().title() + "”?",
                        missing ? "Removes it from the library." : "Deletes " + d.filePath(),
                        "Delete", true, () -> Ui.async(() -> {
                            DownloadManager.deleteFile(d.id());
                            return null;
                        }, done -> main.toast("Deleted"),
                                error -> main.toast(Ui.errorMessage(error))))));
        return row;
    }

    private static Button iconButton(final String icon, final String tooltip,
                                     final Runnable action) {
        final Button b = Button.fromIconName(icon);
        b.addCssClass("flat");
        b.setValign(Align.CENTER);
        b.setTooltipText(tooltip);
        b.onClicked(action::run);
        return b;
    }
}
