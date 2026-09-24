package dev.aeroac.checks.impl.movement;

/**
 * Decides when unslowed item-use ticks add up to NoSlow. Pure logic, so the patterns a NoSlow client
 * produces can be replayed in a unit test without a player.
 *
 * <p>Two rules. Two unslowed ticks in a row flag immediately, as Grim always did. And every unslowed
 * tick adds 1 to a buffer that every other tick drains by {@code decay}; the buffer reaching
 * {@code threshold} flags too. The second rule is the fix: "skip the slowdown only on even use ticks"
 * never produces two unslowed ticks in a row, so under the first rule alone it was never caught.
 *
 * <p>A pattern with one unslowed tick in every {@code n} accumulates {@code 1 - (n - 1) * decay} per
 * cycle, so it is caught for every {@code n < 1 + 1 / decay} — with the default 0.2, anything more
 * frequent than one tick in six. A lone desynced tick when a use starts or ends drains away and never
 * reaches the threshold.
 */
public final class UnslowedTickBuffer {
    private final double threshold;
    private final double decay;
    private double buffer;
    private boolean lastUnslowed;

    public UnslowedTickBuffer(double threshold, double decay) {
        if (!(threshold >= 1.0) || !(decay >= 0.0)) throw new IllegalArgumentException("threshold >= 1, decay >= 0");
        this.threshold = threshold;
        this.decay = decay;
    }

    /**
     * One checked tick while the player is using an item.
     *
     * @param unslowed the movement only matched predictions without the item slowdown
     * @param excused  a known-legitimate unslowed tick (1.8 slot change); it cannot add to the buffer
     * @return whether this tick flags
     */
    public boolean usingItem(boolean unslowed, boolean excused) {
        if (!unslowed) {
            drain();
            lastUnslowed = false;
            return false;
        }
        if (!excused) buffer += 1.0;
        boolean flag = (lastUnslowed && !excused) || buffer >= threshold;
        lastUnslowed = true;
        return flag;
    }

    /** One checked tick without an item in use; the next use starts a new run of consecutive ticks. */
    public void notUsingItem() {
        drain();
        lastUnslowed = false;
    }

    private void drain() {
        buffer = Math.max(0.0, buffer - decay);
    }

    public double value() {
        return buffer;
    }
}
