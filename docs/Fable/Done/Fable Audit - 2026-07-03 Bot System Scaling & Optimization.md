# Fable Audit — Bot System Scaling & Optimization

**Date: 2026-07-03 · Author: Claude Fable 5 (first Fable-class audit of this project)**
Status: **design + game plan only — no code changed.**

Inputs: full read of the bot concurrency surfaces (`BotSM`, `BotGeneration`, `EnvironmentManager`,
`GCMovementDriver`/`ObserverTracker`/LOD layer, `TrainingBot`/`BotGrindSystem`, messaging, event bus,
upstream `MapleMap`/world tasks), plus the two prior docs for reference:
`Legacy Docs/botTypeNotes/bot queue improvement.txt` (2025 ChatGPT notes — verdicts in §6) and
`Codebase Audit - 2026-06-27 Refactor & Compartmentalization Brainstorm.md` (Opus — its §3 overlaps;
where we differ, this doc explains why in §5.1).

Scope guard, per the owner: **optimization and scaling only.** No GC-port restructuring, no
refactor-for-cleanliness, no file-split work. Where a finding lives inside ported (`GC`/NutNNut)
code we route around it, we don't rewrite it.

Findings marked **(confirmed)** were verified in source by me during this audit with file:line
evidence. Items marked **(verify)** deserve a quick check during implementation.

---

## 0. TL;DR

The system is in better shape than the "it can probably be improved" framing suggests. The LOD
investment already paid off where it matters most: movement physics, broadcasts, and combat all
bend their cost toward *player count*, not bot count. What's left is a set of **O(bots) fixed
costs** that don't bend — and they are all in our own code, all replaceable without touching the
GC ports:

1. **Every bot owns a dedicated OS platform thread** — `BotSM.startScheduledTask()` does
   `Executors.newScheduledThreadPool(1)` per bot (`BotSM.java:259`). At today's ~1,890 bots that is
   ~1,900 platform threads (they are **not** virtual threads — virtual threads are only used for
   spawn choreography and fire-and-forget async). This is the #1 scaling wall.
2. **Every bot always has a timer, even when nobody can ever see it.** There is no dormant tier:
   a cold training bot still runs a 250 ms movement task, a 500 ms combat-ticker visit, and a 10 s
   macro tick forever. "Deprioritized" today means "slower," not "free."
3. **Per-tick O(map) scans and dead broadcasts**: every macro tick scans the map's character list
   to ask "is a player here?" (`BotSM.java:174-182`) and then **builds + broadcasts an idle
   movement packet even on empty maps** (`updateState` → `BotIdleStandingUpdate`,
   `MovementCommands.java:282-291,589-606`).
4. **Scheduler churn**: while a player *is* watching, `checkPrioritySpeed()` cancels and recreates
   the bot's `ScheduledFuture` nearly every tick (`BotSM.java:315-339`).

The architectural end-state (§5) is: **one shared tick wheel, tier-scaled cadence, and a true
DORMANT tier that holds zero timers and fast-forwards on wake** — the Witcher-3 trick: off-screen
NPCs aren't simulated slowly, they're *not simulated at all* and are summarized when you arrive.
With that in place, 5,000 bots where ~95% are cold should cost roughly what ~250 active bots cost
today, on the same Legion Go.

And to answer the hardware question directly: **no, the GPU can't help.** The Z1 Extreme's iGPU has
no separate VRAM — it carves from the same 16 GB LPDDR5X the JVM uses, and it's busy rendering the
game client anyway. Java GPU compute (TornadoVM/OpenCL) only pays for dense numeric kernels, not
branchy FSM/packet logic. The wins here are all "do less work," not "find more silicon."

---

## 1. Hardware reality check (Legion Go)

- **APU:** Ryzen Z1 Extreme — 8 Zen4 cores / 16 threads, 5.1 GHz *single-core boost*. In a handheld
  TDP envelope with the game client also running, realistic sustained all-core clocks are far
  lower. Budget the server as if it owns **2–4 effective cores**, not 16.
- **RAM:** 16 GB LPDDR5X **shared** with the iGPU framebuffer + OS + game client. The server jar
  currently runs `-Xmx2048m` (`launch.bat:3` — confirmed). Total server footprint is heap + thread
  stacks + metaspace + Netty/DB buffers.
- **Thread stacks are the hidden tax:** ~1,900 platform threads × 1 MB default reserved stack
  ≈ 1.9 GB of reserved address space (committed less, but real). Phase 2 makes this vanish
  outright — preferable to `-Xss` tuning.
