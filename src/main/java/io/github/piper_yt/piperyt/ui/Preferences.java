package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.db.Backup;
import io.github.piper_yt.piperyt.db.History;
import io.github.piper_yt.piperyt.db.HomeStore;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.net.Network;
import io.github.piper_yt.piperyt.net.NetworkSettings;
import io.github.piper_yt.piperyt.net.NetworkSettings.Route;
import io.github.piper_yt.piperyt.service.HomeRefresher;
import io.github.piper_yt.piperyt.service.YtDlp;
import okhttp3.Request;
import okhttp3.Response;
import org.gnome.adw.ActionRow;
import org.gnome.adw.AlertDialog;
import org.gnome.adw.ComboRow;
import org.gnome.adw.EntryRow;
import org.gnome.adw.ExpanderRow;
import org.gnome.adw.PreferencesDialog;
import org.gnome.adw.PreferencesGroup;
import org.gnome.adw.PreferencesPage;
import org.gnome.adw.ResponseAppearance;
import org.gnome.adw.SpinRow;
import org.gnome.adw.SwitchRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Button;
import org.gnome.gtk.StringList;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The preferences dialog: appearance (themes), playback (SponsorBlock), history, feed and
 * homepage, downloads, data (import/export/wipe), and the network route.
 */
final class Preferences {
    private static final String EXIT_CHECK_URL = "https://am.i.mullvad.net/json";
    private static final Pattern JSON_STRING = Pattern.compile("\"(ip|city|country)\":\"([^\"]*)\"");

    private Preferences() {
    }

    static void present(final MainWindow main) {
        final PreferencesDialog dialog = new PreferencesDialog();
        dialog.setTitle("Preferences");
        dialog.setSearchEnabled(true);
        // Tabs share the width equally; the 640px default cuts off "Recommendations"
        dialog.setContentWidth(800);
        dialog.add(Appearance.page(main));
        dialog.add(page("Playback", "media-playback-start-symbolic", playerGroup(),
                sponsorBlockGroup()));
        dialog.add(page("History", "document-open-recent-symbolic", historyGroup(main)));
        dialog.add(page("Recommendations", "go-home-symbolic", feedGroup(), homeGroup(),
                blocklistGroup()));
        dialog.add(page("Downloads", "folder-download-symbolic", downloadGroup(main),
                downloadFormatGroup()));
        dialog.add(page("Data", "drive-harddisk-symbolic", importGroup(main, dialog),
                exportGroup(main), wipeGroup(main, dialog)));
        dialog.add(page("Network", "network-vpn-symbolic", connectionGroup(), botCheckGroup(),
                diagnosticsGroup()));
        dialog.present(main.window());
    }

    private static PreferencesPage page(final String title, final String icon,
                                        final PreferencesGroup... groups) {
        final PreferencesPage page = new PreferencesPage();
        page.setTitle(title);
        page.setIconName(icon);
        for (final PreferencesGroup g : groups) {
            page.add(g);
        }
        return page;
    }

    private static PreferencesGroup group(final String title, final String description) {
        final PreferencesGroup group = new PreferencesGroup();
        group.setTitle(title);
        if (description != null) {
            group.setDescription(description);
        }
        return group;
    }

    private static SwitchRow toggle(final String title, final String subtitle,
                                    final Settings.Key<Boolean> key) {
        final SwitchRow row = new SwitchRow();
        row.setTitle(title);
        if (subtitle != null) {
            row.setSubtitle(subtitle);
        }
        row.setActive(Settings.get(key));
        row.onNotify("active", pspec -> Settings.set(key, row.getActive()));
        return row;
    }

    private static SpinRow spin(final String title, final String subtitle, final int min,
                                final int max, final int step, final Settings.Key<Integer> key) {
        final SpinRow row = SpinRow.withRange(min, max, step);
        row.setTitle(title);
        if (subtitle != null) {
            row.setSubtitle(subtitle);
        }
        row.setValue(Settings.getInt(key));
        row.onNotify("value", pspec -> Settings.set(key, (int) row.getValue()));
        return row;
    }

