package net.paperstream.chunk;

import com.mojang.logging.LogUtils;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

/**
 * Gives the chunk worker threads a lower CPU priority than the main thread (Linux nice value).
 *
 * On a server with 2 to 4 cores the workers that generate chunks compete with the main thread for
 * the same cores, and the operating system treats them all the same. With a higher nice value the
 * main thread always gets its time first, generation uses whatever is left. Nothing changes while
 * there are free cores.
 */
public final class WorkerPriority {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final MethodHandle SETPRIORITY = lookup("setpriority", 3);
    private static final MethodHandle GETPRIORITY = lookup("getpriority", 2);

    private WorkerPriority() {
    }

    private static MethodHandle lookup(final String name, final int args) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return null;
        }
        try {
            final Linker linker = Linker.nativeLinker();
            final FunctionDescriptor descriptor = args == 3
                ? FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                : FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
            return linker.defaultLookup().find(name).map(symbol -> linker.downcallHandle(symbol, descriptor)).orElse(null);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Wraps a worker's task so the thread lowers its own priority before it starts working.
     */
    public static Runnable wrap(final Runnable run) {
        return () -> {
            apply();
            run.run();
        };
    }

    private static void apply() {
        final int steps = PaperStreamConfig.workerNiceFromFile();
        if (steps <= 0) {
            return;
        }
        if (SETPRIORITY == null || GETPRIORITY == null) {
            if (REPORTED.compareAndSet(false, true)) {
                LOGGER.info("chunks.worker-priority only works on Linux, chunk workers keep the normal priority");
            }
            return;
        }
        try {
            // on Linux, PRIO_PROCESS (0) with who = 0 reads and changes only the calling thread.
            // Relative to the current value, so it also works when the whole server already runs niced.
            final int current = (int)GETPRIORITY.invokeExact(0, 0);
            final int nice = Math.min(19, current + steps);
            final int result = (int)SETPRIORITY.invokeExact(0, 0, nice);
            if (REPORTED.compareAndSet(false, true)) {
                if (result == 0) {
                    LOGGER.info("Chunk workers run with nice {} (main thread {}), the main thread gets the CPU first", nice, current);
                } else {
                    LOGGER.warn("Could not lower the chunk worker priority (setpriority returned {})", result);
                }
            }
        } catch (final Throwable t) {
            if (REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("Could not lower the chunk worker priority", t);
            }
        }
    }
}
