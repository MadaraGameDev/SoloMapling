package scripting.event;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.Mocks;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventInstanceManagerGridTest {

    private EventInstanceManager eim;

    @BeforeEach
    void setUp() {
        eim = newInstance();
    }

    private static EventInstanceManager newInstance() {
        return new EventInstanceManager(mock(EventManager.class, RETURNS_DEEP_STUBS), "gridTest");
    }

    private static Character player(int id) {
        Character chr = Mocks.chr("player" + id);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private static int[] equalWeights(int count) {
        int[] weights = new int[count];
        java.util.Arrays.fill(weights, 1);
        return weights;
    }

    @Test
    void everyPlayerGetsADistinctStatusUntilAllAreClaimed() {
        int[] weights = equalWeights(7);
        Set<Integer> claimed = new HashSet<>();

        for (int id = 1; id <= 7; id++) {
            Character chr = player(id);
            int status = eim.gridClaimUnique(chr, weights);
            assertTrue(status >= 1 && status <= 7, "status out of range: " + status);
            assertTrue(claimed.add(status), "status handed out twice: " + status);
            assertEquals(status, eim.gridCheck(chr));
        }
        assertEquals(7, claimed.size());
    }

    @Test
    void repeatsAreAllowedOnceEveryStatusIsClaimed() {
        int[] weights = equalWeights(2);

        eim.gridClaimUnique(player(1), weights);
        eim.gridClaimUnique(player(2), weights);
        Character third = player(3);
        int status = eim.gridClaimUnique(third, weights);

        assertTrue(status == 1 || status == 2);
        assertEquals(status, eim.gridCheck(third));
        assertEquals(3, eim.gridSize());
    }

    @Test
    void clearingTheGridReleasesAllClaims() {
        int[] weights = equalWeights(2);
        eim.gridClaimUnique(player(1), weights);
        eim.gridClaimUnique(player(2), weights);

        eim.gridClear();

        Set<Integer> claimed = new HashSet<>();
        claimed.add(eim.gridClaimUnique(player(3), weights));
        claimed.add(eim.gridClaimUnique(player(4), weights));
        assertEquals(Set.of(1, 2), claimed);
    }

    @Test
    void aClaimOutlastsTheHoldersLaterStatusChanges() {
        int[] weights = equalWeights(3);
        Character first = player(1);
        int firstStatus = eim.gridClaimUnique(first, weights);

        // e.g. the player answered the question and was marked as done
        eim.gridInsert(first, 0);

        Set<Integer> others = new HashSet<>();
        others.add(eim.gridClaimUnique(player(2), weights));
        others.add(eim.gridClaimUnique(player(3), weights));

        assertEquals(2, others.size());
        assertFalse(others.contains(firstStatus), "the first player's claim was handed out again");
    }

    @Test
    void zeroWeightStatusesAreNotPickedWhileOthersAreAvailable() {
        int[] weights = {0, 1, 1};

        for (int round = 0; round < 100; round++) {
            EventInstanceManager fresh = newInstance();
            int a = fresh.gridClaimUnique(player(1), weights);
            int b = fresh.gridClaimUnique(player(2), weights);
            assertEquals(Set.of(2, 3), Set.of(a, b));
        }
    }
}