    /** A combo over {@code values}, shown as {@code labels}, stored as the value. */
    private static ComboRow choice(final String title, final String subtitle,
                                   final List<String> values, final List<String> labels,
                                   final Settings.Key<String> key) {
        final ComboRow row = new ComboRow();
        row.setTitle(title);
        if (subtitle != null) {
            row.setSubtitle(subtitle);
        }
        row.setModel(new StringList(labels.toArray(String[]::new)));
        row.setSelected(Math.max(0, values.indexOf(Settings.getString(key))));
        row.onNotify("selected", pspec -> Settings.set(key,
                values.get((int) row.getSelected())));
        return row;
    }

    // Playback

    private static PreferencesGroup playerGroup() {
        final PreferencesGroup group = group("Player", null);
        group.add(toggle("Autoplay", "When a video ends and nothing is queued, play the first "
                + "video in Up next after a short countdown", Settings.AUTOPLAY));
        group.add(toggle("Mini player", "Keep a playing video going in a corner of the window "
                + "when you leave its page", Settings.MINI_PLAYER));
        return group;
    }

    private static PreferencesGroup sponsorBlockGroup() {
        final PreferencesGroup group = group("SponsorBlock", "Segments skipped during playback "
                + "and cut or marked in downloads. Segment lists come from sponsor.ajay.app, "
                + "looked up by a 4-character prefix of the video's hash.");
        final List<String> enabled = new ArrayList<>(Settings.sponsorBlockCategories());
        for (final Map.Entry<String, String> e : Settings.SPONSORBLOCK_LABELS.entrySet()) {
            final SwitchRow row = new SwitchRow();
            row.setTitle(e.getValue());
            row.setActive(enabled.contains(e.getKey()));
            row.onNotify("active", pspec -> {
                enabled.remove(e.getKey());
                if (row.getActive()) {
                    enabled.add(e.getKey());
                }
                Settings.setSponsorBlockCategories(enabled);
            });
            group.add(row);
        }
        return group;
    }

    // History

    private static PreferencesGroup historyGroup(final MainWindow main) {
        final PreferencesGroup group = group("History", "Stays on this computer. With watch "
                + "history off nothing is written, not even resume positions, and the homepage "
                + "only uses Favorites.");
        final SwitchRow watch = new SwitchRow();
        watch.setTitle("Save watch history");
        watch.setSubtitle("Powers resume, “hide watched” and the homepage's affinity");
        watch.setActive(History.enabled());
        final boolean[] reverting = {false};
        watch.onNotify("active", pspec -> {
            if (reverting[0]) {
                return;
            }
            if (watch.getActive()) {
                Settings.set(Settings.SAVE_WATCH_HISTORY, true);
                return;
            }
            final AlertDialog ask = new AlertDialog("Stop saving watch history?",
                    "From now on nothing is recorded. What about the history you have?");
            ask.addResponse("cancel", "Cancel");
            ask.addResponse("keep", "Keep existing history");
            ask.addResponse("delete", "Delete existing history");
            ask.setResponseAppearance("delete", ResponseAppearance.DESTRUCTIVE);
            ask.setCloseResponse("cancel");
            ask.setDefaultResponse("keep");
            ask.onResponse(null, response -> {
                switch (response) {
                    case "keep" -> Settings.set(Settings.SAVE_WATCH_HISTORY, false);
                    case "delete" -> {
                        Settings.set(Settings.SAVE_WATCH_HISTORY, false);
                        History.clear(0, false);
                        main.toast("Watch history deleted");
                    }
                    default -> {
                        reverting[0] = true;
                        watch.setActive(true);
                        reverting[0] = false;
                    }
                }
            });
            ask.present(watch);
        });
        group.add(watch);
        group.add(toggle("Save search history", "Shows recent searches under the search bar",
                Settings.SAVE_SEARCH_HISTORY));
        group.add(toggle("Resume playback", "Continue videos where you stopped (needs watch "
                + "history)", Settings.RESUME_PLAYBACK));

        final ActionRow clear = new ActionRow();
        clear.setTitle("Clear history");
        clear.setSubtitle("Last hour, 24 hours, 7 days or all time");
        final Button clearButton = Button.withLabel("Clear…");
        clearButton.addCssClass("destructive-action");
        clearButton.setValign(Align.CENTER);
        clearButton.onClicked(() -> Dialogs.clearHistory(main));
        clear.addSuffix(clearButton);
        group.add(clear);
        return group;
    }

    // Feed & Home

