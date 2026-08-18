# Artificial Player Framework Architecture

Last updated: 2026-07-04 (tick/concurrency sections rewritten for the BotTickService architecture, merge 71921182)
Covers: `soloMapling/ArtificialPlayer/` core (bot identity, lifecycle, state machine, type system, decoration) and how the supporting subsystems plug into it. For subsystem deep dives see `Claude Summary.txt`; for the FM/economy pipeline see `FreeMarket and ItemPool Architecture.txt`.

---

## 1. What a Bot Is

A bot is a **real `client.Character` object** registered in the server's channel and world player storage, exactly like a player-controlled character. There is no separate "bot entity" at the network level — other clients receive the same spawn/movement/chat packets they would for a human. The artificial layer lives entirely in:

- a **shared fake `Client`** that all bots borrow for packet plumbing, and
- a **`BotSM` state machine** attached per bot via `CharacterStorage`.

### Identity

- Every bot is loaded from the same **base character, CID 2** ("Base Bot Character" row in the DB) via `Character.loadCharFromDB(2, getBotClient(), false)`.
- It is then re-identified in memory: `setID(BOT_BASE_ID + counter)`, a random IGN from the real-MapleStory name pool (`FMShopDescGen.getRandomCharacterIGN()`), and `setFame(botId)` as a debug marker.
- The ID counter (`BotGeneration.currentBotCount`) is an `AtomicInteger` starting at 100 — bots spawn in parallel, and a plain int would let two threads grab the same ID and silently overwrite each other in storage.
- A special **Console bot (ID 999)** exists for the MapleMessengerConsole operator channel (see `Logging and Observability.md`).
- `BotHelpers.isBot(chr)` is the universal "is this a bot?" check used everywhere (event filtering, social systems, equip checker).

### The shared bot Client

`BotClientHandler` captures the `Client` of the **first real player who logs in** after server start (`createBotClient`), then forcibly disconnects that player 2 seconds later with a "please relog" notice. That orphaned-but-live `Client` becomes the shared `botClient` every bot uses. After the disconnect, `environmentLoadStartup()` is scheduled — this is what kicks off world population.

This is the source of the documented caveat: *the first player to log in after a fresh boot must relog once (~5s detour)*. There is currently no workaround.

---

## 2. Spawn Lifecycle

`BotGeneration.createBot(pos, map)` is the single entry point:

```
createBot(pos, map)
  1. loadCharFromDB(2, botClient)          - clone the base character
  2. assign botId / IGN / fame
  3. addBotToServer()                      - channel storage + world player storage
  4. placeBotOnMap()                       - setMap/setPosition/setStance(5), map.addPlayer
  5. setBotVariables(bot)                  - SYNCHRONOUS decoration (tier, level, body,
                                             QuickEquip outfit) - in-memory cache lookups,
                                             microseconds; the bot arrives fully dressed
  6. runAsync(playSpawnChoreography)       - arrival animation on a virtual thread;
                                             createBot returns in ~20ms
```

### Spawn choreography

`playSpawnChoreography` makes the spawn look like a real player arriving:

- 500–1200 ms after map placement: **portal drop-down** plays (plus ~1.5 s portal lag inside `botEnterPortalDropDown`)
- 50% roll: 1000–1500 ms later, a **micro turn-around to the left** (bots default to facing right, so this evens the left/right distribution)
- Ordering is strict (turn must never overlap drop-down packets), preserved by running the whole sequence as one sequential task.

`SPAWN_CHOREOGRAPHY_MAX_MS = 7000` bounds the worst case. **Anything that must visually wait for a bot to finish arriving schedules past this constant**: the first FSM tick (7–10 s, randomized to stagger batches), FM shop opening, filler chair sits, blackjack-player facing.

Two related helpers:

- `warpBotToLocation(fakechar, pos, map)` — same placement + choreography but **blocking** (~2.5–6 s). Use when downstream actions must not race the animation (OPQ map transitions, GM warp commands). Callers should already be on a pooled executor.
- `createBotPollReadiness(position, mapId)` — createBot + a 100 ms poll loop (3 s max) until the bot is visible in channel storage. Registration is synchronous, so the poll is normally a no-op fallback. Used by shop spawning.

### Despawn

`removeBotFromServer(fakechar)`: remove from map, channel storage, world player storage, and `CharacterStorage.removeActiveBot`. GM command: `!bot dc <cid>`.

---

## 3. BotSM — the Bot State Machine

`BotSM` (abstract, implements `EventSubscriber`) is the base every bot type extends.

### States

