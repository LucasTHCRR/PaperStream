package net.paperstream.world;

// Taken from WorldMSPTTrackerAPI (Apache-2.0, https://codeberg.org/LucasTHCR/WorldMSPTTrackerAPI)

/**
 * A fixed-size ring buffer of doubles with a running sum.
 *
 * <p>Not thread-safe. Everything that writes to one of these runs on a tick thread; readers
 * go through the immutable snapshots the estimator publishes instead.
 */
public final class RollingWindow {

    private final double[] values;
    private int size;
    private int next;
    private double sum;

    public RollingWindow(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        this.values = new double[capacity];
    }

    /** Append a value, dropping the oldest one once the window is full. */
    public void add(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        if (size == values.length) {
            sum -= values[next];
        } else {
            size++;
        }
        values[next] = value;
        sum += value;
        next = (next + 1) % values.length;
        if (size == values.length && next == 0) {
            // The running sum drifts as values are added and subtracted over millions of
            // ticks. Recomputing once per full lap costs one pass over at most a few hundred
            // doubles and keeps the drift from accumulating.
            recomputeSum();
        }
    }

    /** Mean of everything in the window, or 0 when empty. */
    public double average() {
        return size == 0 ? 0.0 : sum / size;
    }

    /**
     * Mean of the {@code count} most recent values, or of everything present when fewer have
     * been recorded. Returns 0 when empty.
     */
    public double averageOfLast(int count) {
        int n = Math.min(count, size);
        if (n <= 0) return 0.0;
        if (n == size) return average();
        double total = 0.0;
        for (int i = 0; i < n; i++) total += get(i);
        return total / n;
    }

    /** Largest of the {@code count} most recent values, or 0 when empty. */
    public double peakOfLast(int count) {
        int n = Math.min(count, size);
        if (n <= 0) return 0.0;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) max = Math.max(max, get(i));
        return max;
    }

    /**
     * Value at {@code index} counting back from the newest, where 0 is the most recent.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or past the oldest value
     */
    public double get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " for size " + size);
        }
        int pos = next - 1 - index;
        if (pos < 0) pos += values.length;
        return values[pos];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return values.length;
    }

    public boolean isFull() {
        return size == values.length;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
        next = 0;
        sum = 0.0;
    }

    private void recomputeSum() {
        double total = 0.0;
        for (int i = 0; i < size; i++) total += values[i];
        sum = total;
    }
}
