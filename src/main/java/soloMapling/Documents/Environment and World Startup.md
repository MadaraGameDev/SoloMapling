# Environment and World Startup

Last updated: 2026-06-12
Covers: `soloMapling/Environment/` (4 files) — world initialization, the platform system, and the NPC/casino setup it drives. For the bot lifecycle inside each spawn, see `Artificial Player Framework Architecture.md`.

---

## 1. How startup is triggered

The environment cannot start until a real `Client` exists for bots to borrow. The chain:

```
first real player logs in
  -> BotClientHandler.createBotClient captures their Client
  -> 2s later that player is force-disconnected ("please relog")
  -> 1s later environmentLoadStartup() runs
```

You can also trigger it manually with `!env loadenv` (GM level 4).

## 2. The 7-wave startup

`EnvironmentManager.environmentLoadStartup()` populates the world in 7 waves. Each wave is a list of tasks submitted **in parallel**; the wave **blocks until all its tasks complete** (`runWave` -> `runPhase`), then the next wave starts. Every wave logs its elapsed time and how many bots it spawned; the run ends with a total (typically **~650 bots in roughly 10 seconds**).

| Wave | Name | Contents |
|---|---|---|
| 1 | Essentials | Casino NPCs (Game Zone map), Tutorial Bot (Maple Island), 10 Henesys wanderers, Henesys FM region, FM entrance bots (5/5/5 across platforms m1/m2/m5) |
| 2 | FM buildout | Ludi FM, FM entrance batch, merchant bots on m1/m2/m5 (selling/buying/NX), Gachapon bots |
| 3 | Henesys population | JQ bots (Pet Park), wanderers (10 Main / 10 Market / 5 social), Henesys fillers; **then** SocialHotPotatoManager + ConversationManager start |
| 4 | Expand FM + Henesys Market | Perion FM, FM entrance batch, second merchant batch, Market fillers |
| 5 | Henesys sub-areas | Elnath FM, wanderers (10/10/10/4), Park / Potion Shop / Game Zone fillers, Game Zone host bots |
| 6 | Specialty | Blackjack tables, Drop Game Bot + Drop Game spectators (Potion Shop), Pet Park social bots, random filler -> scroll bot conversions |
| 7 | Late arrivals | OPQ lobby bots, final merchant batch |
| — | After waves | `BotDecorationQueue.start()` (deferred full decoration) + `BotEquipChecker.start()` (2-min naked-bot sweep) |

Notes:

- FM room population is fire-and-forget internally, so its bots may be attributed to a later wave's count in the logs.
- Prerequisite data (`EquipMetadataCache`, `DesirableEquipList`) is **not** loaded here — it loads during `Server.init()` with the rest of the WZ-derived data, guaranteed ready before any player can trigger startup.
- Spawn arrival animations (drop-down, turn-around) run on virtual threads per bot, so wave duration reflects actual spawn work, not choreography (see the framework doc, §2).

## 3. Platform system

Bots must stand on valid footholds and not stack on top of each other. The platform system provides that:

- **`Platform`** — a named walkable segment of a map (e.g. `"m1"`, `"m4_social"`) with min/max X bounds and Y data; FLAT or SLOPED.
- **`PlatformParser`** — builds platforms by parsing **movement-recording CSV files** (`movementDataPackets/map<mapId>/<name>.csv`), extracting the (x, y) trail a recorded walk covered. Y variance over 50 px marks a platform SLOPED. Platforms are therefore authored by *recording a walk* across the desired area, not by editing data files.
- **`PlatformSpawner`** — picks spawn points on a platform: `findUnoccupiedPoint(s)` enforce a minimum spacing of 30 px between occupants with ~30% positional variance, falling back to gap-detection between existing occupants.

`EnvironmentManager` composes these: `spawnBotsOnMapOnPlatform(count, mapId, platformName)` finds unoccupied points, creates bots there, and returns their IDs for `setAndStartBots(ids, type)`.

Matching introspection commands: `!env getplat`, `!env getallplatforms`, `!env getallmainplatforms`, `!env getcharsonplatform <plat>`, `!env platformshuffle <cid> <plat>`.

Tolerances used when deciding whether a character is "on" a platform: Y within 10 px, X within bounds +20 px.

## 4. NPC spawning & the casino

`NpcSpawner` (in `soloMapling/server/`) places any NPC at an arbitrary position at runtime — no WZ map edits needed.

Casino setup (Wave 1, Game Zone map 100000203):

- `CasinoChipConfig` defines chip item denominations, exchange rates, and the NPC IDs.
- The **Casino Chip Exchange NPC** (9201066) runs `scripts/npc/9000055.js`, which opens **shop 9999001** — buy/sell chips at equal prices for lossless meso<->chip exchange. The shop row must exist in the `shops`/`shopitems` DB tables (SQL provided in `database/`).
- `WzXmlPatcher` patches WZ XML so the casino NPC data exists client-side.
- Dev commands: `!env spawncasinonpc` (at your feet), `!env spawncasinonpcs` (proper map), `!env spawnrpsnpc` (Rock-Paper-Scissors NPC 9000019).

## 5. Re-running / partial population

Every wave's spawn task has a standalone `!env` command (`spawnfmbots`, `spawnhenesysbots`, `spawnallfillers`, `spawnbjtables`, `spawnopqbots`, …) so any slice of the world can be populated or re-populated ad hoc without a full `loadenv`. Social systems are independently controllable via `starthotpotato` / `stophotpotato` / `startconvo` / `stopconvo`.

Caution: `loadenv` is not idempotent — running it twice doubles the population. There is no "despawn all" wave; use `!bot massmanualstop` + `!bot dc` ranges if needed.
