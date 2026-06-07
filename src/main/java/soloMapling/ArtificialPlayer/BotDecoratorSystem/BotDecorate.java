package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotTier;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;


public class BotDecorate {

    /**
     * Fraction of bots that get queued for the full (expensive) decoration pass.
     * The rest keep only their QuickEquip generic/classless outfit - visually
     * distinct and much cheaper. Tune to taste while experimenting.
     */
    public static final double FULL_DECORATION_RATE = 0.5;

    public static int calculate_min_equip_level(short level) {
        int nearestLowInterval = (((level / 10) * 10) - 10) + 1;
        return nearestLowInterval;
    }

    /**
     * Returns a randomly selected BotTier based on a modified bell curve distribution:
     * - S tier is very rare (5%)
     * - A tier is uncommon (15%)
     * - B tier is common (40%)
     * - C tier is common (30%)
     * - D tier is uncommon (10%)
     *
     * @return A randomly selected BotTier
     */
    public static BotTier getRandomTier() {
        Random random = new Random();
        int roll = random.nextInt(100); // 0-99

        // original rates
//        if (roll < 5) {
//            return BotTier.S;       // 5% chance for S tier
//        } else if (roll < 20) {
//            return BotTier.A;       // 15% chance for A tier
//        } else if (roll < 60) {
//            return BotTier.B;       // 40% chance for B tier (most common)
//        } else if (roll < 90) {
//            return BotTier.C;       // 30% chance for C tier (2nd most common)
//        } else {
//            return BotTier.D;       // 10% chance for D tier
//        }

        // for demo
        if (roll < 30) {
            return BotTier.S;
        } else if (roll < 60) {
            return BotTier.A;
        } else if (roll < 80) {
            return BotTier.B;
        } else if (roll < 90) {
            return BotTier.C;
        } else {
            return BotTier.D;
        }

    }

    /**
     * Generates a random level for a bot based on its tier (S, A, B, C) and the overall level range.
     * S tier gets the highest levels, followed by A, then B, with C tier getting the lowest levels.
     *
     * @param tier     The tier of the bot (S, A, B, C)
     * @param minLevel The minimum level in the range
     * @param maxLevel The maximum level in the range
     * @return A randomly selected level appropriate for the tier
     */
    public static int generateBotLevel(BotTier tier, int minLevel, int maxLevel) {
        // Validate input parameters
        if (minLevel >= maxLevel) {
            throw new IllegalArgumentException("minLevel must be less than maxLevel");
        }

        // Calculate the total range
        int range = maxLevel - minLevel + 1;

        // Define sub-ranges based on the tier
        int lowerBound, upperBound;

        switch (tier) {
            case BotTier.S:
                // S tier gets the top quarter of the range
                lowerBound = minLevel + (3 * range / 4);
                upperBound = maxLevel;
                break;
            case BotTier.A:
                // A tier gets the second highest quarter
                lowerBound = minLevel + (2 * range / 4);
                upperBound = minLevel + (3 * range / 4) - 1;
                break;
            case BotTier.B:
                // B tier gets the second lowest quarter
                lowerBound = minLevel + (range / 4);
                upperBound = minLevel + (2 * range / 4) - 1;
                break;
            case BotTier.C:
            case BotTier.D:
                // C tier gets the bottom quarter of the range
                lowerBound = minLevel;
                upperBound = minLevel + (range / 4) - 1;
                break;
            default:
                throw new IllegalArgumentException("Unknown BotTier: " + tier);
        }

        // Generate a random number in the specified sub-range
        return (int) (Math.random() * (upperBound - lowerBound + 1)) + lowerBound;
    }

