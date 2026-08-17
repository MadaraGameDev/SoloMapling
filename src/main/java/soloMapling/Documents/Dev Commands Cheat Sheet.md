# SoloMapling Dev Commands Cheat Sheet

In-game GM level 4 commands added for SoloMapling development. All are registered in
`CommandsExecutor.java` and live under `client/command/commands/gm4/`.

Notation:
- `!` is the in-game command prefix.
- `<cid>` = bot character id (from `CharacterStorage` / channel storage).
- `<text>` = arbitrary string (chat / description / etc).
- Every command dispatch is routed through `ExecutorServiceManager.getExecutorService()` so the
  GM's client thread is never blocked.
- Parameter count is what the dispatcher uses to pick a handler — unknown shapes fall through
  to "Invalid command" yellow messages.

---

## `!bot` — ArtificialPlayerCommand

General-purpose bot lifecycle, type assignment, loot, equip, party, and chat/megaphone commands.

### Direct (`!bot <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!bot` command reference in-game. |
| `create` | `BotTypeManager.createBots(c)` — spawn a bot at your position. |
| `dcmap` / `dcallmap` / `dchere` | Disconnect ALL bots on your current map (`removeBotFromServer` each; other maps untouched). Reports the count. |
| `hint` | Show a chat-command hint list `["Hey", "Test", "Cho"]` on your own player. |
| `expirehint` | Expire the current chat-command hint on your player. |
| `testslotinfo` | Dumps your equipped WEAPON/SHOES/CAP/COAT/PANTS/EAR/MEDAL (regular + cash). Debug print. |
| `viewqueue` | Runs `QueueMonitor` — inspects the bot messaging queues. |
| `nxcode` | Creates a complete NX code for the hardcoded name `"GERALTYENNEFER69"`. |
| `spawn <class> <tier\|level> [n]` | Spawn n geared bots of a class (warrior/mage/thief/bowman) at your position — job tier 1–4 or an exact level 10–200. |
| `spawnatk <class> <tier> [n]` | Same as `spawn` but the bots auto-attack (attack-test harness). |
| `trainhere` / `traintest <job> [lv] [n]` | Spawn n TrainingBots of a SPECIFIC job at your position and pin them to grind THIS map (station-here — bypasses map discovery/travel). `<job>` = a name (hermit, nightlord, chiefbandit, shadower, assassin, bandit, fpwizard, ilwizard, cleric, fpmage, ilmage, priest, bishop) or a numeric job id (e.g. 411). Requires a map with mobs (no-op in towns). For live-testing a job's combat + movement flavor (flash-jump, teleport, jump-attack). |
| `grindstyle <cid\|map> <style>` | Force a TrainingBot's grind archetype (`camp`, `patrol`, `roam`, `stack`, or `auto` = back to the profile-resolved natural style); `map` applies to every training bot on your current map. Live grinds swap strategy on the next combat tick; idle bots apply it at their next grind. For testing archetype behavior on arbitrary maps (ROAM on HHG, CAMP on a huge sparse map, STACK on the slime tree, PATROL on a wide spread field). |

### Numbered — `!bot <cmd> <cid>` (operates on bot `<cid>`)

