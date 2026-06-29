package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

// The per-bot localized grind sub-FSM. Replaces MobSeeker's flat wide-zone seek with a state machine
// that plants on a tight spawn-point-dense spot and waits respawn lulls out instead of chasing
// map-global respawns across the screen:
//
//   SELECT_SPOT -> TRAVEL_TO_SPOT -> FIGHT <-> WAIT -> RELOCATE -> SELECT_SPOT   (RECOVER orthogonal)
//
// The bot spends almost all its grind life oscillating FIGHT <-> WAIT in ONE place; that oscillation is
// the natural look. Targeting, approach, and loot are all leashed to the spot radius (not a wide band),
// which is what kills the over-traversal. The expensive map-wide work (cluster spawn points + score
// candidates) runs only at SELECT_SPOT; the hot FIGHT/WAIT ticks do cheap local radius scans.
//
// Mirrors MobSeeker's public surface so TrainingBot's macro brain + watchdog need only renames. Rope
// recovery and organic loot are ported verbatim from MobSeeker (only the loot leash and sticky-target
// source changed). Our own creation (not a GreenCat extraction); reads terrain only through GCMovement.
public final class GrindBrain {

    // ── Approach / engage tunables (ported from MobSeeker, unchanged except the band->spot clamp) ──
    private static final int APPROACH_X = 70;                // melee floor: within this dx -> stop and swing
    private static final int APPROACH_Y = 90;                // melee floor: within this dy -> in range
    private static final double APPROACH_REACH_FRAC = 0.80;  // stop at this fraction of attack reach
    private static final int ROAM_RETARGET_EPS = 16;         // skip re-issuing move for tiny shifts
    private static final long TARGET_RETARGET_TIMEOUT = 4_000; // give up walking to an unreachable target/spot after this
    private static final int APPROACH_PROGRESS_EPS = 20;     // bot must move at least this far to count as progress
    private static final int APPROACH_Y_TOLERANCE = 120;     // within() vertical slack (covers a sloped/stacked anchor)

    // ── Rope recovery (ported verbatim) ──
    private static final long CLIMB_STALL_MS = 1_200;
    private static final int CLIMB_PROGRESS_EPS = 6;
    private static final long DISMOUNT_GRACE_MS = 3_000;

    // ── Organic loot (ported; only the SEEK range shrinks from a flat 900 to the spot radius) ──
    private static final int LOOT_PICKUP_PX = 60;            // close enough to grab
    private static final long LOOT_GAP_MIN_MS = 100;         // stagger between pickups
    private static final long LOOT_GAP_MAX_MS = 350;
    // Periodic loot sweep: ranged/magic bots kill from range, so drops land out at the mob (not at the bot's
    // feet) and grabLootAtFeet alone never collects them. Every LOOT_SWEEP_GAP_MS a stationed bot steps off
    // to sweep up its pile, then resumes fighting. Lower → loots more eagerly (more loot:fight time).
    private static final long LOOT_SWEEP_GAP_MS = 7_000;
    private static final int LOOT_SAME_LEDGE_Y = 120;       // only walk to drops near the bot's Y (keep loot on its ledge)
    // Let a fresh drop finish its fly-out arc and settle on the floor before the bot grabs it — instant
    // pickup looks like the item teleports into the bot mid-air. Aged against MapItem.getDropTime() (the
    // server clock stamp set when the drop spawns), so it's "this drop has been on the ground ≥ this long".
    private static final long LOOT_SETTLE_MS = 1_500;

    // ── Patience / relocation (new) ──
    private static final long WAIT_PATIENCE_MS = 8_000;      // lull tolerance before even CONSIDERING a relocate
    private static final long SPOT_UNPRODUCTIVE_MS = 35_000; // cumulative no-kill window that (with patience) relocates
    private static final long RELOCATE_EXCLUDE_MS = 30_000;  // down-weight a just-left spot so it isn't re-picked at once
    private static final int SELECT_CLAIM_ATTEMPTS = 3;      // re-pick this many times if a spot fills under us (cohort race)

    enum State { SELECT_SPOT, TRAVEL_TO_SPOT, FIGHT, WAIT, RELOCATE }

    private final Consumer<String> debug;  // debug narration sink (TrainingBot::debugChat)
    private final Random rng = new Random();

