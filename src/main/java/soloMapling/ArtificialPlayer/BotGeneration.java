package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import client.Job;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.SoloMaplingConstants;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.*;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotClientHandler.getBotClient;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botEnterPortalDropDown;
import static soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate.setBotVariables;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.microTurnAroundToLeft;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.FreeMarket.FMShopDescGen.getRandomCharacterIGN;
import static soloMapling.server.ExecutorServiceManager.runAsync;
import static soloMapling.server.SoloMaplingUtilities.getChr;
import static soloMapling.server.SoloMaplingUtilities.channel;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;
import static soloMapling.server.SoloMaplingUtilities.world;

public class BotGeneration {

    // Bot generation - handles anything related to putting the bots in the server

    private static int currentBotCount = 100;


    public static Character getConsoleBot() {
        Character consoleBot = getChr(999);
        if (consoleBot != null) {
            return consoleBot;
        }

        int botId = 999;
        int baseId = 3;

        try {
            Character chr = Character.loadCharFromDB(baseId, getBotClient(), false);
            consoleBot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }

        consoleBot = setConsoleBot(consoleBot, botId); // Bot onDemandBot
        addBotToServer(consoleBot);
        return consoleBot;
    }

    public static int createBot(Point pos, MapleMap map) {
        int cid = 3;

        Character bot = null;
        try {
            Character chr = Character.loadCharFromDB(cid, getBotClient(), false);
            bot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        int botId = SoloMaplingConstants.GameConstants.BOT_BASE_ID + currentBotCount;
        currentBotCount++;
        bot = setBotStats(bot, botId); // Bot onDemandBot
        addBotToServer(bot);
        warpBotToLocation(bot, pos, map);
        setBotVariables(bot);
        return botId;
    }


    /**
     * Places the bot on a map + position and plays the portal drop-down animation
     * so the spawn looks like a real player arriving. Sequence runs inline so the
     * caller waits for the full spawn choreography to finish before continuing —
     * prevents downstream setup (e.g. opening a shop) from racing ahead of the
     * drop-down / turn-around.
     *
     * Timing:
     *   - drop-down fires 500–1000ms after the bot is added to the map
     *   - a random-direction micro turn-around fires 1000–1500ms after that
     *
     * This method blocks the calling thread for roughly 1.5–2.5s total. Callers
     * should already be running on a pooled executor (not the client thread).
     */
    public static void warpBotToLocation(Character fakechar, Point pos, MapleMap map) {
        if (fakechar.getMap() == map) {
            fakechar.getMap().removePlayer(fakechar);
        }
        fakechar.setMap(map);
        fakechar.setPosition(pos);
        fakechar.setStance(5);
        map.addPlayer(fakechar);

        long dropDelayMs = ThreadLocalRandom.current().nextLong(500, 1201);
        if (!BotHelpers.sleepAmountSeconds(dropDelayMs)) return;
        botEnterPortalDropDown(fakechar);

        // Bots spawn facing right by default, so a 50% roll to flip to left gives
        // roughly even left/right distribution without a no-op right-turn.
        if (ThreadLocalRandom.current().nextBoolean()) {
            long turnDelayMs = ThreadLocalRandom.current().nextLong(1000, 1501);
            if (!BotHelpers.sleepAmountSeconds(turnDelayMs)) return;
            microTurnAroundToLeft(fakechar);
        }
    }

    private static Character setConsoleBot(Character baseChr, int botId) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(getBotClient());
        onDemandBot.setName("Console");

        onDemandBot.setID(botId);
        onDemandBot.setFame(botId); // debug purposes
        onDemandBot.setLevel(69);
        onDemandBot.setJob(Job.getById(420));

        return onDemandBot;
    }

    private static Character setBotStats(Character baseChr, int botId) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(getBotClient());
        onDemandBot.setName(getRandomCharacterIGN());
        onDemandBot.setID(botId);
        onDemandBot.setFame(botId); // debug purposes
        return onDemandBot;
    }

    public static void removeBotFromServer(Character fakechar) {
        fakechar.getMap().removePlayer(fakechar);
        channel.removePlayer(fakechar);
        world.getPlayerStorage().removePlayer(fakechar.getId());
        CharacterStorage.removeActiveBot(fakechar.getId());//
    }

    private static void addBotToServer(Character fakechar) {
//        final Channel channel = Server.getInstance().getChannel(BotSM.GameConstants.WORLD_SCANIA, BotSM.GameConstants.CHANNEL_1);
        channel.addPlayer(fakechar);
//        World world = Server.getInstance().getWorld(BotSM.GameConstants.WORLD_SCANIA);
        world.getPlayerStorage().addPlayer(fakechar);
    }

    public static void spawnBotFm(Character fakechar, Point pt) {
        int fmMap = 910000000;
        MapleMap spawnMap = getBotClient().getChannelServer().getMapFactory().getMap(fmMap);
        fakechar.setMap(spawnMap);
        fakechar.setPosition(pt);
        fakechar.setStance(5);
        spawnMap.addPlayer(fakechar);
    }

    /*
    Creates a bot on demand and waits a few millisecond for it to be fully ready to be used.
    If the bot isn't ready after 3000ms, it will return null.
     */
    public static Character createBotPollReadiness(Point position, int mapId) {
        int botId = BotGeneration.createBot(position, getMapleMapById(mapId));

        // Poll for bot readiness - check every 100ms up to 3 seconds
        Character fakechar2 = null;
        for (int i = 0; i < 30; i++) { // 30 * 100ms = 3000ms max
            try {
                Thread.sleep(100);
                fakechar2 = BotHelpers.getCharFromChannelStorage(botId);
                if (fakechar2 != null) {
                    debugprint("Bot " + botId + " ready after " + ((i + 1) * 100) + "ms");
                    break; // Bot is ready, proceed immediately
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        if (fakechar2 == null) {
            System.err.println("Bot " + botId + " not ready after 3 seconds, skipping store");
            return null;
        }
        return fakechar2;
    }

}
