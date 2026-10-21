package net.paperstream.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.paperstream.PaperStreamConfig;

/**
 * Per world budgets that reset every tick: pathfinding, explosions and the lag state.
 * One instance per ServerLevel, main thread only.
 */
public final class WorldLimits {

    private final ServerLevel level;
    private int tick = Integer.MIN_VALUE;
    private int paths;
    private boolean lagging;
    private final Long2IntOpenHashMap explosionsPerChunk = new Long2IntOpenHashMap();

    public long pathsDenied;
    public long explosionsDenied;

    // item piles: counts of the previous tick decide, counts of this tick are collected meanwhile
    private int itemTick = Integer.MIN_VALUE;
    private Long2IntOpenHashMap itemsLastTick = new Long2IntOpenHashMap();
    private Long2IntOpenHashMap itemsThisTick = new Long2IntOpenHashMap();
    public int throttledItems;
    private int throttledItemsThisTick;

    public WorldLimits(final ServerLevel level) {
        this.level = level;
    }

    private void roll() {
        final int now = MinecraftServer.currentTick;
        if (now != this.tick) {
            this.tick = now;
            this.paths = 0;
            if (!this.explosionsPerChunk.isEmpty()) {
                this.explosionsPerChunk.clear();
            }
            this.lagging = PaperStreamConfig.lagProtection
                && this.level.psTickTimes.average5s() > PaperStreamConfig.lagProtectionMspt;
        }
    }

    /** True while this world averaged more than the configured MSPT over the last 5 seconds. */
    public boolean isLagging() {
        this.roll();
        return this.lagging;
    }

    /**
     * @param chasing true if the mob paths towards an entity (combat, following), those get double the budget
     */
    public boolean tryPath(final boolean chasing) {
        if (!PaperStreamConfig.pathBudget) {
            return true;
        }
        this.roll();
        int budget = PaperStreamConfig.pathBudgetPerTick;
        if (this.lagging) {
            budget /= 2;
        }
        if (chasing) {
            budget *= 2;
        }
        if (this.paths >= budget) {
            ++this.pathsDenied;
            return false;
        }
        ++this.paths;
        return true;
    }

    public boolean tryExplode(final double x, final double z) {
        if (PaperStreamConfig.explosionsPerChunk <= 0) {
            return true;
        }
        this.roll();
        final long key = ((long)((int)Math.floor(z) >> 4) << 32) | (((int)Math.floor(x) >> 4) & 0xFFFFFFFFL);
        if (this.explosionsPerChunk.addTo(key, 1) >= PaperStreamConfig.explosionsPerChunk) {
            ++this.explosionsDenied;
            return false;
        }
        return true;
    }

    /**
     * Item piles: more than entities.item-piles.max-per-block dropped items in one block tick only
     * every few ticks. Nothing is removed, picking up and hoppers work as before, age keeps counting.
     *
     * @return true if the item should skip this tick
     */
    public boolean isThrottledItem(final net.minecraft.world.entity.item.ItemEntity item) {
        if (!PaperStreamConfig.itemPiles) {
            return false;
        }
        final int now = MinecraftServer.currentTick;
        if (now != this.itemTick) {
            final Long2IntOpenHashMap last = this.itemsLastTick;
            this.itemsLastTick = this.itemsThisTick;
            this.itemsThisTick = last;
            this.itemsThisTick.clear();
            this.throttledItems = this.throttledItemsThisTick;
            this.throttledItemsThisTick = 0;
            this.itemTick = now;
        }
        final long key = item.blockPosition().asLong();
        this.itemsThisTick.addTo(key, 1);
        if (this.itemsLastTick.get(key) <= PaperStreamConfig.itemPilesMaxPerBlock) {
            return false;
        }
        if ((item.getId() + now) % PaperStreamConfig.itemPilesInterval == 0) {
            return false;
        }
        ++this.throttledItemsThisTick;
        return true;
    }

    /** Lag protection: while lagging, natural spawning only runs every second tick. */
    public boolean shouldSpawnThisTick() {
        return !this.isLagging() || (MinecraftServer.currentTick & 1) == 0;
    }
}