    private static PreferencesGroup feedGroup() {
        final PreferencesGroup group = group("Feed", "Favorites' uploads come from their RSS "
                + "feeds, one channel at a time in shuffled order, with a random pause before "
                + "each and a longer one between batches.");
        group.add(spin("Refresh every (minutes)", "In the background, while the app is open",
                30, 1440, 15, Settings.FEED_INTERVAL_MIN));
        group.add(spin("Shortest pause between channels (s)", null, 1, 60, 1,
                Settings.FEED_GAP_MIN_S));
        group.add(spin("Longest pause between channels (s)", null, 2, 120, 1,
                Settings.FEED_GAP_MAX_S));
        group.add(spin("Channels per batch", null, 1, 100, 1, Settings.FEED_BATCH_SIZE));
        group.add(spin("Pause between batches (s)", null, 0, 1800, 10,
                Settings.FEED_BATCH_PAUSE_S));
        final SwitchRow rotate = new SwitchRow();
        rotate.setTitle("Switch Mullvad server between batches");
        rotate.setSubtitle("Reconnects the system's Mullvad tunnel, so other apps reconnect too");
        rotate.setActive(NetworkSettings.rotateBetweenBatches());
        rotate.onNotify("active", pspec ->
                NetworkSettings.setRotateBetweenBatches(rotate.getActive()));
        group.add(rotate);
        group.add(toggle("Detect livestreams", "RSS can't tell livestreams from uploads; this "
                + "also loads each channel's Live tab, doubling the feed's requests",
                Settings.FEED_DETECT_LIVE));
        return group;
    }

    private static PreferencesGroup homeGroup() {
        final PreferencesGroup group = group("Homepage", "Recommendations are scored on this "
                + "computer from Favorites, related videos and (if on) your watch history.");
        final SwitchRow discover = toggle("Discover new channels", "Also fetch uploads of "
                + "channels that keep showing up in related videos", Settings.HOME_DISCOVER);
        discover.onNotify("active", pspec -> HomeRefresher.rescore());
        group.add(discover);
        final SwitchRow shorts = toggle("Hide shorts", null, Settings.HOME_HIDE_SHORTS);
        shorts.onNotify("active", pspec -> HomeRefresher.rescore());
        group.add(shorts);
        group.add(spin("Refresh after (minutes)", "Opening Home refreshes it when it's older",
                15, 1440, 15, Settings.HOME_TTL_MIN));
        return group;
    }

    private static PreferencesGroup blocklistGroup() {
        final PreferencesGroup group = group("Blocked", "“Don't recommend channel” and “Not "
                + "interested” from Home. Clearing history can clear these too.");
        final ExpanderRow channels = new ExpanderRow();
        final ActionRow hidden = new ActionRow();
        final Button clearHidden = Button.withLabel("Clear");
        clearHidden.setValign(Align.CENTER);
        hidden.addSuffix(clearHidden);
        final Runnable fill = () -> {
            final List<HomeStore.Blocked> blocked = HomeStore.blocked();
            channels.setTitle("Blocked channels");
            channels.setSubtitle(blocked.isEmpty() ? "None" : blocked.size() + " channels");
            hidden.setTitle("Hidden videos");
            hidden.setSubtitle(HomeStore.hiddenCount() + " videos");
        };
        fill.run();
        clearHidden.onClicked(() -> {
            HomeStore.clearHidden();
            fill.run();
            HomeRefresher.rescore();
        });
        for (final HomeStore.Blocked b : HomeStore.blocked()) {
            final ActionRow row = new ActionRow();
            row.setTitle(Ui.markupSafe(b.name().isBlank() ? b.channelUrl() : b.name()));
            final Button unblock = Button.withLabel("Unblock");
            unblock.setValign(Align.CENTER);
            unblock.onClicked(() -> {
                HomeStore.unblock(b.channelUrl());
                row.setVisible(false);
                fill.run();
                HomeRefresher.rescore();
            });
            row.addSuffix(unblock);
            channels.addRow(row);
        }
        group.add(channels);
        group.add(hidden);
        return group;
    }

    // Downloads

