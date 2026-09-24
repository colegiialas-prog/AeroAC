package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.neural.risk.RiskState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Item construction for every Aero screen, so the interface looks like one thing.
 *
 * <p>Five state colours and nothing else. The temptation on an inventory screen is to reach for a
 * different dye for every row; the result is a wall an operator has to decode instead of read. Here
 * a colour means a state, always the same state, and anything that is not a state is grey.
 *
 * <p>Legacy section-sign strings rather than components on purpose: this plugin declares API 1.13,
 * and the component overloads for inventory titles and item metadata do not exist that far back.
 */
public final class MenuItems {
    public static final String HEADER = "§b§l";
    public static final String LABEL = "§7";
    public static final String VALUE = "§f";
    public static final String MUTED = "§8";
    public static final String WARN = "§6";
    public static final String BAD = "§c";
    public static final String GOOD = "§a";

    private MenuItems() { }

    public static String colourOf(RiskState state) {
        if (state == null) return MUTED;
        return switch (state) {
            case CLEAN -> "§a";
            case WATCH -> "§e";
            case SUSPICIOUS -> "§6";
            case MITIGATED -> "§c";
            case CONFIRMED -> "§4";
        };
    }

    public static String colourOf(DatasetMetadata.Label label) {
        if (label == null) return MUTED;
        return switch (label) {
            case LEGIT -> "§a";
            case CHEAT -> "§c";
            case UNLABELED -> "§e";
        };
    }

    /** The colour that stands for a training job's state: the interface's tone, not the job's data. */
    public static String colourOf(dev.aeroac.neural.admin.training.TrainingStatus.Tone tone) {
        if (tone == null) return MUTED;
        return switch (tone) {
            case ACTIVE -> WARN;
            case GOOD -> GOOD;
            case BAD -> BAD;
            case MUTED -> MUTED;
        };
    }

    /** The pane that stands for a state, used for the risk timeline and the state legend. */
    public static Material paneOf(RiskState state) {
        if (state == null) return Material.GRAY_STAINED_GLASS_PANE;
        return switch (state) {
            case CLEAN -> Material.LIME_STAINED_GLASS_PANE;
            case WATCH -> Material.YELLOW_STAINED_GLASS_PANE;
            case SUSPICIOUS -> Material.ORANGE_STAINED_GLASS_PANE;
            case MITIGATED -> Material.RED_STAINED_GLASS_PANE;
            // No darker red pane exists, so CONFIRMED takes a solid block: it is the one state that
            // should not look like the others at a glance.
            case CONFIRMED -> Material.RED_CONCRETE;
        };
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    /**
     * A player head, falling back to a plain item when the head cannot be resolved.
     *
     * <p>Skull metadata is one of the few places a GUI can accidentally block: resolving a profile
     * that is not cached can hit the network. Only players who are online right now are shown here,
     * so the profile is always local.
     */
    public static ItemStack head(UUID uuid, String name, String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                var player = Bukkit.getPlayer(uuid);
                if (player != null) skull.setOwningPlayer(player);
            } catch (RuntimeException unavailable) {
                // A head without a skin is still a readable row; never fail a screen over a texture.
            }
        }
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack filler() {
        return item(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    public static ItemStack back(String where) {
        return item(Material.ARROW, AeroMessages.tr("gui.7_lback"), MUTED + AeroMessages.tr("gui.return_to") + where);
    }

    public static ItemStack close() {
        return item(Material.BARRIER, BAD + AeroMessages.tr("gui.lclose"));
    }

    public static ItemStack previousPage(boolean enabled) {
        return enabled
                ? item(Material.ARROW, AeroMessages.tr("gui.7_lprevious_page"))
                : item(Material.GRAY_STAINED_GLASS_PANE, MUTED + AeroMessages.tr("gui.previous_page"), MUTED + AeroMessages.tr("gui.already_at_the_first_page"));
    }

    public static ItemStack nextPage(boolean enabled) {
        return enabled
                ? item(Material.ARROW, AeroMessages.tr("gui.7_lnext_page"))
                : item(Material.GRAY_STAINED_GLASS_PANE, MUTED + AeroMessages.tr("gui.next_page"), MUTED + AeroMessages.tr("gui.already_at_the_last_page"));
    }

    /** A line of the form {@code  Label: value}, so every screen aligns the same way. */
    public static String line(String label, String value) {
        return LABEL + label + ": " + VALUE + value;
    }

    public static String line(String label, String value, String colour) {
        return LABEL + label + ": " + colour + value;
    }

    public static String note(String text) {
        return MUTED + text;
    }

    /** Wraps a long explanation so it does not run off the side of the lore box. */
    public static List<String> wrap(String colour, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > width && current.length() > 0) {
                lines.add(colour + current);
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(colour + current);
        return lines;
    }

    /** The AI Risk line, worded the same way everywhere and never claiming to be a probability. */
    public static String riskLine(double overall) {
        return Double.isNaN(overall)
                ? LABEL + AdminStyle.RISK_LABEL + ": " + MUTED + AdminStyle.NO_DATA
                : LABEL + AdminStyle.RISK_LABEL + ": " + VALUE + AdminStyle.percent(overall);
    }

    public static String headLine(String name, double value) {
        return Double.isNaN(value)
                ? LABEL + name + ": " + MUTED + AdminStyle.NO_DATA
                : LABEL + name + ": " + VALUE + AdminStyle.percent(value);
    }

    public static String progress(String label, long done, long target, String colour) {
        double fraction = target <= 0 ? 0 : Math.min(1.0, done / (double) target);
        return LABEL + label + ": " + colour + done + MUTED + " / " + target + "  " + colour
                + AdminStyle.bar(fraction, 10);
    }

    /** Fills every empty slot, so a half-drawn screen never shows the player's own inventory behind it. */
    public static void fill(Inventory inventory) {
        ItemStack filler = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
        }
    }
}
