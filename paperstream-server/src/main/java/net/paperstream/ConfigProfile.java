package net.paperstream;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ready made settings for paperstream.yml. Choosing a profile writes its values into the file
 * once, everything can still be changed by hand afterwards.
 */
public enum ConfigProfile {

    LOW("low", "Light touch, close to Paper: only the optimizations nobody notices in game."),
    MEDIUM("medium", "Balanced, the defaults."),
    PERFORMANCE("performance", "For weak servers (2-4 cores): saves the most CPU, keeps MSPT flat, chunks load a bit slower."),
    CUSTOM("custom", "Nothing is changed, every value comes from the file.");

    public final String id;
    public final String description;

    ConfigProfile(final String id, final String description) {
        this.id = id;
        this.description = description;
    }

    public static ConfigProfile byId(final String id) {
        final String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (final ConfigProfile profile : values()) {
            if (profile.id.equals(normalized)) {
                return profile;
            }
        }
        return null;
    }

    /** The values this profile writes into paperstream.yml. Empty for custom. */
    public Map<String, Object> settings() {
        if (this == CUSTOM) {
            return Map.of();
        }
        final Map<String, Object> v = new LinkedHashMap<>();
        v.put("chunks.directional-loading.enabled", true);
        v.put("chunks.directional-loading.bias", 0.6D);
        v.put("chunks.prefetch.enabled", false);
        v.put("chunks.prefetch.lookahead-chunks", 6);
        v.put("chunks.prefetch.max-per-second", 40);
        v.put("chunks.generation-boost", 2.0D);
        v.put("chunks.send-budget-ms", 0.0D);
        v.put("entities.tick-throttle.near-distance", 32.0D);
        v.put("entities.tick-throttle.far-distance", 56.0D);
        v.put("entities.tick-throttle.lag-mspt", 40.0D);
        v.put("entities.pathfinding.max-per-tick", 80);
        v.put("entities.villager-brain-interval", 20);
        v.put("entities.activation-check-interval", 1);
        v.put("world.lag-protection.mspt", 45.0D);
        v.put("world.explosions.max-per-chunk-per-tick", 32);
        v.put("world.idle.after-seconds", 60);
        v.put("world.redstone-clocks.max-per-second", 400);
        v.put("world.redstone-clocks.throttled-per-second", 40);
        v.put("chunks.fresh-chunks.enabled", true);
        v.put("chunks.fresh-chunks.warmup-seconds", 5);
        v.put("chunks.unload.delay-seconds", 30);
        v.put("chunks.unload.min-per-tick", 50);
        v.put("chunks.unload.fraction-per-tick", 0.05D);
        switch (this) {
            case LOW -> {
                v.put("entities.tick-throttle.near-distance", 48.0D);
                v.put("entities.tick-throttle.far-distance", 80.0D);
                v.put("entities.pathfinding.max-per-tick", 160);
                v.put("entities.villager-brain-interval", 1);
                v.put("world.lag-protection.mspt", 50.0D);
                v.put("world.explosions.max-per-chunk-per-tick", 64);
                v.put("world.redstone-clocks.max-per-second", 600);
                v.put("world.redstone-clocks.throttled-per-second", 60);
                v.put("chunks.fresh-chunks.enabled", false);
                v.put("chunks.unload.delay-seconds", 10);
            }
            case PERFORMANCE -> {
                v.put("chunks.generation-boost", 1.0D);
                v.put("chunks.send-budget-ms", 2.0D);
                v.put("entities.tick-throttle.near-distance", 24.0D);
                v.put("entities.tick-throttle.far-distance", 40.0D);
                v.put("entities.tick-throttle.lag-mspt", 30.0D);
                v.put("entities.pathfinding.max-per-tick", 40);
                v.put("entities.villager-brain-interval", 40);
                v.put("entities.activation-check-interval", 2);
                v.put("world.lag-protection.mspt", 35.0D);
                v.put("world.explosions.max-per-chunk-per-tick", 16);
                v.put("world.idle.after-seconds", 30);
                v.put("world.redstone-clocks.max-per-second", 250);
                v.put("world.redstone-clocks.throttled-per-second", 20);
                v.put("chunks.fresh-chunks.warmup-seconds", 8);
                v.put("chunks.unload.min-per-tick", 25);
                v.put("chunks.unload.fraction-per-tick", 0.03D);
            }
            default -> {
            }
        }
        return v;
    }

    /**
     * Chunk worker threads when chunk-system.worker-threads in paper-global.yml is -1.
     * A number set there always wins.
     */
    public int workerThreads(final int cores) {
        return switch (this) {
            case PERFORMANCE -> Math.max(1, (cores - 2) / 2);
            default -> Math.max(1, Math.min(16, cores - 2));
        };
    }
}
