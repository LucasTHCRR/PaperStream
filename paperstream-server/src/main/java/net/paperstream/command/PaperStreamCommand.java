package net.paperstream.command;

import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.paperstream.PaperStream;
import net.paperstream.PaperStreamConfig;
import net.paperstream.chunk.Pregenerator;
import net.paperstream.link.ProxyLink;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class PaperStreamCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("info", "mspt", "pregen", "reload", "profile", "migrate", "pair", "proxy");

    public PaperStreamCommand(final String name) {
        super(name);
        this.description = "PaperStream status and tools";
        this.usageMessage = "/paperstream <info|mspt|pregen|reload|profile|migrate|pair|proxy>";
        this.setPermission("paperstream.command");
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> this.info(sender);
            case "mspt" -> this.mspt(sender);
            case "pregen" -> this.pregen(sender, args);
            case "pair" -> this.pair(sender, args);
            case "proxy" -> this.proxy(sender, args);
            case "profile" -> this.profile(sender, args);
            case "migrate" -> this.migrate(sender, args);
            case "reload" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("properties")) {
                    PropertiesReload.reload(sender);
                    return true;
                }
                PaperStreamConfig.load();
                PaperStream.applyConfig();
                sender.sendMessage("PaperStream config reloaded.");
            }
            default -> sender.sendMessage("Usage: " + this.usageMessage);
        }
        return true;
    }

    private void migrate(final CommandSender sender, final String[] args) {
        final List<net.paperstream.migrate.ConfigMigration.Difference> diffs = net.paperstream.migrate.ConfigMigration.differences();
        if (diffs.isEmpty()) {
            sender.sendMessage("Your Paper configs already match everything PaperStream recommends.");
            return;
        }
        final boolean apply = args.length >= 2 && args[1].equalsIgnoreCase("apply");
        if (!apply) {
            sender.sendMessage(diffs.size() + " settings differ from what PaperStream recommends:");
            int saveLimits = 0;
            for (final net.paperstream.migrate.ConfigMigration.Difference diff : diffs) {
                final var rec = diff.recommendation();
                if (rec.path().startsWith("chunks.entity-per-chunk-save-limit.")) {
                    ++saveLimits;
                    continue;
                }
                sender.sendMessage("  " + rec.file() + " " + rec.path() + ": " + diff.current() + " -> " + rec.value() + " (" + rec.reason() + ")");
            }
            if (saveLimits > 0) {
                sender.sendMessage("  config/paper-world-defaults.yml chunks.entity-per-chunk-save-limit: " + saveLimits
                    + " entity types without a limit (projectiles, items, minecarts, TNT; lag machines that survive restarts)");
            }
            sender.sendMessage("None of these change how farms work. Take them over with /paperstream migrate apply");
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage("This changes " + diffs.size() + " settings in your config files.");
            sender.sendMessage("We recommend a backup of config/ and spigot.yml first. PaperStream does not make one by itself.");
            sender.sendMessage("Run /paperstream migrate apply confirm to go ahead.");
            return;
        }
        try {
            final List<String> files = net.paperstream.migrate.ConfigMigration.apply(diffs);
            sender.sendMessage(diffs.size() + " settings written to " + String.join(", ", files) + ".");
            sender.sendMessage("Restart the server so every change takes effect.");
        } catch (final java.io.IOException ex) {
            sender.sendMessage("Could not write the config files: " + ex.getMessage());
        }
    }

    private void profile(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Profile: " + PaperStreamConfig.profile.id + " - " + PaperStreamConfig.profile.description);
            for (final net.paperstream.ConfigProfile p : net.paperstream.ConfigProfile.values()) {
                sender.sendMessage("  " + p.id + ": " + p.description);
            }
            sender.sendMessage("Switch with /paperstream profile <name>");
            return;
        }
        final net.paperstream.ConfigProfile target = net.paperstream.ConfigProfile.byId(args[1]);
        if (target == null) {
            sender.sendMessage("Unknown profile. Choose low, medium, performance or custom.");
            return;
        }
        final int workersBefore = PaperStreamConfig.autoWorkerThreads(Runtime.getRuntime().availableProcessors());
        PaperStreamConfig.switchProfile(target);
        PaperStream.applyConfig();
        sender.sendMessage("Profile " + target.id + " is active" + (target == net.paperstream.ConfigProfile.CUSTOM
            ? ", the values in paperstream.yml stay as they are." : ", its values were written to paperstream.yml."));
        final int workersAfter = PaperStreamConfig.autoWorkerThreads(Runtime.getRuntime().availableProcessors());
        if (workersAfter != workersBefore) {
            sender.sendMessage("Chunk worker threads change from " + workersBefore + " to " + workersAfter
                + " after a restart (only if worker-threads is -1 in paper-global.yml).");
        }
    }

    private void pair(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /paperstream pair <proxy-address[:port]> <code>");
            sender.sendMessage("Get the code on the proxy with /paperproxy link pair <server-name> [group].");
            return;
        }
        if (!PaperStreamConfig.proxyLink) {
            sender.sendMessage("The proxy link is off (proxy.link in paperstream.yml).");
            return;
        }
        if (ProxyLink.configuredByHand()) {
            sender.sendMessage("Velocity or BungeeCord forwarding is already set up by hand, PaperStream does not touch it.");
            return;
        }
        if (ProxyLink.isPaired()) {
            sender.sendMessage("This server is already paired. Run /paperstream proxy reset first to pair it again.");
            return;
        }
        final String address = args[1];
        final String code = args[2];
        sender.sendMessage("Pairing with " + address + "...");
        Thread.ofVirtual().name("PaperStream pairing").start(() -> {
            try {
                final String name = ProxyLink.pair(net.minecraft.server.MinecraftServer.getServer(), address, code);
                sender.sendMessage("Paired as " + name + "." + (net.paperstream.PaperStreamConfig.proxyConfigureForwarding
                    ? " Velocity forwarding is on, players now join through the proxy."
                    : " Forwarding was not changed (proxy.configure-forwarding is off), see the console."));
            } catch (final Exception ex) {
                sender.sendMessage("Pairing failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            }
        });
    }

    private void proxy(final CommandSender sender, final String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!ProxyLink.isPaired()) {
                sender.sendMessage("This server is not paired.");
                return;
            }
            ProxyLink.reset(net.minecraft.server.MinecraftServer.getServer());
            sender.sendMessage("Pairing removed, Velocity forwarding is off again. Also run /paperproxy link remove <name> on the proxy.");
            return;
        }
        ProxyLink.info().forEach(sender::sendMessage);
    }

    private void info(final CommandSender sender) {
        final double[] tps = Bukkit.getTPS();
        final Runtime rt = Runtime.getRuntime();
        final long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        final long maxMb = rt.maxMemory() >> 20;
        sender.sendMessage(String.format(Locale.ROOT, "TPS %.2f / %.2f / %.2f, MSPT %.2f, RAM %d/%d MB",
            tps[0], tps[1], tps[2], Bukkit.getAverageTickTime(), usedMb, maxMb));
        sender.sendMessage("Profile: " + PaperStreamConfig.profile.id + ", region compression: " + PaperStreamConfig.regionCompression
            + ", directional loading: " + onOff(PaperStreamConfig.directionalLoading)
            + ", prefetch: " + onOff(PaperStreamConfig.prefetch));
        if (sender instanceof final Player player) {
            final ServerPlayer handle = ((CraftPlayer)player).getHandle();
            final var loader = ((ChunkSystemServerPlayer)handle).moonrise$getChunkLoader();
            if (loader != null) {
                sender.sendMessage("Chunks prefetched for you: " + loader.psPrefetch.getTotalPrefetched());
            }
        }
        long pathsDenied = 0L;
        long explosionsDenied = 0L;
        long redstonePostponed = 0L;
        int redstoneChunks = 0;
        for (final net.minecraft.server.level.ServerLevel level : net.minecraft.server.MinecraftServer.getServer().getAllLevels()) {
            pathsDenied += level.psLimits.pathsDenied;
            explosionsDenied += level.psLimits.explosionsDenied;
            redstonePostponed += level.psRedstone.postponed;
            redstoneChunks += level.psRedstone.throttledChunks;
            if (level.psLimits.isLagging()) {
                sender.sendMessage("Lag protection active in " + level.getWorld().getName());
            }
        }
        sender.sendMessage("Paths postponed: " + pathsDenied + ", explosions dropped: " + explosionsDenied
            + (net.paperstream.world.IdleMode.isIdle() ? ", idle mode on" : ""));
        if (PaperStreamConfig.redstoneGuard) {
            sender.sendMessage("Redstone clocks slowed down: " + redstoneChunks + " chunks, " + redstonePostponed + " updates postponed");
        }
        if (PaperStreamConfig.fastTravelSim) {
            final List<String> fast = new ArrayList<>();
            for (final ServerPlayer p : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
                final var l = ((ChunkSystemServerPlayer)p).moonrise$getChunkLoader();
                if (l != null && l.psPrefetch.isSimulationReduced()) {
                    fast.add(p.getScoreboardName());
                }
            }
            sender.sendMessage("Fast travel (reduced simulation): " + (fast.isEmpty() ? "nobody" : String.join(", ", fast)));
        }
        final Pregenerator gen = Pregenerator.current();
        if (gen != null) {
            sender.sendMessage("Pregen " + gen.status());
        }
    }

    private void mspt(final CommandSender sender) {
        sender.sendMessage("MSPT per world (5s avg / 1m avg / 5s peak):");
        for (final net.minecraft.server.level.ServerLevel level : net.minecraft.server.MinecraftServer.getServer().getAllLevels()) {
            final net.paperstream.world.WorldTickTimes times = level.psTickTimes;
            sender.sendMessage(String.format(Locale.ROOT, "  %s: %.2f / %.2f / %.2f ms",
                level.getWorld().getName(), times.average5s(), times.average1m(), times.peak5s()));
        }
        sender.sendMessage(String.format(Locale.ROOT, "  whole server: %.2f ms", Bukkit.getAverageTickTime()));
    }

    private void pregen(final CommandSender sender, final String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            sender.sendMessage(Pregenerator.stop() ? "Pregen stopped." : "No pregen running.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            final Pregenerator gen = Pregenerator.current();
            sender.sendMessage(gen == null ? "No pregen running." : "Pregen " + gen.status());
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /paperstream pregen <world> <radius in chunks> [centerX centerZ] | stop | status");
            return;
        }
        final World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("Unknown world: " + args[1]);
            return;
        }
        final int radius;
        int centerX = world.getSpawnLocation().getBlockX() >> 4;
        int centerZ = world.getSpawnLocation().getBlockZ() >> 4;
        try {
            radius = Integer.parseInt(args[2]);
            if (args.length >= 5) {
                centerX = Integer.parseInt(args[3]);
                centerZ = Integer.parseInt(args[4]);
            }
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Radius and center must be whole numbers (chunk coordinates).");
            return;
        }
        if (radius < 1 || radius > 2000) {
            sender.sendMessage("Radius must be between 1 and 2000 chunks.");
            return;
        }
        if (!Pregenerator.start(world, centerX, centerZ, radius, sender)) {
            sender.sendMessage("A pregen is already running. Use /paperstream pregen stop first.");
            return;
        }
        final long side = 2L * radius + 1L;
        sender.sendMessage("Pregen started in " + world.getName() + " around chunk " + centerX + ", " + centerZ
            + " (" + side * side + " chunks).");
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (final String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            for (final net.paperstream.ConfigProfile p : net.paperstream.ConfigProfile.values()) {
                if (p.id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.id);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            out.add("apply");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("apply")) {
            out.add("confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            out.add("properties");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("proxy")) {
            out.add("info");
            out.add("reset");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) {
            out.add("stop");
            out.add("status");
            for (final World world : Bukkit.getWorlds()) {
                out.add(world.getName());
            }
        }
        return out;
    }

    private static String onOff(final boolean value) {
        return value ? "on" : "off";
    }
}
