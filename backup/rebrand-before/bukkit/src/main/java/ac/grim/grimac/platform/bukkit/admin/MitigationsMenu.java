package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.neural.NeuralRuntime;
import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminPlayerView;
import ac.grim.grimac.neural.admin.AdminSnapshot;
import ac.grim.grimac.neural.admin.AdminStyle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who has been acted on, with what, and on what evidence.
 *
 * <p>Read-only. There is no button here that starts or stops a mitigation: mitigation is decided by
 * the rules an operator configured, and an interface that let one be applied by hand would be a
 * punishment tool wearing a monitoring tool's clothes.
 */
public final class MitigationsMenu extends AeroMenu {
    private static final int PER_PAGE = 45;

    private int page;
    private final List<UUID> rendered = new ArrayList<>();

    public MitigationsMenu(BukkitAdminGui gui, Player viewer, int page) {
        super(gui, viewer);
        this.page = page;
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › MITIGATIONS"; }

    @Override public String permission() { return AdminPermissions.MITIGATION; }

    @Override protected int rows() { return 6; }

    @Override protected void draw(Inventory inventory) {
        NeuralRuntime runtime = gui.neural().runtime();
        List<AdminPlayerView> touched = new ArrayList<>();
        for (AdminPlayerView view : service().snapshot().players()) {
            if (view.mitigation() != null || view.mitigationCount() > 0) touched.add(view);
        }
        AdminSnapshot.Page<AdminPlayerView> current = AdminSnapshot.page(touched, page, PER_PAGE);
        page = current.index();

        rendered.clear();
        int slot = 0;
        for (AdminPlayerView view : current.items()) {
            boolean active = view.mitigation() != null;
            inventory.setItem(slot++, MenuItems.head(view.uuid(), view.name(),
                    (active ? MenuItems.BAD : MenuItems.LABEL) + view.name(),
                    List.of(MenuItems.line("Active", active ? view.mitigation() : "none",
                                    active ? MenuItems.BAD : MenuItems.MUTED),
                            MenuItems.line("Remaining", active ? view.mitigationRemainingMs() + "ms" : "-"),
                            MenuItems.line("Applied this session", String.valueOf(view.mitigationCount())),
                            MenuItems.line("Attacks dropped", String.valueOf(view.attacksSuppressed())),
                            MenuItems.line("Risk", AdminStyle.number(view.risk(), 2)),
                            "",
                            MenuItems.note("Click for the full mitigation history."))));
            rendered.add(view.uuid());
        }

        if (touched.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                    MenuItems.GOOD + "NOTHING MITIGATED",
                    MenuItems.note("No player online has been acted on this session.")));
        }

        List<String> lore = new ArrayList<>();
        if (runtime == null || !runtime.config().mitigation().enabled()) {
            lore.add(MenuItems.line("Mitigation", "disabled", MenuItems.MUTED));
        } else {
            lore.add(MenuItems.line("Minimum state", runtime.config().mitigation().minState().name()));
            lore.add(MenuItems.line("Cancels attacks",
                    runtime.config().mitigation().cancelAttacks() ? "yes" : "no"));
            lore.add(MenuItems.line("Cap", runtime.config().mitigation().maxPerHour() + " per hour"));
        }
        lore.add("");
        lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                "Mitigation is applied by the configured rules. This screen reports it; "
                        + "it cannot start or stop one.", 44));
        inventory.setItem(49, MenuItems.item(Material.IRON_BARS, MenuItems.HEADER + "MITIGATION RULES", lore));

        inventory.setItem(45, MenuItems.previousPage(current.hasPrevious()));
        inventory.setItem(53, MenuItems.nextPage(current.hasNext()));
        footer(inventory, this::back, "the Aero menu");
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
        if (slot == 45 && page > 0) {
            page--;
            redraw();
            return;
        }
        if (slot == 53) {
            page++;
            redraw();
            return;
        }
        if (slot >= rendered.size() || slot >= PER_PAGE) return;
        if (!permitted(AdminPermissions.MITIGATION)) {
            deny(AdminPermissions.MITIGATION);
            return;
        }
        gui.show(new HistoryMenu(gui, viewer, rendered.get(slot), HistoryMenu.Kind.MITIGATION, false));
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
