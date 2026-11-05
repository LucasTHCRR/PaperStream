package net.paperstream.world;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

// Findet die Chunks mit den meisten Entities und tickenden Block-Entities
public final class LagReport {

    private static final class ChunkStats {
        final int x;
        final int z;
        int entities;
        int tickers;
        final Map<String, Integer> entityTypes = new HashMap<>();
        final Map<String, Integer> tickerTypes = new HashMap<>();

        ChunkStats(final int x, final int z) {
            this.x = x;
            this.z = z;
        }

        int score() {
            return this.entities + this.tickers;
        }
    }

    private LagReport() {
    }

    public static List<String> build(final ServerLevel level, final int top) {
        final Long2ObjectOpenHashMap<ChunkStats> chunks = new Long2ObjectOpenHashMap<>();
        int entities = 0;
        for (final Entity entity : level.getAllEntities()) {
            final ChunkStats stats = stats(chunks, entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
            stats.entities++;
            stats.entityTypes.merge(entity.getType().toShortString(), 1, Integer::sum);
            entities++;
        }
        int tickers = 0;
        final var loaded = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks();
        for (int i = 0, len = loaded.size(); i < len; i++) {
            final LevelChunk chunk = loaded.getUnchecked(i);
            // ChunkPos ist in 26.x ein Record, in 1.21.11 nicht
            final int chunkX = chunk.getPos().getMinBlockX() >> 4;
            final int chunkZ = chunk.getPos().getMinBlockZ() >> 4;
            for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity.getBlockState().getBlock() instanceof EntityBlock block)
                    || block.getTicker(level, blockEntity.getBlockState(), blockEntity.getType()) == null) {
                    continue;
                }
                final ChunkStats stats = stats(chunks, chunkX, chunkZ);
                stats.tickers++;
                final var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                stats.tickerTypes.merge(key == null ? "unknown" : key.getPath(), 1, Integer::sum);
                tickers++;
            }
        }

        final List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%s: %d chunks loaded, %d entities, %d ticking block entities",
            level.getWorld().getName(), loaded.size(), entities, tickers));
        final List<ChunkStats> sorted = new ArrayList<>(chunks.values());
        sorted.sort(Comparator.comparingInt(ChunkStats::score).reversed());
        for (int i = 0; i < Math.min(top, sorted.size()); i++) {
            final ChunkStats stats = sorted.get(i);
            if (stats.score() == 0) {
                break;
            }
            final StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  chunk %d, %d (block %d, %d): ",
                stats.x, stats.z, (stats.x << 4) + 8, (stats.z << 4) + 8));
            line.append(stats.entities).append(" entities");
            appendTypes(line, stats.entityTypes);
            line.append(", ").append(stats.tickers).append(" ticking block entities");
            appendTypes(line, stats.tickerTypes);
            out.add(line.toString());
        }
        return out;
    }

    private static ChunkStats stats(final Long2ObjectOpenHashMap<ChunkStats> chunks, final int x, final int z) {
        return chunks.computeIfAbsent(((long) z << 32) | (x & 0xFFFFFFFFL), k -> new ChunkStats(x, z));
    }

    // die drei haeufigsten Typen in Klammern
    private static void appendTypes(final StringBuilder line, final Map<String, Integer> types) {
        if (types.isEmpty()) {
            return;
        }
        final List<Map.Entry<String, Integer>> entries = new ArrayList<>(types.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        line.append(" (");
        for (int i = 0; i < Math.min(3, entries.size()); i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(entries.get(i).getKey()).append(' ').append(entries.get(i).getValue());
        }
        line.append(')');
    }
}