#### Bot Type Assignment
| Command | What it does |
|---|---|
| `dicebot` | Assign `DICE_BOT` type. |
| `tutorialbot` / `tutbot` | Assign `TUTORIAL_BOT`. |
| `setfmbot` / `fmbot` | Assign `FM_BOT`. |
| `scrollingbot` / `scrollbot` | Assign `SCROLL_BOT`. |
| `merchantbot` / `sellingbot` | Assign `SELLING_MERCHANT_BOT`. |
| `buyingbot` | Assign `BUYING_MERCHANT_BOT`. |
| `nxbot` / `nxmerchantbot` | Assign `NX_MERCHANT_BOT`. |
| `gachabot` | Assign `GACHA_BOT`. |
| `henesysbot` | Assign `HENESYS_BOT`. |
| `gamezonehost` / `gzhbot` | Assign `GAME_ZONE_HOST_BOT`. |
| `bjbot` / `bjdealerbot` / `blackjackbot` | Assign `BLACKJACK_DEALER`. |
| `dropgamebot` / `dgbot` | Assign `DROP_GAME_BOT`. |
| `opqbot` | Assign `OPQ_BOT`. |
| `jqbot` / `henesysjqbot` | Assign `HENESYS_JQ_BOT`. |
| `trainbot` / `trainingbot` | Assign `TRAINING_BOT` (roaming grinder). |
| `followbot` / `followerbot` | Assign `FOLLOWER_BOT` — follows YOU (the commanding GM) across maps. Talk to it on a mob map and pick "Train here with me!" to station it as a TrainingBot. |
| `breaknow` | Force a grinding TrainingBot to take its rest break on the next tick (walks to a safe ledge, 75% sits in a chair, 15% puts up a "brb" chalkboard, resumes after 60–100s). |
| `restspot` | Dump the ranked rest-spot candidates for a TrainingBot (each reachable ledge with its clearance-to-nearest-spawn, spawn-free flag, and score) plus reachability — shows *why* a given break spot won. Use to calibrate the finder against a specific map. |

#### Lifecycle & Movement
| Command | What it does |
|---|---|
| `manualstart` | `manuallyStartBot` — kick the bot's FSM tick loop. |
| `manualstop` | `manuallyStopBot` — halt it. |
| `convertfmbot` | Convert existing bot to `FM_BOT`. |
| `convertscrollbot` | Convert existing bot to `SCROLL_BOT`. |
| `warphere` / `warpbothere` | Warp the bot to your current map + position. |
| `enterportal` | `botWarpMapOnPortal(fakechar)` — bot enters nearest portal (no hardcoded IDs). |
| `disconnect` / `dc` / `remove` | `manuallyStopBot` + `removeBotFromServer(fakechar)`. |
| `faceme` | `botFaceTowardsPoint` — bot turns to face your position. |
| `nudge` | Nudge bot 20 units via `SocialHotPotatoManager.testNudge`. |
| `nudgeoverlap` | Nudge bot away from nearest overlapping bot. |

#### Loot
| Command | What it does |
|---|---|
| `meso` | Runs `testDropCommands` — currently `botThrowToOwnerMeso(fakechar, 100, player)`. |
| `loot` | `botLoot(fakechar, 1000)` — loot at feet. |
| `lootwide` | `botLoot(fakechar, 9000)` — loot ~2.5 character widths. |
| `lootlocation` | Loot at your player position (range default). |
| `lootownitems` | Loot only items dropped by this bot, 12000 range. |
| `loottargetsitems` | Loot items owned by your player, 9000 range. |

#### Combat
| Command | What it does |
|---|---|
| `attack` | `BotAttackDriver.forceSingle(fakechar)` — force the bot's **single-target** attack (ignores cooldown). Reports the hit/kill or why it missed (reach/no target). |
| `attackaoe` | `BotAttackDriver.forceAoe(fakechar)` — force the bot's **sustained AoE** attack (the mob spell it spams, e.g. Bishop's Shining Ray), or report "`<job>` has no AoE attack" when that slot is empty (e.g. Assassin, plain Bandit). |
| `attackult` | `BotAttackDriver.forceUltimate(fakechar)` — force the bot's **full-map ultimate** (Dragon Roar / Genesis / Blizzard / Meteor Shower), or report "`<job>` has no ultimate attack". AUTO throttles this to ~25s; while it cools the bot mobs with its sustained AoE. |
| `buff` | Force the bot's job buff (ignores cooldown). |

#### Appearance
| Command | What it does |
|---|---|
| `randombody` | `BotDecorateBody.decorateBotBody`. |
| `randomequips` | `BotDecorateEquips.decorateBotEquips`. |
| `decoratenx` / `nxdecorate` | `BotDecorateNX.applyForced` — apply NX cash shop appearance layer. |

