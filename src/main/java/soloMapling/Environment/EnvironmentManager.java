package soloMapling.Environment;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorationQueue;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotEquipChecker;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.Blackjack.BlackjackDealerBot;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.SocialHotPotatoManager;
import soloMapling.Casino.CasinoChipConfig;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.NpcSpawner;
import constants.id.NpcId;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotGeneration.createBotPollReadiness;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfRespondant;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair;
import static soloMapling.ArtificialPlayer.BotTypeManager.setAndStartBots;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.DebugUtilities.fmt;
import static soloMapling.Environment.PlatformSpawner.findUnoccupiedPoint;
import static soloMapling.Environment.PlatformSpawner.findUnoccupiedPoints;
import static soloMapling.FreeMarket.ArtificialFreeMarket.populateFreeMarketRegion;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class EnvironmentManager {

    private static final String BASE_PATH = "src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/movementDataPackets";

    /**
     * Tolerance for Y-coordinate matching when determining if a character is on a platform.
     * Characters within this vertical distance of the platform are considered "on" it.
     */
    private static final int Y_TOLERANCE = 10;

    /**
     * Tolerance for X-coordinate proximity when checking if a character is near platform bounds.
     * Allows some buffer beyond the recorded min/max X values.
     */
    private static final int X_TOLERANCE = 20;


    private static final Random random = new Random();
    private static final int FM_ENTRANCE = 910000000;
    private static final int HENESYS = 100000000;
    private static final int HENESYS_MARKET = 100000100;
    private static final int HENESYS_PARK = 100000200;
    private static final int HENESYS_POTION_SHOP = 100000102;
    private static final int HENESYS_GAME_ZONE = 100000203;
    private static final int HENESYS_PET_PARK = 100000202;
    private static final int MAPLE_ISLAND_TUTORIAL = 10000;
    private static final int OPQ_LOBBY = 200080101;

    public static void environmentLoadStartup() {
        // EquipMetadataCache + DesirableEquipList are server data, loaded during
        // Server.init() alongside the other WZ-derived data - guaranteed ready
        // before any player can trigger this.
        long startupStart = System.currentTimeMillis();

        runWave(1, "Essentials", List.of(
                () -> spawnCasinoNpcs(),
                () -> spawnTutorialBot(),
                () -> spawnHenesysBotsBatch(10, 0, 0, 0),
                () -> populateFreeMarketRegion("henesys"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5)
        ));

        runWave(2, "FM buildout", List.of(
                () -> populateFreeMarketRegion("ludi"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5),
                () -> spawnMerchBotsBatch("m1", 2, 2, 1),
                () -> spawnMerchBotsBatch("m2", 2, 2, 0),
                () -> spawnMerchBotsBatch("m5", 2, 2, 0),
                () -> spawnGachaBotsHenesys()
        ));

        runWave(3, "Henesys population", List.of(
                () -> spawnJQBotsPetPark(),
                () -> spawnHenesysBotsBatch(10, 10, 0, 5),
                () -> spawnFillerBotsHenesys()
        ));
        SocialHotPotatoManager.getInstance().start();
        ConversationManager.getInstance().start();

        runWave(4, "Expand FM + Henesys Market", List.of(
                () -> populateFreeMarketRegion("perion"),
                () -> spawnFMEntranceBotsBatch(5, 5, 5),
                () -> spawnMerchBotsBatch("m1", 3, 3, 0),
                () -> spawnMerchBotsBatch("m2", 2, 2, 1),
                () -> spawnMerchBotsBatch("m5", 3, 3, 1),
                () -> spawnFillerBotsHenesysMarket()
        ));

        runWave(5, "Henesys sub-areas", List.of(
                () -> populateFreeMarketRegion("elnath"),
                () -> spawnHenesysBotsBatch(10, 10, 10, 4),
                () -> spawnFillerBotsHenesysPark(),
                () -> spawnFillerBotsPotionShop(),
                () -> spawnFillerBotsGameZone(),
                () -> spawnGameZoneHostBots()
        ));

        runWave(6, "Specialty areas", List.of(
                () -> spawnBlackjackTables(),
                () -> spawnDropGameBotPotionShop(),
                () -> spawnDropGameSpectatorsPotionShop(),
                () -> spawnSocialBotsPetPark(),
                () -> convertRandomFillersToScrollBots()
        ));

        runWave(7, "Late arrivals", List.of(
                () -> spawnOPQBotsInLobby(),
                () -> spawnMerchBotsBatch("m1", 2, 2, 0),
                () -> spawnMerchBotsBatch("m2", 2, 2, 1),
                () -> spawnMerchBotsBatch("m5", 2, 2, 1)
        ));

        BotDecorationQueue.start();
        BotEquipChecker.start();

        double totalSeconds = (System.currentTimeMillis() - startupStart) / 1000.0;
        System.out.println(String.format(
                "[EnvironmentManager] === All bots initialized: %d bots in %.1fs ===",
                BotGeneration.getBotsCreatedCount(), totalSeconds));
    }

    /**
     * Run one startup wave: all tasks in parallel, blocking until the wave
     * completes. Logs start/end with elapsed time and bots spawned. FM room
     * population is fire-and-forget internally, so its bots may be attributed
     * to a later wave's count.
     */
    private static void runWave(int number, String name, List<Runnable> tasks) {
        System.out.println(String.format(
                "[EnvironmentManager] === Wave %d (%s) starting ===", number, name));
        long start = System.currentTimeMillis();
        int botsBefore = BotGeneration.getBotsCreatedCount();

        runPhase(tasks);

        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        int botsSpawned = BotGeneration.getBotsCreatedCount() - botsBefore;
        System.out.println(String.format(
                "[EnvironmentManager] === Wave %d (%s) complete - %d bots spawned in %.1fs ===",
                number, name, botsSpawned, seconds));
    }

    private static void spawnFMEntranceBotsBatch(int m1Count, int m2Count, int m5Count) {
        if (m1Count > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(m1Count, FM_ENTRANCE, "m1");
            setAndStartBots(bots, BotTypeManager.BotType.FM_BOT);
        }
        if (m2Count > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(m2Count, FM_ENTRANCE, "m2");
            setAndStartBots(bots, BotTypeManager.BotType.FM_BOT);
        }
        if (m5Count > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(m5Count, FM_ENTRANCE, "m5");
            setAndStartBots(bots, BotTypeManager.BotType.FM_BOT);
        }
    }

    private static void spawnMerchBotsBatch(String platform, int selling, int buying, int nx) {
        if (selling > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(selling, FM_ENTRANCE, platform);
            setAndStartBots(bots, BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        }
        if (buying > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(buying, FM_ENTRANCE, platform);
            setAndStartBots(bots, BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        }
        if (nx > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(nx, FM_ENTRANCE, platform);
            setAndStartBots(bots, BotTypeManager.BotType.NX_MERCHANT_BOT);
        }
    }

    private static void spawnHenesysBotsBatch(int mainCount, int marketCount, int parkCount, int socialCount) {
        if (mainCount > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(mainCount, HENESYS, "m1");
            setAndStartBots(bots, BotTypeManager.BotType.HENESYS_BOT);
        }
        if (marketCount > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(marketCount, HENESYS_MARKET, "m1");
            setAndStartBots(bots, BotTypeManager.BotType.HENESYS_BOT);
        }
        if (parkCount > 0) {
            List<Integer> bots = spawnBotsOnMapOnPlatform(parkCount, HENESYS_PARK, "m1");
            setAndStartBots(bots, BotTypeManager.BotType.HENESYS_BOT);
        }
        if (socialCount > 0) {
            int perSpot = Math.max(1, socialCount / 3);
            List<Integer> s1 = spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m4_social");
            List<Integer> s2 = spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m5_social");
            List<Integer> s3 = spawnBotsOnMapOnPlatform(perSpot, HENESYS, "m6_social");
        }
    }

    public static void spawnCasinoNpcs() {
        int casinoMap = 100000203;
        NpcSpawner.spawnNpc(CasinoChipConfig.CASINO_NPC_ID, casinoMap, 1321, 214);
        NpcSpawner.spawnNpc(NpcId.RPS_ADMIN, casinoMap, 899, 275);
    }

    private static void runPhase(List<Runnable> tasks) {
        // Virtual threads: wave tasks spend most of their time blocked (spawn
        // choreography sleeps, readiness latches), so they shouldn't occupy
        // the fixed thread pool.
        CompletableFuture<?>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(task, ExecutorServiceManager.getVirtualThreadExecutorService()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    /*
    spawn 50 bots in fm
     */
    public static void spawnBotsInFMEntrance() {
        int fm_entrance = 910000000;
        List<Integer> bots1 = spawnBotsOnMapOnPlatform(15, fm_entrance, "m1"); // Bottom row
        debugprint("bots1", bots1);
        setAndStartBots(bots1, BotTypeManager.BotType.FM_BOT);
        List<Integer> bots3 = spawnBotsOnMapOnPlatform(15, fm_entrance, "m5"); // Left side
        debugprint("bots3", bots3);
        setAndStartBots(bots3, BotTypeManager.BotType.FM_BOT);
        List<Integer> bots2 = spawnBotsOnMapOnPlatform(15, fm_entrance, "m2"); // Second row
        debugprint("bots2", bots2);
        setAndStartBots(bots2, BotTypeManager.BotType.FM_BOT);
    }

    public static void spawnMerchBotsInFMEntrance() {
        int fm_entrance = 910000000;

        // m1 - Bottom row: 7 selling, 7 buying, 1 nx
        List<Integer> m1Selling = spawnBotsOnMapOnPlatform(7, fm_entrance, "m1");
        setAndStartBots(m1Selling, BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        List<Integer> m1Buying = spawnBotsOnMapOnPlatform(7, fm_entrance, "m1");
        setAndStartBots(m1Buying, BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        List<Integer> m1NX = spawnBotsOnMapOnPlatform(1, fm_entrance, "m1");
        setAndStartBots(m1NX, BotTypeManager.BotType.NX_MERCHANT_BOT);

        // m5 - Left side: 7 selling, 7 buying, 2 nx
        List<Integer> m5Selling = spawnBotsOnMapOnPlatform(7, fm_entrance, "m5");
        setAndStartBots(m5Selling, BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        List<Integer> m5Buying = spawnBotsOnMapOnPlatform(7, fm_entrance, "m5");
        setAndStartBots(m5Buying, BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        List<Integer> m5NX = spawnBotsOnMapOnPlatform(2, fm_entrance, "m5");
        setAndStartBots(m5NX, BotTypeManager.BotType.NX_MERCHANT_BOT);

        // m2 - Second row: 6 selling, 6 buying, 2 nx
        List<Integer> m2Selling = spawnBotsOnMapOnPlatform(6, fm_entrance, "m2");
        setAndStartBots(m2Selling, BotTypeManager.BotType.SELLING_MERCHANT_BOT);
        List<Integer> m2Buying = spawnBotsOnMapOnPlatform(6, fm_entrance, "m2");
        setAndStartBots(m2Buying, BotTypeManager.BotType.BUYING_MERCHANT_BOT);
        List<Integer> m2NX = spawnBotsOnMapOnPlatform(2, fm_entrance, "m2");
        setAndStartBots(m2NX, BotTypeManager.BotType.NX_MERCHANT_BOT);
    }

    public static void spawnHenesysBots() {
        int henesys_map = 100000000;
        List<Integer> bots1 = spawnBotsOnMapOnPlatform(30, henesys_map, "m1");
        setAndStartBots(bots1, BotTypeManager.BotType.HENESYS_BOT);

        List<Integer> bots2 = spawnBotsOnMapOnPlatform(10, 100000100, "m1");
        List<Integer> bots3 = spawnBotsOnMapOnPlatform(10, 100000100, "m2");
        setAndStartBots(bots2, BotTypeManager.BotType.HENESYS_BOT); // hene market
        setAndStartBots(bots3, BotTypeManager.BotType.HENESYS_BOT);

        List<Integer> bots4 = spawnBotsOnMapOnPlatform(10, 100000200, "m1");
        setAndStartBots(bots4, BotTypeManager.BotType.HENESYS_BOT); // hene park

        List<Integer> bots5 = spawnBotsOnMapOnPlatform(3, henesys_map, "m4_social"); // nana fairy area
        List<Integer> bots6 = spawnBotsOnMapOnPlatform(3, henesys_map, "m5_social");
        List<Integer> bots7 = spawnBotsOnMapOnPlatform(3, henesys_map, "m6_social");
    }

    public static void spawnGachaBotsHenesys() {
        List<Integer> bots2 = spawnBotsOnMapOnPlatformInRadius(3, 100000100, "m1",  new Point(366,154), 250);
        setAndStartBots(bots2, BotTypeManager.BotType.GACHA_BOT); // hene market
    }

    private static int randomizeCount(int base) {
        return Math.max(1, base + random.nextInt(3) - 1); // -1, 0, or +1
    }

    public static void spawnFillerBotsHenesys() {
        int map = HENESYS;
        debugprint("Spawning filler bots in Henesys...");

        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(-696, 274), new Point(-10, 274)));       // left side
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(-144, 218), new Point(36, 218)));         // left side taxi barrels
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(-286, 101), new Point(7, 94)));           // left side top plat
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(248, 274), new Point(573, 274)));         // left tree under
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(2596, 334), new Point(3347, 334)));       // near market portal
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(3393, 124), new Point(4247, 124)));       // near park portal
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(3831, 454), new Point(4382, 454)));       // under park portal
        allIds.addAll(spawnFillerBots(randomizeCount(8), map, new Point(4832, 454), new Point(5762, 454)));       // near maya house
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(5547, -176), new Point(6232, -176)));     // near sleepy portal
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(4732, -116), new Point(5424, -116)));     // near sleepy portal 2

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys filler bots complete.");
    }

    public static void spawnFillerBotsHenesysMarket() {
        int map = HENESYS_MARKET;
        debugprint("Spawning filler bots in Henesys Market...");

        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(-548, 154), new Point(568, 154)));        // left side
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(592, 154), new Point(1148, 154)));        // left side 2
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(-105, 154), new Point(568, 154)));        // left side 3
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(1340, 214), new Point(2442, 214)));       // near weapon store
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(1369, -56), new Point(2546, -56)));       // above weapon store
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(2689, -116), new Point(3636, -116)));     // near potion store
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(2744, 94), new Point(3494, 94)));         // below potion store
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(3760, 94), new Point(5100, 94)));         // right side
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(3852, -176), new Point(4427, -176)));     // top right side

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Market filler bots complete.");
    }

    public static void spawnFillerBotsHenesysPark() {
        int map = HENESYS_PARK;
        debugprint("Spawning filler bots in Henesys Park...");

        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(-53, 454), new Point(597, 454)));         // left side
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(982, 424), new Point(1288, 424)));        // near storage keeper
        allIds.addAll(spawnFillerBots(randomizeCount(5), map, new Point(984, 574), new Point(1606, 574)));        // HPQ bottom
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(1563, 304), new Point(1769, 304)));       // JQ platform
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(1915, 574), new Point(2909, 574)));       // fountain bottom
        allIds.addAll(spawnFillerBots(randomizeCount(1), map, new Point(2019, 364), new Point(2118, 364)));       // fountain left tomb
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(2198, 424), new Point(2471, 424)));       // fountain top
        allIds.addAll(spawnFillerBots(randomizeCount(1), map, new Point(2549, 364), new Point(2663, 364)));       // right tomb
        allIds.addAll(spawnFillerBots(randomizeCount(2), map, new Point(3233, 334), new Point(3607, 334)));       // right statue TP
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(3585, 694), new Point(4390, 694)));       // outside bowman portal

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Park filler bots complete.");
    }

    public static void spawnFillerBotsGameZone() {
        int map = HENESYS_GAME_ZONE;
        debugprint("Spawning filler bots in Henesys Game Zone...");

        List<Integer> allIds = new ArrayList<>();
//        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(-830, 274), new Point(-62, 274)));        // left side // interferes with table 1
        allIds.addAll(spawnFillerBots(randomizeCount(4), map, new Point(1027, 394), new Point(1483, 394)));       // right side lower
        allIds.addAll(spawnFillerBots(randomizeCount(6), map, new Point(-83, 274), new Point(340, 274)));         // center
        allIds.addAll(spawnFillerBots(randomizeCount(3), map, new Point(263, 64), new Point(929, 64)));           // top platform

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Game Zone filler bots complete.");
    }

    public static void spawnFillerBotsPotionShop() {
        int map = HENESYS_POTION_SHOP;
        debugprint("Spawning filler bots in Henesys Potion Shop...");

        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(3), map, new Point(-370, 182), new Point(175, 182)));   // bottom left
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(2), map, new Point(193, 182), new Point(370, 182)));    // bottom right
        allIds.addAll(spawnFillerBotsLockedY(randomizeCount(3), map, new Point(-112, -127), new Point(245, -127))); // top bar

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint("Henesys Potion Shop filler bots complete.");
    }

    public static void spawnDropGameBotPotionShop() {
        debugprint("Spawning Drop Game Bot in Henesys Potion Shop...");
        Point spawn = new Point(45, 182);
        ExecutorServiceManager.runAsync(() -> {
            Character fakechar = createBotWithRetry(spawn, HENESYS_POTION_SHOP, 5);
            if (fakechar != null) {
                ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                    setAndStartBots(List.of(fakechar.getId()), BotTypeManager.BotType.DROP_GAME_BOT);
                    debugprint("Drop Game Bot started in Henesys Potion Shop.");
                }, 5, TimeUnit.SECONDS);
            } else {
                debugprint("Failed to spawn Drop Game Bot in Henesys Potion Shop.");
            }
        });
    }

    public static void spawnDropGameSpectatorsPotionShop() {
        debugprint("Spawning Drop Game Spectator bots in Henesys Potion Shop...");
        Point[] spots = {
                new Point(145, 145), new Point(76, 22), new Point(297, -28),
                new Point(-87, -25), new Point(-269, -27),
                new Point(-142, 31), new Point(-28, 103), new Point(151, -43),
                new Point(-142, 141)
        };

        List<Point> available = new ArrayList<>(List.of(spots));
        Collections.shuffle(available);
        int count = 3 + new Random().nextInt(4);

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count && i < available.size(); i++) {
            Character bot = createBotWithRetry(available.get(i), HENESYS_POTION_SHOP, 3);
            if (bot != null) {
                ids.add(bot.getId());
            }
        }

        if (!ids.isEmpty()) {
            setAndStartBots(ids, BotTypeManager.BotType.SOCIAL_BOT);
            debugprint("Spawned " + ids.size() + " Drop Game Spectator bots in Potion Shop.");
        }
    }

    public static void spawnTutorialBot() {
        debugprint("Spawning Tutorial Bot on Maple Island...");
        Point spawn = new Point(158, 485);
        Character fakechar = createBotWithRetry(spawn, MAPLE_ISLAND_TUTORIAL, 5);
        if (fakechar != null) {
            setAndStartBots(List.of(fakechar.getId()), BotTypeManager.BotType.TUTORIAL_BOT);
            debugprint("Tutorial Bot started on Maple Island.");
        } else {
            debugprint("Failed to spawn Tutorial Bot on Maple Island.");
        }
    }

    public static void spawnJQBotsPetPark() {
        debugprint("Spawning JQ Bots in Henesys Pet Park...");
        List<Integer> botIds = spawnBotsOnMapOnPlatform(15, HENESYS_PET_PARK, "m1");
        setAndStartBots(botIds, BotTypeManager.BotType.HENESYS_JQ_BOT);
        debugprint(fmt("Pet Park JQ bots spawned: {}", botIds.size()));
    }

    public static void spawnSocialBotsPetPark() {
        int map = HENESYS_PET_PARK;
        debugprint("Spawning social bots in Henesys Pet Park...");

        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(spawnFillerBots(1, map, new Point(-194, 34), new Point(184, 34)));
        allIds.addAll(spawnFillerBots(2, map, new Point(-449, 154), new Point(369, 154)));
        allIds.addAll(spawnFillerBots(3, map, new Point(618, 154), new Point(1375, 154)));
        allIds.addAll(spawnFillerBots(1, map, new Point(841, -116), new Point(1125, -116)));
        allIds.addAll(spawnFillerBots(1, map, new Point(437, -326), new Point(810, -326)));
        allIds.addAll(spawnFillerBots(1, map, new Point(531, -626), new Point(731, -626)));
        allIds.addAll(spawnFillerBots(1, map, new Point(790, -506), new Point(993, -506)));
        allIds.addAll(spawnFillerBots(1, map, new Point(1072, -446), new Point(1274, -446)));
        allIds.addAll(spawnFillerBots(3, map, new Point(-1808, 274), new Point(-738, 274)));

        setAndStartBots(allIds, BotTypeManager.BotType.SOCIAL_BOT);
        debugprint(fmt("Pet Park social bots spawned: {}", allIds.size()));
    }

    public static void spawnGameZoneHostBots() {
        debugprint("Spawning Game Zone Host Bots...");
        Point[] spawns = { new Point(503, 250), new Point(716, 254) };
        List<Integer> botIds = new ArrayList<>();

        for (Point spawn : spawns) {
            Character fakechar = createBotWithRetry(spawn, HENESYS_GAME_ZONE, 5);
            if (fakechar != null) {
                botIds.add(fakechar.getId());
            } else {
                debugprint(fmt("Failed to spawn Game Zone Host Bot at {}", spawn));
            }
        }

        if (!botIds.isEmpty()) {
            setAndStartBots(botIds, BotTypeManager.BotType.GAME_ZONE_HOST_BOT);
            debugprint(fmt("Game Zone Host Bots started: {}", botIds.size()));
        }
    }

    public static void spawnBlackjackTables() {
        debugprint("Spawning Blackjack Tables in Game Zone...");

        // Table 1
        spawnBlackjackTable(
                new Point(-947, 64), new Point(-169, 64),
                new Point(-920, 274), new Point(-169, 274));
        // Table 2
        spawnBlackjackTable(
                new Point(-939, -296), new Point(-152, -296),
                new Point(-937, -116), new Point(-149, -116));
        // Table 3
        spawnBlackjackTable(
                new Point(226, -296), new Point(937, -296),
                new Point(227, -116), new Point(940, -116));
        // Table 4
        spawnBlackjackTable(
                new Point(-927, -656), new Point(-130, -656),
                new Point(-956, -476), new Point(-151, -476));
        // Table 5
        spawnBlackjackTable(
                new Point(229, -656), new Point(924, -656),
                new Point(220, -476), new Point(943, -476));

        debugprint("All Blackjack Tables spawned.");
    }

    private static Point[] calculateTablePositions(Point topP1, Point topP2, Point botP1, Point botP2) {
        int topMinX = Math.min(topP1.x, topP2.x);
        int topMaxX = Math.max(topP1.x, topP2.x);
        int topY = topP1.y;
        int topThird = (topMaxX - topMinX) / 3;

        int botMinX = Math.min(botP1.x, botP2.x);
        int botMaxX = Math.max(botP1.x, botP2.x);
        int botY = botP1.y;
        int botThird = (botMaxX - botMinX) / 3;

        return new Point[] {
                new Point(topMinX + topThird + topThird / 2, topY),                       // [0] Dealer — top middle (no jitter)
                new Point(topMinX + topThird / 2 + jitter(), topY),                       // [1] Player — top left
                new Point(topMinX + topThird * 2 + topThird / 2 + jitter(), topY),        // [2] Player — top right
                new Point(botMinX + botThird / 2 + jitter(), botY),                       // [3] Player — bottom left
                new Point(botMinX + botThird + botThird / 2 + jitter(), botY),             // [4] Player — bottom middle
                new Point(botMinX + botThird * 2 + botThird / 2 + jitter(), botY),         // [5] Player — bottom right
        };
    }

    private static int jitter() {
        return random.nextInt(125) - 50;
    }

    private static void spawnBlackjackTable(Point topP1, Point topP2, Point botP1, Point botP2) {
        Point[] seats = calculateTablePositions(topP1, topP2, botP1, botP2);
        int playerCount = 2 + random.nextInt(4); // 2-5 players

        // Spawn dealer bot at seat[0]
        Character dealerChar = createBotWithRetry(seats[0], HENESYS_GAME_ZONE, 5);
        if (dealerChar == null) {
            debugprint("Failed to spawn Blackjack dealer bot");
            return;
        }

        setAndStartBots(List.of(dealerChar.getId()), BotTypeManager.BotType.BLACKJACK_DEALER);

        // Spawn player bots at random seats from seats[1]-[5]
        List<Integer> playerSeatIndices = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        Collections.shuffle(playerSeatIndices);
        List<Character> playerChars = new ArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            int seatIdx = playerSeatIndices.get(i);
            Character playerChar = createBotWithRetry(seats[seatIdx], HENESYS_GAME_ZONE, 5);
            if (playerChar != null) {
                playerChars.add(playerChar);
            }
        }

        // Register players with the dealer's table immediately (game logic),
        // but face them towards the dealer only after the spawn drop-down/
        // turn-around choreography finishes, so it can't override the facing.
        BotSM dealerBot = getBotById(dealerChar.getId());
        if (dealerBot instanceof BlackjackDealerBot bjDealer) {
            for (Character playerChar : playerChars) {
                bjDealer.getTable().addPlayer(playerChar);
                bjDealer.getInteractors().setRespondant(playerChar);
            }
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                for (Character playerChar : playerChars) {
                    botFaceTowardsPoint(playerChar, seats[0]);
                }
            }, BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500, TimeUnit.MILLISECONDS);
            debugprint(fmt("Blackjack table spawned: dealer={}, players={}", dealerChar.getId(), playerChars.size()));
        } else {
            debugprint("Failed to retrieve BlackjackDealerBot from CharacterStorage");
        }
    }

    public static void convertRandomFillersToScrollBots() {
        debugprint("Converting random filler bots to Scroll Bots across Henesys maps...");

        int[] maps = { HENESYS, HENESYS_MARKET, HENESYS_PARK, HENESYS_POTION_SHOP, HENESYS_GAME_ZONE };
        for (int mapId : maps) {
            int count = 1 + random.nextInt(3); // 1-3
            convertRandomIdleBotsToScrollBots(mapId, count);
        }

        debugprint("Scroll Bot conversion complete.");
    }

    public static void convertRandomIdleBotsToScrollBots(int mapId, int count) {
        List<Character> allChars = getAllCharsOnMap(mapId);

        List<Integer> idleBotIds = allChars.stream()
                .filter(chr -> {
                    if (!isBot(chr)) return false;
                    BotSM bot = getBotById(chr.getId());
                    return bot != null && bot.isAvailableForAmbientActions();
                })
                .map(Character::getId)
                .collect(Collectors.toList());

        if (idleBotIds.isEmpty()) {
            debugprint(fmt("No idle bots found on map {} to convert", mapId));
            return;
        }

        Collections.shuffle(idleBotIds);
        int toConvert = Math.min(count, idleBotIds.size());
        List<Integer> selected = idleBotIds.subList(0, toConvert);

        debugprint(fmt("Converting {} idle bots to Scroll Bots on map {}: {}", toConvert, mapId, selected));
        setAndStartBots(selected, BotTypeManager.BotType.SCROLL_BOT);
    }

    public static void spawnOPQBotsInLobby() {
        int totalBots = 10 + random.nextInt(6); // 10-15
        List<String> platforms = getMainPlatformIds(OPQ_LOBBY);

        if (platforms.isEmpty()) {
            debugprint("No platforms found for OPQ lobby map");
            return;
        }

        debugprint(fmt("Spawning {} OPQ bots across {} platforms in lobby...", totalBots, platforms.size()));

        List<Integer> allBotIds = new ArrayList<>();
        int perPlatform = totalBots / platforms.size();
        int remainder = totalBots % platforms.size();

        for (int i = 0; i < platforms.size(); i++) {
            int count = perPlatform + (i < remainder ? 1 : 0);
            if (count <= 0) continue;
            List<Integer> ids = spawnBotsOnMapOnPlatform(count, OPQ_LOBBY, platforms.get(i));
            allBotIds.addAll(ids);
        }

        if (!allBotIds.isEmpty()) {
            setBotsLevelRange(allBotIds, 50, 70);
            setAndStartBots(allBotIds, BotTypeManager.BotType.OPQ_BOT);
            debugprint(fmt("OPQ lobby bots spawned and started: {}", allBotIds.size()));
        }
    }

    public static void setBotsLevelRange(List<Integer> botIds, int minLevel, int maxLevel) {
        for (int botId : botIds) {
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            if (bot != null) {
                bot.setLevel(minLevel + random.nextInt(maxLevel - minLevel + 1));
            }
        }
    }

    public static List<Integer> spawnBotsOnMapOnPlatform(int numBots, int mapId, String platform_id) {
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform_id);
        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} bots on {} at platform: {}", numBots, mapId, platform_id));

        // Pre-generate all spawn points (must be sequential to avoid overlaps)
        List<Point> spawnPoints = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point spawn = findUnoccupiedPoint(flatPlatform, occupied);
            occupied.add(spawn);
            spawnPoints.add(spawn);
        }

        // Use CountDownLatch to wait for all spawns to complete
        CountDownLatch latch = new CountDownLatch(numBots);

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all spawns to complete with timeout
        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for bot spawns. Completed: {}/{}",
                        characterIds.size(), numBots));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        return new ArrayList<>(characterIds);
    }


    public static List<Integer> spawnBotsOnMapOnPlatformInRadius(int numBots, int mapId, String platform_id, Point center, int radius) {
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform_id);
        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} bots on {} at platform: {} within radius {} of ({},{})",
                numBots, mapId, platform_id, radius, center.x, center.y));

        // Pre-generate all spawn points within radius
        List<Point> spawnPoints = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point spawn = findUnoccupiedPointInRadius(flatPlatform, occupied, center, radius);
            if (spawn == null) {
                debugprint(fmt("Could not find unoccupied point for bot {} within radius", i));
                continue;
            }
            occupied.add(spawn);
            spawnPoints.add(spawn);
        }

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        return new ArrayList<>(characterIds);
    }

    public static List<Integer> spawnFillerBots(int numBots, int mapId, Point p1, Point p2) {
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        Platform adHocPlatform = new Platform(minX, maxX, baseY, List.of(p1, p2), Platform.Type.FLAT);

        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} filler bots between ({},{}) and ({},{}) on map {}",
                numBots, p1.x, p1.y, p2.x, p2.y, mapId));

        List<Point> spawnPoints = findUnoccupiedPoints(adHocPlatform, occupied, numBots);
        occupied.addAll(spawnPoints);

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());
        double chairChance = 0.20;

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                        if (Math.random() < chairChance) {
                            // Sit only after the spawn drop-down/turn-around finishes
                            ExecutorServiceManager.getScheduledExecutorService().schedule(
                                    () -> botSitChair(fakechar, getRandomChairId()),
                                    BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500, TimeUnit.MILLISECONDS);
                        }
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create filler bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating filler bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for filler bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Filler bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Filler spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        debugprint(fmt("Filler bots spawned: {}", characterIds.size()));
        return new ArrayList<>(characterIds);
    }

    public static List<Integer> spawnFillerBotsLockedY(int numBots, int mapId, Point p1, Point p2) {
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        Platform adHocPlatform = new Platform(minX, maxX, baseY, List.of(p1, p2), Platform.Type.FLAT);

        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} filler bots (locked Y={}) between x={} and x={} on map {}",
                numBots, baseY, minX, maxX, mapId));

        List<Point> spawnPoints = findUnoccupiedPoints(adHocPlatform, occupied, numBots);
        for (Point sp : spawnPoints) {
            sp.y = baseY;
        }
        occupied.addAll(spawnPoints);

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());
        double chairChance = 0.20;

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                        if (Math.random() < chairChance) {
                            // Sit only after the spawn drop-down/turn-around finishes
                            ExecutorServiceManager.getScheduledExecutorService().schedule(
                                    () -> botSitChair(fakechar, getRandomChairId()),
                                    BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500, TimeUnit.MILLISECONDS);
                        }
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create filler bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating filler bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for filler bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Filler bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Filler spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        debugprint(fmt("Filler bots spawned (locked Y): {}", characterIds.size()));
        return new ArrayList<>(characterIds);
    }

    private static Point findUnoccupiedPointInRadius(Platform platform, List<Point> occupied, Point center, int radius) {
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts; i++) {
            Point candidate = findUnoccupiedPoint(platform, occupied);
            if (Math.abs(candidate.x - center.x) <= radius && Math.abs(candidate.y - center.y) <= radius) {
                return candidate;
            }
        }
        return null;
    }


    private static Character createBotWithRetry(Point spawn, int mapId, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Character fakechar = createBotPollReadiness(spawn, mapId);
                if (fakechar != null) {
                    return fakechar;
                }
                // If null but no exception, brief pause before retry
                if (attempt < maxRetries) {
                    Thread.sleep(200 * attempt); // Exponential-ish backoff
                }
            } catch (Exception e) {
                debugprint(fmt("Attempt {}/{} failed for bot at {}: {}",
                        attempt, maxRetries, spawn, e.getMessage()));
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(200 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public static void botMoveToPlatformAnyUnoccupiedSpot(Character fakechar, String platform) {
        int mapId = fakechar.getMapId();

        // todo verify platform name exists in mapId

        List<Point> occupiedPointsOnPlatform = getListOfCharacterCoordinates(getAllCharsOnPlatform(mapId, platform));
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform);
        Point unoccupiedPt = findUnoccupiedPoint(flatPlatform, occupiedPointsOnPlatform);
        MovementCommands.pathFinderBeta(fakechar, unoccupiedPt);
    }

    public static void botMoveToPlatformAnyUnoccupiedSpotAware(Character fakechar, String platform) {
        int mapId = fakechar.getMapId();

        List<Point> occupiedPointsOnPlatform = getListOfCharacterCoordinates(getAllCharsOnPlatform(mapId, platform));
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform);
        Point unoccupiedPt = findUnoccupiedPoint(flatPlatform, occupiedPointsOnPlatform);
        MovementCommands.pathFinderAware(fakechar, unoccupiedPt);
    }

    /**
     * Determines which platform a character is currently standing on.
     * <p>
     * Scans all platform CSV files for the character's map and finds the best match
     * based on position proximity. For flat platforms, checks if X is within bounds
     * and Y is close to baseY. For sloped platforms, uses interpolation to estimate
     * the expected Y at the character's X position.
     *
     * @param chr The character whose platform we want to find
     * @return The platform identifier (e.g., "m1", "m2") or null if not on any known platform
     */
    public static String getCurrentPlatform(Character chr) {
        Point currentPosition = chr.getPosition();
        int mapId = chr.getMapId();
        String platform = findPlatformAtPosition(mapId, currentPosition);
        //debugprint("MapID: ", mapId, "Platform: ", platform);
        return platform;
    }

    /**
     * Finds which platform a given position belongs to on a specific map.
     *
     * @param mapId    The map ID to search
     * @param position The position to check
     * @return The platform identifier or null if not found
     */
    public static String findPlatformAtPosition(int mapId, Point position) {
        List<String> platformIds = getAvailablePlatformIds(mapId);

        if (platformIds.isEmpty()) {
            return null;
        }

        String bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (String platformId : platformIds) {
            Platform platform = PlatformParser.parsePlatform(mapId, platformId);

            if (platform == null || platform.getSortedPoints().isEmpty()) {
                continue;
            }

            int score = calculatePlatformMatchScore(platform, position);

            // Score of -1 means position is definitely not on this platform
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                bestMatch = platformId;
            }
        }

        return bestMatch;
    }

    /**
     * Calculates how well a position matches a platform.
     * Lower score = better match. Returns -1 if position is definitely not on the platform.
     *
     * @param platform The platform to check against
     * @param position The position to evaluate
     * @return Match score (lower is better) or -1 if not a match
     */
    private static int calculatePlatformMatchScore(Platform platform, Point position) {
        int x = position.x;
        int y = position.y;

        // Check if X is within platform bounds (with tolerance)
        if (x < platform.getMinX() - X_TOLERANCE || x > platform.getMaxX() + X_TOLERANCE) {
            return -1; // Definitely not on this platform
        }

        // Get the expected Y at this X position
        int expectedY = platform.getYAtX(x);
        int yDifference = Math.abs(y - expectedY);

        // If Y difference is too large, not on this platform
        if (yDifference > Y_TOLERANCE) {
            return -1;
        }

        // Score is based on how close we are to the expected Y
        // Also factor in how well we're within the X bounds (prefer being solidly within bounds)
        int xDistanceFromCenter = Math.abs(x - (platform.getMinX() + platform.getMaxX()) / 2);

        // Combined score: Y accuracy is more important, X centering is secondary
        return yDifference * 10 + (xDistanceFromCenter / 10);
    }

    /**
     * Gets all available platform IDs for a given map by scanning the directory.
     *
     * @param mapId The map ID
     * @return List of platform IDs (e.g., ["m1", "m2", "m3"])
     */
    public static List<String> getAvailablePlatformIds(int mapId) {
        List<String> platformIds = new ArrayList<>();
        File mapDir = new File(BASE_PATH + "/map" + mapId);

        if (!mapDir.exists() || !mapDir.isDirectory()) {
            return platformIds;
        }

        File[] files = mapDir.listFiles((dir, name) -> name.endsWith(".csv"));

        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                // Remove .csv extension to get platform ID
                platformIds.add(name.substring(0, name.length() - 4));
            }
        }
        //debugprint("MapID", mapId, "Platform ids: ", platformIds);
        return platformIds;
    }

    public static List<String> getMainPlatformIds(int mapId) {
        return getAvailablePlatformIds(mapId).stream()
                .filter(id -> id.startsWith("m"))
                .collect(Collectors.toList());
    }

    public static List<String> getConnectorPlatformIds(int mapId) {
        return getAvailablePlatformIds(mapId).stream()
                .filter(id -> id.startsWith("c"))
                .collect(Collectors.toList());
    }

    /**
     * Gets all characters standing on a specific platform.
     * <p>
     * Cross-references all characters on the map against the platform's coordinate bounds.
     * Since platform coordinates are guide points (not every possible position), this method
     * uses interpolation and tolerance values to determine if a character is on the platform.
     *
     * @param mapId      The map ID
     * @param platformId The platform identifier (e.g., "m1")
     * @return List of characters currently on the specified platform
     */
    public static List<Character> getAllCharsOnPlatform(int mapId, String platformId) {
        List<Character> allCharsOnMap = getAllCharsOnMap(mapId);
        List<Character> charsOnPlatform = new ArrayList<>();

        Platform platform = PlatformParser.parsePlatform(mapId, platformId);

        if (platform == null || platform.getSortedPoints().isEmpty()) {
            return charsOnPlatform;
        }

        for (Character chr : allCharsOnMap) {
            if (isCharacterOnPlatform(chr, platform)) {
                charsOnPlatform.add(chr);
            }
        }
        //debugprint("CharsOnPlatform: ", charsOnPlatform, charsOnPlatform.size());
        return charsOnPlatform;
    }

    /**
     * Checks if a character is standing on a specific platform.
     *
     * @param chr      The character to check
     * @param platform The platform to check against
     * @return true if the character is on the platform, false otherwise
     */
    public static boolean isCharacterOnPlatform(Character chr, Platform platform) {
        Point position = chr.getPosition();
        return isPositionOnPlatform(position, platform);
    }

    /**
     * Checks if a position is on a specific platform.
     * <p>
     * For FLAT platforms: checks if X is within bounds and Y matches baseY (within tolerance).
     * For SLOPED platforms: checks if X is within bounds and Y matches the interpolated Y at that X.
     *
     * @param position The position to check
     * @param platform The platform to check against
     * @return true if the position is on the platform
     */
    public static boolean isPositionOnPlatform(Point position, Platform platform) {
        int x = position.x;
        int y = position.y;

        // Check X bounds with tolerance
        if (x < platform.getMinX() - X_TOLERANCE || x > platform.getMaxX() + X_TOLERANCE) {
            return false;
        }

        // Get expected Y at this X position (handles both flat and sloped)
        int expectedY = platform.getYAtX(x);

        // Check if actual Y is close enough to expected Y
        return Math.abs(y - expectedY) <= Y_TOLERANCE;
    }

    public static List<Character> getAllCharsOnMap(int mapId) {
        MapleMap map = getMapleMapById(mapId);
        return map.getAllPlayers();
    }

    public static List<Point> getCoordinatesOfAllCharsOnMap(int mapId) {
        return getListOfCharacterCoordinates(getAllCharsOnMap(mapId));
    }

    public static List<Point> getListOfCharacterCoordinates(List<Character> chars) {
        List<Point> characterCoords = new ArrayList<>();
        for (Character chr : chars) {
            characterCoords.add(chr.getPosition());
        }
        return characterCoords;
    }
}