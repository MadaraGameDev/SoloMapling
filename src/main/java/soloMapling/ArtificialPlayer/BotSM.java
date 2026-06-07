package soloMapling.ArtificialPlayer;

import client.Character;
import server.Trade;
import server.maps.MapObject;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeHandler;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeLogic;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants;
import soloMapling.server.EventMessageSystem.BotEventBuffer;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.botClearChalkboard;
import static soloMapling.ArtificialPlayer.BotHelpers.sleepAmountSeconds;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.botLoggedIn;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.BotIdleStandingUpdate;
import static soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeLogic.clearTradeRequest;
import static soloMapling.BotLogger.log;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.server.SoloMaplingUtilities.random;


public abstract class BotSM implements EventSubscriber {

    // BotSM - bot framework related stuff. setting/getting data

    public enum BotState {
        IDLE,
        RUNNING,
        PAUSE,
        TRADING,
        FINISHED;
    }

    private final Character character; // Reference to the existing Character object
    private boolean running;
    protected BotState state;
    private BotDebugHandler debugger;
    private BotInteractorsHandler interactors = new BotInteractorsHandler();
    private BotTradeHandler tradeHandler;

    protected String dialoguePath;
    protected String botType;

    protected void dprint(String msg) {
        boolean debugPrintConsole = false;
        if (!debugPrintConsole) {
            return;
        }
        debugprint("[" + getBotType() + ":" + getChr().getName() + "] " + msg);
    }

    private static MessageQueue messageQueue = MessageQueue.getInstance();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    private BotDialogueHandler dialogueHandler;

    private BotTradeSM botTradeSM = null; // Initially null
    private BotTradeInventory tradeInventory = new BotTradeInventory();
    private BotTradeWants tradeWants = new BotTradeWants();
    private BotTradeSM.TradeMode currentTradeMode = BotTradeSM.TradeMode.NULL;
    private volatile long currentDelay = getRandomDelay(); // Store current delay
    private volatile boolean movementInterrupted = false;
    protected Trade.TradeResult lastTradeResult = null;
    protected Character lastTradedCharacter = null;

    private final BotEventBuffer eventBuffer;

    public BotSM(Character chr) {
        this.character = chr;
        this.running = false;
        this.state = BotState.IDLE;
        this.tradeHandler = new BotTradeHandler(chr);
        this.debugger = new BotDebugHandler(chr);
        this.dialogueHandler = new BotDialogueHandler(chr);
        this.eventBuffer = new BotEventBuffer(100);
        debugprint(("Bot Initialized: " + this.character.getName() + ", " + this.character.getId()));
    }

    private void setState(BotState state) {
        this.state = state;
    }

    public BotState getState() {
        return this.state;
    }

    private boolean verifyState(BotState expectedState) {
        return this.state == expectedState;
    }

    public client.Character getChr() {
        return this.character;
    }

    public String getBotType() {
        return this.botType;
    }

    public void setRunning(boolean bool) {
        this.running = bool;
    }

    public boolean getRunning() {
        return running;
    }

    public void interruptMovement() {
        this.movementInterrupted = true;
    }

    public boolean isMovementInterrupted() {
        return this.movementInterrupted;
    }

    public void clearMovementInterrupt() {
        this.movementInterrupted = false;
    }

    public BotInteractorsHandler getInteractors() {
        return interactors;
    }

    public BotTradeHandler getTradeHandler() {
        return tradeHandler;
    }

    protected BotDebugHandler getDebugger() {
        return debugger;
    }

//    public static void getMessageQueue() {
//        messageQueue = MessageQueue.getInstance();
//    }


    public boolean checkRunningOnline() {
        return getRunning() && botLoggedIn(this.getChr().getId());
    }

    // todo update to maybe just check all real players and check if they on map
    //  , instead of checking all chars on map
    public boolean checkMainPlayersOnMap() {
        Collection<Character> charsOnMap = character.getMap().getCharacters();
        for (Character chrs : charsOnMap) {
            if (!isBot(chrs)) {
                return true;
            }
        }
        return false;
    }

