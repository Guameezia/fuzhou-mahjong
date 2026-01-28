package com.fzmahjong.engine;

import com.fzmahjong.model.Player;
import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;

import java.util.*;

/**
 * 操作检查器 - 检查玩家是否可以吃、碰、杠、胡
 */
public class ActionChecker {
    
    /**
     * 检查玩家是否可以吃牌
     * 吃：只能吃上家打出的牌，且必须是顺子（只有万条饼可以吃）
     */
    public static boolean canChi(Player player, Tile discardedTile, int discardPlayerIndex, int playerIndex, Tile goldTile) {
        // 只能吃上家的牌
        int previousPlayerIndex = (playerIndex + 3) % 4;
        if (discardPlayerIndex != previousPlayerIndex) {
            return false;
        }

        // 金牌不能参与吃：既不能吃金牌本身，也不能用手中的金牌去吃
        if (goldTile != null && discardedTile != null && discardedTile.isSameAs(goldTile)) {
            return false;
        }
        
        // 只有万条饼可以吃
        if (discardedTile.getType() == TileType.WIND || discardedTile.getType() == TileType.DRAGON) {
            return false;
        }
        
        // 手牌中的金牌不参与吃牌判断
        List<Tile> handTiles = new ArrayList<>();
        for (Tile tile : player.getHandTiles()) {
            if (goldTile != null && tile.isSameAs(goldTile)) {
                continue;
            }
            handTiles.add(tile);
        }
        int value = discardedTile.getValue();
        TileType type = discardedTile.getType();
        
        // 检查是否可以组成顺子
        // 例如：手牌有1万2万，可以吃3万；手牌有2万3万，可以吃1万或4万；手牌有3万4万，可以吃2万或5万
        boolean canChiLeft = false;  // 可以组成 x, x+1, x+2
        boolean canChiMiddle = false; // 可以组成 x-1, x, x+1
        boolean canChiRight = false;  // 可以组成 x-2, x-1, x
        
        // 检查左吃（需要手牌有 value-2, value-1）
        if (value >= 3) {
            boolean hasValueMinus2 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value - 2);
            boolean hasValueMinus1 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value - 1);
            canChiLeft = hasValueMinus2 && hasValueMinus1;
        }
        
        // 检查中吃（需要手牌有 value-1, value+1）
        if (value >= 2 && value <= 8) {
            boolean hasValueMinus1 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value - 1);
            boolean hasValuePlus1 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value + 1);
            canChiMiddle = hasValueMinus1 && hasValuePlus1;
        }
        
        // 检查右吃（需要手牌有 value+1, value+2）
        if (value <= 7) {
            boolean hasValuePlus1 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value + 1);
            boolean hasValuePlus2 = handTiles.stream().anyMatch(t -> 
                t.getType() == type && t.getValue() == value + 2);
            canChiRight = hasValuePlus1 && hasValuePlus2;
        }
        
        return canChiLeft || canChiMiddle || canChiRight;
    }
    
    /**
     * 检查玩家是否可以碰牌
     * 碰：手牌中有2张相同的牌
     */
    public static boolean canPeng(Player player, Tile discardedTile) {
        List<Tile> handTiles = player.getHandTiles();
        long count = handTiles.stream()
            .filter(t -> t.isSameAs(discardedTile))
            .count();
        return count >= 2;
    }
    
    /**
     * 检查玩家是否可以杠牌（明杠）
     * 明杠：手牌中有3张相同的牌，可以杠别人打出的牌
     */
    public static boolean canGang(Player player, Tile discardedTile) {
        List<Tile> handTiles = player.getHandTiles();
        long count = handTiles.stream()
            .filter(t -> t.isSameAs(discardedTile))
            .count();
        return count >= 3;
    }
    
    /**
     * 检查玩家是否可以暗杠
     * 暗杠：手牌中有4张相同的牌
     */
    public static List<Tile> canAnGang(Player player) {
        List<Tile> handTiles = new ArrayList<>(player.getHandTiles());
        Map<String, Integer> tileCount = new HashMap<>();
        
        // 统计每种牌的数量
        for (Tile tile : handTiles) {
            String key = tile.getType().name() + "_" + tile.getValue();
            tileCount.put(key, tileCount.getOrDefault(key, 0) + 1);
        }
        
        // 找出有4张的牌
        List<Tile> anGangTiles = new ArrayList<>();
        for (Tile tile : handTiles) {
            String key = tile.getType().name() + "_" + tile.getValue();
            if (tileCount.get(key) == 4) {
                // 避免重复添加
                boolean alreadyAdded = anGangTiles.stream()
                    .anyMatch(t -> t.isSameAs(tile));
                if (!alreadyAdded) {
                    anGangTiles.add(tile);
                }
            }
        }
        
        return anGangTiles;
    }
    
    /**
     * 检查玩家是否可以胡牌
     */
    public static boolean canHu(Player player, Tile tile, Tile goldTile, boolean isQiangJin) {
        List<Tile> handTiles = new ArrayList<>(player.getHandTiles());
        if (tile != null) {
            handTiles.add(tile);
        }
        return WinValidator.canWin(handTiles, goldTile, isQiangJin);
    }

    /**
     * 计算听牌：基于当前暗牌（花牌不参与），返回“摸到/别人打出哪张牌可以胡”的候选列表。
     *
     * 福州麻将（16 张体系）：
     * - 目标胡牌暗牌总数（点炮/自摸）为 17（5 副面子 + 1 对将）。
     * - 因为可能有吃碰杠，暗牌数量会出现 16、13、10、7、4、1（每次吃/碰/杠减少 3）。
     * - 听牌的判定就是：当前暗牌数 N 加 1 张候选牌后，满足「3n + 2」结构。
     *   等价于：(N + 1) % 3 == 2  =>  N % 3 == 1
     */
    public static List<Tile> getTingTiles(Player player, Tile goldTile) {
        if (player == null) {
            return Collections.emptyList();
        }

        // 过滤花牌（FLOWER），花牌不参与张数/和牌判断
        List<Tile> baseTiles = new ArrayList<>();
        for (Tile t : player.getHandTiles()) {
            if (t != null && t.getType() != TileType.FLOWER) {
                baseTiles.add(t);
            }
        }

        // N 必须满足 N % 3 == 1（例如 16、13、10、7、4、1），才可能“再进一张就胡”
        if (baseTiles.isEmpty() || baseTiles.size() > 16 || (baseTiles.size() % 3) != 1) {
            return Collections.emptyList();
        }

        List<Tile> candidates = buildAllCandidateTiles();
        List<Tile> tingTiles = new ArrayList<>();

        for (Tile candidate : candidates) {
            List<Tile> test = new ArrayList<>(baseTiles);
            // 这里 candidate 的 id 无关紧要；WinValidator 判断只看 type/value
            test.add(new Tile(candidate.getType(), candidate.getValue(), candidate.getType().name() + "_" + candidate.getValue()));
            if (WinValidator.canWin(test, goldTile, false)) {
                tingTiles.add(candidate);
            }
        }

        Collections.sort(tingTiles);
        return tingTiles;
    }

    private static List<Tile> buildAllCandidateTiles() {
        List<Tile> result = new ArrayList<>();

        // 万/条/饼：1-9
        for (int v = 1; v <= 9; v++) {
            result.add(new Tile(TileType.WAN, v, "WAN_" + v));
            result.add(new Tile(TileType.TIAO, v, "TIAO_" + v));
            result.add(new Tile(TileType.BING, v, "BING_" + v));
        }

        // 风牌：东南西北（1-4）
        for (int v = 1; v <= 4; v++) {
            result.add(new Tile(TileType.WIND, v, "WIND_" + v));
        }

        // 箭牌：中白发（1-3）
        for (int v = 1; v <= 3; v++) {
            result.add(new Tile(TileType.DRAGON, v, "DRAGON_" + v));
        }

        return result;
    }
}
