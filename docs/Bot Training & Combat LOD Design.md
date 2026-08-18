# Bot Training & Combat LOD — Design

> **Status:** design only (no code yet). Implement when the **training-bot type** lands and the bot
> combat/attack system is on this branch. Extends the observability-based LOD model from
> `GCMoveSystem LOD Implementation Plan.md` from *movement* to *combat + leveling*.
>
> **Governing rule (same as movement LOD):** fidelity matches observability. Pay full combat +
> animation only where a real player can see it; everywhere else the bot just *levels as if it were
> grinding*, by cheap arithmetic. Cost scales with **players, not bots**.

---

## 1. Context (what these bots are)

- Bots are **ephemeral decoration** — wiped on server restart, **no inventory, no economy impact, no
  real drops**. Their loot animation is purely for show.
- They **do** kill mobs (for show, when watched), and **training bots are on the roadmap**: a
  `GRIND → RETURN_TO_TOWN → GRIND` rotation.
- Desired behavior: a bot grinding far from any player should **not** run attacks / movement / attack
  / loot animations (no packets, no mob sim) — but it **should still level up over time**, so it
  doesn't sit frozen at one level forever. When a real player arrives, it switches to real combat.

This is the *combat* analogue of the movement COARSE tier: full physics when watched, analytic ETA
when not → here, full combat when watched, **analytic EXP** when not.

---

## 2. Two separable concerns (don't conflate them)

| Concern | Cosmetic? | LOD treatment |
|---|---|---|
| **Attack / hit / loot animation** (packets) | yes — only matters to observers | **gate on observability** — never send when no player is on the map |
| **Combat simulation** (mob targeting, damage, kills, EXP) | no — has game-state effect (leveling) | **tier it** — real kills when observed; **abstract EXP accrual** when not (mobs untouched) |

The first is a near-free win and bigger than movement was: one bot swing is several heavy packets
(skill anim + per-mob hit + damage numbers + loot drop + loot pickup) plus the mob AI/hit-detection
behind it. The second is the interesting part below.

---

## 3. The combat tier model