    private static PreferencesGroup downloadGroup(final MainWindow main) {
        final PreferencesGroup group = group("Downloads", "yt-dlp runs with no cookies, no "
                + "cache and no config files, over the same route as the app.");
        final ActionRow folder = new ActionRow();
        folder.setTitle("Folder");
        folder.setSubtitle(Settings.downloadDir().toString());
        final Button choose = Button.withLabel("Choose…");
        choose.setValign(Align.CENTER);
        choose.onClicked(() -> Dialogs.selectFolder(main, "Download folder",
                Settings.downloadDir(), path -> {
                    Settings.set(Settings.DOWNLOAD_DIR, path.toString());
                    folder.setSubtitle(path.toString());
                }));
        folder.addSuffix(choose);
        group.add(folder);
        final List<String> presets = Arrays.stream(YtDlp.Preset.values()).map(p -> p.id).toList();
        group.add(choice("Default quality", null, presets,
                Arrays.stream(YtDlp.Preset.values()).map(p -> p.label).toList(),
                Settings.DOWNLOAD_PRESET));
        group.add(spin("Downloads at once", null, 1, 6, 1, Settings.DOWNLOAD_CONCURRENCY));
        group.add(choice("SponsorBlock", "Uses the categories under Playback",
                List.of("off", "mark", "remove"),
                List.of("Off", "Mark as chapters", "Remove segments"),
                Settings.DOWNLOAD_SPONSORBLOCK));
        return group;
    }

    private static PreferencesGroup downloadFormatGroup() {
        final PreferencesGroup group = group("Format", null);
        group.add(choice("Default video file type", null,
                YtDlp.VIDEO_TYPES.stream().map(YtDlp.FileType::id).toList(),
                YtDlp.VIDEO_TYPES.stream().map(YtDlp.FileType::label).toList(),
                Settings.DOWNLOAD_CONTAINER));
        group.add(choice("Preferred video codec", null, List.of("any", "vp9", "avc", "av01"),
                List.of("Any", "VP9", "H.264 (AVC)", "AV1"), Settings.DOWNLOAD_CODEC));
        group.add(choice("Default audio file type", null,
                YtDlp.AUDIO_TYPES.stream().map(YtDlp.FileType::id).toList(),
                YtDlp.AUDIO_TYPES.stream().map(YtDlp.FileType::label).toList(),
                Settings.DOWNLOAD_AUDIO_FORMAT));
        final SwitchRow subs = toggle("Subtitles", "Download and embed subtitles",
                Settings.DOWNLOAD_SUBTITLES);
        group.add(subs);
        final EntryRow langs = new EntryRow();
        langs.setTitle("Subtitle languages (yt-dlp syntax, e.g. en.*,de)");
        langs.setText(Settings.getString(Settings.DOWNLOAD_SUB_LANGS));
        langs.setShowApplyButton(true);
        langs.onApply(() -> Settings.set(Settings.DOWNLOAD_SUB_LANGS, langs.getText().strip()));
        group.add(langs);
        return group;
    }

    // Data

    private static PreferencesGroup importGroup(final MainWindow main,
                                                final PreferencesDialog dialog) {
        final PreferencesGroup group = group("Import", "Merges into what's here: nothing is "
                + "deleted. History is only imported while saving it is on.");
        group.add(actionRow("PipePipe or NewPipe backup", "The .zip from the app's "
                + "Settings → Backup and restore → Export", "Import…", false, () ->
                Dialogs.openFile(main, "Import backup", "Backup (.zip)",
                        new String[]{"*.zip"}, path -> {
                            dialog.close();
                            main.toast("Importing…");
                            Ui.async(() -> Backup.importZip(path),
                                    report -> main.toast(report.summary()),
                                    error -> main.toast("Import failed: "
                                            + Ui.errorMessage(error)));
                        })));
        group.add(actionRow("JSON", "This app's JSON export, or a PipePipe/NewPipe "
                + "subscriptions export", "Import…", false, () ->
                Dialogs.openFile(main, "Import JSON", "JSON", new String[]{"*.json"}, path -> {
                    dialog.close();
                    Ui.async(() -> Backup.importJson(path),
                            report -> main.toast(report.summary()),
                            error -> main.toast("Import failed: " + Ui.errorMessage(error)));
                })));
        return group;
    }

