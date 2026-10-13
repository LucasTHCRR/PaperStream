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
    public static boolean prefetch = true;
    public static double prefetchMinSpeed = 0.5D;
    public static int prefetchLookahead = 6;
    public static int prefetchMaxPerSecond = 40;
    public static int prefetchTicketTicks = 300;

    // chunks.pregen
    public static int pregenMaxConcurrent = 16;
    public static double pregenPauseAboveMspt = 45.0D;

    // entities.tick-throttle
    public static boolean entityThrottle = true;
    public static double entityThrottleNear = 32.0D;
    public static double entityThrottleFar = 56.0D;
    public static double entityThrottleLagMspt = 40.0D;

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
