package soloMapling.ArtificialPlayer.BotGrindSystem;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Cached map -> representative (median) mob level + mob count, read straight from WZ: Map.wz `life`
// entries of type "m" give the mob ids (following info/link like MapFactory), and each mob's level
// comes from its MonsterStats. Lazy per map, cached forever after. Deterministic - no recordings, no
// config. Maps with no mobs (towns) report level -1 and are naturally skipped by callers.
//
// Our own creation. Lets a TrainingBot discover level-appropriate nearby maps without a hand-authored
// table.
public final class MapMobIndex {

    private MapMobIndex() {
    }

    public record MapMobInfo(int medianLevel, int mobCount, List<Integer> mobIds) {
        static final MapMobInfo NONE = new MapMobInfo(-1, 0, List.of());
    }

    private static final ThreadLocal<DataProvider> MAP_SOURCE =
            ThreadLocal.withInitial(() -> DataProviderFactory.getDataProvider(WZFiles.MAP));
    private static final Map<Integer, MapMobInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MOB_LEVEL = new ConcurrentHashMap<>();

    // Representative (median) mob level of a map, or -1 if it has no mobs (towns, etc.).
    public static int level(int mapId) {
        return info(mapId).medianLevel();
    }

    // Number of mob spawn entries on a map.
    public static int mobCount(int mapId) {
        return info(mapId).mobCount();
    }

    // Mob ids the map is defined to spawn (from WZ life data), or empty for mobless maps (towns).
    public static List<Integer> mobIds(int mapId) {
        return info(mapId).mobIds();
    }

    public static MapMobInfo info(int mapId) {
        return CACHE.computeIfAbsent(mapId, MapMobIndex::compute);
    }

    private static MapMobInfo compute(int mapId) {
        try {
            Data mapData = loadMapData(mapId);
            if (mapData == null) {
                return MapMobInfo.NONE;
            }
            Data life = mapData.getChildByPath("life");
            if (life == null) {
                return MapMobInfo.NONE;
            }
            List<Integer> levels = new ArrayList<>();
            List<Integer> mobIds = new ArrayList<>();
            for (Data entry : life) {
                if (!"m".equals(DataTool.getString("type", entry, ""))) {
                    continue;
                }
                String idStr = DataTool.getString("id", entry, "");
                if (idStr.isEmpty()) {
                    continue;
                }
                int mobId;
                try {
                    mobId = Integer.parseInt(idStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                int lvl = mobLevel(mobId);
                if (lvl > 0) {
                    levels.add(lvl);
                    mobIds.add(mobId);
                }
            }
            if (levels.isEmpty()) {
                return MapMobInfo.NONE;
            }
            Collections.sort(levels);
            return new MapMobInfo(levels.get(levels.size() / 2), levels.size(), mobIds);
        } catch (RuntimeException e) {
            return MapMobInfo.NONE;
        }
    }

    private static Data loadMapData(int mapId) {
        DataProvider src = MAP_SOURCE.get();
        Data mapData = src.getData(mapImgPath(mapId));
        if (mapData == null) {
            return null;
        }
        Data info = mapData.getChildByPath("info");
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                Data linked = src.getData(mapImgPath(Integer.parseInt(link)));
                if (linked != null) {
                    return linked;
                }
            } catch (NumberFormatException ignored) {
                // malformed link — use the map as-is
            }
        }
        return mapData;
    }

    private static int mobLevel(int mobId) {
        return MOB_LEVEL.computeIfAbsent(mobId, id -> {
            try {
                Monster m = LifeFactory.getMonster(id);
                return (m == null || m.getStats() == null) ? -1 : m.getStats().getLevel();
            } catch (RuntimeException e) {
                return -1;
            }
        });
    }

    private static String mapImgPath(int mapId) {
        return "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img";
    }
}
