package com.fzmahjong.engine;

import com.fzmahjong.model.Tile;
import com.fzmahjong.model.TileType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 第一局预设牌序（仅用于测试特殊胡牌）。
 * 当 {@link #WALL_ORDER} 非空且长度为 144 时，第一局使用该顺序作为牌墙；
 * 第二局起恢复随机分牌。
 * <p>
 * 牌码格式（可在代码中随意修改本列表）：
 * <ul>
 *   <li>万：1W～9W</li>
 *   <li>条：1T～9T</li>
 *   <li>饼：1B～9B</li>
 *   <li>风：E=东 S=南 W=西 N=北</li>
 *   <li>字：Z=中 P=白 F=发</li>
 *   <li>花：H1～H8（春夏秋冬梅兰竹菊）</li>
 * </ul>
 * 牌序含义：前 65 张按发牌顺序被摸走（庄4×4+下家4×4+对家4×4+上家4×4 + 庄再1张），
 * 第 66～144 张为牌墙（牌尾最后一张为金牌）。
 */
public final class FirstHandPreset {

    /**
     * 第一局牌墙顺序，共 144 张。
     * 设为 null 或 长度非 144 则第一局也随机；设为 144 条牌码则第一局严格按此顺序。
     */
    public static List<String> WALL_ORDER = Arrays.asList(
"1W","1W","1W","1W","2W","2W","2W","2W","3W","3W","3W","3W","4W","4W","4W","4W","5W","5W","5W","5W","6W","6W","6W","6W","7W","7W","7W","7W","8W","8W","8W","8W","9W","9W","9W","9W",    
        "1T","1T","1T","1T","2T","2T","2T","2T","3T","3T","3T","3T","4T","4T","4T","4T","5T","5T","5T","5T","6T","6T","6T","6T","7T","7T","7T","7T","8T","8T","8T","8T","9T","9T","9T","9T",
        "1B","1B","1B","1B","2B","2B","2B","2B","3B","3B","3B","3B","4B","4B","4B","4B","5B","5B","5B","5B","6B","6B","6B","6B","7B","7B","7B","7B","8B","8B","8B","8B","9B","9B","9B","9B",

"E","E","E","E","S","S","S","S","W","W","W","W","N","N","N","N","Z","Z","Z","Z","P","P","P","P","F","F","F","F","H1","H2","H3","H4","H5","H6","H7","H8"
    );
    // ========== 以下为示例：可复制一份改为 WALL_ORDER = Arrays.asList(...) 做测试 ==========
    // 示例：庄家天胡（庄家 17 张已听牌，且第一张摸到的就是金/所需牌）。需要你按发牌顺序排好前 65 张+牌墙。
    // 发牌顺序：庄(0) 下(1) 对(2) 上(3) 各 4 张 × 4 轮 = 64 张，再庄 1 张 = 65 张；索引 0～64 为已发，65～143 为墙。
    // private static final List<String> EXAMPLE = Arrays.asList(
    //     "1W","1W","1W","2W","2W","2W","3W","3W","3W","4W","4W","4W","5W","5W","5W", ...  // 前 65 张
    //     "5W", ...  // 牌墙 66～143，最后一张为金牌
    // );

    private FirstHandPreset() {
    }

    /**
     * 是否启用了预设（非 null 且恰好 144 张）。
     */
    public static boolean hasPreset() {
        return WALL_ORDER != null && WALL_ORDER.size() == 144;
    }

    /**
     * 根据当前 {@link #WALL_ORDER} 构建牌墙列表；无效时返回 null。
     */
    public static List<Tile> buildWall() {
        if (!hasPreset()) {
            return null;
        }
        List<Tile> wall = new ArrayList<>(144);
        for (int i = 0; i < 144; i++) {
            Tile t = parseTileCode(WALL_ORDER.get(i));
            if (t == null) {
                return null;
            }
            wall.add(t);
        }
        return wall;
    }

    /**
     * 解析单张牌码为 Tile。
     * 万 1W-9W，条 1T-9T，饼 1B-9B，风 E/S/W/N，字 Z/P/F，花 H1-H8。
     */
    public static Tile parseTileCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        String s = code.trim().toUpperCase();
        if (s.length() == 1) {
            switch (s) {
                case "E": return new Tile(TileType.WIND, 1);   // 东
                case "S": return new Tile(TileType.WIND, 2);   // 南
                case "W": return new Tile(TileType.WIND, 3);   // 西
                case "N": return new Tile(TileType.WIND, 4);   // 北
                case "Z": return new Tile(TileType.DRAGON, 1); // 中
                case "P": return new Tile(TileType.DRAGON, 2); // 白
                case "F": return new Tile(TileType.DRAGON, 3); // 发
                default: return null;
            }
        }
        if (s.length() == 2) {
            char first = s.charAt(0);
            char second = s.charAt(1);
            if (first >= '1' && first <= '9') {
                int val = first - '0';
                switch (second) {
                    case 'W': return new Tile(TileType.WAN, val);
                    case 'T': return new Tile(TileType.TIAO, val);
                    case 'B': return new Tile(TileType.BING, val);
                    case 'H': return (val >= 1 && val <= 8) ? new Tile(TileType.FLOWER, val) : null;
                    default: return null;
                }
            }
            // 花牌 "H1"～"H8"（与文档、WALL_ORDER、createStandardOrderCodes 一致）
            if (first == 'H' && second >= '1' && second <= '8') {
                return new Tile(TileType.FLOWER, second - '0');
            }
        }
        return null;
    }

    /**
     * 生成一副 144 张牌的“标准顺序”牌码列表（未洗牌），便于你复制后手工调整成想要的预设。
     * 仅作参考，不参与游戏逻辑。
     */
    public static List<String> createStandardOrderCodes() {
        List<String> list = new ArrayList<>(144);
        for (int v = 1; v <= 9; v++) {
            for (int c = 0; c < 4; c++) {
                list.add(String.valueOf(v) + "W");
            }
        }
        for (int v = 1; v <= 9; v++) {
            for (int c = 0; c < 4; c++) {
                list.add(String.valueOf(v) + "T");
            }
        }
        for (int v = 1; v <= 9; v++) {
            for (int c = 0; c < 4; c++) {
                list.add(String.valueOf(v) + "B");
            }
        }
        for (int c = 0; c < 4; c++) list.add("E");
        for (int c = 0; c < 4; c++) list.add("S");
        for (int c = 0; c < 4; c++) list.add("W");
        for (int c = 0; c < 4; c++) list.add("N");
        for (int c = 0; c < 4; c++) list.add("Z");
        for (int c = 0; c < 4; c++) list.add("P");
        for (int c = 0; c < 4; c++) list.add("F");
        for (int v = 1; v <= 8; v++) list.add("H" + v);
        return list;
    }
}
