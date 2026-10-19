package net.paperstream.chunk;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.paperstream.PaperStreamConfig;

/**
 * Per-player movement tracking for the chunk loader.
 * <p>
 * Supplies the direction used to order the load/generate/send queues and
 * places short-lived tickets on chunks the player is about to reach.
 * Only ever touched from the player's tick thread.
 */
public final class PlayerChunkPrefetch {

    public static final TicketType PREFETCH_TICKET = ChunkSystemTicketType.create(
        "paperstream:prefetch", Long::compareTo, 300L, TicketType.FLAG_LOADING
    );
    private static final Long TICKET_ID = 0L;

    // re-sort the queues at most this often when only the direction changed
    private static final int DIRECTION_RESORT_INTERVAL = 20;
    // cos(45 degrees)
    private static final double DIRECTION_RESORT_DOT = 0.707D;
    private static final double TELEPORT_DISTANCE_SQ = 64.0D * 64.0D;
    private static final double MOVING_SPEED = 0.15D;

    private final ServerPlayer player;

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private double velX;
    private double velZ;

    private double dirX;
    private double dirZ = 1.0D;
    // direction the queues are currently sorted by
    private double sortedDirX;
    private double sortedDirZ = 1.0D;
    private int ticksSinceSort;

    private double budget;
    private int ticksSinceClear;
    private final LongOpenHashSet prefetched = new LongOpenHashSet();
    private long totalPrefetched;

    public PlayerChunkPrefetch(final ServerPlayer player) {
        this.player = player;
    }

    public static void applyConfig() {
        ((ChunkSystemTicketType)(Object)PREFETCH_TICKET).moonrise$setTimeout(PaperStreamConfig.prefetchTicketTicks);
    }

