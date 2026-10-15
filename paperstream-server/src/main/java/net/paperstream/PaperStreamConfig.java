package net.paperstream;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Reads paperstream.yml. Every patch has its own switch here.
 */
public final class PaperStreamConfig {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final File FILE = new File("paperstream.yml");

    // chunks.region-compression
    public static String regionCompression = "lz4";

    // chunks.directional-loading
    public static boolean directionalLoading = true;
    public static double directionalBias = 0.6D;
    public static int directionalInnerRadius = 2;

    // chunks.prefetch
    public static boolean prefetch = false;
    public static double prefetchMinSpeed = 0.5D;
    public static int prefetchLookahead = 6;
    public static int prefetchMaxPerSecond = 40;
    public static int prefetchTicketTicks = 300;

    // chunks.pregen
    public static int pregenMaxConcurrent = 16;
    public static double pregenPauseAboveMspt = 45.0D;

    // entities.activation-check-interval
    public static int activationCheckInterval = 2;

    // entities.villager-brain-interval
    public static int villagerBrainInterval = 20;

    // entities.tick-throttle
    public static boolean entityThrottle = true;
    public static double entityThrottleNear = 32.0D;
    public static double entityThrottleFar = 56.0D;
    public static double entityThrottleLagMspt = 40.0D;

    // chunks.send-budget-ms
    public static double chunkSendBudgetMs = 0.0D;

    // chunks.generation-boost
    public static double genBoost = 2.0D;

    // world.lag-protection
    public static boolean lagProtection = true;
    public static double lagProtectionMspt = 45.0D;

    // entities.pathfinding
    public static boolean pathBudget = true;
    public static int pathBudgetPerTick = 80;

    // world.explosions
    public static int explosionsPerChunk = 32;

    // world.idle
    public static boolean idleMode = true;
    public static int idleAfterSeconds = 60;

    private PaperStreamConfig() {
    }

