package io.github.piper_yt.piperyt.net;

import org.schabi.newpipe.extractor.exceptions.AntiBotException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries requests that YouTube answered with a bot check ("Sign in to confirm you're not a
 * bot", a CAPTCHA, HTTP 429, a PO token rejection), backing off with jitter and optionally
 * switching the Mullvad exit in between.
 */
public final class BotWall {
    private static final long BASE_DELAY_MS = 3_000;
    private static final long MAX_DELAY_MS = 60_000;

    private BotWall() {
    }

    /** Thrown once the retries are used up, with a message meant for the UI. */
    public static final class BlockedException extends Exception {
        BlockedException(final int attempts, final Throwable cause) {
            super("YouTube is asking this exit to prove it isn't a bot (" + attempts
                    + (attempts == 1 ? " attempt" : " attempts") + "). "
                    + (NetworkSettings.rotateExit()
                    ? "Try again in a while."
                    : "Switch Mullvad to another server, or turn on exit rotation in "
                    + "Preferences."), cause);
        }
    }

    /**
     * Runs {@code work}, retrying it whole after a bot check, so a retry also gets a fresh
     * visitor ID and stream URLs for the current exit.
     */
    public static <T> T retrying(final Callable<T> work) throws Exception {
        final int retries = NetworkSettings.botRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                return work.call();
            } catch (final Exception e) {
                if (!isBotCheck(e)) {
                    throw e;
                }
                if (attempt >= retries) {
                    throw new BlockedException(attempt + 1, e);
                }
                System.err.println("Bot check (attempt " + (attempt + 1) + "): "
                        + e.getMessage());
                if (NetworkSettings.rotateExit()) {
                    Mullvad.rotate();
                }
                Thread.sleep(backoff(attempt));
            }
        }
    }

    /** Exponential delay with full jitter: random in [base·2ⁿ / 2, base·2ⁿ]. */
    static long backoff(final int attempt) {
        final long cap = Math.min(MAX_DELAY_MS, BASE_DELAY_MS << Math.min(attempt, 10));
        return ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);
    }

    /** Whether {@code t}, or anything that caused it, is YouTube refusing a suspected bot. */
    public static boolean isBotCheck(final Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AntiBotException || c instanceof ReCaptchaException
                    || c instanceof SabrAttestationException) {
                return true;
            }
            final String msg = c.getMessage() == null ? ""
                    : c.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("confirm you") && msg.contains("bot")
                    || msg.contains("po token") || msg.contains("potoken")) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
