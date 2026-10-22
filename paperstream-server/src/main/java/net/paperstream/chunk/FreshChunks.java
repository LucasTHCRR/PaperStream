package net.paperstream.chunk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.paperstream.PaperStreamConfig;

/**
 * A chunk that was just loaded and has no player close by does not need to run its block entities,
 * leaf updates and natural spawning right away: a player flying past is gone again a few seconds
 * later. After the warm up, or as soon as a player comes close, the chunk ticks normally.
 */
public final class FreshChunks {

    private FreshChunks() {
    }

    /** True while the chunk is still warming up. Cached per chunk and tick, main thread only. */
    public static boolean isWarming(final LevelChunk chunk) {
        if (!PaperStreamConfig.freshChunks) {
            return false;
        }
        final int now = MinecraftServer.currentTick;
        if (now - chunk.psLoadedAt >= PaperStreamConfig.freshChunksWarmupTicks) {
            return false;
        }
        if (chunk.psWarmCheckedAt == now) {
            return chunk.psWarmResult;
        }
        chunk.psWarmCheckedAt = now;
        chunk.psWarmResult = !playerNear(chunk);
        return chunk.psWarmResult;
    }

    /** Ticks left until the chunk is warm, at least 1. */
    public static int ticksLeft(final LevelChunk chunk) {
        return Math.max(1, PaperStreamConfig.freshChunksWarmupTicks - (MinecraftServer.currentTick - chunk.psLoadedAt));
    }

    private static boolean playerNear(final LevelChunk chunk) {
        final int radius = PaperStreamConfig.freshChunksPlayerRadius;
        final int cx = chunk.getPos().x;
        final int cz = chunk.getPos().z;
        for (final ServerPlayer player : ((ServerLevel)chunk.getLevel()).players()) {
            if (Math.abs(player.chunkPosition().x - cx) <= radius && Math.abs(player.chunkPosition().z - cz) <= radius) {
                return true;
            }
        }
        return false;
    }
}
