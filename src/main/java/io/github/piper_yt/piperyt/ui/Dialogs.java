package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.Playlists;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.db.Video;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.YtDlp;
import org.gnome.adw.ActionRow;
import org.gnome.adw.AlertDialog;
import org.gnome.adw.ComboRow;
import org.gnome.adw.Dialog;
import org.gnome.adw.EntryRow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.ResponseAppearance;
import org.gnome.adw.SwitchRow;
import org.gnome.adw.ToolbarView;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.FileFilter;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Widget;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Small dialogs shared by the views. All main thread. */
final class Dialogs {
    private Dialogs() {
    }

    /** Asks for a line of text; {@code onOk} gets it stripped and non-empty. */
    static void prompt(final Widget parent, final String heading, final String body,
                       final String initial, final String confirm,
                       final Consumer<String> onOk) {
        final AlertDialog dialog = new AlertDialog(heading, body);
        final Entry entry = new Entry();
        entry.setText(initial == null ? "" : initial);
        entry.setActivatesDefault(true);
        dialog.setExtraChild(entry);
        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("ok", confirm);
        dialog.setResponseAppearance("ok", ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse("ok");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            final String text = entry.getText().strip();
            if ("ok".equals(response) && !text.isEmpty()) {
                onOk.accept(text);
            }
        });
        dialog.present(parent);
        entry.grabFocus();
    }

    static void confirm(final Widget parent, final String heading, final String body,
                        final String confirm, final boolean destructive, final Runnable onOk) {
        final AlertDialog dialog = new AlertDialog(heading, body);
        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("ok", confirm);
        dialog.setResponseAppearance("ok", destructive
                ? ResponseAppearance.DESTRUCTIVE : ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse(destructive ? "cancel" : "ok");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            if ("ok".equals(response)) {
                onOk.run();
            }
        });
        dialog.present(parent);
    }

    /**
     * Picks a download quality and file type, defaulting to the ones in Preferences. The file
     * types offered follow the quality: containers for video, formats for audio only.
     */
    static void download(final MainWindow main, final Consumer<YtDlp.Choice> onPick) {
        if (!DownloadManager.isAvailable()) {
            main.toast("Downloads need yt-dlp: sudo dnf install yt-dlp");
            return;
        }
        final AlertDialog dialog = new AlertDialog("Download",
                "Saved to " + Settings.downloadDir() + ". SponsorBlock: "
                        + Settings.getString(Settings.DOWNLOAD_SPONSORBLOCK) + ".");

        final YtDlp.Preset[] presets = YtDlp.Preset.values();
        final ComboRow quality = new ComboRow();
        quality.setTitle("Quality");
        quality.setModel(new StringList(java.util.Arrays.stream(presets).map(p -> p.label)
                .toArray(String[]::new)));
        quality.setSelected(YtDlp.Preset.of(Settings.getString(Settings.DOWNLOAD_PRESET))
                .ordinal());

        final ComboRow fileType = new ComboRow();
        fileType.setTitle("File type");
        final Runnable fillTypes = () -> {
            final YtDlp.Preset preset = presets[(int) quality.getSelected()];
            final List<YtDlp.FileType> types = YtDlp.typesFor(preset);
            final String preferred = Settings.getString(preset == YtDlp.Preset.AUDIO
                    ? Settings.DOWNLOAD_AUDIO_FORMAT : Settings.DOWNLOAD_CONTAINER);
            fileType.setModel(new StringList(types.stream().map(YtDlp.FileType::label)
                    .toArray(String[]::new)));
            fileType.setSelected(Math.max(0,
                    types.stream().map(YtDlp.FileType::id).toList().indexOf(preferred)));
        };
        fillTypes.run();
        // Only refill when switching between video and audio, so a picked type survives
        final boolean[] wasAudio = {presets[(int) quality.getSelected()] == YtDlp.Preset.AUDIO};
        quality.onNotify("selected", pspec -> {
            final boolean audio = presets[(int) quality.getSelected()] == YtDlp.Preset.AUDIO;
            if (audio != wasAudio[0]) {
                wasAudio[0] = audio;
                fillTypes.run();
            }
        });

        final ListBox rows = new ListBox();
        rows.setSelectionMode(SelectionMode.NONE);
        rows.addCssClass("boxed-list");
        rows.append(quality);
        rows.append(fileType);
        dialog.setExtraChild(rows);

        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("download", "Download");
        dialog.setResponseAppearance("download", ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse("download");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            if ("download".equals(response)) {
                final YtDlp.Preset preset = presets[(int) quality.getSelected()];
                final List<YtDlp.FileType> types = YtDlp.typesFor(preset);
                final int i = (int) fileType.getSelected();
                onPick.accept(new YtDlp.Choice(preset,
                        i >= 0 && i < types.size() ? types.get(i).id() : null));
            }
        });
        dialog.present(main.window());
    }

    /** Toggles {@code video} in local playlists, with an inline "New playlist" row. */
    static void playlistPicker(final MainWindow main, final Video video) {
        final ListBox list = new ListBox();
        list.setSelectionMode(SelectionMode.NONE);
        list.addCssClass("boxed-list");

        final EntryRow create = new EntryRow();
        create.setTitle("New playlist");
        create.setShowApplyButton(true);

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAll();
            for (final Playlists.Playlist p : Playlists.local()) {
                final ActionRow row = new ActionRow();
                row.setTitle(Ui.markupSafe(p.name()));
                row.setSubtitle(p.itemCount() + (p.itemCount() == 1 ? " video" : " videos"));
                final CheckButton check = new CheckButton();
                check.setActive(Playlists.contains(p.id(), video.url()));
                check.setValign(Align.CENTER);
                row.addPrefix(check);
                row.setActivatableWidget(check);
                list.append(row);
                check.onToggled(() -> {
                    if (check.getActive()) {
                        Playlists.add(p.id(), video);
                    } else {
                        Playlists.remove(p.id(), video.url());
                    }
                });
            }
            list.append(create);
        };
        fill[0].run();
        create.onApply(() -> {
            final String name = create.getText().strip();
            if (!name.isEmpty()) {
                Playlists.add(Playlists.create(name), video);
                create.setText("");
                main.toast("Added to " + name);
                fill[0].run();
            }
        });

        final Box box = new Box(Orientation.VERTICAL, 12);
        box.setMarginTop(12);
        box.setMarginBottom(18);
        box.setMarginStart(18);
        box.setMarginEnd(18);
        box.append(Ui.wrapping(video.title(), 2, "dim-label"));
        box.append(list);
        final ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setPropagateNaturalHeight(true);
        scroller.setChild(box);

        final ToolbarView toolbar = new ToolbarView();
        toolbar.addTopBar(new HeaderBar());
        toolbar.setContent(scroller);
        final Dialog dialog = new Dialog();
        dialog.setTitle("Add to playlist");
        dialog.setContentWidth(420);
        dialog.setChild(toolbar);
        dialog.present(main.window());
    }

    /**
     * "Clear history": a time range, whether to also clear the blocklist, then a destructive
     * confirm. Clears everything derived from the deleted history too.
     */
    static void clearHistory(final MainWindow main) {
        final AlertDialog dialog = new AlertDialog("Clear history?",
                "Deletes watch history, search history, resume positions and everything the "
                        + "homepage learned from them. This can't be undone.");
        final ListBox ranges = new ListBox();
        ranges.setSelectionMode(SelectionMode.NONE);
        ranges.addCssClass("boxed-list");
        final CheckButton[] checks = new CheckButton[History.Range.values().length];
        for (final History.Range r : History.Range.values()) {
            final CheckButton check = new CheckButton();
            check.setActive(r == History.Range.ALL);
            if (r.ordinal() > 0) {
                check.setGroup(checks[0]);
            }
            checks[r.ordinal()] = check;
            final ActionRow row = new ActionRow();
            row.setTitle(r.label);
            row.addPrefix(check);
            row.setActivatableWidget(check);
            ranges.append(row);
        }
        final SwitchRow blocklist = new SwitchRow();
        blocklist.setTitle("Also clear “Not interested” and blocked channels");
        ranges.append(blocklist);
        dialog.setExtraChild(ranges);
        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("clear", "Clear");
        dialog.setResponseAppearance("clear", ResponseAppearance.DESTRUCTIVE);
        dialog.setDefaultResponse("cancel");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            if (!"clear".equals(response)) {
                return;
            }
            History.Range range = History.Range.ALL;
            for (final History.Range r : History.Range.values()) {
                if (checks[r.ordinal()].getActive()) {
                    range = r;
                }
            }
            final History.Range chosen = range;
            final boolean alsoBlocklist = blocklist.getActive();
            Ui.async(() -> {
                History.clear(chosen.since(System.currentTimeMillis()), alsoBlocklist);
                return null;
            }, done -> main.toast("History cleared (" + chosen.label.toLowerCase() + ")"),
                    error -> main.toast(Ui.errorMessage(error)));
        });
        dialog.present(main.window());
    }

    // File choosers

    static void openFile(final MainWindow main, final String title, final String filterName,
                         final String[] patterns, final Consumer<Path> onPick) {
        final FileDialog dialog = new FileDialog();
        dialog.setTitle(title);
        final FileFilter filter = new FileFilter();
        filter.setName(filterName);
        for (final String p : patterns) {
            filter.addPattern(p);
        }
        final ListStore<FileFilter> filters = new ListStore<>(FileFilter.getType());
        filters.append(filter);
        dialog.setFilters(filters);
        dialog.setDefaultFilter(filter);
        dialog.open(main.window(), null, (source, result, data) -> {
            try {
                final org.gnome.gio.File file = dialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    onPick.accept(Path.of(file.getPath()));
                }
            } catch (final Exception e) {
                // Dismissed
            }
        });
    }

    static void saveFile(final MainWindow main, final String title, final String initialName,
                         final Consumer<Path> onPick) {
        final FileDialog dialog = new FileDialog();
        dialog.setTitle(title);
        dialog.setInitialName(initialName);
        dialog.save(main.window(), null, (source, result, data) -> {
            try {
                final org.gnome.gio.File file = dialog.saveFinish(result);
                if (file != null && file.getPath() != null) {
                    onPick.accept(Path.of(file.getPath()));
                }
            } catch (final Exception e) {
                // Dismissed
            }
        });
    }

    static void selectFolder(final MainWindow main, final String title, final Path initial,
                             final Consumer<Path> onPick) {
        final FileDialog dialog = new FileDialog();
        dialog.setTitle(title);
        if (initial != null) {
            dialog.setInitialFolder(org.gnome.gio.File.newForPath(initial.toString()));
        }
        dialog.selectFolder(main.window(), null, (source, result, data) -> {
            try {
                final org.gnome.gio.File file = dialog.selectFolderFinish(result);
                if (file != null && file.getPath() != null) {
                    onPick.accept(Path.of(file.getPath()));
                }
            } catch (final Exception e) {
                // Dismissed
            }
        });
    }
}