    public void updateState() {
        debugger.handleDebugPrints(this);
        switch (state) {
            case IDLE:
                if (checkRunningOnline()) {
                    setState(BotState.RUNNING);
                    log("Moving to RUNNING: " + getChr().getName());
                }
                break;
            case RUNNING:
                checkPrioritySpeed();
                if (!checkRunningOnline()) {
                    setState(BotState.FINISHED);
                    log("Moving to FINISHED: " + getChr().getName());
                    break;
                }
                BotIdleStandingUpdate(getChr());
//                if (!checkMainPlayersOnMap()) {
//                    state = BotState.PAUSE;
//                    log("Moving to PAUSE: " + getChr().getName());
//                    break;
//                }
                if (tradeHandler.verifyTradePartner()) {
                    debugprint("verifyTradePartner");
                    tradeInitialized(getTradeMode());
                    setState(BotState.TRADING);
                    break;
                }
                break;
            case PAUSE:
                if (checkMainPlayersOnMap()) {
                    setState(BotState.RUNNING);
                    log("Resuming to RUNNING: " + getChr().getName());
                }
                break;
            case TRADING:
                /*
                1. completed, has trade partner = should not be possible
                2. not completed, has trade partner = still trading continuously - TRADING

                3. completed, no trade partner = successfully finished trade. go to running
                4. not completed, no partner = canceled / trade declined = go to running - RUNNING
                 */
                if (botTradeSM.isTradeComplete() && !tradeHandler.verifyTradePartner() ||
                        !botTradeSM.isTradeComplete() && !tradeHandler.verifyTradePartner() && !botTradeSM.isOfferAccepted()) {
                    cleanupTradeState();
                    sleepAmountSeconds(2000);
                    setState(BotState.RUNNING);
                    break;
                }
                try {
                    updateTradeSM();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                break;
            case FINISHED:
                this.setRunning(false);
                getInteractors().resetRespondant();
                stopScheduledTask();
                setState(BotState.IDLE);
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    // Method to start the scheduled task
    public synchronized void startScheduledTask() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newScheduledThreadPool(1);
        }
        if (scheduledTask == null || scheduledTask.isCancelled() || scheduler.isShutdown() || scheduler.isTerminated()) {
            // Using FixedDelay instead of FixedRate - // SM NOTE this should prevent "piling up", and only allow 1 at a time
            scheduledTask = scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateState();
                    } catch (Exception e) {
                        e.printStackTrace(); // Handle exceptions to ensure the scheduler doesn't stop unexpectedly
                    }
                }
            }, 0, getRandomDelay(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void updateScheduleDelay(long newDelayMs) {
        if (this.currentDelay == newDelayMs) {
            return; // No change needed
        }

        this.currentDelay = newDelayMs;

        // Cancel and restart
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduledTask = scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateState();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, currentDelay, currentDelay, TimeUnit.MILLISECONDS);
        }
    }

    public void checkPrioritySpeed() {
        if (checkMainPlayersOnMap()) {
            setPriorityNormal();
            return;
        }
        setPriorityLow();
        return;
    }

    // Convenience methods for common adjustments
    public void setPriorityLow() {
        updateScheduleDelay(10000); // 20 seconds
    }

    public void setPriorityHigh() {
        updateScheduleDelay(2000); // 2 seconds
    }

    public void setPriorityNormal() {
        updateScheduleDelay(getRandomDelay()); // Your original random delay
    }

    private long getRandomDelay() {
        return 2000 + random.nextInt(4000); // 2000 to 3000 ms
    }

    // Method to stop the scheduled task
    public synchronized void stopScheduledTask() {
        log("Shutting down scheduler: " + this.getChr().getName());
        EventBus.getInstance().unsubscribeAll(this);
        botClearChalkboard(this.getChr());
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(true);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    // todo
    // At the moment this is not used as far as I know.
    protected void processMessages() {
        System.out.println("BotSM processMessages");
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("secondary");
            if (message.getSender() == getInteractors().getRespondant()) {
                log("This Message is from Respondant: " + getInteractors().getRespondant().getName() + ", Msg: " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<MapObject> detectItems() {
        return null;
    }

    protected boolean checkIfNotRunningOrPaused() {
        if (!this.getRunning()) {
            return true;
        }
        if (verifyState(BotState.PAUSE)) {
            return true;
        }
        return false;
    }

    public void displayCommands(Character chr) {
        List<String> hint = List.of(getChr().getName());
        SocialCommands.talkCygnusGuideCommands(chr, hint);
    }

    //

    protected void checkForTrades() {
        boolean acceptedTrade = BotTradeLogic.checkTradeQueue(getChr());
        if (acceptedTrade) {
            tradeHandler.setTradePartner(tradeHandler.getTradePartnerRaw());
            debugprint("Accepted Trade");
        }
    }

    public void setTradeMode(BotTradeSM.TradeMode tradeMode) {
        this.currentTradeMode = tradeMode;
    }

    protected BotTradeSM.TradeMode getTradeMode() {
        return this.currentTradeMode;
    }

    protected void tradeInitialized(BotTradeSM.TradeMode tradeMode) {
        startTradeSM(tradeMode);
    }

    protected void startTradeSM() {
        if (botTradeSM == null) {
            botTradeSM = new BotTradeSM(this); // Create only when entering TRADING
        }
    }

    protected void startTradeSM(BotTradeSM.TradeMode mode) {
        botTradeSM = new BotTradeSM(this, mode);
    }

    protected void updateTradeSM() {
        botTradeSM.update(); // Continue trading logic
    }

    protected void discardTradeSM() {
        botTradeSM = null;
    }

    protected void cleanupTradeState() {
        botTradeSM = null;
        clearTradeRequest(getChr());
        tradeHandler.resetTradePartner();
        discardTradeSM();
    }

    public BotDialogueHandler getDialogueHandler() {
        return dialogueHandler;
    }

    public BotTradeInventory getTradeInventory() {
        return tradeInventory;
    }

    public BotTradeWants getTradeWants() {
        return tradeWants;
    }

    public void resetLastTradeResult() {
        lastTradeResult = null;
    }

    public void setLastTradeResult(Trade.TradeResult result) {
        lastTradeResult = result;
    }

    public Trade.TradeResult getLastTradeResult() {
        return lastTradeResult;
    }

    public void setLastTradedCharacter(Character character) {
        lastTradedCharacter = character;
    }

    public Character getLastTradedCharacter() {
        return lastTradedCharacter;
    }

    public void resetLastTradedCharacter() {
        lastTradedCharacter = null;
    }

    @Override
    public void onEvent(GameEvent event) {
        // Queue the event for processing later
        eventBuffer.add(event);
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        // Check if this event is relevant to this bot
        int targetWorld = getChr().getWorld();
        int targetChannel = getChr().getMap().getChannelServer().getId();
        MapleMap targetMap = getChr().getMap();
        if (event.getWorld() != targetWorld) {
            return false;
        }
        if (event.getChannel() != targetChannel) {
            return false;
        }
        if (targetMap != null && event.getMap() != targetMap) {
            return false;
        }
        return true;
    }

    // Call this from your event processing state
    public void processQueuedEvents() {
        GameEvent event = eventBuffer.poll();
        if (event != null) {
            handleEvent(event);
        }
    }

    public void handleEvent(GameEvent event) {
        // Process based on event type
        System.out.println("BotSM handleEvent");
        return;
    }

    public boolean isAvailableForAmbientActions() {
        return false;
    }

    public boolean hasQueuedEvents() {
        return !eventBuffer.isEmpty();
    }

}
