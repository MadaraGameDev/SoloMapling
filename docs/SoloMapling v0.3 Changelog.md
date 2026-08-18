# SoloMapling v0.3 — Changelog

**Release date:** 2026-07-08
**Baseline:** end of `feature/training-bot-refinement` (commit `50e751e6`, 2026-06-28) — the previous major-version line.
**This release:** merged into `main` at `84a75d91`.
**Scope of the diff:** 128 commits · 156 files · **+17,602 / −2,484** lines under `soloMapling/`.

> **The one-line story.** v0.2 could put *hundreds* of bots in a town. v0.3 puts **thousands** — a
> handheld Legion Go was scale-tested at **6,571 bots** — and it did that *while* the bots got
> smarter: fluid class-aware combat, a grind AI that reads the map it's on, and living towns across
> all seven major cities instead of just Henesys.

This was the "Fable" development arc — a run of design audits (authored on Claude Fable 5) followed
by implementation passes (Claude Opus 4.8). The audits themselves are the source of truth and live
in `Documents/Fable/`; this changelog is the high-level summary of what shipped.

---

## At a glance

| Area | v0.2 (training-bot-refinement) | v0.3 |
|---|---|---|
| **Concurrency** | one OS thread *per bot* (~2,040 threads) | one shared **tick wheel**, virtual-thread dispatch (**~137–164 threads**) |
| **Proven population** | ~1,900 bots | **6,571 bots** on a Legion Go, headroom to spare |
| **Combat** | grounded swings, teleport-arc fakery | real **flash-jump / teleport** locomotion, jump-attacks, mid-air kiting, class-aware speed/jump |
| **Grinding** | one grind model (localized spot camping) | **four map-archetype strategies** (Camp / Roam / Patrol / Stack) + capacity-aware spot distribution |
| **Social world** | Henesys-only ambience | **all 7 towns** alive — conversations, ambient barks, loiter/stations, level-up reactions |
| **Party play** | OPQ only | player-recruitable bots → **FollowerBot** that grinds with you |
| **Bot types** | 15 | **18** production personalities (+ TownWandererBot, FollowerBot) |
| **Observability** | `!gcmove lod` snapshots | full health readout via **`!env perf`** (threads, heap, ticks/s, wheel lag, governor, combat sweep) |

---

## 1. Massive scaling — the central tick wheel (the marquee change)

The headline of v0.3. Every bot used to own a dedicated OS platform thread for its macro FSM
(`Executors.newScheduledThreadPool(1)` per bot). At ~1,900 bots that was ~1,900 threads and ~1.9 GB
of reserved stack — the hard scaling wall.

v0.3 replaces all of it with **`soloMapling/server/BotTickService.java`**: a single ~100 ms driver
task that pops all due bots from a min-heap and dispatches each `updateState()` onto a **virtual
thread**. No bot owns a thread anymore.

What that unlocked:

