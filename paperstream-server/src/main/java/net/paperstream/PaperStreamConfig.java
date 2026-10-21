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

    public static ConfigProfile profile = ConfigProfile.MEDIUM;

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
    public static int activationCheckInterval = 1;

    // entities.villager-brain-interval
    public static int villagerBrainInterval = 20;

    // entities.tick-throttle
    public static boolean entityThrottle = true;
    public static double entityThrottleNear = 32.0D;
    public static double entityThrottleFar = 56.0D;
    public static double entityThrottleLagMspt = 40.0D;

    // chunks.fast-travel
    public static boolean fastTravelSim = false;
    public static double fastTravelMinSpeed = 1.2D;
    public static int fastTravelSimDistance = 3;
    public static int fastTravelRestoreTicks = 60;

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

    // world.redstone-clocks
    public static boolean redstoneGuard = true;
    public static int redstoneMaxPerSecond = 400;
    public static int redstoneTriggerSeconds = 10;
    public static int redstoneThrottledPerSecond = 40;
    public static int redstoneCooldownSeconds = 60;
    public static boolean redstoneNotifyOps = true;

    // entities.item-piles
    public static boolean itemPiles = true;
    public static int itemPilesMaxPerBlock = 64;
    public static int itemPilesInterval = 20;

    // chunks.unload
    public static int unloadDelayTicks = 600;
    public static int unloadMinPerTick = 50;
    public static double unloadFractionPerTick = 0.05D;

    // chunks.fresh-chunks
    public static boolean freshChunks = true;
    public static int freshChunksWarmupTicks = 100;
    public static int freshChunksPlayerRadius = 2;

    // startup.plugin-check
    public static boolean pluginCheck = true;

    // entities.stack-guard
    public static boolean entityStackGuard = true;
    public static int entityStackPerBlock = 8;
    public static int entityStackPerChunk = 96;
    public static String entityStackMessage = "<red>Too many of these in one spot.";

    // world.idle
    public static boolean idleMode = true;
    public static int idleAfterSeconds = 60;

    public static boolean proxyLink = false;
    public static boolean proxyConfigureForwarding = false;
    public static boolean proxyAllowRestart = false;
    public static boolean proxyAnnounceShutdown = true;
    public static int proxyLinkShutdownWait = 5;
    public static java.util.List<String> proxyExecAllowed = java.util.List.of();

    private PaperStreamConfig() {
    }

    public static void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(FILE);
        if (!yaml.contains("profile")) {
            // New file or one from before profiles: keep every value as it is and call it medium.
            final YamlConfiguration sorted = new YamlConfiguration();
            sorted.set("profile", ConfigProfile.MEDIUM.id);
            sorted.set("profile-applied", ConfigProfile.MEDIUM.id);
            for (final String key : yaml.getKeys(false)) {
                sorted.set(key, yaml.get(key));
            }
            yaml = sorted;
        }
        yaml.options().setHeader(java.util.List.of(
            "PaperStream configuration",
            "Every feature can be switched off on its own."
        ));

        final String profileName = yaml.getString("profile", ConfigProfile.MEDIUM.id);
        ConfigProfile chosen = ConfigProfile.byId(profileName);
        if (chosen == null) {
            LOGGER.warn("Unknown profile '{}' in paperstream.yml, using the values from the file (custom)", profileName);
            chosen = ConfigProfile.CUSTOM;
        }
        if (chosen != ConfigProfile.CUSTOM && !chosen.id.equals(yaml.getString("profile-applied"))) {
            for (final java.util.Map.Entry<String, Object> entry : chosen.settings().entrySet()) {
                yaml.set(entry.getKey(), entry.getValue());
            }
            LOGGER.info("PaperStream profile '{}' applied: {}", chosen.id, chosen.description);
        }
        profile = chosen;
        yaml.set("profile-applied", chosen.id);
        yaml.setComments("profile", java.util.List.of(
            "low = light touch, close to Paper. medium = balanced (default). performance = for weak servers, saves the most CPU.",
            "custom = nothing is changed. Switching writes the profile's values below once, afterwards every value can still be changed by hand.",
            "Switch live with /paperstream profile <name>. The chunk worker threads (when worker-threads is -1 in paper-global.yml) follow after a restart."
        ));
        yaml.setComments("profile-applied", java.util.List.of("Written by PaperStream, do not change."));

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

        fastTravelSim = bool(yaml, "chunks.fast-travel.enabled", fastTravelSim,
            "Off by default because it changes how the world behaves: while a player flies fast, only a small area around them ticks (mobs, crops, redstone). View distance stays the same.");
        fastTravelMinSpeed = number(yaml, "chunks.fast-travel.min-speed", fastTravelMinSpeed,
            "Horizontal speed in blocks per tick from which a player counts as fast (1.2 = 24 blocks per second).");
        fastTravelSimDistance = integer(yaml, "chunks.fast-travel.simulation-distance", fastTravelSimDistance,
            "Simulation distance in chunks for fast players.");
        fastTravelRestoreTicks = integer(yaml, "chunks.fast-travel.restore-after-ticks", fastTravelRestoreTicks,
            "Ticks a player has to be slow again before the normal simulation distance comes back.");
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

        redstoneGuard = bool(yaml, "world.redstone-clocks.enabled", redstoneGuard,
            "Slow down redstone clocks that run far faster than any farm (lag machines). Nothing gets broken, the chunk only gets fewer redstone updates.");
        redstoneMaxPerSecond = integer(yaml, "world.redstone-clocks.max-per-second", redstoneMaxPerSecond,
            "Redstone component updates (repeaters, comparators, observers, torches, pistons, ...) per chunk and second that still count as normal.");
        redstoneTriggerSeconds = integer(yaml, "world.redstone-clocks.trigger-seconds", redstoneTriggerSeconds,
            "A chunk has to stay above the limit this many seconds in a row before it gets slowed down. Short bursts like a big piston door never trigger.");
        redstoneThrottledPerSecond = integer(yaml, "world.redstone-clocks.throttled-per-second", redstoneThrottledPerSecond,
            "Redstone updates per second a slowed down chunk still gets. The rest waits for the next second.");
        redstoneCooldownSeconds = integer(yaml, "world.redstone-clocks.cooldown-seconds", redstoneCooldownSeconds,
            "How long a chunk stays slowed down. It starts again while the clock keeps running.");
        redstoneNotifyOps = bool(yaml, "world.redstone-clocks.notify", redstoneNotifyOps,
            "Tell players with paperstream.notify.redstone (ops by default) where a clock was slowed down. The console always gets it.");

        unloadDelayTicks = integer(yaml, "chunks.unload.delay-seconds", unloadDelayTicks < 0 ? -1 : unloadDelayTicks / 20,
            "Chunks a player left stay loaded this long, so flying back or turning around does not read them from disk again. Costs some RAM. -1 = use delay-chunk-unloads-by from paper-world-defaults.yml (10 seconds in Paper).");
        unloadDelayTicks = unloadDelayTicks < 0 ? -1 : unloadDelayTicks * 20;
        unloadMinPerTick = integer(yaml, "chunks.unload.min-per-tick", unloadMinPerTick,
            "Chunks unloaded at least per tick. Lower = unloading is spread over more ticks, smoother but RAM is freed slower. Paper: 50.");
        unloadFractionPerTick = number(yaml, "chunks.unload.fraction-per-tick", unloadFractionPerTick,
            "Share of the waiting chunks unloaded per tick when more are waiting. Paper: 0.05.");

        freshChunks = bool(yaml, "chunks.fresh-chunks.enabled", freshChunks,
            "Chunks that were just loaded and have no player close by wait a moment before their block entities, leaf updates and mob spawning run. Mobs loaded from the world file follow the normal activation range right away instead of ticking fully for 10 seconds. Saves a lot while players fly through the world.");
        freshChunksWarmupTicks = integer(yaml, "chunks.fresh-chunks.warmup-seconds", freshChunksWarmupTicks / 20,
            "Seconds a freshly loaded chunk waits. A player coming close ends the wait at once.") * 20;
        freshChunksPlayerRadius = integer(yaml, "chunks.fresh-chunks.player-radius", freshChunksPlayerRadius,
            "Chunks around a player that always tick right away.");

        pluginCheck = bool(yaml, "startup.plugin-check", pluginCheck,
            "After the start, list plugins that failed to start and plugins that do the same job as a PaperStream feature.");

        itemPiles = bool(yaml, "entities.item-piles.enabled", itemPiles,
            "Dropped items piling up in one block (item lag machines, broken farms) tick only every few ticks. Nothing is removed: picking up, hoppers and despawning work as before. Death drops never reach the limit.");
        itemPilesMaxPerBlock = integer(yaml, "entities.item-piles.max-per-block", itemPilesMaxPerBlock,
            "Dropped items in one block up to which everything ticks normally.");
        itemPilesInterval = integer(yaml, "entities.item-piles.tick-interval", itemPilesInterval,
            "Items in a bigger pile tick only every this many ticks (20 = once per second).");

        entityStackGuard = bool(yaml, "entities.stack-guard.enabled", entityStackGuard,
            "Refuse placing armor stands, minecarts or boats once too many are in one spot (lag machines). Existing ones stay, plugins and commands are never limited.");
        entityStackPerBlock = integer(yaml, "entities.stack-guard.max-per-block", entityStackPerBlock,
            "Of one kind (armor stands, minecarts, boats) in a single block. 0 = no limit.");
        entityStackPerChunk = integer(yaml, "entities.stack-guard.max-per-chunk", entityStackPerChunk,
            "Of one kind in a whole chunk. 0 = no limit.");
        entityStackMessage = string(yaml, "entities.stack-guard.message", entityStackMessage,
            "Action bar text for a player whose placement was refused (MiniMessage). Empty = no message.");

        idleMode = bool(yaml, "world.idle.enabled", idleMode,
            "With nobody online, worlds tick only once per second. Plugins keep running normally.");
        idleAfterSeconds = integer(yaml, "world.idle.after-seconds", idleAfterSeconds,
            "Seconds without players before idle mode starts.");

        proxyLink = bool(yaml, "proxy.link", proxyLink,
            "Off by default. Lets this server pair with PaperProxy: /paperproxy link pair <name> on the proxy, then /paperstream pair <proxy-address> <code> here. Each part below is its own switch.");
        proxyAnnounceShutdown = bool(yaml, "proxy.announce-shutdown", proxyAnnounceShutdown,
            "On stop or restart, ask the proxy to move players to another server or its limbo first, so nobody gets kicked. Only with proxy.link on.");
        proxyConfigureForwarding = bool(yaml, "proxy.configure-forwarding", proxyConfigureForwarding,
            "Let pairing set up Velocity forwarding and online-mode by itself. Off: nothing is changed, the console shows what to set in paper-global.yml.");
        proxyAllowRestart = bool(yaml, "proxy.allow-restart", proxyAllowRestart,
            "Allow the proxy to restart this server (/paperproxy link restart, rolling restarts).");
        proxyLinkShutdownWait = integer(yaml, "proxy.shutdown-wait-seconds", proxyLinkShutdownWait,
            "On stop, wait at most this long for the proxy to move players to other servers.");
        init(yaml, "proxy.exec-allowed", proxyExecAllowed,
            "Commands the proxy may run here with /paperproxy link exec. Empty = none (default). Only the first word counts, so \"paperstream\" allows all /paperstream subcommands, \"*\" allows every command.");
        proxyExecAllowed = yaml.getStringList("proxy.exec-allowed").stream()
            .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT)).filter(s -> !s.isEmpty()).toList();

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
        proxyLinkShutdownWait = Math.max(0, Math.min(30, proxyLinkShutdownWait));
        activationCheckInterval = Math.max(1, Math.min(10, activationCheckInterval));
        fastTravelSimDistance = Math.max(2, Math.min(32, fastTravelSimDistance));
        fastTravelRestoreTicks = Math.max(20, fastTravelRestoreTicks);
        unloadDelayTicks = unloadDelayTicks < 0 ? -1 : Math.min(20 * 600, unloadDelayTicks);
        unloadMinPerTick = Math.max(1, unloadMinPerTick);
        itemPilesMaxPerBlock = Math.max(48, itemPilesMaxPerBlock);
        itemPilesInterval = Math.max(2, Math.min(100, itemPilesInterval));
        unloadFractionPerTick = Math.max(0.0D, Math.min(1.0D, unloadFractionPerTick));
        freshChunksWarmupTicks = Math.max(20, Math.min(1200, freshChunksWarmupTicks));
        freshChunksPlayerRadius = Math.max(0, Math.min(8, freshChunksPlayerRadius));
        redstoneMaxPerSecond = Math.max(20, redstoneMaxPerSecond);
        redstoneTriggerSeconds = Math.max(1, redstoneTriggerSeconds);
        redstoneThrottledPerSecond = Math.max(1, redstoneThrottledPerSecond);
        redstoneCooldownSeconds = Math.max(1, redstoneCooldownSeconds);

        try {
            yaml.save(FILE);
        } catch (final IOException ex) {
            LOGGER.error("Could not save paperstream.yml", ex);
        }
    }

    /** Chunk worker threads for chunk-system.worker-threads: -1, read before the rest of the config is loaded. */
    public static int autoWorkerThreads(final int cores) {
        final ConfigProfile fromFile = ConfigProfile.byId(YamlConfiguration.loadConfiguration(FILE).getString("profile", ConfigProfile.MEDIUM.id));
        return (fromFile == null ? ConfigProfile.MEDIUM : fromFile).workerThreads(cores);
    }

    /** Switches the profile in paperstream.yml and loads it. */
    public static void switchProfile(final ConfigProfile target) {
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(FILE);
        yaml.set("profile", target.id);
        try {
            yaml.save(FILE);
        } catch (final IOException ex) {
            LOGGER.error("Could not save paperstream.yml", ex);
        }
        load();
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
