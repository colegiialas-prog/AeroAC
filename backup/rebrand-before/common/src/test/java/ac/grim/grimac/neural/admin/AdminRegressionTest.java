package ac.grim.grimac.neural.admin;

import ac.grim.grimac.neural.admin.training.RecordingDraft;
import ac.grim.grimac.neural.dataset.DatasetMetadata;
import ac.grim.grimac.neural.inference.PredictionResult;
import ac.grim.grimac.neural.inference.ModelKind;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminRegressionTest {
    @Test void lateSnapshotCannotResurrectAfterQuitReconnectOrReload() {
        SnapshotMailbox<String> box = new SnapshotMailbox<>();
        UUID id = UUID.randomUUID();
        var old = box.begin(id, new Object());
        assertNull(box.begin(id, old.connection()), "one pending refresh per connection");
        box.forget(id);
        var next = box.begin(id, new Object());
        assertFalse(box.complete(old, "old connection", 1));
        assertTrue(box.complete(next, "new connection", 2));
        assertEquals("new connection", box.get(id, 3, 5));
        assertNull(box.get(id, 8, 5), "lagged values expire instead of looking current");
        var pending = box.begin(id, next.connection());
        box.reset();
        assertFalse(box.complete(pending, "before reload", 9));
        assertNull(box.get(id, 9, 5));
    }

    @Test void confirmedDraftCannotChangeWithLaterTypingAndUnlabelledCannotInheritCheatMetadata() {
        RecordingDraft draft = new RecordingDraft().target(UUID.randomUUID(), "P")
                .label(DatasetMetadata.Label.CHEAT).cheatFamily("aim-assist")
                .assistStrength(DatasetMetadata.AssistStrength.VERY_LOW).notes("independent observation");
        RecordingDraft confirmed = draft.copy();
        draft.notes("changed").label(DatasetMetadata.Label.UNLABELED);
        assertEquals("independent observation", confirmed.notes());
        assertEquals(DatasetMetadata.AssistStrength.VERY_LOW, confirmed.assistStrength());
        assertEquals("", draft.cheatFamily());
        assertEquals("UNKNOWN", draft.assistArgument());
    }

    @Test void predictionAndPublishedArraysAreDefensiveCopies() {
        String[] heads = {"overall"}; double[] values = {0.5};
        var prediction = new PredictionResult(1, 1, ModelKind.FLASH, "test", false, heads, values, 1);
        heads[0] = "changed"; values[0] = 1;
        prediction.headValues()[0] = 0;
        assertEquals(0.5, prediction.overall());
        var view = AdminPlayerView.empty(UUID.randomUUID(), "P", -1);
        long[] counts = view.evidenceByType(); counts[0] = 77;
        assertEquals(0, view.evidenceByType()[0]);
        assertEquals(AdminStyle.NO_DATA, AdminStyle.percent(Double.POSITIVE_INFINITY));
    }

    static class Packets implements NameplateOverlay.Sink {
        final Set<Integer> alive = new HashSet<>();
        int spawned, destroyed, moves, textUpdates;
        public void spawn(int id, Vector3d position, Component text) { assertTrue(alive.add(id)); spawned++; }
        public void move(int id, Vector3d position) { assertTrue(alive.contains(id)); moves++; }
        public void text(int id, Component text) { assertTrue(alive.contains(id)); textUpdates++; }
        public void destroy(int id) { assertTrue(alive.remove(id)); destroyed++; }
    }

    @Test void overlayIsPrivateBoundedCoalescedAndRemovesRealClientState() {
        NameplateOverlay overlay = new NameplateOverlay();
        UUID admin = UUID.randomUUID(), other = UUID.randomUUID(), target = UUID.randomUUID();
        var view = AdminPlayerView.empty(target, "Target", 30);
        var visible = List.of(new NameplateOverlay.Visible(view, new Vector3d(1, 2, 3)));
        Packets first = new Packets(), second = new Packets();
        overlay.reconcile(admin, "world", visible, false, first);
        overlay.reconcile(admin, "world", visible, false, first);
        assertEquals(1, first.spawned);
        assertEquals(0, first.moves + first.textUpdates);
        assertEquals(0, second.spawned, "another viewer receives no packets");
        overlay.reconcile(other, "world", visible, false, second);
        overlay.removeTarget(null, admin, target);
        assertEquals(1, first.destroyed, "quit sends destroy rather than forgetting bookkeeping");
        assertEquals(1, second.alive.size());
        overlay.reconcile(other, "nether", visible, false, second);
        assertEquals(1, second.destroyed);
        assertEquals(2, second.spawned);
        overlay.clearViewer(null, other);
        assertTrue(second.alive.isEmpty());
        assertEquals(0, overlay.viewers());
        List<NameplateOverlay.Visible> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(new NameplateOverlay.Visible(
                AdminPlayerView.empty(UUID.randomUUID(), "P" + i, -1), new Vector3d()));
        overlay.reconcile(admin, "world", many, false, first);
        assertEquals(64, overlay.tagsFor(admin));
        overlay.reconcile(admin, "world", List.of(), false, first);
        assertTrue(first.alive.isEmpty(), "AUTO returning to CLEAN removes markers");
    }

    @Test void markerMetadataTracksProtocolChanges() {
        assertEquals(10, NameplateOverlay.markerIndex(ClientVersion.V_1_8));
        assertEquals(11, NameplateOverlay.markerIndex(ClientVersion.V_1_13));
        assertEquals(13, NameplateOverlay.markerIndex(ClientVersion.V_1_14));
        assertEquals(14, NameplateOverlay.markerIndex(ClientVersion.V_1_16));
        assertEquals(15, NameplateOverlay.markerIndex(ClientVersion.V_1_21));
    }
}
