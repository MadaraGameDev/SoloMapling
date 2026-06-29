package soloMapling.ArtificialPlayer.BotGrindSystem;

import server.maps.MiniDungeonInfo;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Deterministic training-map discovery: BFS the nearby walkable maps from the bot's map, keep the
// ones whose representative mob level is in the bot's band, else the closest-level mob-bearing map.
// Reads only WZ (via GCMovement.mapsWithinHops + MapMobIndex) - no hand-authored region table; the
// walkable-portal BFS naturally excludes towns (no mobs) and PQ/instance maps (not portal-reachable).
// Mini-dungeon instances are also explicitly rejected (MiniDungeonInfo.isDungeonMap) so a bot can
// never grind or warp into a single-person instance even if a future change makes one reachable.
//
// Our own creation. Replaces TrainingBot's old REGIONS table.
public final class TrainingMapFinder {

    private TrainingMapFinder() {
    }

    private static final int MIN_MOB_COUNT = 2; // skip one-off quest-mob maps

    // Reachable mob-bearing maps that aren't far ABOVE the bot's level (upperBand). Easier maps are
    // always eligible — the caller weights them down (and occasionally up, for "chilling"). Falls back
    // to the single closest-level map so the bot always has somewhere to go. Maps in `excluded` (e.g. a
    // map the caller just left because it was overcrowded) are skipped, including for the fallback.
    public static List<TrainingMap> findTrainingMaps(int fromMapId, int level, int upperBand, int maxHops,
                                                     Set<Integer> excluded) {
        Map<Integer, Integer> nearby = GCMovement.mapsWithinHopsByDepth(fromMapId, maxHops);
        List<TrainingMap> eligible = new ArrayList<>();
        TrainingMap closest = null;
        int bestDelta = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : nearby.entrySet()) {
            int mapId = e.getKey();
            int hops = e.getValue();
            if (excluded != null && excluded.contains(mapId)) {
                continue; // caller is steering away from this map (crowd cooldown)
            }
            if (MiniDungeonInfo.isDungeonMap(mapId)) {
                continue; // single-person mini-dungeon instance — bots must never grind/warp here
            }
            MapMobIndex.MapMobInfo info = MapMobIndex.info(mapId);
            int lvl = info.medianLevel();
            if (lvl < 1 || info.mobCount() < MIN_MOB_COUNT) {
                continue; // no mobs (town) or too few to be a grind map
            }
            if (lvl <= level + upperBand) {
                eligible.add(new TrainingMap(mapId, lvl, hops));
            }
            int delta = Math.abs(lvl - level);
            if (delta < bestDelta) {
                bestDelta = delta;
                closest = new TrainingMap(mapId, lvl, hops);
            }
        }
        if (!eligible.isEmpty()) {
            return eligible;
        }
        return closest != null ? List.of(closest) : List.of();
    }
}