#### Party Commands
| Command | What it does |
|---|---|
| `makeparty` | `BotPartyCommands.botMakeParty(fakechar)` — bot creates a party. |
| `leaveparty` | `BotPartyCommands.botLeaveParty(fakechar)`. |
| `acceptinv` / `acceptparty` | `BotPartyCommands.botAcceptPartyInvite(fakechar)`. |
| `rejectinv` / `rejectparty` | `BotPartyCommands.botRejectPartyInvite(fakechar)`. |
| `inviteparty` / `botinviteme` | `BotPartyCommands.botInvitePlayer(fakechar, player)` — bot invites you. |
| `checkpartyqueue` | Check if bot has a pending party invite in `BotPartyQueue`. |

### Mass — `!bot <cmd> <n> <n2>` (name must contain "mass")
| Command | What it does |
|---|---|
| `masscreate` | `massCreateBots(n, n2, c)` — create bots in a range. |
| `massfmbot` | Mass convert/create FM bots in the id range. |
| `massmanualstart` | Start bots in the id range. |
| `massmanualstop` | Stop bots in the id range. |

### Two numbered — `!bot <cmd> <cid> <int>`
| Command | What it does |
|---|---|
| `equip` | `EquipBot(fakechar, itemId)`. |
| `sethair` | `fakechar.setHair(id)`. |
| `bjplayer` / `bjplayerbot` / `bjaddplayer` | Add a player bot (second int) to the blackjack table run by dealer bot (first int). |
| `castbuff` | Bot casts skill `<skillId>` visually (animation only). |
| `givebuff` | Bot casts skill `<skillId>` on **you** (real buff applied). |
| `extbuff` | Like `givebuff` but with a 10-minute duration. |
| `setlevel` | Set the bot's level (1–200). |
| `setclass` / `setjob` | Set the bot's job by job id. |

### String + int + string — `!bot <cmd> <cid> <text>`
| Command | What it does |
|---|---|
| `chat` | `BotSpeak(fakechar, text)`. |
| `bubbletype` | Types a hardcoded string into a chat bubble char-by-char at 150ms. |
| `playrecording` / `playrec` | Play a movement recording by name on the bot. |
| `smega` | `MegaphoneCommands.BotSuperMegaphone(fakechar, text)`. |
| `avatarsmega` | `MegaphoneCommands.BotAvatarMegaphone(fakechar, text)`. |
| `tv` | `MegaphoneCommands.BotMapleTV(fakechar, text)`. |
| `tvpartner` / `tvp` | `MegaphoneCommands.BotMapleTVPartner(fakechar, text, yourPlayer)`. |

---

## `!move` — BotMoveCommand

Bot movement, pathfinding, recording/playback, chairs, interrupts.

### Direct (`!move <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!move` command reference in-game. |
| `stoprecording` | Stops the movement packet recorder. |
| `getfhy` | Prints your player's foothold id. |
| `whereami` / `getpos` / `getposition` / `getmypos` / `findfh` | Prints your `findFootHoldId` + `Point` (both console + yellow message). |
| `testdc` | (commented out — no-op) |
| `closesttpportal` | `TestMethods.findClosestTPPortal(c)`. |

### Numbered — `!move <cmd> <cid>`
| Command | What it does |
|---|---|
| `idle` | `BotIdleStandingUpdate`. |
| `fall` | `botFallDownPacket`. |
| `map` | Warps bot to mapId **910000010** (hardcoded FM entrance). |
| `testallexitdoors` | `testBotExitAllRooms`. |
| `testalldoors` | `testBotEnterExitAllRooms`. |
| `pathfinder` | `pathFinderBeta` to your player position. |
| `pathaware` | `pathFinderAware` to hardcoded Point(1937, 334). Dev test for aware pathfinding. |
| `pathaware2` | `pathFinderAware` to hardcoded Point(3889, 454). Dev test endpoint. |
| `pathfinderaerial` / `aerialpathfinder` | `testAerialPathFinder` to your player position. Elevated terrain pathfinding. |
| `pathpoints` | `fmRoomPathWithStops` to your player position. |
| `sitchair` | Sit on chair id `3010071`. |
| `unsitchair` / `cancelchair` | Cancel chair. |
| `getbotpos` | `System.out` bot's `Point`. |
| `interrupt` / `stop` | `interruptBotMovement(fakechar)` — immediately halt current movement. |
| `testinterrupt` | `testInterruptPathfinder` — starts pathfinder to player's position, interrupts after 2 seconds. |
| `faceme` | `botFaceTowardsPoint` — bot turns to face your position. |
| `wander` | Bot strolls random legal spots across the whole map (`BotWanderSystem`). |
| `wandernear` | Bot loiters near you (radius 150). |
| `wanderstop` | Stop wandering. |

