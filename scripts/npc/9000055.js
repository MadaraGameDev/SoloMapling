/**
 * Casino Chip Exchange NPC (9201066)
 * Opens the casino chip shop where players can buy and sell
 * stamp chips at equal prices for lossless meso exchange.
 *
 * Shop ID 9999001 must exist in the database (shops + shopitems tables).
 */
function start() {
    cm.openShopNPC(9999001);
    cm.dispose();
}
