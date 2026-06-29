package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.List;

// Cheap per-map grind measurement, computed once at the first SELECT_SPOT and cached in SpotFinder.
// The spawn layout is static WZ data, so this is effectively build-once. It is the SINGLE map
// measurement the grind sub-FSM reads: SELECT_SPOT scores `spots` for a bot (the §4 score + a per-bot
// reachability filter), and FIGHT/WAIT/RELOCATE read the regime-adjusted knob defaults. The regime label
// only nudges knob DEFAULTS (patience / relocate-eagerness) and debug narration — it NEVER branches the
// FSM. walkable* is the bbox over the walkable ledges (the grind-relevant "size", not the VR rectangle).
//
// steady-state mob count == spawnPointCount (MapleMap.respawn refills toward monsterSpawn.size()), so
// spawnDensity = spawnPointCount / walkableSpanX is a true "how camp-able" proxy. Our own creation.
public record MapGrindProfile(
        int mapId,
        int walkableMinX,
        int walkableMaxX,
        int walkableSpanX,
        int spawnPointCount,
        double spawnDensity,
        List<Spot> spots,
        int clusterCount,
        int meanInterSpotGapX,
        Regime regime,
        long builtAtMs) {

    // Coarse map classification. Tunes knob defaults + narration only; the FSM is identical for all three.
    public enum Regime { COMPACT, SPREAD, SPARSE }
}
