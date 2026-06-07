package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.maps.MapleMap;
import server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.List;

import static soloMapling.MapVFX.CustomReactor.deleteReactor;
import static soloMapling.MapVFX.CustomReactor.getAllReactorsData;
import static soloMapling.MapVFX.CustomReactor.getNearestReactor;
import static soloMapling.MapVFX.CustomReactor.hitReactor;
import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * Reactor inspection and bot-attack test commands.
 *
 *   !reactor list                       - alive reactors on your map
 *   !reactor near                       - nearest reactor oid
 *   !reactor hitnear                    - hit nearest reactor (1 hit)
 *   !reactor breaknear                  - break nearest reactor (4 hits)
 *   !reactor botattack <cid>            - bot plays a basic 1H swing animation
 *   !reactor hit <cid> <oid>            - hit reactor by oid on bot's map
 *   !reactor break <cid> <oid>          - 4 hits on reactor by oid on bot's map
 *   !reactor destroy <cid> <oid>        - delete reactor by oid on bot's map
 *   !reactor botbreak <cid> <oid>       - bot walks to reactor, swings + hits 4x
 */
public class ReactorCommands extends Command {
    {
        setDescription("Reactor inspection and bot-attack test commands.");
    }

    private static Character player;

    @Override
    public void execute(Client c, String[] params) {
        player = c.getPlayer();
        if (params.length == 0) {
            ExecutorServiceManager.getExecutorService().execute(() -> {
                player.yellowMessage("No Command Parameter Found. Try !reactor help");
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
                } else {
                    player.yellowMessage("Second input not an integer");
                }
            });
            return;
        }
        if (params.length == 3) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                if (isInteger(params[1]) && isInteger(params[2])) {
                    int commandNum  = Integer.parseInt(params[1]);
                    int commandNum2 = Integer.parseInt(params[2]);
                    handleTwoNumberedCommand(params[0], commandNum, commandNum2, c);
                } else {
                    player.yellowMessage("Second and third inputs must both be integers");
                }
            });
            return;
        }
    }

    public static void handleDirectCommand(String input, Client c) {
        switch (input.toLowerCase()) {
            case "help":
                printHelp();
                break;
            case "list":
                listReactors(c.getPlayer());
                break;
            case "near":
                int nearOid = getNearestReactor(c.getPlayer());
                player.yellowMessage("Nearest reactor oid=" + nearOid);
                break;
            case "hitnear":
                int hitOid = getNearestReactor(c.getPlayer());
                if (hitOid == 0) { player.yellowMessage("No reactor near you"); break; }
                hitReactor(c.getPlayer().getMap(), hitOid);
                player.yellowMessage("Hit reactor oid=" + hitOid);
                break;
            case "breaknear":
                int breakOid = getNearestReactor(c.getPlayer());
                if (breakOid == 0) { player.yellowMessage("No reactor near you"); break; }
                for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                    hitReactor(c.getPlayer().getMap(), breakOid);
                }
                player.yellowMessage("Broke reactor oid=" + breakOid + " (" + OPQConstants.MAX_REACTOR_HITS + " hits)");
                break;
            case "dump":
                getAllReactorsData(c.getPlayer());
                player.yellowMessage("Dumped reactors to debug log");
                break;
            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    public static void handleNumberedCommand(String input, int input2, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "botattack":
                BotAttack.basicSwing(fakechar);
                player.yellowMessage("Bot " + fakechar.getName() + " played basic swing animation");
                break;
            case "botlistreactors":
                listReactors(fakechar);
                break;
            default:
                player.yellowMessage("Invalid command - NumberedCommand");
                break;
        }
    }

    public static void handleTwoNumberedCommand(String input, int input2, int input3, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "hit":
                hitReactor(fakechar.getMap(), input3);
                player.yellowMessage("Hit reactor oid=" + input3 + " on " + fakechar.getName() + "'s map");
                break;
            case "break":
                for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                    hitReactor(fakechar.getMap(), input3);
                }
                player.yellowMessage("Broke reactor oid=" + input3 + " (" + OPQConstants.MAX_REACTOR_HITS + " hits)");
                break;
            case "destroy":
                deleteReactor(fakechar.getMap(), input3);
                player.yellowMessage("Destroyed reactor oid=" + input3);
                break;
            case "botbreak":
                botBreakReactor(fakechar, input3, c.getPlayer());
                break;
            default:
                player.yellowMessage("Invalid command - TwoNumberedCommand");
                break;
        }
    }

    private static void printHelp() {
        player.yellowMessage("---- Reactor Commands (!reactor) ----");
        player.yellowMessage("!reactor list                    - list alive reactors on your map");
        player.yellowMessage("!reactor near                    - nearest reactor oid");
        player.yellowMessage("!reactor hitnear                 - hit nearest reactor (1 hit)");
        player.yellowMessage("!reactor breaknear               - break nearest reactor (4 hits)");
        player.yellowMessage("!reactor dump                    - dump all reactors to debug log");
        player.yellowMessage("!reactor botattack <cid>         - bot plays basic swing animation");
        player.yellowMessage("!reactor botlistreactors <cid>   - list reactors on bot's map");
        player.yellowMessage("!reactor hit <cid> <oid>         - hit reactor by oid");
        player.yellowMessage("!reactor break <cid> <oid>       - break reactor (4 hits)");
        player.yellowMessage("!reactor destroy <cid> <oid>     - delete reactor by oid");
        player.yellowMessage("!reactor botbreak <cid> <oid>    - bot walks to reactor, swings + hits");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void listReactors(Character viewer) {
        MapleMap map = viewer.getMap();
        List<Reactor> reactors = map.getAllReactors();
        long aliveCount = reactors.stream().filter(Reactor::isAlive).count();
        player.yellowMessage("---- Reactors on map " + map.getId() + " (alive=" + aliveCount + ") ----");
        for (Reactor r : reactors) {
            if (!r.isAlive()) continue;
            player.yellowMessage("oid=" + r.getObjectId()
                    + " id=" + r.getId()
                    + " state=" + r.getState()
                    + " pos=" + r.getPosition());
        }
    }

    private static void botBreakReactor(Character fakechar, int oid, Character requester) {
        Reactor r = fakechar.getMap().getReactorByOid(oid);
        if (r == null) { requester.yellowMessage("No reactor oid=" + oid + " on bot's map"); return; }

        Point reactorPos = r.getPosition();
        requester.yellowMessage("Sending " + fakechar.getName() + " to reactor oid=" + oid + " at " + reactorPos);
        try {
            MovementCommands.pathFinderBetaAerial(fakechar, reactorPos); // pathFinderBetaAerial // pathFinderBeta
            Thread.sleep(3000); //
            for (int i = 0; i < OPQConstants.MAX_REACTOR_HITS; i++) {
                BotAttack.basicSwing(fakechar);
                hitReactor(fakechar.getMap(), oid);
                Thread.sleep(OPQConstants.SWING_INTERVAL_MS);
            }
            requester.yellowMessage("botbreak done.");
        } catch (Exception e) {
            requester.yellowMessage("botbreak failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
