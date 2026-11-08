package net.paperstream.chunkbench;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkBench extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("sweep")) {
            return this.sweep(args);
        }
        if (args.length > 1 && args[0].equals("jfr")) {
            return this.jfr(sender, args);
        }
        if (args.length > 1 && args[0].equals("mem")) {
            return this.mem(args[1]);
        }
        if (args.length < 3) {
            return false;
        }
        final int radius = Integer.parseInt(args[0]);
        final int cx = Integer.parseInt(args[1]);
        final int cz = Integer.parseInt(args[2]);
        final int parallel = args.length > 3 ? Integer.parseInt(args[3]) : 64;
        final World world = Bukkit.getWorlds().get(0);

        // Spirale von innen nach aussen wie bei einem Pregenerator
        final ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{cx, cz});
        for (int r = 1; r <= radius; r++) {
            for (int i = -r; i < r; i++) {
                queue.add(new long[]{cx + i, cz - r});
                queue.add(new long[]{cx + r, cz + i});
                queue.add(new long[]{cx - i, cz + r});
                queue.add(new long[]{cx - r, cz - i});
            }
        }
        final int total = queue.size();
        final AtomicInteger done = new AtomicInteger();
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long startCpu = os.getProcessCpuTime();
        final long start = System.nanoTime();
        final double[] mspt = new double[]{0, 0, 0};

        final Runnable[] pump = new Runnable[1];
        final int[] inFlight = new int[1];
        pump[0] = () -> {
            while (inFlight[0] < parallel && !queue.isEmpty()) {
                final long[] c = queue.poll();
                inFlight[0]++;
                world.getChunkAtAsync((int) c[0], (int) c[1], true).thenAccept(chunk -> {
                    inFlight[0]--;
                    done.incrementAndGet();
                    pump[0].run();
                });
            }
        };
        pump[0].run();

        Bukkit.getScheduler().runTaskTimer(this, task -> {
            final double t = Bukkit.getAverageTickTime();
            mspt[0] += t;
            mspt[1]++;
            mspt[2] = Math.max(mspt[2], t);
            if (done.get() >= total) {
                task.cancel();
                final double sec = (System.nanoTime() - start) / 1e9;
                final double cpu = (os.getProcessCpuTime() - startCpu) / 1e9;
                getLogger().info(String.format("BENCH chunks=%d time=%.1fs cps=%.1f cpu=%.1fs cpuPerChunk=%.2fms avgMspt=%.2f maxMspt=%.2f",
                    total, sec, total / sec, cpu, cpu * 1000 / total, mspt[0] / mspt[1], mspt[2]));
            }
        }, 20L, 20L);
        sender.sendMessage("ChunkBench started: " + total + " chunks");
        return true;
    }

    private jdk.jfr.Recording recording;

    // Profil ohne JVM-Flags aufnehmen, z. B. auf Pterodactyl
    private boolean jfr(final CommandSender sender, final String[] args) {
        try {
            if (args[1].equals("start") && args.length > 2 && this.recording == null) {
                this.recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
                this.recording.setDestination(java.nio.file.Path.of(args[2]));
                this.recording.start();
                getLogger().info("JFR started " + args[2]);
            } else if (args[1].equals("stop") && this.recording != null) {
                this.recording.stop();
                this.recording.close();
                this.recording = null;
                getLogger().info("JFR stopped");
            }
        } catch (final Exception e) {
            getLogger().warning("JFR failed: " + e);
        }
        return true;
    }

    // Heap nach GC, Metaspace, Direct Memory, GC-Zeit und insgesamt angelegter Speicher
    private boolean mem(final String tag) {
        System.gc();
        System.gc();
        final java.lang.management.MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long meta = 0, direct = 0, gcCount = 0, gcMs = 0;
        for (final java.lang.management.MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().contains("Metaspace")) meta += p.getUsage().getUsed();
        }
        for (final java.lang.management.BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            direct += b.getMemoryUsed();
        }
        for (final java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, g.getCollectionCount());
            gcMs += Math.max(0, g.getCollectionTime());
        }
        final long alloc = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes();
        int chunks = 0, entities = 0;
        for (final World w : Bukkit.getWorlds()) {
            chunks += w.getLoadedChunks().length;
            entities += w.getEntityCount();
        }
        getLogger().info(String.format("MEM tag=%s heapUsedMB=%.1f heapCommittedMB=%.1f metaMB=%.1f directMB=%.1f gcCount=%d gcMs=%d allocMB=%.1f chunks=%d entities=%d",
            tag, heap.getUsed() / 1e6, heap.getCommitted() / 1e6, meta / 1e6, direct / 1e6, gcCount, gcMs, alloc / 1e6, chunks, entities));
        return true;
    }

    // Fenster aus Plugin-Tickets wandert in X-Richtung wie ein fliegender Spieler
    private boolean sweep(final String[] args) {
        final int startX = Integer.parseInt(args[1]);
        final int z = Integer.parseInt(args[2]);
        final int length = Integer.parseInt(args[3]);
        final int radius = Integer.parseInt(args[4]);
        final int ticksPerStep = Integer.parseInt(args[5]);
        final World world = Bukkit.getWorlds().get(0);
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long startCpu = os.getProcessCpuTime();
        final double[] mspt = new double[]{0, 0, 0};
        final int[] step = new int[]{0};
        for (int x = startX - radius; x <= startX + radius; x++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.addPluginChunkTicket(x, z + dz, this);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            final double t = Bukkit.getAverageTickTime();
            if (step[0] > 0) {
                mspt[0] += t;
                mspt[1]++;
                mspt[2] = Math.max(mspt[2], t);
            }
            if (step[0] >= length) {
                task.cancel();
                world.removePluginChunkTickets(this);
                final double cpu = (os.getProcessCpuTime() - startCpu) / 1e9;
                getLogger().info(String.format("SWEEP steps=%d cpu=%.1fs avgMspt=%.2f maxMspt=%.2f", length, cpu, mspt[0] / mspt[1], mspt[2]));
                return;
            }
            final int x = startX + step[0];
            for (int dz = -radius; dz <= radius; dz++) {
                world.removePluginChunkTicket(x - radius, z + dz, this);
                world.addPluginChunkTicket(x + radius + 1, z + dz, this);
            }
            step[0]++;
        }, ticksPerStep, ticksPerStep);
        return true;
    }
}
