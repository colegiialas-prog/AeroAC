package dev.aeroac.platform.bukkit.admin.bot;

import dev.aeroac.locale.AeroMessages;
import dev.aeroac.neural.admin.AdminPermissions;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
import dev.aeroac.platform.bukkit.admin.training.TrainingMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.Locale;

/**
 * The operator's sparring bot: spawn it, then change how it moves, fights and is dressed. Every
 * click applies immediately to the live bot. Collecting data against it is the usual recording:
 * start a LEGIT or CHEAT session on yourself and fight the bot.
 */
public final class BotMenu extends AeroMenu {
    private static final int SLOT_SPAWN = 10, SLOT_MODE = 11, SLOT_SPEED = 12, SLOT_DISTANCE = 13, SLOT_JUMP = 14,
            SLOT_KNOCKBACK = 15, SLOT_DAMAGE = 16, SLOT_ARMOR = 19, SLOT_WEAPON = 20, SLOT_IMMORTAL = 21,
            SLOT_HEALTH = 22, SLOT_TELEPORT = 23, SLOT_RECORD = 25;

    public BotMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    private TrainingBots bots() { return gui.bots(); }

    private BotSettings settings() { return bots().settings(viewer.getUniqueId()); }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.bot.title"); }

    @Override public String permission() { return AdminPermissions.TRAINING_RECORD; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        BotSettings s = settings();
        boolean active = bots().active(viewer.getUniqueId());
        inventory.setItem(4, MenuItems.item(Material.ZOMBIE_HEAD, MenuItems.HEADER + AeroMessages.tr("gui.bot.title"),
                MenuItems.note(AeroMessages.tr("gui.bot.about1")), MenuItems.note(AeroMessages.tr("gui.bot.about2"))));
        inventory.setItem(SLOT_SPAWN, active
                ? MenuItems.item(Material.RED_CONCRETE, MenuItems.BAD + AeroMessages.tr("gui.bot.remove"), MenuItems.note(AeroMessages.tr("gui.bot.remove_hint")))
                : MenuItems.item(Material.LIME_CONCRETE, MenuItems.GOOD + AeroMessages.tr("gui.bot.spawn"), MenuItems.note(AeroMessages.tr("gui.bot.spawn_hint"))));
        inventory.setItem(SLOT_MODE, option(Material.COMPASS, "gui.bot.mode", AeroMessages.tr("gui.bot.mode." + s.mode.name().toLowerCase(Locale.ROOT)),
                AeroMessages.tr("gui.bot.mode_hint")));
        inventory.setItem(SLOT_SPEED, option(Material.SUGAR, "gui.bot.speed", AeroMessages.tr("gui.bot.speed." + s.speed), null));
        inventory.setItem(SLOT_DISTANCE, option(Material.LEAD, "gui.bot.distance", fmt(s.distanceValue()) + " " + AeroMessages.tr("gui.bot.blocks"), null));
        inventory.setItem(SLOT_JUMP, toggle(Material.RABBIT_FOOT, "gui.bot.jumping", s.jumping));
        inventory.setItem(SLOT_KNOCKBACK, option(Material.SLIME_BALL, "gui.bot.knockback", Math.round(s.knockbackValue() * 100) + "%", null));
        inventory.setItem(SLOT_DAMAGE, option(Material.IRON_SWORD, "gui.bot.damage",
                s.damageValue() == 0 ? AeroMessages.tr("gui.bot.off") : fmt(s.damageValue() / 2) + " ❤", AeroMessages.tr("gui.bot.damage_hint")));
        inventory.setItem(SLOT_ARMOR, option(armorIcon(s.armor), "gui.bot.armor", AeroMessages.tr("gui.bot.armor." + TrainingBots.ARMOR[s.armor].toLowerCase(Locale.ROOT)), null));
        Material weapon = Material.matchMaterial(TrainingBots.WEAPONS[s.weapon]);
        inventory.setItem(SLOT_WEAPON, option(weapon == null ? Material.STICK : weapon, "gui.bot.weapon",
                weapon == null ? AeroMessages.tr("gui.bot.off") : TrainingBots.WEAPONS[s.weapon].toLowerCase(Locale.ROOT).replace('_', ' '), null));
        inventory.setItem(SLOT_IMMORTAL, toggle(Material.TOTEM_OF_UNDYING, "gui.bot.immortal", s.immortal));
        inventory.setItem(SLOT_HEALTH, option(Material.GOLDEN_APPLE, "gui.bot.health", fmt(s.healthValue() / 2) + " ❤", null));
        inventory.setItem(SLOT_TELEPORT, MenuItems.item(Material.ENDER_PEARL, MenuItems.HEADER + AeroMessages.tr("gui.bot.teleport")));
        inventory.setItem(SLOT_RECORD, MenuItems.item(Material.WRITABLE_BOOK, MenuItems.HEADER + AeroMessages.tr("gui.bot.record"),
                MenuItems.note(AeroMessages.tr("gui.bot.record_legit")), MenuItems.note(AeroMessages.tr("gui.bot.record_cheat"))));
        footer(inventory, () -> gui.show(new TrainingMenu(gui, viewer)), AeroMessages.tr("gui.training.aero_training_center"));
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Material armorIcon(int armor) {
        Material icon = Material.matchMaterial(TrainingBots.ARMOR[armor] + "_CHESTPLATE");
        return icon == null ? Material.GLASS : icon;
    }

    private static org.bukkit.inventory.ItemStack option(Material icon, String key, String value, String hint) {
        return hint == null
                ? MenuItems.item(icon, MenuItems.HEADER + AeroMessages.tr(key), MenuItems.line(AeroMessages.tr("gui.bot.now"), value),
                MenuItems.note(AeroMessages.tr("gui.bot.click_to_change")))
                : MenuItems.item(icon, MenuItems.HEADER + AeroMessages.tr(key), MenuItems.line(AeroMessages.tr("gui.bot.now"), value),
                MenuItems.note(hint), MenuItems.note(AeroMessages.tr("gui.bot.click_to_change")));
    }

    private static org.bukkit.inventory.ItemStack toggle(Material icon, String key, boolean on) {
        return MenuItems.item(icon, MenuItems.HEADER + AeroMessages.tr(key),
                MenuItems.line(AeroMessages.tr("gui.bot.now"), on ? AeroMessages.tr("gui.bot.on") : AeroMessages.tr("gui.bot.off"),
                        on ? MenuItems.GOOD : MenuItems.MUTED), MenuItems.note(AeroMessages.tr("gui.bot.click_to_change")));
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) { viewer.closeInventory(); return; }
        if (isFooterBack(slot, rows())) { gui.show(new TrainingMenu(gui, viewer)); return; }
        BotSettings s = settings();
        boolean back = event.isRightClick();
        switch (slot) {
            case SLOT_SPAWN -> {
                if (bots().active(viewer.getUniqueId())) bots().remove(viewer.getUniqueId()); else bots().spawn(viewer);
            }
            case SLOT_MODE -> s.mode = BotSettings.Mode.values()[cycle(s.mode.ordinal(), BotSettings.Mode.values().length, back)];
            case SLOT_SPEED -> s.speed = cycle(s.speed, BotSettings.SPEEDS.length, back);
            case SLOT_DISTANCE -> s.distance = cycle(s.distance, BotSettings.DISTANCE.length, back);
            case SLOT_JUMP -> s.jumping = !s.jumping;
            case SLOT_KNOCKBACK -> s.knockback = cycle(s.knockback, BotSettings.KNOCKBACK.length, back);
            case SLOT_DAMAGE -> s.damage = cycle(s.damage, BotSettings.DAMAGE.length, back);
            case SLOT_ARMOR -> { s.armor = cycle(s.armor, TrainingBots.ARMOR.length, back); bots().apply(viewer.getUniqueId()); }
            case SLOT_WEAPON -> { s.weapon = cycle(s.weapon, TrainingBots.WEAPONS.length, back); bots().apply(viewer.getUniqueId()); }
            case SLOT_IMMORTAL -> s.immortal = !s.immortal;
            case SLOT_HEALTH -> { s.health = cycle(s.health, BotSettings.HEALTH.length, back); bots().apply(viewer.getUniqueId()); }
            case SLOT_TELEPORT -> bots().teleportToOwner(viewer);
            case SLOT_RECORD -> {
                viewer.closeInventory();
                viewer.sendMessage(MenuItems.HEADER + AeroMessages.tr("gui.bot.record"));
                viewer.sendMessage(MenuItems.VALUE + "/aero rec " + viewer.getName() + MenuItems.MUTED + "  — " + AeroMessages.tr("gui.bot.record_legit"));
                viewer.sendMessage(MenuItems.VALUE + "/aero rec " + viewer.getName() + " aimassist" + MenuItems.MUTED + "  — " + AeroMessages.tr("gui.bot.record_cheat"));
                viewer.sendMessage(MenuItems.VALUE + "/aero rec stop " + viewer.getName());
                return;
            }
            default -> { return; }
        }
        redraw();
    }

    private static int cycle(int value, int length, boolean back) {
        return back ? (value - 1 + length) % length : (value + 1) % length;
    }
}