    // ── Spot + FSM state ──
    private State state = State.SELECT_SPOT;
    private Spot spot;                       // current claimed spot (anchor, regionId, radius, spawnCount)
    private int spotIndex = -1;              // index into profile.spots() == BotSpotClaims spotId
    private int excludedSpotIndex = -1;      // just-left spot, down-weighted for RELOCATE_EXCLUDE_MS
    private long excludedUntilMs = 0L;

    // ── Sticky target + approach-progress give-up ──
    private int targetOid = -1;
    private long retargetDeadline = 0L;
    private int noProgressAnchorX = Integer.MIN_VALUE;
    private int noProgressAnchorY = 0;

    // ── Engage / approach ──
    private boolean engaged = false;
    private int lastMoveTargetX = Integer.MIN_VALUE;
    private int approachX = -1;              // lazy attack-reach cache
    private int approachY = -1;

    // ── Two-tier patience ──
    private long waitStartedMs = 0L;
    private long lastKillMs = 0L;

    // ── Loot + climb ──
    private long lootNextMs = 0L;
    private long nextLootSweepMs = 0L;       // next periodic step-off-to-collect-drops sweep (FIGHT)
    private long climbStallSinceMs = 0L;
    private long lastDismountMs = 0L;
    private int lastClimbY = 0;

    // ── Combat heartbeat (read by TrainingBot's macro watchdog -> volatile) ──
    private volatile long lastCombatProgressMs = 0L;
    private boolean wasObserved = false;

    // ── Map saturation (read by TrainingBot's macro crowd-bail -> volatile) ──
    // True when the last spot selection found every reachable spot already claimed, so the bot is sharing.
    private volatile boolean mapSaturated = false;

    // ── Debug narration (edge-triggered: speak only when the message changes) ──
    private static final boolean NARRATE = false; // master toggle: false silences all grind debug chatter
    private static final long GIVE_UP_NARRATE_GAP_MS = 8_000;
    private static final long LOOT_NARRATE_GAP_MS = 6_000; // throttle loot lines (pickups are frequent)
    private String lastNarrate = "";
    private long lastGiveUpNarrateMs = 0L;
    private long lastLootNarrateMs = 0L;

    public GrindBrain(Consumer<String> debug) {
        this.debug = debug != null ? debug : msg -> { };
    }

    // ── Public surface (mirrors MobSeeker) ──

    // Grind-episode entry: fresh heartbeat, pick a spot, and issue the first move so the bot disperses into
    // the field even before a player arrives (the driver's analytic coarse executor walks it there).
    public void start(Character chr) {
        lastCombatProgressMs = now();
        wasObserved = false;
        engaged = false;
        targetOid = -1;
        lastMoveTargetX = Integer.MIN_VALUE;
        excludedSpotIndex = -1;
        excludedUntilMs = 0L;
        spotIndex = -1;
        spot = null;
        mapSaturated = false;
        lastKillMs = now();
        nextLootSweepMs = now() + LOOT_SWEEP_GAP_MS;
        lastNarrate = "";
        lastGiveUpNarrateMs = 0L;
        state = State.SELECT_SPOT;
        doSelectSpot(chr); // pre-select + move so a cohort spreads to spots on arrival, even unobserved
    }

    // One combat tick (already gated by the caller on running && phase == GRIND). Observed maps run the
    // sub-FSM; unobserved maps no-op here (the macro tick accrues abstract EXP instead).
    public void tick(Character chr) {
        if (chr == null) {
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            wasObserved = false; // map went quiet; the next observed tick starts a fresh stuck window
            return;
        }
        if (!wasObserved) {
            // A player just arrived: the bot was abstract-leveling, not swinging, so reset the heartbeat so
            // the watchdog gives a grace window instead of instantly judging it stuck.
            wasObserved = true;
            lastCombatProgressMs = now();
        }
        if (GCMovement.isClimbing(chr)) {
            handleClimb(chr); // RECOVER inline
            return;
        }
        climbStallSinceMs = 0L; // back on a foothold -> reset the stall tracker
        switch (state) {
            case SELECT_SPOT -> doSelectSpot(chr);
            case TRAVEL_TO_SPOT -> doTravel(chr);
            case FIGHT -> doFight(chr);
            case WAIT -> doWait(chr);
            case RELOCATE -> state = State.SELECT_SPOT; // claim already released on the way into RELOCATE
        }
    }