Binary (simpler than movement's four tiers — no "halo pre-warm" needed; a player on an *adjacent*
map can't see the fight, and starting combat on entry is instant with no physics shadow to rebuild):

| Tier | When | Mobs | Animation | EXP |
|---|---|---|---|---|
| **REAL** | a real player is on the bot's map | real targeting + damage + kills | full (packets) | from real kills |
| **ABSTRACT** | nobody on the bot's map | **untouched** | **none** | level-scaled analytic accrual (§4) |

- **Gate:** `GCObserverTracker.isFull(mapId)` (real player on the bot's *own* map). HALO (adjacent)
  stays ABSTRACT — the fight isn't visible from the next map over.
- **Anti-flap (optional):** a short FULL-only dwell (a few seconds of REAL after the player leaves) so
  a player pacing a portal doesn't flap combat on/off. Cheaper concern than movement (restart = just
  re-target a mob), so a dwell is optional — add a `isFullOrDwell` to the tracker if flapping shows.
- **Note:** mobs typically don't spawn on player-empty maps anyway, so ABSTRACT bots usually have
  nothing to hit — abstract EXP *is* the grind, not a fallback.

### Promotion / demotion (trivial vs movement)
- **ABSTRACT → REAL** (player enters): the bot just starts attacking the nearest mob. No
  reconstruction. Optionally snap to a sensible combat stance/position first.
- **REAL → ABSTRACT** (player leaves): stop animating; resume EXP accrual from the bot's current
  level. Its abstract rate continues seamlessly.

---

## 4. The level-scaled abstract EXP model (chosen)

When ABSTRACT, accrue EXP at a rate that depends on the bot's level vs the training map's mob level,
so a bot **slows as it out-levels a map and graduates to a harder one** — driving the town↔training
rotation naturally and keeping progression believable.

```
abstractExpPerSec(bot, map):
    mobLevel = map.trainingMobLevel            // representative mob level of the training map
    delta    = bot.getLevel() - mobLevel       // + = over-levelled, - = under-levelled
    return BASE_EXP_PER_SEC * mobLevel * efficiency(delta)

efficiency(delta):                              // 0..1, peaks on-level, falls off both sides
    if delta in [0 .. GOOD_BAND]   -> 1.0                                    // on-level: best
    if delta < 0                   -> max(MIN_EFF, 1 + delta / UNDER_FALLOFF) // under-levelled: slow
    else (delta > GOOD_BAND)       -> max(MIN_EFF, 1 - (delta-GOOD_BAND)/OVER_FALLOFF) // out-levelled
```

- **Accrual:** on a slow tick (e.g. the bot's brain cadence), `exp += rate * elapsedSec`. When `exp`
  crosses the level-up threshold (Cosmic's per-level EXP table), `level++`, carry the remainder, repeat.
- **Silent:** the abstract path updates the bot's level/exp **in memory without packets** — do NOT
  call the normal `Character.gainExp` (it broadcasts). Use a silent setter. (When REAL/observed, real
  kills can use the normal path so a watching player sees the level-up effect — a nice touch.)
- **Graduate:** when `efficiency(delta) < GRADUATE_THRESHOLD` because the bot has out-levelled the map,
  the brain (§5) rotates it to a harder training map. Under-levelled (delta very negative) → pick an
  easier map.
- **Level cap:** a configurable cap (global or per training track) so bots don't all drift to 200.
- **Constants** (`BASE_EXP_PER_SEC`, `GOOD_BAND`, `UNDER_FALLOFF`, `OVER_FALLOFF`, `MIN_EFF`,
  `GRADUATE_THRESHOLD`, cap) are tunables — start rough; it's decoration, not balance.

Optionally re-roll appearance/equipment tier at level milestones (the bot visibly "gears up" as it
levels), via the existing `BotDecoratorSystem`. Cosmetic; defer.

---

## 5. The training-bot brain (where combat LOD plugs in)

A new bot type (e.g. `TrainingBot`) with a small FSM, ticked on the slow decision cadence (like the
existing pollers) — **tier-agnostic**, runs regardless of observability:

```
GO_TO_TRAIN  -> GCMovement.travel(trainMap)        (movement LOD handles fidelity)
GRIND        -> while at trainMap: combat tier (REAL or ABSTRACT per §3); accrue/earn EXP;
                if out-levelled -> pick harder map; periodically -> RETURN_TO_TOWN
RETURN_TOWN  -> GCMovement.travel(townMap); idle/restock-for-show; -> GO_TO_TRAIN
```

Composition:
- **Travel** legs reuse the **movement LOD** already built (coarse/warp when unobserved). Free.
- **Grind** phase is the only net-new executor: REAL combat (existing attack system) vs ABSTRACT EXP.
- The brain only **issues** combat/move intents and **polls** status — same decoupled model as the
  movement action tick (doc 16 §5).

---

## 6. Reuse / integration points

- **Observability:** `GCObserverTracker.isFull(mapId)` (+ optional FULL-dwell). Already built.
- **Real combat path:** the existing bot attack system (`BotAttackSystem` / the GreenCat melee work on
  `experiment/bot-skills-system`). This design **gates** it; it does not replace it.
- **EXP table / level-up:** Cosmic's per-level EXP table; a **silent** level/exp setter (no packets).
- **Appearance on level:** `BotDecoratorSystem` (optional milestone re-roll).
- **Movement:** `GCMovement.travel/move` for the town↔train legs.

---

## 7. Dependencies — what must land before building

1. The **training-bot type** (roadmap) — the driver of the grind↔town loop. Doesn't exist yet.
2. The **bot attack/combat system on this branch** (currently `experiment/bot-skills-system`) — the
   REAL-tier executor.

Until both are present, this stays design-only: building the abstract-EXP core now would be
infrastructure with no caller and risks not fitting the real combat shape.

---

## 8. Suggested file list (when building)

- `TrainingBot` (new bot type) — the grind↔town FSM brain.
- `BotGrindLOD` (or similar) — the combat tier switch: `isFull → real combat`, else `abstract EXP`.
- `AbstractExpModel` — the level-scaled rate + accrual + graduate logic (pure, unit-testable with a
  fake clock + a synthetic EXP table, exactly like `CoarseExecutorTest` / `TierDwellTest`).
- Silent EXP/level setter helper (no-broadcast).
- Optional `GCObserverTracker.isFullOrDwell` if combat flapping appears.
- Training-map registry (mob level + level band per map) — YAML, matching the project's YAML-first
  convention.

No core/engine changes; additive, like the movement LOD.

---

## 9. Open questions / risks

- **EXP rate calibration:** abstract rate vs what real grinding would yield — only needs to be
  *believable*, not exact (decoration). Tune live.
- **Level-up while observed:** if a bot crosses a level threshold mid-fight in front of a player, route
  that one through the real (packet) path so the effect shows.
- **Map mob-level data:** needs a representative mob level per training map (registry).
- **Stat/HP scaling on level:** do levelled bots need updated stats for inspect/UI correctness, or is
  the level number enough? (Decoration → probably just the number + optional gear re-roll.)
- **Cap policy:** global cap vs per-track, so the population doesn't homogenise at max level.

---

## 10. Verification (when built)

- **Offline/unit:** `AbstractExpModel` as a pure function of `(level, mapMobLevel, elapsed)` →
  deterministic fake-clock tests for accrual, level-up boundaries, efficiency falloff, and the
  graduate trigger (mirror `CoarseExecutorTest` / `TierDwellTest`).
- **Live:** a training bot grinding unobserved should **gain levels over time** with zero combat
  packets (confirm via a stats readout, like `!gcmove lod stats`); walking onto its map should flip it
  to real attacks + animation; CPU stays flat with many unobserved grinders.
