package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 gate (offline): prove the GreenCat "no hardcoded paths" baker works on the
 * SoloMapling base — load a map's WZ geometry, bake the nav graph by simulating physics,
 * and assert sane region / edge / rope counts. Same-package so it can reach the
 * package-private loader/provider. Requires the repo {@code wz/} directory.
 */
class GCMoveBakeTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void bakesEllinia_ladderHeavy() {
        // Ellinia (101000000) is rope/ladder-heavy — also proves the WZ ladderRope parsing.
        assertBakes(101000000, true);
    }

    @Test
    void bakesHenesys() {
        assertBakes(100000000, false);
    }

    private static void assertBakes(int mapId, boolean expectRopes) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        assertNotNull(map, "map geometry loaded");
        assertNotNull(map.getFootholds(), "footholds present");

        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(g, "graph baked");

        int regions = g.regions.size();
        int edges = g.outgoingByRegionId.values().stream().mapToInt(List::size).sum();
        int ropes = map.getRopes().size();
        System.out.printf("[GCMove bake] map %d: regions=%d ropes=%d edges=%d%n", mapId, regions, ropes, edges);

        assertTrue(regions > 0, "expected >0 regions");
        assertTrue(edges > 0, "expected >0 edges");
        if (expectRopes) {
            assertTrue(ropes > 0, "expected >0 ropes (proves WZ ladderRope parsing)");
        }
    }
}
