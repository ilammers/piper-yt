package io.github.piper_yt.piperyt.ui;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.ResponseAppearance;
import org.gnome.glib.GLib;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * Shuts the computer down after a while, for falling asleep to a video: when time's up (or
 * the video ends, in that mode) playback pauses, and a dialog counts down a minute before
 * powering off so anyone still awake can cancel. One timer for the whole app; every header
 * button shows it.
 */
final class SleepTimer {
    private static final int[] MINUTES = {15, 30, 45, 60, 90, 120};
    private static final int WARNING_SECONDS = 60;

    private final MainWindow main;
    /** Buttons on screen; they register while mapped (java-gi frees unparented widgets). */
    private final List<Runnable> views = new ArrayList<>();
    /** Seconds left, or -1 when off or waiting for the video's end. */
    private int remaining = -1;
    private boolean atVideoEnd;
    /** Bumped to stop a running countdown. */
    private int token;

    SleepTimer(final MainWindow main) {
        this.main = main;
    }

    boolean isActive() {
        return remaining >= 0 || atVideoEnd;
    }

    /**
     * Called when a video ends. True when the timer was waiting for that and took over, so
     * autoplay and the queue shouldn't start another video.
     */
    boolean videoEnded() {
        if (!atVideoEnd) {
            return false;
        }
        expire();
        return true;
    }

    private void start(final int minutes) {
        stop();
        remaining = minutes * 60;
        final int t = token;
        GLib.timeoutAddSecondsOnce(1, () -> tick(t));
        changed();
        main.toast("Shutting down in " + minutes + " min");
    }

    private void startAtVideoEnd() {
        stop();
        atVideoEnd = true;
        changed();
        main.toast("Shutting down when this video ends");
    }

    private void stop() {
        token++;
        remaining = -1;
        atVideoEnd = false;
        changed();
    }

    private void tick(final int t) {
        if (t != token) {
            return;
        }
        remaining--;
        if (remaining <= 0) {
            expire();
            return;
        }
        changed();
        GLib.timeoutAddSecondsOnce(1, () -> tick(t));
    }

    /** Time's up: pause everything and give a last minute to cancel. */
    private void expire() {
        stop();
        main.pauseAllForSleep();
        final int t = token;
        final AlertDialog dialog = new AlertDialog("Shutting down",
                "The computer turns off in " + WARNING_SECONDS + " seconds.");
        dialog.addResponse("cancel", "Cancel");
        dialog.addResponse("now", "Shut Down Now");
        dialog.setResponseAppearance("now", ResponseAppearance.DESTRUCTIVE);
        dialog.setDefaultResponse("cancel");
        dialog.setCloseResponse("cancel");
        dialog.onResponse(null, response -> {
            if (t != token) {
                return;
            }
            token++;
            if ("now".equals(response)) {
                powerOff();
            } else {
                main.toast("Shutdown cancelled");
            }
        });
        dialog.present(main.window());
        countDown(t, dialog, WARNING_SECONDS);
    }

    private void countDown(final int t, final AlertDialog dialog, final int seconds) {
        if (t != token) {
            return;
        }
        if (seconds <= 0) {
            token++;
            dialog.forceClose();
            powerOff();
            return;
        }
        dialog.setBody("The computer turns off in " + seconds
                + (seconds == 1 ? " second." : " seconds."));
        GLib.timeoutAddSecondsOnce(1, () -> countDown(t, dialog, seconds - 1));
    }

    /** Through logind, which lets the active local user power off without a password. */
    private void powerOff() {
        Ui.background(() -> {
            try {
                final Process p = new ProcessBuilder("systemctl", "poweroff")
                        .redirectErrorStream(true).start();
                final String output = new String(p.getInputStream().readAllBytes()).strip();
                if (p.waitFor() != 0) {
                    Ui.runOnMain(() -> main.toast("Couldn't shut down: " + output));
                }
            } catch (final Exception e) {
                Ui.runOnMain(() -> main.toast("Couldn't shut down: " + e.getMessage()));
            }
        });
    }

    private void changed() {
        for (final Runnable view : List.copyOf(views)) {
            view.run();
        }
    }

    /** A header button: a clock that shows the time left while the timer runs. */
    Widget button() {
        final MenuButton button = new MenuButton();
        final Box content = new Box(Orientation.HORIZONTAL, 6);
        final org.gnome.gtk.Image icon = org.gnome.gtk.Image.fromIconName("alarm-symbolic");
        final Label left = new Label("");
        left.addCssClass("numeric");
        content.append(icon);
        content.append(left);
        button.setChild(content);

        final Box menu = new Box(Orientation.VERTICAL, 0);
        menu.addCssClass("menu-box");
        final Label heading = Ui.label("Shut down the computer", "heading");
        heading.setMarginTop(4);
        heading.setMarginBottom(6);
        menu.append(heading);
        final Popover popover = new Popover();
        for (final int minutes : MINUTES) {
            menu.append(item(popover, "In " + label(minutes), () -> start(minutes)));
        }
        menu.append(item(popover, "When this video ends", this::startAtVideoEnd));
        final Separator separator = new Separator(Orientation.HORIZONTAL);
        final Button off = item(popover, "Turn off timer", () -> {
            stop();
            main.toast("Sleep timer off");
        });
        menu.append(separator);
        menu.append(off);
        popover.setChild(menu);
        button.setPopover(popover);

        final Runnable update = () -> {
            off.setVisible(isActive());
            separator.setVisible(isActive());
            if (remaining >= 0) {
                left.setText(String.format("%d:%02d", remaining / 60, remaining % 60));
            } else {
                left.setText(atVideoEnd ? "End" : "");
            }
            left.setVisible(isActive());
            button.setTooltipText(isActive() ? "Sleep timer: shutting down "
                    + (atVideoEnd ? "when the video ends" : "in " + left.getText())
                    : "Sleep timer");
            if (isActive()) {
                button.addCssClass("accent");
            } else {
                button.removeCssClass("accent");
            }
        };
        update.run();
        button.onMap(() -> {
            update.run();
            views.add(update);
        });
        button.onUnmap(() -> views.remove(update));
        return button;
    }

    private static Button item(final Popover popover, final String text, final Runnable action) {
        final Button button = Button.withLabel(text);
        button.addCssClass("flat");
        button.addCssClass("menu-item");
        ((Label) button.getChild()).setXalign(0);
        button.onClicked(() -> {
            popover.popdown();
            action.run();
        });
        return button;
    }

    private static String label(final int minutes) {
        if (minutes < 60) {
            return minutes + " minutes";
        }
        return minutes % 60 == 0 ? (minutes / 60) + (minutes == 60 ? " hour" : " hours")
                : (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
