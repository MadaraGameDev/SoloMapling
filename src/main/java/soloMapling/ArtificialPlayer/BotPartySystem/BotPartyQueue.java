package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;

import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyQueue {

    public static final class PartyInviteEntry {
        private final Character inviter;
        private final int partyId;

        public PartyInviteEntry(Character inviter, int partyId) {
            this.inviter = inviter;
            this.partyId = partyId;
        }

        public Character getInviter() {
            return inviter;
        }

        public int getPartyId() {
            return partyId;
        }
    }

    private final ConcurrentHashMap<Character, PartyInviteEntry> queues;
    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    // First-wins: if an invite is already pending, new invites are dropped.
    public void addPartyInvite(Character fakechar, Character inviter, int partyId) {
        debugprint("addPartyInvite: bot=" + fakechar.getName() + ", inviter=" + inviter.getName() + ", partyId=" + partyId);
        queues.putIfAbsent(fakechar, new PartyInviteEntry(inviter, partyId));
    }

    public PartyInviteEntry getPartyInvite(Character fakechar) {
        return queues.get(fakechar);
    }

    public boolean hasPendingInvite(Character fakechar) {
        return queues.containsKey(fakechar);
    }

    public void removePartyInvite(Character fakechar) {
        queues.remove(fakechar);
    }
}