    /**
     * Selects an appropriate MapleStory job based on character level.
     *
     * @param level The character's level
     * @return The job ID constant corresponding to the appropriate job
     */
    public static int selectJobByLevel(int level) {
        // Beginner for levels 1-9
        if (level < 10) {
            return 0; // BEGINNER
        }

        // Randomly select a base class (Warrior, Magician, Bowman, or Thief)
        int baseClass = (int) (Math.random() * 4) + 1;

        // First job advancement (levels 10-29)
        if (level < 30) {
            switch (baseClass) {
                case 1:
                    return 100; // WARRIOR
                case 2:
                    return 200; // MAGICIAN
                case 3:
                    return 300; // BOWMAN
                case 4:
                    return 400; // THIEF
                default:
                    return 0;  // BEGINNER (fallback)
            }
        }

        // Second job advancement (levels 30-69)
        if (level < 70) {
            switch (baseClass) {
                case 1: // WARRIOR paths
                    int warriorPath = (int) (Math.random() * 3) + 1;
                    switch (warriorPath) {
                        case 1:
                            return 110; // FIGHTER
                        case 2:
                            return 120; // PAGE
                        case 3:
                            return 130; // SPEARMAN
                    }
                case 2: // MAGICIAN paths
                    int magePath = (int) (Math.random() * 3) + 1;
                    switch (magePath) {
                        case 1:
                            return 210; // FP_WIZARD
                        case 2:
                            return 220; // IL_WIZARD
                        case 3:
                            return 230; // CLERIC
                    }
                case 3: // BOWMAN paths
                    return (Math.random() < 0.5) ? 310 : 320; // HUNTER or CROSSBOWMAN
                case 4: // THIEF paths
                    return (Math.random() < 0.5) ? 410 : 420; // ASSASSIN or BANDIT
                default:
                    return 0; // BEGINNER (fallback)
            }
        }

        // Third job advancement (levels 70-119)
        if (level < 120) {
            switch (baseClass) {
                case 1: // WARRIOR paths
                    int warriorPath = (int) (Math.random() * 3) + 1;
                    switch (warriorPath) {
                        case 1:
                            return 111; // CRUSADER
                        case 2:
                            return 121; // WHITEKNIGHT
                        case 3:
                            return 131; // DRAGONKNIGHT
                    }
                case 2: // MAGICIAN paths
                    int magePath = (int) (Math.random() * 3) + 1;
                    switch (magePath) {
                        case 1:
                            return 211; // FP_MAGE
                        case 2:
                            return 221; // IL_MAGE
                        case 3:
                            return 231; // PRIEST
                    }
                case 3: // BOWMAN paths
                    return (Math.random() < 0.5) ? 311 : 321; // RANGER or SNIPER
                case 4: // THIEF paths
                    return (Math.random() < 0.5) ? 411 : 421; // HERMIT or CHIEFBANDIT
                default:
                    return 0; // BEGINNER (fallback)
            }
        }

        // Fourth job advancement (levels 120+)
        switch (baseClass) {
            case 1: // WARRIOR paths
                int warriorPath = (int) (Math.random() * 3) + 1;
                switch (warriorPath) {
                    case 1:
                        return 112; // HERO
                    case 2:
                        return 122; // PALADIN
                    case 3:
                        return 132; // DARKKNIGHT
                }
            case 2: // MAGICIAN paths
                int magePath = (int) (Math.random() * 3) + 1;
                switch (magePath) {
                    case 1:
                        return 212; // FP_ARCHMAGE
                    case 2:
                        return 222; // IL_ARCHMAGE
                    case 3:
                        return 232; // BISHOP
                }
            case 3: // BOWMAN paths
                return (Math.random() < 0.5) ? 312 : 322; // BOWMASTER or MARKSMAN
            case 4: // THIEF paths
                return (Math.random() < 0.5) ? 412 : 422; // NIGHTLORD or SHADOWER
            default:
                return 0; // BEGINNER (fallback)
        }
    }

    /**
     * Selects a gender at random with 50/50 probability.
     *
     * @return 0 for male, 1 for female
     */
    public static int selectRandomGender() {
        // Create a Random object
        Random random = new Random();

        // Return 0 (male) or 1 (female) with equal probability
        return random.nextInt(2);
    }

    public static void setBotVariables(Character bot) {
        BotTier tier = getRandomTier();
        bot.setTier(tier);
        int level = generateBotLevel(tier, 10, 80);
        int job = selectJobByLevel(level);
        bot.setGender(selectRandomGender());
        bot.setLevel(level);
        bot.setJob(Job.getById(job));

        BotDecorateBody.decorateBotBody(bot);

        // Quick generic equip at spawn - fast, tiny curated list, no WZ lookups.
        // 100% of bots go through this path.
        QuickEquip.apply(bot);

        // Safety net: if QuickEquip left the bot with no clothing (common for
        // low-level bots where the curated pool has no matching items), force
        // the full decoration so they don't walk around shirtless.
        boolean hasClothing = bot.getInventory(InventoryType.EQUIPPED).getItem((short) -5) != null  // coat/overall
                           || bot.getInventory(InventoryType.EQUIPPED).getItem((short) -6) != null; // pants
        if (!hasClothing) {
            BotDecorationQueue.addBot("default", bot.getId());
        }

        // NX cosmetic layer - runs on every bot regardless of which equip path
        // it took (QuickEquip-only or QuickEquip + full decoration). Its own
        // 30% base gate decides whether the bot actually gets any NX pieces.
        BotDecorateNX.apply(bot);

        // Only a fraction of bots get queued for the expensive class-aware full
        // decoration. The rest keep their generic/classless QuickEquip look,
        // which gives the population a distinct mix of "casual" and "kitted out"
        // bots and keeps CPU cost bounded when the queue is running.
        if (hasClothing && ThreadLocalRandom.current().nextDouble() < FULL_DECORATION_RATE) {
            BotDecorationQueue.addBot("default", bot.getId());
        }
    }

    /**
     * Convenience method: add a bot to the deferred decoration queue under a specific category.
     * Call this after spawn if you want a category other than "default" (e.g. "fm", "henesys").
     */
    public static void addBotToDecorationQueue(String category, int botId) {
        BotDecorationQueue.addBot(category, botId);
    }
}
