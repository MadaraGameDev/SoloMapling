package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import server.TimerManager;
import soloMapling.server.MethodScheduler;
import tools.PacketCreator;

import java.util.concurrent.atomic.AtomicLong;

import static soloMapling.Environment.EnvironmentManager.environmentLoadStartup;


public class BotClientHandler {

    final static String clientIp = "127.0.0.1";
    static final AtomicLong sessionId = new AtomicLong(6969);
    static Client botClient = null;


    public static void createBotClient(Client c) {
        if (botClient == null) {
            botClient = c;
            TimerManager.getInstance().schedule(() -> disconnectFirstClient(c), 2000);
        }
    }

    public static Client getBotClient() {
        return botClient;
    }

    public static void disconnectFirstClient(Client c) {
        Character player = c.getPlayer();
        for (int i = 0; i < 10; i++) {
            player.yellowMessage("ATTENTION Please relog for Bot Client Creation - SoloMapling");
        }
        c.sendPacket(PacketCreator.serverNotice(1, "Please relog for Bot Client Creation - SoloMapling"));
        player.getClient().disconnect(true, false);

        // Setup environment // Comment this line if you want no bots.
        MethodScheduler.runAfterDelay(() -> environmentLoadStartup(), 1000);

    }

}
