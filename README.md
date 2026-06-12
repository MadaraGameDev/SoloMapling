# SoloMapling

An artificial player simulation framework for MapleStory v83, built on the [Cosmic](https://github.com/P0nk/Cosmic) server emulator. Spawns hundreds of autonomous bots that populate the game world — wandering towns, running shops, playing minigames, chatting, and interacting with real players as if they were real.

The framework is purely server-side — no client modifications required. Bots are real character objects in the game engine, indistinguishable from player-controlled characters at the network level.

## Documentation

| Document | What it covers |
|----------|----------------|
| [Claude Summary](src/main/java/soloMapling/Documents/Claude%20Summary.txt) | Full architecture overview — every subsystem, design patterns, concurrency model |
| [Artificial Player Framework Architecture](src/main/java/soloMapling/Documents/Artificial%20Player%20Framework%20Architecture.md) | Deep dive on the bot framework — identity, lifecycle, state machine, decoration pipeline, extension guide |
| [Dev Commands Cheat Sheet](src/main/java/soloMapling/Documents/Dev%20Commands%20Cheat%20Sheet.md) | All in-game GM dev commands (`!bot`, `!move`, `!env`, `!opq`, …) |
| [FreeMarket and ItemPool Architecture](src/main/java/soloMapling/Documents/FreeMarket%20and%20ItemPool%20Architecture.txt) | Shop generation pipeline, tier/version system, economy engine, haggling |
| [Environment and World Startup](src/main/java/soloMapling/Documents/Environment%20and%20World%20Startup.md) | 7-wave world initialization, spawn choreography, platform system, casino setup |
| [Logging and Observability](src/main/java/soloMapling/Documents/Logging%20and%20Observability.md) | BotLog, debug utilities, the in-game MMC operator console, queue/event monitors |
| [Cosmic README](Cosmic_README.md) | The original readme for the underlying Cosmic server emulator |

## Why

Private servers feel dead without players, and players don't stay on dead servers. SoloMapling breaks that cycle by filling the world with bots that look, move, trade, chat, and play like real people. Every bot has its own appearance, equipment, level, personality, and behavioral loop. From a player's perspective, the server just looks alive.

## What It Does

Bots don't just stand around — they:

- **Populate towns** — Henesys bots wander between Main, Market, Park, and Pet Park. Filler bots sit on benches, stand at shops, and chat with each other. Social bots hold conversations and react to players who approach them.
- **Run the Free Market** — Merchant bots operate Hired Merchants and Player Shops across Henesys, Ludibrium, Perion, and Elnath FM rooms with procedurally generated inventories and dynamic pricing.
- **Host minigames** — Blackjack tables with AI dealers, drop-item racing games with tiered entry fees, dice games, and Gachapon machines with reactor-based prize sprays.
- **Simulate social life** — Ambient chat system (SocialHotPotatoManager) fires off contextual dialogue, emotes, megaphones, and chalkboard messages. Multi-bot conversations (ConversationManager) detect nearby bot clusters and play scripted dialogue with proper timing and emotes.
- **Attempt jump quests** — JQ bots in Pet Park replay pre-recorded movement attempts at weighted difficulty tiers, fail/succeed realistically, chat about their progress, and convert back to town bots when done.
- **Run party quests** — OPQ (Orbis Party Quest) bots recruit, party up, navigate stages, attack cloud reactors, and complete objectives via a centralized orchestrator.
- **Onboard new players** — A Tutorial Bot on Maple Island greets newcomers, offers admin powers (GM level 6, Night Lord, starter gear, NX code), teaches chat mechanics, upgrades their weapon, and escorts them to the exit.
- **Scroll and trade** — Scrolling bots use scrolls on items and react via the event bus. Trade bots handle full 9-state trade lifecycles with fair pricing logic.

## Architecture at a Glance

Everything revolves around **BotSM** (Bot State Machine) — an abstract FSM ticked every 2-6 seconds with adaptive speed (faster when players are nearby). Fifteen bot types extend it, each with their own state machine and behavioral loop. Adding a new bot personality means extending one base class and overriding the update loop. Configuration is YAML-driven — dialogue lines, item pools, movement recordings, and pricing all live in data files rather than hardcoded logic.

### Bot Types

| Bot | What It Does |
|-----|-------------|
| FMBot | Free Market wanderer, shops and trades |
| HenesysBot | Town wanderer across 4 Henesys maps via portals |
| HenesysJQBot | Jump quest attempts with tier-weighted difficulty progression |
| SocialBot | Interactive NPC with dialogue trees and anti-spam escalation |
| ScrollingBot | Scrolls items, reacts to results via EventBus |
| GachaBot | Operates Gachapon with reactor VFX prize sprays |
| DiceBot | Dice-rolling social game |
| TutorialBot | 18-state onboarding flow with admin grant and item trade |
| SellingMerchantBot | Runs Hired Merchants selling items |
| BuyingMerchantBot | Purchases items from players |
| NXMerchantBot | Cash Shop item specialist |
| GameZoneHostBot | Hosts game zone events |
| BlackjackDealerBot | Full blackjack table with AI strategy |
| DropGameBot | 8-state drop racing minigame with tiered loot |
| OPQBot | Orchestrated Orbis Party Quest participant |

### Key Subsystems

- **Movement** — Three modes: replay from 825+ pre-recorded movement files, JGraphT Dijkstra pathfinding (ground/aware/aerial variants), and portal-based cross-map navigation.
- **Decoration** — Procedural appearance generation, driven entirely by an in-memory equip metadata cache (no WZ reads at spawn time). Tier system (S/A/B/C/D) controls gear quality. Bots arrive fully dressed: body + quick generic equips at spawn, full class-aware decoration deferred to a background queue. Three layers: body (face/hair/skin), equips (job-specific), NX overlay (20% gate, tier-scaled intensity).
- **Free Market** — Four FM regions with economy simulation. Regional multipliers, entrance premiums, day-of-week modifiers, procedural shop inventories.
- **Trade** — 9-state sub-FSM handling the full trade lifecycle with fair pricing evaluation.
- **Social Simulation** — SocialHotPotatoManager (ambient actions every 8-20s) + ConversationManager (scripted multi-bot dialogues every 60-120s with spatial cluster detection).
- **Event Bus** — Decoupled pub/sub system. Bots subscribe to game events (LEVEL_UP, SCROLL, MAP_ENTERED, etc.) filtered by world/channel/map.
- **Dialogue** — YAML-driven per-bot dialogue with randomized selection, emote IDs, timing, and variable substitution. 15 dialogue files.
- **Item Pool** — YAML-driven item registry with per-class configs, scroll metadata, upgrade simulation, and weighted selection.
- **Party System** — Full party lifecycle for OPQ and DropGameBot.
- **Environment** — 7-wave phased world startup; waves run their tasks in parallel and block until complete (~650 bots in roughly 10 seconds).

## World Startup (7 Waves)

The world populates in phases to keep startup stable and observable:

1. **Essentials** — Casino NPCs, Tutorial Bot, 10 Henesys wanderers, Henesys FM
2. **FM Buildout** — Ludi FM, merchant bots, gacha bots
3. **Henesys Population** — JQ bots, more wanderers, filler bots; social simulation systems start
4. **Expand FM** — Perion FM, more merchants, Market fillers
5. **Sub-areas** — Elnath FM, Park/Potion Shop/Game Zone fillers + hosts
6. **Specialty** — Blackjack tables, Drop Game + spectators, Pet Park social bots, scroll bot conversions
7. **Late Arrivals** — OPQ lobby bots, final merchant batch

After the waves, the deferred decoration queue and a periodic equip checker start. Bot spawn animations (portal drop-down, turn-around) play asynchronously on virtual threads so mass spawning never blocks on choreography.

## Dev Commands

Nine GM-level-4 command suites for testing and control. All have `help` subcommands.

| Command | Purpose |
|---------|---------|
| `!bot` | Bot lifecycle, type assignment, loot, appearance, party, chat |
| `!move` | Movement, pathfinding, recording, chairs, interrupts |
| `!betafmshop` | Free Market population and shop management |
| `!env` | World init, filler bots, NPCs, platforms, social systems |
| `!fmbot` | FM-bot-specific (mostly stubs) |
| `!tradebot` | Trade FSM driving and inspection |
| `!test` | Reactors, VFX, events, gacha, messenger |
| `!opq` | OPQ orchestration and lifecycle |
| `!reactor` | Reactor inspection and bot-attack testing |

Full reference: [Dev Commands Cheat Sheet](src/main/java/soloMapling/Documents/Dev%20Commands%20Cheat%20Sheet.md)

## Relationship to Cosmic

[Cosmic](https://github.com/P0nk/Cosmic) handles all the foundational game logic — client networking, packet handling, map loading, NPC scripts, quests, combat, and persistence. SoloMapling is a layer built on top that uses Cosmic's character and map systems to inject autonomous bot behavior.

The SoloMapling code lives primarily in its own package (`soloMapling/`) with minimal modifications to the Cosmic base — mainly hook points for bot registration, command dispatching, and event observation. The original Cosmic readme is preserved at [Cosmic_README.md](Cosmic_README.md).

## Requirements & Setup

- Java 21 (Amazon Corretto recommended)
- MySQL 8+
- MapleStory v83 WZ XML files (in `wz/` directory)
- Maven for building

1. Follow the standard [Cosmic setup](https://github.com/P0nk/Cosmic) for database and client configuration.
2. Configure `config.yaml` with your database credentials.
3. Build: `mvn clean package`
4. Run: `launch.bat` (Windows) or via IDE with main class `net.server.Server`.

The SoloMapling environment starts automatically on server boot and populates the world with bots. The first real player that logs in after a fresh server start must re-log (5-second detour) because the bots need a Client address to use. There is currently no workaround for this.

## Project Structure

```
src/main/java/
  soloMapling/            <- SoloMapling framework
    ArtificialPlayer/     <- Bot state machines, types, movement, trade, party, commands
      BotTypes/           <- 15 concrete bot implementations + Blackjack/ and OPQ/ subsystems
      BotMovementSystem/  <- Movement replay, pathfinding, navigation, recording
      BotTradeSystem/     <- 9-state trade FSM, inventory, pricing
      BotMessagingSystem/ <- Async pub/sub message broker, character storage
      BotCommandsPack/    <- 7 command packs (social, megaphone, drop, warp, VFX, messenger, attack)
      BotPartySystem/     <- Party lifecycle management
      BotDecoratorSystem/ <- Procedural appearance generation (body, equips, NX, decoration queue)
      BotDialoguePack/    <- 15 YAML dialogue files + drop game loot pool
    Casino/               <- Casino chip system and WZ patching
    Environment/          <- World initialization, platform system
    FreeMarket/           <- FM economy simulation, shop generation, haggling
    MapVFX/               <- Reactor-based visual effects
    itemPool/             <- YAML-driven item registry, scroll system, equip metadata cache
    server/               <- Utilities, event bus, executor management, NX codes
    Documents/            <- Architecture notes and design documents
  client/                 <- Cosmic base (client handling, GM commands)
  net/                    <- Cosmic base (networking)
  server/                 <- Cosmic base (game logic)
```

## Tech Stack

- **Java 21** (Amazon Corretto) on **Cosmic** MapleStory v83 server emulator
- **Maven** build with fat jar assembly
- **MySQL 8+** for persistence
- **JGraphT** for pathfinding graph algorithms
- **YAML** for all configuration data (dialogue, items, equips, versions)
- Movement replay from pre-recorded binary/CSV packet data
