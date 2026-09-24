package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.neural.admin.AdminConfig;
import dev.aeroac.neural.admin.AdminStyle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Your own alert switch, and an explanation of when an alert can fire at all.
 *
 * <p>The toggle is per administrator and lasts for the session. The thresholds beside it are
 * configuration and are shown read-only: an operator who could raise the alert floor from a menu
 * would be changing what every other operator sees, from a screen that looks personal.
 */
public final class AlertsMenu extends AeroMenu {
    private static final int SLOT_TOGGLE = 13;

    public AlertsMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_alerts"); }

    @Override public String permission() { return AdminPermissions.ALERTS; }

    @Override protected int rows() { return 3; }

    @Override protected void draw(Inventory inventory) {
        boolean muted = service().alerts().muted(viewer.getUniqueId());
        AdminConfig.Alerts settings = service().config().alerts();

        inventory.setItem(SLOT_TOGGLE, MenuItems.item(muted ? Material.GRAY_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                muted ? MenuItems.MUTED + AeroMessages.tr("gui.alerts_off") : MenuItems.GOOD + AeroMessages.tr("gui.alerts_on"),
                MenuItems.note(AeroMessages.tr("gui.click_to_turn_them") + (muted ? AeroMessages.tr("gui.on") : AeroMessages.tr("gui.off")) + AeroMessages.tr("gui.for_yourself"))));

        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.enabled_on_this_server"), settings.enabled() ? AeroMessages.tr("gui.yes") : AeroMessages.tr("gui.no"),
                settings.enabled() ? MenuItems.GOOD : MenuItems.MUTED));
        lore.add(MenuItems.line(AeroMessages.tr("gui.minimum_state"), AdminLabels.state(settings.minState()),
                MenuItems.colourOf(settings.minState())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.throttle"), settings.throttleSeconds() + "s"));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.an_alert_fires_when_a_player_s_state_climbs_to_at_least_the")
                        + AeroMessages.tr("gui.once_per_throttle_for_that_player_a_state_that_falls_is_reco")
                        + AeroMessages.tr("gui.somebody_hovering_on_the_boundary_does_not_alert_on_every_cr"), 44));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.predictions_never_alert_the_model_produces_an_output_every_w")
                        + AeroMessages.tr("gui.that_would_be_a_stream_nobody_reads"), 44));
        inventory.setItem(11, MenuItems.item(Material.NOTE_BLOCK, MenuItems.HEADER + AeroMessages.tr("gui.when_alerts_fire"), lore));

        inventory.setItem(15, MenuItems.item(Material.PAPER, MenuItems.HEADER + AeroMessages.tr("gui.what_an_alert_says"),
                MenuItems.note(AeroMessages.tr("gui.aero_player_suspicious")),
                MenuItems.note(AeroMessages.tr("gui.risk_7_82") + AdminStyle.RISK_LABEL + " 91% | AIM"),
                "",
                MenuItems.note(AeroMessages.tr("gui.profile_and_watch_in_the_message_are")),
                MenuItems.note(AeroMessages.tr("gui.clickable_and_run_the_matching_command")),
                "",
                MenuItems.note(AeroMessages.tr("gui.the_percentage_is_a_model_output_it_is_not")),
                MenuItems.note(AeroMessages.tr("gui.a_measured_probability_that_someone_cheats"))));

        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) {
            viewer.closeInventory();
            return;
        }
        if (isFooterBack(slot, rows())) {
            back();
            return;
        }
        if (slot == SLOT_TOGGLE) {
            boolean nowEnabled = service().alerts().muted(viewer.getUniqueId());
            service().alerts().toggle(viewer.getUniqueId(), nowEnabled);
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.aero_alerts_2") + (nowEnabled ? AeroMessages.tr("gui.enabled") : AeroMessages.tr("gui.disabled"))
                    + AeroMessages.tr("gui.for_you"));
            redraw();
        }
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
