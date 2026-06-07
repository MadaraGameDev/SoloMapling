package soloMapling.ArtificialPlayer.BotMovementSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;
import java.util.Random;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.injectArtificialStopPacket;

/**
 * Handles a bot's reaction when it detects a real player nearby during aware-pathfinding.
 * Three possible outcomes: ignore, stop and react, or react while walking.
 */
public class PlayerReaction {

    private static final Random random = new Random();
    private static final String DIALOGUE_PATH = "HenesysBotDialogue.yaml";
    private static final String BOT_TYPE = "HenesysBot";
    private static final String DIALOGUE_NODE = "PlayerReaction";
    private static List<String> ambientLines;
    private static List<Integer> ambientEmotes;

    private static void loadDialogue() {
        if (ambientLines != null) return;
        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE, DIALOGUE_NODE);
        if (dialog != null) {
            ambientLines = dialog.getDialogue();
            ambientEmotes = dialog.getEmotes();
        }
    }

    public enum ReactionType {
        IGNORE,
        STOP_REACT,
        WALK_REACT
    }

    /**
     * Rolls a three-way weighted random to decide how the bot reacts.
     *
     * @param ignoreWeight   weight for doing nothing (e.g. 40)
     * @param stopWeight     weight for stopping and reacting to the player (e.g. 30)
     * @param walkWeight     weight for reacting while continuing to walk (e.g. 30)
     * @return the chosen ReactionType
     */
    public static ReactionType rollReaction(int ignoreWeight, int stopWeight, int walkWeight) {
        int total = ignoreWeight + stopWeight + walkWeight;
        int roll = random.nextInt(total);

        if (roll < ignoreWeight) {
            return ReactionType.IGNORE;
        } else if (roll < ignoreWeight + stopWeight) {
            return ReactionType.STOP_REACT;
        } else {
            return ReactionType.WALK_REACT;
        }
    }

    /**
     * Full stop reaction: interrupts the bot's movement, faces the player,
     * performs an emote or chat, pauses briefly, then returns so the caller
     * can resume pathfinding.
     *
     * @return the snapshot of the player the bot reacted to
     */
    public static PlayerSnapshot executeStopReaction(Character bot, Character player) {
        PlayerSnapshot snapshot = new PlayerSnapshot(player);

        // Visually stop the bot — don't use interruptBotMovement() here because
        // this runs inside the MidMovementCheck callback on the same thread as
        // the packet loop. Setting the interrupt flag would poison the next
        // BotMoveStreamHelper call (e.g. botFaceTowardsPoint). The callback
        // returning true already stops the packet loop for us.
        injectArtificialStopPacket(bot);
        BotHelpers.sleepAmountSeconds(700);

        // Face towards the player
        botFaceTowardsPoint(bot, player.getPosition());
        BotHelpers.sleepAmountSeconds(400);

        // Pick a reaction: emote, chat, or both
        int pick = random.nextInt(3);
        if (pick == 0) {
            BotEmote(bot, getRandomAmbientEmote());
        } else if (pick == 1) {
            BotFullChat(bot, getRandomAmbientLine());
        } else {
            BotEmote(bot, getRandomAmbientEmote());
            BotHelpers.sleepAmountSeconds(500);
            BotFullChat(bot, getRandomAmbientLine());
        }

        // Brief pause before resuming movement (500ms - 2s)
        int pauseMs = 500 + random.nextInt(2000);
        BotHelpers.sleepAmountSeconds(pauseMs);

        return snapshot;
    }

    /**
     * Walk reaction: fires an emote or ambient chat line asynchronously
     * without interrupting movement. The bot is NOT reacting to the player
     * specifically — just doing something flavorful while walking.
     */
    public static void executeWalkReaction(Character bot) {
        ExecutorServiceManager.getExecutorService().execute(() -> {
            int pick = random.nextInt(2);
            if (pick == 0) {
                BotEmote(bot, getRandomAmbientEmote());
            } else {
                BotFullChat(bot, getRandomAmbientLine());
            }
        });
    }

    private static String getRandomAmbientLine() {
        loadDialogue();
        if (ambientLines == null || ambientLines.isEmpty()) return "...";
        return ambientLines.get(random.nextInt(ambientLines.size()));
    }

    private static int getRandomAmbientEmote() {
        loadDialogue();
        if (ambientEmotes == null || ambientEmotes.isEmpty()) return 2;
        return ambientEmotes.get(random.nextInt(ambientEmotes.size()));
    }
}