```
IDLE -> RUNNING -> (PAUSE) -> TRADING -> FINISHED -> IDLE
```

- **IDLE** — waiting; transitions to RUNNING when `running == true` and the bot is logged in (`checkRunningOnline`).
- **RUNNING** — the live state. Each tick: adjusts tick speed (`checkPrioritySpeed`), sends an idle-standing update packet, and checks for an accepted trade partner -> TRADING. Concrete bot types override `updateState()` and layer their own sub-FSMs on top of this skeleton.
- **PAUSE** — defined but the auto-pause transition is currently commented out; resumes to RUNNING when a real player is on the map.
- **TRADING** — delegates ticks to the 9-state `BotTradeSM` sub-machine until the trade completes/cancels, then cleans up and returns to RUNNING.
- **FINISHED** — teardown: unregisters from the tick wheel, resets interactors, returns to IDLE.

### Tick model (BotTickService — since 2026-07, merge 71921182)

**No bot owns a thread.** All macro ticks ride one central wheel, `soloMapling/server/BotTickService.java`:

- One driver task (on the shared scheduled pool) scans the wheel every ~100 ms and dispatches each due bot's `updateState()` onto a **virtual thread**.
- The old `scheduleWithFixedDelay` semantics are preserved exactly: never two concurrent ticks for one bot (per-entry CAS guard), and the next delay is measured from tick *completion*, so ticks can never pile up. `BotTickServiceTest` covers these invariants.
- `startScheduledTask` / `updateScheduleDelay` / `nudgeSoon` / `stopScheduledTask` kept their signatures — they are now wheel operations (register / set period / pull the next due time forward / unregister). A reschedule is two field writes, not a cancel + recreate.
- `nudgeSoon` (fed by `BotMapEntryResponder` on MAP_ENTERED) pulls the next tick to ~150–700 ms so a bot acts almost immediately when it starts sharing a map with a real player. Debounced 1.5 s; never fired at TRADING/FINISHED bots.

| Cadence tier | Delay | When |
|---|---|---|
| Observed | random 2000–6000 ms | a real player can see the bot's map (`ObserverTracker` FULL) |
| High | 2000 ms | explicit (`setPriorityHigh`) |
| Unobserved | 9000–12000 ms jittered (`lowPriorityDelayMs`, overridable per type) | no real player; TrainingBot deepens to 60–120 s while grinding unobserved |

`checkPrioritySpeed()` runs every RUNNING tick: it asks `ObserverTracker` (via `LodCounts`, O(1)) instead of scanning the map's character list, reschedules the observed cadence only on a tier flip, and re-asserts the unobserved cadence each tick so per-phase overrides take effect promptly.

A **governor** inside the wheel stretches unobserved cadences (×1.5 up to ×4) if average dispatch lag stays above ~1 s for 5 s, and relaxes symmetrically on recovery. Nudges are never throttled — promotion responsiveness is sacred.

**Waiting without sleeping:** FSM code never calls `Thread.sleep`. Pacing the bot's own next action = `waitFor(ms)` / `waitForRandom(lo,hi)` + set the next state + return (the wheel skips the bot until the wait passes). A delayed side-effect or scripted choreography = `BotTiming.after(...)` / `BotTiming.chain()` — full decision table in `BotTiming.java`'s header.

First tick after spawn is delayed `SPAWN_CHOREOGRAPHY_MAX_MS + random(0–3000 ms)` (see `manuallyStartBot`) so bots never act mid-arrival-animation.

### Per-bot members

Every `BotSM` instance carries its own:

- `BotTradeHandler` + lazily created `BotTradeSM`, `BotTradeInventory` (sells), `BotTradeWants` (buys)
- `BotDialogueHandler` — YAML dialogue loading/selection for `dialoguePath`
- `BotInteractorsHandler` — tracks the player currently engaging the bot ("respondant")
- `BotEventBuffer` (capacity 100) — events from the EventBus are buffered, not processed inline; bots drain them in their own tick via `processQueuedEvents()`
- `BotDebugHandler` + `dprint()` gated debug output

### Event subscription

`BotSM.matchesFilter(event)` filters by world + channel + map, so a bot only buffers events happening around it. Concrete types override `handleEvent` (e.g. ScrollingBot reacting to scroll results). `stopScheduledTask()` unsubscribes from the EventBus and clears any chalkboard.

---

## 4. Type System & Factory

`BotTypeManager.BotType` is an **enum factory**: each constant overrides `createAndSetBot(Character)`, which instantiates the concrete `BotSM` subclass and registers it in `CharacterStorage.addActiveBot(id, bot)`.

