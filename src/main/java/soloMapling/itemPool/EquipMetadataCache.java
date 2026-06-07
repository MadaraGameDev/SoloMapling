package soloMapling.itemPool;

import constants.inventory.EquipType;
import server.ItemInformationProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time startup cache of all equip metadata parsed from WZ.
 * After {@link #initialize()} completes, every query is a pure in-memory
 * lookup — no WZ access, no iteration over ID ranges.
 *
 * <p>Usage:
 * <pre>
 *   EquipMetadataCache.initialize();              // once, after WZ is ready
 *   List&lt;EquipEntry&gt; nxCaps = EquipMetadataCache.get()
 *       .query(EquipType.CAP).cashOnly().asList();
 * </pre>
 */
public class EquipMetadataCache {

    // ── Entry ────────────────────────────────────────────────────────────

    public static class EquipEntry {
        public final int id;
        public final EquipType equipType;
        public final int gender;      // 0=male, 1=female, 2=unisex (4th-digit convention)
        public final int reqLevel;
        public final int reqJob;       // bitmask: 0=beginner/all, 1=warrior, 2=mage, 4=bow, 8=thief, 16=pirate
        public final boolean cash;
        public final boolean untradeable;
        public final boolean quest;
        public final int wzPrice;
        public final String name;

        EquipEntry(int id, EquipType equipType, int gender, int reqLevel,
                   int reqJob, boolean cash, boolean untradeable, boolean quest,
                   int wzPrice, String name) {
            this.id = id;
            this.equipType = equipType;
            this.gender = gender;
            this.reqLevel = reqLevel;
            this.reqJob = reqJob;
            this.cash = cash;
            this.untradeable = untradeable;
            this.quest = quest;
            this.wzPrice = wzPrice;
            this.name = name;
        }

        @Override
        public String toString() {
            return id + " " + name + " [" + equipType + " lv" + reqLevel
                    + (cash ? " NX" : "") + "]";
        }
    }

    // ── ID ranges to scan ────────────────────────────────────────────────
    // Covers every equip category in v83. Ranges are inclusive.
    // Body equips use the standard equipTypeRanges from ItemInformationProviderUtilities.
    // We also add face accessories and rings which lack EquipType range entries.

    private static final Map<EquipType, int[]> EQUIP_RANGES = new LinkedHashMap<>();

    static {
        // Body / armor
        EQUIP_RANGES.put(EquipType.CAP,       new int[]{1000000, 1003073});
        EQUIP_RANGES.put(EquipType.FACE,       new int[]{1012000, 1012200});  // face accessories
        EQUIP_RANGES.put(EquipType.ACCESSORY,  new int[]{1022000, 1022104});  // eye accessories
        EQUIP_RANGES.put(EquipType.EARRING,    new int[]{1032000, 1032075});
        EQUIP_RANGES.put(EquipType.COAT,       new int[]{1040000, 1049000});
        EQUIP_RANGES.put(EquipType.LONGCOAT,   new int[]{1050000, 1052234});
        EQUIP_RANGES.put(EquipType.PANTS,      new int[]{1060000, 1062119});
        EQUIP_RANGES.put(EquipType.SHOES,      new int[]{1070000, 1072437});
        EQUIP_RANGES.put(EquipType.GLOVES,     new int[]{1080000, 1082262});
        EQUIP_RANGES.put(EquipType.SHIELD,     new int[]{1092000, 1092062});
        EQUIP_RANGES.put(EquipType.CAPE,       new int[]{1102000, 1102236});
        EQUIP_RANGES.put(EquipType.RING,       new int[]{1112000, 1112400});
        // Weapons
        EQUIP_RANGES.put(EquipType.SWORD,      new int[]{1302000, 1302133});
        EQUIP_RANGES.put(EquipType.AXE,        new int[]{1312000, 1312046});
        EQUIP_RANGES.put(EquipType.MACE,       new int[]{1322000, 1322074});
        EQUIP_RANGES.put(EquipType.DAGGER,     new int[]{1332000, 1332088});
        EQUIP_RANGES.put(EquipType.WAND,       new int[]{1372000, 1372046});
        EQUIP_RANGES.put(EquipType.STAFF,      new int[]{1382000, 1382062});
        EQUIP_RANGES.put(EquipType.SWORD_2H,   new int[]{1402000, 1402072});
        EQUIP_RANGES.put(EquipType.AXE_2H,     new int[]{1412000, 1412046});
        EQUIP_RANGES.put(EquipType.MACE_2H,    new int[]{1422000, 1422047});
        EQUIP_RANGES.put(EquipType.SPEAR,      new int[]{1432000, 1432061});
        EQUIP_RANGES.put(EquipType.POLEARM,    new int[]{1442000, 1442103});
        EQUIP_RANGES.put(EquipType.BOW,        new int[]{1452000, 1452085});
        EQUIP_RANGES.put(EquipType.CROSSBOW,   new int[]{1462000, 1462075});
        EQUIP_RANGES.put(EquipType.CLAW,       new int[]{1472000, 1472100});
        EQUIP_RANGES.put(EquipType.KNUCKLER,   new int[]{1482000, 1482046});
        EQUIP_RANGES.put(EquipType.PISTOL,     new int[]{1492000, 1492048});
    }

    // ── Singleton ────────────────────────────────────────────────────────

    private static volatile EquipMetadataCache instance;
    private static volatile boolean initialized = false;

    private final List<EquipEntry> allEquips;
    private final Map<EquipType, List<EquipEntry>> byType;
    private final Map<EquipType, List<EquipEntry>> cashByType;

    private EquipMetadataCache(List<EquipEntry> allEquips,
                               Map<EquipType, List<EquipEntry>> byType,
                               Map<EquipType, List<EquipEntry>> cashByType) {
        this.allEquips = allEquips;
        this.byType = byType;
        this.cashByType = cashByType;
    }

    public static EquipMetadataCache get() {
        if (!initialized) {
            throw new IllegalStateException("[EquipMetadataCache] Not initialized — call initialize() first");
        }
        return instance;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ── Initialization ───────────────────────────────────────────────────

    public static synchronized void initialize() {
        if (initialized) return;

        long start = System.currentTimeMillis();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        List<EquipEntry> all = new ArrayList<>();
        Map<EquipType, List<EquipEntry>> typeMap = new ConcurrentHashMap<>();
        Map<EquipType, List<EquipEntry>> cashMap = new ConcurrentHashMap<>();

        int scanned = 0;
        for (Map.Entry<EquipType, int[]> rangeEntry : EQUIP_RANGES.entrySet()) {
            EquipType eqType = rangeEntry.getKey();
            int[] range = rangeEntry.getValue();

            List<EquipEntry> typeList = new ArrayList<>();
            List<EquipEntry> cashList = new ArrayList<>();

            for (int id = range[0]; id <= range[1]; id++) {
                Map<String, Integer> stats = ii.getEquipStats(id);
                if (stats == null) continue;  // item doesn't exist in WZ

                scanned++;
                int gender = deriveGender(id);
                int reqLevel = stats.getOrDefault("reqLevel", 0);
                int reqJob = stats.getOrDefault("reqJob", 0);
                boolean isCash = stats.getOrDefault("cash", 0) == 1;
                boolean isUntradeable = ii.isUntradeableRestricted(id);
                boolean isQuest = ii.isQuestItem(id);
                int wzPrice = ii.getWholePrice(id);
                String name = ii.getName(id);

                EquipEntry entry = new EquipEntry(id, eqType, gender, reqLevel,
                        reqJob, isCash, isUntradeable, isQuest, wzPrice,
                        name != null ? name : "?");

                all.add(entry);
                typeList.add(entry);
                if (isCash) {
                    cashList.add(entry);
                }
            }

            typeMap.put(eqType, Collections.unmodifiableList(typeList));
            cashMap.put(eqType, Collections.unmodifiableList(cashList));
        }

        instance = new EquipMetadataCache(
                Collections.unmodifiableList(all),
                Collections.unmodifiableMap(typeMap),
                Collections.unmodifiableMap(cashMap)
        );
        initialized = true;

        long elapsed = System.currentTimeMillis() - start;
        int cashTotal = cashMap.values().stream().mapToInt(List::size).sum();
        System.out.println("[EquipMetadataCache] Initialized in " + elapsed + "ms — "
                + scanned + " equips cached (" + cashTotal + " cash/NX)");
    }

    // ── Gender derivation (4th digit convention) ─────────────────────────

    private static int deriveGender(int itemId) {
        String s = Integer.toString(itemId);
        if (s.length() >= 4) {
            int digit = Character.getNumericValue(s.charAt(3));
            if (digit >= 0 && digit <= 2) return digit;
        }
        return 2; // default unisex
    }

    // ── Query API ────────────────────────────────────────────────────────

    /** Get all cached equips. */
    public List<EquipEntry> all() {
        return allEquips;
    }

    /** Get all equips of a given type. */
    public List<EquipEntry> getByType(EquipType type) {
        return byType.getOrDefault(type, Collections.emptyList());
    }

    /** Get all cash/NX equips of a given type (pre-filtered, instant). */
    public List<EquipEntry> getCashByType(EquipType type) {
        return cashByType.getOrDefault(type, Collections.emptyList());
    }

    /** Start a fluent filtered query. */
    public EquipQuery query(EquipType type) {
        return new EquipQuery(getByType(type));
    }

    /** Start a fluent query pre-filtered to cash items. */
    public EquipQuery queryCash(EquipType type) {
        return new EquipQuery(getCashByType(type));
    }

    // ── Fluent query builder ─────────────────────────────────────────────

    public static class EquipQuery {
        private List<EquipEntry> source;

        EquipQuery(List<EquipEntry> source) {
            this.source = source;
        }

        public EquipQuery cashOnly() {
            List<EquipEntry> filtered = new ArrayList<>();
            for (EquipEntry e : source) {
                if (e.cash) filtered.add(e);
            }
            source = filtered;
            return this;
        }

        /** Filter to items matching the given bot gender (includes unisex). */
        public EquipQuery forGender(int gender) {
            List<EquipEntry> filtered = new ArrayList<>();
            for (EquipEntry e : source) {
                if (e.gender == 2 || e.gender == gender) filtered.add(e);
            }
            source = filtered;
            return this;
        }

        /** Filter to items with reqLevel <= maxLevel. */
        public EquipQuery maxLevel(int maxLevel) {
            List<EquipEntry> filtered = new ArrayList<>();
            for (EquipEntry e : source) {
                if (e.reqLevel <= maxLevel) filtered.add(e);
            }
            source = filtered;
            return this;
        }

        /** Filter to items whose reqJob bitmask matches (0 = any job). */
        public EquipQuery forJob(int reqJobBitmask) {
            List<EquipEntry> filtered = new ArrayList<>();
            for (EquipEntry e : source) {
                if (e.reqJob == 0 || (e.reqJob & reqJobBitmask) != 0) filtered.add(e);
            }
            source = filtered;
            return this;
        }

        public List<EquipEntry> asList() {
            return source;
        }

        public int count() {
            return source.size();
        }

        /** Pick one at random, or null if empty. */
        public EquipEntry random() {
            if (source.isEmpty()) return null;
            return source.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(source.size()));
        }
    }
}