    // Drop the spot claim + reset combat state when the bot leaves the map / stops.
    public void release(Character chr) {
        if (spotIndex >= 0 && chr != null) {
            BotSpotClaims.release(chr.getMapId(), spotIndex, chr.getId());
        }
        spot = null;
        spotIndex = -1;
        targetOid = -1;
        engaged = false;
        lastMoveTargetX = Integer.MIN_VALUE;
        mapSaturated = false;
        state = State.SELECT_SPOT;
    }

    // Watchdog teleport-to-portal escalation: drop the target and re-select a spot from the new position.
    // Deliberately does NOT reset the heartbeat, so the bail timer keeps running if the teleport doesn't
    // help. doSelectSpot releases the old claim before claiming the new one, so no claim leaks here.
    public void resetupAfterTeleport(Character chr) {
        targetOid = -1;
        resetApproachProgress(chr);
        state = State.SELECT_SPOT;
    }

    public long msSinceProgress() {
        return now() - lastCombatProgressMs;
    }

    // True when the last spot selection found every reachable spot already claimed (the bot is sharing).
    // TrainingBot's macro crowd-bail reads this to leave an over-crowded map for a deeper, less-crowded one.
    public boolean mapSaturated() {
        return mapSaturated;
    }

    // True while the bot is mid-rope or just dismounted — the watchdog defers escalation during recovery.
    public boolean isRecovering(Character chr) {
        return GCMovement.isClimbing(chr) || (now() - lastDismountMs) < DISMOUNT_GRACE_MS;
    }

    public String spotLabel() {
        return spot == null ? "[no-spot]"
                : "[spot x" + spot.anchor().x + " r" + spot.radius() + " n" + spot.spawnCount() + "]";
    }

    // ── States ──

    private void doSelectSpot(Character chr) {
        if (chr == null || chr.getMap() == null) {
            return;
        }
        // Release any spot we were holding (teleport re-select path) before picking a new one — keeps claims
        // from leaking and makes SELECT_SPOT idempotent w.r.t. the claim registry.
        if (spotIndex >= 0) {
            BotSpotClaims.release(chr.getMapId(), spotIndex, chr.getId());
            spotIndex = -1;
        }
        MapGrindProfile p = SpotFinder.profile(chr.getMap()); // cached; builds clusters on first touch
        // Pick a spot AND secure its claim against a cohort that selected at the same instant. claim() is
        // capacity-enforcing, so a -1 means the spot filled between scoring and claiming (two bots that
        // arrived together both saw it empty). Re-pick: pickBest now sees the winner's holder count, and the
        // over-cap penalty steers us to a free spot. Honoring the claim result (the old code discarded it) is
        // what actually makes the cap bite — otherwise the loser just grinds the same spot anyway.
        Spot s = null;
        int idx = -1;
        boolean claimed = false;
        for (int attempt = 0; attempt < SELECT_CLAIM_ATTEMPTS; attempt++) {
            s = SpotFinder.pickBest(chr, p, excludedSpotIndex, excludedUntilMs);
            if (s == null) {
                spot = null;
                mapSaturated = false; // no reachable spot is a different problem (watchdog), not crowding
                narrate("no reachable spot here -> waiting"); // edge-triggered; macro watchdog bails if it persists
                return; // rare (DECIDE guarantees mobs)
            }
            idx = indexOfSame(p.spots(), s);
            if (BotSpotClaims.claim(chr.getMapId(), idx, SpotFinder.MAX_BOTS_PER_SPOT, chr.getId()) >= 0) {
                claimed = true;
                break;
            }
        }
        // Never got an open slot → the map is saturated (more bots than spots). Grind the best candidate
        // shared rather than idling; s/idx hold the last (best) spot scored. The bot isn't registered as a
        // holder in this case, but every spot is already at cap so the slight undercount is harmless.
        mapSaturated = !claimed; // every reachable spot already claimed -> sharing -> signal the macro crowd-bail
        if (!claimed) {
            narrate("all spots taken -> sharing the best one");
        }
        spotIndex = idx;
        spot = s;
        resetApproachProgress(chr);
        lastKillMs = now();
        if (within(chr, spot.anchor(), spot.radius())) {
            narrate("FIGHT " + spotLabel());
            state = State.FIGHT;
        } else {
            GCMovement.move(chr, spot.anchor().x, spot.anchor().y);
            lastMoveTargetX = spot.anchor().x;
            narrate("TRAVEL -> " + spotLabel());
            state = State.TRAVEL_TO_SPOT;
        }
    }

