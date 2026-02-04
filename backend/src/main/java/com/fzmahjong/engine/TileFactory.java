package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 麻将牌工厂 - 创建和洗牌
 */
public class TileFactory {

    /**
     * 创建一副完整的福州麻将牌（144张）
     * 公式：3×4×9 + 4×7 + 8 = 108 + 28 + 8
     * - 万、条、饼：3 门 × 4 张 × 9 面 = 108 张
     * - 字牌（东南西北 + 中发白）：7 种 × 4 张 = 28 张
     * - 花牌（春夏秋冬梅兰竹菊）：8 张
     */
    public static List<Tile> createFullDeck() {
        List<Tile> tiles = new ArrayList<>();
        
        // 万、条、饼（1-9，每种4张）
        for (TileType type : new TileType[]{TileType.WAN, TileType.TIAO, TileType.BING}) {
            for (int value = 1; value <= 9; value++) {
                for (int count = 0; count < 4; count++) {
                    tiles.add(new Tile(type, value));
                }
            }
        }
        
        // 东南西北（1-4，每种4张）
        for (int value = 1; value <= 4; value++) {
            for (int count = 0; count < 4; count++) {
                tiles.add(new Tile(TileType.WIND, value));
            }
        }
        
        // 中发白（1=中，2=白，3=发，每种4张）
        for (int value = 1; value <= 3; value++) {
            for (int count = 0; count < 4; count++) {
                tiles.add(new Tile(TileType.DRAGON, value));
            }
        }
        
        // 花牌：春夏秋冬梅兰竹菊（1-8，每种1张，共8张）
        for (int value = 1; value <= 8; value++) {
            tiles.add(new Tile(TileType.FLOWER, value));
        }
        
        return tiles;
    }

    /**
     * 洗牌
     */
    public static void shuffle(List<Tile> tiles) {
        Collections.shuffle(tiles);
    }

    /**
     * 创建并洗好的牌墙
     */
    public static List<Tile> createAndShuffleWall() {
        List<Tile> tiles = createFullDeck();
        shuffle(tiles);
        return tiles;
    }
}