### Bot + string — `!move <cmd> <cid> <moveName>`
| Command | What it does |
|---|---|
| `bot` / `botmove` | `testBotMove(fakechar, moveName)` — play a recorded movement. |
| `botmod` / `botmovemod` | `testBotMoveMod` — play a movement mod (e.g. `"rightleft20"`). |
| `botinject` / `botmoveinject` | (commented out) |
| `botmovecsv` | `testBotMoveCSV`. |
| `botpath` | `pathTest(fakechar)` (ignores the string). |
| `botmovetest` | `botmovetest(fakechar, input)`. |
| `movetoportal` | `MovementCommands.moveToPortal(fakechar, portalId)` — pathfind to portal by ID. |
| `movetoportalenter` | `MovementCommands.moveToPortalAndEnter(fakechar, portalId)` — pathfind to portal and enter it. |

### Bot + string + point — `!move <cmd> <cid> <moveName> <x> <y>`
| Command | What it does |
|---|---|
| `botstop` / `botmovestop` | `testBotMoveStopPoint(fakechar, moveName, new Point(x, y))`. |
| `pathfinderaerial` / `aerialpathfinder` | `testAerialPathFinder(fakechar, point)` — aerial pathfind to exact coordinates. |

### Two strings — `!move <cmd> <recordingName>`
| Command | What it does |
|---|---|
| `startrecording` | Begin recording movement packets under the given name on current map. |

---

## `!betafmshop` — ArtificialFreeMarketCommand

Free-market population / test commands.

### No args
| Args | What it does |
|---|---|
| *(none)* | `populateFreeMarketSpot(c)` — spawn a hired merchant at your current spot. |

### Single arg
| Args | What it does |
|---|---|
| `help` | Print the full `!betafmshop` command reference in-game. |
| `destroy` | `destroyAllShops(c)` — channel close-all-merchants (note: known flaky in source). |
| `<region>` | `populateFreeMarketRegion(region)` — region = `henesys` / `ludi` / `perion` / `elnath`. |

### String + int — `!betafmshop <cmd> <cid>`
| Command | What it does |
|---|---|
| `store` / `permit` | `BotPlayerStorePermit(fakechar)` — give this bot a PlayerShop with generated inventory. |

### Two strings — `!betafmshop <cmd> <any>`
| Command | What it does |
|---|---|
| `botshop` | `createBotShopAtLocation(yourPos, yourMapId)` — create a bot + player-shop at your feet. |

---

## `!test` — TestDevCommand

Grab-bag: reactors / VFX, event bus tests, gacha pop, messenger invites, MMC.

### Direct (`!test <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!test` command reference in-game. |
| `addmmc` | `addMMC(c)` — add MapleMessengerConsole. |
| `testconvo` | Trigger a conversation via `ConversationManager` on your current map. |
| `getallreactors` | Dump all reactor data on current map. |
| `getnearestreactor` | Print nearest reactor to you. |
| `spawnreactor` | Spawn a test reactor at your position. |
| `congrats` | Publish a `LEVEL_UP` for **you** so nearby town/social bots (and GachaBots) congratulate — no need to gain real EXP. |
| `flavor` | Force a random idle expression (emote / buff-flex / skill-swing) on **every bot on your map**. |

