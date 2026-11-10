package net.paperstream.chunks;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.command.CommandSender;

public final class PaperChunks {

    private static long tasksInTick;
    private static long tickNanosTotal;
    private static long maxTickNanos;
    private static long statsSinceNanos = System.nanoTime();

    private PaperChunks() {
    }

    public static void runRecentChunkTasks(final ChunkTaskScheduler scheduler) {
        final long start = System.nanoTime();
        final long before = scheduler.paperstream$pendingMainThreadTasks();
        scheduler.executeAllRecentlyQueuedMainThreadTasks();
        final long took = System.nanoTime() - start;
        tasksInTick += Math.max(0L, before - scheduler.paperstream$pendingMainThreadTasks());
        tickNanosTotal += took;
        if (took > maxTickNanos) {
            maxTickNanos = took;
        }
    }

    public static void stats(final CommandSender sender) {
        final double seconds = Math.max(1.0D, (System.nanoTime() - statsSinceNanos) / 1.0E9D);
        sender.sendMessage(String.format(Locale.ROOT, "PaperChunks (last %.0f s):", seconds));
        sender.sendMessage(String.format(Locale.ROOT, "  chunk tasks at tick end: %d (%.1f/s), %.1f ms total, longest %.2f ms",
            tasksInTick, tasksInTick / seconds, tickNanosTotal / 1.0E6D, maxTickNanos / 1.0E6D));
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            final ChunkTaskScheduler scheduler = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();
            sender.sendMessage(String.format(Locale.ROOT, "  %s: %d loaded chunks, %d waiting main thread tasks",
                level.getWorld().getName(), level.getChunkSource().getLoadedChunksCount(), scheduler.paperstream$pendingMainThreadTasks()));
        }
    }

    public static void resetStats() {
        tasksInTick = 0L;
        tickNanosTotal = 0L;
        maxTickNanos = 0L;
        statsSinceNanos = System.nanoTime();
    }
}