- **`Runtime.availableProcessors()` = 16** on this box, so autoscaled pools come out as:
  `MethodScheduler` = 16 threads, `GCMovementDriver.POOL` = 8 threads.

Implication: every recommendation below optimizes for **fewer runnable threads, fewer timer
wakeups, less allocation** — not for spreading work wider.

---

## 2. Current state — the concurrency census

### 2.1 Bot population (wave math, confirmed from `EnvironmentManager.java:95-188`)

- Waves 1–7: ~650 town / FM / specialty bots (Henesys, FM rooms ×4 regions, JQ, blackjack, OPQ…).
- Wave 8: **1,240 training bots** (1,195 across 12 town/deep-hub cohorts + 45 beginner grinders).
- Total ≈ **1,890 bots**, each a full Cosmic `Character` loaded from DB (CID 2 clone,
  `BotGeneration.java:78-105`) and registered in the real `PlayerStorage` + channel
  (`BotGeneration.java:187-192`).

### 2.2 Threads at steady state (C = 16 cores)

| Source | Threads | Notes |
|---|---|---|
| **`BotSM` per-bot pools** | **~1,890** | `newScheduledThreadPool(1)` per running bot — `BotSM.java:259` |
| `ExecutorServiceManager.executorService` | up to 100 | fixed pool, `ExecutorServiceManager.java:10` |
| `ExecutorServiceManager.scheduledExecutorService` | 10 | the shared workhorse, `:11` |
| `MethodScheduler` | 16 (=cores) | mostly redundant with the above |
| `GCMovementDriver.POOL` | 8 (=max(2,C/2)) | drives ALL bot movement |
| GCTravel / GCFollow / GCFidget / ObserverTracker / DecorationQueue / 2× graph-warmup | 8 | singletons, 1–2 each |
| Upstream `TimerManager` | 4 | all Cosmic engine timers |
| **Total** | **~2,040 platform threads** | + ~16 virtual-thread carriers + Netty/DB/JVM |

Thread count scales **1:1 with bot count**. Everything except the `BotSM` row is already
shared-pool and scales by task count instead.

### 2.3 Recurring work at steady state (~1,890 bots, zero players online)

| Task | Cadence | Multiplicity | Idle cost character |
|---|---|---|---|
| BotSM macro tick | 10 s (unobserved) | per bot | map-scan + idle packet build + broadcast to nobody (§4 F3/F4) |
| GCMovementDriver tick | 250 ms (unobserved) | per GC-movement bot (~1,240) | ~5,000 task executions/s on 8 threads; coarse = cheap, no-graph/airborne = physics + dead broadcast |
| TrainingBot combat ticker | 500 ms | **1 shared task** | O(active grinders) loop; unobserved bots return immediately — already the model to copy |
| ObserverTracker refresh | 1 s | 1 | O(real players) — already scales right |
| Dispatcher + QueueCleaner | 2 s each | 2 | trivial |
| BotDecorationQueue | 250 ms | 1 | trivial batches |
| GCTravel/Follow/Wander pollers | 300/400/400 ms | per active traveler only | fine |

The "idle hum" the machine feels today is dominated by rows 1–2: **hundreds of timer wakeups and
task executions per second that produce nothing a player can ever see.**

### 2.4 What is already right (preserve these — they are the template)

- **The LOD tier machinery** (`ObserverTracker` FULL/HALO/dwell/COARSE, 1 s poll, 350 px portal
  HALO, 4 s dwell, `markObservedNow` instant promotion). Cost scales with players. Keep as the
  single source of observation truth and *widen* its use (§5.2).
- **`CoarseExecutor` + `MovementPlan`**: coarse position is a **pure function of the clock**
  (`CoarseExecutor.java:29`) — this is the key that unlocks the dormant tier (§5.4).
- **The shared combat ticker** (`TrainingBot.java:162-196`): one task, all grinders, self-gating
  per bot. Exactly the shape the macro brain needs.
- **Nav graph caching**: shared per (map, speed, jump), disk-cached, built off-thread
  (`BotNavigationGraphProvider`). Don't touch.
- **Broadcast hygiene**: packets built once, bots skipped as recipients, movement broadcasts
  dedup-guarded (`BotMovementManager.java:862-870`, `MapleMap.java:2831-2848`).
- **Map-entry responsiveness** (`BotMapEntryResponder` + `nudgeSoon`, both directions, jittered
  150–700 ms, debounced 1.5 s). This *is* the wake mechanism the dormant tier will ride on.
