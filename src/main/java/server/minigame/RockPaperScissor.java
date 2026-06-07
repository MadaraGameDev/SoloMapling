package server.minigame;

import client.Client;
import tools.PacketCreator;
import tools.Randomizer;

/**
 * @Author Arnah
 * @Website http://Vertisy.ca/
 * @since Aug 15, 2016
 *
 * Modified: meso-based double-or-nothing rewards instead of certificates.
 * Entry cost: 1,000,000 mesos. Each win doubles the pot.
 * Round 0 win = 1m, round 1 = 2m, round 2 = 4m ... round 9 = 512m.
 * Player can exit after any win to collect, or continue to double.
 */
public class RockPaperScissor {
    public static final int ENTRY_COST = 1_000_000;

    private int round = 0;
    private boolean ableAnswer = true;
    private boolean win = false;

    public RockPaperScissor(final Client c, final byte mode) {
        c.sendPacket(PacketCreator.rpsMode((byte) (9 + mode)));
        if (mode == 0) {
            c.getPlayer().gainMeso(-ENTRY_COST, true, true, true);
        }
    }

    public final boolean answer(final Client c, final int answer) {
        if (ableAnswer && !win && answer >= 0 && answer <= 2) {
            final int response = Randomizer.nextInt(3);
            if (response == answer) {
                c.sendPacket(PacketCreator.rpsSelection((byte) response, (byte) round));
                // dont do anything. they can still answer once a draw
            } else if ((answer == 0 && response == 2) || (answer == 1 && response == 0) || (answer == 2 && response == 1)) { // they win
                c.sendPacket(PacketCreator.rpsSelection((byte) response, (byte) (round + 1)));
                ableAnswer = false;
                win = true;
            } else { // they lose
                c.sendPacket(PacketCreator.rpsSelection((byte) response, (byte) -1));
                ableAnswer = false;
            }
            return true;
        }
        reward(c);
        return false;
    }

    public final boolean timeOut(final Client c) {
        if (ableAnswer && !win) {
            ableAnswer = false;
            c.sendPacket(PacketCreator.rpsMode((byte) 0x0A));
            return true;
        }
        reward(c);
        return false;
    }

    public final boolean nextRound(final Client c) {
        if (win) {
            round++;
            if (round < 10) {
                win = false;
                ableAnswer = true;
                c.sendPacket(PacketCreator.rpsMode((byte) 0x0C));
                return true;
            } else {
                round = 10;
            }
        }
        reward(c);
        return false;
    }

    /**
     * Payout: ENTRY_COST * 2^(round+1).
     * The +1 accounts for the entry fee — winning round 1 returns 2m (your 1m back + 1m profit).
     * Round 1 = 2m, round 2 = 4m, round 3 = 8m ... round 10 = 1,024m.
     */
    private int getWinnings() {
        return ENTRY_COST * (1 << (round + 1));
    }

    public final void reward(final Client c) {
        if (win) {
            int payout = getWinnings();
            c.getPlayer().gainMeso(payout, true, true, true);
            c.getPlayer().dropMessage(5, "You won " + String.format("%,d", payout) + " mesos! (Round " + (round + 1) + ")");
        }
        c.getPlayer().setRPS(null);
    }

    public final void dispose(final Client c) {
        reward(c);
        c.sendPacket(PacketCreator.rpsMode((byte) 0x0D));
    }
}
