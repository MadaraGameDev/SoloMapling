package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.inventory.WeaponType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal attack-animation data for bots, extracted from the Character/00002000.img
 * body-action table and the per-weapon-class swing buckets used by the real client.
 *
 * The close-range-attack packet's "direction" byte in this Cosmic build is actually
 * the body action id (NOT a left/right toggle), and the "stance" byte is the facing
 * mask (0x80 left / 0x00 right). Without the right action id the client picks no
 * animation and the swing is invisible.
 *
 * For ranged classes (bow, crossbow, gun) we use the "degenerate close-range" swing
 * actions — the same animations the client falls back to when an archer is too close
 * to fire. This keeps every weapon class visually responsive through the single
 * close-range packet path, without requiring real ranged/magic packet plumbing.
 */
public final class BotAttackData {

    private BotAttackData() {}

    /** Facing mask for the close-range-attack stance byte. */
    public static final int FACING_RIGHT_MASK = 0x00;
    public static final int FACING_LEFT_MASK  = 0x80;

    /** Default attack speed when the weapon profile is unknown. v83 range is 2-9 (lower = faster). */
    public static final int DEFAULT_ATTACK_SPEED = 4;

    // ----- Body action ids from Character/00002000.img.xml -----
    // Standard 1H over-swing block
    private static final int SWING_O1 = 5;
    private static final int SWING_O2 = 6;
    private static final int SWING_O3 = 7;
    // 2H over-swing block
    private static final int SWING_T1 = 9;
    private static final int SWING_T2 = 10;
    private static final int SWING_T3 = 11;
    // Polearm swing block
    private static final int SWING_P1 = 13;
    private static final int SWING_P2 = 14;
    // 1H stab block
    private static final int STAB_O1 = 16;
    private static final int STAB_O2 = 17;
    // 2H stab block
    private static final int STAB_T1 = 19;
    private static final int STAB_T2 = 20;
    // Wand/staff use a separate swingO block in the actions table
    private static final int WAND_SWING_O1 = 28;
    private static final int WAND_SWING_O3 = 29;
    // Claw uses the second swingO block (not the standard sword one)
    private static final int CLAW_SWING_O1 = 24;
    private static final int CLAW_SWING_O2 = 25;
    private static final int CLAW_SWING_O3 = 26;

    // ----- Per-weapon-class action variants (mirrors OTHERCODE's getBasicAttackSpec switch) -----
    private static final int[] DEFAULT_1H_VARIANTS    = {STAB_O1, STAB_O2, SWING_O1, SWING_O2, SWING_O3};
    private static final int[] HEAVY_2H_VARIANTS      = {STAB_O1, STAB_O2, SWING_T1, SWING_T2, SWING_T3};
    private static final int[] POLEARM_VARIANTS       = {SWING_P1, STAB_T1};
    private static final int[] WAND_VARIANTS          = {WAND_SWING_O1, WAND_SWING_O3};
    private static final int[] CLAW_VARIANTS          = {CLAW_SWING_O1, CLAW_SWING_O2, CLAW_SWING_O3};
    // Degenerate close-range fallbacks for ranged weapons (used when too close to fire normally)
    private static final int[] BOW_DEGENERATE         = {SWING_T1, SWING_T3};
    private static final int[] CROSSBOW_DEGENERATE    = {SWING_T1, STAB_T1};
    private static final int[] GUN_DEGENERATE         = {SWING_P1, STAB_T2};

    /** Random body-action id appropriate for the given weapon class. */
    public static int randomActionFor(WeaponType weaponType) {
        int[] variants = variantsFor(weaponType);
        return variants[ThreadLocalRandom.current().nextInt(variants.length)];
    }

    private static int[] variantsFor(WeaponType weaponType) {
        if (weaponType == null) {
            return DEFAULT_1H_VARIANTS;
        }
        return switch (weaponType) {
            case SWORD2H, GENERAL2H_SWING, GENERAL2H_STAB                 -> HEAVY_2H_VARIANTS;
            case SPEAR_SWING, SPEAR_STAB, POLE_ARM_SWING, POLE_ARM_STAB   -> POLEARM_VARIANTS;
            case WAND, STAFF                                              -> WAND_VARIANTS;
            case CLAW                                                     -> CLAW_VARIANTS;
            case BOW                                                      -> BOW_DEGENERATE;
            case CROSSBOW                                                 -> CROSSBOW_DEGENERATE;
            case GUN                                                      -> GUN_DEGENERATE;
            // SWORD1H, GENERAL1H_SWING, GENERAL1H_STAB, DAGGER_OTHER, DAGGER_THIEVES, KNUCKLE, NOT_A_WEAPON
            default -> DEFAULT_1H_VARIANTS;
        };
    }
}
