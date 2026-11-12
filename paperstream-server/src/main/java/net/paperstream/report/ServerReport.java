package net.paperstream.report;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.paperstream.PaperStreamConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

// Nur Serverdaten: keine Weltnamen, Spielernamen, Adressen oder UUIDs. Gesendet wird nur auf Befehl.
public final class ServerReport {

    private static final int TOP_ENTITY_TYPES = 15;
    private static final int TOP_TICKER_TYPES = 10;

    public final List<JsonObject> lines = new ArrayList<>();
    public final long capturedAtMillis = System.currentTimeMillis();
    public final String reason;
    public int worlds;
    public int plugins;
    public int settings;

    private ServerReport(final String reason) {
        this.reason = reason;
    }

    public static ServerReport collect(final MinecraftServer server, final String reason) {
        final ServerReport report = new ServerReport(reason);
        final String time = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        final Runtime runtime = Runtime.getRuntime();

        final JsonObject head = line(time, "ps_report");
        head.addProperty("paperstream", version(io.papermc.paper.ServerBuildInfo.buildInfo().asString(io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_SIMPLE)));
        head.addProperty("minecraft", version(io.papermc.paper.ServerBuildInfo.buildInfo().minecraftVersionId()));
        head.addProperty("java", version(System.getProperty("java.version", "unknown")));
        head.addProperty("profile", PaperStreamConfig.profile.id);
        head.addProperty("compression", text(PaperStreamConfig.regionCompression, 16));
        head.addProperty("cores", runtime.availableProcessors());
        head.addProperty("heapMaxMb", (int) (runtime.maxMemory() >> 20));
        head.addProperty("heapUsedMb", (int) ((runtime.totalMemory() - runtime.freeMemory()) >> 20));
        head.addProperty("workers", workerThreads());
        head.addProperty("players", server.getPlayerCount());
        head.addProperty("uptimeMin", (int) (java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 60000L));
        head.addProperty("reason", reason);
        head.addProperty("memoryBraking", net.paperstream.world.MemoryGuard.isBraking());
        head.addProperty("idlePregenChunks", net.paperstream.chunks.IdlePregen.completed());
        report.lines.add(head);

        int index = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            report.lines.add(world(level, time, ++index));
        }
        report.worlds = index;

