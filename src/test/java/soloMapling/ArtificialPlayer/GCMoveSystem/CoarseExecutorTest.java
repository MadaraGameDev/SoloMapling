package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 gate (offline): the analytic {@link CoarseExecutor} / {@link MovementPlan} are pure functions of
 * elapsed time, so they are verified deterministically with a fake clock — no live client, no crowd.
 * Test 1 pins the interpolation/completion math on a synthetic plan; tests 2–3 prove the same math
 * flows through real baked edge costs/geometry and the live A* factory. Same package so it can reach
 * the package-private plan/executor/graph. WZ tests require the repo {@code wz/} directory.
 */
class CoarseExecutorTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private static BotNavigationGraph.Edge walk(int x1, int y1, int x2, int y2, int cost) {
        return new BotNavigationGraph.Edge(0, 1, BotNavigationGraph.EdgeType.WALK,
                new Point(x1, y1), new Point(x2, y2), 0, -1, 0, 0, 0, cost);
    }

    // ── Test 1: pure interpolation math, no WZ ───────────────────────────────
    @Test
    void interpolatesAndCompletesDeterministically() {
        // 3 edges, total 2500ms: (0,0)->(100,0) 1000, ->(100,50) 500, ->(200,50) 1000.
        MovementPlan plan = MovementPlan.inMap(100000000, List.of(
                walk(0, 0, 100, 0, 1000),
                walk(100, 0, 100, 50, 500),
                walk(100, 50, 200, 50, 1000)));
        assertNotNull(plan);
        assertEquals(2500L, plan.totalTimeMs);

        assertEquals(new Point(0, 0), plan.positionAt(0));
        assertEquals(new Point(50, 0), plan.positionAt(500));    // mid edge 0
        assertEquals(new Point(100, 0), plan.positionAt(1000));  // boundary 0/1
        assertEquals(new Point(100, 25), plan.positionAt(1250)); // mid edge 1
        assertEquals(new Point(100, 50), plan.positionAt(1500)); // boundary 1/2
        assertEquals(new Point(150, 50), plan.positionAt(2000)); // mid edge 2
        assertEquals(new Point(200, 50), plan.positionAt(2500)); // end
        assertEquals(new Point(200, 50), plan.positionAt(9999)); // clamps past end

        assertFalse(plan.isComplete(2499));
        assertTrue(plan.isComplete(2500));

        // CoarseExecutor.advance is a pure fn of (plan, start, now): same answers off a fake clock.
        long start = 1_000_000L;
        CoarseExecutor.Step mid = CoarseExecutor.advance(plan, start, start + 1250);
        assertEquals(new Point(100, 25), mid.position());
        assertFalse(mid.complete());
        CoarseExecutor.Step done = CoarseExecutor.advance(plan, start, start + 2500);
        assertEquals(new Point(200, 50), done.position());
        assertTrue(done.complete());
    }

    // ── Test 2: a real baked Henesys edge flows through unchanged ─────────────
    @Test
    void interpolatesARealBakedEdge() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(g);

        BotNavigationGraph.Edge edge = firstEdgeWithCost(g);
        assertNotNull(edge, "expected at least one baked edge with cost > 0");

        MovementPlan plan = MovementPlan.inMap(map.getId(), List.of(edge));
        assertNotNull(plan);
        assertEquals(edge.cost, plan.totalTimeMs);
        assertEquals(new Point(edge.startPoint), plan.positionAt(0));
        assertEquals(new Point(edge.endPoint), plan.positionAt(edge.cost));
        assertTrue(CoarseExecutor.advance(plan, 0L, edge.cost).complete());
        assertFalse(CoarseExecutor.advance(plan, 0L, edge.cost - 1).complete());
    }

    // ── Test 3: the live A* factory builds a plan with a sane total time ──────
    @Test
    void factoryPlansARealRoute() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());

        // Use a real edge's endpoints as the route's start/target, lifted a few px above the foothold
        // so findGroundFoothold (which scans for ground BELOW the point — engine convention, cf.
        // GCMovement.enable's y-1) resolves them. A path is guaranteed between their distinct regions.
        MovementPlan plan = null;
        for (List<BotNavigationGraph.Edge> outgoing : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : outgoing) {
                if (e.cost <= 0) {
                    continue;
                }
                Point startP = new Point(e.startPoint.x, e.startPoint.y - 4);
                Point targetP = new Point(e.endPoint.x, e.endPoint.y - 4);
                if (g.findRegionId(map, startP) < 0 || g.findRegionId(map, targetP) < 0) {
                    continue;
                }
                plan = MovementPlan.inMap(g, map, startP, targetP);
                if (plan != null) {
                    break;
                }
            }
            if (plan != null) {
                break;
            }
        }
        assertNotNull(plan, "expected the A* factory to plan at least one walkable route");
        assertTrue(plan.totalTimeMs > 0, "planned route should take > 0 ms");
        // Endpoints of the plan are the first edge's start and the last edge's end.
        assertEquals(plan.positionAt(0), plan.positionAt(-5)); // clamps below 0
        assertTrue(plan.isComplete(plan.totalTimeMs));
    }

    private static BotNavigationGraph.Edge firstEdgeWithCost(BotNavigationGraph g) {
        for (List<BotNavigationGraph.Edge> outgoing : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : outgoing) {
                if (e.cost > 0) {
                    return e;
                }
            }
        }
        return null;
    }
}
