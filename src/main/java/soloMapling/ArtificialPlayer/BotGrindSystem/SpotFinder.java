package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Stateless spot math for the localized grind sub-FSM (GrindBrain). Two jobs:
//  - profile(map): cluster the map's static spawn points into candidate Spots (cluster-sized radii) and
//    measure the map once (walkable span, spawn density, regime). Cached per map — the spawn layout is
//    static WZ data, so this is build-once.
//  - pickBest / nearestHostileWithin: score candidate spots for a specific bot at SELECT_SPOT, and the
//    cheap per-tick radius scan FIGHT/WAIT use.
//
// Replaces ZoneAllocator (wide band) and the band-scoring half of MobClusterFinder. It scores PLACES TO
// STAND (durable spawn-point density), once per relocation — not individual live mobs, every tick — which
// is what makes the bot settle and camp instead of chasing map-global respawns across the screen. Reads
// terrain only through the generic GCMovement queries. Our own creation (not a GreenCat extraction).
public final class SpotFinder {

    private SpotFinder() {
    }

    // ── Clustering tunables (§4c) ──
    private static final int SPOT_RADIUS_MIN = 250;          // cluster-sized spot radius clamp (lower)
    private static final int SPOT_RADIUS_MAX = 500;          // ... and upper (≈ one screen). THE critical knob-pair.
    private static final int SPOT_CLUSTER_MERGE_PX = 350;    // greedy single-linkage merge distance
    private static final double SPOT_CLUSTER_VERTICAL_SCALE = 2.5; // dy weight vs dx (stacked platforms split)

    // ── Regime thresholds (tune knob DEFAULTS only — never branch the FSM). Calibrate against a real
    //    !env grindprofile dump before trusting these (§13.10 #1). ──
    private static final double DENSITY_HI = 0.012;         // spawns/px above which a map reads COMPACT
    private static final double DENSITY_LO = 0.005;         // ... below which it reads SPARSE
    private static final int GAP_LO = 600;                  // inter-spot gap below which a map reads COMPACT
    private static final int SPARSE_SPAWN_COUNT = 6;        // very few spawn points → SPARSE regardless of density

    // ── Spot-selection score weights (§4) ──
    private static final double SPAWN_DENSITY_W = 10.0;     // per spawn point in the cluster (durable "stays fed")
    private static final double LIVE_MOB_W = 6.0;          // per live hostile in radius now (start-hot bias)
    private static final double DISTANCE_W = 0.015;        // per px to the anchor (prefer near → minimal traversal)
    private static final double CROWDING_W = 30.0;         // per claimant (spread the cohort)
    private static final double OVER_CAP_PENALTY = 100_000.0; // soft cap: an over-cap spot is chosen only if nothing else

    // Spot claim capacity (shared with GrindBrain's claim call). 1 = one bot per spot is the norm, so a
    // cohort fans out across the map's spots instead of doubling up. The OVER_CAP_PENALTY soft cap still lets
    // bots share a spot, but ONLY when every reachable spot is already taken (saturated / tiny map). Raise to
    // 2 to deliberately allow a pair on a hot spot (they self-separate by targeting different mobs in it).
    public static final int MAX_BOTS_PER_SPOT = 1;

    // Per-map profile cache. Static spawn layout → effectively build-once (no invalidation in practice).
    private static final ConcurrentHashMap<Integer, MapGrindProfile> CACHE = new ConcurrentHashMap<>();

    // ── Profile (cached) ──

    // The cached grind profile for a map; builds clusters + measures the map on first touch. Building warms
    // the nav graph (walkableLedges / regionIdAt trigger the one-time per-map bake) — fine, the bot has
    // already committed to grinding here. get/putIfAbsent (not computeIfAbsent) so the expensive bake never
    // runs under a map bin lock; a rare double-build just races to the same result.
    public static MapGrindProfile profile(MapleMap map) {
        if (map == null) {
            return null;
        }
        int id = map.getId();
        MapGrindProfile cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        MapGrindProfile built = build(map);
        MapGrindProfile prev = CACHE.putIfAbsent(id, built);
        return prev != null ? prev : built;
    }

    private static MapGrindProfile build(MapleMap map) {
        List<Spot> spots = clusterSpawns(map);

        // Walkable bbox = the grind-relevant "size" (a map can have a huge VR but a small walkable strip).
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (GCMovement.Ledge l : GCMovement.walkableLedges(map)) {
            minX = Math.min(minX, l.minX());
            maxX = Math.max(maxX, l.maxX());
        }
        if (minX > maxX) {
            minX = 0;
            maxX = 0;
        }
        int spanX = Math.max(0, maxX - minX);
        int spawnCount = map.getMonsterSpawnPositions().size();
        double density = spawnCount / (double) Math.max(1, spanX);
        int meanGap = meanInterSpotGapX(spots);
        MapGrindProfile.Regime regime = classify(density, spawnCount, spots.size(), meanGap);

        return new MapGrindProfile(map.getId(), minX, maxX, spanX, spawnCount, density,
                spots, spots.size(), meanGap, regime, System.currentTimeMillis());
    }