    private void doTravel(Character chr) {
        if (spot == null) {
            state = State.SELECT_SPOT;
            return;
        }
        if (within(chr, spot.anchor(), spot.radius())) {
            GCMovement.stop(chr);
            resetApproachProgress(chr);
            narrate("FIGHT " + spotLabel());
            state = State.FIGHT;
            return;
        }
        if (madeApproachProgress(chr)) {
            markProgress(); // moving toward the spot is not stuck (§13.4 (3))
        } else if (now() > retargetDeadline) {
            toRelocate(chr); // can't reach this spot -> exclude it and re-pick
        }
    }

    private void doFight(Character chr) {
        if (spot == null) {
            state = State.SELECT_SPOT;
            return;
        }
        Monster t = stickyTarget(chr);
        if (t == null) {
            t = SpotFinder.nearestHostileWithin(chr.getMap(), spot.anchor(), spot.radius());
            targetOid = (t != null) ? t.getObjectId() : -1;
            resetApproachProgress(chr);
        }
        if (t == null) {
            enterWait(chr);
            return;
        }
        // Periodic loot sweep: a ranged/magic bot stands and kills from range, so its drops land out at the
        // mob and grabLootAtFeet never reaches them. Every LOOT_SWEEP_GAP_MS, step off to collect the pile in
        // the spot, then resume fighting. The throttle is re-armed only when there's nothing to collect, so a
        // started sweep runs to completion over consecutive ticks. No-ops for melee (their pile is already at
        // their feet, picked up by grabLootAtFeet).
        if (now() >= nextLootSweepMs) {
            if (tryWalkAndLoot(chr)) {
                return; // walking to / clearing the pile this tick
            }
            nextLootSweepMs = now() + LOOT_SWEEP_GAP_MS; // nothing to collect — re-arm
        }
        if (inAttackRange(chr, t)) {
            if (!engaged) {
                GCMovement.stop(chr); // plant and swing
                engaged = true;
                lastMoveTargetX = Integer.MIN_VALUE;
            }
            BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(chr);
            if (r != null && (r.hit() || r.killed())) {
                markProgress(); // landed hit/kill -> not stuck (§13.4 (1))
            }
            if (r != null && r.killed()) {
                targetOid = -1; // re-acquire the next mob in the spot
                lastKillMs = now();
            }
            grabLootAtFeet(chr);
            return;
        }
        engaged = false;
        if (tryWalkAndLoot(chr)) {
            return; // tidy a nearby drop first (leashed to the spot radius)
        }
        approachLeashed(chr, t);
        if (madeApproachProgress(chr)) {
            markProgress(); // closing on the mob -> not stuck (§13.4 (3))
        } else if (now() > retargetDeadline) {
            targetOid = -1; // unreachable mob in radius -> re-pick; heartbeat NOT refreshed, so a real wedge shows
            narrateGiveUp();
        }
    }

    private void enterWait(Character chr) {
        engaged = false;
        waitStartedMs = now();
        GCMovement.stop(chr);
        narrate("WAIT (respawn lull) " + spotLabel());
        state = State.WAIT;
    }

    private void doWait(Character chr) {
        if (spot == null) {
            state = State.SELECT_SPOT;
            return;
        }
        Monster t = SpotFinder.nearestHostileWithin(chr.getMap(), spot.anchor(), spot.radius());
        if (t != null) {
            targetOid = t.getObjectId();
            resetApproachProgress(chr);
            narrate("FIGHT " + spotLabel());
            state = State.FIGHT;
            return;
        }
        if (spotStillValid(chr)) {
            markProgress(); // healthy patience at a live spot is not stuck (§13.4 (2))
        }
        // Idle behaviour = collect the drop pile. A respawn lull is exactly when a real player walks around
        // tidying up their loot; tryWalkAndLoot sweeps the whole spot (it covers the full radius from wherever
        // the bot stopped), so a stationed ranged bot finally clears its pile here instead of fidgeting.
        if (tryWalkAndLoot(chr)) {
            return;
        }
        // Pile cleared and no mob — just hold position and wait out the respawn lull.
        if (now() - waitStartedMs >= WAIT_PATIENCE_MS && now() - lastKillMs >= SPOT_UNPRODUCTIVE_MS) {
            toRelocate(chr);
        }
    }

