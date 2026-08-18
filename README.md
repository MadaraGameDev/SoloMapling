# SoloMapling

An artificial player simulation **plugin** for MapleStory v83 hosts. It spawns hundreds of autonomous bots that populate the game world — wandering towns, running shops, playing minigames, chatting, and interacting with real players as if they were real.

This repository is **only the plugin**. It does not contain a game server. Hosts implement [maple-extension-api](https://github.com/zmzeng/maple-extension-api) (`dev.maple.extension:extension-api` **0.2.0**) and load this jar from their `plugins/` directory.

The framework is purely server-side — no client modifications required. Bots are real character objects in the host engine, indistinguishable from player-controlled characters at the network level.

## Showcase

### Full demo (video)

| Part 1 | Part 2 |
|:------:|:------:|
| [![SoloMapling — Full Demo Part 1](https://img.youtube.com/vi/9extNWkkTeM/hqdefault.jpg)](https://youtu.be/9extNWkkTeM) | [![SoloMapling — Full Demo Part 2](https://img.youtube.com/vi/PMfWSx-m_7o/hqdefault.jpg)](https://youtu.be/PMfWSx-m_7o) |
| **[Watch Part 1](https://youtu.be/9extNWkkTeM)** | **[Watch Part 2](https://youtu.be/PMfWSx-m_7o)** |

- **[Image Showcase](docs/SHOWCASE.md)** — Free Market, Blackjack, Drop Game, dialogue, Henesys bots, OPQ Rush, and more
- **[How to Record Movement](docs/Movement-Recording.md)** — capturing movement recordings and the pathfinding system
- **[Dev Diary](docs/DEV-DIARY.md)** — work-in-progress shots from development

## Documentation

| Document | What it covers |
|----------|----------------|
| [Claude Summary](docs/Claude%20Summary.txt) | Full architecture overview — every subsystem, design patterns, concurrency model |
| [Artificial Player Framework Architecture](docs/Artificial%20Player%20Framework%20Architecture.md) | Identity, lifecycle, state machine, decoration pipeline, extension guide |
| [Dev Commands Cheat Sheet](docs/Dev%20Commands%20Cheat%20Sheet.md) | In-game GM commands (`!bot`, `!move`, `!env`, `!opq`, …) |
| [FreeMarket and ItemPool Architecture](docs/FreeMarket%20and%20ItemPool%20Architecture.txt) | Shop generation, tier/version system, economy engine, haggling |
| [Environment and World Startup](docs/Environment%20and%20World%20Startup.md) | 7-wave world initialization, spawn choreography, platform system |
| [Population config (YAML)](src/main/java/soloMapling/Environment/CONFIG.md) | `EnvironmentPopulation.yaml` — wave counts, `scale`, TrainingBot hubs, town ambient |
| [Movement Recording & Pathfinding](docs/Movement-Recording.md) | How to record bot movement |
| [Logging and Observability](docs/Logging%20and%20Observability.md) | BotLog, debug utilities, MMC operator console |

## Why

Private servers feel dead without players, and players don't stay on dead servers. SoloMapling fills the world with bots that look, move, trade, chat, and play like real people. Every bot has its own appearance, equipment, level, personality, and behavioral loop.

## What it does

- **Populate towns** — Henesys bots wander between Main, Market, Park, and Pet Park. Filler bots sit on benches, stand at shops, and chat. Social bots hold conversations and react to players who approach them.
- **Run the Free Market** — Merchant bots operate Hired Merchants and Player Shops across Henesys, Ludibrium, Perion, and Elnath FM rooms with procedurally generated inventories and dynamic pricing.
- **Host minigames** — Blackjack tables with AI dealers, drop-item racing games, dice games, and Gachapon machines.
- **Simulate social life** — Ambient chat, emotes, megaphones, chalkboard messages, and scripted multi-bot conversations.
- **Attempt jump quests** — JQ bots replay pre-recorded movement at weighted difficulty tiers.
- **Run party quests** — OPQ bots recruit, party up, navigate stages, and complete objectives.
- **Onboard new players** — A Tutorial Bot on Maple Island greets newcomers.
- **Scroll and trade** — Scrolling bots and a 9-state trade lifecycle with fair pricing.

## Architecture at a glance

Everything revolves around **BotSM** (Bot State Machine) — an abstract FSM ticked every 2–6 seconds with adaptive speed (faster when players are nearby). Fifteen bot types extend it. Configuration is YAML-driven — dialogue, item pools, movement recordings, and pricing live in data files.

### Bot types

| Bot | What it does |
|-----|-------------|
| FMBot | Free Market wanderer, shops and trades |
| HenesysBot | Town wanderer across 4 Henesys maps via portals |
| HenesysJQBot | Jump quest attempts with tier-weighted difficulty |
| SocialBot | Interactive NPC with dialogue trees |
| ScrollingBot | Scrolls items, reacts via EventBus |
| GachaBot | Operates Gachapon with reactor VFX |
| DiceBot | Dice-rolling social game |
| TutorialBot | 18-state onboarding flow |
| SellingMerchantBot | Runs Hired Merchants |
| BuyingMerchantBot | Purchases items from players |
| NXMerchantBot | Cash Shop item specialist |
| GameZoneHostBot | Hosts game zone events |
| BlackjackDealerBot | Full blackjack table |
| DropGameBot | Drop racing minigame |
| OPQBot | Orbis Party Quest participant |

## Host integration

```
MapleStory v83 client
        │ GMS protocol
        ▼
Host (maps / characters / packets / DB)
  implements maple-extension-api HostRuntime
        │ ServiceLoader + plugins/*.jar
        ▼
SoloMapling plugin
  SoloMaplingExtension · BotSM · Environment waves · FM · Training
```

| Piece | Role |
|-------|------|
| [maple-extension-api](https://github.com/zmzeng/maple-extension-api) `0.2.0` | Host-neutral SPI: `ServerExtension`, `HostRuntime`, config, event bus, command registry, `CharacterClassifier` |
| Host `ExtensionLoader` | Scans `plugins/*.jar` and publishes gameplay `HostEvent`s |
| This repo | SoloMapling framework packaged as one shaded jar; SPI entry `soloMapling.plugin.SoloMaplingExtension` |

The current compile-time game types (`org.gms.client.Character`, …) come from **[BeiDou-Server](https://github.com/BeiDouMS/BeiDou-Server)** (`gms-server`, provided). A host must still provide engine hooks (headless bot client, map/trade/merchant awareness, timeout skip) and publish:

- `CharacterMapEnteredEvent`
- `CharacterChatEvent`
- `TradeInviteEvent` (invite **to** an artificial character)

Artificial character ids are **not** hard-coded in the host. On `onLoad` this plugin registers a `CharacterClassifier` (`id > 20000 || id == 999`). The host queries `HostRuntime.isArtificialCharacter(id)`.

**Load order (BeiDou):** Spring Boot starts → host builds `HostRuntime` and loads plugins → `Server.init()` → `notifyServerReady()` → plugin `onServerReady()` → optional `EnvironmentManager` waves.

**Host config (BeiDou `application.yml`):**

```yaml
solomapling:
  plugins-enabled: true
  plugins-dir: plugins
  spawn-bots-on-startup: true
  # population-config: /optional/path/to/EnvironmentPopulation.yaml
```

## Build & install

```bash
# 1. Neutral SPI
cd /path/to/maple-extension-api && mvn install

# 2. Host compile classpath (thin gms-server jar)
cd /path/to/BeiDou-Server
mvn -pl gms-server -am install -DskipTests
mvn -pl gms-server package -DskipTests

# 3. This plugin
cd /path/to/SoloMapling
mvn package -DskipTests
cp target/solomapling-plugin-*-SNAPSHOT.jar /path/to/BeiDou-Server/gms-server/plugins/

# 4. Run the host (BeiDou cwd must be gms-server)
cd /path/to/BeiDou-Server/gms-server
java -Xmx4g -Dspring.config.location=src/main/resources/application.yml \
  -jar target/BeiDou-boot.jar
```

Notes:

- SPI file: `META-INF/services/dev.maple.extension.api.ServerExtension`
- Bot clone template on BeiDou is the character named **`fmbot`** (Flyway migration), not a hard-coded character id
- GM ≥ 4 commands via HostCommandRegistry: `!smping`, `!env`, `!bot`, `!move`, `!fmbot`, `!gcmove`
- Population YAML: `src/main/java/soloMapling/Environment/EnvironmentPopulation.yaml` (see [CONFIG.md](src/main/java/soloMapling/Environment/CONFIG.md))

```bash
mvn test -Dtest=EnvironmentPopulationConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Project structure

```
src/main/java/soloMapling/
  plugin/               <- SoloMaplingExtension (SPI)
  command/              <- GM command adapters for HostCommandRegistry
  ArtificialPlayer/     <- BotSM, types, movement, trade, party, decoration
  Casino/ Environment/ FreeMarket/ MapVFX/ itemPool/ server/
src/main/resources/
  META-INF/services/    <- ServerExtension registration
docs/                   <- architecture notes and showcase assets
```

## Tech stack

- **Java 21**
- **Maven** (shaded plugin jar: yamlbeans + JGraphT)
- **maple-extension-api 0.2.0** (provided)
- **BeiDou `gms-server`** (provided) for current engine types
- **YAML** for dialogue, items, equips, population
- Movement replay from pre-recorded packet data

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).
