package net.paperstream.migrate;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.paperstream.PaperStreamConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Runs once after the server started: lists plugins that failed to start and plugins that do the
 * same job as a PaperStream feature, so the two do not fight each other.
 */
public final class PluginCheck {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** Lower case plugin name -> what to know about it on PaperStream. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
        Map.entry("antiredstoneclock", "does the same as world.redstone-clocks in paperstream.yml, keep only one of them"),
        Map.entry("redstonelimiter", "does the same as world.redstone-clocks in paperstream.yml, keep only one of them"),
        Map.entry("clearlag", "PaperStream already limits lag machines and idle mobs, ClearLag removing entities is rarely needed"),
        Map.entry("clearlagg", "PaperStream already limits lag machines and idle mobs, ClearLag removing entities is rarely needed"),
        Map.entry("laggremover", "PaperStream already limits lag machines and idle mobs, check that both are not throttling the same thing"),
        Map.entry("lagassist", "overlaps with PaperStream's lag protection and redstone/entity limits, check that both are not throttling the same thing"),
        Map.entry("farmcontrol", "overlaps with PaperStream's mob throttling (entities.tick-throttle), check that both are not throttling the same mobs"),
        Map.entry("farmlimiter", "overlaps with entities.stack-guard and the entity save limits, keep the stricter one only"),
        Map.entry("chunky", "works, PaperStream also has /paperstream pregen built in")
    );

    private PluginCheck() {
    }

    public static void run() {
        if (!PaperStreamConfig.pluginCheck) {
            return;
        }
        final List<String> lines = new ArrayList<>();
        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            final String name = plugin.getName();
            if (!plugin.isEnabled()) {
                lines.add(name + " " + plugin.getPluginMeta().getVersion()
                    + ": did not start, see its error above. If it works on Paper, please report it: https://dc.gg/paperstream");
                continue;
            }
            final String note = KNOWN.get(name.toLowerCase(Locale.ROOT));
            if (note != null) {
                lines.add(name + ": " + note);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        LOGGER.warn("Plugin check ({} notes, turn off with startup.plugin-check in paperstream.yml):", lines.size());
        for (final String line : lines) {
            LOGGER.warn("  - {}", line);
        }
    }
}
