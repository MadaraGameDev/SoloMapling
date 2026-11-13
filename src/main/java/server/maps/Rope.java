package server.maps;

/**
 * Represents a rope or ladder climbable object parsed from WZ ladderRope data.
 * id: the entry's number in the map's ladderRope list (its WZ name), 0 when unknown. A client on a
 * rope reports minus this number in place of a foothold, and observers draw it in the rope's layer.
 * isLadder: true when the WZ {@code l} field is 1 (ladder), false for rope.
 *
 * Part of the GCMoveSystem terrain model (GreenCat dynamic movement).
 */
public record Rope(int id, int x, int y1, int y2, boolean isLadder) {
    /** A ladderRope entry's number from its WZ name; 0 when the name is not a positive number. */
    public static int idOf(String name) {
        try {
            return Math.max(0, Integer.parseInt(name));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Top of the rope (smaller y = higher on screen). */
    public int topY() { return Math.min(y1, y2); }
    /** Bottom of the rope (larger y = lower on screen). */
    public int bottomY() { return Math.max(y1, y2); }
}
