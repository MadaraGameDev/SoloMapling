package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.MethodScheduler;
import tools.PacketCreator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.*;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotHelpers.sleepAmountSeconds;
import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.*;
import static soloMapling.BotLogger.log;
import static soloMapling.server.SoloMaplingUtilities.random;

public class SocialBot extends BotSM {

    public enum SocialBotVariant { SINGLE_RESPONSE, INTERACTIVE }

    private enum InteractionLevel { NORMAL, REDUCED, NONVERBAL, IGNORE }

    private enum SocialBotState { IDLE_AMBIENT, GREETING, AWAITING_CHOICE, RESPONDING }

    private SocialBotState socialState = SocialBotState.IDLE_AMBIENT;
    private final SocialBotVariant variant;
    private final Map<Integer, InteractionTracker> interactionTrackers = new ConcurrentHashMap<>();

    private static final long CONVERSATION_TIMEOUT_MS = 35_000;
    private long lastRespondantMessageTime = 0;

    private static final long TRACKER_CLEANUP_INTERVAL_MS = 120_000;
    private long lastCleanupTime = System.currentTimeMillis();

    private static final double RARE_LINE_CHANCE = 0.01;
    private static final double GOODBYE_SIT_CHANCE = 0.40;
    private boolean wasSittingBeforeInteraction = false;
    private int originalChairId = 0;

    private static final String[] INTERACTIVE_OPTIONS = {
            "What's up?",
            "Anything interesting?",
            "Any rumors?",
            "Goodbye"
    };

    private static final String DIALOGUE_PATH = "SocialBotDialogue.yaml";
    private static final String BOT_TYPE_KEY = "SocialBot";

    public SocialBot(Character character) {
        super(character);
        dialoguePath = DIALOGUE_PATH;
        botType = "SocialBot";
        this.variant = random.nextDouble() < 0.30 ? SocialBotVariant.INTERACTIVE : SocialBotVariant.SINGLE_RESPONSE;
    }

    public SocialBotVariant getVariant() {
        return variant;
    }

    @Override
    public boolean isAvailableForAmbientActions() {
        return !hasActiveRespondant();
    }

    public boolean hasActiveRespondant() {
        return getInteractors().getRespondant() != null;
    }

    @Override
    public void checkPrioritySpeed() {
        if (hasActiveRespondant()) {
            setPriorityHigh();
            return;
        }
        if (checkMainPlayersOnMap()) {
            setPriorityNormal();
            return;
        }
        updateScheduleDelay(30000);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;
        getDebugger().debugLoggingFull(String.format("%s SocialBotState: %s", getChr().getName(), socialState), String.format("%s", socialState));

        checkPrioritySpeed();

        if (hasActiveRespondant()) {
            checkConversationTimeout();
            processMessages();
        }

        cleanupExpiredTrackers();
    }

