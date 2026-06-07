package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotBlockList;

import static soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands.getTradePartnerCharacter;

public class BotTradeLogic {

    public static boolean checkTradeQueue(Character fakechar) {
        if (BotTradeQueue.getInstance().hasPendingTrades(fakechar)) {
            if (BotBlockList.getInstance().isBlocked(fakechar.getId(), getTradePartnerCharacter(fakechar).getId())) {
                BotTradeQueue.getInstance().removeTradeRequest(fakechar);
                BotTradeCommands.declineTradeInvite(fakechar);
                return false;
            }
            acceptTradeRequest(fakechar);
            return true;
        }
        return false;
    }

    private static void acceptTradeRequest(Character fakechar) {
        BotTradeCommands.acceptTradeInvite(fakechar);
        BotTradeQueue.getInstance().removeTradeRequest(fakechar);
    }

    private static void rejectTradeRequest(Character fakechar) {
        BotTradeCommands.declineTradeInvite(fakechar);
        BotTradeQueue.getInstance().removeTradeRequest(fakechar);
    }

    public static void clearTradeRequest(Character fakechar) {
        BotTradeQueue.getInstance().removeTradeRequest(fakechar);
    }


}
