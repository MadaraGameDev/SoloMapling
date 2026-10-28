package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.PartyCharacter;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.DialogueContextResolver;
import soloMapling.ArtificialPlayer.PartyQuestBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.BotLogger.log;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.EVENT_NAME_PREFIX;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.MAX_LEVEL;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.MIN_LEVEL;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.STAGE_AREAS;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.STAGE_COMBINATIONS;
import static soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants.STAGE_PROPERTY_PREFIX;
import static soloMapling.ArtificialPlayer.BotDialogueHandler.getRandomResolvedLine;

/**
 * Kerning Party Quest bot. A cohort of six (one per class slot, spawned by
 * {@code EnvironmentManager.spawnKPQBotsInKerning}) waits at an assigned spot in Kerning City, advertises for
 * a party and accepts a human's invite, then plays the quest next to the humans:
 * <ul>
 * <li>Stage 1: fight the mobs for coupons, answer the stage NPC's question with the matching coupon count
 * (each player is given a different question, see {@code EventInstanceManager.gridClaimUnique}) and drop the
 * resulting passes for the leader.</li>
 * <li>Stages 2-4: stand on the areas of the combination currently being tried, filling in around any humans
 * already standing on one, and move on to the next combination after a wrong check
 * ({@link #positionBotsForStage}).</li>
 * <li>Final stage and bonus room: attack the mobs and boss.</li>
 * </ul>
 * {@code assistRun} coordinates all the bots of one event instance (warping them to the leader's map and
 * assigning stage targets); each bot's own tick then walks to and works on its target. The bots and the stage
 * NPC script ({@code scripts/npc/9020001.js}) share state through event properties, see {@link KPQConstants}.
 * Bots return to Kerning City when the leader leaves the quest or logs out, and refuse invites outside the
 * quest's level range.
 */
public final class KPQBot extends BotSM implements PartyQuestBot {
    // Vertical padding of a boss's attack reach on the final stage. The shared default (300) reaches the
    // ledges one level above the boss, from which bots could hit it without ever joining it on its floor.
    private static final int FINAL_STAGE_BOSS_Y_PAD = 100;
    private static final int TOWN_MAP = 103000000;
    private static final int ENTRY_MAP = 103000800;
    private static final int FINAL_STAGE = 103000804;
    private static final int BONUS_MAP = 103000805;
    private static final int EXIT_MAP = 103000890;
    private static final int ITEM_PASS = 4001008;
    private static final int ITEM_COUPON = 4001007;
    private static final int[] STAGE_ONE_ANSWERS = {10, 35, 20, 25, 25, 30, 8};
    private static final int[] STAGE_ONE_WEIGHTS = {4, 1, 3, 2, 2, 1, 5};
    private static final Point STAGE_ONE_NPC = new Point(592, 106);
    private static final Point STAGE_ONE_EXIT = new Point(716, 106);
    private static final Point STAGE_FIVE_EXIT = new Point(305, -2717); // Cloto, who opens the bonus stage
    private static final long STAGE_FIVE_EMPTY_SETTLE_MS = 1_500;
    private static final long STAGE_ONE_TICK_MS = 400;
    private static final long COMBAT_TICK_MS = 300;
    private static final long COUPON_STALL_MS = 5_000;
    private static final long COUPON_RETRY_MS = 3_000;
    private static final ConcurrentHashMap<MapItem, Integer> COUPON_CLAIMS = new ConcurrentHashMap<>();
    private static final long RECRUIT_INTERVAL_MS = 18_000;
    private static final long HOME_RETRY_MS = 12_000;
    private static final long APPROACH_TIMEOUT_MS = 12_000;
    private static final long RETRY_TARGET_MS = 3_000;
    private static final int APPROACH_PROGRESS_PX = 20;
    private static final int TARGET_MOVE_PX = 40;
    private static final long PUZZLE_RETRY_MS = 10_000;
    private static final long PUZZLE_RETEST_MS = 10_000;
    private static final String DIALOGUE_PATH = "KPQBotDialogue.yaml";

    private long lastRecruitAt;
    private String lastRecruitLine;
    private volatile Point assignedSpace;
    private long homeMoveAt;
    private int combatTargetOid = -1;
    private int stalledTargetOid = -1;
    private long retryStalledTargetAt;
    private long approachProgressAt;
    private Point approachProgressPos;
    private Point moveTarget;
    private volatile int puzzleMapId = -1;
    private volatile Rectangle puzzleArea;
    private Point puzzleDestination;
    private Point puzzleProgressPos;
    private long puzzleProgressAt;
    private volatile boolean puzzleMoveIssued;
    private volatile boolean puzzleArrived;
    private boolean fastKpqCadence;
    private MapItem claimedCoupon;
    private Point claimedCouponProgressPos;
    private long claimedCouponProgressAt;
    private int stalledCouponOid = -1;
    private long retryStalledCouponAt;

    public KPQBot(Character character) {
        super(character);
        dialoguePath = DIALOGUE_PATH;
        botType = "KPQBot";
        assignedSpace = character.getMapId() == TOWN_MAP && character.getPosition() != null
                ? new Point(character.getPosition()) : null;
    }

    public void setAssignedSpace(Point position) {
        assignedSpace = position == null ? null : new Point(position);
        homeMoveAt = 0;
    }