- **Wave-phased startup** (~1,890 bots in ~10 s) and the watchdog culture (movement stuck
  detectors, grind watchdog, travel timeout, try/catch around every shared ticker body).
- **Event delivery is buffered per bot** (`BotEventBuffer`, drop-oldest at 100) — publishers never
  block on bot logic.

---

## 3. How the pieces interact today (one paragraph you need for §5)

A bot is four independent clocks glued by shared state: the **macro brain** (per-bot thread,
2–6 s / 10 s) decides *what to do*; the **movement driver** (shared 8-thread pool, 50/250 ms
self-reschedule per bot) executes *where to go*; the **combat ticker** (1 shared 500 ms task)
executes *fighting*; and **ObserverTracker** (1 s) decides *how honest the simulation must be*.
Only the last two are centralized. The macro brain is the odd one out — per-bot threads, per-tick
map scans, blocking sleeps inside ticks — and it is also the only clock that *owns* a thread per
bot. The plan below centralizes the macro brain the same way combat already was, then teaches all
four clocks to stop entirely for cold bots.

---

## 4. Findings, ranked

**F1 — Per-bot platform thread for the macro FSM. (confirmed · HIGH · the scaling wall)**
`BotSM.java:259` — `Executors.newScheduledThreadPool(1)` per bot; ~1,900 OS threads, ~1.9 GB
reserved stack, constant scheduler pressure. Nothing about the macro tick needs a dedicated
thread: cadence state (`currentDelay`, `lastNudgeMs`) is just data. Fix in Phase 2.

**F2 — Observed bots cancel + recreate their ScheduledFuture nearly every tick. (confirmed · MED, trivial fix)**
`checkPrioritySpeed()` runs every RUNNING tick; the observed branch calls
`setPriorityNormal()` → `updateScheduleDelay(getRandomDelay())` with a *fresh random*, so the
"no change" guard (`BotSM.java:269`) almost never hits → synchronized cancel + reschedule +
allocation per tick per observed bot (`BotSM.java:315-339`). Fix: track a cadence *tier* (observed
/ unobserved), reschedule only on tier transition; keep per-tick jitter by drawing the delay when
(re)scheduling, or fold jitter into the wheel in Phase 2 (which deletes this whole path anyway).

**F3 — "Is a player here?" is an O(characters-on-map) scan, per bot, per tick. (confirmed · MED-HIGH)**
`checkMainPlayersOnMap()` (`BotSM.java:174-182`) iterates `map.getCharacters()` (LinkedHashSet
copy under a **fair** RW-lock, `MapleMap.java:120,187-213`). On a 200-bot map that's O(n²) per
tick wave, plus lock traffic. Meanwhile `ObserverTracker.isFull(mapId)` answers the same question
in O(1) and is already maintained. Fix: replace the scan with the tracker query (one-line
semantics change: "any player on map" → "map observed", which is the *intended* meaning anyway).
The bot's own TODO at `BotSM.java:172-173` asks for exactly this.

**F4 — Idle bots build and broadcast packets to empty maps, forever. (confirmed · MED-HIGH, easy win)**
Every RUNNING macro tick calls `BotIdleStandingUpdate` (`BotSM.java:200`) which, if the bot is
standing, constructs an idle movement InPacket, re-parses it, updates position, and
`broadcastMessage`s it (`MovementCommands.java:282-291` → `BotMove` `:589-606`) — even with zero
real players on the map (the broadcast loop then iterates all map characters under the fair lock
just to send to nobody). At ~190 unobserved ticks/s server-wide this is pure waste. Fix: gate on
`ObserverTracker.isActiveMap()`; on promotion the existing nudge + a one-shot forced idle refresh
covers client-side freshness. Related known gap in the movement layer (agent-confirmed): unobserved
**airborne/climbing/no-graph** bots also still broadcast from inside the physics core
(`GCMovementDriver.java:183-186` fallthrough; design doc M4 unbuilt) — gate at the same
chokepoint if possible without touching ported internals (`broadcastIfObserved` already exists,
`GCMovementDriver.java:425-429`).

**F5 — No dormant tier: cold bots still tick everything. (confirmed · HIGH, the design gap)**
COARSE movement bots self-reschedule every 250 ms even though `CoarseExecutor.advance` is a pure
clock function that could be evaluated *lazily*; macro brains tick at 10 s forever; the combat
ticker visits every grinder every 500 ms. "Cold" should mean **zero scheduled work**: a wake time
(plan ETA / next macro intent) in a queue, and state fast-forwarded on promotion. The abstract-EXP
path (`TrainingBot.accrueAbstractExp`, `:825-844`) already proves the fast-forward model works —
it just still runs on a timer instead of being computed on wake. Design in §5.3/§5.4.

