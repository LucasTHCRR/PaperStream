package net.paperstream.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.paperstream.PaperStreamConfig;

public final class AiThrottle {

    private static final int DISTANCE_RECHECK_TICKS = 20;
    private static final double STEP_BLOCKS = 16.0D;

    private AiThrottle() {
    }

    // 1 = wie Vanilla, sonst läuft das Denken nur jeden n-ten Tick
    public static int interval(final Mob mob) {
        if (!PaperStreamConfig.aiThrottle) {
            return 1;
        }
        final int now = MinecraftServer.currentTick;
        if (now >= mob.psAiRecheckTick) {
            mob.psAiRecheckTick = now + DISTANCE_RECHECK_TICKS + (mob.getId() & 7);
            mob.psAiInterval = computeInterval(mob);
        }
        return mob.psAiInterval;
    }

    private static int computeInterval(final Mob mob) {
        final Player nearest = mob.level().getNearestPlayer(mob, -1.0D);
        if (nearest == null) {
            return 1;
        }
        final double start = PaperStreamConfig.aiThrottleStart;
        final double distSq = nearest.distanceToSqr(mob);
        if (distSq < start * start) {
            return 1;
        }
        final int steps = (int) ((Math.sqrt(distSq) - start) / STEP_BLOCKS);
        return Math.max(1, Math.min(PaperStreamConfig.aiThrottleMaxInterval, 2 + steps));
    }

    // Sensoren und Erinnerungen laufen weiter, nur die Verhaltensliste wird ausgedünnt
    public static boolean skipBehaviors(final Villager villager) {
        final int interval = interval(villager);
        if (interval <= 1 || villager.isTrading() || villager.hurtTime > 0) {
            return false;
        }
        // feste Takte im Vanilla-Code (Golem-Versuch alle 100 Ticks, Knochenmehl alle 10) dürfen nie ausfallen
        if (villager.level().getGameTime() % 100L == 0L || villager.tickCount % 10 == 0) {
            return false;
        }
        // Panik treibt Eisenfarmen an, dort läuft alles wie Vanilla
        if (villager.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC)) {
            return false;
        }
        return (villager.tickCount + villager.getId()) % interval != 0;
    }

    // Wege zu einem Ziel werden weiter weg seltener neu berechnet, Angriffe bleiben im Takt
    public static int pathDelay(final Mob mob, final LivingEntity target, final int delay) {
        if (target instanceof Player) {
            return delay;
        }
        return delay * interval(mob);
    }
}
