package net.paperstream.command;

import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.paperstream.PaperStream;
import net.paperstream.PaperStreamConfig;
import net.paperstream.chunk.Pregenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class PaperStreamCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("info", "mspt", "pregen", "reload");

    public PaperStreamCommand(final String name) {
        super(name);
        this.description = "PaperStream status and tools";
        this.usageMessage = "/paperstream <info|mspt|pregen|reload>";
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
            case "reload" -> {
                PaperStreamConfig.load();
                PaperStream.applyConfig();
                sender.sendMessage("PaperStream config reloaded.");
            }
            default -> sender.sendMessage("Usage: " + this.usageMessage);
        }
        return true;
    }

    private void info(final CommandSender sender) {
        final double[] tps = Bukkit.getTPS();
        final Runtime rt = Runtime.getRuntime();
        final long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        final long maxMb = rt.maxMemory() >> 20;
        sender.sendMessage(String.format(Locale.ROOT, "TPS %.2f / %.2f / %.2f, MSPT %.2f, RAM %d/%d MB",
            tps[0], tps[1], tps[2], Bukkit.getAverageTickTime(), usedMb, maxMb));
        sender.sendMessage("Region compression: " + PaperStreamConfig.regionCompression
            + ", directional loading: " + onOff(PaperStreamConfig.directionalLoading)
            + ", prefetch: " + onOff(PaperStreamConfig.prefetch));
        if (sender instanceof final Player player) {
            final ServerPlayer handle = ((CraftPlayer)player).getHandle();
            final var loader = ((ChunkSystemServerPlayer)handle).moonrise$getChunkLoader();
            if (loader != null) {
                sender.sendMessage("Chunks prefetched for you: " + loader.psPrefetch.getTotalPrefetched());
            }
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
