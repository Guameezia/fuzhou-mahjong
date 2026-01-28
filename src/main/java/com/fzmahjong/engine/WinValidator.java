package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 和牌判断器
 */
public class WinValidator {

    private static final Logger log = LoggerFactory.getLogger(WinValidator.class);

    /**
     * 检查是否可以和牌
     * 福州麻将和牌规则：
     * 1. 平和：标准麻将胡牌形式（n个顺子/刻子 + 1对将）
     * 2. 三头金：手里有3张金
     * 3. 抢金：开金后抓到金就和
     * 4. 金将：2张金做将，其他组成面子
     */
    public static boolean canWin(List<Tile> handTiles, Tile goldTile, boolean isQiangJin) {
        if (handTiles == null || handTiles.isEmpty()) {
            return false;
        }

        // 过滤掉花牌（FLOWER类型），花牌不参与胡牌判断
        List<Tile> validTiles = new ArrayList<>();
        for (Tile tile : handTiles) {
            if (tile.getType() != TileType.FLOWER) {
                validTiles.add(tile);
            }
        }

        // 如果过滤后没有有效牌，不能胡
        if (validTiles.isEmpty()) {
            return false;
        }

        // 抢金判断（开金阶段抓到金）
        if (isQiangJin) {
            return true;
        }

        // 统计金牌数量（使用过滤后的牌）
        int goldCount = countGoldTiles(validTiles, goldTile);

        // 三头金（3张金）
        if (goldCount >= 3) {
            log.info("三头金！");
            return true;
        }

        // 标准和牌判断（包括金雀、金将、金作面子），使用过滤后的牌
        // 关键：金牌按“万能牌（癞子）”处理，不作为固定牌型参与分组与计数，避免双重计数
        List<Tile> tilesWithoutGold = removeAllGoldTiles(validTiles, goldTile);
        return checkStandardWin(validTiles.size(), tilesWithoutGold, goldCount);
    }

