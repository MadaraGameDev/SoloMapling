package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot.OPQBotState;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext.OPQPhase;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * OPQ developer commands. Piece-by-piece testing of the OPQ bot state machine.
 *
 *   !opq help                           - this list
 *   !opq status                         - orchestrator phase, flags, counts
 *   !opq start                          - resetForNewRun (pqActive=true, phase=RECRUITMENT)
 *   !opq reset                          - wipe all per-run state
 *   !opq list                           - list OPQBots and their states
 *   !opq stage1done / stage2done        - mark every bot task-complete (orchestrator flips flag)
 *   !opq killall                        - unregister and stop every OPQ bot
 *
 *   !opq spawn <n>                      - spawn N OPQBots at your position
 *   !opq dump <cid>                     - print one bot's full state
 *   !opq complete <cid>                 - mark one bot's task complete
 *   !opq kill <cid>                     - unregister + stop one bot
 *
 *   !opq phase <PHASE>                  - force shared-context phase
 *   !opq warp <lobby|s1|tower|s2|exit>  - warp yourself to the named OPQ map
 *   !opq forcestateall <STATE>          - force every OPQ bot into a state
 *
 *   !opq assign <cid> <cloud|box>       - auto-assign reactor (cloud) or platform (box)
 *   !opq forcestate <cid> <STATE>       - force one bot into a state
 *   !opq move <cid> <platformId>        - test platform navigation
 */
public class OPQCommands extends Command {
    {
        setDescription("OPQ bot dev commands. Try: !opq help");
    }

    private static Character player;

