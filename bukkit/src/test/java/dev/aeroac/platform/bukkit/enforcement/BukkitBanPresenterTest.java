package dev.aeroac.platform.bukkit.enforcement;

import dev.aeroac.neural.enforcement.BanDecision;
import dev.aeroac.neural.enforcement.BanPolicy;
import dev.aeroac.neural.risk.RiskState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The send-off, driven one frame at a time with the server replaced by a hand-cranked stage.
 *
 * <p>What matters about this class is not how it looks but what it guarantees: the ban callback
 * runs exactly once whatever happens, nobody is left frozen, a preview never costs anybody an
 * item, and a real ban takes the inventory in the order it says it does.
 */
class BukkitBanPresenterTest {
    private final UUID id = UUID.nameUUIDFromBytes("sent-off".getBytes());
    private World world;
    private Player player;
    private ItemStack[] slots;
    private ManualStage stage;
    private BukkitBanPresenter presenter;
    private final List<ItemStack> thrown = new ArrayList<>();
    private final List<Item> thrownEntities = new ArrayList<>();

    /** The server, cranked by hand. */
    private static final class ManualStage implements BukkitBanPresenter.Stage {
        final Map<UUID, Player> players = new HashMap<>();
        final List<String> broadcasts = new ArrayList<>();
        Runnable frame;
        boolean stopped;

        @Override public Player player(UUID id) { return players.get(id); }

        @Override public BukkitBanPresenter.Stop repeat(Player player, long period, Runnable frame, Runnable retired) {
            this.frame = frame;
            this.stopped = false;
            return () -> stopped = true;
        }

        @Override public void soon(Player player, Runnable task, Runnable retired) { task.run(); }

        @Override public void broadcast(String message) { broadcasts.add(message); }

        /** Runs up to {@code frames} frames, stopping early once the timer was stopped. */
        void advance(int frames) {
            for (int i = 0; i < frames && frame != null && !stopped; i++) frame.run();
        }
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        ItemStack copy = mock(ItemStack.class);
        when(copy.getType()).thenReturn(material);
        when(copy.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(copy);
        return stack;
    }

    @BeforeEach void setup() {
        world = mock(World.class);
        player = mock(Player.class);
        slots = new ItemStack[41];
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getSize()).thenReturn(41);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
        doAnswer(call -> { slots[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), nullable(ItemStack.class));

        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Suspect");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0.5, 64, 0.5));
        when(player.getWalkSpeed()).thenReturn(0.2f);
        when(player.getFlySpeed()).thenReturn(0.1f);
        when(player.isCollidable()).thenReturn(true);

        when(world.dropItem(any(Location.class), any(ItemStack.class))).thenAnswer(call -> {
            thrown.add(call.getArgument(1));
            Item entity = mock(Item.class);
            thrownEntities.add(entity);
            return entity;
        });

        stage = new ManualStage();
        stage.players.put(id, player);
        presenter = new BukkitBanPresenter(() -> stage);
    }

    /** The shipped defaults: a config that answers every key with the caller's own fallback. */
    private static BanPolicy.Animation animation() {
        var defaults = (ac.grim.grimac.api.config.ConfigManager) java.lang.reflect.Proxy.newProxyInstance(
                BukkitBanPresenterTest.class.getClassLoader(),
                new Class<?>[]{ac.grim.grimac.api.config.ConfigManager.class},
                (proxy, method, args) -> args != null && args.length == 2 ? args[1] : null);
        return BanPolicy.read(defaults).animation();
    }

    private BanDecision decision() {
        return new BanDecision("abc123", id, "Suspect", RiskState.CONFIRMED, 14, 0.96, "AIM", 40, 40, 1);
    }

    private int framesInFlight() {
        return (int) (animation().ticks() / BukkitBanPresenter.FRAME_TICKS);
    }

    @Test void theFlightFreezesThePlayerAndGivesEverythingBackAtTheEnd() {
        int[] callbacks = {0};
        presenter.present(decision(), animation(), false, () -> callbacks[0]++);

        assertTrue(presenter.frozen(id));
        verify(player).setWalkSpeed(0f);
        verify(player).setFlySpeed(0f);
        verify(player).setInvulnerable(true);
        verify(player).setCollidable(false);
        verify(player).closeInventory();
        assertEquals(0, callbacks[0], "the ban waits for the show");

        stage.advance(framesInFlight() + 5);

        assertFalse(presenter.frozen(id));
        assertTrue(stage.stopped, "the timer is stopped, not left running");
        verify(player).setWalkSpeed(0.2f);
        verify(player).setFlySpeed(0.1f);
        verify(player).setInvulnerable(false);
        verify(player).setCollidable(true);
        verify(player).setFallDistance(0f);
        assertEquals(1, callbacks[0], "the callback runs exactly once");
    }

    @Test void theBangHasItsFlashItsSoundsAndItsAnnouncement() {
        presenter.present(decision(), animation(), false, () -> { });
        stage.advance(framesInFlight() + 1);

        verify(world).strikeLightningEffect(any(Location.class));
        verify(world, atLeastOnce()).playSound(any(Location.class), eq("entity.warden.sonic_boom"), anyFloat(), anyFloat());
        verify(world, atLeastOnce()).playSound(any(Location.class), eq("entity.lightning_bolt.impact"), anyFloat(), anyFloat());
        verify(world, atLeastOnce()).playSound(any(Location.class), eq("block.beacon.activate"), anyFloat(), anyFloat());
        assertEquals(1, stage.broadcasts.size());
        String announced = stage.broadcasts.get(0);
        assertTrue(announced.contains("Suspect"), "the name reaches the server: " + announced);
        assertFalse(announced.contains("%s") || announced.contains("{0}"),
                "no placeholder survives into the message: " + announced);
    }