    public static void load() {
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(FILE);
        yaml.options().setHeader(java.util.List.of(
            "PaperStream configuration",
            "Every feature can be switched off on its own."
        ));

        regionCompression = string(yaml, "chunks.region-compression", regionCompression,
            "Compression for newly written chunks: lz4, deflate, none or keep (use server.properties). Old chunks stay readable.");

        directionalLoading = bool(yaml, "chunks.directional-loading.enabled", directionalLoading,
            "Load and send chunks in front of the player before the ones behind.");
        directionalBias = number(yaml, "chunks.directional-loading.bias", directionalBias,
            "0 = plain distance order, 0.9 = strongly prefer the view/movement direction.");
        directionalInnerRadius = integer(yaml, "chunks.directional-loading.inner-radius", directionalInnerRadius,
            "Chunks within this radius always load first, regardless of direction.");

        prefetch = bool(yaml, "chunks.prefetch.enabled", prefetch,
            "Generate chunks ahead of fast moving players (elytra, boats, sprinting).");
        prefetchMinSpeed = number(yaml, "chunks.prefetch.min-speed", prefetchMinSpeed,
            "Minimum horizontal speed in blocks per tick before prefetching starts.");
        prefetchLookahead = integer(yaml, "chunks.prefetch.lookahead-chunks", prefetchLookahead,
            "How many chunks beyond the view distance get prepared.");
        prefetchMaxPerSecond = integer(yaml, "chunks.prefetch.max-per-second", prefetchMaxPerSecond,
            "Upper limit of prefetched chunks per player and second.");
        prefetchTicketTicks = integer(yaml, "chunks.prefetch.keep-loaded-ticks", prefetchTicketTicks,
            "How long a prefetched chunk stays loaded when nobody reaches it.");

        pregenMaxConcurrent = integer(yaml, "chunks.pregen.max-concurrent", pregenMaxConcurrent,
            "Chunks generated at the same time by /paperstream pregen.");
        pregenPauseAboveMspt = number(yaml, "chunks.pregen.pause-above-mspt", pregenPauseAboveMspt,
            "Pregeneration pauses while the average MSPT is above this value.");

        chunkSendBudgetMs = number(yaml, "chunks.send-budget-ms", chunkSendBudgetMs,
            "Main thread milliseconds per tick for building chunk packets. Keeps MSPT flat while chunks load fast. 0 = no limit.");
        genBoost = number(yaml, "chunks.generation-boost", genBoost,
            "Fast moving players may generate this many times more chunks at once, as long as the world is not lagging. 1 = off.");

        lagProtection = bool(yaml, "world.lag-protection.enabled", lagProtection,
            "While a world is slow: half pathfinding budget and natural spawning only every 2nd tick, for that world only.");
        lagProtectionMspt = number(yaml, "world.lag-protection.mspt", lagProtectionMspt,
            "5 second average MSPT of a world from which on it counts as lagging.");

        pathBudget = bool(yaml, "entities.pathfinding.budget-enabled", pathBudget,
            "Limit new path calculations per world and tick. Mobs that hit the limit simply retry a moment later.");
        pathBudgetPerTick = integer(yaml, "entities.pathfinding.max-per-tick", pathBudgetPerTick,
            "New paths per world and tick. Mobs chasing an entity get twice this.");

        explosionsPerChunk = integer(yaml, "world.explosions.max-per-chunk-per-tick", explosionsPerChunk,
            "Explosions beyond this in one chunk and tick are dropped (TNT cannons, creeper farms). 0 = no limit.");

        idleMode = bool(yaml, "world.idle.enabled", idleMode,
            "With nobody online, worlds tick only once per second. Plugins keep running normally.");
        idleAfterSeconds = integer(yaml, "world.idle.after-seconds", idleAfterSeconds,
            "Seconds without players before idle mode starts.");

        activationCheckInterval = integer(yaml, "entities.activation-check-interval", activationCheckInterval,
            "Check which entities are near a player (entity activation range) only every this many ticks. 1 = every tick like Paper.");
        villagerBrainInterval = integer(yaml, "entities.villager-brain-interval", villagerBrainInterval,
            "Villagers that have not moved for 10 seconds run their AI only every this many ticks. Trading villagers are never throttled. 1 = off.");

        entityThrottle = bool(yaml, "entities.tick-throttle.enabled", entityThrottle,
            "Idle mobs far from every player tick less often. Fighting, riding, leashed, hurt or falling mobs are never throttled.");
        entityThrottleNear = number(yaml, "entities.tick-throttle.near-distance", entityThrottleNear,
            "Beyond this many blocks idle mobs tick every 2nd tick.");
        entityThrottleFar = number(yaml, "entities.tick-throttle.far-distance", entityThrottleFar,
            "Beyond this many blocks idle mobs tick every 4th tick.");
        entityThrottleLagMspt = number(yaml, "entities.tick-throttle.lag-mspt", entityThrottleLagMspt,
            "If a world averages more MSPT than this, throttled mobs in that world tick half as often again.");

        directionalBias = Math.max(0.0D, Math.min(0.95D, directionalBias));
        prefetchLookahead = Math.max(1, Math.min(32, prefetchLookahead));
        prefetchTicketTicks = Math.max(20, prefetchTicketTicks);
        pregenMaxConcurrent = Math.max(1, pregenMaxConcurrent);
        genBoost = Math.max(1.0D, Math.min(8.0D, genBoost));
        pathBudgetPerTick = Math.max(4, pathBudgetPerTick);
        idleAfterSeconds = Math.max(5, idleAfterSeconds);
        activationCheckInterval = Math.max(1, Math.min(10, activationCheckInterval));

        try {
            yaml.save(FILE);
        } catch (final IOException ex) {
            LOGGER.error("Could not save paperstream.yml", ex);
        }
    }

    private static String string(final YamlConfiguration yaml, final String path, final String def, final String comment) {
        init(yaml, path, def, comment);
        return yaml.getString(path, def);
    }

    private static boolean bool(final YamlConfiguration yaml, final String path, final boolean def, final String comment) {
        init(yaml, path, def, comment);
        return yaml.getBoolean(path, def);
    }

    private static int integer(final YamlConfiguration yaml, final String path, final int def, final String comment) {
        init(yaml, path, def, comment);
        return yaml.getInt(path, def);
    }

    private static double number(final YamlConfiguration yaml, final String path, final double def, final String comment) {
        init(yaml, path, def, comment);
        return yaml.getDouble(path, def);
    }

    private static void init(final YamlConfiguration yaml, final String path, final Object def, final String comment) {
        if (!yaml.contains(path)) {
            yaml.set(path, def);
        }
        yaml.setComments(path, java.util.List.of(comment));
    }
}
