package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import constants.game.ExpTable;
import constants.id.MapId;
import net.server.Server;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotSM;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapFinder;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat;
import static soloMapling.BotLogger.log;

// A roaming grinder. Spawns in a base town, travels out to a level-appropriate map with mobs, grinds
// for a session, returns to a town hub, and repeats — organically, forever. First production consumer
// of GCMovement (LOD-governed movement) and BotAttackDriver (combat).
//
// Two state machines, kept separate (the design's core split):
//  - Macro brain: updateState() on the slow BotSM tick (2-6s): INIT -> IN_TOWN -> DECIDE -> GO_TRAIN
//    -> GRIND -> GO_TOWN -> IN_TOWN ... Tier-agnostic; only issues movement intents and accrues abstract EXP.
//  - Combat tier: one shared 0.5s ticker (ensureCombatTicker) swings every grinding bot whose map is
//    observed (GCMovement.isMapObserved). The single shared task drives all bots' combat — no thread per bot.
//
// EXP: when unobserved (the common case) the bot levels by cheap arithmetic (kills/min x per-kill-exp),
// applied silently (no packets) via setExp/setLevel. When a real player is on its map, real kills (the
// combat ticker) feed EXP through the normal engine path. Bots are ephemeral decoration — no economy
// impact, wiped on restart.
//
// Map discovery is deterministic from WZ: the bot finds level-appropriate field maps near its spawn
// town by BFS over the portal graph (TrainingMapFinder + MapMobIndex), reading mob levels straight from
// Map.wz/Mob.wz. No hand-authored region table; town-locality emerges from hop distance.
public class TrainingBot extends BotSM {

    // ── Tunables (decoration, not balance — rough is fine) ───────────────────
    private static final long COMBAT_TICK_MS = 500;          // REAL-tier swing cadence (shared ticker)
    private static final double KILLS_PER_MIN = 30.0;        // abstract grind speed
    private static final long GRIND_MIN_MS = 600_000;        // a grind session lasts 10–20 min
    private static final long GRIND_MAX_MS = 1_200_000;
    // A bot that DIDN'T migrate to its first grind (warped in, or already on the map at the first decision) is
    // meant to look like it's been grinding a while, not just arrived — so its FIRST session is partially
    // elapsed (caught at a random point in a normal session), floored so it doesn't instantly turn around.
    private static final long MID_SESSION_FLOOR_MS = 90_000; // min remaining on a partially-elapsed first session
    // Travel watchdog: abandon a trip only after this long with NO progress at all — no hop completed AND no
    // movement on the current map. The deadline is pushed out every time the bot advances a map or moves, so
    // big maps and long multi-hop routes are never cut off for merely taking a while; only a genuinely wedged
    // trip (stuck in one spot, not changing maps) trips it, and onFail re-plans from here (never teleports).
    private static final long TRAVEL_TIMEOUT_MS = 120_000;
    private static final int TRAVEL_PROGRESS_EPS_PX = 24;    // min position change between ticks that counts as "moving"
    private static final long TOWN_DWELL_MIN_MS = 4_000;     // linger in town (settle) 4–10 s before deciding
    private static final long TOWN_DWELL_MAX_MS = 10_000;
    private static final double SHOP_VISIT_CHANCE = 0.4;     // chance a town visit includes a shop trip (timing variety)
    private static final long SHOP_DWELL_MIN_MS = 30_000;    // browse a store 30–90 s
    private static final long SHOP_DWELL_MAX_MS = 90_000;
    // Town map id -> {potion shop map, weapon/armor shop map}. A bot in a listed town may detour to one or
    // both stores (in either order) before heading out, for organic startup/return timing. GMS v83 store maps.
    private static final Map<Integer, int[]> TOWN_SHOPS = Map.of(
            100000000, new int[]{100000102, 100000101}, // Henesys
            101000000, new int[]{101000002, 101000001}, // Ellinia
            102000000, new int[]{102000002, 102000001}, // Perion
            103000000, new int[]{103000002, 103000001}, // Kerning City
            104000000, new int[]{104000002, 104000001}, // Lith Harbor
            200000000, new int[]{200000002, 200000001}, // Orbis
            220000000, new int[]{220000002, 220000001}, // Ludibrium
            211000000, new int[]{211000102, 211000101}  // El Nath (Department Store/potion, Weapon Store/equip — reached via El Nath Market hub)
    );
    // Sleepywood has no potion/equip stores; its town "errand" is a flavor trip to the hotel sauna instead.
    // The sauna entry is a non-portal NPC teleport (smoke-and-mirrors warp); the exit is a real one-way portal
    // back into the hotel. Only bots whose home town is Sleepywood ever run this (see doInTown / startSaunaTrip).
    private static final int SLEEPYWOOD_HOTEL = 105040400;   // hotel hub, reached by a portal from Sleepywood town
    private static final int SAUNA_REGULAR = 105040401;
    private static final int SAUNA_VIP = 105040402;
    private static final int SAUNA_DOOR_X = -19;             // the in-hotel "sauna door" spot (not a portal): walk here
    private static final int SAUNA_DOOR_Y = -208;            //   for the visual, then warp into the chosen sauna
    private static final double SAUNA_VIP_CHANCE = 0.40;     // 60% regular sauna / 40% VIP sauna
    private static final long DOOR_WALK_MAX_MS = 20_000;     // cap the door walk so a missed arrival can't stall the trip
    private static final long SAUNA_TRIP_TIMEOUT_MS = 300_000; // whole-trip watchdog → force-recover to town if it hangs
    private static final int LEVEL_BAND = 12;                // full-weight comfort band around the bot's level
    private static final int LEVEL_CAP = 195;                // guard (no real cap intended — see spec)
    // Map discovery: low-level bots hug their spawn town; the wander radius (hops from town) ramps fast
    // because Victoria's worthwhile maps sit a fair few hops out and 30+ is already "highish" there. Past
    // ANYWHERE_LEVEL the radius opens up to essentially the whole foot-connected landmass.
    private static final int[][] HOPS_BY_LEVEL = {           // {level, hops}, linearly interpolated between points
            {1, 1}, {15, 2}, {30, 4}, {50, 8}, {70, 10}
    };
    private static final int ANYWHERE_LEVEL = 70;            // 70+ : open the radius to the whole landmass
    private static final int MAX_HOPS = 20;                  // "anywhere" radius; the walkable BFS stops at the coast anyway
    private static final double LOW_MAP_WEIGHT = 0.15;       // pick-weight for easy maps below the band ("chilling")
    // Distance bias: within the reachable set, fresh bots favor maps close to town and higher bots favor
    // maps at the far edge of their reach, ramping to full "venture" by VENTURE_FULL_LEVEL.
    private static final int VENTURE_FULL_LEVEL = 50;        // level by which a bot fully prefers far maps
    private static final double DISTANCE_WEIGHT_BASE = 0.15; // floor so a non-preferred-distance map is still possible
    // First-trip warp: on the VERY FIRST decision to go train (mainly world startup), most bots teleport
    // straight to the picked grind map instead of walking there, so the world looks already-populated with
    // grinders rather than a town-emptying migration. One-time only (firstTrip) — every later trip travels
    // naturally. Gated on the town being unobserved so a watching player never sees a bot blink out.
    private static final double FIRST_TRIP_TELEPORT_CHANCE = 0.70;