    @Test void theChargeClimbsInPitchOnTheWayUp() {
        presenter.present(decision(), animation(), false, () -> { });
        stage.advance(framesInFlight() + 1);

        var pitch = org.mockito.ArgumentCaptor.forClass(Float.class);
        verify(world, atLeast(2)).playSound(any(Location.class), eq("block.note_block.bass"), anyFloat(), pitch.capture());
        List<Float> pitches = pitch.getAllValues();
        for (int i = 1; i < pitches.size(); i++) {
            assertTrue(pitches.get(i) > pitches.get(i - 1), "each note is higher than the last: " + pitches);
        }
    }

    @Test void aRealBanTakesTheInventoryBackpackFirstAndArmourLast() {
        slots[20] = stack(Material.COBBLESTONE);       // backpack
        slots[0] = stack(Material.DIAMOND_SWORD);       // hotbar
        slots[40] = stack(Material.SHIELD);             // off-hand
        slots[36] = stack(Material.DIAMOND_BOOTS);      // armour
        slots[39] = stack(Material.DIAMOND_HELMET);     // armour
        ItemStack sword = slots[0];
        ItemStack boots = slots[36];

        presenter.present(decision(), animation(), false, () -> { });
        stage.advance(framesInFlight() + 1);

        for (ItemStack slot : slots) assertNull(slot, "a real ban leaves the inventory empty");
        List<Material> order = thrown.stream().map(ItemStack::getType).toList();
        assertEquals(List.of(Material.COBBLESTONE, Material.DIAMOND_SWORD, Material.SHIELD,
                Material.DIAMOND_HELMET, Material.DIAMOND_BOOTS), order);
        assertSame(sword, thrown.get(1), "the real item is thrown, not a copy");
        assertSame(boots, thrown.get(4));
        for (Item entity : thrownEntities) verify(entity, never()).setPickupDelay(anyInt());
    }

    @Test void theItemsLeaveAcrossTheFlightNotAllAtOnce() {
        for (int slot = 9; slot < 13; slot++) slots[slot] = stack(Material.COBBLESTONE);
        presenter.present(decision(), animation(), false, () -> { });

        stage.advance(framesInFlight() / 3);
        int early = thrown.size();
        assertTrue(early >= 1 && early < 4, "some, not all, have left a third of the way up: " + early);

        stage.advance(framesInFlight());
        assertEquals(4, thrown.size());
    }

    /** An operator trying the show on themselves must end it with everything they started with. */
    @Test void aPreviewThrowsCopiesAndKeepsEveryItem() {
        ItemStack sword = stack(Material.DIAMOND_SWORD);
        ItemStack helmet = stack(Material.DIAMOND_HELMET);
        slots[0] = sword;
        slots[39] = helmet;

        int[] callbacks = {0};
        presenter.present(decision(), animation(), true, () -> callbacks[0]++);
        stage.advance(framesInFlight() + 1);

        assertSame(sword, slots[0], "the preview never takes the real item");
        assertSame(helmet, slots[39]);
        assertEquals(2, thrown.size());
        assertNotSame(sword, thrown.get(0), "a copy is thrown");
        for (Item entity : thrownEntities) {
            verify(entity).setPickupDelay(32767);
            verify(entity).setTicksLived(anyInt());
        }
        assertTrue(stage.broadcasts.isEmpty(), "a preview is never announced to the server");
        assertEquals(1, callbacks[0]);
    }

    @Test void leavingMidFlightEndsTheShowAndStillRunsTheBan() {
        int[] callbacks = {0};
        presenter.present(decision(), animation(), false, () -> callbacks[0]++);
        stage.advance(5);
        assertTrue(presenter.frozen(id));

        assertTrue(presenter.release(id), "the quit handler ends the flight");
        assertFalse(presenter.frozen(id));
        assertEquals(1, callbacks[0], "logging out does not escape the ban");

        stage.advance(framesInFlight());
        assertEquals(1, callbacks[0], "and the leftover timer cannot run it a second time");
    }

    @Test void anOfflinePlayerIsBannedWithoutAShow() {
        stage.players.clear();
        int[] callbacks = {0};
        presenter.present(decision(), animation(), false, () -> callbacks[0]++);
        assertEquals(1, callbacks[0]);
        assertFalse(presenter.frozen(id));
    }

    @Test void aSecondShowForThePlayerInTheAirIsRefusedNotStacked() {
        int[] first = {0};
        int[] second = {0};
        presenter.present(decision(), animation(), false, () -> first[0]++);
        presenter.present(decision(), animation(), false, () -> second[0]++);
        assertEquals(1, second[0], "the second request returns at once");
        assertEquals(0, first[0], "and does not end the first");
        stage.advance(framesInFlight() + 1);
        assertEquals(1, first[0]);
    }

    @Test void stoppingThePluginReleasesEveryoneStillInTheAir() {
        int[] callbacks = {0};
        presenter.present(decision(), animation(), false, () -> callbacks[0]++);
        presenter.cancelAll();
        assertFalse(presenter.frozen(id));
        assertEquals(1, callbacks[0]);
        verify(player).setWalkSpeed(0.2f);
    }

    /**
     * No server means no effect registry, which is exactly the situation on a version whose effect
     * names changed. The flight has to carry on without levitation rather than stop.
     */
    @Test void aMissingEffectRegistryCostsTheEffectNotTheFlight() {
        int[] callbacks = {0};
        presenter.present(decision(), animation(), false, () -> callbacks[0]++);
        stage.advance(framesInFlight() + 1);
        assertEquals(1, callbacks[0]);
        verify(world).strikeLightningEffect(any(Location.class));
    }
}
