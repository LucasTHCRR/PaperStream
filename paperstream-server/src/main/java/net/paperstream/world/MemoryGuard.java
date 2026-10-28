package net.paperstream.world;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import javax.management.ObjectName;
import net.minecraft.server.MinecraftServer;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

/**
 * Two ways to keep RAM in check:
 * the unload brake drops the chunk unload delay and speeds up unloading while the live heap is nearly full,
 * and an empty server runs one full collection so the JVM can hand unused memory back to the system.
 */
public final class MemoryGuard {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int CHECK_TICKS = 100;

    private static volatile boolean braking;
    private static long emptySinceNanos = -1;
    private static boolean releasedThisEmptyPhase;

    private MemoryGuard() {
    }

    public static boolean isBraking() {
        return braking;
    }

    public static void tick(final MinecraftServer server) {
        if (MinecraftServer.currentTick % CHECK_TICKS != 0) {
            return;
        }
        checkBrake();
        checkEmpty(server);
    }

    private static void checkBrake() {
        if (!PaperStreamConfig.memoryBrake) {
            if (braking) {
                setBraking(false, 0);
            }
            return;
        }
        final double percent = liveHeapPercent();
        // 10 Prozentpunkte Abstand, sonst schaltet die Bremse um die Grenze herum ständig hin und her.
        if (!braking && percent >= PaperStreamConfig.memoryBrakePercent) {
            setBraking(true, percent);
        } else if (braking && percent < PaperStreamConfig.memoryBrakePercent - 10) {
            setBraking(false, percent);
        }
    }

    private static void setBraking(final boolean on, final double percent) {
        braking = on;
        io.papermc.paper.FeatureHooks.applyPlayerChunkUnloadDelay();
        if (on) {
            LOGGER.warn("Live heap at {}%: chunks are unloaded right away and faster until it drops below {}%",
                Math.round(percent), PaperStreamConfig.memoryBrakePercent - 10);
        } else {
            LOGGER.info("Live heap back at {}%: normal chunk unloading again", Math.round(percent));
        }
    }

    /** Heap still in use after the last collection, as a percentage of -Xmx. */
    public static double liveHeapPercent() {
        long used = 0;
        boolean any = false;
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) {
                continue;
            }
            final MemoryUsage after = pool.getCollectionUsage();
            if (after != null) {
                used += after.getUsed();
                any = true;
            }
        }
        final Runtime rt = Runtime.getRuntime();
        if (!any || used == 0) {
            used = rt.totalMemory() - rt.freeMemory();
        }
        return used * 100.0D / rt.maxMemory();
    }

    private static void checkEmpty(final MinecraftServer server) {
        if (!PaperStreamConfig.memoryReleaseEmpty || server.getPlayerCount() > 0) {
            emptySinceNanos = -1;
            releasedThisEmptyPhase = false;
            return;
        }
        final long now = System.nanoTime();
        if (emptySinceNanos < 0) {
            emptySinceNanos = now;
            return;
        }
        if (releasedThisEmptyPhase || now - emptySinceNanos < PaperStreamConfig.memoryReleaseAfterSeconds * 1_000_000_000L) {
            return;
        }
        releasedThisEmptyPhase = true;
        final long before = Runtime.getRuntime().totalMemory();
        // Java gibt nach dem Aufräumen erst ab, wenn mehr als MaxHeapFreeRatio (70 %) frei ist; kurz senken, danach zurück.
        final com.sun.management.HotSpotDiagnosticMXBean hotspot =
            ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        String oldMin = null;
        String oldMax = null;
        try {
            oldMin = hotspot.getVMOption("MinHeapFreeRatio").getValue();
            oldMax = hotspot.getVMOption("MaxHeapFreeRatio").getValue();
            hotspot.setVMOption("MinHeapFreeRatio", "0");
            hotspot.setVMOption("MaxHeapFreeRatio", "10");
        } catch (final Exception e) {
            oldMin = null;
        }
        try {
            // GC.run statt System.gc(): wirkt auch mit -XX:+DisableExplicitGC aus Aikars Flags.
            ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcRun", null, null);
        } catch (final Exception e) {
            LOGGER.warn("Could not run a collection to give memory back: {}", e.toString());
        } finally {
            if (oldMin != null) {
                try {
                    hotspot.setVMOption("MaxHeapFreeRatio", oldMax);
                    hotspot.setVMOption("MinHeapFreeRatio", oldMin);
                } catch (final Exception ignored) {
                }
            }
        }
        final long after = Runtime.getRuntime().totalMemory();
        final long initial = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit();
        String why = "";
        if (after >= before) {
            why = after <= initial + (64L << 20)
                ? " (the JVM keeps it because -Xms is " + (initial >> 20) + " MB)"
                : " (the garbage collector keeps it)";
        }
        LOGGER.info("Nobody online for {} s: cleaned up the heap, reserved memory {} MB -> {} MB{}",
            PaperStreamConfig.memoryReleaseAfterSeconds, before >> 20, after >> 20, why);
    }
}