    private static MapGrindProfile.Regime classify(double density, int spawnCount, int clusterCount, int meanGap) {
        if (spawnCount <= SPARSE_SPAWN_COUNT || density <= DENSITY_LO) {
            return MapGrindProfile.Regime.SPARSE;
        }
        if (clusterCount <= 1 || (density >= DENSITY_HI && meanGap <= GAP_LO)) {
            return MapGrindProfile.Regime.COMPACT;
        }
        return MapGrindProfile.Regime.SPREAD;
    }

    // Mean |Δx| between x-sorted adjacent spot anchors (0 if < 2 spots).
    private static int meanInterSpotGapX(List<Spot> spots) {
        if (spots.size() < 2) {
            return 0;
        }
        List<Integer> xs = new ArrayList<>(spots.size());
        for (Spot s : spots) {
            xs.add(s.anchor().x);
        }
        xs.sort(Integer::compareTo);
        long sum = 0;
        for (int i = 1; i < xs.size(); i++) {
            sum += Math.abs(xs.get(i) - xs.get(i - 1));
        }
        return (int) (sum / (xs.size() - 1));
    }

    // ── Clustering: greedy single-linkage, anisotropic (§4c) ──

    private record SpawnPt(Point foothold, int region) {
    }

    private static List<Spot> clusterSpawns(MapleMap map) {
        // 1. Snap every static spawn point to the foothold under it + its region.
        List<SpawnPt> pts = new ArrayList<>();
        for (Point p : map.getMonsterSpawnPositions()) {
            Point g = GCMovement.groundPointBelow(map, p.x, p.y);
            Point a = (g != null) ? g : p;
            int region = GCMovement.regionIdAt(map, a.x, a.y);
            pts.add(new SpawnPt(a, region));
        }
        if (pts.isEmpty()) {
            return List.of();
        }

        // 2. Greedy single-linkage under the anisotropic metric (dy weighted x VERTICAL_SCALE) so a long
        //    horizontal platform stays ONE cluster while stacked platforms split, without hard region gating.
        List<List<SpawnPt>> clusters = greedySingleLinkage(pts, SPOT_CLUSTER_MERGE_PX);

        // 3. Turn each cluster into one Spot, or tile a too-wide cluster into a row of MAX-bounded spots.
        List<Spot> spots = new ArrayList<>();
        for (List<SpawnPt> c : clusters) {
            if (robustHalfSpreadX(c) <= SPOT_RADIUS_MAX) {
                spots.add(makeSpot(c));
            } else {
                for (List<SpawnPt> slice : tileByX(c, 2 * SPOT_RADIUS_MAX)) {
                    spots.add(makeSpot(slice));
                }
            }
        }
        return spots;
    }

