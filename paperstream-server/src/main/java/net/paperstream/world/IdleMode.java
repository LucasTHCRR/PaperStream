package net.paperstream.world;

import net.minecraft.server.MinecraftServer;
import net.paperstream.PaperStreamConfig;

/**
 * With nobody online for a while, worlds only tick once per second.
 * Plugins, the scheduler and the network keep running every tick.
 */
public final class IdleMode {

    private static long emptySinceNanos = -1L;
    private static boolean idle;

    private IdleMode() {
    }

    /** Called once per server tick before the worlds tick. */
    public static void update(final MinecraftServer server) {
        if (!PaperStreamConfig.idleMode || server.getPlayerCount() > 0) {
            emptySinceNanos = -1L;
            idle = false;
            return;
        }
        final long now = System.nanoTime();
        if (emptySinceNanos < 0L) {
            emptySinceNanos = now;
        }
        idle = now - emptySinceNanos >= PaperStreamConfig.idleAfterSeconds * 1_000_000_000L;
    }

    public static boolean shouldTickWorlds() {
        return !idle || MinecraftServer.currentTick % 20 == 0;
    }

    public static boolean isIdle() {
        return idle;
    }
}
