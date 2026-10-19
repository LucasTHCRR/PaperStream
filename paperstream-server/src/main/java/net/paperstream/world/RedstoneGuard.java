package net.paperstream.world;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

/**
 * Finds redstone clocks that run far faster than any farm and slows them down.
 *
 * Every scheduled tick of a redstone component (repeater, comparator, observer, torch, lamp,
 * dispenser, sculk sensor, ...) and every piston move counts for its chunk. A chunk that stays
 * above the limit for several seconds in a row is throttled: its components then only get a
 * small budget per second, everything above it is postponed. Nothing gets destroyed, and a
 * farm clock that pulses a few times per second never comes close to the limit.
 *
 * One instance per ServerLevel, main thread only.
 */
public final class RedstoneGuard {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static final class ChunkStats {
        int count;
        int hotSeconds;
        int throttledUntilSecond = Integer.MIN_VALUE;
        int budgetUsed;
        long lastPos;
        boolean announced;
    }

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<ChunkStats> chunks = new Long2ObjectOpenHashMap<>();
    private int second = Integer.MIN_VALUE;

    public long postponed;
    public int throttledChunks;

    public RedstoneGuard(final ServerLevel level) {
        this.level = level;
    }

    public static boolean isComponent(final Block block) {
        return block instanceof DiodeBlock
            || block instanceof ObserverBlock
            || block instanceof RedstoneTorchBlock
            || block instanceof DispenserBlock
            || block instanceof RedstoneLampBlock
            || block instanceof SculkSensorBlock
            || block instanceof CrafterBlock
            || block instanceof BasePressurePlateBlock
            || block instanceof ButtonBlock
            || block instanceof TargetBlock
            || block instanceof LightningRodBlock;
    }

    /**
     * Called for every scheduled block tick.
     *
     * @return false if the tick has to be postponed
     */
    public boolean allowTick(final BlockPos pos, final Block block) {
        if (!PaperStreamConfig.redstoneGuard || !isComponent(block)) {
            return true;
        }
        final ChunkStats stats = this.count(pos);
        if (stats.throttledUntilSecond < this.second) {
            return true;
        }
        if (stats.budgetUsed < PaperStreamConfig.redstoneThrottledPerSecond) {
            ++stats.budgetUsed;
            return true;
        }
        ++this.postponed;
        return false;
    }

    /** Called for every piston move. Only counted, a hot chunk is slowed down through its clock. */
    public void countPiston(final BlockPos pos) {
        if (PaperStreamConfig.redstoneGuard) {
            this.count(pos);
        }
    }

    /** Ticks until the start of the next second, used as delay for a postponed tick. */
    public int postponeDelay() {
        return 20 - (MinecraftServer.currentTick % 20);
    }

    private ChunkStats count(final BlockPos pos) {
        this.roll();
        final long key = ((long)(pos.getZ() >> 4) << 32) | ((pos.getX() >> 4) & 0xFFFFFFFFL);
        ChunkStats stats = this.chunks.get(key);
        if (stats == null) {
            stats = new ChunkStats();
            this.chunks.put(key, stats);
        }
        ++stats.count;
        stats.lastPos = pos.asLong();
        return stats;
    }

    private void roll() {
        final int now = MinecraftServer.currentTick / 20;
        if (now == this.second) {
            return;
        }
        final int gap = this.second == Integer.MIN_VALUE ? 1 : now - this.second;
        this.second = now;
        int throttled = 0;
        for (final ObjectIterator<Long2ObjectMap.Entry<ChunkStats>> it = this.chunks.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
            final Long2ObjectMap.Entry<ChunkStats> entry = it.next();
            final ChunkStats stats = entry.getValue();
            if (gap == 1 && stats.count > PaperStreamConfig.redstoneMaxPerSecond) {
                if (++stats.hotSeconds >= PaperStreamConfig.redstoneTriggerSeconds) {
                    stats.throttledUntilSecond = now + PaperStreamConfig.redstoneCooldownSeconds;
                    if (!stats.announced) {
                        stats.announced = true;
                        this.announce(stats);
                    }
                }
            } else {
                stats.hotSeconds = Math.max(0, stats.hotSeconds - gap);
            }
            stats.count = 0;
            stats.budgetUsed = 0;
            if (stats.throttledUntilSecond >= now) {
                ++throttled;
            } else if (stats.hotSeconds == 0) {
                it.remove();
            }
        }
        this.throttledChunks = throttled;
    }

    private void announce(final ChunkStats stats) {
        final BlockPos pos = BlockPos.of(stats.lastPos);
        final String where = this.level.getWorld().getName() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        LOGGER.warn("Redstone clock slowed down at {} ({} updates per second for {} seconds)",
            where, stats.count, stats.hotSeconds);
        if (!PaperStreamConfig.redstoneNotifyOps) {
            return;
        }
        final net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.text(
            "[PaperStream] Redstone clock slowed down at " + where + " (" + stats.count + "/s)",
            net.kyori.adventure.text.format.NamedTextColor.GOLD);
        for (final ServerPlayer player : this.level.getServer().getPlayerList().getPlayers()) {
            if (player.getBukkitEntity().hasPermission("paperstream.notify.redstone")) {
                player.getBukkitEntity().sendMessage(message);
            }
        }
    }
}