### String + string — `!test <cmd> <arg>`
| Command | What it does |
|---|---|
| `flavor <emote\|buff\|swing>` | Force that specific bot-flavor expression on every bot on your map (unknown arg → random). |

### String + int — `!test <cmd> <cid>`
| Command | What it does |
|---|---|
| `botmminvite` / `botmminv` | `sendMessengerInviteComplete(fakechar, yourPlayer)`. |
| `botmmtyping` | `botTypingStatus(fakechar, true)`. |
| `botgacha` | `gachaPop(fakechar, …)` with gacha filler list + item `1082223` as prize. |
| `testevent` | `eventUnitTests(yourPlayer)` — publishes a `LevelUpEvent` to `EventBus`. |

### String + int + int — `!test <cmd> <cid> <reactorId>`
| Command | What it does |
|---|---|
| `destroyreactor` | `deleteReactor(yourMap, reactorId)`. |
| `hitreactor` | `hitReactor(yourMap, reactorId)`. |
| `3hitreactor` / `breakreactor` | `threeHitReactor(yourMap, reactorId)`. |
| `sprayreactor` | Spray a hardcoded drop list `[1082223, 2022179, 1050018, 1082149, 1032026]` from the reactor as `fakechar`. |

### String + int + string — `!test <cmd> <cid> <text>`
| Command | What it does |
|---|---|
| `chat` | `BotSpeak(fakechar, text)`. |
| `botmmchat` | `botSendMessengerChat(fakechar, text)`. |

---

## `!fmbot` — FMBotCommand

Scaffolded FM-bot-specific dispatcher. **Almost entirely empty stubs right now** — dispatcher is
wired up for single/string+int/string+string/string+int+int/string+int+string variants but the
only live switch case is the `chat` fall-through at the bottom (also missing a `break`).

| Form | Active cases |
|---|---|
| `!fmbot help` | Print the full `!fmbot` command reference in-game. |
| `!fmbot <cid> chat <text>` | `BotSpeak(fakechar, text)` (falls through to "Invalid command Two Object"). |
| everything else | Prints "Invalid command". |

This is the placeholder for future FM-bot-only commands.

---

## `!tradebot` — TradeBotTestCommand

Drives `BotTradeCommands` for the 9-state trade sub-FSM.

### Direct (`!tradebot <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!tradebot` command reference in-game. |

### Numbered — `!tradebot <cmd> <cid>`
| Command | What it does |
|---|---|
| `confirm` | `confirmTrade(fakechar)`. |
| `checkconfirmed` / `checklocked` / `checkpartnerlocked` / `checkpartnerlock` | `isPartnerLocked(fakechar)`. |
| `accept` / `accepttrade` | `acceptTradeInvite(fakechar)`. |
| `cancel` / `decline` / `declinetrade` | `declineTradeInvite(fakechar)`. |
| `request` / `requesttrade` | `sendTradeRequestToPlayer(fakechar, yourPlayer)`. |
| `readmeso` | `readMeso(fakechar)`. |
| `readpmeso` / `readpartnermeso` | `readPartnerMeso(fakechar)`. |
| `generaltest` | Reads partner fame + level. |
| `getoccupiedslots` | `getOccupiedTradeSlots(fakechar)`. |
| `getfreeslots` / `getemptyslots` | `getEmptyTradeSlots(fakechar)`. |

### Two ints — `!tradebot <cmd> <cid> <n>`
| Command | What it does |
|---|---|
| `meso` / `addmeso` / `setmeso` | `setMeso(fakechar, n)`. |
| `addequip` | `addCleanEquipToTrade(fakechar, n, 1)`. |
| `additem` | `addItemToTrade(fakechar, n, 1, 1)`. |
| `swapitem` | `swapScamEquipToTrade(fakechar, n, 1)`. |
| `readitem` | `readItemInPartnerSlot(fakechar, n)`. |

### String + int + string — `!tradebot <cmd> <cid> <text>`
| Command | What it does |
|---|---|
| `chat` / `tradechat` | `writeTradeChat(fakechar, text)`. |

