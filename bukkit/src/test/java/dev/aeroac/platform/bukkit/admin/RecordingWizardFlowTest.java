package dev.aeroac.platform.bukkit.admin;

import ac.grim.grimac.api.config.ConfigManager;
import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.NeuralManager;
import dev.aeroac.neural.NeuralPlayerState;
import dev.aeroac.neural.NeuralRuntime;
import dev.aeroac.neural.admin.AdminAlerts;
import dev.aeroac.neural.admin.AdminConfig;
import dev.aeroac.neural.admin.AdminPlayerView;
import dev.aeroac.neural.admin.AdminService;
import dev.aeroac.neural.admin.AdminSnapshot;
import dev.aeroac.neural.admin.AdminViewMode;
import dev.aeroac.neural.admin.training.DatasetSummary;
import dev.aeroac.neural.admin.training.RecordingDraft;
import dev.aeroac.neural.admin.training.TrainingServiceClient;
import dev.aeroac.neural.dataset.DatasetMetadata;
import dev.aeroac.platform.bukkit.admin.training.QuickCheatMenu;
import dev.aeroac.platform.bukkit.admin.training.QuickRecordMenu;
import dev.aeroac.platform.bukkit.admin.training.WizardMenu;
import dev.aeroac.player.AeroPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Walks the recording wizard the way an operator does: from an empty draft, one click at a time.
 *
 * <p>The existing renderer test starts from a draft that is already filled in, so every step has
 * something to show. This one starts from nothing, which is the state an operator is actually in
 * when they open START RECORDING, and asserts that each step they land on has something to click.
 */
class RecordingWizardFlowTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<MenuItems> items;
    private BukkitAdminGui gui;
    private AdminService service;
    private Player viewer;
    private final UUID viewerId = UUID.nameUUIDFromBytes("flow-admin".getBytes());
    private final UUID targetId = UUID.nameUUIDFromBytes("flow-target".getBytes());
    private final Map<UUID, RecordingDraft> drafts = new HashMap<>();
    private final Map<ItemStack, String> names = new IdentityHashMap<>();
    private final Map<Inventory, ItemStack[]> inventories = new IdentityHashMap<>();
    private NeuralManager neuralManager;
    private AeroPlayer tracked;

    @BeforeEach void setup() {
        gui = mock(BukkitAdminGui.class);
        service = mock(AdminService.class);
        viewer = mock(Player.class);
        when(gui.service()).thenReturn(service);
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.getName()).thenReturn("FlowAdmin");
        when(viewer.hasPermission(anyString())).thenReturn(true);
        ConfigManager settings = (ConfigManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConfigManager.class},
                (proxy, method, args) -> args != null && args.length == 2 ? args[1] : null);
        when(service.config()).thenReturn(AdminConfig.read(settings));
        when(service.alerts()).thenReturn(new AdminAlerts());
        when(service.viewMode(any())).thenReturn(AdminViewMode.OFF);
        when(service.draft(any())).thenAnswer(call -> drafts.computeIfAbsent(call.getArgument(0), id -> new RecordingDraft()));
        when(service.peekDraft(any())).thenAnswer(call -> drafts.get(call.getArgument(0)));
        when(service.training()).thenReturn(new TrainingServiceClient.Offline());
        when(service.datasetSummary()).thenReturn(DatasetSummary.PENDING);
        neuralManager = mock(NeuralManager.class);
        when(gui.neural()).thenReturn(neuralManager);
        when(neuralManager.runtime()).thenReturn(new NeuralRuntime(1, NeuralConfig.read(settings), null, () -> null));
        tracked = mock(AeroPlayer.class);
        when(tracked.getNeuralState()).thenReturn(new NeuralPlayerState());
        when(tracked.getUniqueId()).thenReturn(targetId);
        when(tracked.getName()).thenReturn("FlowTarget");
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(tracked).runSafely(any());
        when(gui.tracked(targetId)).thenReturn(tracked);
        when(service.snapshot()).thenReturn(AdminSnapshot.of(
                List.of(AdminPlayerView.empty(targetId, "FlowTarget", 42)), 1));

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
            Inventory inventory = mock(Inventory.class);
            int size = call.getArgument(1);
            ItemStack[] contents = new ItemStack[size];
            inventories.put(inventory, contents);
            when(inventory.getSize()).thenReturn(size);
            when(inventory.getHolder()).thenReturn(call.getArgument(0));
            when(inventory.getItem(anyInt())).thenAnswer(get -> contents[(int) get.getArgument(0)]);
            doAnswer(set -> { contents[(int) set.getArgument(0)] = set.getArgument(1); return null; })
                    .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            doAnswer(clear -> { Arrays.fill(contents, null); return null; }).when(inventory).clear();
            return inventory;
        });
        items = mockStatic(MenuItems.class, call -> {
            String method = call.getMethod().getName();
            if (method.equals("item")) {
                Object[] args = call.getRawArguments();
                return named((Material) args[0], (String) args[1]);
            }
            if (method.equals("head")) return named(Material.PLAYER_HEAD, call.getArgument(2));
            return call.callRealMethod();
        });
    }

    private ItemStack named(Material material, String name) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getDisplayName()).thenReturn(name);
        names.put(stack, name);
        return stack;
    }

    @AfterEach void teardown() { if (items != null) items.close(); if (bukkit != null) bukkit.close(); }

    /** Every slot that is not the black filler pane, as "slot=name". */
    private List<String> clickable(AeroMenu menu) {
        ItemStack[] contents = inventories.get(menu.getInventory());
        List<String> found = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null) continue;
            String name = names.get(item);
            if (name == null || name.isBlank()) continue;
            found.add(slot + "=" + name);
        }
        return found;
    }

    private AeroMenu clickAndCapture(AeroMenu menu, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(menu.getInventory());
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(viewer);
        when(event.getClickedInventory()).thenReturn(menu.getInventory());
        when(event.getSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        clearInvocations(gui);
        menu.click(event);
        ArgumentCaptor<AeroMenu> opened = ArgumentCaptor.forClass(AeroMenu.class);
        verify(gui, atLeast(0)).show(opened.capture());
        List<AeroMenu> shown = opened.getAllValues();
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }

    /**
     * Walks the whole LEGIT path from an empty draft, asserting every screen is usable.
     *
     * <p>Written after an operator reported that picking a player led to a screen with nothing on
     * it. Two steps had no presets configured at all, so they rendered as a title, a book and two
     * unexplained buttons; the forward control also moved between slots depending on what had been
     * filled in. A step with nothing to click is a dead end, and this asserts there is none.
     */
    @Test void everyStepFromAnEmptyDraftHasSomethingToClick() {
        AeroMenu players = new WizardMenu(gui, viewer, WizardMenu.Step.PLAYER);
        players.redraw();
        assertFalse(clickable(players).isEmpty(), "SELECT PLAYER rendered nothing");

        AeroMenu label = clickAndCapture(players, 9);
        assertNotNull(label, "clicking a player opened no screen at all");
        label.redraw();
        List<String> onLabel = clickable(label);
        assertTrue(onLabel.stream().anyMatch(entry -> entry.contains("LEGIT")), onLabel.toString());
        assertTrue(onLabel.stream().anyMatch(entry -> entry.contains("CHEAT")), onLabel.toString());

        AeroMenu client = clickAndCapture(label, 20);
        assertNotNull(client, "LEGIT opened no screen");
        client.redraw();
        assertPresetStep(client, "CLIENT");

        // Pick the first preset rather than skipping, so the forward control is exercised too.
        AeroMenu configuration = clickAndCapture(client, 9);
        assertNotNull(configuration, "choosing a client opened no screen");
        configuration.redraw();
        assertPresetStep(configuration, "CONFIGURATION");

        AeroMenu scenario = clickAndCapture(configuration, 9);
        assertNotNull(scenario, "choosing a configuration opened no screen");
        scenario.redraw();
        assertPresetStep(scenario, "SCENARIO");

        AeroMenu confirm = clickAndCapture(scenario, 9);
        assertNotNull(confirm, "choosing a scenario opened no screen");
        confirm.redraw();
        List<String> onConfirm = clickable(confirm);
        assertTrue(onConfirm.stream().anyMatch(entry -> entry.startsWith("49=")),
                "CONFIRM has no start control: " + onConfirm);

        RecordingDraft draft = drafts.get(viewerId);
        assertNotNull(draft);
        assertNull(draft.problem(), "the wizard produced a draft the recorder would refuse");
        assertEquals("FlowTarget", draft.targetName());
    }

    /** A preset step must offer presets, a free-text route and a forward control in a fixed slot. */
    private void assertPresetStep(AeroMenu menu, String step) {
        List<String> found = clickable(menu);
        long presets = found.stream().filter(entry -> {
            int slot = Integer.parseInt(entry.substring(0, entry.indexOf('=')));
            return slot >= 9 && slot < 45;
        }).count();
        assertTrue(presets > 0, step + " offers no presets to choose from: " + found);
        assertTrue(found.stream().anyMatch(entry -> entry.startsWith("47=")),
                step + " has no free-text route: " + found);
        assertTrue(found.stream().anyMatch(entry -> entry.startsWith("49=")),
                step + " has no forward control in slot 49: " + found);
    }

    /** The wizard says which step this is and what it wants, on every step. */
    @Test void everyStepNumbersItselfAndExplainsWhatItWants() {
        for (WizardMenu.Step step : WizardMenu.Step.values()) {
            drafts.clear();
            drafts.computeIfAbsent(viewerId, id -> new RecordingDraft()).target(targetId, "FlowTarget");
            AeroMenu menu = new WizardMenu(gui, viewer, step);
            menu.redraw();
            List<String> found = clickable(menu);
            assertTrue(found.stream().anyMatch(entry -> entry.startsWith("4=")),
                    step + " has no draft card: " + found);
        }
    }

    /**
     * One click on a head opens an honest-play session, with nothing invented around it.
     *
     * <p>This is the path an operator uses while somebody waits: the long form asks eight questions
     * and seven of them are optional, so the quick screen asks none of them and records the two
     * fields the metadata actually requires.
     */
    @Test void oneClickRecordsHonestPlay() {
        AeroMenu quick = new QuickRecordMenu(gui, viewer);
        quick.redraw();
        assertFalse(clickable(quick).isEmpty());

        clickAndCapture(quick, 9);

        ArgumentCaptor<String> family = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> assist = ArgumentCaptor.forClass(String.class);
        verify(neuralManager).startSession(eq(tracked), eq(DatasetMetadata.Label.LEGIT),
                family.capture(), eq(""), eq(""), eq(""), assist.capture(), eq(""), any());
        assertEquals("", family.getValue(), "honest play carries no cheat family");
        assertEquals("NONE", assist.getValue(), "LEGIT is always NONE");
    }

    /** A cheat session needs its family and nothing else; the strength stays UNKNOWN, never NONE. */
    @Test void twoClicksRecordACheatWithUnknownStrength() {
        AeroMenu quick = new QuickRecordMenu(gui, viewer);
        quick.redraw();

        InventoryClickEvent right = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(quick.getInventory());
        when(right.getView()).thenReturn(view);
        when(right.getWhoClicked()).thenReturn(viewer);
        when(right.getClickedInventory()).thenReturn(quick.getInventory());
        when(right.getSlot()).thenReturn(9);
        when(right.getClick()).thenReturn(ClickType.RIGHT);
        when(right.isRightClick()).thenReturn(true);
        clearInvocations(gui);
        quick.click(right);

        ArgumentCaptor<AeroMenu> opened = ArgumentCaptor.forClass(AeroMenu.class);
        verify(gui).show(opened.capture());
        AeroMenu families = opened.getValue();
        assertInstanceOf(QuickCheatMenu.class, families);
        families.redraw();
        assertFalse(clickable(families).isEmpty(), "the cheat family screen is empty");

        clickAndCapture(families, 9);
        verify(neuralManager).startSession(eq(tracked), eq(DatasetMetadata.Label.CHEAT),
                eq("aim-assist"), eq(""), eq(""), eq(""), eq("UNKNOWN"), eq(""), any());
    }

    /** Shift-click is still the way to the long form, for a session worth describing. */
    @Test void shiftClickOpensTheLongForm() {
        AeroMenu quick = new QuickRecordMenu(gui, viewer);
        quick.redraw();

        InventoryClickEvent shift = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(quick.getInventory());
        when(shift.getView()).thenReturn(view);
        when(shift.getWhoClicked()).thenReturn(viewer);
        when(shift.getClickedInventory()).thenReturn(quick.getInventory());
        when(shift.getSlot()).thenReturn(9);
        when(shift.getClick()).thenReturn(ClickType.SHIFT_LEFT);
        when(shift.isShiftClick()).thenReturn(true);
        clearInvocations(gui);
        quick.click(shift);

        ArgumentCaptor<AeroMenu> opened = ArgumentCaptor.forClass(AeroMenu.class);
        verify(gui).show(opened.capture());
        assertInstanceOf(WizardMenu.class, opened.getValue());
        // Rendering asks the manager for config; what must not happen is a session opening.
        verify(neuralManager, never()).startSession(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }
}
