package dev.aeroac.checks.impl.aim;

/**
 * Counts fast reversals in one rotation axis: a swing of at least the hysteresis that took at most
 * {@code maxSamples} samples, immediately undone by a swing of at least the hysteresis the other way.
 *
 * <p>The hysteresis keeps mouse noise and sensitivity steps out: a direction only changes once the
 * value has come back by the full amount from its extreme.
 */
public final class ZigzagCounter {
    private final double hysteresis;
    private final int maxSamples;

    private long index;
    private int direction;
    private double high, low;
    private long highIndex, lowIndex;
    private double extremum;
    private long extremumIndex;
    private long previousExtremumIndex;

    public ZigzagCounter(double hysteresis, int maxSamples) {
        this.hysteresis = hysteresis;
        this.maxSamples = maxSamples;
        reset();
    }

    /** @return true when this value completed a fast reversal */
    public boolean add(double value) {
        long i = index++;
        if (direction == 0) {
            if (i == 0 || value > high) {
                high = value;
                highIndex = i;
            }
            if (i == 0 || value < low) {
                low = value;
                lowIndex = i;
            }
            if (high - low >= hysteresis) {
                // Heading from the older extreme towards the newer one
                boolean up = highIndex > lowIndex;
                direction = up ? 1 : -1;
                extremum = up ? high : low;
                extremumIndex = up ? highIndex : lowIndex;
                previousExtremumIndex = up ? lowIndex : highIndex;
            }
            return false;
        }

        if (direction * (value - extremum) > 0) {
            extremum = value;
            extremumIndex = i;
            return false;
        }
        if (direction * (extremum - value) < hysteresis) return false;

        boolean fast = extremumIndex - previousExtremumIndex <= maxSamples;
        previousExtremumIndex = extremumIndex;
        direction = -direction;
        extremum = value;
        extremumIndex = i;
        return fast;
    }

    public void reset() {
        index = 0;
        direction = 0;
    }
}