---

## `!env` — EnvironmentCommand

Environment startup + platform / foothold / portal introspection + NPC spawning.

### Direct (`!env <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!env` command reference in-game. |
| `perf` | **Bot-system health readout** (`BotPerfStats.report`): threads, heap, macro/movement tick rates, idle broadcasts, nudges, combat sweep, tick-wheel size/lag/throttle, bot + tier counts. Run twice a few seconds apart for live rates. |
| `attacktest` | Spawn tier-1 per-class attack test bots on Henesys Hunting Ground 1 (`!env attacktest <2\|3\|4>` for other tiers). |
| `scattertest` / `trainscatter` | Scatter 30 training bots on your current map's ground spots (`!env scattertest <n>` for other counts — the scale-test harness). |
| `grindprofile` / `spotdump` | Dump the grind-spot scoring profile for your current map (spot list, density scores, claims). |
| `townpresence reload` / `tp reload` | Re-read `waves.town_presence.towns` from `EnvironmentPopulation.yaml` (apply YAML edits without a server restart). Same file as `!env population reload`. |
| `townpresence spawn [map n lo hi]` | Spawn a town's ambient SocialBot cohort at anchor-weighted spots; no args = spawn every town in the YAML (stationed + wanderers). |
| `townpresence here [count]` | Dry-run: spawn `count` (default 10) stationed SocialBots on your current map. |
| `townpresence wander [map n lo hi]` | Spawn roaming TownWandererBots (no map = your current map, default 5) — the generic non-Henesys roamers. |
| `townpresence weights [topN]` | Dump the anchor-weighted ledge weights (NPC/portal pull + shape profile + overrides) for your current map — tune → look → tune. |
| `townpresence mark [mapId]` | Pin your current spot (stand where you want a bot) — appends to the machine-owned `TownPins.txt`, merged into that map's pins. |
| `chatter` | Readout of the ambient bot-to-bot conversation system (BotChatterSystem): tuning knobs, exchanges loaded from `TownChatterDialogue.yaml`, and how many bots are mid-chat right now. |
| `chatter reload` | Re-read `TownChatterDialogue.yaml` from disk (apply chatter-line edits without a server restart). |
| `chatter fire` | Force every eligible participant (SocialBot / TownWandererBot) on your current map to try starting a chat now — skips the chance/cooldown rolls; still needs an adjacent, available partner. |
| `loadenv` | `environmentLoadStartup()` — kick the multi-phase world init. |
| `spawnfmbots` | `spawnBotsInFMEntrance()`. |
| `spawnmerchantbots` / `spawnmerchbots` | `spawnMerchBotsInFMEntrance()`. |
| `spawnhenesysbots` | `spawnHenesysBots()`. |
| `spawngachabots` | `spawnGachaBotsHenesys()`. |
| `getmap` | Print your current `mapId`. |
| `getportal` | Prints closest portal's id, name, type, target map, script, state, position. |
| `getplat` / `getplatform` / `getcurrentplat` / `getcurrentplatform` | `getCurrentPlatform(player)`. |
| `getallplatforms` | `getAvailablePlatformIds(mapId)`. |
| `getallmainplatforms` | `getMainPlatformIds(mapId)`. |
| `spawnhenefillers` | Spawn Henesys filler bots. |
| `spawnmarketfillers` | Spawn Henesys Market filler bots. |
| `spawnparkfillers` | Spawn Henesys Park filler bots. |
| `spawngamezonefillers` | Spawn Game Zone filler bots. |
| `spawnpotshopfillers` | Spawn Potion Shop filler bots. |
| `spawnallfillers` | Spawn all filler bots (all areas). |
| `convertscrollbots` | Convert random filler bots to scroll bots. |
| `spawnopqbots` | Spawn OPQ bots in lobby. |
| `spawngzhbots` | Spawn Game Zone Host bots. |
| `spawnbjtables` | Spawn Blackjack tables. |
| `starthotpotato` | Start Social Hot Potato manager. |
| `stophotpotato` | Stop Social Hot Potato manager. |
| `startconvo` | Start Conversation Manager. |
| `stopconvo` | Stop Conversation Manager. |
| `spawncasinonpc` | Spawns the casino chip NPC at your position (`CasinoChipConfig.CASINO_NPC_ID`). |
| `spawnrpsnpc` | Spawns the Rock-Paper-Scissors NPC (id 9000019) at your position. |
| `spawncasinonpcs` | `spawnCasinoNpcs()` — spawns casino NPCs on map 100000203. |