    private static PreferencesGroup exportGroup(final MainWindow main) {
        final PreferencesGroup group = group("Export", null);
        final String date = LocalDate.now().toString();
        group.add(actionRow("PipePipe backup", "Favorites, playlists, history and searches, "
                + "ready for PipePipe's “Import database”", "Export…", false, () ->
                Dialogs.saveFile(main, "Export backup", "PipePipeData-" + date + ".zip",
                        path -> Ui.async(() -> {
                            Backup.exportZip(path);
                            return null;
                        }, done -> main.toast("Exported to " + path.getFileName()),
                                error -> main.toast("Export failed: "
                                        + Ui.errorMessage(error))))));
        group.add(actionRow("NewPipe backup", "The same, in upstream NewPipe's format, for its "
                + "“Import database”", "Export…", false, () ->
                Dialogs.saveFile(main, "Export backup", "NewPipeData-" + date + ".zip",
                        path -> Ui.async(() -> {
                            Backup.exportZip(path, Backup.Flavor.NEWPIPE);
                            return null;
                        }, done -> main.toast("Exported to " + path.getFileName()),
                                error -> main.toast("Export failed: "
                                        + Ui.errorMessage(error))))));
        group.add(actionRow("JSON", "Everything, settings included, as plain JSON", "Export…",
                false, () -> Dialogs.saveFile(main, "Export JSON",
                        "piper-yt-" + date + ".json", path -> Ui.async(() -> {
                            Backup.exportJson(path);
                            return null;
                        }, done -> main.toast("Exported to " + path.getFileName()),
                                error -> main.toast("Export failed: "
                                        + Ui.errorMessage(error))))));
        return group;
    }

    private static PreferencesGroup wipeGroup(final MainWindow main,
                                              final PreferencesDialog dialog) {
        final PreferencesGroup group = group("Wipe", null);
        group.add(actionRow("Wipe all local data", "Favorites, feed, playlists, history, "
                + "download list, caches and these settings", "Wipe…", true, () -> {
            final AlertDialog ask = new AlertDialog("Wipe all local data?",
                    "Everything this app stored is deleted and it starts empty. Downloaded "
                            + "files stay unless you delete them too. This can't be undone.");
            ask.addResponse("cancel", "Cancel");
            ask.addResponse("wipe", "Wipe");
            ask.addResponse("files", "Wipe and delete downloads");
            ask.setResponseAppearance("wipe", ResponseAppearance.DESTRUCTIVE);
            ask.setResponseAppearance("files", ResponseAppearance.DESTRUCTIVE);
            ask.setDefaultResponse("cancel");
            ask.setCloseResponse("cancel");
            ask.onResponse(null, response -> {
                if ("cancel".equals(response)) {
                    return;
                }
                dialog.close();
                Ui.async(() -> {
                    Backup.wipe("files".equals(response));
                    return null;
                }, done -> main.toast("All local data wiped"),
                        error -> main.toast("Wipe failed: " + Ui.errorMessage(error)));
            });
            ask.present(dialog);
        }));
        return group;
    }

    private static ActionRow actionRow(final String title, final String subtitle,
                                       final String button, final boolean destructive,
                                       final Runnable action) {
        final ActionRow row = new ActionRow();
        row.setTitle(title);
        row.setSubtitle(subtitle);
        final Button b = Button.withLabel(button);
        b.setValign(Align.CENTER);
        if (destructive) {
            b.addCssClass("destructive-action");
        }
        b.onClicked(action::run);
        row.addSuffix(b);
        return row;
    }

    // Network (the privacy route; unchanged behavior)

