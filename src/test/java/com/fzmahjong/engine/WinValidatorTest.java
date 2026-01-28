package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class WinValidatorTest {

    @Test
    void goldAsJoker_canCompleteKezi() {
        // 金牌：七条（作为万能牌）
        Tile goldTile = new Tile(TileType.TIAO, 7, "gold_7t");

        // 牌型（14张，仍满足 3n+2 结构）：
        // 123万 / 456万 / 789万 / 33饼 + 金(当3饼) / 22饼(将)
        List<Tile> hand = new ArrayList<>();
        hand.add(new Tile(TileType.WAN, 1, "w1"));
        hand.add(new Tile(TileType.WAN, 2, "w2"));
        hand.add(new Tile(TileType.WAN, 3, "w3"));

        hand.add(new Tile(TileType.WAN, 4, "w4"));
        hand.add(new Tile(TileType.WAN, 5, "w5"));
        hand.add(new Tile(TileType.WAN, 6, "w6"));

        hand.add(new Tile(TileType.WAN, 7, "w7"));
        hand.add(new Tile(TileType.WAN, 8, "w8"));
        hand.add(new Tile(TileType.WAN, 9, "w9"));

        hand.add(new Tile(TileType.BING, 3, "p3a"));
        hand.add(new Tile(TileType.BING, 3, "p3b"));

        // 一张金（七条），当作第三张3饼来组成刻子
        hand.add(new Tile(TileType.TIAO, 7, "gold_in_hand"));

        hand.add(new Tile(TileType.BING, 2, "p2a"));
        hand.add(new Tile(TileType.BING, 2, "p2b"));

        assertTrue(WinValidator.canWin(hand, goldTile, false));
    }

    @Test
    void fuzhou17Tiles_canWin() {
        // 福州 16 张体系：胡牌结构为 5 副面子 + 1 对将 = 17 张
        Tile goldTile = new Tile(TileType.TIAO, 7, "gold_7t");

        // 111万 / 222万 / 333万 / 444万 / 555万 + 66饼(将) + 金(当6饼)
        // 实际：将用 6饼 + 金（当6饼）
        List<Tile> hand = new ArrayList<>();
        // 111万
        hand.add(new Tile(TileType.WAN, 1, "w1a"));
        hand.add(new Tile(TileType.WAN, 1, "w1b"));
        hand.add(new Tile(TileType.WAN, 1, "w1c"));
        // 222万
        hand.add(new Tile(TileType.WAN, 2, "w2a"));
        hand.add(new Tile(TileType.WAN, 2, "w2b"));
        hand.add(new Tile(TileType.WAN, 2, "w2c"));
        // 333万
        hand.add(new Tile(TileType.WAN, 3, "w3a"));
        hand.add(new Tile(TileType.WAN, 3, "w3b"));
        hand.add(new Tile(TileType.WAN, 3, "w3c"));
        // 444万
        hand.add(new Tile(TileType.WAN, 4, "w4a"));
        hand.add(new Tile(TileType.WAN, 4, "w4b"));
        hand.add(new Tile(TileType.WAN, 4, "w4c"));
        // 555万
        hand.add(new Tile(TileType.WAN, 5, "w5a"));
        hand.add(new Tile(TileType.WAN, 5, "w5b"));
        hand.add(new Tile(TileType.WAN, 5, "w5c"));
        // 6饼 + 金（将）
        hand.add(new Tile(TileType.BING, 6, "p6a"));
        hand.add(new Tile(TileType.TIAO, 7, "gold_in_hand"));

        assertTrue(WinValidator.canWin(hand, goldTile, false));
    }

    @Test
    void dianPao_canWinWithAddedDiscard() {
        // 点炮：手里 16 张，别人打出一张，凑成 17 张后可胡
        Tile goldTile = new Tile(TileType.TIAO, 7, "gold_7t");

        // 111万 / 222万 / 333万 / 444万 / 555万 + 6饼（单张） + 金（当6饼） => 16 张
        // 点炮再来一张 6饼，完成将（6饼 + 金）
        List<Tile> base16 = new ArrayList<>();
        // 111万
        base16.add(new Tile(TileType.WAN, 1, "w1a"));
        base16.add(new Tile(TileType.WAN, 1, "w1b"));
        base16.add(new Tile(TileType.WAN, 1, "w1c"));
        // 222万
        base16.add(new Tile(TileType.WAN, 2, "w2a"));
        base16.add(new Tile(TileType.WAN, 2, "w2b"));
        base16.add(new Tile(TileType.WAN, 2, "w2c"));
        // 333万
        base16.add(new Tile(TileType.WAN, 3, "w3a"));
        base16.add(new Tile(TileType.WAN, 3, "w3b"));
        base16.add(new Tile(TileType.WAN, 3, "w3c"));
        // 444万
        base16.add(new Tile(TileType.WAN, 4, "w4a"));
        base16.add(new Tile(TileType.WAN, 4, "w4b"));
        base16.add(new Tile(TileType.WAN, 4, "w4c"));
        // 555万
        base16.add(new Tile(TileType.WAN, 5, "w5a"));
        base16.add(new Tile(TileType.WAN, 5, "w5b"));
        base16.add(new Tile(TileType.WAN, 5, "w5c"));
        // 单张 6饼 + 金（癞子）
        base16.add(new Tile(TileType.BING, 6, "p6a"));
        base16.add(new Tile(TileType.TIAO, 7, "gold_in_hand"));

        List<Tile> withDiscard = new ArrayList<>(base16);
        withDiscard.add(new Tile(TileType.BING, 6, "p6_from_discard"));

        assertTrue(WinValidator.canWin(withDiscard, goldTile, false));
    }
}

