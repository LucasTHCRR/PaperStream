package net.paperstream.command;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Re-reads server.properties and applies every value that can change while the server runs.
 * Values that only take effect on startup are reported instead of silently ignored.
 */
final class PropertiesReload {

    private static final Set<String> NEEDS_RESTART = Set.of(
        "online-mode", "level-name", "level-seed", "level-type", "server-port", "server-ip",
        "region-file-compression", "generate-structures", "generator-settings", "enable-rcon",
        "rcon.port", "enable-query", "query.port", "network-compression-threshold", "max-world-size",
        "management-server-enabled", "management-server-port"
    );

    private static Properties lastSeen;

    private PropertiesReload() {
    }

    private static Properties read() throws IOException {
        final Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("server.properties"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /** Called once at startup so later reloads know what the running server uses. */
    static void snapshot() {
        try {
            lastSeen = read();
        } catch (final IOException ignored) {
            lastSeen = new Properties();
        }
    }

    static void reload(final CommandSender sender) {
        final Properties now;
        try {
            now = read();
        } catch (final IOException ex) {
            sender.sendMessage("Could not read server.properties: " + ex.getMessage());
            return;
        }
        if (lastSeen == null) {
            snapshot();
        }
        final List<String> applied = new ArrayList<>();
        final List<String> restart = new ArrayList<>();
        for (final Map.Entry<Object, Object> e : now.entrySet()) {
            final String key = (String)e.getKey();
            final String value = ((String)e.getValue()).trim();
            if (value.equals(lastSeen.getProperty(key, "").trim())) {
                continue;
            }
            if (NEEDS_RESTART.contains(key)) {
                restart.add(key);
                continue;
            }
            if (apply(key, value)) {
                applied.add(key + "=" + value);
            } else {
                restart.add(key);
            }
        }
        lastSeen = now;
        if (applied.isEmpty() && restart.isEmpty()) {
            sender.sendMessage("server.properties: nothing changed.");
            return;
        }
        if (!applied.isEmpty()) {
            sender.sendMessage("Applied without restart: " + String.join(", ", applied));
        }
        if (!restart.isEmpty()) {
            sender.sendMessage("Needs a restart to take effect: " + String.join(", ", restart));
        }
    }

    private static boolean apply(final String key, final String value) {
        try {
            switch (key) {
                case "motd" -> MinecraftServer.getServer().setMotd(value);
                case "max-players" -> Bukkit.setMaxPlayers(Integer.parseInt(value));
                case "white-list" -> Bukkit.setWhitelist(Boolean.parseBoolean(value));
                case "enforce-whitelist" -> Bukkit.setWhitelistEnforced(Boolean.parseBoolean(value));
                case "difficulty" -> {
                    final Difficulty difficulty = parseDifficulty(value);
                    for (final World world : Bukkit.getWorlds()) {
                        world.setDifficulty(difficulty);
                    }
                }
                case "view-distance" -> {
                    final int vd = Integer.parseInt(value);
                    for (final World world : Bukkit.getWorlds()) {
                        world.setViewDistance(vd);
                    }
                }
                case "simulation-distance" -> {
                    final int sd = Integer.parseInt(value);
                    for (final World world : Bukkit.getWorlds()) {
                        world.setSimulationDistance(sd);
                    }
                }
                case "pvp" -> {
                    for (final World world : Bukkit.getWorlds()) {
                        world.setPVP(Boolean.parseBoolean(value));
                    }
                }
                case "spawn-protection" -> Bukkit.getServer().setSpawnRadius(Integer.parseInt(value));
                case "player-idle-timeout" -> Bukkit.setIdleTimeout(Integer.parseInt(value));
                default -> {
                    return false;
                }
            }
            return true;
        } catch (final RuntimeException ex) {
            Bukkit.getConsoleSender().sendMessage(Component.text("Invalid value for " + key + ": " + value));
            return true;
        }
    }

    private static Difficulty parseDifficulty(final String value) {
        try {
            return Difficulty.getByValue(Integer.parseInt(value));
        } catch (final NumberFormatException ex) {
            return Difficulty.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
