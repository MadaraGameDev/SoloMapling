package client;

import io.netty.handler.timeout.IdleStateEvent;
import net.packet.Packet;

/*
 * A single shared headless Client for SoloMapling bots.
 * Credits to NutNNut for creating this headless System (c) 2026.
 */
public class BotClient extends Client {

    public BotClient(int world, int channel) {
        // (Type, sessionId, remoteAddress, PacketProcessor, world, channel)
        // No type/session/processor/socket — only world+channel are load-bearing
        // (routing). A dummy "bot" remote address keeps any logging path safe.
        super(null, -1, "bot", null, world, channel);
    }

    /*
     * No-op: a bot has no network socket. The base sendPacket does
     * ioChannel.writeAndFlush(packet) with no null guard, so this is the
     * core NPE fix. It also covers every announce* helper, which all
     * funnel through sendPacket.
     */
    @Override
    public void sendPacket(Packet packet) {
        // packets to a bot fizzle out — no socket, no recipients
    }

    /*
     * Bots must always read as logged-in: shared player-code paths gate on this.
     * (This is independent of Character.isLoggedin(), which stays false
     * for bots, so autosave/disconnect loops still skip them.)
     */
    @Override
    public boolean isLoggedIn() {
        return true;
    }

    /*
     * Skip the DB write and SessionCoordinator registration a real login state
     * change performs. A headless client must never write login-state rows
     * (the base method UPDATEs accounts keyed on getAccID()) or
     * register/deregister online sessions.
     */
    @Override
    public void updateLoginState(int newState) {
        // headless: no account row, no session to register
    }

    /* No-op: base does ioChannel.disconnect(). Bots never disconnect. */
    @Override
    public void disconnectSession() {
        // no socket to disconnect
    }

    /* No-op: base does ioChannel.close(). Bots have no session to close. */
    @Override
    public void closeSession() {
        // no socket to close
    }

    /*
     * No-op: the base idle checker pings, then dereferences
     * ioChannel.isActive() and may disconnect. A bot is never part of a
     * netty pipeline so this is unreachable in practice, but neutralizing it
     * guarantees the inactivity reaper can never touch the shared bot client.
     */
    @Override
    public void checkIfIdle(final IdleStateEvent event) {
        // never reap the headless bot client
    }

    /*
     * Always report "just now" so any timeout task sees the client as freshly
     * active and never considers the shared bot client idle.
     */
    @Override
    public long getLastPacket() {
        return System.currentTimeMillis();
    }
}