- **Observation-tiered cadence.** `ObserverTracker` is now the single source of "who can see this
  bot," computed at 1 Hz from real players. Ticks run ~2–6 s jittered when observed, ~9–12 s
  unobserved, and deeper still for background work (a grinding TrainingBot idles at 60–120 s when
  nobody's watching). A map-entry nudge pulls the next tick forward the instant a player walks in —
  the world "wakes up" within ~1 s.
- **Gate packets, never simulation.** Idle-refresh and movement broadcasts are gated on
  observation; empty maps no longer build-and-broadcast idle packets to nobody (~190/s → ~0).
- **An elastic governor.** A feedback loop inside the wheel measures average dispatch lag and
  stretches background cadences (×1.5…×4) if it stays high, relaxing on recovery. A safety net that,
  at 6.5k bots, never had to engage.
- **No more `Thread.sleep` in FSM/ticker code.** Pacing moved to `BotSM.waitFor/waitForRandom` (own
  next move) and the new **`BotTiming.after/chain`** (delayed side-effects / scripted beats). The
  full decision table lives in `BotTiming.java`.

### Measured results (Legion Go, `-Xmx3g`, one player online)

| Metric | Old arch (~1.9k bots) | New arch @ 1,571 bots | New arch @ **6,571 bots** |
|---|---|---|---|
| Platform threads | ~2,040 | 137 | **164** |
| Macro dispatch lag (avg) | n/a | 53 ms | **56 ms** |
| Governor throttle | n/a | ×1.00 | **×1.00 (never engaged)** |
| Combat sweep (all grinders) | n/a | 0–1 ms / 787 | **2 ms (max 17 ms) / 5,000** |
| Idle packet builds/s (empty maps) | ~190 | ~0 | ~0 |
| Heap used | ~1.2 GB / 2 GB | 958 MB / 3 GB | **1,428 MB / 3 GB (46%)** |

**The scaling law changed:** 4.2× the bots cost +3 ms lag, +27 threads, and ~94 KB/bot marginal
heap. Naive extrapolation puts the 3 GB ceiling somewhere north of 15k loaded bots. The original
3,000–5,000 target was exceeded at 6.5k with room left over.

Full detail + methodology: `Documents/Fable/Done/Fable Audit - 2026-07-03 Bot System Scaling & Optimization.md`.

---

## 2. Fluid combat (GreenCatMS-derived)

Grinders stopped looking like they were on rails. Movement skills that were previously faked with
teleport-arcs became the real thing, and combat gained air game and class identity.

- **Real flash-jump and teleport locomotion** (`GCMovementSkills`) — nav-level primitives, decoupled
  from combat. Assassins/bandits flash-jump a genuine airborne arc; mages blink.
- **Arced jump-attacks and mid-air kiting** — bots leap toward a cluster, strike at apex, and drift
  away from melee range instead of standing in it.
- **Cluster targeting, AoE repositioning, up-teleport, ranged kiting**, and cleric heal-vs-undead
  behavior.
- **Class-aware movement** — thief **Haste** is now real (speed + jump), party Haste propagates from
  an online 60+ haste-thief in the party, and jump height scales with level for every class.
- **Level-scaled throwing stars** for claw thieves (`ThrowingStarSelector`), and a bot class
  distribution weighted toward v83 popularity so the crowd looks authentic.
- **Damage feel** — tier-scaled damage model (per-line bands by job tier + level ramp), per-class
  crit (negated damage lines), and damage-concentration for client-side knockback feel.
- **Tiered flash jump** (`FlashJumpTiers`) — reach scales with level × how well the platform fits,
  with a `!gcmove fj` calibration hook.
- **Sustained AoE split from the full-map ultimate**, with a 25 s cooldown on ultimates while
  grinding so it reads as deliberate, not spammy.

Merge review: `Documents/Fable/Done/Fable Audit III - 2026-07-07 Fluid-Combat Merge Review.md`.

---

## 3. Grind overhaul — the bot reads the map it's on

v0.2 had a single grind model (localized spot camping). v0.3 recognizes that a flat spawn-dense
ledge, a tower of tiny platforms, and a sprawling open field are *different problems*, and picks a
strategy to match (`BotGrindSystem/GrindStrategy` split into **Camp / Roam / Patrol / Stack**):

- **Camp** — plant on a tight, spawn-point-dense spot and wait through respawn lulls (the refined v0.2
  behavior, now one archetype among four).
- **Roam** — for un-campable maps (tiny platforms, jumpy mobs): horizontal-safe wandering with
  de-thrash recovery (fixes bots hurling themselves off tall vertical maps like El Nath).
- **Patrol** — spot-ring rotation for maps whose spawns are spread out.
- **Stack** — vertical tether grinding with axis-aware blinks for stacked-platform maps.

Supporting work:

- **Capacity-aware spot distribution** (Grind Redesign III) — map capacity is estimated from
  claimable spots; per-ledge partitioning, intra-spot spacing, and claim hygiene stop 10+ bots from
  piling onto one section while other platforms sit empty. Directly addresses the crowding bug logged
  in `v0.3 findings.txt`.
- **Smarter map selection** — level-band admission, region allow-lists, gap-decay, and occasional
  "chill visits" so cohorts land on level-appropriate maps and spread out instead of converging.
- **Deep hubs** (`DeepHub`) — cohorts that grind a home field and roam its neighbors, with
  downward-only progression and on-map vendor errands.
- **Rest & recovery** — `RestSpotFinder` (ledge threat model that rejects spawn-bearing/edge spots),
  `MobHitboxIndex` (per-map hitbox inflation from WZ), grind breaks (safe-ledge / chair / "brb" sign
  / rope-hang rest), and `ClimbRecovery` for rope/ladder snags.
- **Loot** — drive-by loot sweeps and class-skill loot approaches so ranged/magic grinders stop
  leaving drops on the floor.
- **Testing hooks** — `!bot grindstyle` forces an archetype; `!gcmove ropecheck` / `fj` diagnostics.

---

## 4. Living towns & social life — all seven cities

Ambient life was Henesys-only in v0.2. v0.3 extends it to all seven major towns (Henesys,
Ellinia, Perion, Kerning, Sleepywood, Orbis, El Nath) via a new **`BotTownSystem`**:

- **Town presence & loiter** — anchor-weighted ambient SocialBot cohorts per town, spawned as a
  dedicated startup wave (the new **wave 9**, after the grinders bake the town nav graphs), returning
  bots scatter off the arrival portal instead of clumping on it, plus a curation/override layer and
  `!env townpresence` live tuning.
- **`TownStation`** — engine-agnostic claim-a-spot-and-sit loitering (benches, shop fronts).
- **Bot-to-bot conversations** (`BotChatterSystem`) — paired and clustered scripted exchanges driven
  by `TownChatterDialogue.yaml`, plus town-wide ambient barks and chalkboard/megaphone activity,
  all observation-gated. `!env chatter` / `!env townpresence reload` tune them live.
- **Bot flavor** (`BotFlavorSystem`) — idle expression (emotes, cosmetic skill-swings, buff-flex)
  and **level-up congratulations** that fire when a real player *or* another bot levels up
  (`LEVEL_UP` published on the event bus).
- **`TownWandererBot`** — a generic roaming town personality for the non-Henesys cities (HenesysBot
  stayed untouched).

### Party play — recruitable bots

- **Town party recruitment** (`BotPartySystem/BotRecruitManager`) — SocialBots recruit players in
  town, and a recruited player gets a **`FollowerBot`** that follows and grinds alongside them
  (station-here + party-aware DECIDE, real shared-party EXP). New `BotOptionMenu` for the interaction.

---

## 5. Code health, tooling, tests, and docs

The "Fable Audit II" pass hardened the codebase around the new architecture rather than adding
features:

- **`!env perf`** — the single bot-system health command: thread count, heap, ticks/s, wheel lag,
  governor throttle, combat sweep timing. Call it twice a few seconds apart for live rates.
- **`BotTickServiceTest`** — six unit tests pinning the wheel invariants (never two concurrent
  `updateState()` per bot; delay measured from tick *completion*; a nudge during an in-flight tick is
  never lost).
- **De-sleeping the FSMs** — `sleepAmountSeconds` renamed to `blockingSleep`, sanctioned sites tagged,
  SocialBot choreography converted to `BotTiming` chains; `BotTiming` (after/chain) introduced as the
  standard.
- **Dead code removed** — ~519 confirmed-dead lines deleted (re-verified zero callers), pool sprawl
  reduced (`ExecutorServiceManager` 100→8 threads; `MethodScheduler` defanged to a thin delegate).
- **Concurrency safety** — parallel-spawn registry races addressed as the wheel moved bot ticks onto
  shared virtual threads.
- **Project skills** — four `.claude/skills/` (bot-authoring, bot-perf-triage, solomapling-conventions,
  solomapling-dev) so future sessions inherit the post-Fable rules.
- **Documentation refresh** — CLAUDE.md and ~13 architecture docs updated to the tick-wheel reality;
  the planning-vs-implementation workflow codified.
- **Bug fixes** — FM shop kick sending the wrong-slot exit packet to the kicked visitor; repeating
  bot-trade NPE from a stale `BotTradeQueue` entry; GCTravel soft-lock watchdog (warp + intent log
  when a bot is stuck jumping at an unreachable portal); config.yaml forced back to pure ASCII (a
  stray em-dash was crashing server startup).

---

## 6. New in v0.3 — subsystem & bot-type inventory

**New bot types:** `TownWandererBot` (generic town roamer), `FollowerBot` (player-party grinder).
`TestAttackBot` added as a combat-test harness.

**New subsystems / packages:**

- `server/BotTickService`, `server/BotTiming`, `server/BotPerfStats` — the tick wheel, delayed-action
  helper, and perf counters.
- `BotTownSystem/` — `TownLoiter`, `TownStation`, `TownPresenceConfig`, `TownPresenceSampler`,
  `TownOverrides`, `TownPinsStore`.
- `BotChatterSystem/` — `BotChatter`, `TownChatterLines` (+ `TownChatterDialogue.yaml`).
- `BotFlavorSystem/` — `BotFlavor`, `FlavorAction`, `LevelUpCongrats`.
- `BotGrindSystem/` (expanded) — `GrindStrategy`, `CampStrategy`, `RoamStrategy`, `PatrolStrategy`,
  `StackStrategy`, `EngageBeat`, `GrindLoot`, `ClimbRecovery`, `RestSpotFinder`, `MobHitboxIndex`,
  `FlashJumpTiers`, `DeepHub`, `TrainingMapChooser`, `TrainingRegions`, `GrindStyle`/`MovementStyle`
  policies, `SpotStack`.
- `GCMoveSystem/GCMovementSkills`, `GCMoveSystem/LodCounts` — combat locomotion + LOD counters.
- `BotPartySystem/BotRecruitManager`, `ArtificialPlayer/BotOptionMenu`.
- `BotAttackSystem/ThrowingStarSelector`, `Environment/PlatformPlacement`.

---

## 7. Known issues & consciously deferred

Kept honest — these are open going into v0.3 (from `v0.3 findings.txt` and the Fable audit backlogs).
None block the milestone; several are latent (never observed live).

**Open findings:**
- FM shop kick can still disconnect a low-baller in browser slot 2/3 to login instead of ejecting
  cleanly — a per-slot packet-routing issue (partly addressed; verify).
- Training bots can still get stuck jumping toward an unreachable portal on curved-floor maps;
  soft-lock warp mitigation exists but the "on top of the portal → just enter it" case wants
  tightening.

**Latent hazards flagged by Audit II/III (planning-only, un-hit at 6.5k bots):**
- `GrindBrain` combat-ticker vs. macro-tick field races (fix: per-instance lock on the mutators).
- Non-thread-safe lazy singletons `EventBus` / `ItemDatabase` (fix: eager init).
- `BotBlockList` plain `ArrayList` mutated from concurrent trade ticks.
- A few `BotSM` cross-thread fields (`running`, `state`) not `volatile`.
- Governor throttle is bypassed for the population it protects (re-asserted cadence writes an
  unthrottled due time) — a safety net that has never needed to engage.
- Spot-claim release keyed to current map rather than the claim map (ghost claim if a grinder is
  force-warped).

**Deferred with data (build only if the numbers demand it):**
- True DORMANT park + wake-at-ETA travel (at 6.5k the 1 s idle heartbeat measured as harmless).
- Combat-ticker observed-subset (sweep is 2 ms at 5k grinders).
- Remaining FSM de-sleep conversions (harmless on virtual threads).
- Compartmentalization backlog: EnvironmentManager / TrainingBot / OPQBot splits, cross-bot
  `updateState` preamble boilerplate.

---

## 8. Milestone bookkeeping

**Merges that make up v0.3 (first-parent, newest first):**

| Merge | What it brought |
|---|---|
| `84a75d91` | town-social-life — bundles fluid combat, fable-final, town presence/loiter, all-town social life, bot flavor, grind archetypes, movement fixes |
| `36fe4f07` | party recruitment, FollowerBot, grind breaks |
| `367150c7` | Fable Audit II — code health, de-sleep, grind/movement hardening |
| `71921182` | Fable bot-scaling — the tick wheel (Phases 0–5) |
| `2ec14f88` | El Nath cohort |

**Source-of-truth docs (all under `Documents/Fable/`):**
- `Done/Fable Audit - 2026-07-03 Bot System Scaling & Optimization.md` — the scaling architecture + measured results.
- `Fable Audit II - 2026-07-04 Code Health.md` — hazards & backlog.
- `Done/Fable Audit III - 2026-07-07 Fluid-Combat Merge Review.md` — combat merge review.
- Plus the per-feature plan docs (grind strategies, town presence, town social life v0.5/v0.6, bot
  flavor, movement root causes, rope top-exit, rest spots, party recruitment).

**Docs updated for this milestone:** `CLAUDE.md` (bot-type roster corrected to 18 production types
incl. TownWandererBot/FollowerBot; stale Fable-audit path re-pointed to `Done/`).

---

*v0.3 · 2026-07-08 · the Fable arc. Built on Cosmic (Global MapleStory v83). The framework touches
the host engine in only ~1,100 lines — a layer on top, not a rewrite.*