19 constants (18 production personalities + `TEST_ATTACK_BOT`, a dev/test combat harness): `DICE_BOT, TUTORIAL_BOT, FM_BOT, SCROLL_BOT, SELLING_MERCHANT_BOT, BUYING_MERCHANT_BOT, NX_MERCHANT_BOT, GACHA_BOT, HENESYS_BOT, HENESYS_JQ_BOT, GAME_ZONE_HOST_BOT, BLACKJACK_DEALER, DROP_GAME_BOT, OPQ_BOT, SOCIAL_BOT, TOWN_WANDERER_BOT, TEST_ATTACK_BOT, TRAINING_BOT, FOLLOWER_BOT`. (`TOWN_WANDERER_BOT` = generic non-Henesys town roamer; `FOLLOWER_BOT` = grinds alongside a recruiting player — both added in v0.3.)

Key operations:

- `manuallyStartBot` / `manuallyStopBot` — flip `running` and start/stop the scheduler.
- `convertBotType(fakechar, type)` — stop, re-wrap the same Character in a new BotSM subclass, restart. This is how HenesysBot <-> HenesysJQBot conversions and the Wave 6 scroll-bot conversions work: **the Character persists, the brain is swapped**.
- `setAndStartBots(botIds, type)` — batch assign + start; the standard call after `EnvironmentManager` spawns a batch on a platform.

`CharacterStorage` (singleton, ConcurrentHashMaps) is the global registry mapping bot ID -> `BotSM`, and also tracks players in dialogue, trade inquirers, and hidden bots. `getBotById(cid)` is how command handlers and managers reach a bot's state machine.

---

## 5. Decoration Pipeline (how a bot gets its look)

All equip metadata comes from **`EquipMetadataCache`** — a one-time WZ scan run during `Server.init()` (parallel with other WZ-derived data), indexing reqLevel/reqJob/gender/cash for every equip. After init, decoration never touches WZ files.

```
Phase 1 - at spawn, synchronous (setBotVariables inside createBot):
  tier roll (S/A/B/C/D weighted; demo rates are top-heavy: 30/30/20/10/10)
  level derived from tier
  BotDecorateBody     - face / hair / skin
  QuickEquip.apply    - generic outfit from the small curated GenericEquipPool
                        (clothing > weapon > cap > shoes > cape/gloves;
                         equip chance by tier: S 90% / A 75% / B 55% / C 35% / D 15%)

Phase 2 - deferred (BotDecorationQueue, started after Wave 7):
  categorized queues ("fm", "henesys", "gacha", ...) drained in parallel,
  10 bots per category per 250ms tick, 5s initial delay
  ~50% of bots (FULL_DECORATION_RATE) get the full class-aware pass:
  BotDecorateEquips   - job-path + tier gear via metadata-cache queries
  BotDecorateNX       - cash-shop overlay (20% gate, tier-scaled intensity)
  the other 50% keep their QuickEquip look -> population reads as a mix
  of casual and kitted-out players

Safety net:
  BotEquipChecker     - every 2 minutes, sweeps all bots and re-dresses any
                        found naked (skips mapless bots like the Console bot)
```

Toggles: `QuickEquip.ENABLED`, `BotDecorationQueue.ENABLED`, plus MMC `decoqueue` / `decoratenx` console commands.

---

## 6. Supporting Subsystems (wiring view)

How the rest of `soloMapling/` attaches to a bot:

| Subsystem | Attachment point |
|---|---|
| Movement (`BotMovementSystem/`) | Static command style — `MovementCommands.*(fakechar, ...)` act on any Character. Replay, pathfinding (ground/aware/aerial), portal navigation. `BotSM.interruptMovement()` sets a volatile flag movement loops poll. |
| Trade (`BotTradeSystem/`) | `BotSM.tradeHandler` detects partners; TRADING state runs `BotTradeSM.update()` per tick. |
| Messaging (`BotMessagingSystem/`) | `MessageQueue` singleton (PRIMARY/SECONDARY/TERTIARY queues); `CharacterStorage` is in this package. Chat to a bot is routed via the Dispatcher to the right handler. |
| Events (`server/EventMessageSystem/`) | `EventBus` singleton -> `BotSM.onEvent` -> per-bot `BotEventBuffer` -> drained in tick. |
| Dialogue (`BotDialoguePack/`) | Each subclass sets `dialoguePath`; `BotDialogueHandler` loads the YAML and serves randomized lines with emote/timing/variable substitution. |
| Party (`BotPartySystem/`) | Static `BotPartyCommands.*(fakechar, ...)`; pending invites in `BotPartyQueue` with timeout. |
| Commands (`BotCommandsPack/`) | Static packs (SocialCommands, MegaphoneCommands, DropCommands, WarpCommands, VFXCommands, MapleMessengerCommands, BotAttack) — the verbs bot FSMs compose into behavior. |
| Ambient social | `SocialHotPotatoManager` + `ConversationManager` (singletons) act on *filler/Henesys* bots from outside; they check `isAvailableForAmbientActions()` and in-conversation flags before commandeering a bot. |

