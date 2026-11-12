package net.paperstream.chunks;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.paperstream.PaperStreamConfig;

public final class IdlePregen {

    private static final int MAX_REMEMBERED = 400_000;
    private static final double AHEAD_COS = 0.5D;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final Map<ServerLevel, LongOpenHashSet> DONE = new WeakHashMap<>();
    // Spieler, um die herum schon alles fertig ist, bis sie den Chunk wechseln
    private static final Map<UUID, Long> EXHAUSTED = new java.util.HashMap<>();
    private static double budget;
    private static int nextPlayer;

    private IdlePregen() {
    }

    public static long completed() {
        return COMPLETED.get();
    }

    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    public static void tick(final MinecraftServer server) {
        if (!PaperStreamConfig.idlePregen) {
            return;
        }
        budget = Math.min(budget + PaperStreamConfig.idlePregenMaxPerSecond / 20.0D, 4.0D);
        if (budget < 1.0D) {
            return;
        }
        if (EXHAUSTED.size() > 1000) {
            EXHAUSTED.clear();
        }
        final int max = PaperStreamConfig.idlePregenMaxConcurrent;
        for (final ServerLevel level : server.getAllLevels()) {
            if (IN_FLIGHT.get() >= max || budget < 1.0D) {
                return;
            }
            final List<ServerPlayer> players = level.players();
            if (players.isEmpty() || level.psTickTimes.average5s() > PaperStreamConfig.idlePregenMaxMspt) {
                continue;
            }
            final LongOpenHashSet done = DONE.computeIfAbsent(level, k -> new LongOpenHashSet());
            if (done.size() > MAX_REMEMBERED) {
                done.clear();
                EXHAUSTED.clear();
            }
            // reihum, damit kein Spieler alles bekommt
            for (int i = 0; i < players.size() && IN_FLIGHT.get() < max && budget >= 1.0D; ++i) {
                final ServerPlayer player = players.get((nextPlayer + i) % players.size());
                if (player.isSpectator()) {
                    continue;
                }
                schedule(level, player, done, max);
            }
            ++nextPlayer;
        }
    }

    private static void schedule(final ServerLevel level, final ServerPlayer player, final LongOpenHashSet done, final int max) {
        final int cx = player.chunkPosition().x;
        final int cz = player.chunkPosition().z;
        final long here = CoordinateUtils.getChunkKey(cx, cz);
        final Long exhaustedAt = EXHAUSTED.get(player.getUUID());
        if (exhaustedAt != null && exhaustedAt == here) {
            return;
        }
        final int view = player.getBukkitEntity().getViewDistance();
        final double yaw = Math.toRadians(player.getYRot());
        final double dirX = -Math.sin(yaw);
        final double dirZ = Math.cos(yaw);
        final int around = PaperStreamConfig.idlePregenRadius;
        final int ahead = Math.max(around, PaperStreamConfig.idlePregenAhead);
        final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();

        boolean any = false;
        for (int ring = view + 1; ring <= view + ahead; ++ring) {
            final boolean onlyAhead = ring > view + around;
            for (int dx = -ring; dx <= ring; ++dx) {
                final int step = Math.abs(dx) == ring ? 1 : 2 * ring;
                for (int dz = -ring; dz <= ring; dz += step) {
                    if (onlyAhead && (dx * dirX + dz * dirZ) < AHEAD_COS * Math.sqrt((double) (dx * dx + dz * dz))) {
                        continue;
                    }
                    final int x = cx + dx;
                    final int z = cz + dz;
                    if (!done.add(CoordinateUtils.getChunkKey(x, z))) {
                        continue;
                    }
                    any = true;
                    if (level.getChunkSource().getChunkAtIfLoadedImmediately(x, z) != null) {
                        continue;
                    }
                    IN_FLIGHT.incrementAndGet();
                    budget -= 1.0D;
                    // nur bis LIGHT: die ganze Worker-Arbeit, aber kein Hauptthread und keine Entities
                    scheduler.scheduleChunkLoad(x, z, ChunkStatus.LIGHT, true, Priority.LOWEST, chunk -> {
                        IN_FLIGHT.decrementAndGet();
                        COMPLETED.incrementAndGet();
                    });
                    if (IN_FLIGHT.get() >= max || budget < 1.0D) {
                        return;
                    }
                }
            }
        }
        if (!any) {
            EXHAUSTED.put(player.getUUID(), here);
        }
    }
}
