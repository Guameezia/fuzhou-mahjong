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
     * 3. 抢金：开金后抓到金就和（不看具体牌型）
     * 4. 金将 / 金雀 / 金作面子：金牌视作“万能牌（癞子）”，可补任意牌
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

        // 将所有非金牌转换为“计数数组”表示，金牌只记录数量
        int[][] counts = new int[5][10]; // 0:万,1:条,2:饼,3:风,4:箭; 点数用1..9
        for (Tile tile : validTiles) {
            if (goldTile != null && tile.isSameAs(goldTile)) {
                continue;
            }
            int typeIndex = mapTypeIndex(tile.getType());
            int value = tile.getValue();
            if (value >= 1 && value <= 9 && typeIndex >= 0) {
                counts[typeIndex][value]++;
            }
        }

        int totalTileCount = validTiles.size();

        // 张数约束：暗牌（+点炮牌）总数必须满足 3n+2，且不超过 17 张
        if (totalTileCount < 2 || totalTileCount > 17 || (totalTileCount % 3) != 2) {
            return false;
        }

        // 使用“计数 + 金牌 DFS”进行标准胡牌判断（平和 / 金雀 / 金将 / 金作面子）
        return canWinWithCounts(counts, goldCount);
    }

    /**
     * 计数版胡牌判断入口：
     * - 先枚举“将”的用法（2 普通 / 1 普通+1 金 / 2 金）
     * - 剩余牌全部拆成面子（AAA/ABC），缺的用金补
     */
    private static boolean canWinWithCounts(int[][] counts, int goldCount) {
        // 情况一：2 张金直接做将
        if (goldCount >= 2) {
            if (allMelds(counts, goldCount - 2)) {
                log.info("金将和牌！");
                return true;
            }
        }

        // 情况二：普通牌相关的将（两张普通 / 一张普通+一张金）
        for (int type = 0; type < counts.length; type++) {
            for (int value = 1; value <= 9; value++) {
                int c = counts[type][value];
                if (c == 0) {
                    continue;
                }

                // 1) 两张普通牌做将
                if (c >= 2) {
                    counts[type][value] -= 2;
                    if (allMelds(counts, goldCount)) {
                        log.info("平和！");
                        counts[type][value] += 2; // 回溯前先恢复
                        return true;
                    }
                    counts[type][value] += 2;
                }

                // 2) 一张普通牌 + 一张金做将（金雀）
                if (c >= 1 && goldCount >= 1) {
                    counts[type][value] -= 1;
                    if (allMelds(counts, goldCount - 1)) {
                        log.info("金雀和牌！");
                        counts[type][value] += 1;
                        return true;
                    }
                    counts[type][value] += 1;
                }
            }
        }

        return false;
    }

    /**
     * 检查“所有剩余牌 + 剩余金”能否全部拆成面子（AAA/ABC）
     */
    private static boolean allMelds(int[][] counts, int goldCount) {
        // 找到当前还存在的最小一张牌
        int type = -1;
        int value = -1;
        outer:
        for (int t = 0; t < counts.length; t++) {
            for (int v = 1; v <= 9; v++) {
                if (counts[t][v] > 0) {
                    type = t;
                    value = v;
                    break outer;
                }
            }
        }

        // 已经没有普通牌了：金必须也用完
        if (type == -1) {
            return goldCount == 0;
        }

        // 尝试一：以 (type, value) 组刻子 AAA（可以用金补）
        int have = counts[type][value];
        int needForKe = Math.max(0, 3 - have);
        if (needForKe <= goldCount && have > 0) {
            int used = Math.min(3, have);
            counts[type][value] -= used;
            if (allMelds(counts, goldCount - needForKe)) {
                counts[type][value] += used;
                return true;
            }
            counts[type][value] += used;
        }

        // 尝试二：如果是万/条/饼，再尝试顺子 ABC（可以用金补）
        if (isShunziType(type) && value <= 7) {
            int have0 = counts[type][value];
            int have1 = counts[type][value + 1];
            int have2 = counts[type][value + 2];

            int totalHave = have0 + have1 + have2;
            int needForShun = Math.max(0, 3 - totalHave);

            if (needForShun <= goldCount && value + 2 <= 9) {
                // 扣除一组顺子里真实存在的牌（每个位置最多扣 1）
                int use0 = Math.min(1, have0);
                int use1 = Math.min(1, have1);
                int use2 = Math.min(1, have2);

                counts[type][value]     -= use0;
                counts[type][value + 1] -= use1;
                counts[type][value + 2] -= use2;

                if (allMelds(counts, goldCount - needForShun)) {
                    counts[type][value]     += use0;
                    counts[type][value + 1] += use1;
                    counts[type][value + 2] += use2;
                    return true;
                }

                // 回溯
                counts[type][value]     += use0;
                counts[type][value + 1] += use1;
                counts[type][value + 2] += use2;
            }
        }

        // 无法通过任何一种拆分
        return false;
    }

    // === 工具方法 ===

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
     * 将 TileType 映射到内部计数数组的下标
     */
    private static int mapTypeIndex(TileType type) {
        switch (type) {
            case WAN:
                return 0;
            case TIAO:
                return 1;
            case BING:
                return 2;
            case WIND:
                return 3;
            case DRAGON:
                return 4;
            default:
                return -1;
        }
    }

    /**
     * 是否是可以组成顺子的花色（万/条/饼）
     */
    private static boolean isShunziType(int typeIndex) {
        return typeIndex == 0 || typeIndex == 1 || typeIndex == 2;
    }
}
