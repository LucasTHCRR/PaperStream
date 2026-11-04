package net.paperstream.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.paperstream.PaperStreamConfig;

/**
 * Lets idle mobs far away from every player tick less often.
 * <p>
 * Runs after Paper's entity activation range decided the entity is active, so it only
 * thins out entities inside the activation range. Anything that is fighting, riding,
 * leashed, hurt or in the air keeps ticking every tick.
 */
public final class EntityTickThrottle {

    private static final int DISTANCE_RECHECK_TICKS = 20;

    private EntityTickThrottle() {
    }

    /**
     * @return false if the entity should skip this tick
     */
    public static boolean shouldTick(final Entity entity) {
        if (!PaperStreamConfig.entityThrottle || !(entity instanceof final Mob mob)) {
            return true;
        }
        if (!isIdle(mob)) {
            mob.psThrottleInterval = 1;
            return true;
        }

        final int now = MinecraftServer.currentTick;
        if (now >= mob.psThrottleRecheckTick) {
            mob.psThrottleRecheckTick = now + DISTANCE_RECHECK_TICKS;
            mob.psThrottleInterval = computeInterval(mob);
        }

        final int interval = mob.psThrottleInterval;
        return interval <= 1 || (now + mob.getId()) % interval == 0;
    }

    private static int computeInterval(final Mob mob) {
        final Player nearest = mob.level().getNearestPlayer(mob, -1.0D);
        if (nearest == null) {
            return 1;
        }
        final double distSq = nearest.distanceToSqr(mob);
        final double near = PaperStreamConfig.entityThrottleNear;
        final double far = PaperStreamConfig.entityThrottleFar;

        int interval;
        if (distSq >= far * far) {
            interval = 4;
        } else if (distSq >= near * near) {
            interval = 2;
        } else {
            return 1;
        }

        // per world lag protection: the slower this world ticks, the less often far mobs tick
        if (mob.level() instanceof final ServerLevel level
            && level.psTickTimes.average5s() > PaperStreamConfig.entityThrottleLagMspt) {
            interval *= 2;
        }
        return interval;
    }

    private static boolean isIdle(final Mob mob) {
        if (mob.getTarget() != null || mob.isAggressive() || mob.hurtTime > 0) {
            return false;
        }
        if (mob.isPassenger() || mob.isVehicle() || mob.isLeashed()) {
            return false;
        }
        if (!mob.onGround() || mob.isInWater() || mob.isInLava()) {
            return false;
        }
        // Villager nie drosseln: Auffüllen, Jobblock und Bett hängen am genauen Timing ihres Gehirns.
        if (mob instanceof net.minecraft.world.entity.npc.villager.Villager) {
            return false;
        }
        final EntityType<?> type = mob.getType();
        return type != EntityType.ENDER_DRAGON && type != EntityType.WITHER && type != EntityType.WARDEN;
    }
}