    // ── Self-repair watchdog (macro tick) ────────────────────────────────────
    // If the bot is observed but lands no hit for this long it's genuinely stuck (wedged / boxed in / dead
    // map): teleport to a portal and re-grind; if that still doesn't help, leave the map. A landed hit (the
    // grind engine's combat heartbeat) resets the whole ladder. The roam/loot/climb tunables now live in GrindBrain.
    private static final long STUCK_TELEPORT_MS = 30_000;     // no landed hit this long (observed) → teleport to a portal, re-grind
    private static final long STUCK_BAIL_MS = 60_000;         // still no hit after a teleport → bail the map (re-DECIDE)
    private static final long REPAIR_COOLDOWN_MS = 12_000;    // min gap between repair actions, so each one gets a window to prove out

    // ── Map crowding balance (spread the cohort across maps, not just spots) ──────
    // A map's bot carrying capacity = its spawn-point budget / this (≈ one bot per N spawn points). Tuned so
    // "map at capacity" ≈ "every spot claimed". Selection (weightedPick) hard-caps at this; the crowd-bail
    // relieves a map that fills up after bots commit by sending the surplus a hop deeper.
    private static final int SPAWN_POINTS_PER_BOT = 5;        // carrying capacity divisor (calibrate vs !env grindprofile)
    private static final long MAP_SATURATED_DWELL_MS = 8_000; // saturation must persist this long before a crowd-bail (ride out arrival races)
    private static final long MAP_EXCLUDE_MS = 45_000;        // don't re-pick a map we just left for crowding
    private static final int MAX_MAP_HOPS_PER_EPISODE = 2;    // crowd-driven map changes before the bot settles & shares
    private static final int MAX_REDECIDES_PER_EPISODE = 2;   // capacity-reservation re-rolls before accepting an over-cap map

    // ── Ambient dialogue (context-token flavor; spoken only when observed, throttled) ──
    // Idle self-muttering only - reacting to nearby players is the movement driver's job
    // (BotPlayerReaction), so this stays deliberately sparse to avoid talkative spam.
    private static final long AMBIENT_MIN_MS = 120_000;      // min gap between idle grind lines (2 min)
    private static final long AMBIENT_MAX_MS = 240_000;      // max gap (4 min)
    // Self-buff cadence while grinding: re-show the bot's class buffs every 90-120s so a watched grinder
    // looks like it's keeping itself buffed (cosmetic - BotBuffDriver shows visuals only, no real stats).
    private static final long BUFF_MIN_MS = 90_000;
    private static final long BUFF_MAX_MS = 120_000;

    // ── Organic loot tunables (collect own + free-for-all drops naturally, not a vacuum) ──
    private static final int LOOT_SEEK_PX = 900;             // look this far for a drop worth walking to
    private static final int LOOT_PICKUP_PX = 60;            // close enough to grab (≈ vanilla "on top of it")
    private static final long LOOT_GAP_MIN_MS = 100;         // stagger between pickups so they don't all pop at once
    private static final long LOOT_GAP_MAX_MS = 250;

    // ── Shared combat ticker (one task for ALL training bots) ────────────────
    private static final Set<TrainingBot> ACTIVE_GRINDERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean combatTickerStarted = false;