    /**
     * 统计金牌数量
     */
    private static int countGoldTiles(List<Tile> handTiles, Tile goldTile) {
        if (goldTile == null) {
            return 0;
        }
        
        int count = 0;
        for (Tile tile : handTiles) {
            if (tile.isSameAs(goldTile)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查标准和牌
     */
    private static boolean checkStandardWin(int totalTileCount, List<Tile> tilesWithoutGold, int goldCount) {
        // 福州麻将（16 张体系）张数要点：
        // - 开局：庄家 17 张，其余 16 张
        // - 自摸/点炮胡牌时：暗牌 + (点炮牌) 的总数应满足「3n + 2」
        //   在本实现里，n=5 时总数为 17（= 5 副面子 + 1 对将）。
        // - 吃/碰/杠后，暗牌会每次减少 3 张，因此可能出现 17、14、11、8、5、2 等。
        //
        // 注意：这里只拿到“暗牌（+可能点炮加入的一张）”，不包含桌面明牌，
        // 所以不能写死为 17，只能按「3n + 2」约束并做上限保护。
        if (totalTileCount < 2 || totalTileCount > 17 || (totalTileCount % 3) != 2) {
            return false;
        }

        // 将（对子）有三种可能：
        // 1) 两张普通牌
        // 2) 一张普通牌 + 一张金（癞子）
        // 3) 两张金

        // 3) 两张金做将
        if (goldCount >= 2) {
            if (checkAllMelds(new ArrayList<>(tilesWithoutGold), goldCount - 2)) {
                log.info("金将和牌！");
                return true;
            }
        }

        // 1) / 2) 普通牌相关的将
        Map<String, Integer> tileCountMap = buildTileCountMap(tilesWithoutGold);
        for (Map.Entry<String, Integer> entry : tileCountMap.entrySet()) {
            String key = entry.getKey();
            int count = entry.getValue();

            // 1) 两张普通牌做将
            if (count >= 2) {
                List<Tile> remaining = new ArrayList<>(tilesWithoutGold);
                int removed = removeTilesByKey(remaining, key, 2);
                if (removed == 2 && checkAllMelds(remaining, goldCount)) {
                    log.info("平和！");
                    return true;
                }
            }

            // 2) 一张普通牌 + 一张金做将（金雀）
            if (count >= 1 && goldCount >= 1) {
                List<Tile> remaining = new ArrayList<>(tilesWithoutGold);
                int removed = removeTilesByKey(remaining, key, 1);
                if (removed == 1 && checkAllMelds(remaining, goldCount - 1)) {
                    log.info("金雀和牌！");
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查剩余牌是否都能组成面子（顺子或刻子）
     */
    private static boolean checkAllMelds(List<Tile> tiles, int goldCount) {
        if (tiles.isEmpty()) {
            return true;
        }

        // 按类型分组
        Map<TileType, List<Tile>> groupedTiles = groupByType(tiles);
        
        // 递归检查每种类型的牌
        return checkMeldsRecursive(groupedTiles, goldCount);
    }

    /**
     * 递归检查面子
     */
    private static boolean checkMeldsRecursive(Map<TileType, List<Tile>> groupedTiles, int goldCount) {
        // 所有牌都处理完了
        if (groupedTiles.values().stream().allMatch(List::isEmpty)) {
            return goldCount == 0; // 金牌也要用完
        }

        // 找到第一组有牌的类型
        TileType currentType = null;
        for (Map.Entry<TileType, List<Tile>> entry : groupedTiles.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                currentType = entry.getKey();
                break;
            }
        }

        if (currentType == null) {
            return goldCount == 0;
        }

        List<Tile> currentTiles = groupedTiles.get(currentType);
        Tile firstTile = currentTiles.get(0);

        // 跳过花牌类型（FLOWER），花牌不参与胡牌判断
        if (currentType == TileType.FLOWER) {
            // 移除所有花牌，继续处理其他牌
            groupedTiles.get(currentType).clear();
            return checkMeldsRecursive(groupedTiles, goldCount);
        }

        // 尝试组成刻子（3张相同）
        if (tryFormKezi(groupedTiles, currentType, firstTile, goldCount)) {
            return true;
        }

        // 尝试组成顺子（3张连续）
        if (!firstTile.isFlowerTile() && tryFormShunzi(groupedTiles, currentType, firstTile, goldCount)) {
            return true;
        }

        return false;
    }

    /**
     * 尝试组成刻子
     */
    private static boolean tryFormKezi(Map<TileType, List<Tile>> groupedTiles, 
                                       TileType type, Tile tile, 
                                       int goldCount) {
        List<Tile> tiles = groupedTiles.get(type);
        int sameCount = countSameTiles(tiles, tile);
        
        // 有3张相同的
        if (sameCount >= 3) {
            List<Tile> removed = removeSameTiles(tiles, tile, 3);
            if (checkMeldsRecursive(groupedTiles, goldCount)) {
                return true;
            }
            tiles.addAll(removed); // 回溯
        }
        
        // 2张相同 + 1张金
        if (sameCount >= 2 && goldCount >= 1) {
            List<Tile> removed = removeSameTiles(tiles, tile, 2);
            if (checkMeldsRecursive(groupedTiles, goldCount - 1)) {
                return true;
            }
            tiles.addAll(removed); // 回溯
        }
        
        // 1张相同 + 2张金
        if (sameCount >= 1 && goldCount >= 2) {
            List<Tile> removed = removeSameTiles(tiles, tile, 1);
            if (checkMeldsRecursive(groupedTiles, goldCount - 2)) {
                return true;
            }
            tiles.addAll(removed); // 回溯
        }
        
        return false;
    }

    /**
     * 尝试组成顺子
     */
    private static boolean tryFormShunzi(Map<TileType, List<Tile>> groupedTiles,
                                         TileType type, Tile tile,
                                         int goldCount) {
        List<Tile> tiles = groupedTiles.get(type);
        int value = tile.getValue();
        
        // 字牌不能组顺子
        if (type == TileType.WIND || type == TileType.DRAGON) {
            return false;
        }
        
        // 检查是否有连续的三张（可以用金代替）
        int[] needs = new int[3]; // 需要value, value+1, value+2
        for (int i = 0; i < 3; i++) {
            needs[i] = countTilesByValue(tiles, value + i);
        }
        
        int totalNeeded = 3;
        int totalHave = needs[0] + needs[1] + needs[2];
        int goldNeeded = Math.max(0, totalNeeded - totalHave);
        
        if (goldNeeded <= goldCount && value + 2 <= 9) {
            // 尝试移除这个顺子
            List<Tile> removed = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (needs[i] > 0) {
                    Tile toRemove = findTileByValue(tiles, value + i);
                    if (toRemove != null) {
                        tiles.remove(toRemove);
                        removed.add(toRemove);
                    }
                }
            }
            
            if (checkMeldsRecursive(groupedTiles, goldCount - goldNeeded)) {
                return true;
            }
            
            // 回溯
            tiles.addAll(removed);
        }
        
        return false;
    }

    // === 工具方法 ===

    private static Map<String, Integer> buildTileCountMap(List<Tile> tiles) {
        Map<String, Integer> map = new HashMap<>();
        for (Tile tile : tiles) {
            String key = getTileKey(tile);
            map.put(key, map.getOrDefault(key, 0) + 1);
        }
        return map;
    }

    private static String getTileKey(Tile tile) {
        return tile.getType() + "_" + tile.getValue();
    }

    private static Tile tileFromKey(String key) {
        // key: TYPE_value
        String[] parts = key.split("_");
        TileType type = TileType.valueOf(parts[0]);
        int value = Integer.parseInt(parts[1]);
        return new Tile(type, value, key);
    }

    private static Map<TileType, List<Tile>> groupByType(List<Tile> tiles) {
        Map<TileType, List<Tile>> map = new HashMap<>();
        for (Tile tile : tiles) {
            map.computeIfAbsent(tile.getType(), k -> new ArrayList<>()).add(tile);
        }
        return map;
    }

    private static int removeTiles(List<Tile> tiles, Tile target, int count) {
        int removed = 0;
        Iterator<Tile> iterator = tiles.iterator();
        while (iterator.hasNext() && removed < count) {
            Tile tile = iterator.next();
            if (tile.isSameAs(target)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static int removeTilesByKey(List<Tile> tiles, String key, int count) {
        Tile target = tileFromKey(key);
        return removeTiles(tiles, target, count);
    }

    private static List<Tile> removeAllGoldTiles(List<Tile> tiles, Tile goldTile) {
        if (goldTile == null) {
            return new ArrayList<>(tiles);
        }
        List<Tile> result = new ArrayList<>();
        for (Tile t : tiles) {
            if (!t.isSameAs(goldTile)) {
                result.add(t);
            }
        }
        return result;
    }

    private static int countSameTiles(List<Tile> tiles, Tile target) {
        return (int) tiles.stream().filter(t -> t.isSameAs(target)).count();
    }

    private static List<Tile> removeSameTiles(List<Tile> tiles, Tile target, int count) {
        List<Tile> removed = new ArrayList<>();
        Iterator<Tile> iterator = tiles.iterator();
        while (iterator.hasNext() && removed.size() < count) {
            Tile tile = iterator.next();
            if (tile.isSameAs(target)) {
                iterator.remove();
                removed.add(tile);
            }
        }
        return removed;
    }

    private static int countTilesByValue(List<Tile> tiles, int value) {
        return (int) tiles.stream().filter(t -> t.getValue() == value).count();
    }

    private static Tile findTileByValue(List<Tile> tiles, int value) {
        return tiles.stream().filter(t -> t.getValue() == value).findFirst().orElse(null);
    }
}
