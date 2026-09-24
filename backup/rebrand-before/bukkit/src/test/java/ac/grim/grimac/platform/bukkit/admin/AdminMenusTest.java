package ac.grim.grimac.platform.bukkit.admin;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.neural.*;
import ac.grim.grimac.neural.admin.*;
import ac.grim.grimac.neural.admin.training.*;
import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.neural.risk.RiskState;
import ac.grim.grimac.neural.risk.EvidenceType;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.bukkit.admin.training.*;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual menu renderers and click routing, with only the Bukkit transport replaced. No live server is claimed. */
class AdminMenusTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<MenuItems> items;
    private BukkitAdminGui gui;
    private AdminService service;
    private Player viewer;
    private final UUID viewerId = UUID.nameUUIDFromBytes("admin-preview".getBytes());
    private final UUID targetId = UUID.nameUUIDFromBytes("target-preview".getBytes());
    private final Map<UUID, RecordingDraft> drafts = new HashMap<>();
    private record Tile(String material, String name, List<String> lore) { }
    private final Map<ItemStack, Tile> tiles = new IdentityHashMap<>();
    private final Map<Inventory, ItemStack[]> inventories = new IdentityHashMap<>();
    private final Map<Inventory, String> titles = new IdentityHashMap<>();

    @BeforeEach void setup() {
        gui = mock(BukkitAdminGui.class); service = mock(AdminService.class); viewer = mock(Player.class);
        when(gui.service()).thenReturn(service);
        when(viewer.getUniqueId()).thenReturn(viewerId); when(viewer.getName()).thenReturn("PreviewAdmin");
        when(viewer.hasPermission(anyString())).thenReturn(true);
        ConfigManager settings = (ConfigManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConfigManager.class}, (proxy, method, args) -> args != null && args.length == 2 ? args[1] : null);
        when(service.config()).thenReturn(AdminConfig.read(settings));
        when(service.alerts()).thenReturn(new AdminAlerts());
        when(service.viewMode(any())).thenReturn(AdminViewMode.SUSPICIOUS);
        when(service.draft(any())).thenAnswer(call -> drafts.computeIfAbsent(call.getArgument(0), id -> new RecordingDraft()));
        when(service.peekDraft(any())).thenAnswer(call -> drafts.get(call.getArgument(0)));
        when(service.training()).thenReturn(new TrainingServiceClient.Offline());
        when(service.datasetSummary()).thenReturn(new DatasetSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), false, 1, null));
        NeuralManager neural = mock(NeuralManager.class);
        when(gui.neural()).thenReturn(neural);
        when(neural.runtime()).thenReturn(new NeuralRuntime(1, NeuralConfig.read(settings), null, () -> null));
        List<AdminPlayerView> rows = new ArrayList<>();
        rows.add(recordingPlayer());
        for (int i = 0; i < 50; i++) rows.add(AdminPlayerView.empty(UUID.nameUUIDFromBytes(("p" + i).getBytes()), "Player" + i, 30 + i));
        when(service.snapshot()).thenReturn(AdminSnapshot.of(rows, 1));
        GrimPlayer tracked = mock(GrimPlayer.class);
        when(tracked.getNeuralState()).thenReturn(new NeuralPlayerState());
        when(gui.tracked(targetId)).thenReturn(tracked);
        doAnswer(call -> {
            java.util.function.Consumer<AdminDetailView> callback = call.getArgument(1);
            callback.accept(new AdminDetailView(recordingPlayer(),
                    List.of(new AdminDetailView.Prediction("flash", "demo-v2", false, "attack", .84,
                            new String[]{"overall", "aimAssist"}, new double[]{.84, .81}, 12, 200)),
                    List.of(new AdminDetailView.Evidence(EvidenceType.AI_AIM, .4, "flash/demo", "fixture", 200),
                            new AdminDetailView.Evidence(EvidenceType.GRIM_REACH, .2, "Reach", "fixture", 800)),
                    List.of(new AdminDetailView.Mitigation("fixture-rule", 8, RiskState.MITIGATED,
                            "Renderer example only", 500, 0, 2000)), new long[EvidenceType.values().length],
                    new double[]{1, 2, 2.5, 4, 3, 5, 7, 8, 7.8}, 15, 1));
            return null;
        }).when(service).detail(eq(tracked), any());
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
            Inventory inventory = mock(Inventory.class);
            int size = call.getArgument(1);
            ItemStack[] contents = new ItemStack[size];
            inventories.put(inventory, contents); titles.put(inventory, call.getArgument(2));
            when(inventory.getSize()).thenReturn(size); when(inventory.getHolder()).thenReturn(call.getArgument(0));
            when(inventory.getItem(anyInt())).thenAnswer(get -> contents[(int) get.getArgument(0)]);
            doAnswer(set -> { contents[(int) set.getArgument(0)] = set.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            doAnswer(clear -> { Arrays.fill(contents, null); return null; }).when(inventory).clear();
            return inventory;
        });
        items = mockStatic(MenuItems.class, call -> {
            String method = call.getMethod().getName();
            if (method.equals("item")) {
                Object[] args = call.getRawArguments();
                Object lore = args[2];
                return tile((Material) args[0], (String) args[1], lore instanceof List<?> list ? (List<String>) list : Arrays.asList((String[]) lore));
            }
            if (method.equals("head")) return tile(Material.PLAYER_HEAD, call.getArgument(2), call.getArgument(3));
            return call.callRealMethod();
        });
    }

    private ItemStack tile(Material material, String name, List<String> lore) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getType()).thenReturn(material); when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getDisplayName()).thenReturn(name); when(meta.getLore()).thenReturn(lore);
        tiles.put(stack, new Tile(material.name(), name, List.copyOf(lore)));
        return stack;
    }

    @AfterEach void teardown() { if (items != null) items.close(); if (bukkit != null) bukkit.close(); }

    private AdminPlayerView recordingPlayer() {
        RecordingView recording = new RecordingView(UUID.nameUUIDFromBytes("session-preview".getBytes()), "DemoTarget", targetId,
                RecordingView.State.RECORDING, DatasetMetadata.Label.CHEAT, "aim-assist", "demo-client", "subtle",
                DatasetMetadata.AssistStrength.VERY_LOW, "box-pvp", 222, 4440, 4700, 132, 151, 126, 0, 0,
                null, null, 0.95, 0.92, 0.92);
        return new AdminPlayerView(targetId, "DemoTarget", RiskState.SUSPICIOUS, 7.8, 8.1, 2, 15, true,
                0.84, 0.81, Double.NaN, Double.NaN, new DominantSignal("AIM", 0.81), "flash", "demo-v2", false,
                200, 12, 20, 45, 160, 3, true, 42, 2.8, 1.4, 0.8, null, 0, 0, 0, true, recording, new long[10]);
    }

    private InventoryClickEvent event(AeroMenu menu, int slot, ClickType click) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(menu.getInventory()); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(viewer); when(event.getClickedInventory()).thenReturn(menu.getInventory());
        when(event.getSlot()).thenReturn(slot); when(event.getClick()).thenReturn(click);
        when(event.isRightClick()).thenReturn(click == ClickType.RIGHT);
        when(gui.current(viewerId)).thenReturn(menu);
        return event;
    }

    @Test void allScreensRenderAndExportTheirActualSlots() throws Exception {
        SessionRecord reviewed = new SessionRecord("demo-reviewed", "demo-a", 2, "LEGIT", "LAB_LEGIT",
                null, "vanilla", "default", "duel", "NONE", 765, 360000, 7200, 7600, 0, true,
                "STOPPED", null, "GOOD", "Fixture audit", "Demo reviewer", "2026-09-14T00:00:00Z", "LEGIT");
        SessionRecord attention = new SessionRecord("demo-review", "demo-b", 1, "CHEAT", "LAB_CHEAT",
                "aim-assist", "demo-client", "subtle", "box-pvp", "VERY_LOW", 765, 310000, 6000, 6500, 3, true,
                "STOPPED", null, "REVIEW", "Fixture: inspect dropped samples", null, null, null);
        when(service.datasetSummary()).thenReturn(new DatasetSummary(2, 2, 1, 1, 0, 0, 0, 1, 2, 1,
                670000, 13200, 14100, Map.of("aim-assist", 1), Map.of("demo-client", 1),
                Map.of("demo-client/subtle", 1), Map.of("VERY_LOW", 1), Map.of("box-pvp", 1),
                Map.of("duel", 1), Map.of("765", 1), Map.of("GOOD", 1, "REVIEW", 1),
                List.of(reviewed, attention), List.of(attention), false, 1, null,
                new AuditTotals(312, 580, Map.of("150-249ms", 1), 1)));
        TrainingServiceClient backend = mock(TrainingServiceClient.class);
        when(backend.job()).thenReturn(new TrainingJob(TrainingJob.Status.TRAINING, "DEMO-JOB", "flash",
                "demo-dataset", 2, "attack", "overall, aimAssist", 8, 20, .4, .28, .34, 120, 1,
                "Renderer fixture. No training job is running."));
        when(backend.currentEvaluation()).thenReturn(evaluation("demo-current", .8, .25));
        when(backend.candidateEvaluation()).thenReturn(evaluation("demo-candidate", .82, .3));
        when(service.training()).thenReturn(backend);
        RecordingDraft draft = service.draft(viewerId).target(targetId, "DemoTarget").label(DatasetMetadata.Label.CHEAT)
                .cheatFamily("aim-assist").clientFamily("demo-client").configuration("subtle")
                .assistStrength(DatasetMetadata.AssistStrength.VERY_LOW).scenario("box-pvp");
        List<AeroMenu> screens = new ArrayList<>(List.of(new MainMenu(gui, viewer), new PlayerListMenu(gui, viewer, false, 0),
                new PlayerListMenu(gui, viewer, true, 0), new ProfileMenu(gui, viewer, targetId, false),
                new LiveMonitorMenu(gui, viewer, 0), new StatusMenu(gui, viewer), new ChecksMenu(gui, viewer),
                new MitigationsMenu(gui, viewer, 0), new AlertsMenu(gui, viewer), new StatisticsMenu(gui, viewer),
                new SettingsMenu(gui, viewer), new TrainingMenu(gui, viewer), new RecordingsMenu(gui, viewer),
                new RecordingDetailMenu(gui, viewer, targetId), new ModelMenu(gui, viewer, false), new ModelMenu(gui, viewer, true)));
        for (var kind : HistoryMenu.Kind.values()) screens.add(new HistoryMenu(gui, viewer, targetId, kind, false));
        for (var kind : DatasetMenu.Kind.values()) screens.add(new DatasetMenu(gui, viewer, kind, 0));
        for (var step : WizardMenu.Step.values()) screens.add(new WizardMenu(gui, viewer, step));
        List<Map<String, Object>> exported = new ArrayList<>();
        for (AeroMenu menu : screens) {
            menu.redraw(); Inventory inventory = menu.getInventory();
            assertTrue(inventory.getSize() >= 27 && inventory.getSize() <= 54);
            List<Tile> content = Arrays.stream(inventories.get(inventory)).map(tiles::get).toList();
            assertTrue(content.stream().noneMatch(Objects::isNull), menu.getClass().getSimpleName());
            assertTrue(titles.get(inventory).length() <= 32);
            exported.add(Map.of("title", menu.title(), "permission", menu.permission(), "items", content));
            when(viewer.hasPermission(anyString())).thenReturn(false);
            assertFalse(menu.allowed(), menu.getClass().getSimpleName());
            when(viewer.hasPermission(menu.permission())).thenReturn(true);
            assertTrue(menu.allowed());
            when(viewer.hasPermission(anyString())).thenReturn(true);
        }
        draft.label(DatasetMetadata.Label.LEGIT).clientFamily("vanilla").configuration("default").scenario("duel");
        AeroMenu legit = new WizardMenu(gui, viewer, WizardMenu.Step.CONFIRM);
        legit.redraw();
        exported.add(Map.of("title", legit.title() + " · LEGIT", "permission", legit.permission(),
                "items", Arrays.stream(inventories.get(legit.getInventory())).map(tiles::get).toList()));
        when(service.training()).thenReturn(new TrainingServiceClient.Offline());
        AeroMenu offline = new ModelMenu(gui, viewer, false); offline.redraw();
        exported.add(Map.of("title", offline.title() + " · OFFLINE", "permission", offline.permission(),
                "items", Arrays.stream(inventories.get(offline.getInventory())).map(tiles::get).toList()));
        Path output = Path.of("build/reports/admin-menus.json"); Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "notice", "Renderer fixtures only; these are not Minecraft screenshots or measured model quality.", "screens", exported)));
        assertEquals(DatasetMetadata.AssistStrength.NONE, draft.assistStrength());
    }

    private EvaluationSummary evaluation(String name, double auc, double fp) {
        return new EvaluationSummary(name, "demo-dataset", 2, auc, .75, .001, Double.NaN, fp, 2.4,
                EvaluationSummary.Calibration.UNCALIBRATED, EvaluationSummary.Population.MIXED,
                Map.of("VERY_LOW", .3, "HIGH", .8), Map.of("demo-client", .6), Map.of("box-pvp", .6),
                List.of("DEMONSTRATION VALUES ONLY", "Insufficient legit windows for the requested FPR"),
                1, "a".repeat(64), Map.of("KNOWN_CLIENT", .6, "UNKNOWN_CLIENT", Double.NaN));
    }

    @Test void confirmationFreezesMetadataAndCannotStartTwice() {
        RecordingDraft draft = service.draft(viewerId).target(targetId, "DemoTarget").label(DatasetMetadata.Label.CHEAT)
                .cheatFamily("aim-assist").clientFamily("demo-client").configuration("subtle")
                .assistStrength(DatasetMetadata.AssistStrength.VERY_LOW).scenario("box-pvp").notes("original notes");
        List<Runnable> queued = new ArrayList<>();
        GrimPlayer target = gui.tracked(targetId);
        doAnswer(call -> { queued.add(call.getArgument(0)); return null; }).when(target).runSafely(any(Runnable.class));
        WizardMenu menu = new WizardMenu(gui, viewer, WizardMenu.Step.CONFIRM); menu.redraw();
        menu.click(event(menu, 49, ClickType.LEFT));
        menu.click(event(menu, 49, ClickType.LEFT));
        draft.configuration("changed").notes("changed");
        assertEquals(1, queued.size()); queued.get(0).run();
        verify(gui.neural()).startSession(eq(target), eq(DatasetMetadata.Label.CHEAT), eq("aim-assist"),
                eq("demo-client"), eq("subtle"), eq("box-pvp"), eq("VERY_LOW"), eq("original notes"), any());
    }

    @Test void hubDoesNotExposeRestrictedDatasetOrModelCards() {
        when(viewer.hasPermission(anyString())).thenReturn(false);
        when(viewer.hasPermission(AdminPermissions.GUI)).thenReturn(true);
        MainMenu menu = new MainMenu(gui, viewer); menu.redraw();
        assertEquals(Material.BARRIER, menu.getInventory().getItem(23).getType());
        when(viewer.hasPermission(AdminPermissions.TRAINING)).thenReturn(true);
        TrainingMenu training = new TrainingMenu(gui, viewer); training.redraw();
        assertEquals(Material.BARRIER, training.getInventory().getItem(13).getType());
        assertEquals(Material.BARRIER, training.getInventory().getItem(22).getType());
    }

    @Test void wizardPaginatesAndFreeTextResumesTheCorrectStep() {
        WizardMenu menu = new WizardMenu(gui, viewer, WizardMenu.Step.PLAYER); menu.redraw();
        menu.click(event(menu, 53, ClickType.LEFT));
        assertNotNull(menu.getInventory().getItem(9));
        var draft = service.draft(viewerId).target(targetId, "T").label(DatasetMetadata.Label.CHEAT);
        assertEquals(WizardMenu.Step.CONFIGURATION, WizardMenu.resumeStep("client", draft));
        assertEquals(WizardMenu.Step.ASSIST, WizardMenu.resumeStep("config", draft));
        draft.label(DatasetMetadata.Label.LEGIT);
        assertEquals(WizardMenu.Step.SCENARIO, WizardMenu.resumeStep("config", draft));
        assertEquals(WizardMenu.Step.CONFIRM, WizardMenu.resumeStep("notes", draft));
    }

    @Test void outsideClicksShiftClicksAndRevokedPermissionCannotTriggerActions() {
        MainMenu menu = new MainMenu(gui, viewer); menu.redraw(); MenuListener listener = new MenuListener(gui);
        var outside = event(menu, -999, ClickType.LEFT); when(outside.getClickedInventory()).thenReturn(null);
        assertDoesNotThrow(() -> listener.onClick(outside)); verify(outside).setCancelled(true);
        var shift = event(menu, 11, ClickType.SHIFT_LEFT); listener.onClick(shift); verify(gui, never()).show(any());
        when(viewer.hasPermission(anyString())).thenReturn(false);
        listener.onClick(event(menu, 11, ClickType.LEFT)); verify(gui, never()).show(any());
        when(viewer.hasPermission(AdminPermissions.GUI)).thenReturn(true);
        when(viewer.hasPermission(AdminPermissions.PLAYERS)).thenReturn(true);
        listener.onClick(event(menu, 11, ClickType.LEFT)); verify(gui).show(isA(PlayerListMenu.class));
    }
}
