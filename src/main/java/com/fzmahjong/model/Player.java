package com.fzmahjong.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家
 */
public class Player {
    private String id;                          // 玩家ID
    private String name;                        // 玩家名称
    private int position;                       // 位置（0-3）
    private List<Tile> handTiles;               // 手牌
    private List<List<Tile>> exposedMelds;      // 明牌（碰、杠、吃的牌）
    private List<Tile> flowerTiles;             // 补的花牌
    private int score;                          // 当前分数
    private boolean isDealer;                   // 是否是庄家

    public Player(String id, String name, int position) {
        this.id = id;
        this.name = name;
        this.position = position;
        this.handTiles = new ArrayList<>();
        this.exposedMelds = new ArrayList<>();
        this.flowerTiles = new ArrayList<>();
        this.score = 0;
        this.isDealer = false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public List<Tile> getHandTiles() {
        return handTiles;
    }

    public void setHandTiles(List<Tile> handTiles) {
        this.handTiles = handTiles;
    }

    public List<List<Tile>> getExposedMelds() {
        return exposedMelds;
    }

    public void setExposedMelds(List<List<Tile>> exposedMelds) {
        this.exposedMelds = exposedMelds;
    }

    public List<Tile> getFlowerTiles() {
        return flowerTiles;
    }

    public void setFlowerTiles(List<Tile> flowerTiles) {
        this.flowerTiles = flowerTiles;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public boolean isDealer() {
        return isDealer;
    }

    public void setDealer(boolean dealer) {
        isDealer = dealer;
    }

    /**
     * 开始新一局前重置与“本局”相关的数据
     * （分数/座位等不在此重置）
     */
    public void resetForNewHand() {
        handTiles.clear();
        exposedMelds.clear();
        flowerTiles.clear();
    }

    /**
     * 添加手牌
     */
    public void addTile(Tile tile) {
        handTiles.add(tile);
    }

    /**
     * 移除手牌
     */
    public boolean removeTile(Tile tile) {
        return handTiles.removeIf(t -> t.getId().equals(tile.getId()));
    }

    /**
     * 手牌排序
     * 金牌永远排在最左边，其他牌按正常规则排序
     * @param goldTile 金牌，如果为null则按正常排序
     */
    public void sortHand(Tile goldTile) {
        if (goldTile == null) {
            // 如果没有金牌，按正常排序
            Collections.sort(handTiles);
            return;
        }
        
        // 分离金牌和非金牌
        List<Tile> goldTiles = new ArrayList<>();
        List<Tile> otherTiles = new ArrayList<>();
        
        for (Tile tile : handTiles) {
            if (tile.isSameAs(goldTile)) {
                goldTiles.add(tile);
            } else {
                otherTiles.add(tile);
            }
        }
        
        // 非金牌按正常规则排序
        Collections.sort(otherTiles);
        
        // 重新组合：金牌在最左边，其他牌在后面
        handTiles.clear();
        handTiles.addAll(goldTiles);
        handTiles.addAll(otherTiles);
    }

    /**
     * 获取手牌数量
     */
    public int getHandSize() {
        return handTiles.size();
    }

    /**
     * 检查是否有花牌需要补
     */
    public List<Tile> getFlowerTilesInHand() {
        List<Tile> flowers = new ArrayList<>();
        for (Tile tile : handTiles) {
            if (tile.isFlowerTile()) {
                flowers.add(tile);
            }
        }
        return flowers;
    }

    /**
     * 补花（移除花牌到flowerTiles）
     */
    public void replaceFlowerTile(Tile flowerTile) {
        if (removeTile(flowerTile)) {
            flowerTiles.add(flowerTile);
        }
    }

    /**
     * 添加明牌组合
     */
    public void addExposedMeld(List<Tile> meld) {
        exposedMelds.add(new ArrayList<>(meld));
    }
}
