package net.paperstream.chunk;

import net.minecraft.server.MinecraftServer;
import net.paperstream.PaperStreamConfig;

/**
 * Caps the main thread time spent building chunk packets per tick, so fast chunk
 * generation does not turn into MSPT spikes. Every player still gets at least one
 * chunk per tick, the rest waits for the next tick.
 */
public final class ChunkSendBudget {

    private static int tick = Integer.MIN_VALUE;
    private static long spentNanos;

    private ChunkSendBudget() {
    }

    private static void roll() {
        final int now = MinecraftServer.currentTick;
        if (now != tick) {
            tick = now;
            spentNanos = 0L;
        }
    }

    public static boolean exhausted() {
        if (PaperStreamConfig.chunkSendBudgetMs <= 0.0D) {
            return false;
        }
        roll();
        return spentNanos >= (long)(PaperStreamConfig.chunkSendBudgetMs * 1_000_000.0D);
    }

    public static void spent(final long nanos) {
        roll();
        spentNanos += nanos;
    }
}
