package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 牌工厂测试
 */
class TileFactoryTest {

    @Test
    void testCreateFullDeck() {
        List<Tile> tiles = TileFactory.createFullDeck();
        
        // 检查总数（144张）
        assertEquals(144, tiles.size(), "牌总数应该是144张");
        
        // 检查万、条、饼各36张
        long wanCount = tiles.stream().filter(t -> t.getType().name().equals("WAN")).count();
        long tiaoCount = tiles.stream().filter(t -> t.getType().name().equals("TIAO")).count();
        long bingCount = tiles.stream().filter(t -> t.getType().name().equals("BING")).count();
        
        assertEquals(36, wanCount, "万牌应该有36张");
        assertEquals(36, tiaoCount, "条牌应该有36张");
        assertEquals(36, bingCount, "饼牌应该有36张");
        
        // 检查风牌（东南西北各4张，共16张）
        long windCount = tiles.stream().filter(t -> t.getType().name().equals("WIND")).count();
        assertEquals(16, windCount, "风牌应该有16张");
        
        // 检查中（4张）
        long dragonCount = tiles.stream().filter(t -> t.getType().name().equals("DRAGON")).count();
        assertEquals(4, dragonCount, "中牌应该有4张");
    }

    @Test
    void testShuffle() {
        List<Tile> tiles1 = TileFactory.createFullDeck();
        List<Tile> tiles2 = TileFactory.createFullDeck();
        
        TileFactory.shuffle(tiles1);
        
        // 洗牌后总数不变
        assertEquals(144, tiles1.size());
        
        // 洗牌后顺序应该改变（虽然有极小概率相同）
        boolean isDifferent = false;
        for (int i = 0; i < Math.min(10, tiles1.size()); i++) {
            if (!tiles1.get(i).isSameAs(tiles2.get(i))) {
                isDifferent = true;
                break;
            }
        }
        assertTrue(isDifferent, "洗牌后顺序应该改变");
    }
}
