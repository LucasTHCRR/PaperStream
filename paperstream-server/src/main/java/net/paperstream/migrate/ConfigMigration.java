package net.paperstream.migrate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Compares the Paper, Spigot and Bukkit configs of a server that came from Paper with what
 * PaperStream recommends. Fresh PaperStream installs already start with these values, a migrated
 * server keeps its old Paper values until /paperstream migrate apply.
 *
 * Only settings that do not change how farms or the game work are on the list.
 */
public final class ConfigMigration {

    /** EXACT: only the value itself is fine. AT_MOST / AT_LEAST: a stricter value is kept as well. */
    public enum Compare { EXACT, AT_MOST, AT_LEAST }

    public record Recommendation(String file, String path, Object value, String reason, Compare compare) {
        Recommendation(final String file, final String path, final Object value, final String reason) {
            this(file, path, value, reason, Compare.EXACT);
        }
    }

    public record Difference(Recommendation recommendation, Object current) {
    }

    private static final String WORLD = "config/paper-world-defaults.yml";
    private static final String SPIGOT = "spigot.yml";
    private static final String SAVE_LIMIT = "chunks.entity-per-chunk-save-limit.";
    private static final String SAVE_LIMIT_REASON = "more than this in one chunk is not saved (lag machines that survive restarts)";

    private static final List<Recommendation> RECOMMENDATIONS = buildRecommendations();

    private ConfigMigration() {
    }

    private static List<Recommendation> buildRecommendations() {
        final List<Recommendation> list = new ArrayList<>();
        list.add(new Recommendation(WORLD, "misc.redstone-implementation", "ALTERNATE_CURRENT", "much faster redstone dust, same behaviour"));
        list.add(new Recommendation(WORLD, "environment.optimize-explosions", true, "TNT and creepers cost less CPU"));
        list.add(new Recommendation(WORLD, "collisions.max-entity-collisions", 2, "piles of mobs or minecarts cost less, cramming still works", Compare.AT_MOST));
        list.add(new Recommendation(WORLD, "collisions.fix-climbing-bypassing-cramming-rule", true, "closes a cramming bypass used by lag machines"));
        list.add(new Recommendation(WORLD, "entities.armor-stands.do-collision-entity-lookups", false, "armor stands stop searching for collisions"));
        final Map<String, Integer> saveLimits = new LinkedHashMap<>();
        saveLimits.put("area_effect_cloud", 8);
        saveLimits.put("arrow", 16);
        saveLimits.put("breeze_wind_charge", 8);
        saveLimits.put("dragon_fireball", 3);
        saveLimits.put("egg", 8);
        saveLimits.put("ender_pearl", 8);
        saveLimits.put("experience_orb", 16);
        saveLimits.put("eye_of_ender", 8);
        saveLimits.put("fireball", 8);
        saveLimits.put("firework_rocket", 8);
        saveLimits.put("lingering_potion", 8);
        saveLimits.put("llama_spit", 3);
        saveLimits.put("shulker_bullet", 8);
        saveLimits.put("small_fireball", 8);
        saveLimits.put("snowball", 8);
        saveLimits.put("spectral_arrow", 16);
        saveLimits.put("splash_potion", 8);
        saveLimits.put("tnt_minecart", 16);
        saveLimits.put("trident", 16);
        saveLimits.put("wind_charge", 8);
        saveLimits.put("wither_skull", 4);
        saveLimits.put("item", 512);
        saveLimits.put("minecart", 64);
        saveLimits.put("chest_minecart", 64);
        saveLimits.put("hopper_minecart", 64);
        saveLimits.put("falling_block", 64);
        saveLimits.put("tnt", 64);
        for (final Map.Entry<String, Integer> entry : saveLimits.entrySet()) {
            list.add(new Recommendation(WORLD, SAVE_LIMIT + entry.getKey(), entry.getValue(), SAVE_LIMIT_REASON, Compare.AT_MOST));
        }
        list.add(new Recommendation(SPIGOT, "world-settings.default.merge-radius.item", 2.5D, "dropped items merge into fewer entities"));
        list.add(new Recommendation(SPIGOT, "world-settings.default.merge-radius.exp", 3.0D, "experience orbs merge into fewer entities"));
        return List.copyOf(list);
    }

    /** All recommendations whose value differs from the server's config. Missing files are skipped. */
    public static List<Difference> differences() {
        final List<Difference> out = new ArrayList<>();
        final Map<String, YamlConfiguration> files = new LinkedHashMap<>();
        for (final Recommendation rec : RECOMMENDATIONS) {
            final YamlConfiguration yaml = files.computeIfAbsent(rec.file(), ConfigMigration::load);
            if (yaml == null) {
                continue;
            }
            final Object current = yaml.get(rec.path());
            if (!same(current, rec)) {
                out.add(new Difference(rec, current));
            }
        }
        return out;
    }

    /**
     * Writes all recommended values. No backup is made here on purpose (small servers often have
     * no disk space for it), the command recommends one and asks for a confirm first.
     *
     * @return the files that were changed
     */
    public static List<String> apply(final List<Difference> differences) throws IOException {
        final Map<String, YamlConfiguration> files = new LinkedHashMap<>();
        for (final Difference diff : differences) {
            files.computeIfAbsent(diff.recommendation().file(), ConfigMigration::load);
        }
        for (final Difference diff : differences) {
            final YamlConfiguration yaml = files.get(diff.recommendation().file());
            if (yaml != null) {
                yaml.set(diff.recommendation().path(), diff.recommendation().value());
            }
        }
        for (final Map.Entry<String, YamlConfiguration> entry : files.entrySet()) {
            if (entry.getValue() != null) {
                entry.getValue().save(new File(entry.getKey()));
            }
        }
        return List.copyOf(files.keySet());
    }

    private static YamlConfiguration load(final String name) {
        final File file = new File(name);
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
    }

    private static boolean same(final Object current, final Recommendation rec) {
        if (current == null) {
            return false;
        }
        if (current instanceof Number a && rec.value() instanceof Number b) {
            final double have = a.doubleValue();
            final double want = b.doubleValue();
            return switch (rec.compare()) {
                // -1 or 0 means no limit in Paper, so it is never stricter
                case AT_MOST -> have > 0 && have <= want;
                case AT_LEAST -> have >= want;
                case EXACT -> have == want;
            };
        }
        return String.valueOf(current).equalsIgnoreCase(String.valueOf(rec.value()));
    }
}