    @Override
    public void checkPrioritySpeed() {
        Character chr = getChr();
        EventInstanceManager eim = chr.getEventInstance();
        int mapId = chr.getMapId();
        if (eim != null && eim.getName() != null && eim.getName().startsWith(EVENT_NAME_PREFIX)
                && (mapId == ENTRY_MAP || mapId == FINAL_STAGE || mapId == BONUS_MAP)) {
            fastKpqCadence = true;
            updateScheduleDelay(mapId == ENTRY_MAP ? STAGE_ONE_TICK_MS : COMBAT_TICK_MS);
            return;
        }
        if (fastKpqCadence) {
            fastKpqCadence = false;
            releaseCouponClaim();
            super.checkPrioritySpeed();
            if (checkMainPlayersOnMap()) setPriorityNormal();
            return;
        }
        super.checkPrioritySpeed();
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;

        Character chr = getChr();
        EventInstanceManager eim = chr.getEventInstance();
        boolean activeKpqRun = eim != null && eim.getName() != null
                && eim.getName().startsWith(EVENT_NAME_PREFIX);
        Character leader = chr.getParty() == null ? null : chr.getParty().getLeader().getPlayer();
        int mapId = chr.getMapId();
        if (mapId != TOWN_MAP && homeMoveAt != 0) {
            if (GCMovement.isEnabled(chr)) GCMovement.disable(chr);
            homeMoveAt = 0;
        }
        if (!activeKpqRun && isKpqMap(mapId)) {
            stopCombat();
            stopPuzzle();
            MapleMap town = chr.getClient().getChannelServer().getMapFactory().getMap(TOWN_MAP);
            if (town != null) chr.changeMap(town, town.getRandomPlayerSpawnpoint());
            return;
        }
        if (leader != null && isKpqMap(mapId)
                && ((leader.getMapId() == EXIT_MAP && mapId != EXIT_MAP)
                || !isKpqMap(leader.getMapId()))) {
            // The exit NPC moves the leader to the exit room first. Follow even if
            // event cleanup has already unregistered this bot or stage EXP leveled it out.
            stopCombat();
            stopPuzzle();
            MapleMap destination = chr.getClient().getChannelServer()
                    .getMapFactory().getMap(leader.getMapId());
            if (destination != null) {
                if (leader.getMapId() == EXIT_MAP) {
                    chr.changeMap(destination, destination.getPortal("st00"));
                } else {
                    chr.changeMap(destination, destination.getRandomPlayerSpawnpoint());
                }
            }
            return;
        }
        // Characters can level after being assigned this bot type. Never accept a KPQ invite
        // once outside the quest's level range, but finish an active run.
        if ((chr.getLevel() < MIN_LEVEL || chr.getLevel() > MAX_LEVEL) && !activeKpqRun) {
            stopCombat();
            stopPuzzle();
            if (BotPartyQueue.getInstance().hasPendingInvite(chr)) {
                BotPartyCommands.botRejectPartyInvite(chr);
            }
            return;
        }
        if (chr.getParty() == null) {
            stopCombat();
            stopPuzzle();
            if (chr.getMapId() == TOWN_MAP) {
                if (!activeKpqRun) returnToAssignedSpace();
                BotPartyLogic.checkPartyQueue(chr);
                long now = System.currentTimeMillis();
                if (chr.getParty() == null && now - lastRecruitAt >= RECRUIT_INTERVAL_MS) {
                    String line = nextRecruitLine(chr);
                    if (line != null) {
                        String profile = "lvl " + chr.getLevel() + " " + kpqJobName(chr);
                        SocialCommands.BotSpeak(chr, String.format(line, profile));
                        lastRecruitLine = line;
                        lastRecruitAt = now;
                    }
                }
            }
            return;
        }

        if (!activeKpqRun) {
            stopCombat();
            stopPuzzle();
            if (mapId == TOWN_MAP) returnToAssignedSpace();
            return;
        }
        try {
            assistRun(eim);
            mapId = chr.getMapId();
            if (mapId == FINAL_STAGE) {
                BotAttackDriver.setBossYPad(chr.getId(), FINAL_STAGE_BOSS_Y_PAD);
            } else {
                BotAttackDriver.clearBossYPad(chr.getId());
            }
            if (leader != null && mapId == ENTRY_MAP && mapId == leader.getMapId()) {
                stopPuzzle();
                stageOneTick(eim, leader);
            } else if (leader != null && mapId == FINAL_STAGE && mapId == leader.getMapId()) {
                stopPuzzle();
                if (stageFiveClearReady(eim)) {
                    if (combatTargetOid >= 0) stopCombat();
                    moveToPoint(STAGE_FIVE_EXIT);
                } else {
                    combatTick();
                }
            } else if (leader != null && mapId == BONUS_MAP && mapId == leader.getMapId()) {
                stopPuzzle();
                combatTick();
            } else if (leader != null && mapId == leader.getMapId()
                    && mapId >= 103000801 && mapId <= 103000803) {
                puzzleTick();
            } else {
                stopCombat();
                stopPuzzle();
            }
        } catch (Exception ex) {
            stopCombat();
            stopPuzzle();
            log("[KPQBot " + chr.getName() + "] run assist failed: " + ex.getMessage());
        }
    }

    private void returnToAssignedSpace() {
        if (assignedSpace == null) return;
        Character chr = getChr();
        Point position = chr.getPosition();
        if (position == null) return;
        if (position.distanceSq(assignedSpace) <= 20 * 20) {
            if (homeMoveAt != 0 && GCMovement.isEnabled(chr)) GCMovement.stop(chr);
            homeMoveAt = 0;
        } else {
            long now = System.currentTimeMillis();
            if (homeMoveAt == 0 || now - homeMoveAt >= HOME_RETRY_MS) {
                GCMovement.move(chr, assignedSpace.x, assignedSpace.y);
                homeMoveAt = now;
            }
        }
    }

