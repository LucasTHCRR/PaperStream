package net.paperstream.chunk;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

/**
 * Gives the chunk worker threads a lower CPU priority than the main thread (Linux nice value).
 *
 * On a server with 2 to 4 cores the workers that generate chunks compete with the main thread for
 * the same cores, and the operating system treats them all the same. With a higher nice value the
 * main thread always gets its time first, generation uses whatever is left. Nothing changes while
 * there are free cores.
 *
 * In this version the worker pool does not let us run code in the new thread, and Java 21 has no
 * stable native call API. So the worker threads are found by name in /proc/self/task and reniced
 * with the renice command, now and then again for workers started later.
 */
public final class WorkerPriority {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();

    private WorkerPriority() {
    }

    /** Called now and then from the main thread, the work itself runs in the background. */
    public static void reniceWorkers(final String brand) {
        if (!LINUX || PaperStreamConfig.workerNice <= 0 || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("PaperStream worker priority").start(() -> {
            try {
                apply(brand);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void apply(final String brand) {
        // thread names in /proc are cut to 15 characters
        final String full = brand + " Common Worker";
        final String prefix = full.substring(0, Math.min(15, full.length()));
        final int main = niceOf(Path.of("/proc/self/stat"));
        final int nice = Math.min(19, main + PaperStreamConfig.workerNice);
        int changed = 0;
        boolean failed = false;
        try (Stream<Path> tasks = Files.list(Path.of("/proc/self/task"))) {
            for (final Path task : (Iterable<Path>)tasks::iterator) {
                final String tid = task.getFileName().toString();
                if (DONE.contains(tid)) {
                    continue;
                }
                final String name;
                try {
                    name = Files.readString(task.resolve("comm")).trim();
                } catch (final IOException e) {
                    continue;
                }
                if (!name.startsWith(prefix)) {
                    continue;
                }
                if (renice(tid, nice)) {
                    DONE.add(tid);
                    ++changed;
                } else {
                    failed = true;
                }
            }
        } catch (final IOException e) {
            failed = true;
        }
        if (changed > 0 && REPORTED.compareAndSet(false, true)) {
            LOGGER.info("Chunk workers run with nice {} (main thread {}), the main thread gets the CPU first", nice, main);
        } else if (failed && REPORTED.compareAndSet(false, true)) {
            LOGGER.warn("Could not lower the chunk worker priority (renice failed), chunk workers keep the normal priority");
        }
    }

    private static int niceOf(final Path stat) {
        try {
            final String text = Files.readString(stat);
            // field 19 is the nice value, counted after the ") " that ends the thread name
            return Integer.parseInt(text.substring(text.lastIndexOf(") ") + 2).split(" ")[16]);
        } catch (final IOException | RuntimeException e) {
            return 0;
        }
    }

    private static boolean renice(final String tid, final int nice) {
        try {
            final Process process = new ProcessBuilder("renice", "-n", Integer.toString(nice), "-p", tid)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }
}
