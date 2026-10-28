package net.paperstream.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

/**
 * Stops "server killer" items: containers stuffed into containers until a single item carries so much
 * data that the chunk or the inventory packet holding it kicks everyone nearby. Survival players cannot
 * build such items, they come in through the creative inventory of a modified client.
 */
public final class ItemGuard {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int DEPTH_STOP = 16;

    private ItemGuard() {
    }

    /**
     * @return false if this item must not enter the server from a creative inventory
     */
    public static boolean allowCreative(final ServerPlayer player, final ItemStack stack) {
        if (!PaperStreamConfig.itemGuard || stack.isEmpty()) {
            return true;
        }
        final int depth = depth(stack, 0);
        if (depth > PaperStreamConfig.itemGuardMaxNesting) {
            refuse(player, stack, "containers nested " + depth + " deep");
            return false;
        }
        final int bytes = size(player, stack);
        if (bytes > PaperStreamConfig.itemGuardMaxSizeKb * 1024) {
            refuse(player, stack, (bytes / 1024) + " KB of item data");
            return false;
        }
        return true;
    }

    // 0 = plain item, 1 = shulker with items, 2 = chest item holding filled shulkers, ...
    private static int depth(final ItemStack stack, final int level) {
        if (level >= DEPTH_STOP) {
            return level;
        }
        int max = level;
        for (final TypedDataComponent<?> component : stack.getComponents()) {
            final java.util.stream.Stream<ItemStack> items;
            if (component.value() instanceof final ItemContainerContents container) {
                items = container.nonEmptyStream();
            } else if (component.value() instanceof final BundleContents bundle) {
                items = bundle.itemCopyStream();
            } else {
                continue;
            }
            final int inner = items
                .filter(item -> !item.isEmpty())
                .mapToInt(item -> depth(item, level + 1))
                .max().orElse(level);
            max = Math.max(max, inner);
        }
        return max;
    }

    private static int size(final ServerPlayer player, final ItemStack stack) {
        try {
            return ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .result().map(net.minecraft.nbt.Tag::sizeInBytes).orElse(0);
        } catch (final RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void refuse(final ServerPlayer player, final ItemStack stack, final String why) {
        LOGGER.warn("Blocked a creative item from {}: {} ({})", player.getScoreboardName(), stack.getItem(), why);
        player.getBukkitEntity().sendActionBar(net.kyori.adventure.text.Component.text(
            "This item carries too much data and was refused.", net.kyori.adventure.text.format.NamedTextColor.RED));
        if (!PaperStreamConfig.itemGuardNotify) {
            return;
        }
        final net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.text(
            "[PaperStream] Blocked an oversized creative item from " + player.getScoreboardName() + " (" + why + ")",
            net.kyori.adventure.text.format.NamedTextColor.GOLD);
        for (final ServerPlayer online : player.level().getServer().getPlayerList().getPlayers()) {
            if (online.getBukkitEntity().hasPermission("paperstream.notify.items")) {
                online.getBukkitEntity().sendMessage(message);
            }
        }
    }
}