---

## 7. Concurrency Summary

- **Central tick wheel** (`BotTickService`): one driver task, due ticks dispatched onto virtual threads; per-bot no-overlap CAS guard; exceptions swallowed per tick so a bot error never kills the wheel. Thread count no longer scales with bot count (~150 platform threads at any population).
- A bot's object is touched by up to four thread families: its macro tick (virtual thread), the shared 500 ms combat ticker (TrainingBot grinders), the `GCMovementDriver` pool (movement callbacks, `nudgeSoon`), and chat-dispatch workers. Cross-thread fields must be volatile or concurrent — see the Fable Audit II hazard list.
- `ExecutorServiceManager` provides all shared pools and `runAsync` (virtual threads) for fire-and-forget work — spawn choreography, environment wave tasks, command dispatch (GM commands never block the client thread). `MethodScheduler` is a thin delegate over the same pools.
- Shared registries (`CharacterStorage`, queues, shop-offer sessions) are `ConcurrentHashMap`-based singletons.
- Environment startup: 9 waves (wave 8 = training grinders, wave 9 = all-town ambient presence), tasks within a wave parallel, wave boundary blocking (see `Environment and World Startup.md`).
- Orchestrated systems (OPQ, ConversationManager) coordinate bots from a manager loop rather than bot-to-bot communication.
- Health readout: `!env perf` (threads, heap, tick rates, wheel lag, governor throttle, combat sweep). Architecture rationale + measured results (scale-tested to 6,571 bots): `Documents/Fable/Done/Fable Audit - 2026-07-03 Bot System Scaling & Optimization.md`.

---

## 8. Adding a New Bot Type (checklist)

1. Create `BotTypes/MyBot.java extends BotSM`; in the constructor call `super(chr)` and set `botType` + `dialoguePath`.
2. Override `updateState()` — either keep the base RUNNING skeleton and add behavior, or run your own sub-FSM enum inside RUNNING (see DropGameBot/TutorialBot for the pattern).
3. Add a `MY_BOT { createAndSetBot(...) }` constant to `BotTypeManager.BotType`.
4. (Optional) Add a dialogue YAML to `BotDialoguePack/` and load lines via `getDialogueHandler()`.
5. (Optional) Add a type-assignment alias in `ArtificialPlayerCommand` (`!bot mybot <cid>`) for in-game testing, and a `help` entry.
6. Spawn it: `EnvironmentManager` wave task (`spawnBotsOnMapOnPlatform` + `setAndStartBots`) for world population, or GM commands for ad hoc testing.
7. If the bot reacts to game events, subscribe via `EventBus` and override `handleEvent` / `matchesFilter`.

Things that are easy to forget:

- First FSM tick must tolerate the bot still being mid-choreography if you start it manually with no delay.
- If your bot leaves its map, use `warpBotToLocation` (blocking) when subsequent steps depend on arrival.
- Clean up in FINISHED — wheel registration, parties, chalkboards, reactors.
- **Never `Thread.sleep`** in `updateState()` or any shared ticker: use `waitFor`/`waitForRandom` for the bot's own pacing, `BotTiming.after`/`chain` for delayed side-effects and choreography.
- Deep-background types can override `lowPriorityDelayMs()` to slow their unobserved cadence (TrainingBot returns 60–120 s while grinding).
- Never create an executor — everything schedules through `BotTickService` / `BotTiming` / `ExecutorServiceManager`.

---

## 9. Known Caveats

- **First-player relog**: the framework needs a real `Client`; the first login after boot is sacrificed (see §1).
- ~~BotSM owns its scheduler~~ — resolved 2026-07: the per-bot executor is gone; all macro ticks ride the central `BotTickService` wheel (see §3). Scale-tested to 6,571 bots at ~164 platform threads.
- **PAUSE state** is mostly vestigial — the empty-map slowdown is handled by tick-delay scaling instead.
- `processMessages()` on BotSM is currently unused (message handling goes through the Dispatcher path).