    private void toRelocate(Character chr) {
        excludedSpotIndex = spotIndex;
        excludedUntilMs = now() + RELOCATE_EXCLUDE_MS;
        if (spotIndex >= 0 && chr != null) {
            BotSpotClaims.release(chr.getMapId(), spotIndex, chr.getId());
        }
        spot = null;
        spotIndex = -1;
        targetOid = -1;
        narrate("RELOCATE (spot dry)");
        state = State.RELOCATE;
    }

    // The current sticky target, still valid: alive, hostile, within the spot radius of the ANCHOR (not the
    // bot), and on the anchor's OWN ledge. Anchoring stickiness to the spot is what stops a fleeing/kited mob
    // dragging the bot off; the same-ledge check stops a mob that jumps/wanders onto a stacked platform from
    // keeping the bot committed to roping after it (it gets re-acquired on our own ledge next tick).
    private Monster stickyTarget(Character chr) {
        if (targetOid < 0 || spot == null) {
            return null;
        }
        MapObject mo = chr.getMap().getMapObject(targetOid);
        if (!(mo instanceof Monster m) || !SpotFinder.isHostile(m)) {
            return null;
        }
        Point mp = m.getPosition();
        if (mp == null || Math.abs(mp.x - spot.anchor().x) > spot.radius()) {
            return null; // left the spot radius
        }
        if (GCMovement.onDifferentLedge(chr.getMap(), spot.anchor().x, spot.anchor().y, mp.x, mp.y)) {
            return null; // moved onto a separate platform — drop it and re-acquire on our own ledge
        }
        return m;
    }

    // Out of range: walk toward the mob's foothold, leashed to the spot radius around the anchor so the bot
    // never leaves the spot to chase a mob. Aims at the foothold UNDER the mob (a jumping/airborne or sloped
    // mob otherwise reads as a point in empty space and detours the pathfinder).
    private void approachLeashed(Character chr, Monster mob) {
        Point mp = mob.getPosition();
        if (mp == null || spot == null) {
            return;
        }
        int anchorX = spot.anchor().x;
        int tx = clamp(mp.x, anchorX - spot.radius(), anchorX + spot.radius());
        if (Math.abs(tx - lastMoveTargetX) < ROAM_RETARGET_EPS) {
            return; // already heading there
        }
        Point gp = GCMovement.groundPointBelow(chr.getMap(), mp.x, mp.y);
        int ty = (gp != null) ? gp.y : mp.y;
        GCMovement.move(chr, tx, ty);
        lastMoveTargetX = tx;
    }

    // Lazily derive (and cache) the stop-and-swing distance from this bot's attack reach so ranged/magic
    // bots fire from afar. Floored at the melee values for short attacks.
    private boolean inAttackRange(Character chr, Monster mob) {
        if (approachX < 0) {
            approachX = Math.max(APPROACH_X, (int) (BotAttackDriver.attackReachX(chr) * APPROACH_REACH_FRAC));
            approachY = Math.max(APPROACH_Y, (int) (BotAttackDriver.attackReachY(chr) * APPROACH_REACH_FRAC));
        }
        Point pos = chr.getPosition();
        Point mp = mob.getPosition();
        return pos != null && mp != null
                && Math.abs(mp.x - pos.x) <= approachX && Math.abs(mp.y - pos.y) <= approachY;
    }

    // Healthy WAIT test for the heartbeat contract: a spot that's still reachable and still has spawn points
    // is feeding, so waiting it out is working, not wedged. Graph unbaked / on no ledge -> don't penalize.
    private boolean spotStillValid(Character chr) {
        if (spot == null || spot.spawnCount() <= 0) {
            return false;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return false;
        }
        Set<Integer> reach = GCMovement.reachableRegions(chr.getMap(), pos.x, pos.y);
        return reach.isEmpty() || spot.regionId() < 0 || reach.contains(spot.regionId());
    }

