package io.github.piper_yt.piperyt.ui;

import io.github.jwharm.javagi.interop.Interop;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The patched java-gi Interop is the one loaded, and attaching arenas starts no threads. */
class InteropPatchTest {
    // Counts every JVM thread; Cleaner threads are outside the caller's thread group
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    @Test
    void attachArenaReusesOneCleanerThread() {
        // Warm up: the shared Cleaner's own thread starts on first use
        Interop.attachArena(Arena.ofShared(), new Object());
        final int before = THREADS.getThreadCount();
        for (int i = 0; i < 500; i++) {
            Interop.attachArena(Arena.ofShared(), new Object());
        }
        final int grew = THREADS.getThreadCount() - before;
        assertTrue(grew < 10, "attachArena started " + grew + " threads; is java-gi's "
                + "unpatched Interop first on the classpath?");
    }
}
