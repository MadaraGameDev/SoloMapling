package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot on a rope or ladder reports the rope in its move packets, as a client does: minus the rope's
 * number in the map's ladderRope list where a foothold id goes otherwise. Requires the repo
 * {@code wz/} directory for the map checks.
 */
class BotClimbFootholdTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void ropeNumberComesFromItsWzName() {
        assertEquals(7, Rope.idOf("7"));
        assertEquals(0, Rope.idOf("rope"));
        assertEquals(0, Rope.idOf("-2"));
        assertEquals(0, Rope.idOf(null));
    }

    @Test
    void climbingBotReportsMinusItsRopeNumber() {
        Rope ladder = new Rope(3, 933, -264, 2, true);
        assertEquals(-3, BotMovementManager.climbingFhId(true, ladder));
    }

    @Test
    void groundIsReportedWhenNotClimbingOrTheRopeIsUnknown() {
        Rope ladder = new Rope(3, 933, -264, 2, true);
        assertEquals(0, BotMovementManager.climbingFhId(false, ladder));
        assertEquals(0, BotMovementManager.climbingFhId(true, null));
        assertEquals(0, BotMovementManager.climbingFhId(true, new Rope(0, 933, -264, 2, true)));
    }

    @Test
    void everyRopeOfAMapKeepsItsOwnNumber() {
        // Ellinia (101000000) is rope/ladder-heavy.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(101000000);
        assertNotNull(map, "map geometry loaded");
        assertFalse(map.getRopes().isEmpty(), "expected ropes");

        Set<Integer> numbers = new HashSet<>();
        for (Rope rope : map.getRopes()) {
            assertTrue(rope.id() > 0, "rope has a number");
            assertTrue(numbers.add(rope.id()), "rope numbers are distinct");
        }
    }

    @Test
    void freeMarketEntranceLaddersHaveTheirWzNumbers() {
        // ladderRope 1 is the ladder at x=1033, 2 the one at x=933.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(910000000);
        assertNotNull(map, "map geometry loaded");
        assertEquals(2, map.getRopes().size());
        for (Rope rope : map.getRopes()) {
            assertEquals(rope.x() == 1033 ? 1 : 2, rope.id());
        }
    }
}