**F6 — Blocking sleeps inside `updateState()` pin threads and gate centralization. (confirmed · HIGH as a dependency)**
Base `BotSM` TRADING sleeps 2 s (`BotSM.java:231`); TutorialBot, SocialBot, OPQBot (700 ms × 4
swing loop), BlackjackDealerBot, DropGameBot, ScrollingBot (10–15 s!), GachaBot sleep 100 ms–15 s
inside ticks (census §4 has the full line list). Today each sleep pins that bot's own thread; on a
shared wheel it would stall other bots. This is *the* reason Phase 2 dispatches ticks onto virtual
threads first (sleeps become ~free) and Phase 4 converts sleeps to wake-at-timestamp states
per bot type (the 2025 note's `stateEndTime` pattern — correct then, correct now).

**F7 — `CharacterStorage` is an unsynchronized `HashMap` + `ArrayList`s. (agent-confirmed · MED correctness, trivial fix)**
`activeBotMap` plain `HashMap`, `currentRespondants`/`inquirer` plain `ArrayList`
(`CharacterStorage.java:9-11`), mutated from parallel spawn waves, Dispatcher threads, and bot
lifecycle. CLAUDE.md's own claim "shared registries use ConcurrentHashMap" is violated by the most
shared registry of all. A resize race here corrupts the global bot index. Fix: `ConcurrentHashMap`
/ `CopyOnWriteArrayList`, 10 minutes, do it in Phase 1.

**F8 — Per-bot foothold index duplication. (agent-confirmed · MED memory)**
Every bot rebuilds a full-map `Map<Integer,Foothold>` on map entry (`BotMovementManager`
`buildFhIndex` `:917-923`, invoked from `GCMovementDriver.onMapChange:392`). 125 bots on one map =
125 identical maps. Fix: a static per-map cache (`ConcurrentHashMap<Integer, Map<Integer,Foothold>>`)
— the index is immutable per map. This lives at the driver boundary (our file), not inside the port.

**F9 — A\* allocation churn on observed AI ticks. (agent-confirmed · MED CPU, deliberately deferred)**
`BotNavigationManager.runSearch` allocates PriorityQueue + 3 HashMaps + per-node objects every
100 ms per moving observed bot. It's inside ported code → **out of scope per the owner's ground
rules.** Mitigation is indirect: fewer simultaneously-observed movers (LOD already caps this), and
the existing `BotPerformanceMonitor` hooks can tell us if it ever actually matters on the Z1.

**F10 — Host-engine costs from bots living in `PlayerStorage`. (confirmed · LOW-MED, mostly fine)**
World tasks (pet/mount/fishing/party-search/timeout, 10 s–1 min cadences, `World.java:232-249`)
iterate all ~1,890 entries. `TimeoutTask` is already bot-patched (`TimeoutTask.java:25`); the rest
are cheap null-checks. **(verify)** hourly `CharacterAutosaverTask` (`USE_AUTOSAVE: true` in
config) — bots load via `loadCharFromDB(..., false)` so `loggedIn` should stay false
(`Character.java:7084` is inside the channelserver branch) and they're skipped; confirm once with
a log line, because a save storm of 1,890 fake characters into the DB would be nasty.

**F11 — Memory posture unknown at 2 GB heap. (confirmed config · MED, measure before changing)**
~1,890 full `Character` objects + nav graphs + 825 movement recordings + item pools inside
`-Xmx2048m`. It runs today, so it fits — but there's no headroom number for 3–5k bots. Also every
`BotSM` eagerly allocates trade inventory/wants, debug handler, dialogue handler, and a 100-slot
event buffer even for the ~1,240 training bots that never trade (`BotSM.java:100-109`). Phase 0
measures; lazy-init the trade trio and consider `-Xmx3g` only if measurement says so.

**F12 — Pool sprawl. (confirmed · LOW)**
A 100-thread fixed pool (`ExecutorServiceManager.java:10`) is far oversized for its one real
consumer (Dispatcher match handling); `MethodScheduler` duplicates the shared scheduled pool with
16 more threads (the older audit already recommended merging). Consolidate during Phase 2 while
the scheduler surface is open. The 10-thread shared scheduled pool is the right home for the new
wheel *driver*, not for per-bot work.

**F13 — No tick-lag / throughput metrics; `BotPerformanceMonitor` is a no-op stub. (confirmed · enabler gap)**
`!gcmove lod` gives tier snapshots, but nothing measures "did the system keep up" (wheel lag,
ticks/s, dispatch queue depth, per-subsystem CPU). Phase 0 fixes this first so every later phase
has a before/after number. Without it, "optimize so advanced it just works" is vibes.

**F14 — Minor: `DropGameBot` spawns 2 dedicated threads per active game (`DropGameBot.java:467,480`); `setPriorityLow` comment says 20 s, code says 10 s (`BotSM.java:325-327`); `PAUSE` state is dead code. (confirmed · LOW)**
Sweep up opportunistically while editing `BotSM` in Phase 2.

---

## 5. Target architecture — "one clock, many lanes, zero timers for the cold"

### 5.1 `BotTickService` — central wheel for the macro brain

One new class (suggested home: `soloMapling/server/BotTickService.java`), replacing every per-bot
scheduler:

- **Data:** each bot gets a `nextDueMs` (long) + `cadenceTier` + an `AtomicBoolean ticking` guard.
  Bots live in a min-heap / `PriorityQueue` keyed by `nextDueMs` (or a simple hashed timing wheel;
  at <10k bots the heap is fine and simpler).
- **Driver:** ONE `scheduleWithFixedDelay` task on the existing shared scheduled pool, period
  ~100–250 ms. Each pass pops all due bots and dispatches each `updateState()` to a
  **virtual-thread-per-task executor** (`ExecutorServiceManager.runAsync` already exists).
- **Overlap invariant preserved:** dispatch only if `ticking.compareAndSet(false,true)`; clear in
  a `finally`, then compute the next `nextDueMs = now + cadenceFor(tier) + jitter` and re-insert.
  This reproduces `scheduleWithFixedDelay`'s "never two ticks at once, delay measured from
  completion" semantics exactly.
- **`nudgeSoon` becomes data:** set `nextDueMs = now + delay` and re-heap (debounce unchanged).
  `setPriority*` becomes "set tier field." All the synchronized cancel/reschedule machinery (F2)
  is deleted, not fixed.
- **Why virtual threads for dispatch (differs from the Opus audit's "bounded platform pool"):**
  the inline sleeps (F6) make a bounded platform pool dangerous — a burst of ScrollingBot 15 s
  sleeps could occupy every worker (head-of-line starvation). Virtual threads make a sleeping tick
  cost ~a few hundred bytes, no carrier pinned during `Thread.sleep`. We get the thread-count win
  on day one *without* first de-sleeping 8 bot types, and de-sleeping (Phase 4) becomes a
  quality improvement instead of a blocker. Watch-item: JDK 21 pins carriers when a VT blocks
  *inside a `synchronized` block* — bot sleeps don't hold monitors today (verify with
  `-Djdk.tracePinnedThreads=short` during rollout); MapleMap uses `ReentrantLock`s, which don't pin.
- **Result:** ~1,900 threads → the shared pools only (~150), with identical observable behavior.

### 5.2 One observation truth: widen `ObserverTracker`

`ObserverTracker` already computes exactly the tiers everyone needs, at O(players), on one thread.
Make it the *only* way any bot system asks about visibility:

- `BotSM` cadence: replace `checkMainPlayersOnMap()` scans with `isFull(mapId)` (F3).
- Idle refresh + any packet emission: gate on `isActiveMap(mapId)` (F4).
- Add one cheap upstream convenience **(small host patch, justified):** a `realPlayerCount` int
  maintained in `MapleMap.addPlayer/removePlayer` — O(1), makes even non-tracker call sites cheap,
  and gives the tracker a faster refresh primitive. (Optional; tracker alone suffices.)

### 5.3 Whole-bot LOD: four tiers, and the fourth has no clock

| Tier | Trigger (already computed) | Macro cadence | Movement | Combat | Packets |
|---|---|---|---|---|---|
| **T0 FULL** | player on map | 2–6 s (+ nudges) | 50 ms physics | real swings | yes |
| **T1 HALO/dwell** | player near portal / left <4 s ago | 5–10 s | 50 ms physics | no | yes (M4 later: no) |
| **T2 COARSE** | unobserved, reachable soon | 30–60 s | **wake-at-ETA** (no periodic tick) | abstract EXP on wake/summary | **never** |
| **T3 DORMANT** | unobserved + no travel plan in flight | **none — event-driven only** | none | rolled up on wake | never |

- T2/T3 bots are **not in the wheel** except for a single scheduled wake (plan ETA, errand end, or
  a coarse "life sign" like a 60–120 s roll to start a new travel plan so the world still evolves).
- **Promotion path** (already exists): `MAP_ENTERED` → `BotMapEntryResponder` sweep → for each bot:
  fast-forward (below), insert into wheel with a 150–700 ms jittered `nextDueMs`, movement driver
  resumes FULL ticking. HALO gives the adjacent-map pre-warm you already like — unchanged.
- **Fast-forward on wake** ("don't simulate — summarize"): TrainingBot: apply
  `accrueAbstractExp(elapsed)` (exists) + place at a plausible grind spot (SpotFinder exists);
  travelers: `CoarseExecutor.advance(plan, startedAt, now)` is already a pure clock function —
  evaluate once on wake instead of 4×/s forever (F5); town/FM bots: nothing to roll up — they're
  ambient chatter, position shuffle optional.
- **Demotion path:** tracker demotes map → macro tick notices tier change → bot finishes current
  intent, parks: cancel periodic work, register its single wake, leave the wheel.

This is the piece that changes the scaling *law*: today cost ≈ k₁·bots; after, cost ≈
k₂·observed_bots + k₃·warm_bots + ε·cold_bots with ε ≈ one heap entry.

### 5.4 Movement: keep the driver for hot tiers, unhook the cold

No changes inside ported physics/nav. Changes at the driver boundary (our code):

- T0/T1: exactly today's 50 ms self-reschedule on the shared pool.
- T2: when a plan starts and the map is unobserved, **don't self-reschedule** — compute ETA from
  `MovementPlan.totalTimeMs`, register one wake (arrival → `arriveCoarse`, next hop, or promotion
  recompute via `positionAt(now)`).
- Gate the remaining unobserved broadcast leaks (no-graph / airborne / climbing fallthrough) at
  `broadcastIfObserved` (F4).
- Share the per-map foothold index (F8).

### 5.5 De-sleeping the FSMs (Phase 4, one bot type at a time)

Convert inline `sleepAmountSeconds(...)` choreography to the `waitUntil` pattern (2025 note §3 —
adopt as written): store `stateEndTime`, return from the tick, let the wheel re-enter. Order by
value: ScrollingBot (15 s sleeps) → OPQBot (swing loop) → Tutorial/Social → Blackjack/DropGame
(their table choreography may honestly stay sleep-based on virtual threads forever — they're
rare, observed-only bots; converting them is polish, not necessity). After the big offenders are
converted, the dispatch executor can optionally become a small fixed pool (= cores), but with
virtual threads there is no urgency — this phase is about *tick latency honesty*, not threads.

### 5.6 Capacity governor — "so advanced it just works"

Small feedback loop inside `BotTickService` (this replaces the 2025 note's CPU%-based idea with a
better signal we own):

- Measure **wheel lag** (now − due time at dispatch) and **dispatch queue depth** every driver pass.
- If p95 lag exceeds ~1 s sustained: stretch T1/T2 cadences globally (×1.5, ×2…), cap
  max-promoted-bots-per-map, log loudly. Recover symmetrically when lag clears.
- Expose everything via a new **`!env perf`** (or `!gcmove perf`): threads, wheel size per tier,
  ticks/s, lag histogram, movement tasks live, combat ticker duration, heap. One command = the
  whole health picture.

### 5.7 Memory plan

- Phase 0 measures actuals (`jcmd GC.heap_info`, JFR 60 s flight, `Thread.print | wc`).
- Lazy-init trade trio + debug handler in `BotSM` (saves ~4 objects × 1,890, mostly principle).
- Keep `-Xmx2048m` until measurement argues otherwise; the thread-stack reclaim from Phase 2 is
  the real memory win. If 5k bots need it, `-Xmx3g` is safely inside the Legion Go budget with the
  client running.

---

## 6. Verdicts on the 2025 idea list (`bot queue improvement.txt`)

| 2025 idea | Verdict |
|---|---|
| 1. Centralized tick queue | **Adopt** — it's §5.1, with the pileup guard + jitter it lacked. The `nextUpdateTime` comparison model is exactly right. |
| 2. Batch/chunked updates (20% per 400 ms slice) | **Superseded** — random per-bot jitter already de-lockstepped ticks; the wheel + due-time heap naturally spreads load. A per-pass dispatch cap is the honest version (part of §5.6). |
| 3. Non-blocking states / eliminate in-tick sleeps | **Adopt** — §5.5, staged per bot type; virtual threads remove the urgency it assumed. |
| Metrics + elastic global intervals on CPU% | **Adopt the metric, replace the signal** — wheel lag beats CPU% (CPU% is polluted by the game client on the same box). §5.6. |
| Interval jitter (1.8–2.2 s) | **Already built** (2–6 s random) — keep. |
| Hybrid event + FSM ("wake on player enter") | **Already built** (`BotMapEntryResponder` + `nudgeSoon`) and it answers the note's own question: it's done outside the bot, on the map-entry event. Extend to full DORMANT wake (§5.3). |
| Warmup stagger on mass spawn | **Already built** (spawn choreography + wave phasing). |
| Spatial partitioning (per-region clock control) | **Superseded** by ObserverTracker tiers — observation is the partition that matters. |
| Distance-based tiers (adjacent maps 5–10 s) | **Already built** (HALO) for movement; §5.3 extends it to the macro brain. |
| Teleport instead of walking for inactive bots | **Already built better** (CoarseExecutor interpolation); §5.4 upgrades it further to wake-at-ETA. |

The note aged well directionally — items 1 and 3 are the two we're actually building. Everything
else either already exists in stronger form or is subsumed.

---

## 7. Game plan

Each phase is independently shippable, independently revertible, and ends with an in-game feel
check + a metrics delta. Estimated sizes: S < half day, M ≈ 1–2 days, L ≈ multi-day.

**Phase 0 — Instrument before touching. (S–M)**
Add tick/lag counters + `!env perf`; capture a JFR baseline + thread dump + heap numbers at
~1,890 bots idle and with a player in Henesys. Record: thread count, CPU% server-only, ticks/s,
movement task executions/s. **(verify)** the autosave-skips-bots assumption (F10) with one log
line. *Exit: a baseline table pasted into this doc.*

**Phase 1 — Free wins, zero behavior change. (S)**
F7 `CharacterStorage` → concurrent collections. F3 `checkMainPlayersOnMap` → `ObserverTracker.isFull`.
F4 gate idle-refresh + driver-fallthrough broadcasts on observation (send one forced refresh on
promotion instead). F2 tier-transition-only rescheduling (interim fix; deleted by Phase 2 anyway —
do it only if Phase 2 won't land the same week). *Exit: idle packet builds/s ≈ 0 on empty world;
no visual change when entering maps.*

**Phase 2 — Kill the per-bot thread. (M-L, the marquee)**
Build `BotTickService` (§5.1). Port `BotSM.startScheduledTask / updateScheduleDelay / nudgeSoon /
stopScheduledTask` to wheel operations behind the same method signatures — bot subclasses don't
change at all. Delete the per-bot scheduler fields. Fold `MethodScheduler` into
`ExecutorServiceManager` and right-size the 100-thread pool while the surface is open (F12, F14
sweep). *Exit: server thread count ~150 (from ~2,040) at identical bot behavior; wheel lag p95
< 250 ms with a player in the busiest map.*

**Phase 3 — DORMANT tier + wake-at-ETA. (L, the scaling law change)**
§5.3 + §5.4: tier field on the bot, park/wake protocol, fast-forward hooks (TrainingBot EXP +
respot; traveler lazy `positionAt`; town bots trivial), movement driver stops self-rescheduling
for T2/T3, combat ticker iterates an `OBSERVED_GRINDERS` subset instead of all grinders. *Exit:
with zero players online, steady-state task executions/s drops from ~5,000 to < 50; walking into
any town still feels alive within ~1 s (nudge sweep + fast-forward). This is the phase to
play-test hard.*

**Phase 4 — De-sleep the FSMs, one type at a time. (M, spread out)**
§5.5 order: ScrollingBot → OPQBot → Tutorial/Social → (optionally) Blackjack/DropGame. Each
conversion is a self-contained PR with a feel check. *Exit: p99 tick occupancy < 100 ms; dispatch
pool optionally shrinkable.*

**Phase 5 — Governor + scale test. (M)**
§5.6 elastic degrade. Then the actual goal: raise wave 8 cohorts toward 3,000–5,000 bots
(`LodMetrics.load` harness + real cohort dials), watch `!env perf`, find the new ceiling, tune
cadences. *Exit: a documented "bots vs. lag" curve on the Legion Go, and a configured safe default.*

Sequencing note: Phases 1–2 don't depend on each other's internals but share files — land 1
first, it's an hour of work. Phase 3 depends on 2 (the wheel is where parking happens). Phase 4
and 5 are independent of each other.

---

## 8. Invariants to preserve (test these after every phase)

1. **Never two concurrent `updateState()` for one bot** (today: per-bot single thread; after: the
   `ticking` CAS guard).
2. **A tick delay is measured from tick *completion*** (fixed-delay semantics — prevents pileups
   behind a slow tick).
3. **Trades are sacred**: never nudge/park/demote a bot in `TRADING` (`nudgeSoon` already guards;
   the wheel and the park path must too).
4. **Spawn choreography timing**: anything visually waiting for a fresh bot schedules past
   `SPAWN_CHOREOGRAPHY_MAX_MS` (`BotGeneration.java:45`) — unchanged by all phases.
5. **Promotion latency ≤ ~1 s** end-to-end (markObservedNow + nudge sweep) — the whole "world
   feels alive" illusion hangs on this number; put it in `!env perf`.
6. **GC-port boundary**: all changes live in our files (`BotSM`, driver edges, new
   `BotTickService`); `BotNavigationManager`/`BotPhysicsEngine`/etc. stay untouched (F9
   consciously deferred).
7. **Wave-phased startup stays wave-phased** — mass spawn stability was hard-won.

---

## 9. Measurement appendix (Windows, one-liners)

```
:: find the server PID
jps -l | findstr Cosmic

:: thread count + states (before/after Phase 2 headline number)
jcmd <pid> Thread.print | findstr /c:"java.lang.Thread.State" | find /c /v ""

:: heap actuals vs -Xmx
jcmd <pid> GC.heap_info

:: 60s flight recording for CPU hotspots (open in JDK Mission Control)
jcmd <pid> JFR.start duration=60s filename=fable-baseline.jfr

:: virtual-thread pinning check during Phase 2 rollout (add to launch.bat temporarily)
java -Djdk.tracePinnedThreads=short -Xmx2048m -Dwz-path=wz -jar target\Cosmic.jar
```

In-game: `!gcmove lod` (tier snapshot), `!gcmove lod train` (grinder phases), and the new
`!env perf` from Phase 0.

---

## 10. RESULTS (2026-07-04, one day later)

Phases 0, 1, 2, 3a, 4a (waitFor + pilots), and 5a (governor) shipped on
`feature/fable-bot-scaling` and were live-tested by the owner on the Legion Go.
Measured via `!env perf`, jar run (`launch.bat`, `-Xmx3g`), one player online:

| Metric | Old arch (pre-branch, ~1.9k bots) | New arch @ 1,571 bots | New arch @ **6,571 bots** |
|---|---|---|---|
| Platform threads | ~2,040 | **137** | **164** |
| Wheel/macro dispatch lag avg | n/a (per-bot threads) | 53ms | **56ms** |
| Governor throttle | n/a | x1.00 | **x1.00 (never engaged)** |
| Combat sweep (all grinders) | n/a | 0-1ms / 787 | **2ms, max 17ms / 5,000** |
| Movement ticks/s | ~4,800 @ 1.9k bots | 1,062 | 7,714 (≈1/s per idle bot) |
| Idle packet builds/s (empty maps) | ~190 | ~0 (only observed maps) | ~0 |
| Heap used | ~1.2GB / 2GB | 958MB / 3GB | **1,428MB / 3GB (46%)** |

The scaling-law change is confirmed: **4.2x the bots cost +3ms of lag, +27 threads,
+470MB heap (≈94KB/bot marginal), and zero governor intervention.** The 3-5k goal
is exceeded at 6.5k with headroom; naive heap extrapolation puts the 3GB ceiling
around 15k+ loaded bots. The next lever IF the population pushes past ~10k is
parking jobless unobserved movement entries entirely (wake-at-park, §5.4) - at
6.5k the 1s idle heartbeat (~6.5k wakeups/s across 8 threads) is measured as
non-problematic, so it stays unbuilt by the doc's own rule: no complexity the
numbers don't demand.

Deferred with data (build only when measurements ask): true DORMANT park +
wake-at-ETA travel, combat-ticker observed-subset (sweep is 2ms at 5k grinders),
JQ/blackjack fast-forward cosmetics, remaining de-sleep conversions
(Tutorial/Social/OPQ/Blackjack/DropGame - harmless on virtual threads).

*Fable 5 · 2026-07-03, results appended 2026-07-04 · First audit in `Documents/Fable/`.*