        // nur Schluessel aus dem Profil: das sind Zahlen und Schalter, nie Adressen oder Namen
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("paperstream.yml"));
        for (final Map.Entry<String, Object> entry : PaperStreamConfig.profile.settings().entrySet()) {
            final Object actual = yaml.get(entry.getKey());
            if (actual == null || same(actual, entry.getValue())) {
                continue;
            }
            final JsonObject setting = line(time, "ps_setting");
            setting.addProperty("key", text(entry.getKey(), 80));
            setting.addProperty("value", text(String.valueOf(actual), 80));
            report.lines.add(setting);
            report.settings++;
        }

        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            final JsonObject entry = line(time, "ps_plugin");
            entry.addProperty("name", text(plugin.getName(), 64));
            entry.addProperty("version", version(plugin.getPluginMeta().getVersion()));
            entry.addProperty("enabled", plugin.isEnabled());
            report.lines.add(entry);
            report.plugins++;
        }
        return report;
    }

    private static JsonObject world(final ServerLevel level, final String time, final int index) {
        final Map<String, Integer> entityTypes = new HashMap<>();
        int entities = 0;
        for (final Entity entity : level.getAllEntities()) {
            entityTypes.merge(entity.getType().toShortString(), 1, Integer::sum);
            entities++;
        }
        final Map<String, Integer> tickerTypes = new HashMap<>();
        int tickers = 0;
        final var loaded = ((ChunkSystemServerLevel) level).moonrise$getLoadedChunks();
        for (int i = 0, len = loaded.size(); i < len; i++) {
            final LevelChunk chunk = loaded.getUnchecked(i);
            for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity.getBlockState().getBlock() instanceof EntityBlock block)
                    || block.getTicker(level, blockEntity.getBlockState(), blockEntity.getType()) == null) {
                    continue;
                }
                final var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                tickerTypes.merge(key == null ? "unknown" : key.getPath(), 1, Integer::sum);
                tickers++;
            }
        }

        final JsonObject world = line(time, "ps_world");
        // nie der echte Weltname
        world.addProperty("world", "world_" + index);
        world.addProperty("env", level.dimension() == Level.OVERWORLD ? "overworld"
            : level.dimension() == Level.NETHER ? "nether" : level.dimension() == Level.END ? "end" : "custom");
        world.addProperty("mspt5s", round(level.psTickTimes.average5s()));
        world.addProperty("mspt1m", round(level.psTickTimes.average1m()));
        world.addProperty("peak5s", round(level.psTickTimes.peak5s()));
        world.addProperty("chunks", loaded.size());
        world.addProperty("entities", entities);
        world.addProperty("tickers", tickers);
        world.addProperty("players", level.players().size());
        world.add("entityTypes", top(entityTypes, TOP_ENTITY_TYPES));
        world.add("tickerTypes", top(tickerTypes, TOP_TICKER_TYPES));
        return world;
    }

    private static JsonArray top(final Map<String, Integer> counts, final int limit) {
        final List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        final JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("name", text(entries.get(i).getKey(), 64));
            entry.addProperty("count", entries.get(i).getValue());
            out.add(entry);
        }
        return out;
    }

    private static JsonObject line(final String time, final String type) {
        final JsonObject line = new JsonObject();
        line.addProperty("time", time);
        line.addProperty("type", type);
        return line;
    }

    private static int workerThreads() {
        try {
            return ca.spottedleaf.moonrise.common.util.MoonriseCommon.WORKER_POOL.getCoreThreads().length;
        } catch (final Throwable ignored) {
            return 0;
        }
    }

    private static boolean same(final Object a, final Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static double round(final double value) {
        return Double.isFinite(value) ? Math.round(value * 100.0D) / 100.0D : 0.0D;
    }

    // der Dienst nimmt nur diese Zeichen an, alles andere wird ersetzt
    static String text(final String value, final int limit) {
        final String cut = value.length() > limit ? value.substring(0, limit) : value;
        return cut.replaceAll("[^A-Za-z0-9 ()+.,:=%/_\\-]", "_");
    }

    static String version(final String value) {
        String out = (value == null || value.isEmpty() ? "unknown" : value).replaceAll("[^0-9A-Za-z.+_\\-]", "_");
        if (!Character.isLetterOrDigit(out.charAt(0))) {
            out = "v" + out;
        }
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    public interface Result {
        void done(boolean ok, String readUrl, String deleteKey, String error);
    }

    public void send(final MinecraftServer server, final Result result) {
        this.lines.get(0).addProperty("capturedAgoSec", (int) ((System.currentTimeMillis() - this.capturedAtMillis) / 1000L));
        final StringBuilder text = new StringBuilder();
        for (final JsonObject line : this.lines) {
            text.append(line).append('\n');
        }
        final byte[] body;
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
            body = bytes.toByteArray();
        } catch (final java.io.IOException e) {
            result.done(false, null, null, e.getMessage());
            return;
        }
        final Consumer<Runnable> onMain = server::execute;
        final Thread thread = new Thread(() -> {
            try {
                final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                final HttpRequest request = HttpRequest.newBuilder(URI.create(PaperStreamConfig.reportUrl + "/api/v1/reports"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/gzip")
                    .header("User-Agent", "PaperStream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
                final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                final JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (response.statusCode() == 201) {
                    final String url = json.get("readUrl").getAsString();
                    final String key = json.get("id").getAsString() + " " + json.get("deleteKey").getAsString();
                    onMain.accept(() -> result.done(true, url, key, null));
                } else {
                    final String error = (json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode())
                        + (json.has("detail") ? ": " + json.get("detail").getAsString() : "");
                    onMain.accept(() -> result.done(false, null, null, error));
                }
            } catch (final Exception e) {
                final String error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                onMain.accept(() -> result.done(false, null, null, error));
            }
        }, "PaperStream report upload");
        thread.setDaemon(true);
        thread.start();
    }
}
