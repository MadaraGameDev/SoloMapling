package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-7 gate (offline): prove the portals-only world graph scans Map.wz and routes between
 * walkably-connected maps. Same-package so it can reach the package-private graph. Requires the
 * repo {@code wz/} directory.
 */
class GCWorldGraphTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void scansWorldAndRoutesAdjacentMaps() {
        Map<Integer, int[]> g = GCWorldGraph.get();
        assertNotNull(g);
        System.out.printf("[GCTravel] indexed %d maps%n", g.size());
        assertTrue(g.size() > 1000, "expected a large world scan (>1000 maps)");

        // Self-validating: pick any map with an outgoing portal edge and route to that neighbour —
        // must be a 1-hop route ending at the neighbour. No hardcoded map ids.
        int from = -1;
        int neighbour = -1;
        for (Map.Entry<Integer, int[]> e : g.entrySet()) {
            if (e.getValue().length > 0) {
                from = e.getKey();
                neighbour = e.getValue()[0];
                break;
            }
        }
        assertTrue(from >= 0 && neighbour >= 0, "expected at least one portal edge in the world");

        List<Integer> route = GCWorldGraph.route(from, neighbour, 12);
        System.out.printf("[GCTravel] route %d -> %d = %s%n", from, neighbour, route);
        assertNotNull(route, "direct neighbour must be routable");
        assertEquals(List.of(neighbour), route, "neighbour should be a single hop");

        assertEquals(List.of(), GCWorldGraph.route(from, from, 12), "same-map route is empty");
    }

    @Test
    void routesTownToTownViaTaxi() {
        // Henesys -> Kerning has no walkable portal path; the Victoria cab network makes it a 1-hop
        // route. (Pure connectivity — execution walks to the cab NPC then rides.)
        List<Integer> route = GCWorldGraph.route(100000000, 103000000, 12);
        System.out.printf("[GCTaxi] Henesys->Kerning route = %s%n", route);
        assertNotNull(route, "Henesys->Kerning should be routable via taxi");
        assertEquals(List.of(103000000), route, "should be a single taxi hop");
    }
}
