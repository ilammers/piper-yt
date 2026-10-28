package io.github.piper_yt.piperyt.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Switches the Mullvad exit through its CLI. This reconnects the whole system's tunnel, not
 * only the app's traffic, so it only runs when the user turned it on.
 */
public final class Mullvad {
    private static final long COOLDOWN_MS = 60_000;
    private static final long CONNECT_TIMEOUT_MS = 20_000;

    private static long lastRotation;

    private Mullvad() {
    }

    /**
     * Reconnects through another relay, at a random one of the configured locations if any are
     * set. Several requests failing at once rotate only once: a rotation within the last
     * minute counts. Returns whether the tunnel is connected afterwards.
     */
    public static synchronized boolean rotate() {
        if (System.currentTimeMillis() - lastRotation < COOLDOWN_MS) {
            return true;
        }
        try {
            final List<String> locations = locations(NetworkSettings.rotateLocations());
            if (!locations.isEmpty()) {
                final String pick = locations.get(
                        ThreadLocalRandom.current().nextInt(locations.size()));
                final List<String> cmd = new ArrayList<>(List.of("mullvad", "relay", "set",
                        "location"));
                cmd.addAll(Arrays.asList(pick.split("\\s+")));
                run(cmd);
            }
            run(List.of("mullvad", "reconnect"));
            lastRotation = System.currentTimeMillis();
            Network.resetConnections();
            return waitForConnected();
        } catch (final IOException e) {
            System.err.println("Mullvad rotation failed: " + e.getMessage());
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Splits {@code "se, ch, nl got"} into Mullvad location arguments. */
    static List<String> locations(final String setting) {
        return Arrays.stream(setting.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean waitForConnected() throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            if (run(List.of("mullvad", "status")).startsWith("Connected")) {
                return true;
            }
        }
        return false;
    }

    private static String run(final List<String> cmd) throws IOException, InterruptedException {
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String output = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException(String.join(" ", cmd) + ": " + output.strip());
        }
        return output.strip();
    }
}
