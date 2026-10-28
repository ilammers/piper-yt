package io.github.piper_yt.piperyt.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Spreads background fetches out so they never arrive as one burst: jobs run one at a time,
 * in shuffled order, with a random gap before each, in batches with a longer random pause
 * (and, if the user turned it on, a new Mullvad exit) between them. Each job goes through
 * {@link BotWall#retrying}; a job that still hits the bot wall pauses all paced work for a
 * while, so the feed refresh and the homepage don't keep knocking.
 *
 * <p>Only one paced run is active at a time; a second waits for the first. Interactive fetches
 * (opening a page) don't go through here: they are one request the user asked for.
 */
public final class Pacer {
    /** After a bot wall, paced work stops for this long. */
    static final long COOL_DOWN_MS = 15 * 60_000;

    private static final Object RUN_LOCK = new Object();
    private static volatile long blockedUntil;

    /** Timing of one paced run, in seconds. */
    public record Plan(int minGapS, int maxGapS, int batchSize, int batchPauseS,
                      boolean shuffle) {
        public Plan {
            minGapS = Math.max(0, minGapS);
            maxGapS = Math.max(minGapS, maxGapS);
            batchSize = Math.max(1, batchSize);
            batchPauseS = Math.max(0, batchPauseS);
        }
    }

    /** One fetch. */
    @FunctionalInterface
    public interface Job<T> {
        void run(T item) throws Exception;
    }

    /** Hears how a run is going; called on the pacing thread. */
    public interface Progress<T> {
        default void started(final T item, final int index, final int total) {
        }

        default void failed(final T item, final Exception e) {
        }
    }

    /** How a run ended. */
    public record Result(int done, int failed, boolean blocked, boolean cancelled) {
    }

    private Pacer() {
    }

    /** Whether paced work is on hold after a bot wall. */
    public static boolean coolingDown() {
        return System.currentTimeMillis() < blockedUntil;
    }

    public static long coolDownEndsAt() {
        return blockedUntil;
    }

    /**
     * Runs {@code job} for each item, paced by {@code plan}, on the calling (background)
     * thread. Stops early when {@code cancelled} says so or when a job is bot-walled.
     */
    public static <T> Result run(final List<T> items, final Plan plan, final Job<T> job,
                                 final Progress<T> progress, final BooleanSupplier cancelled)
            throws InterruptedException {
        synchronized (RUN_LOCK) {
            if (coolingDown()) {
                return new Result(0, 0, true, false);
            }
            final List<T> order = new ArrayList<>(items);
            if (plan.shuffle()) {
                Collections.shuffle(order);
            }
            int done = 0;
            int failed = 0;
            for (int i = 0; i < order.size(); i++) {
                if (cancelled.getAsBoolean()) {
                    return new Result(done, failed, false, true);
                }
                if (i > 0 && i % plan.batchSize() == 0) {
                    sleepSeconds(plan.batchPauseS() / 2, plan.batchPauseS(), cancelled);
                    if (NetworkSettings.rotateBetweenBatches()) {
                        Mullvad.rotate();
                    }
                } else if (i > 0) {
                    sleepSeconds(plan.minGapS(), plan.maxGapS(), cancelled);
                }
                if (cancelled.getAsBoolean()) {
                    return new Result(done, failed, false, true);
                }
                final T item = order.get(i);
                progress.started(item, i, order.size());
                try {
                    BotWall.retrying(() -> {
                        job.run(item);
                        return null;
                    });
                    done++;
                } catch (final BotWall.BlockedException e) {
                    blockedUntil = System.currentTimeMillis() + COOL_DOWN_MS;
                    progress.failed(item, e);
                    return new Result(done, failed + 1, true, false);
                } catch (final InterruptedException e) {
                    throw e;
                } catch (final Exception e) {
                    failed++;
                    progress.failed(item, e);
                }
            }
            return new Result(done, failed, false, false);
        }
    }

    /** Sleeps a random time in [min, max] seconds: the gap between page fetches. */
    public static void sleepJitter(final int minS, final int maxS) throws InterruptedException {
        sleepSeconds(minS, maxS, () -> false);
    }

    /** Sleeps a random time in [min, max] seconds, waking early if cancelled. */
    static void sleepSeconds(final int min, final int max, final BooleanSupplier cancelled)
            throws InterruptedException {
        final long total = max <= 0 ? 0
                : ThreadLocalRandom.current().nextLong(min * 1000L, max * 1000L + 1);
        final long end = System.currentTimeMillis() + total;
        while (System.currentTimeMillis() < end && !cancelled.getAsBoolean()) {
            Thread.sleep(Math.min(500, Math.max(1, end - System.currentTimeMillis())));
        }
    }
}
