package ac.grim.grimac.neural.admin;

import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.neural.risk.RiskState;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * One colour and one wording for a state, everywhere it appears.
 *
 * <p>Five colours, each meaning exactly one state, so a screen can be read at a glance instead of
 * decoded. The percentage wording matters as much as the colour: what the model returns is called
 * AI Risk and never a chance of cheating, because an experimental head that has not been calibrated
 * against a real labelled dataset is not a probability of anything, and an operator acting on the
 * word "chance" would be acting on a claim nobody has earned yet.
 */
public final class AdminStyle {
    /** Shown instead of a number whenever the model produced nothing. Never 0%. */
    public static final String NO_DATA = "NO DATA";
    /** The heading over any model output. */
    public static final String RISK_LABEL = "AI Risk";

    private AdminStyle() { }

    public static NamedTextColor colour(RiskState state) {
        if (state == null) return NamedTextColor.GRAY;
        return switch (state) {
            case CLEAN -> NamedTextColor.GREEN;
            case WATCH -> NamedTextColor.YELLOW;
            case SUSPICIOUS -> NamedTextColor.GOLD;
            case MITIGATED -> NamedTextColor.RED;
            case CONFIRMED -> NamedTextColor.DARK_RED;
        };
    }

    /** A quiet dot for a player nobody needs to look at, a warning sign for one who is flagged. */
    public static String symbol(RiskState state) {
        return state != null && state.atLeast(RiskState.WATCH) ? "⚠" : "●";
    }

    public static NamedTextColor colour(DatasetMetadata.Label label) {
        if (label == null) return NamedTextColor.GRAY;
        return switch (label) {
            case LEGIT -> NamedTextColor.GREEN;
            case CHEAT -> NamedTextColor.RED;
            case UNLABELED -> NamedTextColor.YELLOW;
        };
    }

    /** "87%", or NO DATA. A missing prediction is never rendered as a number. */
    public static String percent(double value) {
        return !Double.isFinite(value) ? NO_DATA : Math.round(value * 100) + "%";
    }

    public static String number(double value, int decimals) {
        return !Double.isFinite(value) ? NO_DATA : String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** "1m 32s" for a duration an operator reads as elapsed time. Negative means unknown. */
    public static String duration(long seconds) {
        if (seconds < 0) return NO_DATA;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** "03:42" for a running recording, where the shape of the number matters more than the words. */
    public static String clock(long seconds) {
        if (seconds < 0) return "--:--";
        long minutes = seconds / 60;
        if (minutes < 100) return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60);
    }

    public static String age(long millis) {
        if (millis < 0) return NO_DATA;
        if (millis < 1000) return millis + "ms";
        return duration(millis / 1000) + " ago";
    }

    /** A text progress bar for lore, where an inventory has no other way to show a fraction. */
    public static String bar(double fraction, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        StringBuilder text = new StringBuilder(width);
        for (int i = 0; i < width; i++) text.append(i < filled ? '■' : '□');
        return text.toString();
    }

    public static String blankIfEmpty(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }
}
