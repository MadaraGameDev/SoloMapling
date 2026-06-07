package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.world.Messenger;
import net.server.world.MessengerCharacter;
import net.server.world.World;
import tools.PacketCreator;

import java.util.Objects;

import static soloMapling.ArtificialPlayer.BotHelpers.sleepAmountSeconds;

public class MapleMessengerCommands {
    public static void sendConsoleMessage(Character player, String textMessage) {
        player.sendPacket(PacketCreator.messengerChat(player.getName() + " : " + textMessage));
    }

    public static void sendColoredConsoleMessage(Character player, String textMessage) {
        player.sendPacket(PacketCreator.messengerChat("Console" + " : " + textMessage));
    }

    public static void addBotToMessenger(Character mainChar, Character consoleChar) {
        World world = Server.getInstance().getWorld(0); // scania
        Messenger messenger = mainChar.getMessenger();

        int position = messenger.getLowestPosition();
        MessengerCharacter messengerplayer = new MessengerCharacter(consoleChar, position);
        if (messenger.getMembers().size() < 3) {
            consoleChar.setMessenger(messenger);
            consoleChar.setMessengerPosition(position);
            world.joinMessenger(messenger.getId(), messengerplayer, consoleChar.getName(), messengerplayer.getChannel());
        }
    }

    public static void removeBotFromMessenger(Character mainChar, Character consoleBot) {
        Messenger messenger = mainChar.getMessenger();
        if (messenger != null) {
            int messengerId = messenger.getId();
            World world = Server.getInstance().getWorld(0);
            MessengerCharacter messengerplayer = new MessengerCharacter(consoleBot, consoleBot.getMessengerPosition());
            world.leaveMessenger(messengerId, messengerplayer);
        }
    }

    public static void botLeaveMessenger(Character fakechar) {
        Messenger messenger = fakechar.getMessenger();
        if (messenger != null) {
            int messengerId = messenger.getId();
            World world = Server.getInstance().getWorld(0);
            MessengerCharacter messengerplayer = new MessengerCharacter(fakechar, fakechar.getMessengerPosition());
            world.leaveMessenger(messengerId, messengerplayer);
        }
    }

    public static void sendMessengerInviteComplete(Character fakechar, Character targetPlayer) {
        botCreateMessengerChatRoom(fakechar);
        sendMessengerInvite(fakechar, targetPlayer);
    }

    private static void botCreateMessengerChatRoom(Character fakechar) {
        InviteCoordinator.removeInvite(InviteCoordinator.InviteType.MESSENGER, fakechar.getId());
        MessengerCharacter messengerplayer = new MessengerCharacter(fakechar, 0);
        Messenger messenger = fakechar.getWorldServer().createMessenger(messengerplayer);
        fakechar.setMessenger(messenger);
        fakechar.setMessengerPosition(0);
    }

    private static void sendMessengerInvite(Character fakechar, Character targetPlayer) {
        Messenger messenger = fakechar.getMessenger();
        if (messenger == null) {
            botCreateMessengerChatRoom(fakechar);
        }
        if (InviteCoordinator.createInvite(InviteCoordinator.InviteType.MESSENGER, fakechar, messenger.getId(), targetPlayer.getId())) {
            targetPlayer.sendPacket(PacketCreator.messengerInvite(fakechar.getName(), messenger.getId()));
        }
    }

    // Checks if targetPlayer accepted the fakechar's Maple Messenger Invitation
    public static boolean isMessengerInviteAccepted(Character fakechar, Character targetPlayer) {
        Messenger messenger = fakechar.getMessenger();
        for (MessengerCharacter messengerchar : messenger.getMembers()) {
            if (Objects.equals(messengerchar.getName(), targetPlayer.getName())) {
                return true;
            }
        }
        return false;
    }


    // Base method that handles sending any message to messenger members
    private static void sendToMessengerMembers(Character fakechar, String message) {
        Messenger messenger = fakechar.getMessenger();
        for (MessengerCharacter messengerchar : messenger.getMembers()) {
            if (!(messengerchar.getName().equals(fakechar.getName()))) {
                Character chr = fakechar.getWorldServer().getPlayerStorage().getCharacterByName(messengerchar.getName());
                if (chr != null) {
                    chr.sendPacket(PacketCreator.messengerChat(message));
                }
            }
        }
    }

    // Send a regular chat message
    public static void botSendMessengerChat(Character fakechar, String message) {
        sendToMessengerMembers(fakechar, fakechar.getName() + " : " + message);
    }

    // Set typing status using boolean
    public static void botTypingStatus(Character fakechar, boolean isTyping) {
        String statusFlag = isTyping ? "1" : "0";
        sendToMessengerMembers(fakechar, fakechar.getName() + statusFlag);
    }

    public static void botSendChatFull(Character fakechar, String message, long milliseconds) {
        botTypingStatus(fakechar, true);
        sleepAmountSeconds(milliseconds);
        botSendMessengerChat(fakechar, message);
    }

}
