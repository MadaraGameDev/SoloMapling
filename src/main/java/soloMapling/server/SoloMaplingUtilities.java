package soloMapling.server;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.maps.MapleMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class SoloMaplingUtilities {

    public static Random random = new Random();
    public static final Channel channel = Server.getInstance().getChannel(SoloMaplingConstants.GameConstants.WORLD_SCANIA, SoloMaplingConstants.GameConstants.CHANNEL_1);
    public static final World world = Server.getInstance().getWorld(SoloMaplingConstants.GameConstants.WORLD_SCANIA);


    public static Item AppendScrolledStatsToEquip(Equip equipToScroll, Map<String, Object> dictionary) {
        Equip finalItem = equipToScroll;
        for (Map.Entry<String, Object> entry : dictionary.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            switch (key) {
                case "watk":
                    finalItem.setWatk(addShort(finalItem.getWatk(), value));
                    break;
                case "matk":
                    finalItem.setMatk(addShort(finalItem.getMatk(), value));
                    break;
                case "str":
                    finalItem.setStr(addShort(finalItem.getStr(), value));
                    break;
                case "dex":
                    finalItem.setDex(addShort(finalItem.getDex(), value));
                    break;
                case "int":
                    finalItem.setInt(addShort(finalItem.getInt(), value));
                    break;
                case "luk":
                    finalItem.setLuk(addShort(finalItem.getLuk(), value));
                    break;
                case "hp":
                    finalItem.setHp(addShort(finalItem.getHp(), value));
                    break;
                case "mp":
                    finalItem.setMp(addShort(finalItem.getMp(), value));
                    break;
                case "level": // Number of Successful Scrolls (Next to +X number next to name)
                    finalItem.setLevel(addByte(finalItem.getLevel(), value));
                    break;
                case "upgradeSlots": // Number of Slots left
                    finalItem.setUpgradeSlots(convertToByte(value));
                    break;
//                case "hammer":
//                case "vicioushammer":
//                    finalItem.setViciousHammer(addByte(finalItem.getViciousHammer(), value));
//                    break;
                case "owner":
                    finalItem.setOwner((String) value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown key: " + key);
            }
        }
        return finalItem;
    }

    // Only used for hard coding in code the stats of an equip. Use createDictionary instead.
    public static <K, V> Map<K, V> createDictionaryDynamicObject(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid number of arguments. Must be an even number.");
        }

        Map<K, V> dictionary = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            K key = (K) keyValuePairs[i];
            V value = (V) keyValuePairs[i + 1];
            dictionary.put(key, value);
        }
        return dictionary;
    }

    private static short addShort(short currentValue, Object value) {
        return (short) (currentValue + convertToShort(value));
    }

    private static byte addByte(byte currentValue, Object value) {
        return (byte) (currentValue + convertToByte(value));
    }

    private static short convertToShort(Object obj) {
        if (obj instanceof Short) {
            return (Short) obj;
        } else if (obj instanceof Number) {
            return ((Number) obj).shortValue();
        } else {
            throw new IllegalArgumentException("Cannot convert Object to short");
        }
    }

    private static byte convertToByte(Object obj) {
        if (obj instanceof Byte) {
            return (Byte) obj;
        } else if (obj instanceof Number) {
            return ((Number) obj).byteValue();
        } else {
            throw new IllegalArgumentException("Cannot convert Object to byte");
        }
    }

//    public static int getRandomNumber(int min, int max) {
//        if (min > max) {
//            throw new IllegalArgumentException("Max must be greater than min");
//        }
//        return random.nextInt((max - min) + 1) + min;
//    }

    // this function is very unintuitive. meant to be a dice, but many think its a % chance.
    // The bigger the number, the lower the chance
    // DO NOT USE THIS ANYMORE. JUST LEAVE IT AS IS.
    public static boolean rollChanceInverse(int outOf) {
        return new Random().nextInt(outOf) == 0;
    }

    // Use this for easy boolean % Chance calculating
    public static boolean chance(double percent) {
        return ThreadLocalRandom.current().nextDouble(100) < percent;
    }

    public static String pickRandomItem(List<String> items) {
        return items.get(random.nextInt(items.size()));
    }

    public static boolean isInteger(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static int getRandomId(List<Integer> randomList) {
        if (randomList.isEmpty()) {
            return 0;
        }
        return getRandomElement(randomList);
    }

    public static <T> T getRandomElement(List<T> list) {
        try {
            Random random = new Random();
            return list.get(random.nextInt(list.size()));
        } catch (IllegalArgumentException e) {
            return null; // or some default value
        }
    }

    public static client.Character getChr(int userId) {
        Character chr = SoloMaplingConstants.mainChannel.getPlayerStorage().getCharacterById(userId);
        return chr;
    }

    public static Character getChr(String name) {
        Character chr = SoloMaplingConstants.mainChannel.getPlayerStorage().getCharacterByName(name);
        return chr;
    }

    public static String getTextAfterColon(String input) {
        int colonIndex = input.indexOf(":");
        if (colonIndex != -1 && colonIndex < input.length() - 1) {
            return input.substring(colonIndex + 1).trim();
        }
        return null; // Return null if no colon or text is found
    }

    public static boolean isNumeric(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Waits for a condition to become true with retry logic
     * @param condition The boolean condition to check (as a Supplier)
     * @param maxAttempts Maximum number of attempts
     * @param delayMs Delay between attempts in milliseconds
     * @return true if condition became true, false if max attempts reached
     */
    public static boolean waitForCondition(Supplier<Boolean> condition, int maxAttempts, long delayMs) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (condition.get()) {
                System.out.println("Condition met on attempt " + attempt);
                return true;
            }

            if (attempt < maxAttempts) {
                System.out.println("Attempt " + attempt + " failed, waiting " + delayMs + "ms...");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        System.out.println("Max attempts (" + maxAttempts + ") reached, condition not met");
        return false;
    }

    /**
     * Overloaded method with default values (15 attempts, 2 seconds)
     */
    public static boolean waitForCondition(Supplier<Boolean> condition) {
        return waitForCondition(condition, 15, 2000);
    }

    public static int getRandomNumber(int x, int y) {
        return generateRandomNumber(x, y);
    }

    public static int generateRandomNumber(int x, int y) {
        if (x > y) {
            throw new IllegalArgumentException("x must be less than or equal to y.");
        }
        Random random = new Random();
        // Generate a random number between x and y (inclusive)
        return random.nextInt(y - x + 1) + x;
    }

    public static int getRandomNumber(List<Integer> numbers) {
        Random rand = new Random();
        int index = rand.nextInt(numbers.size()); // pick a random index
        return numbers.get(index);
    }

    public static MapleMap getMapleMapById(int id) {
        MapleMap map = Server.getInstance().getChannel(0, 1).getMapFactory().getMap(id);
        return map;
    }

}
