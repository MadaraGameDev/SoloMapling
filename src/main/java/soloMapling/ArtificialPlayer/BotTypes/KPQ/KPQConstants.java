package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import java.awt.Rectangle;
import java.util.List;

/**
 * Shared values for the Kerning Party Quest bots: the quest's level range, how its event instances
 * and event properties are named, and the puzzle geometry of stages 2-4.
 */
public final class KPQConstants {

    private KPQConstants() {}

    /** Level range the quest accepts; bots outside it refuse invites. */
    public static final int MIN_LEVEL = 21;
    public static final int MAX_LEVEL = 30;

    /** Level band the Kerning City cohort is generated in; keeps a bot inside the range for a while. */
    public static final int SPAWN_MIN_LEVEL = 23;
    public static final int SPAWN_MAX_LEVEL = 29;

    /** Event instances of this quest are named with this prefix (see KerningPQ.js). */
    public static final String EVENT_NAME_PREFIX = "Kerning";

    /**
     * Prefix of the per-stage event properties shared between the bots and the stage NPC script
     * (9020001.js builds the same "kpqbot_stg" + stage prefix when it records a wrong puzzle check).
     * Keep the two in sync.
     */
    public static final String STAGE_PROPERTY_PREFIX = "kpqbot_stg";

    // ---- Puzzle geometry (stages 2-4) ---------------------------------------
    // STAGE_AREAS.get(i) holds the check areas of stage i + 2 (stage 2: four ropes, stage 3: five
    // platforms, stage 4: six barrels). STAGE_COMBINATIONS.get(i) lists every accepted answer for
    // that stage as a 0/1 mask over the same areas; every accepted answer occupies exactly three of them.

    static final List<List<Rectangle>> STAGE_AREAS = List.of(
            List.of(
                    new Rectangle(-755, -132, 4, 218), new Rectangle(-721, -340, 4, 166),
                    new Rectangle(-586, -326, 4, 150), new Rectangle(-483, -181, 4, 222)),
            List.of(
                    new Rectangle(608, -180, 140, 50), new Rectangle(791, -117, 140, 45),
                    new Rectangle(958, -180, 140, 50), new Rectangle(876, -238, 140, 45),
                    new Rectangle(702, -238, 140, 45)),
            List.of(
                    new Rectangle(910, -236, 35, 5), new Rectangle(877, -184, 35, 5),
                    new Rectangle(946, -184, 35, 5), new Rectangle(845, -132, 35, 5),
                    new Rectangle(910, -132, 35, 5), new Rectangle(981, -132, 35, 5))
    );
    static final List<List<int[]>> STAGE_COMBINATIONS = List.of(
            List.of(new int[]{0, 1, 1, 1}, new int[]{1, 0, 1, 1},
                    new int[]{1, 1, 0, 1}, new int[]{1, 1, 1, 0}),
            List.of(platforms(1, 2, 3), platforms(1, 2, 4),
                    platforms(1, 2, 5), platforms(1, 3, 4),
                    platforms(1, 3, 5), platforms(1, 4, 5),
                    platforms(2, 3, 4), platforms(2, 3, 5),
                    platforms(2, 4, 5), platforms(3, 4, 5)),
            List.of(barrels(1, 2, 3), barrels(1, 2, 4), barrels(1, 2, 5),
                    barrels(1, 2, 6), barrels(1, 3, 4), barrels(1, 3, 5),
                    barrels(1, 3, 6), barrels(1, 4, 5), barrels(1, 4, 6),
                    barrels(1, 5, 6), barrels(2, 3, 4), barrels(2, 3, 5),
                    barrels(2, 3, 6), barrels(2, 4, 5), barrels(2, 4, 6),
                    barrels(2, 5, 6), barrels(3, 4, 5), barrels(3, 4, 6),
                    barrels(3, 5, 6), barrels(4, 5, 6))
    );

    private static int[] platforms(int first, int second, int third) {
        return occupiedPositions(5, first, second, third);
    }

    private static int[] barrels(int first, int second, int third) {
        return occupiedPositions(6, first, second, third);
    }

    private static int[] occupiedPositions(int count, int first, int second, int third) {
        int[] combination = new int[count];
        combination[first - 1] = 1;
        combination[second - 1] = 1;
        combination[third - 1] = 1;
        return combination;
    }
}
