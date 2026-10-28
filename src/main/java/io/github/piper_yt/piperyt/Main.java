package io.github.piper_yt.piperyt;

import io.github.piper_yt.piperyt.db.Db;
import io.github.piper_yt.piperyt.db.Library;
import io.github.piper_yt.piperyt.db.Settings;
import io.github.piper_yt.piperyt.net.Extractor;
import io.github.piper_yt.piperyt.service.DownloadManager;
import io.github.piper_yt.piperyt.service.FeedRefresher;
import io.github.piper_yt.piperyt.ui.MainWindow;
import io.github.piper_yt.piperyt.ui.Ui;
import org.gnome.adw.Application;
import org.gnome.gio.ApplicationFlags;

public final class Main {
    public static final String APP_ID = "io.github.piper_yt.PiperYT";

    private Main() {
    }

    public static void main(final String[] args) {
        try {
            Db.open();
        } catch (final Exception e) {
            System.err.println("Can't open the library at " + Db.dataDir() + ": " + e.getMessage());
            System.exit(1);
        }
        Extractor.init();
        Extractor.applySponsorBlockCategories(Settings.sponsorBlockCategories());
        Settings.addListener(key -> {
            if (key.equals(Settings.SPONSORBLOCK_CATEGORIES.name())) {
                Extractor.applySponsorBlockCategories(Settings.sponsorBlockCategories());
            }
        });

        final Application app = new Application(APP_ID, ApplicationFlags.DEFAULT_FLAGS);
        app.onStartup(() -> {
            Ui.initMainThread();
            Ui.loadCss();
            Library.setDispatcher(Ui::runOnMain);
            DownloadManager.start();
            FeedRefresher.start();
        });
        app.onActivate(() -> {
            if (app.getActiveWindow() != null) {
                app.getActiveWindow().present();
            } else {
                new MainWindow(app).present();
            }
        });
        System.exit(app.run(args));
    }
}
