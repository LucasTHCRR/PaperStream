package net.paperstream.chunk;

import java.util.concurrent.atomic.AtomicInteger;
import net.paperstream.PaperStreamConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Generates a square of chunks around a center in spiral order, a few at a time.
 * Driven from the main thread tick, generation itself runs on the chunk system workers.
 */
public final class Pregenerator {

    private static Pregenerator running;

    private final World world;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final long total;
    private final CommandSender requester;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final long startNanos = System.nanoTime();

    // spiral state
    private int x;
    private int z;
    private int legLength = 1;
    private int legProgress;
    private int direction;
    private int legsDone;
    private long issued;
    private final AtomicInteger completed = new AtomicInteger();
    private boolean paused;

    private Pregenerator(final World world, final int centerX, final int centerZ, final int radius, final CommandSender requester) {
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        final long side = 2L * radius + 1L;
        this.total = side * side;
        this.requester = requester;
    }

    public static synchronized boolean start(final World world, final int centerX, final int centerZ, final int radius, final CommandSender requester) {
        if (running != null) {
            return false;
        }
        running = new Pregenerator(world, centerX, centerZ, radius, requester);
        return true;
    }

    public static synchronized boolean stop() {
        if (running == null) {
            return false;
        }
        running = null;
        return true;
    }

    public static synchronized Pregenerator current() {
        return running;
    }

    public static void tick() {
        final Pregenerator gen = current();
        if (gen != null) {
            gen.tickInternal();
        }
    }

    private void tickInternal() {
        if (this.issued >= this.total) {
            if (this.inFlight.get() == 0) {
                this.finish();
            }
            return;
        }
        this.paused = Bukkit.getAverageTickTime() > PaperStreamConfig.pregenPauseAboveMspt;
        if (this.paused) {
            return;
        }
        while (this.issued < this.total && this.inFlight.get() < PaperStreamConfig.pregenMaxConcurrent) {
            final int cx = this.centerX + this.x;
            final int cz = this.centerZ + this.z;
            this.advance();
            ++this.issued;
            if (Math.abs(cx - this.centerX) > this.radius || Math.abs(cz - this.centerZ) > this.radius) {
                // spiral corner overshoot, cannot happen for a square but keep the bounds explicit
                this.completed.incrementAndGet();
                continue;
            }
            this.inFlight.incrementAndGet();
            this.world.getChunkAtAsync(cx, cz, true, false).whenComplete((chunk, error) -> {
                if (error != null || chunk == null) {
                    this.failed.incrementAndGet();
                }
                this.completed.incrementAndGet();
                this.inFlight.decrementAndGet();
            });
        }
    }

    private void advance() {
        switch (this.direction) {
            case 0 -> ++this.x;
            case 1 -> ++this.z;
            case 2 -> --this.x;
            default -> --this.z;
        }
        if (++this.legProgress == this.legLength) {
            this.legProgress = 0;
            this.direction = (this.direction + 1) & 3;
            if (++this.legsDone % 2 == 0) {
                ++this.legLength;
            }
        }
    }

    private void finish() {
        synchronized (Pregenerator.class) {
            if (running == this) {
                running = null;
            }
        }
        final String msg = "PaperStream pregen finished: " + this.completed.get() + " chunks in " + this.elapsedSeconds()
            + "s" + (this.failed.get() > 0 ? " (" + this.failed.get() + " failed)" : "");
        Bukkit.getConsoleSender().sendMessage(msg);
        if (this.requester != Bukkit.getConsoleSender()) {
            this.requester.sendMessage(msg);
        }
    }

    private long elapsedSeconds() {
        return (System.nanoTime() - this.startNanos) / 1_000_000_000L;
    }

    public String status() {
        final long done = this.completed.get();
        final long secs = Math.max(1L, this.elapsedSeconds());
        final double rate = done / (double)secs;
        final long eta = rate > 0.0D ? (long)((this.total - done) / rate) : -1L;
        return String.format(java.util.Locale.ROOT,
            "%s: %d/%d chunks (%.1f%%), %.1f chunks/s, ETA %s%s",
            this.world.getName(), done, this.total, done * 100.0D / this.total, rate,
            eta < 0 ? "?" : (eta / 60) + "m " + (eta % 60) + "s",
            this.paused ? ", paused (MSPT too high)" : "");
    }
}
