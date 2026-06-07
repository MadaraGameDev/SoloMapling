package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.FreeMarket.ArtificialFreeMarket;
import soloMapling.server.ExecutorServiceManager;

//import static soloMapling.FreeMarket.ArtificialFreeMarket.populateFreeMarket;
import java.util.Objects;

import static soloMapling.FreeMarket.ArtificialFreeMarket.BotPlayerStorePermit;
import static soloMapling.FreeMarket.ArtificialFreeMarket.destroyAllShops;
import static soloMapling.FreeMarket.ArtificialFreeMarket.populateFreeMarketRegion;
import static soloMapling.FreeMarket.ArtificialFreeMarket.populateFreeMarketSpot;
import static soloMapling.server.SoloMaplingUtilities.isInteger;


public class ArtificialFreeMarketCommand extends Command {
    {
        setDescription("Artificial FM Test.");
    }

    private static Character player;


    @Override
    public void execute(Client c, String[] params) {
        player = c.getPlayer();
        if (params.length == 0) {
            ExecutorServiceManager.getExecutorService().execute(() ->
                    populateFreeMarketSpot(c));
            return;
        }
        if (params.length == 1 && Objects.equals(params[0], "help")) {
            ExecutorServiceManager.getExecutorService().execute(ArtificialFreeMarketCommand::printHelp);
            return;
        }
        if (params.length == 1 && Objects.equals(params[0], "destroy")) {
            destroyAllShops(c);
            return;
        }
        if (params.length == 1) {
            System.out.println(params[0]);
            ExecutorServiceManager.getExecutorService().execute(() ->
                    populateFreeMarketRegion(params[0]));
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


//        ExecutorServiceManager.getExecutorService().execute(() ->
////                populateFreeMarket(c));
//                populateFreeMarketFull());
//                populateFreeMarketRoom(c.getPlayer().getMapId()));


//        iitest(c);
    }

    private static void printHelp() {
        player.yellowMessage("---- FM Shop Commands (!betafmshop) ----");
        player.yellowMessage("!betafmshop                      - populate FM spot at your location");
        player.yellowMessage("!betafmshop destroy              - destroy all shops");
        player.yellowMessage("!betafmshop <region>             - populate entire FM region");
        player.yellowMessage("!betafmshop store/permit <cid>   - grant bot player store permit");
        player.yellowMessage("!betafmshop botshop <name>       - create bot shop at your location");
    }

    public static void handleStringIntCommand(String input, int input2, Client c) {
        Character fakechar = BotHelpers.getCharFromChannelStorage(input2);
        if (fakechar == null) {
            player.yellowMessage("Bot null for handleStringIntCommand");
            return;
        }

        switch (input.toLowerCase()) {
            case "store":
            case "permit":
                BotPlayerStorePermit(fakechar);
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
            case "botshop":
                ArtificialFreeMarket.createBotShopAtLocation(c.getPlayer().getPosition(), c.getPlayer().getMapId());
                break;
            default:
                player.yellowMessage("Invalid command - handleStringIntCommand");
                break;
        }
    }

}
