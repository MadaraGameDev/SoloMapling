package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.maps.ReactorDropEntry;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;

import static soloMapling.MapVFX.CustomReactor.createReactorDropList;
import static soloMapling.MapVFX.CustomReactor.gachaPop;
import static soloMapling.MapVFX.CustomReactor.sprayFromReactor;
import static soloMapling.MapVFX.CustomReactor.threeHitReactor;
import static soloMapling.MapVFX.CustomReactor.deleteReactor;
import static soloMapling.MapVFX.CustomReactor.getAllReactorsData;
import static soloMapling.MapVFX.CustomReactor.getNearestReactor;
import static soloMapling.MapVFX.CustomReactor.hitReactor;
import static soloMapling.MapVFX.CustomReactor.spawnReactor;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.botSendMessengerChat;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.botTypingStatus;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.sendMessengerInviteComplete;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.TestMethods.addMMC;
import static soloMapling.itemPool.GachaFillerSystem.createGachaListWithPrize;
import static soloMapling.server.EventMessageSystem.EventFactory.createLevelUpEvent;
import static soloMapling.server.EventMessageSystem.EventFactory.createScrollEvent;
import static soloMapling.server.SoloMaplingUtilities.isInteger;

public class TestDevCommand extends Command {
    {
        setDescription("Template Commands Test.");
    }

    private static Character player;

    @Override
    public void execute(Client c, String[] params) {
        player = c.getPlayer();
        if (params.length == 0) {
            ExecutorServiceManager.getExecutorService().execute(() -> {
                player.yellowMessage("No Command Parameter Found. Try !test help");
            });
            return;
        }
        if (params.length == 1) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                handleSingleInputCommand(params[0], c);
            });
            return;
        }
        if (params.length == 2) {
            ExecutorServiceManager.getExecutorService().execute(() ->
            {
                if (isInteger(params[1])) {
                    int commandNum = Integer.parseInt(params[1]);
                    handleStringIntCommand(params[0], commandNum, c);
                } else if (!isInteger(params[0]) && !isInteger(params[1])) {
                    String commandString1 = (params[0]);
                    String commandString2 = params[1];
                    handleStringStringCommand(commandString1, commandString2, c);
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
                    int commandNum = Integer.parseInt(params[1]);
                    int commandNum2 = Integer.parseInt(params[2]);
                    handleStringIntIntCommand(params[0], commandNum, commandNum2, c);
                } else if (isInteger(params[1])) {
                    int commandNum = Integer.parseInt(params[1]);
                    String commandString = params[2];
                    handleStringIntStringCommand(params[0], commandNum, commandString);
                } else {
                    player.yellowMessage("Second input not an integer");
                }
            });
            return;
        }
    }

    public static void handleSingleInputCommand(String input, Client c) {
        switch (input.toLowerCase()) {
            case "help":
                printHelp();
                break;
            case "addmmc":
                addMMC(c);
                break;
            case "getallreactors":
                getAllReactorsData(c.getPlayer());
                break;
            case "getnearestreactor":
                getNearestReactor(c.getPlayer());
                break;
            case "testconvo":
                player.yellowMessage("[ConversationManager] Triggering conversation on current map...");
                ConversationManager.getInstance().triggerOnMap(player);
                break;
            case "spawnreactor":
                spawnReactor(c.getPlayer());
                break;

            default:
                player.yellowMessage("Invalid command - Direct Command");
                break;
        }
    }

    public static void handleStringIntIntCommand(String input, int input2, int input3, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        player.yellowMessage("Command: " + input2 + ", arg: " + input3);
        switch (input.toLowerCase()) {
            case "destroyreactor":
                deleteReactor(c.getPlayer().getMap(), input3);
                break;
            case "hitreactor":
                hitReactor(c.getPlayer().getMap(), input3);
                break;
            case "3hitreactor":
            case "breakreactor":
                threeHitReactor(c.getPlayer().getMap(), input3);
                break;
            case "sprayreactor":
                List<ReactorDropEntry> drops = createReactorDropList(List.of(1082223, 2022179, 1050018, 1082149, 1032026));
                sprayFromReactor(fakechar.getMap(), input3, drops, fakechar);
                break;

            default:
                player.yellowMessage("Invalid command - handleStringIntIntCommand");
                break;
        }
    }

    public static void handleStringIntCommand(String input, int input2, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null for handleStringIntCommand");
            return;
        }

        switch (input.toLowerCase()) {
            case "Test":
                break;
            case "botmminvite":
            case "botmminv":
                sendMessengerInviteComplete(fakechar, c.getPlayer());
                break;
            case "botmmtyping":
                botTypingStatus(fakechar, true);
                break;
            case "botgacha":
                List<ReactorDropEntry> popDrops = createReactorDropList(createGachaListWithPrize(1082223));
                gachaPop(fakechar, popDrops);
                break;
            case "testevent":
                eventUnitTests(c.getPlayer());
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

    public static void handleStringStringCommand(String input, String input2, Client c) {
        switch (input.toLowerCase()) {
            case "Test":
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

    public static void handleStringIntStringCommand(String input, int input2, String str) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null");
            return;
        }

        switch (input.toLowerCase()) {
            case "chat":
                BotSpeak(fakechar, str);
                break;
            case "botmmchat":
                botSendMessengerChat(fakechar, str);
                break;
            default:
                player.yellowMessage("Invalid command Two Object");
                break;
        }

    }

    private static void printHelp() {
        player.yellowMessage("---- Test Dev Commands (!test) ----");
        player.yellowMessage("-- Utility --");
        player.yellowMessage("!test addmmc                     - add MMC test");
        player.yellowMessage("!test testconvo                  - trigger conversation on current map");
        player.yellowMessage("!test testevent <cid>            - run event unit tests");
        player.yellowMessage("-- Reactors --");
        player.yellowMessage("!test getallreactors             - dump all reactors data");
        player.yellowMessage("!test getnearestreactor          - get nearest reactor");
        player.yellowMessage("!test spawnreactor               - spawn reactor at your location");
        player.yellowMessage("!test destroyreactor <cid> <oid> - delete reactor");
        player.yellowMessage("!test hitreactor <cid> <oid>     - hit reactor once");
        player.yellowMessage("!test breakreactor <cid> <oid>   - three-hit reactor break");
        player.yellowMessage("!test sprayreactor <cid> <oid>   - spray drops from reactor");
        player.yellowMessage("-- Messenger --");
        player.yellowMessage("!test botmminvite <cid>          - send messenger invite");
        player.yellowMessage("!test botmmtyping <cid>          - send messenger typing status");
        player.yellowMessage("!test botmmchat <cid> <message>  - bot sends messenger chat");
        player.yellowMessage("-- VFX --");
        player.yellowMessage("!test botgacha <cid>             - test gacha drop pop");
        player.yellowMessage("-- Chat --");
        player.yellowMessage("!test chat <cid> <message>       - bot speaks in chat");
    }

    public static void eventUnitTests(Character fakechar) {
        GameEvent event = createLevelUpEvent(fakechar);
        EventBus.getInstance().publish(event);

//        GameEvent event2 = createScrollEvent(fakechar, null, true);
//        EventBus.getInstance().publish(event2);

//        GameEvent event3 = createChatMegaphoneEvent(fakechar, "test smega");
//        EventBus.getInstance().publish(event3);
    }

}
