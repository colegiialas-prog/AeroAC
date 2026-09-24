package dev.aeroac.platform.bukkit.admin;

import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.AeroAPI;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.risk.EvidenceType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic check evidence, totalled across everyone online.
 *
 * <p>Deliberately a separate screen from the model heads. A deterministic check proved something
 * about geometry; a model head produced a number. Mixing them on one screen would let an operator
 * read a model score as if a check had fired, and this whole subsystem is built so that never
 * happens — checks keep their own violations and their own punishments, and what is shown here is
 * only the share of them that also entered the risk engine as one evidence family among several.
 */
public final class ChecksMenu extends AeroMenu {
    public ChecksMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + AeroMessages.tr("gui.aero_checks"); }

    @Override public String permission() { return AdminPermissions.STATUS; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        List<AdminPlayerView> players = service().snapshot().players();

        int slot = 10;
        for (EvidenceType type : EvidenceType.values()) {
            if (type.fromModel()) continue;
            long total = 0;
            int carriers = 0;
            for (AdminPlayerView view : players) {
                long count = view.evidenceOf(type);
                total += count;
                if (count > 0) carriers++;
            }
            inventory.setItem(slot++, MenuItems.item(Material.COMPARATOR,
                    MenuItems.HEADER + type.name(),
                    MenuItems.line(AeroMessages.tr("gui.accepted_as_evidence"), String.valueOf(total)),
                    MenuItems.line(AeroMessages.tr("gui.players_carrying_it"), String.valueOf(carriers)),
                    "",
                    MenuItems.note(describe(type))));
            if (slot == 17) break;
        }

        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line(AeroMessages.tr("gui.grim_evidence_weight"), runtime == null ? AdminStyle.NO_DATA
                : AdminStyle.number(runtime.config().risk().grimWeight(), 2)));
        lore.add(MenuItems.line(AeroMessages.tr("gui.model_evidence_weight"), runtime == null ? AdminStyle.NO_DATA
                : AdminStyle.number(runtime.config().risk().aiWeight(), 2)));
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                AeroMessages.tr("gui.these_totals_count_check_results_that_were_accepted_as_risk")
                        + AeroMessages.tr("gui.online_right_now_a_check_s_own_violation_count_its_alerts_an")
                        + AeroMessages.tr("gui.punishments_are_untouched_by_the_neural_subsystem_and_are_no"), 44));
        inventory.setItem(22, MenuItems.item(Material.BOOK, MenuItems.HEADER + AeroMessages.tr("gui.how_to_read_this"), lore));

        footer(inventory, this::back, AeroMessages.tr("gui.the_aero_menu"));
    }

    private static String describe(EvidenceType type) {
        return switch (type) {
            case GRIM_REACH -> "Attack beyond a provable reach.";
            case GRIM_WALL_HIT -> "Attack through a solid block.";
            case GRIM_ENTITY_PIERCE -> "Attack through another entity.";
            case GRIM_PACKET_ORDER -> "Impossible ordering of client packets.";
            case SESSION_ANOMALY -> "Something wrong with the connection itself.";
            default -> "";
        };
    }

    @Override public void click(InventoryClickEvent event) {
        if (isFooterClose(event.getSlot(), rows())) viewer.closeInventory();
        else if (isFooterBack(event.getSlot(), rows())) back();
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
