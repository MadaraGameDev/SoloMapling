# Logging and Observability

Last updated: 2026-06-12
How to see what the bots are doing: file logs, debug prints, the in-game operator console, and the monitor utilities.

---

## 1. BotLog.txt — the framework log file

`soloMapling/BotLogger.java` is the framework's file logger. `log(String)` appends a timestamped line to **`BotLog.txt` in the working directory** (the file is gitignored). Globally toggled by the `log` constant in `BotLogger` (compile-time, default `true`).

What lands in it: BotSM state transitions (IDLE->RUNNING, ->FINISHED, scheduler shutdowns), SocialHotPotatoManager activity, BotEquipChecker sweeps, and anything else that imports `BotLogger.log`.

Tail it live while the server runs:

```powershell
powershell -Command "Get-Content -Path 'BotLog.txt' -Wait"
```

## 2. Console / debug prints

- **`DebugUtilities.debugprint(Object...)`** — timestamped stdout debug print. Double-gated: a hardcoded `printDebug` flag (default **off**) *and* a check that the JVM is running under a debugger (jdwp). Flip the flag and run under a debugger to see them. `fmt("Hello {}, {} items", a, b)` is the companion string formatter used throughout.
- **`BotSM.dprint(msg)`** — per-bot debug print prefixed `[BotType:Name]`, gated by its own hardcoded flag (default off), routed through `debugprint`.
- **`BotDebugHandler`** — attached to every bot; `handleDebugPrints` runs at the top of every FSM tick and drives per-bot watch output (see MMC `botlog` below).
- **Startup logs** — `EnvironmentManager` prints per-wave timing/bot counts and the final `=== All bots initialized: N bots in X.Xs ===` line to stdout; `BotEquipChecker` and `BotDecorationQueue` print their lifecycle lines as well. These are always on.

## 3. MapleMessengerConsole (MMC) — in-game operator console

MMC turns a Maple Messenger window into an operator console: a fake "Console" bot (ID 999) joins your messenger, you type commands into the chat, and output comes back as messenger messages. Setup: `!test addmmc`, then type `connect` in the messenger window. Commands are plain words (no `!` prefix), `name: command` parsed after the colon.

| Command | What it does |
|---|---|
| `connect` / `disconnect` | Open/close your console session (most commands require being connected) |
| `botlog <id\|name>` | Add a bot to the **watch set** — its activity streams to all connected console users |
| `botunlog <id\|name>` | Remove it from the watch set |
| `resetbotlog` | Clear the watch set |
| `chalkboard <id\|name>` | Toggle a chalkboard over a bot's head |
| `setallchalk` / `removeallchalk` | Chalkboards on/off for every active bot |
| `cmd <gm command>` | Run any GM chat command (e.g. `cmd !bot create`) through the console |
| `decoqueue enable\|disable\|start\|stop\|status` | Control/inspect the deferred decoration queue |
| `decoratenx ...` | Drive NX decoration manually |

The watch set (`botlog`) is the most useful tool for live debugging a single misbehaving bot without drowning in global logs.

## 4. Queue & event monitors

- **`QueueMonitor`** (`BotMessagingSystem/`) — inspects the three message queues (PRIMARY/SECONDARY/TERTIARY). In-game: `!bot viewqueue`. `QueueCleaner` handles stale-entry maintenance.
- **`EventStore`** (`server/EventMessageSystem/`) — rolling history of the last ~5,000 game events published on the EventBus; `EventMonitorTest` contains the inspection/test utilities. In-game smoke test: `!test testevent` publishes a LevelUpEvent.
- **OPQ**: `!opq status`, `!opq list`, `!opq dump <cid>` give orchestrator phase, per-bot FSM state, and assignment detail.
- **Platforms**: `!env getcharsonplatform <plat>` shows who the spawner thinks is standing where.

## 5. Quick reference — "I want to see…"

| Goal | Tool |
|---|---|
| Everything the framework logged, live | tail `BotLog.txt` |
| One specific bot's behavior | MMC `botlog <name>` |
| Why a bot looks naked/undecorated | MMC `decoqueue status`, then `!bot randomequips <cid>`; BotEquipChecker output in BotLog |
| Startup performance | stdout wave timing lines |
| Message queue backlog | `!bot viewqueue` |
| Recent game events | EventStore (~5k history) via EventMonitorTest |
| Trade FSM state | `!tradebot` inspection commands |
| OPQ run state | `!opq status` / `list` / `dump` |
