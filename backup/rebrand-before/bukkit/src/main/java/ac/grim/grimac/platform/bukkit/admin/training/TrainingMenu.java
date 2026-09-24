package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.neural.admin.AdminPermissions;
import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.DatasetSummary;
import ac.grim.grimac.neural.admin.training.TrainingJob;
import ac.grim.grimac.neural.dataset.DatasetJson;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MainMenu;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The training centre: collecting a dataset and watching what is done with it.
 *
 * <p>The whole point of this section is that collecting data should not require remembering command
 * syntax while a test subject waits. Everything here maps onto the recorder and the Python tooling
 * that already exist; nothing here is a second implementation of either.
 */
public final class TrainingMenu extends AeroMenu {
    private static final int SLOT_ACTIVE = 11;
    private static final int SLOT_START = 12;
    private static final int SLOT_OVERVIEW = 13;
    private static final int SLOT_RECENT = 14;
    private static final int SLOT_REVIEW = 15;
    private static final int SLOT_MODEL = 22;
    private static final int SLOT_COVERAGE = 20;
    private static final int SLOT_HEADER = 4;

    public TrainingMenu(BukkitAdminGui gui, Player viewer) {
        super(gui, viewer);
    }

    @Override protected String title() { return MenuItems.HEADER + "AERO › TRAINING CENTER"; }

    @Override public String permission() { return AdminPermissions.TRAINING; }

    @Override protected int rows() { return 4; }

    @Override protected void draw(Inventory inventory) {
        DatasetSummary dataset = service().datasetSummary();
        int active = service().snapshot().recordings().size();
        TrainingJob job = service().training().job();

        inventory.setItem(SLOT_HEADER, header(dataset, active));

        inventory.setItem(SLOT_ACTIVE, MenuItems.item(
                active > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + "ACTIVE RECORDINGS",
                MenuItems.line("Running now", String.valueOf(active),
                        active > 0 ? MenuItems.GOOD : MenuItems.MUTED),
                "",
                MenuItems.note("Progress, live quality counters and stop.")));

        inventory.setItem(SLOT_START, MenuItems.item(Material.WRITABLE_BOOK,
                MenuItems.HEADER + "START RECORDING",
                MenuItems.note("Pick a player, a label and the metadata"),
                MenuItems.note("that describes how the session was produced."),
                "",
                MenuItems.note("Requires " + AdminPermissions.TRAINING_RECORD)));

        inventory.setItem(SLOT_OVERVIEW, MenuItems.item(Material.BOOKSHELF,
                MenuItems.HEADER + "DATASET OVERVIEW",
                dataset.ready()
                        ? MenuItems.line("Sessions", String.valueOf(dataset.sessions()))
                        : MenuItems.note("Loading..."),
                MenuItems.note("Counts and breakdowns from session metadata.")));

        inventory.setItem(SLOT_COVERAGE, MenuItems.item(Material.MAP,
                MenuItems.HEADER + "COLLECTION COVERAGE",
                MenuItems.note("Which categories are thin against your own"),
                MenuItems.note("collection goals. Planning aid, not a verdict.")));

        inventory.setItem(SLOT_RECENT, MenuItems.item(Material.CLOCK,
                MenuItems.HEADER + "RECENT SESSIONS",
                MenuItems.note("The newest recordings and how they closed.")));

        inventory.setItem(SLOT_REVIEW, MenuItems.item(
                dataset.ready() && !dataset.attention().isEmpty()
                        ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + "REVIEW QUEUE",
                dataset.ready()
                        ? MenuItems.line("Needing a look", String.valueOf(dataset.attention().size()),
                                dataset.attention().isEmpty() ? MenuItems.GOOD : MenuItems.WARN)
                        : MenuItems.note("Loading..."),
                "",
                MenuItems.note("Imported REVIEW verdicts and sessions that"),
                MenuItems.note("did not close cleanly.")));

        inventory.setItem(SLOT_MODEL, MenuItems.item(Material.BEACON,
                MenuItems.HEADER + "MODEL TRAINING",
                MenuItems.line("Status", job.status().name(),
                        job.configured() ? MenuItems.VALUE : MenuItems.MUTED),
                "",
                MenuItems.note("Training runs outside this server."),
                MenuItems.note("The JVM never trains a model.")));

        footer(inventory, this::back, "the Aero menu");
        lockSlot(inventory, SLOT_START, AdminPermissions.TRAINING_RECORD);
        lockSlot(inventory, SLOT_OVERVIEW, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_RECENT, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_COVERAGE, AdminPermissions.TRAINING_OVERVIEW);
        lockSlot(inventory, SLOT_REVIEW, AdminPermissions.TRAINING_REVIEW);
        lockSlot(inventory, SLOT_MODEL, AdminPermissions.TRAINING_MODEL);
    }