    private static PreferencesGroup connectionGroup() {
        final PreferencesGroup group = new PreferencesGroup();
        group.setTitle("Connection");
        group.setDescription("Searches, videos, thumbnails, feeds, downloads and SponsorBlock "
                + "all go out this way. Changes apply to new connections; restart playback to "
                + "move a video.");

        final ComboRow route = new ComboRow();
        route.setTitle("Route");
        route.setModel(new StringList(Arrays.stream(Route.values())
                .map(r -> r.label).toArray(String[]::new)));
        route.setSelected(NetworkSettings.route().ordinal());

        final EntryRow socks = new EntryRow();
        socks.setTitle("SOCKS5 proxy (host:port)");
        socks.setText(NetworkSettings.socksAddress());
        socks.setShowApplyButton(true);
        socks.onApply(() -> {
            try {
                NetworkSettings.setSocksAddress(socks.getText());
                socks.removeCssClass("error");
            } catch (final IllegalArgumentException e) {
                socks.addCssClass("error");
            }
        });

        final Runnable describeRoute = () -> {
            final boolean proxied = NetworkSettings.route() == Route.SOCKS5;
            route.setSubtitle(proxied
                    ? "Fails closed: nothing is sent if the proxy is down, and hostnames are"
                    + " resolved by the proxy. Mullvad's is " + NetworkSettings.DEFAULT_SOCKS
                    : "Direct connections, relying on the VPN being the default route");
            socks.setSensitive(proxied);
        };
        describeRoute.run();
        route.onNotify("selected", pspec -> {
            NetworkSettings.setRoute(Route.values()[route.getSelected()]);
            describeRoute.run();
        });

        final ActionRow exit = new ActionRow();
        exit.setTitle("Exit");
        exit.setSubtitle("Asks am.i.mullvad.net which IP YouTube sees");
        final Button check = Button.withLabel("Check");
        check.setValign(Align.CENTER);
        check.onClicked(() -> {
            check.setSensitive(false);
            exit.setSubtitle("Checking…");
            Ui.async(Preferences::checkExit, result -> {
                exit.setSubtitle(result);
                check.setSensitive(true);
            }, error -> {
                exit.setSubtitle(Ui.errorMessage(error));
                check.setSensitive(true);
            });
        });
        exit.addSuffix(check);

        group.add(route);
        group.add(socks);
        group.add(exit);
        return group;
    }

    private static PreferencesGroup botCheckGroup() {
        final PreferencesGroup group = new PreferencesGroup();
        group.setTitle("Bot checks");
        group.setDescription("When YouTube asks to confirm you're not a bot, retry after a"
                + " random, growing delay. Background refreshes then pause for 15 minutes.");

        final SpinRow retries = SpinRow.withRange(0, NetworkSettings.MAX_RETRIES, 1);
        retries.setTitle("Retries");
        retries.setValue(NetworkSettings.botRetries());
        retries.onNotify("value", pspec ->
                NetworkSettings.setBotRetries((int) retries.getValue()));

        final SwitchRow rotate = new SwitchRow();
        rotate.setTitle("Switch Mullvad server before retrying");
        rotate.setSubtitle("Reconnects the system's Mullvad tunnel, so other apps"
                + " reconnect too");
        rotate.setActive(NetworkSettings.rotateExit());

        final EntryRow locations = new EntryRow();
        locations.setTitle("Locations to pick from, e.g. \"se, ch, nl ams\" (blank: current)");
        locations.setText(NetworkSettings.rotateLocations());
        locations.setShowApplyButton(true);
        locations.onApply(() -> NetworkSettings.setRotateLocations(locations.getText()));
        locations.setSensitive(rotate.getActive());

        rotate.onNotify("active", pspec -> {
            NetworkSettings.setRotateExit(rotate.getActive());
            locations.setSensitive(rotate.getActive());
        });

        group.add(retries);
        group.add(rotate);
        group.add(locations);
        return group;
    }

    private static PreferencesGroup diagnosticsGroup() {
        final PreferencesGroup group = new PreferencesGroup();
        group.setTitle("Diagnostics");

        final SwitchRow log = new SwitchRow();
        log.setTitle("Log connections");
        log.setSubtitle("Print every outbound host, and the route it took, to the terminal");
        log.setActive(NetworkSettings.logConnections());
        log.onNotify("active", pspec -> NetworkSettings.setLogConnections(log.getActive()));

        group.add(log);
        return group;
    }

    /** "1.2.3.4 · City, Country", fetched through the app's own route. */
    private static String checkExit() throws Exception {
        final Request request = new Request.Builder().url(EXIT_CHECK_URL)
                .header("User-Agent", Network.USER_AGENT).build();
        try (Response response = Network.client().newCall(request).execute()) {
            final String body = response.body().string();
            String ip = "?";
            String city = "";
            String country = "";
            final Matcher m = JSON_STRING.matcher(body);
            while (m.find()) {
                switch (m.group(1)) {
                    case "ip" -> ip = m.group(2);
                    case "city" -> city = m.group(2);
                    default -> country = m.group(2);
                }
            }
            final boolean mullvad = body.contains("\"mullvad_exit_ip\":true");
            return ip + " · " + city + (city.isEmpty() ? "" : ", ") + country
                    + (mullvad ? " · Mullvad" : " · NOT a Mullvad exit");
        }
    }
}
