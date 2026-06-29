package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.awt.Point;

// A grind spot: an anchor on a reachable ledge plus a tight radius, and the spawn-point count of the
// cluster it was built from. The bot plants here, kills what falls inside the radius, and waits out
// respawn lulls. radius is cluster-sized (clamped to SPOT_RADIUS_MIN/MAX in SpotFinder), so a compact
// map collapses to one fat spot and a long field tiles into a row of adjacent spots. spawnCount is the
// self-feeding score term — more nearby spawn points means more of the map-global respawn lands inside
// the radius, so the bot stays busy without moving. Our own creation (not a GreenCat extraction).
public record Spot(Point anchor, int regionId, int radius, int spawnCount) {
}