    @Override
    public void execute(Client c, String[] params) {
        player = c.getPlayer();
        if (params.length == 0) {
            ExecutorServiceManager.getExecutorService().execute(() -> {
                player.yellowMessage("No Command Parameter Found for OPQ command. Try !opq help");
            });
            return;
        }
        if (params.length == 1) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                handleDirectCommand(params[0], c);
            });
            return;
        }
        if (params.length == 2) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                if (isInteger(params[1])) {
                    int commandNum = Integer.parseInt(params[1]);
                    handleNumberedCommand(params[0], commandNum, c);
                } else if (!isInteger(params[0]) && !isInteger(params[1])) {
                    handleTwoStringCommand(params[0], params[1], c);
                } else {
                    player.yellowMessage("Second input not an integer");
                }
            });
            return;
        }
        if (params.length == 3) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                if (isInteger(params[1])) {
                    int commandNum = Integer.parseInt(params[1]);
                    String commandString = params[2];
                    handleStringIntStringCommand(params[0], commandNum, commandString, c);
                } else {
                    player.yellowMessage("Second input not an integer");
                }
            });
            return;
        }
    }

    // =========================================================================
    // 1-arg
    // =========================================================================

    public static void handleDirectCommand(String input, Client c) {
        switch (input.toLowerCase()) {
            case "help":
                printHelp();
                break;
            case "status":
                printStatus();
                break;
            case "start":
                OPQOrchestrator.getInstance().resetForNewRun();
                player.yellowMessage("OPQ run started — pqActive=true, phase=RECRUITMENT");
                break;
            case "reset":
                OPQOrchestrator.getInstance().shutdownRun();
                player.yellowMessage("OPQ context reset — pqActive=false");
                break;
            case "list":
                printList();
                break;
            case "stage1done":
                markAllTaskComplete();
                player.yellowMessage("All OPQBots marked task-complete. Stage 1 flag flips on next tick.");
                break;
            case "stage2done":
                markAllTaskComplete();
                player.yellowMessage("All OPQBots marked task-complete. Stage 2 flag flips on next tick.");
                break;
            case "killall":
                int killed = killAllOPQBots();
                player.yellowMessage("Killed " + killed + " OPQBots");
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command. Try !opq help");
                break;
        }
    }

    // =========================================================================
    // 2-arg: string + int
    // =========================================================================

    public static void handleNumberedCommand(String input, int input2, Client c) {
        // Bot-less commands handled before the bot lookup.
        switch (input.toLowerCase()) {
            case "spawn":
                spawnBots(input2, c);
                return;
        }

        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }
        OPQBot bot = findOPQBotForChar(fakechar);
        if (bot == null) {
            player.yellowMessage("Char " + fakechar.getName() + " is not an OPQBot");
            return;
        }

        switch (input.toLowerCase()) {
            case "dump":
                printDump(bot);
                break;
            case "complete":
                OPQOrchestrator.getInstance().getSharedContext().markTaskComplete(bot.getChr().getId());
                player.yellowMessage("Marked " + bot.getChr().getName() + " task-complete");
                break;
            case "kill":
                BotTypeManager.manuallyStopBot(bot.getChr());
                OPQOrchestrator.getInstance().unregisterBot(bot);
                player.yellowMessage("Killed " + bot.getChr().getName());
                break;
            default:
                player.yellowMessage("Invalid command - NumberedCommand");
                break;
        }
    }

    // =========================================================================
    // 2-arg: string + string
    // =========================================================================

    public static void handleTwoStringCommand(String input, String input2, Client c) {
        switch (input.toLowerCase()) {
            case "phase":
                try {
                    OPQPhase p = OPQPhase.valueOf(input2.toUpperCase());
                    OPQOrchestrator.getInstance().mirrorPhase(p);
                    player.yellowMessage("Phase forced -> " + p);
                } catch (IllegalArgumentException e) {
                    player.yellowMessage("Unknown phase: " + input2
                            + ". Valid: INACTIVE, RECRUITMENT, IN_PARTY_IDLE, STAGE_1, STAGE_2, EXIT");
                }
                break;
            case "warp":
                warpSelf(input2, c);
                break;
            case "forcestateall":
                forceStateAll(input2);
                break;
            default:
                player.yellowMessage("Invalid command - TwoStringCommand");
                break;
        }
    }

    // =========================================================================
    // 3-arg: string + int + string
    // =========================================================================

    public static void handleStringIntStringCommand(String input, int input2, String input3, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }
        OPQBot bot = findOPQBotForChar(fakechar);
        if (bot == null) {
            player.yellowMessage("Char " + fakechar.getName() + " is not an OPQBot");
            return;
        }

        switch (input.toLowerCase()) {
            case "assign":
                assignTarget(bot, input3);
                break;
            case "forcestate":
                try {
                    OPQBotState state = OPQBotState.valueOf(input3.toUpperCase());
                    bot.setStateForDebug(state);
                    player.yellowMessage("Forced " + bot.getChr().getName() + " -> " + state);
                } catch (IllegalArgumentException e) {
                    player.yellowMessage("Unknown state: " + input3);
                }
                break;
            case "move":
                EnvironmentManager.botMoveToPlatformAnyUnoccupiedSpot(bot.getChr(), input3);
                player.yellowMessage("Sent " + bot.getChr().getName() + " toward platform " + input3);
                break;
            default:
                player.yellowMessage("Invalid command - StringIntStringCommand");
                break;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void printHelp() {
        player.yellowMessage("---- OPQ Commands ----");
        player.yellowMessage("!opq help / status / list");
        player.yellowMessage("!opq start                      - begin run (pqActive=true)");
        player.yellowMessage("!opq reset                      - wipe per-run state");
        player.yellowMessage("!opq stage1done / stage2done    - mark every bot task-complete");
        player.yellowMessage("!opq killall                    - kill every OPQ bot");
        player.yellowMessage("!opq spawn <n>                  - spawn N OPQBots at your position");
        player.yellowMessage("!opq dump <cid>                 - print one bot's full state");
        player.yellowMessage("!opq complete <cid>             - mark one bot task-complete");
        player.yellowMessage("!opq kill <cid>                 - unregister + stop one bot");
        player.yellowMessage("!opq phase <PHASE>              - force shared-context phase");
        player.yellowMessage("!opq warp <lobby|s1|tower|s2|exit>");
        player.yellowMessage("!opq forcestateall <STATE>      - force every bot's state");
        player.yellowMessage("!opq assign <cid> <cloud|box>   - auto-assign cloud reactor or stage-2 box");
        player.yellowMessage("!opq forcestate <cid> <STATE>   - force one bot's state");
        player.yellowMessage("!opq move <cid> <platformId>    - test platform navigation");
    }

    private static void printStatus() {
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        player.yellowMessage("---- OPQ Status ----");
        player.yellowMessage("phase=" + ctx.getCurrentPhase()
                + " pqActive=" + ctx.isPqActive()
                + " stage1Done=" + ctx.isStage1Complete()
                + " stage2Done=" + ctx.isStage2Complete());
        int count = 0;
        for (OPQBot bot : snapshotOPQBots()) count++;
        player.yellowMessage("registeredBots=" + count);
    }

    private static void printList() {
        player.yellowMessage("---- OPQBots ----");
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        for (OPQBot bot : snapshotOPQBots()) {
            int id = bot.getChr().getId();
            Integer cloud = ctx.getMyCloudAssignment(id);
            String  box   = ctx.getMyPlatformAssignment(id);
            player.yellowMessage(bot.getChr().getName()
                    + " cid=" + id
                    + " state=" + bot.getOPQBotState()
                    + " map=" + bot.getChr().getMapId()
                    + " cloudOid=" + cloud
                    + " box=" + box
                    + " done=" + ctx.isMyTaskComplete(id));
        }
    }

    private static void printDump(OPQBot bot) {
        Character chr = bot.getChr();
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        player.yellowMessage("---- " + chr.getName() + " (cid=" + chr.getId() + ") ----");
        player.yellowMessage("mapId=" + chr.getMapId()
                + " pos=" + chr.getPosition()
                + " party=" + (chr.getParty() != null));
        player.yellowMessage("cloudOid=" + ctx.getMyCloudAssignment(chr.getId())
                + " box=" + ctx.getMyPlatformAssignment(chr.getId())
                + " taskDone=" + ctx.isMyTaskComplete(chr.getId()));
        player.yellowMessage("opqState=" + bot.getOPQBotState()
                + " botSMState=" + bot.getState()
                + " platform=" + EnvironmentManager.getCurrentPlatform(chr));
    }

    private static void spawnBots(int n, Client c) {
        Character self = c.getPlayer();
        int mapId = self.getMapId();
        int spawned = 0;
        for (int i = 0; i < n; i++) {
            try {
                Character fakechar = BotGeneration.createBotPollReadiness(self.getPosition(), mapId);
                if (fakechar == null) continue;
                OPQBot bot = new OPQBot(fakechar);
                CharacterStorage.addActiveBot(fakechar.getId(), bot);
                BotTypeManager.manuallyStartBot(fakechar);
                spawned++;
            } catch (Exception e) {
                self.yellowMessage("Spawn " + i + " failed: " + e.getMessage());
            }
        }
        self.yellowMessage("Spawned " + spawned + "/" + n + " OPQBots");
    }

    private static int killAllOPQBots() {
        OPQOrchestrator orch = OPQOrchestrator.getInstance();
        int killed = 0;
        for (OPQBot bot : snapshotOPQBots()) {
            BotTypeManager.manuallyStopBot(bot.getChr());
            orch.unregisterBot(bot);
            killed++;
        }
        return killed;
    }

    private static void markAllTaskComplete() {
        OPQSharedContext ctx = OPQOrchestrator.getInstance().getSharedContext();
        for (OPQBot bot : snapshotOPQBots()) {
            ctx.markTaskComplete(bot.getChr().getId());
        }
    }

    private static void warpSelf(String target, Client c) {
        Character self = c.getPlayer();
        int mapId = switch (target.toLowerCase()) {
            case "lobby" -> OPQConstants.OPQ_LOBBY;
            case "s1"    -> OPQConstants.OPQ_STAGE_1;
            case "tower" -> OPQConstants.OPQ_TOWER;
            case "s2"    -> OPQConstants.OPQ_STAGE_2;
            case "exit"  -> OPQConstants.OPQ_EXIT_LOBBY;
            default      -> -1;
        };
        if (mapId < 0) { self.yellowMessage("Unknown warp target: " + target); return; }
        MapleMap map = Server.getInstance()
                .getChannel(self.getWorld(), self.getClient().getChannel())
                .getMapFactory().getMap(mapId);
        if (map == null) { self.yellowMessage("Map not found: " + mapId); return; }
        self.changeMap(map, map.getPortal(0));
        self.yellowMessage("Warped to " + target + " (" + mapId + ")");
    }

    private static void forceStateAll(String stateName) {
        OPQBotState state;
        try {
            state = OPQBotState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.yellowMessage("Unknown state: " + stateName);
            return;
        }
        int hit = 0;
        for (OPQBot bot : snapshotOPQBots()) {
            bot.setStateForDebug(state);
            hit++;
        }
        player.yellowMessage("Forced " + hit + " bot(s) -> " + state);
    }

    private static void assignTarget(OPQBot bot, String kind) {
        OPQOrchestrator orch = OPQOrchestrator.getInstance();
        // Re-register the bot to clear any prior assignment, then auto-pick.
        orch.unregisterBot(bot);
        orch.registerBot(bot);
        switch (kind.toLowerCase()) {
            case "cloud":
                Integer pickedReactor = orch.assignCloudReactor(bot);
                player.yellowMessage("Cloud reactor auto-assigned to " + bot.getChr().getName()
                        + ": oid=" + pickedReactor);
                break;
            case "box":
                String pickedBox = orch.assignPlatformTarget(bot);
                player.yellowMessage("Box platform auto-assigned to " + bot.getChr().getName()
                        + ": " + pickedBox);
                break;
            default:
                player.yellowMessage("Kind must be 'cloud' or 'box'");
                break;
        }
    }

    private static List<OPQBot> snapshotOPQBots() {
        List<OPQBot> out = new ArrayList<>();
        for (Map.Entry<Integer, BotSM> e : CharacterStorage.getAllBots().entrySet()) {
            if (e.getValue() instanceof OPQBot opq) out.add(opq);
        }
        return out;
    }

    private static OPQBot findOPQBotForChar(Character chr) {
        BotSM sm = CharacterStorage.getAllBots().get(chr.getId());
        return (sm instanceof OPQBot opq) ? opq : null;
    }
}