    private static boolean isKpqMap(int mapId) {
        return (mapId >= 103000800 && mapId <= 103000805) || mapId == 103000890;
    }

    public static void returnPartyBotsToKerningOnHumanLogout(Character human) {
        if (human == null || CharacterStorage.botLoggedIn(human.getId())
                || !isKpqMap(human.getMapId()) || human.getParty() == null) return;

        for (PartyCharacter member : human.getParty().getMembers()) {
            BotSM activeBot = CharacterStorage.getBotById(member.getId());
            if (!(activeBot instanceof KPQBot bot)) continue;
            Character chr = bot.getChr();
            if (!isKpqMap(chr.getMapId())) continue;

            bot.stopCombat();
            bot.stopPuzzle();
            MapleMap town = chr.getClient().getChannelServer().getMapFactory().getMap(TOWN_MAP);
            chr.changeMap(town, town.getRandomPlayerSpawnpoint());
        }
    }

    private static void assistRun(EventInstanceManager eim) {
        synchronized (eim) {
            List<KPQBot> bots = new ArrayList<>();
            for (Character participant : eim.getPlayers()) {
                BotSM bot = CharacterStorage.getBotById(participant.getId());
                if (bot instanceof KPQBot kpqBot) bots.add(kpqBot);
            }
            bots.sort((a, b) -> Integer.compare(a.getChr().getId(), b.getChr().getId()));
            Character leader = eim.getLeader();
            if (leader == null) return;
            int mapId = leader.getMapId();
            if (mapId == ENTRY_MAP) {
                MapleMap firstMap = eim.getMapInstance(ENTRY_MAP);
                for (KPQBot bot : bots) {
                    if (firstMap != null && bot.getChr().getMap() != firstMap) {
                        bot.stopCombat();
                        bot.stopPuzzle();
                        bot.getChr().changeMap(firstMap, firstMap.getPortal("st00"));
                    }
                }
            } else if (mapId >= 103000801 && mapId <= 103000803) {
                if (!bots.isEmpty()) {
                    positionBotsForStage(eim, bots, mapId - 103000801);
                }
            } else if (mapId == FINAL_STAGE) {
                MapleMap finalMap = eim.getMapInstance(FINAL_STAGE);
                if (finalMap == null) return;
                for (KPQBot bot : bots) {
                    if (bot.getChr().getMapId() != FINAL_STAGE) {
                        bot.stopCombat();
                        bot.stopPuzzle();
                        bot.getChr().changeMap(finalMap, finalMap.getRandomPlayerSpawnpoint());
                    }
                }
            } else if (mapId == BONUS_MAP && eim.isEventCleared()) {
                MapleMap bonusMap = eim.getMapInstance(BONUS_MAP);
                if (bonusMap == null) return;
                for (KPQBot bot : bots) {
                    Character chr = bot.getChr();
                    if (chr.getMapId() != BONUS_MAP) {
                        bot.stopCombat();
                        bot.stopPuzzle();
                        // Headless bots do not get client gravity after a warp. Use the
                        // same entrance as kpq4.js and land on its foothold.
                        Point entrance = bonusMap.getPortal("st00").getPosition();
                        Point ground = GCMovement.groundPointBelow(bonusMap, entrance.x, entrance.y);
                        chr.changeMap(bonusMap, ground != null ? ground : entrance);
                    }
                    // The shared headless bot client has no current player. Event rewards
                    // use that client for inventory changes, so awarding one here aborts
                    // the tick before bonus-map combat can run. Human rewards remain on
                    // the normal NPC path.
                }
            }
        }
    }

