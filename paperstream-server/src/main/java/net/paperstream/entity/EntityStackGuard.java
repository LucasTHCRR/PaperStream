package net.paperstream.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.paperstream.PaperStreamConfig;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Stops lag machines made of armor stands, minecarts or boats piled into one spot.
 *
 * Only placing a new one is refused, nothing that already exists is removed. Plugins
 * (holograms, NPCs) and commands are never limited, and marker armor stands do not count.
 * A farm or a rail station never puts that many into a single block.
 */
public final class EntityStackGuard {

    private EntityStackGuard() {
    }

    private static boolean isLimited(final Entity entity) {
        if (entity instanceof ArmorStand stand) {
            return !stand.isMarker();
        }
        return entity instanceof AbstractMinecart || entity instanceof AbstractBoat;
    }

    /**
     * @param reason the spawn reason, null for entities moving between worlds
     * @return false if the entity must not be added
     */
    public static boolean allowSpawn(final Level level, final Entity entity, final SpawnReason reason) {
        if (!PaperStreamConfig.entityStackGuard || reason == null
            || reason == SpawnReason.CUSTOM || reason == SpawnReason.COMMAND || !isLimited(entity)) {
            return true;
        }
        return allowAt(level, entity);
    }

    /** Check for a player placing the entity by hand, before the item gets used up. */
    public static boolean allowPlace(final Level level, final Entity entity) {
        return !PaperStreamConfig.entityStackGuard || !isLimited(entity) || allowAt(level, entity);
    }

    private static boolean allowAt(final Level level, final Entity entity) {
        final Class<? extends Entity> type = entity instanceof ArmorStand ? ArmorStand.class
            : entity instanceof AbstractMinecart ? AbstractMinecart.class : AbstractBoat.class;
        final BlockPos pos = entity.blockPosition();
        final int perBlock = PaperStreamConfig.entityStackPerBlock;
        if (perBlock > 0 && level.getEntitiesOfClass(type, new AABB(pos), e -> e != entity && isLimited(e)).size() >= perBlock) {
            return false;
        }
        final int perChunk = PaperStreamConfig.entityStackPerChunk;
        if (perChunk > 0) {
            final int x = pos.getX() & ~15;
            final int z = pos.getZ() & ~15;
            final AABB chunk = new AABB(x, level.getMinY(), z, x + 16, level.getMaxY() + 1, z + 16);
            return level.getEntitiesOfClass(type, chunk, e -> e != entity && isLimited(e)).size() < perChunk;
        }
        return true;
    }
}
