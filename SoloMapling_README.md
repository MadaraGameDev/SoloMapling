# SoloMapling

A server-side artificial player framework for MapleStory v83 that populates the game world with hundreds of autonomous bots.

Built on top of [Cosmic](https://github.com/P0nk/Cosmic), a Global MapleStory v83 server emulator.

---

## What is this?

SoloMapling solves the empty-server problem. Private servers feel dead without players, and players don't stay on dead servers. SoloMapling breaks that cycle by filling the world with bots that look, move, trade, chat, and play like real people.

Every bot has its own appearance, equipment, level, personality, and behavioral loop. They walk around towns, set up shops in the Free Market, use Gachapon machines, scroll items, run party quests, host minigames, and respond to real players who interact with them.

From a player's perspective, the server just looks alive.

---

## Core Features

### Living Population
Bots spawn across the world with procedurally generated appearances — face, hair, equipment, and Cash Shop items — assigned by a tier system (S through D rank) so the population looks naturally diverse, not uniform.

### Free Market Economy
Bots open Hired Merchants and Player Shops across all four FM regions (Henesys, Ludibrium, Perion, Elnath). Prices follow an economy model with regional multipliers, entrance-spot premiums, day-of-week fluctuations, and random variance. Shops stock class-appropriate gear, scrolls, potions, and rare items.

### Player Interaction
Bots trade with real players using a 9-state negotiation system that evaluates offers, counter-offers, and knows fair pricing. They respond to chat, join parties, and react to nearby events (level-ups, scrolling successes, megaphones) through an event bus.

### Interactive Games
Beyond population, bots host activities players can participate in:
- **Blackjack** — full card game with Artificial players at the table
- **Drop Games** — item-drop races with prize pools
- **Orbis Party Quest** — 2 Stage Rush PQ simulation with coordinated bot parties

### Modular Architecture
Each bot type is a self-contained state machine that plugs into the framework. Adding a new bot personality means extending one base class and overriding the update loop. Configuration is YAML-driven — dialogue lines, item pools, movement recordings, and pricing all live in data files rather than hardcoded logic.

---

## How It Works

Every bot runs on a scheduled timer that ticks every 2-6 seconds with randomized jitter. When real players are nearby, bots tick faster and become more active. When a map is empty, they slow down to conserve resources.

The framework is purely server-side — no client modifications required. Bots are real character objects in the game engine, indistinguishable from player-controlled characters at the network level.

An environment manager handles world initialization in phases, spawning bots into their designated areas with staggered timing to prevent server strain during startup.

---

## Relationship to Cosmic

[Cosmic](https://github.com/P0nk/Cosmic) handles all the foundational game logic — client networking, packet handling, map loading, NPC scripts, quests, combat, and persistence. SoloMapling is a layer built on top that uses Cosmic's character and map systems to inject autonomous bot behavior.

The SoloMapling code lives primarily in its own package (`soloMapling/`) with minimal modifications to the Cosmic base — mainly hook points for bot registration, command dispatching, and event observation.

---

## Requirements

- Java 21 (Amazon Corretto recommended)
- MySQL 8+
- MapleStory v83 WZ XML files
- Maven for building

## Setup

1. Follow the standard [Cosmic setup](https://github.com/P0nk/Cosmic) for database and client configuration.
2. Configure `config.yaml` with your database credentials.
3. Ensure WZ XML files are in the `wz/` directory.
4. Put SoloMapling on top (or just use the SoloMapling source to begin with if you want).
5. Build: `mvn clean package`
6. Run: `launch.bat` (Windows) or via IDE with main class `net.server.Server`.

The SoloMapling environment starts automatically on server boot and populates the world with bots.
The Only Caveat is that the first "Real" Player that logs in after a fresh server start must re-log in (5 second detour) because the bots need a Client address to use. At the moment with the current SoloMapling framework, there is no workaround for this 5 second detour.

---

## Project Structure

```
src/main/java/
  soloMapling/            <- SoloMapling framework (the new stuff)
    ArtificialPlayer/     <- Bot state machines, types, movement, trade, party, commands
    Casino/               <- Casino chip system
    Environment/          <- World initialization and platform management
    FreeMarket/           <- FM economy simulation
    MapVFX/               <- Reactor-based visual effects
    itemPool/             <- YAML-driven item registry
    server/               <- Utilities, event bus, executor management
    docs/                 <- Architecture notes and design documents
  client/                 <- Cosmic base (client handling)
  net/                    <- Cosmic base (networking)
  server/                 <- Cosmic base (game logic)
  ...                     <- Other Cosmic packages
```

---
