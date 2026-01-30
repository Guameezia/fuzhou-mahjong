package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;
import java.util.*;

/**
 * 和牌判断器。仅做“能否和牌”的纯逻辑判断，不输出日志；
 * 听牌计算会多次调用本类，实际胡牌时的类型与日志由 GameEngine 负责。
 */
public class WinValidator {

    /**
     * 检查是否可以和牌
     * 福州麻将和牌规则：
     * 1. 平和：标准麻将胡牌形式（n个顺子/刻子 + 1对将）
     * 2. 金将：2张金做将，其他组成面子
     * 3. 金雀：1张金做将，其他组成面子
     * 4. 金作面子：金牌视作“万能牌（癞子）”，可补任意牌   
     * 5. 抢金：开金后抓到金就和
     * 6. 三头金：手里有3张金
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

        // 统计金牌数量（使用过滤后的牌）
        int goldCount = countGoldTiles(validTiles, goldTile);

        // 三头金（3张金）
        if (goldCount == 3) {
            return true;
        }

        // 抢金判断（开金后：满足“16 张时进一张金即可和”）
        // 注意：三头金优先级更高，因此必须在三头金之后判断
        if (isQiangJin) {
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
                        counts[type][value] += 2; // 回溯前先恢复
                        return true;
                    }
                    counts[type][value] += 2;
                }

                // 2) 一张普通牌 + 一张金做将（金雀）
                if (c >= 1 && goldCount >= 1) {
                    counts[type][value] -= 1;
                    if (allMelds(counts, goldCount - 1)) {
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
        //
        // 这里需要特别考虑一种情况：
        //   实际最小的那张牌是 8条，但牌型想要的是 7-8-9 条，其中 7 条由金牌代替。
        //   旧逻辑只从 (v, v+1, v+2) 这种“以当前最小牌为首”的顺子出发，
        //   因为没有真实的 7 条，就永远不会尝试 7-8-9 这种“缺最小一张、用金补”的顺子，
        //   导致进张（听牌）时漏掉了一些依赖“左补金”的顺子形态。
        //
        // 为了覆盖所有可能的顺子组合，并且仍然保持“每次递归都会消耗当前最小牌”以保证收敛，
        // 我们对当前最小牌 (type, value) 同时尝试三种顺子形态：
        //   1. [value,   value+1, value+2]  —— 当前牌作为首张（原有逻辑）
        //   2. [value-1, value,   value+1]  —— 当前牌作为中张
        //   3. [value-2, value-1, value]    —— 当前牌作为末张
        //
        // 这三种形态都包含当前最小牌本身，因此不会破坏 DFS 的有序性和终止性。
        if (isShunziType(type)) {
            // 三种候选形态的起始点与偏移
            int[][] patterns = new int[][]{
                    // 当前值作为首张：value, value+1, value+2
                    {value, 0},
                    // 当前值作为中张：value-1, value, value+1
                    {value - 1, 0},
                    // 当前值作为末张：value-2, value-1, value
                    {value - 2, 0}
            };

            for (int[] p : patterns) {
                int start = p[0];
                // 起始点必须在 1..7 之间，才能保证 [start, start+1, start+2] 落在 1..9 里
                if (start < 1 || start > 7) {
                    continue;
                }

                int v0 = start;
                int v1 = start + 1;
                int v2 = start + 2;

                // 当前递归的“最小牌”必须在此顺子形态中，否则可能跳过它导致死循环
                if (value != v0 && value != v1 && value != v2) {
                    continue;
                }

                int have0 = counts[type][v0];
                int have1 = counts[type][v1];
                int have2 = counts[type][v2];

                // 顺子必须是 (v0, v1, v2) 各 1 张；缺哪个位置就用金补哪个位置。
                int needForShun = 0;
                if (have0 <= 0) needForShun++;
                if (have1 <= 0) needForShun++;
                if (have2 <= 0) needForShun++;

                if (needForShun <= goldCount) {
                    // 扣除一组顺子里真实存在的牌（每个位置最多扣 1）
                    int use0 = Math.min(1, have0);
                    int use1 = Math.min(1, have1);
                    int use2 = Math.min(1, have2);

                    counts[type][v0] -= use0;
                    counts[type][v1] -= use1;
                    counts[type][v2] -= use2;

                    if (allMelds(counts, goldCount - needForShun)) {
                        counts[type][v0] += use0;
                        counts[type][v1] += use1;
                        counts[type][v2] += use2;
                        return true;
                    }

                    // 回溯
                    counts[type][v0] += use0;
                    counts[type][v1] += use1;
                    counts[type][v2] += use2;
                }
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
