package soloMapling.plugin;

import dev.maple.extension.api.CharacterClassifier;
import dev.maple.extension.api.HostCommandHandler;
import dev.maple.extension.api.HostRuntime;
import dev.maple.extension.api.ServerExtension;
import dev.maple.extension.api.event.CharacterChatEvent;
import dev.maple.extension.api.event.CharacterMapEnteredEvent;
import dev.maple.extension.api.event.ServerReadyEvent;
import dev.maple.extension.api.event.TradeInviteEvent;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.EnvironmentPopulationConfig;
import soloMapling.command.ArtificialPlayerCommand;
import soloMapling.command.BotMoveCommand;
import soloMapling.command.EnvironmentCommand;
import soloMapling.command.FMBotCommand;
import soloMapling.command.GCMoveCommand;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.MethodScheduler;

/**
 * SPI entry for SoloMapling. Discovered via
 * {@code META-INF/services/dev.maple.extension.api.ServerExtension}.
 */
public final class SoloMaplingExtension implements ServerExtension {

    private static final Logger log = LoggerFactory.getLogger(SoloMaplingExtension.class);

    /** Matches {@code BotHelpers} artificial / console id ranges. */
    private static final CharacterClassifier ARTIFICIAL_CHARACTERS =
            id -> id > 20_000 || id == 999;

    private final Consumer<CharacterMapEnteredEvent> onMapEntered = this::onCharacterMapEntered;
    private final Consumer<CharacterChatEvent> onChat = this::onCharacterChat;
    private final Consumer<TradeInviteEvent> onTradeInvite = this::onTradeInvite;
    private final Consumer<ServerReadyEvent> onReady = this::onHostServerReady;

    private HostRuntime runtime;

    @Override
    public String id() {
        return "solomapling";
    }

    @Override
    public String version() {
        return "0.3.0";
    }

    @Override
    public void onLoad(HostRuntime runtime) {
        this.runtime = runtime;
        log.info("SoloMapling plugin onLoad hostId={} spawnBotsOnStartup={}",
                runtime.hostId(),
                runtime.config().getBool("solomapling.spawn-bots-on-startup", false));

        String populationPath = runtime.config().getString("solomapling.population-config", "");
        if (populationPath != null && !populationPath.isBlank()) {
            EnvironmentPopulationConfig.setConfigPath(populationPath);
            log.info("SoloMapling population-config override={}", populationPath);
        }
        var plan = EnvironmentPopulationConfig.plan();
        log.info("SoloMapling population plan source={} scale={} trainingScaledTotal={}",
                plan.loadedFrom(), plan.scale(), plan.trainingCohortTotal());

        runtime.addCharacterClassifier(ARTIFICIAL_CHARACTERS);
        registerCommands(runtime);
        runtime.events().subscribe(CharacterMapEnteredEvent.class, onMapEntered);
        runtime.events().subscribe(CharacterChatEvent.class, onChat);
        runtime.events().subscribe(TradeInviteEvent.class, onTradeInvite);
        runtime.events().subscribe(ServerReadyEvent.class, onReady);

        try {
            EquipMetadataCache.initialize();
            DesirableEquipList.load();
            log.info("SoloMapling EquipMetadataCache + DesirableEquipList loaded");
        } catch (Throwable t) {
            log.warn("SoloMapling equip metadata prefetch failed (bots may still start lazily): {}", t.toString());
        }
    }

    private void onCharacterMapEntered(CharacterMapEnteredEvent event) {
        Character chr = findOnlineCharacter(event.characterId());
        if (chr == null) {
            return;
        }
        EventBus.getInstance().publish(new GameEvent(chr, EventType.MAP_ENTERED,
                "Entered map " + event.mapId(), null, null));
    }

    private void onCharacterChat(CharacterChatEvent event) {
        Character chr = findOnlineCharacter(event.characterId());
        if (chr == null) {
            return;
        }
        EventBus.getInstance().publish(new GameEvent(chr, EventType.CHAT_GENERAL, event.message(), null, null));
    }

    private void onTradeInvite(TradeInviteEvent event) {
        Character bot = findOnlineCharacter(event.inviteeCharacterId());
        Character partner = findOnlineCharacter(event.inviterCharacterId());
        if (bot == null || partner == null) {
            return;
        }
        BotTradeQueue.getInstance().addTradeRequest(bot, partner);
    }

    private void registerCommands(HostRuntime runtime) {
        bindCommand(runtime, "smping", 4, "SoloMapling plugin ping",
                (characterId, args) -> log.info("SoloMapling !smping from characterId={} args={}",
                        characterId, String.join(" ", args == null ? new String[0] : args)));
        bindCommand(runtime, "env", 4, "SoloMapling environment commands", new EnvironmentCommand());
        bindCommand(runtime, "bot", 4, "SoloMapling artificial player commands", new ArtificialPlayerCommand());
        bindCommand(runtime, "move", 4, "SoloMapling bot move commands", new BotMoveCommand());
        bindCommand(runtime, "fmbot", 4, "SoloMapling FM bot commands", new FMBotCommand());
        bindCommand(runtime, "gcmove", 4, "SoloMapling GCMove commands", new GCMoveCommand());
    }

    private void bindCommand(HostRuntime runtime, String syntax, int level, String description, Command command) {
        runtime.commands().register(syntax, level, description, (characterId, args) -> {
            Character chr = findOnlineCharacter(characterId);
            if (chr == null) {
                log.warn("SoloMapling command !{}: characterId={} not online", syntax, characterId);
                return;
            }
            Client client = chr.getClient();
            if (client == null) {
                log.warn("SoloMapling command !{}: characterId={} has no client", syntax, characterId);
                return;
            }
            command.execute(client, args == null ? new String[0] : args);
        });
    }

    private void bindCommand(HostRuntime runtime, String syntax, int level, String description,
                             HostCommandHandler handler) {
        runtime.commands().register(syntax, level, description, handler);
    }

    private static Character findOnlineCharacter(int characterId) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterById(characterId);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }

    private void onHostServerReady(ServerReadyEvent event) {
        log.info("SoloMapling received ServerReadyEvent at {}", event.readyAtEpochMs());
    }

    @Override
    public void onServerReady() {
        boolean spawn = runtime != null
                && runtime.config().getBool("solomapling.spawn-bots-on-startup", false);
        log.info("SoloMapling onServerReady spawnBotsOnStartup={}", spawn);
        try {
            BotClientHandler.initHeadlessBotClient();
            log.info("SoloMapling BotClientHandler headless client initialized");
        } catch (Throwable t) {
            log.error("SoloMapling failed to init headless BotClient", t);
            return;
        }
        if (spawn) {
            MethodScheduler.runAfterDelay(() -> {
                try {
                    EnvironmentManager.environmentLoadStartup();
                } catch (Throwable t) {
                    log.error("SoloMapling environmentLoadStartup failed", t);
                }
            }, 1000);
            log.info("SoloMapling scheduled environmentLoadStartup in 1s");
        }
    }

    @Override
    public void onUnload() {
        log.info("SoloMapling plugin onUnload");
        if (runtime != null) {
            runtime.removeCharacterClassifier(ARTIFICIAL_CHARACTERS);
            runtime.events().unsubscribe(CharacterMapEnteredEvent.class, onMapEntered);
            runtime.events().unsubscribe(CharacterChatEvent.class, onChat);
            runtime.events().unsubscribe(TradeInviteEvent.class, onTradeInvite);
            runtime.events().unsubscribe(ServerReadyEvent.class, onReady);
        }
        runtime = null;
    }
}