    /**
     * Called once per tick before the chunk loader recalculates its queues.
     */
    public void tick(final ServerLevel world, final int loadViewDistance) {
        final double x = this.player.getX();
        final double z = this.player.getZ();
        if (Double.isNaN(this.lastX)) {
            this.lastX = x;
            this.lastZ = z;
        }
        final double dx = x - this.lastX;
        final double dz = z - this.lastZ;
        this.lastX = x;
        this.lastZ = z;

        if (dx * dx + dz * dz > TELEPORT_DISTANCE_SQ) {
            // teleport or world change, movement history is meaningless
            this.velX = 0.0D;
            this.velZ = 0.0D;
            this.prefetched.clear();
        } else {
            this.velX = this.velX * 0.7D + dx * 0.3D;
            this.velZ = this.velZ * 0.7D + dz * 0.3D;
        }

        final double speed = Math.sqrt(this.velX * this.velX + this.velZ * this.velZ);
        if (speed > MOVING_SPEED) {
            this.dirX = this.velX / speed;
            this.dirZ = this.velZ / speed;
        } else {
            final double yaw = Math.toRadians(this.player.getYRot());
            this.dirX = -Math.sin(yaw);
            this.dirZ = Math.cos(yaw);
        }
        ++this.ticksSinceSort;
        this.updateFastTravelSimulation(speed);

        if (++this.ticksSinceClear >= 600) {
            // tickets expire on their own, forget old entries so far-away chunks can be prefetched again
            this.ticksSinceClear = 0;
            this.prefetched.clear();
        }

        if (!PaperStreamConfig.prefetch || speed < PaperStreamConfig.prefetchMinSpeed || loadViewDistance < 0) {
            this.budget = 0.0D;
            return;
        }

        final double perTick = PaperStreamConfig.prefetchMaxPerSecond / 20.0D;
        this.budget = Math.min(this.budget + perTick, Math.max(1.0D, perTick * 20.0D));
        if (this.budget < 1.0D) {
            return;
        }

        final ChunkHolderManager holderManager = ((ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager;
        final int centerX = this.player.chunkPosition().x();
        final int centerZ = this.player.chunkPosition().z();
        final int lookahead = PaperStreamConfig.prefetchLookahead;

        // walk along the movement line, starting at the edge of the load view distance
        for (int step = 1; step <= lookahead && this.budget >= 1.0D; ++step) {
            final double dist = loadViewDistance + step;
            final int px = centerX + (int)Math.round(this.dirX * dist);
            final int pz = centerZ + (int)Math.round(this.dirZ * dist);
            // a 3 chunk wide corridor covers diagonal movement and small turns
            for (int ox = -1; ox <= 1 && this.budget >= 1.0D; ++ox) {
                for (int oz = -1; oz <= 1 && this.budget >= 1.0D; ++oz) {
                    final long key = CoordinateUtils.getChunkKey(px + ox, pz + oz);
                    if (!this.prefetched.add(key)) {
                        continue;
                    }
                    holderManager.addTicketAtLevel(PREFETCH_TICKET, key, ChunkHolderManager.FULL_LOADED_TICKET_LEVEL, TICKET_ID);
                    this.budget -= 1.0D;
                    ++this.totalPrefetched;
                }
            }
        }
    }

    private boolean simReduced;
    private int simSavedDistance;
    private int slowTicks;

    /**
     * Fast travel: while the player moves faster than the configured speed, chunks around them
     * are still loaded and sent at full view distance, but only a small area around them ticks.
     * The previous simulation distance comes back once the player has been slow for a while.
     */
    private void updateFastTravelSimulation(final double speed) {
        final RegionizedPlayerChunkLoader.ViewDistanceHolder holder =
            ((ChunkSystemServerPlayer)this.player).moonrise$getViewDistanceHolder();
        if (!PaperStreamConfig.fastTravelSim) {
            if (this.simReduced) {
                holder.setTickViewDistance(this.simSavedDistance);
                this.simReduced = false;
            }
            return;
        }
        if (speed >= PaperStreamConfig.fastTravelMinSpeed) {
            this.slowTicks = 0;
            if (!this.simReduced) {
                this.simSavedDistance = holder.getViewDistances().tickViewDistance();
                holder.setTickViewDistance(PaperStreamConfig.fastTravelSimDistance);
                this.simReduced = true;
            }
        } else if (this.simReduced && ++this.slowTicks >= PaperStreamConfig.fastTravelRestoreTicks) {
            holder.setTickViewDistance(this.simSavedDistance);
            this.simReduced = false;
        }
    }

    public boolean isSimulationReduced() {
        return this.simReduced;
    }

    /**
     * Returns true if the queues should be re-sorted because the player turned noticeably.
     * Marks the current direction as sorted when it returns true.
     */
    public boolean shouldResort() {
        if (!PaperStreamConfig.directionalLoading || this.ticksSinceSort < DIRECTION_RESORT_INTERVAL) {
            return false;
        }
        if (this.dirX * this.sortedDirX + this.dirZ * this.sortedDirZ >= DIRECTION_RESORT_DOT) {
            return false;
        }
        this.markSorted();
        return true;
    }

    public void markSorted() {
        this.sortedDirX = this.dirX;
        this.sortedDirZ = this.dirZ;
        this.ticksSinceSort = 0;
    }

    /**
     * Sort key for a chunk at the given offset from the player. Lower loads first.
     */
    public double priority(final int dx, final int dz) {
        final double dist = Math.sqrt((double)(dx * dx + dz * dz));
        if (dist <= PaperStreamConfig.directionalInnerRadius) {
            // close chunks always come first, in plain distance order
            return dist - 1.0E6D;
        }
        final double cos = (dx * this.sortedDirX + dz * this.sortedDirZ) / dist;
        return dist * (1.0D - PaperStreamConfig.directionalBias * cos);
    }

    /**
     * Factor for the concurrent chunk generation limit: fast players get more, unless the world lags.
     */
    public double generationBoost(final ServerLevel world) {
        if (PaperStreamConfig.genBoost <= 1.0D || world.psLimits.isLagging()) {
            return 1.0D;
        }
        final double speed = Math.sqrt(this.velX * this.velX + this.velZ * this.velZ);
        return speed >= PaperStreamConfig.prefetchMinSpeed ? PaperStreamConfig.genBoost : 1.0D;
    }

    public long getTotalPrefetched() {
        return this.totalPrefetched;
    }
}