    @Override
    protected void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance()
                    .getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) return;

            if (isBot(message.getSender())) return;

            Character respondant = getInteractors().getRespondant();
            if (respondant == null || message.getSender().getId() != respondant.getId()) return;

            lastRespondantMessageTime = System.currentTimeMillis();
            handlePlayerMessage(message);
        } catch (Exception e) {
            log("[SocialBot] processMessages error: " + e.getMessage());
        }
    }

    public void onFirstInteraction(Character player) {
        lastRespondantMessageTime = System.currentTimeMillis();

        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }

        ExecutorServiceManager.runAsync(() -> {
            wasSittingBeforeInteraction = getChr().getChair() > 0;
            if (wasSittingBeforeInteraction) {
                originalChairId = getChr().getChair();
                botCancelChair(getChr());
                sleepAmountSeconds(600);
            }
            botFaceTowardsPoint(getChr(), player.getPosition());
            sleepAmountSeconds(2000 + random.nextInt(2000));

            switch (level) {
                case NORMAL:
                    if (variant == SocialBotVariant.SINGLE_RESPONSE) {
                        doSingleResponse(player);
                        tracker.increment();
                        botFaceTowardsPoint(getChr(), player.getPosition());
                        maybeResitAfterInteraction();
                        resetConversation();
                    } else {
                        doGreeting(player);
                        tracker.increment();
                        socialState = SocialBotState.AWAITING_CHOICE;
                    }
                    break;
                case REDUCED:
                    doReducedResponse(player);
                    tracker.increment();
                    resetConversation();
                    break;
                case NONVERBAL:
                    doNonverbalResponse(player);
                    tracker.increment();
                    resetConversation();
                    break;
            }
        });
    }

    private void handlePlayerMessage(ChatMessage message) {
        Character player = message.getSender();
        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }
        if (level == InteractionLevel.NONVERBAL) {
            doNonverbalResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }
        if (level == InteractionLevel.REDUCED) {
            doReducedResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }

        if (socialState == SocialBotState.AWAITING_CHOICE) {
            handleDialogueChoice(message.getContent(), player);
            tracker.increment();
        }
    }

    private void handleDialogueChoice(String content, Character player) {
        String lower = content.trim().toLowerCase();

        String category = null;
        if (lower.equals("1") || lower.contains("what's up") || lower.contains("whats up")) {
            category = "WhatsUp";
        } else if (lower.equals("2") || lower.contains("interesting")) {
            category = "Interesting";
        } else if (lower.equals("3") || lower.contains("rumor")) {
            category = "Rumors";
        } else if (lower.equals("4") || lower.contains("goodbye") || lower.contains("bye") || lower.contains("cya")) {
            doGoodbye(player);
            resetConversation();
            return;
        }

        if (category == null) {
            category = "WhatsUp";
        }

        if (random.nextDouble() < RARE_LINE_CHANCE) {
            category = "Rare";
        }

        String finalCategory = category;
        ExecutorServiceManager.runAsync(() -> {
            sleepAmountSeconds(2000 + random.nextInt(2000));
            botFaceTowardsPoint(getChr(), player.getPosition());
            String line = getRandomLine(finalCategory, player);
            if (line != null) {
                BotSpeak(getChr(), line);
                int emote = getRandomEmote(finalCategory);
                if (emote > 0) {
                    sleepAmountSeconds(400);
                    BotEmote(getChr(), emote);
                }
            }
            showInteractiveOptions(player);
        });
    }

    // --- Response types ---

    private void doSingleResponse(Character player) {
        String category = "SingleResponse";
        if (random.nextDouble() < RARE_LINE_CHANCE) {
            category = "Rare";
        }
        String line = getRandomLine(category, player);
        if (line != null) {
            BotSpeak(getChr(), line);
            sleepAmountSeconds(500 + random.nextInt(500));
            int emote = getRandomEmote(category);
            if (emote > 0) {
                BotEmote(getChr(), emote);
            }
        }
    }

    private void doGreeting(Character player) {
        String line = getRandomLine("Greeting", player);
        if (line != null) {
            BotSpeak(getChr(), line);
            int emote = getRandomEmote("Greeting");
            if (emote > 0) {
                sleepAmountSeconds(400);
                BotEmote(getChr(), emote);
            }
        }
        showInteractiveOptions(player);
        socialState = SocialBotState.GREETING;
    }

    private void doGoodbye(Character player) {
        String line = getRandomLine("Goodbye", player);
        if (line != null) {
            BotSpeak(getChr(), line);
            int emote = getRandomEmote("Goodbye");
            if (emote > 0) {
                sleepAmountSeconds(400);
                BotEmote(getChr(), emote);
            }
        }
        maybeResitAfterInteraction();
    }

    private void doReducedResponse(Character player) {
        String line = getRandomLine("Reduced", player);
        if (line != null) {
            BotSpeak(getChr(), line);
        }
    }

    private void doNonverbalResponse(Character player) {
        String line = getRandomLine("Nonverbal", player);
        if (line != null) {
            BotSpeak(getChr(), line);
        }
    }

    private void maybeResitAfterInteraction() {
        if (wasSittingBeforeInteraction || random.nextDouble() < GOODBYE_SIT_CHANCE) {
            sleepAmountSeconds(1000 + random.nextInt(1500));
            int chairId = originalChairId > 0 ? originalChairId : getRandomChairId();
            botSitChair(getChr(), chairId);
        }
        wasSittingBeforeInteraction = false;
        originalChairId = 0;
    }

    private void showBusyHint(Character player) {
        player.yellowMessage("They seem busy...");
        player.getClient().sendPacket(PacketCreator.sendHint("They seem busy...", 150, 5));
        player.getClient().sendPacket(PacketCreator.enableActions());
        MethodScheduler.runAfterDelay(() -> expirePlayerChatCommands(player), 5000);
    }

    private void showInteractiveOptions(Character player) {
        List<String> options = List.of(INTERACTIVE_OPTIONS);
        displayPlayerChatCommands(player, options);
    }

    // --- Timeout ---

    private void checkConversationTimeout() {
        if (!hasActiveRespondant()) return;
        if (lastRespondantMessageTime == 0) return;

        long elapsed = System.currentTimeMillis() - lastRespondantMessageTime;
        if (elapsed > CONVERSATION_TIMEOUT_MS) {
            String line = getRandomLine("Timeout", getInteractors().getRespondant());
            if (line != null) {
                BotSpeak(getChr(), line);
            }
            resetConversation();
        }
    }

    private void resetConversation() {
        Character respondant = getInteractors().getRespondant();
        if (respondant != null) {
            expirePlayerChatCommands(respondant);
        }
        getInteractors().resetRespondant();
        socialState = SocialBotState.IDLE_AMBIENT;
        lastRespondantMessageTime = 0;
    }

    // --- Anti-spam tracker ---

    private InteractionTracker getOrCreateTracker(int playerId) {
        return interactionTrackers.computeIfAbsent(playerId, k -> new InteractionTracker());
    }

    private void cleanupExpiredTrackers() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < TRACKER_CLEANUP_INTERVAL_MS) return;
        lastCleanupTime = now;
        interactionTrackers.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // --- Dialogue helpers ---

    // Resolves any {TOKEN}s (incl. {PLAYER_*}) against this bot and the player it's reacting to.
    // A line whose tokens can't resolve is dropped rather than spoken raw.
    private String getRandomLine(String category, Character player) {
        try {
            return BotDialogueHandler.getRandomResolvedLine(DIALOGUE_PATH, BOT_TYPE_KEY, category, getChr(), player);
        } catch (Exception e) {
            return null;
        }
    }

    private int getRandomEmote(String category) {
        try {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE_KEY, category);
            if (dialog == null) return -1;
            return dialog.getEmote();
        } catch (Exception e) {
            return -1;
        }
    }

    // --- Anti-spam tracking ---

    private static class InteractionTracker {
        private int count = 0;
        private long lastInteractionTime;
        private static final long COOLDOWN_MS = 300_000;

        public InteractionTracker() {
            this.lastInteractionTime = System.currentTimeMillis();
        }

        public void increment() {
            count++;
            lastInteractionTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastInteractionTime > COOLDOWN_MS;
        }

        public InteractionLevel getLevel() {
            if (isExpired()) {
                reset();
                return InteractionLevel.NORMAL;
            }
            if (count <= 3) return InteractionLevel.NORMAL;
            if (count <= 4) return InteractionLevel.REDUCED;
            if (count == 5) return InteractionLevel.NONVERBAL;
            return InteractionLevel.IGNORE;
        }

        public void reset() {
            count = 0;
            lastInteractionTime = System.currentTimeMillis();
        }
    }
}
