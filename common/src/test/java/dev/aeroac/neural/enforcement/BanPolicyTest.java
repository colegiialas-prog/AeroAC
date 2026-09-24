package dev.aeroac.neural.enforcement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The animation's settings: defaults that sound right, and config that cannot break the show.
 *
 * <p>A typo in one sound must cost that sound and nothing else. The worst outcome of a bad config
 * line here is a quieter send-off, never an exception on the path that runs a ban.
 */
class BanPolicyTest {

    @Test void theDefaultShowIsTheFullShow() {
        BanPolicy.Animation animation = BanPolicy.read(BanServiceTest.config(Map.of())).animation();
        assertTrue(animation.enabled());
        assertEquals(5, animation.seconds());
        assertEquals(100, animation.ticks());
        assertTrue(animation.scatterInventory());
        assertTrue(animation.glow());
        assertTrue(animation.lightning());
        assertTrue(animation.title());
        assertTrue(animation.broadcast());
        assertEquals("block.beacon.activate", animation.ascend().sound());
        assertNotNull(animation.charge());
        assertNotNull(animation.scatter());
        assertEquals(3, animation.bang().size(), "the bang is layered by default");
        assertEquals("entity.warden.sonic_boom", animation.bang().get(0).sound());
    }

    @Test void theBangIsNotTheOrdinaryExplosion() {
        BanPolicy.Animation animation = BanPolicy.read(BanServiceTest.config(Map.of())).animation();
        for (BanPolicy.Cue cue : animation.bang()) {
            assertNotEquals("entity.generic.explode", cue.sound(),
                    "the default bang is deliberately not the sound every creeper makes");
        }
    }

    @Test void aCueReadsKeyPitchAndVolume() {
        BanPolicy.Cue cue = BanPolicy.Cue.parse("entity.warden.sonic_boom 0.7 2.0", 1f, 1f);
        assertEquals("entity.warden.sonic_boom", cue.sound());
        assertEquals(0.7f, cue.pitch(), 1e-6);
        assertEquals(2.0f, cue.volume(), 1e-6);
    }

    @Test void aNamespacedKeyIsNotMistakenForAPitch() {
        BanPolicy.Cue cue = BanPolicy.Cue.parse("minecraft:entity.warden.sonic_boom 0.9", 1f, 1f);
        assertEquals("minecraft:entity.warden.sonic_boom", cue.sound());
        assertEquals(0.9f, cue.pitch(), 1e-6);
    }

    @Test void missingPartsTakeTheDefaults() {
        BanPolicy.Cue cue = BanPolicy.Cue.parse("entity.item.break", 1.3f, 0.7f);
        assertEquals(1.3f, cue.pitch(), 1e-6);
        assertEquals(0.7f, cue.volume(), 1e-6);
    }

    @Test void outOfRangeNumbersAreClamped() {
        BanPolicy.Cue loud = BanPolicy.Cue.parse("entity.item.break 9 99", 1f, 1f);
        assertEquals(2.0f, loud.pitch(), 1e-6, "the client clamps pitch to 0.5..2 anyway");
        assertEquals(4.0f, loud.volume(), 1e-6);
    }

    @Test void aKeyIsReadCaseInsensitively() {
        assertEquals("entity.item.break", BanPolicy.Cue.parse("Entity.Item.Break", 1f, 1f).sound());
    }

    /**
     * A line that is not exactly a cue is dropped whole.
     *
     * <p>The case this exists for: a key written with spaces instead of dots. Half-parsing it would
     * play a sound called "entity" and the operator would hear nothing and not know why.
     */
    @Test void anythingThatIsNotExactlyACueIsDropped() {
        assertNull(BanPolicy.Cue.parse(null, 1f, 1f));
        assertNull(BanPolicy.Cue.parse("   ", 1f, 1f));
        assertNull(BanPolicy.Cue.parse("entity warden sonic boom", 1f, 1f), "spaces instead of dots");
        assertNull(BanPolicy.Cue.parse("entity.item.break loud quiet", 1f, 1f), "pitch that is not a number");
        assertNull(BanPolicy.Cue.parse("Not A Key!", 1f, 1f));
        assertNull(BanPolicy.Cue.parse("entity.item.break 1 1 1", 1f, 1f), "too many parts");
        assertNull(BanPolicy.Cue.parse("entity/../item!", 1f, 1f), "not a resource path");
    }

    @Test void aBadLineInTheBangListCostsOnlyThatLine() {
        BanPolicy.Animation animation = BanPolicy.read(BanServiceTest.config(Map.of(
                "neural.enforcement.animation.ban-sounds",
                List.of("entity.warden.sonic_boom 0.7", "!!!", "entity.lightning_bolt.impact")))).animation();
        assertEquals(2, animation.bang().size());
        assertEquals("entity.lightning_bolt.impact", animation.bang().get(1).sound());
    }

    @Test void theBangListIsBounded() {
        List<String> many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "entity.item.break").toList();
        BanPolicy.Animation animation = BanPolicy.read(BanServiceTest.config(Map.of(
                "neural.enforcement.animation.ban-sounds", many))).animation();
        assertEquals(8, animation.bang().size(), "fifty simultaneous sounds is a config mistake, not a bang");
    }
}