    private void markProgress() {
        lastCombatProgressMs = now();
    }

    // ── Approach-progress tracking (no-progress give-up on an unreachable target/spot; ported verbatim) ──

    private void resetApproachProgress(Character chr) {
        retargetDeadline = now() + TARGET_RETARGET_TIMEOUT;
        Point p = (chr != null) ? chr.getPosition() : null;
        noProgressAnchorX = (p != null) ? p.x : Integer.MIN_VALUE;
        noProgressAnchorY = (p != null) ? p.y : 0;
    }

    private boolean madeApproachProgress(Character chr) {
        Point p = chr.getPosition();
        if (p == null) {
            return false;
        }
        if (noProgressAnchorX == Integer.MIN_VALUE
                || Math.abs(p.x - noProgressAnchorX) + Math.abs(p.y - noProgressAnchorY) > APPROACH_PROGRESS_EPS) {
            noProgressAnchorX = p.x;
            noProgressAnchorY = p.y;
            retargetDeadline = now() + TARGET_RETARGET_TIMEOUT; // moving -> push the give-up deadline out
            return true;
        }
        return false;
    }

    // ── Rope recovery (ported verbatim; only dismountTowardMob now reads the sticky target) ──

    private void handleClimb(Character chr) {
        Point pos = chr.getPosition();
        int y = (pos != null) ? pos.y : 0;
        if (GCMovement.isNavigatingClimb(chr)) {
            climbStallSinceMs = 0L; // deliberate traversal — let the driver finish it
            lastClimbY = y;
            return;
        }
        if (climbStallSinceMs == 0L || Math.abs(y - lastClimbY) >= CLIMB_PROGRESS_EPS) {
            climbStallSinceMs = now(); // making progress (or first sample) — let the climb continue
            lastClimbY = y;
            return;
        }
        if (now() - climbStallSinceMs >= CLIMB_STALL_MS) {
            dismountTowardMob(chr, stickyTarget(chr)); // hung rope -> jump off toward the current target
        }
    }

    private void dismountTowardMob(Character chr, Monster mob) {
        int dx = 0;
        Point pos = chr.getPosition();
        if (mob != null && mob.getPosition() != null && pos != null) {
            dx = Integer.compare(mob.getPosition().x, pos.x);
        }
        GCMovement.dismountRope(chr, dx);
        lastDismountMs = now();
        climbStallSinceMs = 0L;
        engaged = false;
        lastMoveTargetX = Integer.MIN_VALUE;
    }

    // ── Organic loot (ported; the SEEK leash is now the spot radius, not a flat 900px band) ──

