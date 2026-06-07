package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import tools.PacketCreator;

import java.util.Collections;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.facingLeft;

/**
 * Server-side attack animation helper for bots. 
 * Credit to NuTNNuT for attack animation values and code reference
 *
 * Bots have no real client, so they don't generate close-range-damage packets
 * the way players do. This helper synthesizes the broadcast packet directly so
 * other clients see the swing animation. We do NOT couple this to any actual
 * damage application — when the swing should also hit a reactor or monster,
 * the caller invokes the relevant logic separately (e.g. CustomReactor.hitReactor).
 *
 * Packet byte semantics in this Cosmic build (verified against AbstractDealDamageHandler.parseDamage):
 *   direction byte = body action id from Character/00002000.img (e.g. swingO1 = 5, swingP1 = 13)
 *   stance    byte = facing mask (0x80 = facing left, 0x00 = facing right)
 *   display   byte = 0 for a basic (non-skill) attack
 *   speed     byte = weapon attackSpeed (2..9; lower is faster). 4 is a safe default.
 *
 * The body-action id MUST match the equipped weapon class — a 1H swingO1 on a polearm
 * renders nothing. {@link BotAttackData#randomActionFor} picks the right variant.
 */
public final class BotAttack {

    /** Equip slot id for the main-hand weapon in v83. */
    private static final short EQUIP_SLOT_WEAPON = -11;

    private BotAttack() {}

    /**
     * Broadcast a basic Ctrl-attack swing animation. Pure visual; no damage,
     * no targets, no skill. Caller is responsible for any follow-up damage
     * application (e.g. reactor hit). The animation is selected based on the
     * bot's currently equipped weapon class.
     */
    public static void basicSwing(Character chr) {
        if (chr == null) return;

        int facingMask = facingLeft(chr) ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        WeaponType weaponType = resolveEquippedWeaponType(chr);
        int bodyActionId = BotAttackData.randomActionFor(weaponType);

        Map<Integer, AttackTarget> emptyTargets = Collections.emptyMap();
        chr.getMap().broadcastMessage(
                chr,
                PacketCreator.closeRangeAttack(
                        chr,
                        /* skill        */ 0,
                        /* skilllevel   */ 0,
                        /* stance       */ facingMask,
                        /* numAttackedAndDamage */ 0,
                        /* targets      */ emptyTargets,
                        /* speed        */ BotAttackData.DEFAULT_ATTACK_SPEED,
                        /* direction    */ bodyActionId,
                        /* display      */ 0
                ),
                /* repeatToSource */ false
        );
    }

    private static WeaponType resolveEquippedWeaponType(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem(EQUIP_SLOT_WEAPON);
        if (weapon == null) return null;
        return ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
    }
}
