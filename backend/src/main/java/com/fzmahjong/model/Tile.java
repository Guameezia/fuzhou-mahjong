package com.fzmahjong.model;

/**
 * 麻将牌
 */
public class Tile implements Comparable<Tile> {
    private TileType type;      // 牌类型（万、条、饼、字）
    private int value;          // 牌值（1-9 或字牌编号）
    private String id;          // 唯一标识（用于区分同样的牌）

    public Tile() {
    }

    public Tile(TileType type, int value, String id) {
        this.type = type;
        this.value = value;
        this.id = id;
    }

    public Tile(TileType type, int value) {
        this.type = type;
        this.value = value;
        this.id = type.name() + value + "_" + System.nanoTime();
    }

    public TileType getType() {
        return type;
    }

    public void setType(TileType type) {
        this.type = type;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * 是否为花牌（东南西北中发白、春夏秋冬梅兰竹菊）
     */
    public boolean isFlowerTile() {
        return type == TileType.WIND || type == TileType.DRAGON || type == TileType.FLOWER;
    }

    /**
     * 显示名称
     */
    public String getDisplayName() {
        switch (type) {
            case WAN:
                return value + "万";
            case TIAO:
                return value + "条";
            case BING:
                return value + "饼";
            case WIND:
                return new String[]{"", "东", "南", "西", "北"}[value];
            case DRAGON:
                String[] dragonNames = {"", "中", "白", "发"};
                if (value >= 1 && value <= 3) {
                    return dragonNames[value];
                }
                return "字" + value;
            case FLOWER:
                String[] flowerNames = {"", "春", "夏", "秋", "冬", "梅", "兰", "竹", "菊"};
                if (value >= 1 && value <= 8) {
                    return flowerNames[value];
                }
                return "花" + value;
            default:
                return "未知";
        }
    }

    /**
     * 排序：先按类型，再按数值
     */
    @Override
    public int compareTo(Tile other) {
        if (this.type != other.type) {
            return this.type.ordinal() - other.type.ordinal();
        }
        return this.value - other.value;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    /**
     * 判断两张牌是否相同（不考虑ID）
     */
    public boolean isSameAs(Tile other) {
        return this.type == other.type && this.value == other.value;
    }
}