    private MapItem nearestCollectableDrop(Character chr, int rangePx, int x0, int x1, int yLimit) {
        Point pos = chr.getPosition();
        if (pos == null) {
            return null;
        }
        double searchSq = (double) rangePx * rangePx;
        MapItem best = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : chr.getMap().getMapObjectsInRange(pos, searchSq, List.of(MapObjectType.ITEM))) {
            MapItem mi = (MapItem) mo;
            if (!DropCommands.botCanLoot(chr, mi)) {
                continue;
            }
            if (now() - mi.getDropTime() < LOOT_SETTLE_MS) {
                continue; // too fresh — let it land first; it'll be collected on a later tick
            }
            Point ip = mi.getPosition();
            if (ip.x < x0 || ip.x > x1 || Math.abs(ip.y - pos.y) > yLimit) {
                continue; // outside the spot's X leash, or on a stacked ledge above/below (unreachable)
            }
            double dsq = pos.distanceSq(ip);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = mi;
            }
        }
        return best;
    }

    // While planted and swinging, scoop a single drop sitting at the bot's feet (staggered).
    private void grabLootAtFeet(Character chr) {
        if (now() < lootNextMs) {
            return;
        }
        MapItem drop = nearestCollectableDrop(chr, LOOT_PICKUP_PX, Integer.MIN_VALUE, Integer.MAX_VALUE, LOOT_PICKUP_PX);
        if (drop != null) {
            DropCommands.botLootSingleDrop(chr, drop);
            lootNextMs = now() + lootGapMs();
            markProgress(); // collecting loot is productive, not wedged
            narrateLoot("scooping up loot at my feet");
        }
    }

    // Between kills, head to the nearest collectable drop WITHIN THE SPOT and pick it up once on top.
    // Returns true when there is loot to deal with this tick (so the caller skips chasing a mob).
    private boolean tryWalkAndLoot(Character chr) {
        if (spot == null) {
            return false;
        }
        int anchorX = spot.anchor().x;
        int x0 = anchorX - spot.radius();
        int x1 = anchorX + spot.radius();
        // loot leash = the spot's X band; search 2*radius so a drop on the far side of the spot from wherever
        // the bot currently stands is still found. Y leash keeps the bot on its own ledge (skip stacked drops).
        MapItem drop = nearestCollectableDrop(chr, 2 * spot.radius(), x0, x1, LOOT_SAME_LEDGE_Y);
        if (drop == null) {
            return false;
        }
        Point pos = chr.getPosition();
        Point dp = drop.getPosition();
        if (Math.abs(dp.x - pos.x) <= LOOT_PICKUP_PX && Math.abs(dp.y - pos.y) <= LOOT_PICKUP_PX) {
            if (now() >= lootNextMs) {
                GCMovement.stop(chr);
                DropCommands.botLootSingleDrop(chr, drop);
                lootNextMs = now() + lootGapMs();
                engaged = false;
                lastMoveTargetX = Integer.MIN_VALUE;
                markProgress(); // collecting loot is productive, not wedged
                narrateLoot("grabbed a nearby drop");
            }
            return true; // standing on the pile, pacing the pickups
        }
        engaged = false;
        int tx = clamp(dp.x, x0, x1);
        if (Math.abs(tx - lastMoveTargetX) >= ROAM_RETARGET_EPS) {
            Point gp = GCMovement.groundPointBelow(chr.getMap(), dp.x, dp.y);
            int ty = (gp != null) ? gp.y : dp.y;
            GCMovement.move(chr, tx, ty);
            lastMoveTargetX = tx;
            narrateLoot("walking over to grab a drop");
        }
        return true;
    }

    private long lootGapMs() {
        return LOOT_GAP_MIN_MS + (long) (rng.nextDouble() * (LOOT_GAP_MAX_MS - LOOT_GAP_MIN_MS));
    }

    // ── Helpers ──

    // Horizontal is the real leash; a generous Y tolerance covers a sloped / vertically-stacked anchor.
    private boolean within(Character chr, Point anchor, int radius) {
        Point p = (chr != null) ? chr.getPosition() : null;
        return p != null && Math.abs(p.x - anchor.x) <= radius && Math.abs(p.y - anchor.y) <= APPROACH_Y_TOLERANCE;
    }

    // Index by identity — pickBest returns an element OF the cached list, so this matches the spotId
    // BotSpotClaims/pickBest use, and is robust to two value-equal Spot records.
    private static int indexOfSame(List<Spot> spots, Spot s) {
        for (int i = 0; i < spots.size(); i++) {
            if (spots.get(i) == s) {
                return i;
            }
        }
        return spots.indexOf(s);
    }

    private void narrate(String msg) {
        if (!NARRATE) {
            return;
        }
        if (!msg.equals(lastNarrate)) {
            lastNarrate = msg;
            debug.accept(msg);
        }
    }

    private void narrateGiveUp() {
        if (!NARRATE) {
            return;
        }
        if (now() - lastGiveUpNarrateMs >= GIVE_UP_NARRATE_GAP_MS) {
            lastGiveUpNarrateMs = now();
            debug.accept("can't reach target mob in spot -> retargeting (traversal?)");
        }
    }

    // Throttled loot narration — pickups are frequent, so cap one line per LOOT_NARRATE_GAP_MS. Its
    // ABSENCE is itself a signal: a stationed ranged/magic bot that never closes on a kill never loots.
    private void narrateLoot(String msg) {
        if (!NARRATE) {
            return;
        }
        if (now() - lastLootNarrateMs >= LOOT_NARRATE_GAP_MS) {
            lastLootNarrateMs = now();
            debug.accept(msg);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