    // Union-find single-linkage: any two points within mergePx (anisotropic) join, transitively.
    private static List<List<SpawnPt>> greedySingleLinkage(List<SpawnPt> pts, int mergePx) {
        int n = pts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        double thr2 = (double) mergePx * mergePx;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (dist2(pts.get(i).foothold(), pts.get(j).foothold()) <= thr2) {
                    union(parent, i, j);
                }
            }
        }
        java.util.Map<Integer, List<SpawnPt>> byRoot = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(pts.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    private static double dist2(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = (a.y - b.y) * SPOT_CLUSTER_VERTICAL_SCALE;
        return dx * dx + dy * dy;
    }

    // p90 of |x - meanX| — the horizontal half-extent, ignoring a lone outlier. 0 for a single point.
    private static int robustHalfSpreadX(List<SpawnPt> members) {
        int n = members.size();
        if (n <= 1) {
            return 0;
        }
        long sumX = 0;
        for (SpawnPt s : members) {
            sumX += s.foothold().x;
        }
        double meanX = sumX / (double) n;
        List<Integer> devs = new ArrayList<>(n);
        for (SpawnPt s : members) {
            devs.add((int) Math.abs(s.foothold().x - meanX));
        }
        devs.sort(Integer::compareTo);
        int idx = (int) Math.round(0.9 * (n - 1));
        return devs.get(Math.max(0, Math.min(n - 1, idx)));
    }

    // Split an over-wide cluster into adjacent <=sliceWidth-wide slices by x; each slice owns its points.
    private static List<List<SpawnPt>> tileByX(List<SpawnPt> members, int sliceWidth) {
        List<SpawnPt> sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> Integer.compare(a.foothold().x, b.foothold().x));
        List<List<SpawnPt>> slices = new ArrayList<>();
        List<SpawnPt> cur = new ArrayList<>();
        int sliceStartX = sorted.isEmpty() ? 0 : sorted.get(0).foothold().x;
        for (SpawnPt s : sorted) {
            if (!cur.isEmpty() && s.foothold().x - sliceStartX > sliceWidth) {
                slices.add(cur);
                cur = new ArrayList<>();
                sliceStartX = s.foothold().x;
            }
            cur.add(s);
        }
        if (!cur.isEmpty()) {
            slices.add(cur);
        }
        return slices;
    }

    // Anchor = the member foothold nearest the centroid (guaranteed walkable & reachable-by-construction;
    // a raw centroid can float mid-air between platforms, a member foothold never does). radius = the
    // cluster's robust half-spread, clamped to [MIN, MAX].
    private static Spot makeSpot(List<SpawnPt> members) {
        long sumX = 0, sumY = 0;
        for (SpawnPt s : members) {
            sumX += s.foothold().x;
            sumY += s.foothold().y;
        }
        int n = members.size();
        Point centroid = new Point((int) (sumX / n), (int) (sumY / n));
        SpawnPt anchor = members.get(0);
        double bestSq = Double.MAX_VALUE;
        for (SpawnPt s : members) {
            double dsq = s.foothold().distanceSq(centroid);
            if (dsq < bestSq) {
                bestSq = dsq;
                anchor = s;
            }
        }
        int radius = clamp(robustHalfSpreadX(members), SPOT_RADIUS_MIN, SPOT_RADIUS_MAX);
        return new Spot(anchor.foothold(), anchor.region(), radius, n);
    }

    // ── Selection + the FIGHT/WAIT radius scan ──

    // Score each candidate spot for this bot and return the highest scorer (or null if none reachable).
    // Hard filters: a spot's region must be reachable from where the bot stands (skip the filter if that
    // set is empty — graph unbaked / on no ledge); skip the just-left spot while its cooldown holds. An
    // over-cap spot is heavily down-weighted (chosen only if nothing else qualifies). DISTANCE_W is the
    // anti-traversal lever at the selection layer — a near healthy spot beats a far marginally-denser one.
    public static Spot pickBest(Character chr, MapGrindProfile p, int excludedIdx, long excludedUntilMs) {
        if (chr == null || p == null || p.spots().isEmpty()) {
            return null;
        }
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return null;
        }
        Set<Integer> reach = GCMovement.reachableRegions(map, pos.x, pos.y);
        boolean filter = !reach.isEmpty();
        long now = System.currentTimeMillis();
        List<Spot> spots = p.spots();
        Spot best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            if (filter && s.regionId() >= 0 && !reach.contains(s.regionId())) {
                continue; // unreachable island ledge
            }
            if (i == excludedIdx && now < excludedUntilMs) {
                continue; // just-left this spot — let it cool down
            }
            int holders = BotSpotClaims.holders(map.getId(), i);
            double score = SPAWN_DENSITY_W * s.spawnCount()
                    + LIVE_MOB_W * liveHostilesWithin(map, s.anchor(), s.radius())
                    - DISTANCE_W * pos.distance(s.anchor())
                    - CROWDING_W * holders;
            if (holders >= MAX_BOTS_PER_SPOT) {
                score -= OVER_CAP_PENALTY; // soft cap
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    // The nearest live hostile on the spot anchor's OWN ledge, within the radius box. Cheap: one scan plus a
    // peek-only ledge check per in-box mob (never triggers a graph build, no O(n^2)). The same-ledge gate —
    // not a vertical pixel box — is what keeps a planted bot from acquiring a mob on a stacked platform
    // above/below and roping across to it; it mirrors BotAttackDriver.onAttackableSurface. The radius-tall Y
    // box is a coarse pre-filter only: it short-circuits the cheap arithmetic before the ledge lookup, and
    // it's the sole vertical bound when the nav graph isn't baked (onDifferentLedge degrades to "don't
    // filter"). Replaces MobClusterFinder.
    public static Monster nearestHostileWithin(MapleMap map, Point anchor, int radius) {
        if (map == null || anchor == null) {
            return null;
        }
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            if (!isHostile(m)) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null || Math.abs(mp.x - anchor.x) > radius || Math.abs(mp.y - anchor.y) > radius) {
                continue; // outside the X leash, or beyond the coarse Y fallback bound
            }
            if (GCMovement.onDifferentLedge(map, anchor.x, anchor.y, mp.x, mp.y)) {
                continue; // on a separate platform from the anchor — don't bleed vertically to it
            }
            double dsq = anchor.distanceSq(mp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = m;
            }
        }
        return best;
    }

    // Live hostiles within a spot's radius (backs the !env grindprofile debug dump).
    public static int liveHostilesWithin(MapleMap map, Spot spot) {
        return spot == null ? 0 : liveHostilesWithin(map, spot.anchor(), spot.radius());
    }

    private static int liveHostilesWithin(MapleMap map, Point anchor, int radius) {
        int count = 0;
        for (Monster m : map.getAllMonsters()) {
            if (!isHostile(m)) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null || Math.abs(mp.x - anchor.x) > radius || Math.abs(mp.y - anchor.y) > radius) {
                continue;
            }
            if (GCMovement.onDifferentLedge(map, anchor.x, anchor.y, mp.x, mp.y)) {
                continue; // count only same-ledge mobs so a spot's "fight now" score reflects its own platform
            }
            count++;
        }
        return count;
    }

    // Shared hostile predicate (moved here from MobSeeker.isHostile; SpotFinder is the stateless spot/mob
    // util, so it's the single home — GrindBrain and TrainingBot both call SpotFinder.isHostile).
    public static boolean isHostile(Monster m) {
        return m != null && m.isAlive()
                && (m.getStats() == null || !m.getStats().isFriendly());
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
