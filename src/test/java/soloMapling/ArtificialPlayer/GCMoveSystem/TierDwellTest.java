package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 gate (offline, no WZ): the tier hysteresis is a pure clock-injected state machine, so promotion
 * (immediate), demotion (after the dwell), and the no-flap property for a pacing player are verified
 * deterministically with a fake clock.
 */
class TierDwellTest {

    @Test
    void promotesImmediatelyAndDemotesAfterDwell() {
        TierDwell d = new TierDwell(4000);

        d.observe(Set.of(100, 200), 0);
        assertTrue(d.isActive(100));
        assertTrue(d.isActive(200));
        assertFalse(d.isActive(300), "unobserved map is not active");

        // 200 stops being observed at t=1000; it stays active until its deadline (0 + 4000).
        d.observe(Set.of(100), 1000);
        assertTrue(d.isActive(200), "within dwell -> still active");

        d.observe(Set.of(100), 3999);
        assertTrue(d.isActive(200), "1ms before the deadline -> still active");

        d.observe(Set.of(100), 4000);
        assertFalse(d.isActive(200), "deadline reached -> demoted");
        assertTrue(d.isActive(100), "still observed -> stays active");

        // Promotion is immediate: a brand-new map is active the instant it's observed.
        d.observe(Set.of(100, 500), 4000);
        assertTrue(d.isActive(500));
    }

    @Test
    void pacingPlayerDoesNotFlap() {
        // Observed only on even seconds (a player pacing across a portal). Gaps (1s) < dwell (4s),
        // so the map must never drop to inactive.
        TierDwell d = new TierDwell(4000);
        for (long t = 0; t <= 20_000; t += 1000) {
            Set<Integer> observed = (t / 1000) % 2 == 0 ? Set.of(100) : Set.of();
            d.observe(observed, t);
            assertTrue(d.isActive(100), "should not flap at t=" + t);
        }
    }
}