### String + int — `!env <cmd> <count>`
| Command | What it does |
|---|---|
| `fillerbot` | Spawn N filler bots at hardcoded X coords (-548 to 568), y=154 on your map. |
| `fillerboty` | Spawn N filler bots at hardcoded Y coords (-112 to 245), y=-127 on your map. |
| `attacktest <tier>` | Attack test bots at job tier 2/3/4. |
| `scattertest <n>` / `trainscatter <n>` | Scatter N training bots on your current map (scale testing; 5,000 verified). |

### Two strings — `!env <cmd> <platformId>`
| Command | What it does |
|---|---|
| `getallcharsonplatform` / `getcharsonplatform` | `getAllCharsOnPlatform(mapId, platformId)`. |

### String + int + string — `!env <cmd> <cid> <platformId>`
| Command | What it does |
|---|---|
| `chat` | `BotSpeak(fakechar, str)`. |
| `platformshuffle` | `botMoveToPlatformAnyUnoccupiedSpot(fakechar, platformId)`. |
| `platformshufflerandom` | Moves bot to a random main platform on its current map. |

---

## `!opq` — OPQCommands

Orbis Party Quest bot orchestration. Full lifecycle management for the OPQ simulation system.

### Direct (`!opq <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full OPQ command reference in-game. |
| `status` | Print orchestrator phase, pqActive flag, stage completion flags, bot count. |
| `start` | `resetForNewRun()` — sets pqActive=true, phase=RECRUITMENT. |
| `reset` | `shutdownRun()` — wipes all per-run state, pqActive=false. |
| `list` | Lists all registered OPQBots with cid, state, map, cloud/box assignments, task status. |
| `stage1done` | Marks every bot's task complete (stage 1 flag flips on next orchestrator tick). |
| `stage2done` | Marks every bot's task complete (stage 2 flag flips on next orchestrator tick). |
| `killall` | Unregisters and stops every OPQ bot. |

### Numbered — `!opq <cmd> <int>`
| Command | What it does |
|---|---|
| `spawn <n>` | Spawn N OPQBots at your position (creates bot + registers with orchestrator + starts FSM). |
| `dump <cid>` | Print full bot state: map, position, party, cloud/box assignment, task status, FSM state. |
| `complete <cid>` | Mark one bot's task complete in shared context. |
| `kill <cid>` | Unregister + stop one bot. |

### Two strings — `!opq <cmd> <string>`
| Command | What it does |
|---|---|
| `phase <PHASE>` | Force shared-context phase. Valid: `INACTIVE`, `RECRUITMENT`, `IN_PARTY_IDLE`, `STAGE_1`, `STAGE_2`, `EXIT`. |
| `warp <target>` | Warp yourself to an OPQ map. Targets: `lobby`, `s1`, `tower`, `s2`, `exit`. |
| `forcestateall <STATE>` | Force every registered OPQ bot into a state (uses `OPQBotState` enum). |

### String + int + string — `!opq <cmd> <cid> <string>`
| Command | What it does |
|---|---|
| `assign <cid> <cloud\|box>` | Auto-assign a cloud reactor or stage-2 platform target to the bot. |
| `forcestate <cid> <STATE>` | Force one bot into a specific `OPQBotState`. |
| `move <cid> <platformId>` | Test platform navigation — send bot to a named platform. |

---

## `!reactor` — ReactorCommands

Reactor inspection, hitting, and bot-attack testing. Used heavily for OPQ cloud reactor development.

