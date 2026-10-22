package net.paperstream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Sends anonymous usage statistics to bStats: https://bstats.org/plugin/server-implementation/PaperStream/34877
 *
 * Replaces Paper's own reporting, so a PaperStream server is counted for PaperStream and not for Paper.
 * Uses the same switch as every bStats plugin: enabled in plugins/bStats/config.yml.
 */
public final class PaperStreamMetrics {

    public static final int SERVICE_ID = 34877;

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String REPORT_URL = "https://bStats.org/api/v2/data/server-implementation";
    private static final String METRICS_VERSION = "3.1.0";
    private static ScheduledExecutorService scheduler;

    private PaperStreamMetrics() {
    }

    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        final File configFile = new File(new File((File)net.minecraft.server.MinecraftServer.getServer().options.valueOf("plugins"), "bStats"), "config.yml");
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.isSet("serverUuid")) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.options().setHeader(java.util.List.of(
                "bStats (https://bStats.org) collects some basic information for plugin authors, like how",
                "many people use their plugin and their total player count. It's recommended to keep bStats",
                "enabled, but if you're not comfortable with this, you can turn this setting off. There is no",
                "performance penalty associated with having metrics enabled, and data sent to bStats is fully",
                "anonymous."
            )).copyDefaults(true);
            try {
                config.save(configFile);
            } catch (final IOException ignored) {
                // the next start tries again
            }
        }
        if (!config.getBoolean("enabled", true)) {
            return;
        }
        final String serverUuid = config.getString("serverUuid");
        final boolean logFailed = config.getBoolean("logFailedRequests", false);
        final int serviceId = Integer.getInteger("paperstream.bstats.service", SERVICE_ID);

        scheduler = Executors.newSingleThreadScheduledExecutor(run -> {
            final Thread thread = new Thread(run, "PaperStream bStats");
            thread.setDaemon(true);
            return thread;
        });
        // the same timing as the bStats library: first report after 3 to 6 minutes, then every 30 minutes
        final long initialDelay = Long.getLong("paperstream.bstats.initialDelaySeconds", 60L * (3 + ThreadLocalRandom.current().nextInt(4)));
        scheduler.scheduleAtFixedRate(() -> submit(serviceId, serverUuid, logFailed), initialDelay, 30L * 60L, TimeUnit.SECONDS);
    }

    private static void submit(final int serviceId, final String serverUuid, final boolean logFailed) {
        try {
            final JsonObject data = new JsonObject();
            data.addProperty("serverUUID", serverUuid);
            data.addProperty("metricsVersion", METRICS_VERSION);
            data.addProperty("playerAmount", Bukkit.getOnlinePlayers().size());
            data.addProperty("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
            data.addProperty("bukkitVersion", Bukkit.getVersion());
            data.addProperty("bukkitName", Bukkit.getName());
            data.addProperty("javaVersion", System.getProperty("java.version"));
            data.addProperty("osName", System.getProperty("os.name"));
            data.addProperty("osArch", System.getProperty("os.arch"));
            data.addProperty("osVersion", System.getProperty("os.version"));
            data.addProperty("coreCount", Runtime.getRuntime().availableProcessors());

            final JsonArray charts = new JsonArray();
            addChart(charts, "players", "value", () -> Bukkit.getOnlinePlayers().size());
            addChart(charts, "minecraft_version", "value", Bukkit::getMinecraftVersion);
            addChart(charts, "online_mode", "value", () -> Bukkit.getOnlineMode() ? "online" : "offline");
            addChart(charts, "profile", "value", () -> PaperStreamConfig.profile.id);
            addChart(charts, "java_version", "value", () -> String.valueOf(Runtime.version().feature()));
            final JsonObject service = new JsonObject();
            service.addProperty("id", serviceId);
            service.add("customCharts", charts);
            data.add("service", service);

            send(data, logFailed);
        } catch (final Throwable t) {
            if (logFailed) {
                LOGGER.warn("Could not submit bStats data", t);
            }
        }
    }

    private static void addChart(final JsonArray charts, final String chartId, final String key, final Supplier<Object> value) {
        final Object result;
        try {
            result = value.get();
        } catch (final Throwable t) {
            return;
        }
        if (result == null) {
            return;
        }
        final JsonObject inner = new JsonObject();
        if (result instanceof Number number) {
            inner.addProperty(key, number);
        } else {
            inner.addProperty(key, result.toString());
        }
        final JsonObject chart = new JsonObject();
        chart.addProperty("chartId", chartId);
        chart.add("data", inner);
        charts.add(chart);
    }

    private static void send(final JsonObject data, final boolean logFailed) throws IOException {
        final byte[] compressed;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(data.toString().getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            compressed = bytes.toByteArray();
        }
        final HttpURLConnection connection = (HttpURLConnection)URI.create(REPORT_URL).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", String.valueOf(compressed.length));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Metrics-Service/1");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(compressed);
        }
        final int code = connection.getResponseCode();
        if (logFailed && code >= 300) {
            final java.io.InputStream error = connection.getErrorStream();
            final String body = error == null ? "" : new String(error.readAllBytes(), StandardCharsets.UTF_8);
            LOGGER.warn("bStats answered {}: {}", code, body);
        } else if (logFailed) {
            LOGGER.info("bStats data sent ({})", code);
        }
        connection.disconnect();
    }
}
