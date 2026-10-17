package net.paperstream.entity;

import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.paperstream.PaperStreamConfig;

/**
 * Villagers that have not moved for a while (trading halls, breeders, 1x1 cells) run their
 * brain only every few ticks. Trading villagers and villagers that move tick normally,
 * so trades, restocking and job sites keep working, just with a short delay. At night and when
 * danger is near they always tick normally.
 */
public final class VillagerThrottle {

    private static final int STILL_TICKS_BEFORE_THROTTLE = 200;

    private VillagerThrottle() {
    }

    public static boolean shouldTickBrain(final Villager villager) {
        if (PaperStreamConfig.villagerBrainInterval <= 1) {
            return true;
        }
        final long pos = villager.blockPosition().asLong();
        if (pos != villager.psLastBlockPos) {
            villager.psLastBlockPos = pos;
            villager.psStillTicks = 0;
            return true;
        }
        if (villager.psStillTicks < STILL_TICKS_BEFORE_THROTTLE) {
            ++villager.psStillTicks;
            return true;
        }
        if (villager.isTrading() || villager.isSleeping() || villager.hurtTime > 0) {
            return true;
        }
        // never slow down reactions to danger: at night, with a monster nearby or while panicking
        // the villager has to find its bed or flee in time
        if (villager.level().isDarkOutside()
            || villager.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE)
            || villager.getBrain().hasMemoryValue(MemoryModuleType.IS_PANICKING)) {
            villager.psStillTicks = 0;
            return true;
        }
        return (villager.tickCount + villager.getId()) % PaperStreamConfig.villagerBrainInterval == 0;
    }
}
