package net.paperstream.world;

/**
 * Exact tick time of one world, measured around ServerLevel#tick.
 * Written and read on the main thread only.
 */
public final class WorldTickTimes {

    // 5 seconds and 1 minute at 20 TPS
    private static final int SHORT_WINDOW = 100;
    private static final int LONG_WINDOW = 1200;

    private final RollingWindow window = new RollingWindow(LONG_WINDOW);
    private double lastMspt;

    public void record(final long nanos) {
        this.lastMspt = nanos / 1.0E6D;
        this.window.add(this.lastMspt);
    }

    public double last() {
        return this.lastMspt;
    }

    /** Average over the last 5 seconds. */
    public double average5s() {
        return this.window.averageOfLast(SHORT_WINDOW);
    }

    /** Average over the last minute. */
    public double average1m() {
        return this.window.average();
    }

    /** Highest single tick in the last 5 seconds. */
    public double peak5s() {
        return this.window.peakOfLast(SHORT_WINDOW);
    }
}
