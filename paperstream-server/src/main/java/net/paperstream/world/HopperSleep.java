package net.paperstream.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.paperstream.PaperStreamConfig;

// Hopper ohne Arbeit schlafen, bis Inventar, Nachbarblock, Redstone oder ein Item in der Naehe sie weckt
public final class HopperSleep {

    private HopperSleep() {
    }

    public static boolean enabled() {
        return PaperStreamConfig.sleepingHoppers;
    }

    public static void sleep(final HopperBlockEntity hopper, final long gameTime) {
        if (PaperStreamConfig.sleepingHoppers) {
            hopper.paperstreamSleepUntil = gameTime + PaperStreamConfig.sleepingHoppersMaxTicks;
        }
    }

    public static void blockEntityChanged(final Level level, final BlockPos pos) {
        if (!PaperStreamConfig.sleepingHoppers || !(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        wake(serverLevel, pos);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            wake(serverLevel, cursor.setWithOffset(pos, direction));
        }
    }

    // Saugbereich reicht bis zwei Bloecke ueber den Hopper und streift Nachbarbloecke
    public static void entityMoved(final Level level, final BlockPos pos) {
        if (!PaperStreamConfig.sleepingHoppers || !(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    wake(serverLevel, cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
                }
            }
        }
    }

    private static void wake(final ServerLevel level, final BlockPos pos) {
        final BlockState state = level.getBlockStateIfLoaded(pos);
        if (state != null && state.is(Blocks.HOPPER) && level.getBlockEntity(pos) instanceof HopperBlockEntity hopper) {
            hopper.paperstreamSleepUntil = 0L;
        }
    }
}