### Direct (`!reactor <cmd>`)
| Command | What it does |
|---|---|
| `help` | Print the full `!reactor` command reference in-game. |
| `list` | List all alive reactors on your map (oid, id, state, position). |
| `near` | Print nearest reactor's oid. |
| `hitnear` | Hit the nearest reactor (1 hit). |
| `breaknear` | Break the nearest reactor (4 hits = `OPQConstants.MAX_REACTOR_HITS`). |
| `dump` | `getAllReactorsData` — dumps full reactor data to debug log. |

### Numbered — `!reactor <cmd> <cid>`
| Command | What it does |
|---|---|
| `botattack` | `BotAttack.basicSwing(fakechar)` — bot plays a basic 1-handed swing animation. |
| `botlistreactors` | Lists alive reactors on the bot's map. |

### Two numbered — `!reactor <cmd> <cid> <oid>`
| Command | What it does |
|---|---|
| `hit` | `hitReactor` — hit reactor by oid on bot's map. |
| `break` | Break reactor by oid (4 hits). |
| `destroy` | `deleteReactor` — remove reactor by oid from bot's map. |
| `botbreak` | Bot pathfinds to reactor, plays swing animation, hits 4 times with staggered timing. Full bot-attacks-reactor sequence. |

---

## `!gcmove` — GCMoveCommand

GCMoveSystem dynamic movement (physics/nav-graph based, distinct from the recorded-path `!move` suite) + LOD tier inspection.

### Direct
| Command | What it does |
|---|---|
| `help` | Print the full `!gcmove` reference in-game. |
| `bake` | Bake + report the nav graph for your current map (proof point). |
| `ropecheck` | Per-rope top-exit probe on your current map: old strict vs widened `findTopExitLanding`, the recovered landing Y, and which failure mode (FM-1 below / FM-2 above / FM-3 off-axis) the old probe hit. Tolerance calibration for uneven ladder heads. |
| `lod` / `lod stats` | Report dynamic-movement load: bots/maps/job state per LOD tier. |
| `lod load <n>` | Enable + fidget up to n idle bots (LOD load test). |
| `lod unload` | Release the LOD load-test bots. |
| `lod train` | Training-bot LOD overview: tier/phase counts + FULL/HALO maps. |

### Per-bot — `!gcmove <cmd> <botId> [...]`
| Command | What it does |
|---|---|
| `move <botId> <x> <y>` | Bot walks/jumps/climbs to (x, y). |
| `here <botId>` | Bot walks to you (same map). |
| `come <botId>` | Bot travels across maps to your spot. |
| `follow <botId>` | Bot dynamically follows you. |
| `travel <botId> <map>` | Bot travels to another map (chained portal hops). |
| `route <botId> <map>` | Print the portal-hop route without moving. |
| `jump <botId>` | Bot hops in place. |
| `fj <botId> [scale]` | Fire one flash jump at an exact scale (0.2–1.0, default 1) toward the bot's facing and report measured travel — calibrates `GCMovementSkills.FJ_TRAVEL_PX`. |
| `turn <botId>` | Bot turns around. |
| `duck <botId>` | Bot crouches briefly. |
| `fidget <botId> [off]` | Toggle idle auto-fidget. |
| `stop <botId>` | Stop the bot. |
| `off <botId>` | Remove the bot from dynamic control. |
| `status <botId>` | Dynamic state of the bot. |

---

## Known footguns while reading these

Also, several handlers compare with `"Test"` (capital T) but the dispatcher lowercases input —
those branches are dead (`TestDevCommand`, `TradeBotTestCommand`, `FMBotCommand`, `EnvironmentCommand`).

**Reference item IDs:**

`!bot equip`:
1702150 NX sword Mercury · 1302063 Flaming Katana · 1002577 Pickpocket Pilfer ·
1050018 Blue Sauna Robe · 1072344 Facestompers · 1082223 Stormcaster Gloves.

`!tradebot`: 1022060 white rac mask · 1002357 (hat) · 2340000 white scroll.