    private static String kpqJobName(Character chr) {
        return switch (chr.getJob().getId()) {
            case 200 -> "mage";
            default -> chr.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private String nextRecruitLine(Character bot) {
        BotDialogueHandler.DialogueConstructor dialogue =
                BotDialogueHandler.getDialogueCon(dialoguePath, botType, "Recruit");
        if (dialogue == null) return null;
        List<String> available = new ArrayList<>();
        for (String line : dialogue.getDialogue()) {
            DialogueContextResolver.fill(line, bot).ifPresent(available::add);
        }
        if (available.isEmpty()) return null;
        List<String> choices = new ArrayList<>(available);
        if (lastRecruitLine != null && choices.size() > 1) choices.removeIf(lastRecruitLine::equals);
        if (choices.isEmpty()) choices = available;
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    private static void sayRandom(Character bot, String node, Object value) {
        String line = getRandomResolvedLine(DIALOGUE_PATH, "KPQBot", node, bot, null);
        if (line != null) SocialCommands.BotSpeak(bot, String.format(line, value));
    }

    public static void announceStageClear(EventInstanceManager eim, int mapId) {
        if (eim == null) return;
        String key = "kpqbot_chat_clear_" + mapId;
        if ("true".equals(eim.getProperty(key))) return;
        eim.setProperty(key, "true");
        MapleMap map = eim.getMapInstance(mapId);
        if (map == null) return;
        List<Character> bots = new ArrayList<>();
        for (Character participant : eim.getPlayers()) {
            if (participant.getMap() == map
                    && CharacterStorage.getBotById(participant.getId()) instanceof KPQBot) {
                bots.add(participant);
            }
        }
        if (bots.isEmpty()) return;
        Character speaker = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
        String line = getRandomResolvedLine(DIALOGUE_PATH, "KPQBot", "StageClear", speaker, null);
        if (line != null) SocialCommands.BotSpeak(speaker, line);
    }

    private void combatTick() {
        Character bot = getChr();
        MapleMap map = bot.getMap();
        Point position = bot.getPosition();
        if (map == null || position == null) return;

        Monster target = currentCombatTarget(map);
        if (target == null) {
            target = nearestCombatTarget(map, position);
            if (target == null) {
                if (GCMovement.isEnabled(bot)) GCMovement.stop(bot);
                return;
            }
            combatTargetOid = target.getObjectId();
            approachProgressPos = position;
            approachProgressAt = System.currentTimeMillis();
            moveTarget = null;
        }

        // The attack driver has the authoritative skill reach and ledge checks. Try it
        // before moving; a landed swing means this bot is already close enough.
        BotAttackDriver.AttackResult attack = BotAttackDriver.botAttack(bot);
        if (attack.hit()) {
            if (GCMovement.isEnabled(bot)) GCMovement.stop(bot);
            moveTarget = null;
            approachProgressPos = bot.getPosition();
            approachProgressAt = System.currentTimeMillis();
            if (attack.killed()) combatTargetOid = -1;
            return;
        }
        if ("on cooldown".equals(attack.reason())) return;

        Point mobPos = target.getPosition();
        if (mobPos == null) {
            combatTargetOid = -1;
            return;
        }
        Point ground = GCMovement.groundPointBelow(map, mobPos.x, mobPos.y);
        Point destination = ground != null ? ground : mobPos;
        long now = System.currentTimeMillis();
        if (approachProgressPos == null || position.distance(approachProgressPos) >= APPROACH_PROGRESS_PX) {
            approachProgressPos = position;
            approachProgressAt = now;
        } else if (moveTarget != null && now - approachProgressAt >= APPROACH_TIMEOUT_MS) {
            stalledTargetOid = combatTargetOid;
            retryStalledTargetAt = now + RETRY_TARGET_MS;
            combatTargetOid = -1;
            moveTarget = null;
            if (GCMovement.isEnabled(bot)) GCMovement.stop(bot);
            return;
        }

        if (moveTarget == null || moveTarget.distance(destination) >= TARGET_MOVE_PX) {
            GCMovement.move(bot, destination.x, destination.y);
            moveTarget = new Point(destination);
        }
    }

    private Monster currentCombatTarget(MapleMap map) {
        MapObject object = combatTargetOid < 0 ? null : map.getMapObject(combatTargetOid);
        return object instanceof Monster monster && monster.isAlive() ? monster : null;
    }

    private Monster nearestCombatTarget(MapleMap map, Point position) {
        Monster nearest = null;
        double bestDistance = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (Monster monster : map.getAllMonsters()) {
            if (!monster.isAlive() || monster.getPosition() == null
                    || (monster.getObjectId() == stalledTargetOid && now < retryStalledTargetAt)) continue;
            double distance = position.distanceSq(monster.getPosition());
            if (distance < bestDistance) {
                nearest = monster;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    private void stopCombat() {
        boolean wasMovingForCombat = combatTargetOid >= 0 || moveTarget != null;
        combatTargetOid = -1;
        moveTarget = null;
        approachProgressPos = null;
        if (wasMovingForCombat && GCMovement.isEnabled(getChr())) GCMovement.disable(getChr());
    }

    private void assignPuzzleTarget(MapleMap map, Rectangle area, boolean ropeStage) {
        Character bot = getChr();
        if (puzzleMapId == map.getId() && area.equals(puzzleArea)
                && bot.getMap() == map) return;

        stopCombat();
        stopPuzzle();
        if (bot.getMap() != map) {
            // Follow the same stage entrance as a real party member. Navigation
            // starts there rather than warping directly to the answer.
            bot.changeMap(map, map.getPortal("st00"));
        }
        Point center = new Point(area.x + area.width / 2, area.y + area.height / 2);
        // Sample the floor from the zone's bottom edge: from the center, a stump or other raised
        // foothold inside the zone can be nearer than the platform surface the zone is meant to cover.
        Point ground = ropeStage ? null
                : GCMovement.groundPointBelow(map, center.x, area.y + area.height - 1);
        puzzleDestination = ground != null && area.contains(ground) ? ground : center;
        puzzleMapId = map.getId();
        puzzleArea = area;
        puzzleProgressPos = bot.getPosition();
        puzzleProgressAt = System.currentTimeMillis();
        puzzleMoveIssued = false;
        puzzleArrived = false;
    }

    private void puzzleTick() {
        Character bot = getChr();
        if (puzzleMapId != bot.getMapId() || puzzleArea == null || puzzleDestination == null) return;
        Point position = bot.getPosition();
        if (position == null) return;
        if (puzzleArea.contains(position)) {
            if (!puzzleArrived && GCMovement.isEnabled(bot)) GCMovement.stop(bot);
            puzzleArrived = true;
            return;
        }
        puzzleArrived = false;

        long now = System.currentTimeMillis();
        // Sideways motion or a back-and-forth wiggle is not progress toward the target.
        if (puzzleProgressPos == null || position.distance(puzzleDestination) + APPROACH_PROGRESS_PX
                <= puzzleProgressPos.distance(puzzleDestination)) {
            puzzleProgressPos = position;
            puzzleProgressAt = now;
        }
        if (puzzleMoveIssued && now - puzzleProgressAt >= PUZZLE_RETRY_MS) {
            Point center = new Point(puzzleArea.x + puzzleArea.width / 2,
                    puzzleArea.y + puzzleArea.height / 2);
            GCMovement.teleportToExact(bot, center.x, center.y);
            puzzleProgressPos = bot.getPosition();
            puzzleProgressAt = now;
            puzzleMoveIssued = false;
            return;
        }
        if (!puzzleMoveIssued) {
            GCMovement.move(bot, puzzleDestination.x, puzzleDestination.y);
            puzzleMoveIssued = true;
            puzzleProgressPos = bot.getPosition();
            puzzleProgressAt = now;
        }
    }

    private void stopPuzzle() {
        if (puzzleMapId >= 0 && GCMovement.isEnabled(getChr())) GCMovement.disable(getChr());
        puzzleMapId = -1;
        puzzleArea = null;
        puzzleDestination = null;
        puzzleProgressPos = null;
        puzzleMoveIssued = false;
        puzzleArrived = false;
    }

    @Override
    public synchronized void stopScheduledTask() {
        BotAttackDriver.clearBossYPad(getChr().getId());
        releaseCouponClaim();
        stopCombat();
        stopPuzzle();
        super.stopScheduledTask();
    }

    private void stageOneTick(EventInstanceManager eim, Character leader) {
        Character bot = getChr();
        if (eim.getProperty("1stageclear") != null || stageOnePassesReady(eim, leader)) {
            releaseCouponClaim();
            if (combatTargetOid >= 0) stopCombat();
            moveToPoint(STAGE_ONE_EXIT);
            return;
        }
        int assignment = eim.gridCheck(bot);
        if (assignment == -1) {
            if (combatTargetOid >= 0) stopCombat();
            if (!moveToPoint(STAGE_ONE_NPC)) return;
            assignment = eim.gridClaimUnique(bot, STAGE_ONE_WEIGHTS);
            sayRandom(bot, "Assignment", STAGE_ONE_ANSWERS[assignment - 1]);
            return;
        }
        if (assignment == 0) {
            releaseCouponClaim();
            if (bot.countItem(ITEM_PASS) > 0) {
                if (combatTargetOid >= 0) stopCombat();
                if (!moveToPoint(STAGE_ONE_EXIT)) return;
                Inventory inventory = bot.getInventory(InventoryType.ETC);
                Item pass = inventory.findById(ITEM_PASS);
                if (pass != null) {
                    inventory.removeItem(pass.getPosition());
                    bot.getMap().spawnItemDropNoExpire(bot, leader, new Item(ITEM_PASS, (short) 0, (short) 1),
                            bot.getPosition(), false, false);
                    sayRandom(bot, "PassDrop", leader.getName());
                }
            } else {
                // A bot with a pass helps others by killing mobs and leaves coupons for them.
                combatTick();
            }
            return;
        }

        int answer = STAGE_ONE_ANSWERS[assignment - 1];
        if (bot.countItem(ITEM_COUPON) == answer) {
            releaseCouponClaim();
            if (combatTargetOid >= 0) stopCombat();
            if (!moveToPoint(STAGE_ONE_NPC)) return;
            Inventory inventory = bot.getInventory(InventoryType.ETC);
            if (!addStageOneItem(inventory, ITEM_PASS)) return;
            removeStageOneItems(inventory, ITEM_COUPON, answer);
            eim.gridInsert(bot, 0);
            return;
        }
        if (bot.countItem(ITEM_COUPON) < answer && lootStageOneCoupon(answer)) return;
        combatTick();
    }

    private static boolean stageOnePassesReady(EventInstanceManager eim, Character leader) {
        for (Character member : eim.getPlayers()) {
            if (CharacterStorage.getBotById(member.getId()) instanceof KPQBot
                    && (eim.gridCheck(member) != 0 || member.countItem(ITEM_PASS) > 0)) return false;
        }
        int passes = leader.countItem(ITEM_PASS);
        MapleMap map = eim.getMapInstance(ENTRY_MAP);
        if (map == null) return false;
        for (MapObject object : map.getMapObjects()) {
            if (object instanceof MapItem item && !item.isPickedUp()
                    && item.getItem() != null && item.getItemId() == ITEM_PASS) {
                passes += item.getItem().getQuantity();
            }
        }
        return passes >= 3;
    }

    private static boolean stageFiveClearReady(EventInstanceManager eim) {
        synchronized (eim) {
            MapleMap map = eim.getMapInstance(FINAL_STAGE);
            if (map == null) return false;
            if (map.countMonsters() != 0) {
                eim.setProperty("kpqbot_stage5EmptySince", "0");
                return false;
            }
            long now = System.currentTimeMillis();
            long since = eventLongProperty(eim, "kpqbot_stage5EmptySince", 0);
            if (since == 0) {
                eim.setProperty("kpqbot_stage5EmptySince", Long.toString(now));
                return false;
            }
            // King Slime spawns its slimes after its death animation. Wait through
            // that gap before treating an empty map as the end of the fight.
            return now - since >= STAGE_FIVE_EMPTY_SETTLE_MS;
        }
    }

    private boolean moveToPoint(Point destination) {
        Character bot = getChr();
        Point position = bot.getPosition();
        if (position == null) return false;
        if (position.distanceSq(destination) <= 40 * 40) {
            if (GCMovement.isEnabled(bot)) GCMovement.stop(bot);
            moveTarget = null;
            return true;
        }
        if (moveTarget == null || moveTarget.distanceSq(destination) > TARGET_MOVE_PX * TARGET_MOVE_PX) {
            GCMovement.move(bot, destination.x, destination.y);
            moveTarget = new Point(destination);
        }
        return false;
    }

    private boolean lootStageOneCoupon(int answer) {
        Character bot = getChr();
        Point position = bot.getPosition();
        if (position == null) return false;
        int remaining = answer - bot.countItem(ITEM_COUPON);
        if (claimedCoupon != null && (claimedCoupon.isPickedUp()
                || bot.getMap().getMapObject(claimedCoupon.getObjectId()) != claimedCoupon
                || !canPickStageOneCoupon(bot, claimedCoupon, remaining))) {
            releaseCouponClaim();
        }
        if (claimedCoupon == null) {
            List<MapItem> available = new ArrayList<>();
            for (MapObject object : bot.getMap().getMapObjectsInRange(position, 2000 * 2000,
                    Arrays.asList(MapObjectType.ITEM))) {
                MapItem item = (MapItem) object;
                if (canPickStageOneCoupon(bot, item, remaining)
                        && (item.getObjectId() != stalledCouponOid
                        || System.currentTimeMillis() >= retryStalledCouponAt)) {
                    available.add(item);
                }
            }
            available.sort((a, b) -> Double.compare(position.distanceSq(a.getPosition()),
                    position.distanceSq(b.getPosition())));
            for (MapItem item : available) {
                if (COUPON_CLAIMS.putIfAbsent(item, bot.getId()) == null) {
                    claimedCoupon = item;
                    claimedCouponProgressPos = position;
                    claimedCouponProgressAt = System.currentTimeMillis();
                    break;
                }
            }
        }
        if (claimedCoupon == null) return false;
        Point drop = claimedCoupon.getPosition();
        double distance = position.distanceSq(drop);
        if (distance > 85 * 85) {
            long now = System.currentTimeMillis();
            if (claimedCouponProgressPos == null
                    || position.distanceSq(claimedCouponProgressPos) >= APPROACH_PROGRESS_PX * APPROACH_PROGRESS_PX) {
                claimedCouponProgressPos = position;
                claimedCouponProgressAt = now;
            } else if (now - claimedCouponProgressAt >= COUPON_STALL_MS) {
                stalledCouponOid = claimedCoupon.getObjectId();
                retryStalledCouponAt = now + COUPON_RETRY_MS;
                releaseCouponClaim();
                return true;
            }
            if (moveTarget == null || moveTarget.distanceSq(drop) > TARGET_MOVE_PX * TARGET_MOVE_PX) {
                GCMovement.move(bot, drop.x, drop.y);
                moveTarget = new Point(drop);
            }
            return true;
        }
        MapItem coupon = claimedCoupon;
        coupon.lockItem();
        try {
            if (coupon.isPickedUp() || bot.countItem(ITEM_COUPON) >= answer) return true;
            Inventory inventory = bot.getInventory(InventoryType.ETC);
            if (!addStageOneItem(inventory, ITEM_COUPON, coupon.getItem().getQuantity())) return true;
            DropCommands.botLootSingleDrop(bot, coupon);
        } finally {
            coupon.unlockItem();
            if (claimedCoupon == coupon) releaseCouponClaim();
        }
        return true;
    }

    private static boolean canPickStageOneCoupon(Character bot, MapItem item, int remaining) {
        return item != null && !item.isPickedUp() && item.getPosition() != null
                && item.getItem() != null && item.getItemId() == ITEM_COUPON
                && item.getItem().getQuantity() > 0 && item.getItem().getQuantity() <= remaining
                && item.canBePickedBy(bot);
    }

    private void releaseCouponClaim() {
        if (claimedCoupon != null) {
            if (moveTarget != null && claimedCoupon.getPosition() != null
                    && moveTarget.distanceSq(claimedCoupon.getPosition()) <= TARGET_MOVE_PX * TARGET_MOVE_PX) {
                if (GCMovement.isEnabled(getChr())) GCMovement.stop(getChr());
                moveTarget = null;
            }
            COUPON_CLAIMS.remove(claimedCoupon, getChr().getId());
            claimedCoupon = null;
            claimedCouponProgressPos = null;
        }
    }

    private static boolean addStageOneItem(Inventory inventory, int itemId) {
        return addStageOneItem(inventory, itemId, (short) 1);
    }

    private static boolean addStageOneItem(Inventory inventory, int itemId, short quantity) {
        Item stack = inventory.findById(itemId);
        if (stack != null) {
            stack.setQuantity((short) (stack.getQuantity() + quantity));
            return true;
        }
        return inventory.addItem(new Item(itemId, (short) 0, quantity)) != -1;
    }

    private static void removeStageOneItems(Inventory inventory, int itemId, int quantity) {
        for (Item item : inventory.listById(itemId)) {
            int taken = Math.min(quantity, item.getQuantity());
            inventory.removeItem(item.getPosition(), (short) taken, false);
            quantity -= taken;
            if (quantity == 0) return;
        }
    }

    /**
     * Stages 2-4: decides which combination the bots act out and which areas they must occupy. An answer
     * needs three occupied areas and humans standing on an area count toward it, so the bots take the rest.
     * <p>
     * State lives in event properties under {@code STAGE_PROPERTY_PREFIX + stage}:
     * <ul>
     * <li>{@code Attempt}: index of the combination currently being tried, -1 for none.</li>
     * <li>{@code Tried<i>}: combination i was already checked and rejected. The NPC script reports a rejected
     * check through {@code WrongChecks} and {@code CheckedMask}; {@code SeenWrongChecks} counts how many of
     * those this method has already consumed.</li>
     * <li>{@code ExhaustedSince}: since when every compatible combination had been tried. After
     * {@code PUZZLE_RETEST_MS} the tried flags are cleared and the search starts over, so the bots keep
     * retrying instead of giving up.</li>
     * </ul>
     * Order: consume a newly reported wrong check; keep the current attempt while it is still valid (untried,
     * fits where the humans stand, and the bots left are enough); otherwise take the next untried compatible
     * combination after it. With none workable the bots wait at the stage exit and may hint a stuck human.
     */
    private static void positionBotsForStage(EventInstanceManager eim, List<KPQBot> bots, int stageIndex) {
        List<Rectangle> areas = STAGE_AREAS.get(stageIndex);
        List<int[]> combinations = STAGE_COMBINATIONS.get(stageIndex);
        int stage = stageIndex + 2;
        String prefix = STAGE_PROPERTY_PREFIX + stage;
        Character leader = eim.getLeader();
        if (leader == null) return;
        MapleMap map = eim.getMapInstance(leader.getMapId());
        if (map == null) return;
        Point exit = map.getPortal("next00").getPosition();
        Point ground = GCMovement.groundPointBelow(map, exit.x, exit.y);
        Point destination = ground != null ? ground : exit;
        Rectangle exitArea = new Rectangle(destination.x - 16, destination.y - 16, 33, 33);

        if (eim.getProperty(stage + "stageclear") != null) {
            assignStageTargets(bots, map, List.of(), exitArea, false);
            return;
        }

        int wrongChecks = eventIntProperty(eim, prefix + "WrongChecks", 0);
        int seenWrongChecks = eventIntProperty(eim, prefix + "SeenWrongChecks", 0);
        int current = eventIntProperty(eim, prefix + "Attempt", -1);
        int searchAfter = current;
        if (wrongChecks > seenWrongChecks) {
            int checkedMask = eventIntProperty(eim, prefix + "CheckedMask", -1);
            int checked = -1;
            for (int i = 0; i < combinations.size(); i++) {
                if (checkedMask == combinationMask(combinations.get(i))) {
                    checked = i;
                    break;
                }
            }
            if (checked >= 0 && checked < combinations.size()) {
                eim.setProperty(prefix + "Tried" + checked, "true");
            }
            eim.setProperty(prefix + "SeenWrongChecks", wrongChecks);
            if (checked >= 0 && checked == current) current = -1;
        }

        int[] humanCounts = new int[areas.size()];
        List<Character> positionedHumans = new ArrayList<>();
        for (Character participant : eim.getPlayers()) {
            if (CharacterStorage.getBotById(participant.getId()) != null
                    || participant.getMap() != map || participant.getPosition() == null) continue;
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).contains(participant.getPosition())) {
                    humanCounts[i]++;
                    positionedHumans.add(participant);
                    break;
                }
            }
        }
        positionedHumans.sort((a, b) -> Integer.compare(a.getId(), b.getId()));

        int neededBots = 3 - positionedHumans.size();
        boolean currentValid = current >= 0 && current < combinations.size()
                && !"true".equals(eim.getProperty(prefix + "Tried" + current))
                && neededBots >= 0 && neededBots <= bots.size()
                && accommodatesHumans(combinations.get(current), humanCounts);
        if (!currentValid) {
            current = -1;
            for (int step = 0; step < combinations.size(); step++) {
                int index = Math.floorMod(searchAfter + 1 + step, combinations.size());
                if ("true".equals(eim.getProperty(prefix + "Tried" + index))) continue;
                if (neededBots >= 0 && neededBots <= bots.size()
                        && accommodatesHumans(combinations.get(index), humanCounts)) {
                    current = index;
                    break;
                }
            }
            if (current < 0 && neededBots >= 0 && neededBots <= bots.size()) {
                boolean compatible = false;
                for (int[] combination : combinations) {
                    if (accommodatesHumans(combination, humanCounts)) {
                        compatible = true;
                        break;
                    }
                }
                if (compatible) {
                    long now = System.currentTimeMillis();
                    long since = eventLongProperty(eim, prefix + "ExhaustedSince", 0);
                    if (since == 0) {
                        eim.setProperty(prefix + "ExhaustedSince", Long.toString(now));
                    } else if (now - since >= PUZZLE_RETEST_MS) {
                        for (int i = 0; i < combinations.size(); i++) {
                            eim.setProperty(prefix + "Tried" + i, "false");
                        }
                        eim.setProperty(prefix + "ExhaustedSince", "0");
                        eim.setProperty(prefix + "BlockedSignature", "");
                        for (int step = 0; step < combinations.size(); step++) {
                            int index = Math.floorMod(searchAfter + 1 + step, combinations.size());
                            if (accommodatesHumans(combinations.get(index), humanCounts)) {
                                current = index;
                                break;
                            }
                        }
                    }
                }
            } else {
                eim.setProperty(prefix + "ExhaustedSince", "0");
            }
            eim.setProperty(prefix + "Attempt", current);
        }

        if (current < 0) {
            assignStageTargets(bots, map, List.of(), exitArea, false);
            maybeHintStuckHuman(eim, bots, stageIndex, prefix, combinations,
                    humanCounts, positionedHumans, areas);
            return;
        }
        eim.setProperty(prefix + "ExhaustedSince", "0");
        eim.setProperty(prefix + "BlockedSignature", "");
        List<Rectangle> targets = new ArrayList<>();
        int[] combination = combinations.get(current);
        for (int i = 0; i < combination.length; i++) {
            if (combination[i] == 1 && humanCounts[i] == 0) targets.add(areas.get(i));
        }
        assignStageTargets(bots, map, targets, exitArea, stageIndex == 0);
    }

    private static int eventIntProperty(EventInstanceManager eim, String key, int fallback) {
        String value = eim.getProperty(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int combinationMask(int[] combination) {
        int mask = 0;
        for (int i = 0; i < combination.length; i++) {
            if (combination[i] == 1) mask |= 1 << i;
        }
        return mask;
    }

    private static boolean accommodatesHumans(int[] combination, int[] humanCounts) {
        for (int i = 0; i < combination.length; i++) {
            if (humanCounts[i] > combination[i]) return false;
        }
        return true;
    }

    private static void maybeHintStuckHuman(EventInstanceManager eim, List<KPQBot> bots,
                                            int stageIndex, String prefix, List<int[]> combinations,
                                            int[] humanCounts, List<Character> humans,
                                            List<Rectangle> areas) {
        if (humans.isEmpty() || 3 - humans.size() > bots.size()
                || "true".equals(eim.getProperty(prefix + "HintSent"))) return;
        boolean hasUntested = false;
        for (int i = 0; i < combinations.size(); i++) {
            if ("true".equals(eim.getProperty(prefix + "Tried" + i))) continue;
            hasUntested = true;
            if (accommodatesHumans(combinations.get(i), humanCounts)) return;
        }
        if (!hasUntested) return;

        StringBuilder signature = new StringBuilder();
        for (Character human : humans) {
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).contains(human.getPosition())) {
                    signature.append(human.getId()).append(':').append(i).append(';');
                    break;
                }
            }
        }
        String currentSignature = signature.toString();
        long now = System.currentTimeMillis();
        if (!currentSignature.equals(eim.getProperty(prefix + "BlockedSignature"))) {
            eim.setProperty(prefix + "BlockedSignature", currentSignature);
            eim.setProperty(prefix + "BlockedSince", Long.toString(now));
            return;
        }
        long since = eventLongProperty(eim, prefix + "BlockedSince", now);
        if (now - since <= 5_000) return;

        Character target = humans.get(0);
        boolean found = false;
        for (Character human : humans) {
            int[] remaining = humanCounts.clone();
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).contains(human.getPosition())) {
                    remaining[i]--;
                    break;
                }
            }
            for (int i = 0; i < combinations.size(); i++) {
                if (!"true".equals(eim.getProperty(prefix + "Tried" + i))
                        && accommodatesHumans(combinations.get(i), remaining)) {
                    target = human;
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        eim.setProperty(prefix + "HintSent", "true");
        String positionName = stageIndex == 0 ? "rope" : stageIndex == 1 ? "platform" : "barrel";
        String hint = getRandomResolvedLine(DIALOGUE_PATH, "KPQBot", "PuzzleHint", bots.get(0).getChr(), null);
        if (hint != null) SocialCommands.BotSpeak(bots.get(0).getChr(), String.format(hint, target.getName(), positionName));
    }

    private static long eventLongProperty(EventInstanceManager eim, String key, long fallback) {
        String value = eim.getProperty(key);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void assignStageTargets(List<KPQBot> bots, MapleMap map,
                                           List<Rectangle> neededAreas, Rectangle exitArea,
                                           boolean ropeStage) {
        List<Rectangle> targets = new ArrayList<>(neededAreas);
        while (targets.size() < bots.size()) targets.add(exitArea);
        if (targets.size() != bots.size()) return;
        PuzzleAssignment best = new PuzzleAssignment(bots.size());
        chooseStageTargets(bots, map, targets, 0, new int[bots.size()],
                new boolean[targets.size()], best);
        for (int i = 0; i < bots.size(); i++) {
            Rectangle target = targets.get(best.indexes[i]);
            bots.get(i).assignPuzzleTarget(map, target, ropeStage && target != exitArea);
        }
    }

    private static void chooseStageTargets(List<KPQBot> bots, MapleMap map,
                                           List<Rectangle> targets, int botIndex,
                                           int[] assignment, boolean[] used,
                                           PuzzleAssignment best) {
        if (botIndex == bots.size()) {
            int retained = 0;
            double travel = 0;
            Point entrance = map.getPortal("st00").getPosition();
            for (int i = 0; i < bots.size(); i++) {
                KPQBot bot = bots.get(i);
                Rectangle area = targets.get(assignment[i]);
                boolean onMap = bot.getChr().getMap() == map;
                Point position = onMap ? bot.getChr().getPosition() : entrance;
                if (onMap && (area.equals(bot.puzzleArea)
                        || position != null && area.contains(position))) retained++;
                Point center = new Point(area.x + area.width / 2, area.y + area.height / 2);
                travel += (position == null ? entrance : position).distanceSq(center);
            }
            if (retained > best.retained || retained == best.retained && travel < best.travel) {
                best.retained = retained;
                best.travel = travel;
                best.indexes = assignment.clone();
            }
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            assignment[botIndex] = i;
            chooseStageTargets(bots, map, targets, botIndex + 1, assignment, used, best);
            used[i] = false;
        }
    }

    private static final class PuzzleAssignment {
        private int[] indexes;
        private int retained = -1;
        private double travel = Double.MAX_VALUE;

        private PuzzleAssignment(int count) {
            indexes = new int[count];
        }
    }

}