    // Per-map count of training bots currently targeting/grinding each map, so map selection can prefer
    // less-crowded maps — spreading the population across the world, including the quiet noob maps.
    private static final Map<Integer, AtomicInteger> BOTS_PER_MAP = new ConcurrentHashMap<>();

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted) {
            return;
        }
        combatTickerStarted = true;
        ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
                TrainingBot::combatTickAll, COMBAT_TICK_MS, COMBAT_TICK_MS, TimeUnit.MILLISECONDS);
    }

    // Swing every grinding bot whose map a real player can see. One exception per bot never stops the ticker.
    private static void combatTickAll() {
        for (TrainingBot bot : ACTIVE_GRINDERS) {
            try {
                bot.combatTick();
            } catch (Exception e) {
                // a single bot's combat error must never kill the shared ticker
            }
        }
    }

    private void combatTick() {
        Character chr = getChr();
        if (chr == null || !getRunning() || phase != Phase.GRIND) {
            return; // gated; removal happens in leaveGrind()/stopScheduledTask()
        }
        grind.tick(chr); // observed → spot grind (FIGHT⇄WAIT); unobserved → no-op (the macro tick accrues abstract EXP)
    }

    // ── Macro brain state ────────────────────────────────────────────────────
    private enum Phase { INIT, IN_TOWN, SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN, DECIDE, GO_TRAIN, GRIND, GO_TOWN }

    private volatile Phase phase = Phase.INIT;
    private boolean phaseEntered = false;     // has this phase run its one-time setup?
    private long phaseDeadlineMs = 0;         // travel timeout / town dwell

    // Async travel coordination — the callback fires on the GCMovement driver thread.
    private volatile boolean moveDone = false;
    private volatile boolean moveOk = false;
    // Travel progress watchdog: the trip's deadline resets whenever the bot advances a hop or moves, so the
    // 2-min budget means "no progress for 2 min", not "the whole trip within 2 min".
    private int travelLastMapId = -1;
    private Point travelLastPos = null;

    private int homeMapId = -1;           // the spawn town; GO_TOWN returns here
    private boolean firstTrip = true;     // first decision to go train: maybe warp straight there (see FIRST_TRIP_TELEPORT_CHANCE)
    private boolean midSessionGrind = false; // next grind starts partially elapsed (warped/in-place first trip — see MID_SESSION_FLOOR_MS)
    private final List<Integer> shopQueue = new ArrayList<>(); // store maps still to visit this town stop
    private int shopTargetMapId = -1;     // the store currently being travelled to
    private int currentTrainMapId = -1;   // the discovered map currently being trained on
    private int currentMobLevel = 0;      // its representative mob level (drives abstract EXP)
    private long grindUntilMs = 0;
    private long lastExpAccrualMs = 0;
    private long nextChatterMs = 0;        // throttle gate for ambient grind chatter
    private long nextBuffMs = 0;           // re-buff gate while grinding (only advances after an actual buff)
    private int lastKnownLevel = -1;       // tracks level to detect a level-up worth announcing

    // Sleepywood sauna flavor trip — an async errand that runs off the macro FSM; the IN_TOWN tick parks on it.
    private volatile boolean saunaTripActive = false; // a sauna trip's async chain is in flight
    private volatile boolean saunaTripDone = false;   // chain finished → macro tick advances to DECIDE
    private volatile long saunaTripDeadlineMs = 0;    // whole-trip watchdog deadline

    // ── Grind engine + macro watchdog state ──
    // The per-bot localized grind sub-FSM (SELECT_SPOT→TRAVEL→FIGHT⇄WAIT→RELOCATE). TrainingBot keeps the
    // macro brain and delegates each observed combat tick to it; it owns the combat heartbeat the watchdog reads.
    private final GrindBrain grind = new GrindBrain(this::debugChat);
    private boolean teleportedThisEpisode = false; // macro watchdog: a portal-teleport has already been tried this stuck episode
    private long lastRepairMs = 0L;             // macro watchdog: last self-repair action (cooldown gate)

    // ── Map crowding state (macro tick only) ──
    // Maps this bot recently left because they were saturated (mapId -> cooldown expiry), so DECIDE steers
    // away from them for a beat; the per-outing crowd-hop counter so a fully-packed region settles into
    // sharing instead of migrating forever; and the persist timer for the saturation dwell.
    private final Map<Integer, Long> mapCrowdCooldown = new HashMap<>();
    private int crowdHopsThisEpisode = 0;
    private long mapSaturatedSinceMs = 0L;       // when the current grind first read saturated (0 = not saturated)

    private final Random rng = new Random();

    public TrainingBot(Character character) {
        super(character);
        botType = "TrainingBot";
        dialoguePath = "TrainingBotDialogue.yaml";
    }

    private void enterPhase(Phase next) {
        if (phase == Phase.SHOP_DWELL) {
            BotWanderSystem.stop(getChr()); // leaving a shop: end the flavor wander before travelling out
        }
        phase = next;
        phaseEntered = false;
        moveDone = false;
        moveOk = false;
        debugChat("phase -> " + next);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s TrainingBot phase: %s", chr.getName(), phase), String.format("%s", phase));

        switch (phase) {
            case INIT -> doInit();
            case IN_TOWN -> doInTown();
            case SHOP_TRAVEL -> doTravel(shopTargetMapId, Phase.SHOP_DWELL, Phase.SHOP_RETURN);
            case SHOP_DWELL -> doShopDwell();
            case SHOP_RETURN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.DECIDE, Phase.DECIDE);
            case DECIDE -> doDecide();
            case GO_TRAIN -> doTravel(currentTrainMapId, Phase.GRIND, Phase.DECIDE);
            case GRIND -> doGrind();
            case GO_TOWN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.IN_TOWN, Phase.IN_TOWN);
        }
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        ensureCombatTicker();
        homeMapId = getChr().getMapId();
        // No mobs here → it's a town: do the town beat first. Has mobs → a field: decide immediately.
        enterPhase(MapMobIndex.level(homeMapId) < 0 ? Phase.IN_TOWN : Phase.DECIDE);
    }

    // In a town hub: settle a beat, maybe plan a shop trip, then either head to the first store or decide
    // what to train on. Reached at spawn and after every grind, so shop trips add organic timing variety.
    private void doInTown() {
        // A Sleepywood sauna trip runs its own async chain off the FSM — park here while it's in flight (and
        // force-recover if it overruns). Gated by homeMapId below, so no other town ever enters this branch.
        if (saunaTripActive) {
            if (now() > saunaTripDeadlineMs) {
                abortSaunaTrip();
            }
            return;
        }
        if (saunaTripDone) {
            saunaTripDone = false;
            enterPhase(Phase.DECIDE); // trip finished — resume the grind loop
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            crowdHopsThisEpisode = 0; // back home -> fresh outing, reset the crowd-migration budget
            phaseDeadlineMs = now() + dwellMs();
            buildShopPlan();
            if (rng.nextInt(3) == 0) {
                BotEmote(getChr(), 1 + rng.nextInt(7)); // a little life in town
            }
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else if (homeMapId == MapId.SLEEPYWOOD && rng.nextDouble() < SHOP_VISIT_CHANCE) {
            startSaunaTrip(); // Sleepywood has no stores — its errand is a trip to the hotel sauna
        } else {
            enterPhase(Phase.DECIDE);
        }
    }

    // Decide this town stop's store itinerary: nothing, just potions, just equips, or both in either order.
    private void buildShopPlan() {
        shopQueue.clear();
        int[] shops = TOWN_SHOPS.get(homeMapId);
        if (shops == null || rng.nextDouble() > SHOP_VISIT_CHANCE) {
            return; // unknown town, or skipping the shops this visit
        }
        int potion = shops[0];
        int equip = shops[1];
        switch (rng.nextInt(4)) {
            case 0 -> shopQueue.add(potion);
            case 1 -> shopQueue.add(equip);
            case 2 -> { shopQueue.add(potion); shopQueue.add(equip); }
            default -> { shopQueue.add(equip); shopQueue.add(potion); }
        }
    }

    // Browse a store for a randomized beat, then move to the next store or head back out to train.
    private void doShopDwell() {
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + shopDwellMs();
            if (rng.nextInt(2) == 0) {
                BotEmote(getChr(), 1 + rng.nextInt(7));
            }
            sayContext("ShopRestock", getChr(), null);
            BotWanderSystem.start(getChr()); // browse the shop map (whole-map) instead of piling on the portal
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else {
            enterPhase(Phase.SHOP_RETURN);
        }
    }

    // ── Sleepywood flavor: hotel sauna trip (Sleepywood-home bots only) ────────
    // A self-contained async errand standing in for the shop trip Sleepywood can't offer (no potion/equip
    // stores). It runs entirely off the macro FSM via chained callbacks — no new Phase states: the IN_TOWN
    // tick parks on saunaTripActive while it runs and resumes at DECIDE when it finishes. Gated by
    // homeMapId == SLEEPYWOOD, so it can never leak into any other town's behavior.
    //   town -> hotel -> walk to the (non-portal) sauna door -> warp into a sauna -> wander -> exit -> town.
    private void startSaunaTrip() {
        Character chr = getChr();
        if (chr == null) {
            enterPhase(Phase.DECIDE);
            return;
        }
        saunaTripActive = true;
        saunaTripDone = false;
        saunaTripDeadlineMs = now() + SAUNA_TRIP_TIMEOUT_MS;
        debugChat("Sleepywood errand -> heading to the sauna");
        if (rng.nextInt(2) == 0) {
            BotEmote(chr, 1 + rng.nextInt(7)); // a beat of life before heading off
        }
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, ok -> enterSaunaFromHotel(chr));
    }

    // In the hotel: walk to the sauna door for the visual, then warp into a sauna (the NPC-script teleport is
    // pure smoke-and-mirrors server-side). The warp fires on arrival, or after a cap so a missed/abandoned
    // door-walk can't stall the trip — whichever comes first, exactly once.
    private void enterSaunaFromHotel(Character chr) {
        if (!saunaTripActive) {
            return; // aborted while travelling to the hotel
        }
        AtomicBoolean entered = new AtomicBoolean(false);
        Runnable enter = () -> {
            if (!saunaTripActive || !entered.compareAndSet(false, true)) {
                return; // already entered (door-arrival vs cap race), or the trip was aborted
            }
            int sauna = rng.nextDouble() < SAUNA_VIP_CHANCE ? SAUNA_VIP : SAUNA_REGULAR;
            chr.changeMap(sauna);
            BotWanderSystem.start(chr); // relax in the sauna for a beat
            ExecutorServiceManager.getScheduledExecutorService().schedule(
                    () -> leaveSauna(chr), shopDwellMs(), TimeUnit.MILLISECONDS);
        };
        GCMovement.move(chr, SAUNA_DOOR_X, SAUNA_DOOR_Y, enter);
        ExecutorServiceManager.getScheduledExecutorService().schedule(enter, DOOR_WALK_MAX_MS, TimeUnit.MILLISECONDS);
    }

    // Done relaxing: the sauna's one-way exit portal drops the bot back into the hotel; from there take the
    // hotel's portal back down to Sleepywood town. Then hand control back to the macro tick (-> DECIDE).
    private void leaveSauna(Character chr) {
        if (!saunaTripActive) {
            return;
        }
        BotWanderSystem.stop(chr);
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, okHotel ->
                GCMovement.travel(chr, homeMapId, okTown -> finishSaunaTrip()));
    }

    private void finishSaunaTrip() {
        if (!saunaTripActive) {
            return; // already aborted/finished — don't re-arm the handoff
        }
        saunaTripActive = false;
        saunaTripDone = true; // doInTown advances to DECIDE on the macro thread
    }

    // Trip watchdog (macro thread): the chained callbacks can silently drop on an unreachable hop, so if the
    // whole errand overruns, cut it and hard-recover to town rather than leaving the bot parked forever.
    private void abortSaunaTrip() {
        Character chr = getChr();
        debugChat("sauna trip overran -> force-recover to town");
        saunaTripActive = false; // disarm any in-flight callbacks first
        if (chr != null) {
            BotWanderSystem.stop(chr);
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            if (homeMapId >= 0 && chr.getMapId() != homeMapId) {
                chr.changeMap(homeMapId);
            }
        }
        saunaTripDone = true;
    }

    // Discover a field map near the current town (WZ-driven): level-scaled wander radius, weighted
    // toward level-appropriate + less-crowded maps, with a chance to chill at an easier map.
    private void doDecide() {
        Character chr = getChr();
        int level = chr.getLevel();
        int reach = hopsForLevel(level);
        Set<Integer> excluded = crowdExcludedMaps(); // maps on crowd-cooldown (pruned) — steer away from them

        // Pick a level-appropriate, uncrowded map and RESERVE a slot on it. weightedPick hard-caps maps at
        // capacity, so the pick is already biased to maps with headroom (and, transitively, to deeper hops
        // once the near maps fill). The reservation loop closes the cohort race: if our atomic increment
        // pushed the map OVER capacity (another bot grabbed the last slot at the same instant), release and
        // re-pick — the bumped count now hard-zeros that map, steering us deeper.
        TrainingMap pick = null;
        for (int attempt = 0; attempt <= MAX_REDECIDES_PER_EPISODE; attempt++) {
            List<TrainingMap> eligible = TrainingMapFinder.findTrainingMaps(
                    chr.getMapId(), level, LEVEL_BAND, reach, excluded);
            if (eligible.isEmpty()) {
                // Nothing reachable with mobs (or all on cooldown) — idle in town and try again later.
                debugChat("DECIDE: nothing reachable (lv " + level + ", reach " + reach + ") -> idle in town");
                enterPhase(Phase.IN_TOWN);
                return;
            }
            TrainingMap candidate = weightedPick(eligible, level, reach);
            setTrainTarget(candidate.mapId(), candidate.mobLevel()); // reserve a slot (atomic increment)
            if (botsOnMap(candidate.mapId()) <= capacityFor(candidate.mapId())
                    || attempt == MAX_REDECIDES_PER_EPISODE) {
                pick = candidate; // within capacity, or out of re-rolls -> accept (saturated region: share)
                break;
            }
            clearTrainTarget(); // raced over capacity -> drop the reservation and re-pick deeper
            debugChat("DECIDE: map " + candidate.mapId() + " over cap, re-picking");
        }
        debugChat("DECIDE: chose map " + pick.mapId() + " (mob lv " + pick.mobLevel() + ")");
        // First trip only (mainly startup): most bots warp straight to the grind map instead of walking, so
        // the world reads as already-populated. Skipped when the town is observed (don't blink out in front of
        // a player) or already on the picked map. doGrind's entry then runs the normal grind setup. Consumed
        // either way, so every subsequent trip travels naturally.
        boolean wasFirstTrip = firstTrip;
        firstTrip = false;
        boolean migrates = chr.getMapId() != pick.mapId();
        if (wasFirstTrip && migrates
                && !GCMovement.isMapObserved(chr.getMapId())
                && rng.nextDouble() < FIRST_TRIP_TELEPORT_CHANCE) {
            debugChat("DECIDE: first-trip warp -> map " + pick.mapId());
            midSessionGrind = true; // warped in -> reads as already-grinding -> shortened first session
            GCMovement.stop(chr); // clear any stale town move intent before the warp
            chr.changeMap(pick.mapId());
            enterPhase(Phase.GRIND);
            return;
        }
        if (wasFirstTrip && !migrates) {
            midSessionGrind = true; // already on the grind map (no visible travel) -> also a mid-session start
        }
        // First trip with a real cross-map walk (the ~30% that don't warp): we watch it travel, so it reads as
        // freshly heading out -> a full session is correct. midSessionGrind stays false.
        enterPhase(Phase.GO_TRAIN);
    }

    // Issue a one-shot travel to destMapId and advance on the async result. Already on the map -> straight
    // to onArrive. Timed out / failed -> onFail. Non-blocking: re-polls each macro tick while the trip
    // is in flight.
    private void doTravel(int destMapId, Phase onArrive, Phase onFail) {
        Character chr = getChr();
        if (destMapId < 0) {
            enterPhase(onFail);
            return;
        }
        if (chr.getMapId() == destMapId) {
            enterPhase(onArrive);
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
            travelLastMapId = chr.getMapId();
            travelLastPos = chr.getPosition();
            debugChat("travel -> map " + destMapId);
            GCMovement.travel(chr, destMapId, ok -> {
                moveOk = (ok != null && ok);
                moveDone = true;
            });
            return;
        }
        if (moveDone) {
            if (!moveOk) {
                debugChat("travel reported FAIL -> " + onFail);
            }
            enterPhase(moveOk ? onArrive : onFail);
            return;
        }
        // Progress-aware watchdog: while the bot is advancing maps OR moving across the current one, it's
        // travelling fine (a big map / long route is expected) — keep pushing the deadline out. Only a trip
        // that makes no progress at all for the whole window is wedged; abandon it and re-plan (no teleport).
        if (madeTravelProgress(chr)) {
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
        } else if (now() > phaseDeadlineMs) {
            debugChat("travel STUCK: no progress for " + (TRAVEL_TIMEOUT_MS / 1000) + "s -> " + onFail);
            GCMovement.cancelTravel(chr);
            enterPhase(onFail);
        }
        // else still travelling — wait for the next tick
    }

    // True if the bot advanced a hop (its map changed) or moved on its current map since the last check. The
    // position anchor only advances when movement clears the epsilon, so slow steady travel still accumulates
    // into progress across ticks instead of reading as stuck.
    private boolean madeTravelProgress(Character chr) {
        int curMap = chr.getMapId();
        if (curMap != travelLastMapId) {
            travelLastMapId = curMap;
            travelLastPos = chr.getPosition(); // hop completed — unambiguous progress; re-anchor on the new map
            return true;
        }
        Point pos = chr.getPosition();
        if (pos != null && (travelLastPos == null
                || Math.abs(pos.x - travelLastPos.x) + Math.abs(pos.y - travelLastPos.y) > TRAVEL_PROGRESS_EPS_PX)) {
            travelLastPos = pos;
            return true;
        }
        return false;
    }

    // At a training map: anchor on a platform, swing (when observed) via the shared ticker, accrue EXP.
    private void doGrind() {
        Character chr = getChr();
        if (!phaseEntered) {
            phaseEntered = true;
            teleportedThisEpisode = false;
            GCMovement.setGrinding(chr, true); // engage grind nav guards (no idle-hang on ropes)
            grind.start(chr); // fresh heartbeat, select a spot, and move into it (disperses cohorts even unobserved)
            grindUntilMs = now() + grindSessionMs();
            lastExpAccrualMs = now();
            nextBuffMs = now(); // buff up as soon as a player can see it (looks already-buffed on arrival)
            ACTIVE_GRINDERS.add(this);
            lastKnownLevel = chr.getLevel();
            sayContext("GrindStart", chr, null);
            debugChat("GRIND on map " + chr.getMapId() + " " + grind.spotLabel());
            return;
        }

        // Abstract EXP only when unobserved (observed time is covered by real kills via the ticker).
        long nowMs = now();
        double elapsedSec = (nowMs - lastExpAccrualMs) / 1000.0;
        lastExpAccrualMs = nowMs;
        if (elapsedSec > 0 && !GCMovement.isMapObserved(chr.getMapId()) && currentMobLevel > 0) {
            accrueAbstractExp(currentMobLevel, elapsedSec);
        }

        // Announce a level-up the moment it becomes visible (real kills on an observed map).
        int lvl = chr.getLevel();
        if (lastKnownLevel >= 0 && lvl > lastKnownLevel) {
            sayContext("LevelUp", chr, null);
        }
        lastKnownLevel = lvl;

        maybeGrindChatter(chr);
        maybeSelfBuff(chr);

        if (grindWatchdog(chr)) {
            return; // self-repair left the map (re-DECIDE) — skip the normal grind-timer transition
        }

        if (grindCrowdBail(chr)) {
            return; // map too crowded — left for a deeper one (re-DECIDE); skip the grind-timer transition
        }

        if (nowMs >= grindUntilMs) {
            sayContext("MapTransition", chr, null); // "this spot's dead, moving on" — said while still on the map
            leaveGrind();
            enterPhase(Phase.GO_TOWN);
        }
    }

    // Macro self-repair watchdog (runs on the slow tick, observed only). The combat ticker keeps the
    // heartbeat (lastCombatProgressMs = last landed hit). If that goes stale the bot isn't engaging anything:
    //   ~30s + a reachable mob nearby → wedged/bad-pathing → teleport to the nearest portal and re-grind.
    //   ~30s + NO reachable mob, or ~60s after a teleport that didn't help → this map's a dead end → re-DECIDE.
    // A landed hit (heartbeat refresh) drops it back below the threshold and resets the ladder. Returns true
    // only when it changed phase (left the map), so doGrind skips its own grind-timer transition that tick.
    private boolean grindWatchdog(Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // unobserved bots don't fight (abstract EXP) — they can't be physically stuck
        }
        long stuck = grind.msSinceProgress();
        if (stuck < STUCK_TELEPORT_MS) {
            teleportedThisEpisode = false; // making (or recently made) progress → reset the ladder
            return false;
        }
        if (now() - lastRepairMs < REPAIR_COOLDOWN_MS) {
            return false; // let the previous repair take effect before escalating
        }
        boolean mobReachable = isReachableHostileNearby(chr);
        boolean recovering = grind.isRecovering(chr);
        if (mobReachable && !teleportedThisEpisode && !recovering) {
            // A mob is reachable but we still can't land a hit (wedged / pathing failure) → reset position.
            debugChat("watchdog: no hit for " + (stuck / 1000) + "s, mob reachable -> teleport to portal & re-grind");
            teleportToNearestPortal(chr);
            grind.resetupAfterTeleport(chr); // fresh kill window + re-select a spot from the new position
            teleportedThisEpisode = true;
            lastRepairMs = now();
            return false;
        }
        if (!mobReachable || stuck >= STUCK_BAIL_MS) {
            // Nothing to fight here, or a teleport already failed to help → leave and pick a new map.
            debugChat("watchdog: stuck " + (stuck / 1000) + "s (" + (mobReachable ? "teleport didn't help" : "no reachable mob") + ") -> bail map, re-decide");
            leaveGrind();
            enterPhase(Phase.DECIDE);
            return true;
        }
        return false;
    }

    // Crowd-balance escalation (macro tick, observed only). The grind sub-FSM reports the map saturated when
    // every reachable spot is claimed and the bot is sharing; if that persists past the dwell, leave for a
    // deeper, less-crowded map — capacity-aware DECIDE spills us outward. Three guards stop thrash: a persist
    // timer (ignore arrival-race blips), a per-map cooldown (no instant return), and a per-outing hop cap (a
    // fully packed region settles into sharing instead of migrating forever). Returns true if it left the map.
    private boolean grindCrowdBail(Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // unobserved bots aren't physically grinding (abstract EXP) — nothing for a player to see
        }
        if (!grind.mapSaturated() || crowdHopsThisEpisode >= MAX_MAP_HOPS_PER_EPISODE) {
            mapSaturatedSinceMs = 0L; // not saturated, or out of hops -> reset the persist timer
            return false;
        }
        if (mapSaturatedSinceMs == 0L) {
            mapSaturatedSinceMs = now(); // first saturated tick -> start the dwell
            return false;
        }
        if (now() - mapSaturatedSinceMs < MAP_SATURATED_DWELL_MS) {
            return false; // saturation must persist (ride out a cohort-arrival race before bailing)
        }
        int leaving = chr.getMapId();
        debugChat("crowd: map " + leaving + " saturated -> bail to a deeper map");
        mapCrowdCooldown.put(leaving, now() + MAP_EXCLUDE_MS);
        crowdHopsThisEpisode++;
        mapSaturatedSinceMs = 0L;
        leaveGrind();
        enterPhase(Phase.DECIDE);
        return true;
    }

    // Is there a hostile mob the bot could actually walk/jump to from where it stands? Reachability-filtered
    // (a mob stranded on a platform the bot can't get to doesn't count) using the same foothold-snap the
    // approach uses, so a jumping mob still resolves to its real platform.
    private boolean isReachableHostileNearby(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return false;
        }
        Set<Integer> reach = GCMovement.reachableRegions(map, pos.x, pos.y);
        if (reach.isEmpty()) {
            return false;
        }
        for (Monster m : map.getAllMonsters()) {
            if (!SpotFinder.isHostile(m) || m.getPosition() == null) {
                continue;
            }
            Point g = GCMovement.groundPointBelow(map, m.getPosition().x, m.getPosition().y);
            if (g != null && reach.contains(GCMovement.regionIdAt(map, g.x, g.y))) {
                return true;
            }
        }
        return false;
    }

    // Hard-teleport to the nearest portal on the current map — a guaranteed-valid foothold — to break a
    // geometry wedge. The caller re-runs setupGrind from the new spot.
    private void teleportToNearestPortal(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return;
        }
        Point best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal p : map.getPortals()) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            double dsq = pos.distanceSq(pp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = pp;
            }
        }
        if (best != null) {
            GCMovement.stop(chr); // drop the in-flight move so the driver re-acquires cleanly from the new spot
            GCMovement.teleportTo(chr, best.x, best.y);
        }
    }

    private void leaveGrind() {
        ACTIVE_GRINDERS.remove(this);
        grind.release(getChr()); // drop the spot claim + reset combat state
        clearTrainTarget(); // release this map's occupancy slot
        GCMovement.setGrinding(getChr(), false); // back to normal nav for travel
        GCMovement.stop(getChr()); // clear the roam target before travelling
    }

    // ── Ambient dialogue (context-aware flavor) ───────────────────────────────

    // Occasionally mutters to itself while grinding (GrindAmbient). Reacting to nearby players is handled
    // by the movement driver's BotPlayerReaction layer, so this is idle-only and rarely fires. Throttled
    // by nextChatterMs (2-4 min); only speaks when a player is observing the map.
    private void maybeGrindChatter(Character chr) {
        long t = now();
        if (t < nextChatterMs) {
            return;
        }
        nextChatterMs = t + AMBIENT_MIN_MS + (long) (rng.nextDouble() * (AMBIENT_MAX_MS - AMBIENT_MIN_MS));
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // gate advanced so we don't re-roll every tick; nothing worth saying unobserved
        }
        sayContext("GrindAmbient", chr, null);
    }

    // Re-show the bot's class buffs every 90-120s while grinding. Only fires when a player can see it and
    // the bot is grounded (no buffing from a rope); the timer advances only on an actual buff, so an
    // unobserved bot buffs the instant it's first observed and then keeps to the cadence. forceBuff
    // ignores BotBuffDriver's own duration timers so the visible re-buff stays on this fixed cadence.
    private void maybeSelfBuff(Character chr) {
        if (now() < nextBuffMs) {
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // hold the timer until it's worth showing.
        }
        BotBuffDriver.forceBuff(chr);
        nextBuffMs = now() + BUFF_MIN_MS + (long) (rng.nextDouble() * (BUFF_MAX_MS - BUFF_MIN_MS));
    }

    // Fires a context-token dialogue node, but only when a real player can see it (observed map), and
    // off the macro thread so the line's hold never blocks the tick. Biased toward plain lines over
    // {TOKEN} ones (CONTEXT_LINE_CHANCE). player may be null (self-only nodes).
    private void sayContext(String node, Character chr, Character player) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        ExecutorServiceManager.runAsync(() ->
                getDialogueHandler().executeBotContextDialogue(node, this, player, BotDialogueHandler.CONTEXT_LINE_CHANCE));
    }

    // Debug narration (OFF by default). When enabled, the bot announces what it's doing — every phase change
    // and the notable fail/repair branches — as normal chat (speech bubble + chat box) so you can watch the
    // macro FSM live. The switch is a PLAIN LOCAL below, deliberately NOT static/final, so you can flip it to
    // true and HotSwap just this one method while the server is running (no restart; a static/final would be
    // constant-folded at the call sites or keep its old value across a reload).
    private void debugChat(String message) {
        boolean debugChatEnabled = false; // <-- flip to true + HotSwap this method to turn narration on
        if (!debugChatEnabled) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        BotFullChat(chr, message); // general chat: bubble above the head AND a line in the chat box
    }

    // Silent, level-scaled accrual: kills/min x per-kill-exp, where per-kill-exp ~ the map's mob level.
    private void accrueAbstractExp(int mobLevel, double elapsedSec) {
        int perKillExp = Math.max(1, mobLevel);
        int gain = (int) Math.round((KILLS_PER_MIN / 60.0) * perKillExp * elapsedSec);
        if (gain <= 0) {
            return;
        }
        Character chr = getChr();
        long exp = (long) chr.getExp() + gain;
        int level = chr.getLevel();
        while (level < LEVEL_CAP) {
            int need = ExpTable.getExpNeededForLevel(level);
            if (need <= 0 || exp < need) {
                break;
            }
            exp -= need;
            level++;
        }
        chr.setLevel(level);
        chr.setExp((int) Math.min(exp, Integer.MAX_VALUE));
    }

    // ── Map selection (level-scaled hops + weighted, occupancy-balanced, chill-aware pick) ──

    // Wander radius (hops from town) for a level, interpolating HOPS_BY_LEVEL between its breakpoints.
    // ANYWHERE_LEVEL and up opens the radius to MAX_HOPS (the whole connected landmass). Low bots hug
    // town, 30+ bots range widely (Victoria's good maps are far out), 70+ can go essentially anywhere.
    private static int hopsForLevel(int level) {
        if (level >= ANYWHERE_LEVEL) {
            return MAX_HOPS;
        }
        int[][] bp = HOPS_BY_LEVEL;
        if (level <= bp[0][0]) {
            return bp[0][1];
        }
        for (int i = 1; i < bp.length; i++) {
            if (level <= bp[i][0]) {
                int loL = bp[i - 1][0], hiL = bp[i][0];
                int loH = bp[i - 1][1], hiH = bp[i][1];
                return (int) Math.round(loH + (hiH - loH) * (double) (level - loL) / (hiL - loL));
            }
        }
        return MAX_HOPS; // above the top breakpoint but below ANYWHERE_LEVEL — safety only
    }

    // Weighted random over eligible maps: level-appropriate maps full weight, easier maps a reduced chance
    // (so higher bots sometimes "chill" at low maps), biased by distance (low bots near town, high bots
    // further out), and CAPACITY-AWARE — a map at/over its carrying capacity is hard-zeroed so it drops out
    // of contention and selection spills to deeper, less-crowded maps; the rest are weighted by remaining
    // headroom. If every reachable map is full, falls back to the old soft divisor so the bot still goes
    // somewhere (and shares) rather than idling.
    private TrainingMap weightedPick(List<TrainingMap> maps, int level, int reach) {
        double[] weights = new double[maps.size()];
        double total = 0;
        for (int i = 0; i < maps.size(); i++) {
            TrainingMap m = maps.get(i);
            int cap = capacityFor(m.mapId());
            int n = botsOnMap(m.mapId());
            double base = levelFitWeight(level, m.mobLevel()) * distanceWeight(level, m.hops(), reach);
            double w = (n >= cap) ? 0.0 : base * ((cap - n) / (double) cap); // hard cap + headroom weight
            weights[i] = w;
            total += w;
        }
        if (total <= 0) {
            // Every reachable map is at capacity — re-weight with the soft divisor so the surplus still
            // spreads onto the least-crowded map instead of idling in town.
            for (int i = 0; i < maps.size(); i++) {
                TrainingMap m = maps.get(i);
                double w = levelFitWeight(level, m.mobLevel())
                        * distanceWeight(level, m.hops(), reach)
                        / (1.0 + botsOnMap(m.mapId()));
                weights[i] = w;
                total += w;
            }
        }
        if (total <= 0) {
            return maps.get(rng.nextInt(maps.size()));
        }
        double r = rng.nextDouble() * total;
        for (int i = 0; i < maps.size(); i++) {
            r -= weights[i];
            if (r <= 0) {
                return maps.get(i);
            }
        }
        return maps.get(maps.size() - 1);
    }

    // Full weight within the comfort band (>= level - LEVEL_BAND); a small chance for easier maps.
    private static double levelFitWeight(int level, int mobLevel) {
        return (mobLevel >= level - LEVEL_BAND) ? 1.0 : LOW_MAP_WEIGHT;
    }

    // Distance preference, ramped by level. reach = the bot's hop radius (hopsForLevel); hops = this
    // map's BFS distance from the spawn town (1 = adjacent). A fresh bot favors close maps; the
    // preference slides toward the far edge of its reach as it approaches VENTURE_FULL_LEVEL. With only
    // adjacent maps reachable there's nothing to bias, so weight is flat.
    private static double distanceWeight(int level, int hops, int reach) {
        if (reach <= 1) {
            return 1.0;
        }
        double venture = Math.min(1.0, level / (double) VENTURE_FULL_LEVEL);
        double hopFrac = (hops - 1) / (double) (reach - 1); // 0 = nearest, 1 = edge of reach
        hopFrac = Math.max(0.0, Math.min(1.0, hopFrac));
        double pref = (1.0 - venture) * (1.0 - hopFrac) + venture * hopFrac;
        return DISTANCE_WEIGHT_BASE + pref;
    }

    private static int botsOnMap(int mapId) {
        AtomicInteger c = BOTS_PER_MAP.get(mapId);
        return c == null ? 0 : Math.max(0, c.get());
    }

    // A map's bot carrying capacity from its spawn-point budget. MapMobIndex.mobCount is the WZ `life`
    // type-m count (each a respawning spawn point), read once and cached — no live map load, no nav bake,
    // and DECIDE already touches MapMobIndex per candidate so this is effectively free. Floored at 1 so a
    // sparse map still hosts one grinder. Tuned (SPAWN_POINTS_PER_BOT) so "at capacity" ≈ "all spots claimed".
    private static int capacityFor(int mapId) {
        int spawns = MapMobIndex.mobCount(mapId);
        return Math.max(1, (int) Math.round(spawns / (double) SPAWN_POINTS_PER_BOT));
    }

    // Maps recently left because they were saturated, with expired entries pruned. Fed to findTrainingMaps
    // so a just-left packed map isn't immediately re-picked (map-scale analogue of the spot exclude cooldown).
    private Set<Integer> crowdExcludedMaps() {
        long t = now();
        mapCrowdCooldown.values().removeIf(until -> until <= t);
        return new HashSet<>(mapCrowdCooldown.keySet());
    }

    private void setTrainTarget(int mapId, int mobLevel) {
        clearTrainTarget();
        currentTrainMapId = mapId;
        currentMobLevel = mobLevel;
        BOTS_PER_MAP.computeIfAbsent(mapId, k -> new AtomicInteger()).incrementAndGet();
    }

    private void clearTrainTarget() {
        if (currentTrainMapId >= 0) {
            AtomicInteger c = BOTS_PER_MAP.get(currentTrainMapId);
            if (c != null) {
                c.decrementAndGet();
            }
        }
        currentTrainMapId = -1;
        currentMobLevel = 0;
    }

    private long dwellMs() {
        return TOWN_DWELL_MIN_MS + (long) (rng.nextDouble() * (TOWN_DWELL_MAX_MS - TOWN_DWELL_MIN_MS));
    }

    // Length of the grind session about to start. Normally a fresh 10–20 min. But a bot flagged
    // midSessionGrind (warped in, or already on the map at its first decision) is meant to look like it's
    // been grinding a while: caught at a random point inside a normal session, floored so a just-arrived bot
    // doesn't instantly turn around. One-time — the flag is consumed here, so every later session is full.
    private long grindSessionMs() {
        long full = GRIND_MIN_MS + (long) (rng.nextDouble() * (GRIND_MAX_MS - GRIND_MIN_MS));
        if (midSessionGrind) {
            midSessionGrind = false;
            return Math.max(MID_SESSION_FLOOR_MS, (long) (rng.nextDouble() * full));
        }
        return full;
    }

    private long shopDwellMs() {
        return SHOP_DWELL_MIN_MS + (long) (rng.nextDouble() * (SHOP_DWELL_MAX_MS - SHOP_DWELL_MIN_MS));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ── Diagnostics: aggregate LOD/training overview (backs !gcmove lod train) ──

    // A snapshot for a GM: how many active training bots sit in each LOD tier (full/halo/dwell/coarse) and
    // each macro phase, plus the maps currently FULL and HALO with names, so it's quick to verify the right
    // maps are being observed/trained. Aggregate counts only (per-bot listing would be too noisy at scale).
    public static List<String> lodDiagnostic() {
        List<String> out = new ArrayList<>();

        // Name occupied maps without MapFactory plumbing: index every online character's live map once.
        Map<Integer, MapleMap> mapsById = new HashMap<>();
        Server server = Server.getInstance();
        if (server != null) {
            for (World w : server.getWorlds()) {
                if (w == null) {
                    continue;
                }
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c != null && c.getMap() != null) {
                        mapsById.putIfAbsent(c.getMapId(), c.getMap());
                    }
                }
            }
        }

        int total = 0, full = 0, halo = 0, dwell = 0, coarse = 0;
        int pInit = 0, pTown = 0, pShop = 0, pDecide = 0, pTrain = 0, pGrind = 0, pGoTown = 0;
        Map<Integer, Integer> trainByMap = new TreeMap<>();
        for (BotSM b : CharacterStorage.getAllBots().values()) {
            if (!(b instanceof TrainingBot tb)) {
                continue;
            }
            Character chr = tb.getChr();
            if (chr == null) {
                continue;
            }
            total++;
            int mapId = chr.getMapId();
            trainByMap.merge(mapId, 1, Integer::sum);
            switch (GCMovement.lodTier(mapId)) {
                case "full" -> full++;
                case "halo" -> halo++;
                case "dwell" -> dwell++;
                default -> coarse++;
            }
            switch (tb.phase) {
                case INIT -> pInit++;
                case IN_TOWN -> pTown++;
                case SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN -> pShop++;
                case DECIDE -> pDecide++;
                case GO_TRAIN -> pTrain++;
                case GRIND -> pGrind++;
                case GO_TOWN -> pGoTown++;
            }
        }

        out.add("=== TrainingBot LOD overview ===");
        out.add(String.format("training bots: %d   tiers: full=%d halo=%d dwell=%d coarse=%d",
                total, full, halo, dwell, coarse));
        out.add(String.format("phases: grind=%d goTrain=%d goTown=%d inTown=%d shop=%d decide=%d init=%d",
                pGrind, pTrain, pGoTown, pTown, pShop, pDecide, pInit));
        out.add(String.format("-- FULL maps (real player present): %d --", GCMovement.observedFullMaps().size()));
        appendMapLines(out, GCMovement.observedFullMaps(), mapsById, trainByMap);
        out.add(String.format("-- HALO maps (portal-adjacent): %d --", GCMovement.observedHaloMaps().size()));
        appendMapLines(out, GCMovement.observedHaloMaps(), mapsById, trainByMap);
        return out;
    }

    private static void appendMapLines(List<String> out, Set<Integer> mapIds,
                                       Map<Integer, MapleMap> mapsById, Map<Integer, Integer> trainByMap) {
        if (mapIds.isEmpty()) {
            out.add("  (none)");
            return;
        }
        for (int mapId : new TreeSet<>(mapIds)) {
            MapleMap m = mapsById.get(mapId);
            String name = (m != null) ? (m.getStreetName() + " - " + m.getMapName()) : "?";
            int bots = trainByMap.getOrDefault(mapId, 0);
            out.add(String.format("  %d  %s  [%d training bot(s)]", mapId, name, bots));
        }
    }

    // Release combat + movement when the bot stops (FINISHED / converted / manually stopped).
    @Override
    public synchronized void stopScheduledTask() {
        ACTIVE_GRINDERS.remove(this);
        Character chr = getChr();
        if (chr != null) {
            grind.release(chr);
            clearTrainTarget(); // release this map's occupancy slot
            BotAttackDriver.clearBot(chr.getId());
            BotBuffDriver.clearBot(chr.getId());
            BotWanderSystem.stop(chr); // end any shop-dwell flavor wander
            GCMovement.disable(chr); // releases the shared movement lock the recorded engine needs
        }
        super.stopScheduledTask();
        log("[TrainingBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }
}