    private org.bukkit.inventory.ItemStack header(DatasetSummary dataset, int active) {
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Active recordings", String.valueOf(active)));
        lore.add(MenuItems.line("Dataset version", DatasetJson.DATASET_VERSION));
        if (!permitted(AdminPermissions.TRAINING_OVERVIEW)) {
            lore.add(MenuItems.note("Dataset totals require " + AdminPermissions.TRAINING_OVERVIEW));
        } else if (dataset.ready()) {
            lore.add(MenuItems.line("Sessions", String.valueOf(dataset.sessions())));
            lore.add(MenuItems.line("LEGIT", String.valueOf(dataset.legit()), MenuItems.GOOD));
            lore.add(MenuItems.line("CHEAT", String.valueOf(dataset.cheat()), MenuItems.BAD));
            lore.add(MenuItems.line("Attack windows", dataset.audit().attackWindows() < 0
                    ? AdminStyle.NO_DATA : String.valueOf(dataset.audit().attackWindows())));
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    "Session metadata records frames, not how many attack windows could be built "
                            + "from them. The offline audit counts those.", 44));
            if (dataset.truncated()) {
                lore.add(MenuItems.note("Only the newest sessions were read."));
            }
        } else {
            lore.add(MenuItems.note(dataset.failedToLoad()
                    ? "Summary unavailable: " + dataset.error()
                    : "Reading session metadata in the background..."));
        }
        return MenuItems.item(Material.NETHER_STAR, MenuItems.HEADER + "TRAINING CENTER", lore);
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
        switch (slot) {
            case SLOT_ACTIVE -> gui.show(new RecordingsMenu(gui, viewer));
            case SLOT_START -> {
                if (permitted(AdminPermissions.TRAINING_RECORD)) {
                    service().clearDraft(viewer.getUniqueId());
                    gui.show(new WizardMenu(gui, viewer, WizardMenu.Step.PLAYER));
                } else {
                    deny(AdminPermissions.TRAINING_RECORD);
                }
            }
            case SLOT_OVERVIEW -> open(DatasetMenu.Kind.OVERVIEW);
            case SLOT_COVERAGE -> open(DatasetMenu.Kind.COVERAGE);
            case SLOT_RECENT -> open(DatasetMenu.Kind.RECENT);
            case SLOT_REVIEW -> open(DatasetMenu.Kind.REVIEW);
            case SLOT_MODEL -> {
                if (permitted(AdminPermissions.TRAINING_MODEL)) gui.show(new ModelMenu(gui, viewer, false));
                else deny(AdminPermissions.TRAINING_MODEL);
            }
            default -> { }
        }
    }

    private void open(DatasetMenu.Kind kind) {
        String needed = kind == DatasetMenu.Kind.REVIEW
                ? AdminPermissions.TRAINING_REVIEW : AdminPermissions.TRAINING_OVERVIEW;
        if (permitted(needed)) gui.show(new DatasetMenu(gui, viewer, kind, 0));
        else deny(needed);
    }

    private void back() {
        gui.show(new MainMenu(gui, viewer));
    }
}
