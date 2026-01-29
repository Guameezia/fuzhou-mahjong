package com.fzmahjong.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏状态
 */
public class GameState {
    private String roomId;                      // 房间ID
    private List<Player> players;               // 玩家列表（4人）
    private List<Tile> wallTiles;               // 牌墙（剩余的牌）
    private Tile goldTile;                      // 金牌
    private int currentPlayerIndex;             // 当前行动玩家索引
    private int dealerIndex;                    // 庄家索引
    private GamePhase phase;                    // 游戏阶段
    private Tile lastDiscardedTile;             // 最后打出的牌
    private int lastDiscardPlayerIndex;         // 最后打牌的玩家索引
    private int consecutiveDealerWins;          // 连庄次数
    private int dealerChangesSinceCycleStart;   // 从上次确认继续以来，庄家轮换次数（仅在“换庄”时+1）
    private int cycleStartDealerIndex;          // 本轮（用于“轮庄一圈”）起始庄家索引
    private Map<String, Boolean> continueDecisions; // 轮庄一圈后：每个玩家是否继续（null=未表态）
    private List<Tile> discardedTiles;          // 已打出的牌（牌池）
    private Map<String, Map<String, Object>> availableActions; // 每个玩家可用的操作（玩家ID -> 操作类型 -> 详情）
    private String currentActionPlayerId;        // 当前应该执行操作的玩家ID（按优先级）
    private String currentActionType;            // 当前优先级操作类型（"hu", "gang", "peng", "chi"）

    // === 抢金/开局相关状态 ===
    private boolean qiangJinWindowActive;        // 庄家首打后到庄家首次再摸牌前：抢金窗口有效
    private Tile lastDrawnTile;                  // 最近一次摸到/补到的最终有效牌（非花）
    private int lastDrawPlayerIndex;             // 最近一次摸牌的玩家索引
    private int lastDrawValidHandCountBefore;    // 最近一次摸牌前，该玩家“暗牌（不含花）”张数

    // === 胡牌结果记录 ===
    private String lastWinPlayerId;              // 最近一局胡牌玩家ID（流局则为null）
    private String lastWinType;                  // 最近一局胡牌类型（清一色/混一色/金龙/金雀/三金倒/无花无杠/天胡/抢金/一张花/自摸/胡）

    public GameState(String roomId) {
        this.roomId = roomId;
        this.players = new ArrayList<>();
        this.wallTiles = new ArrayList<>();
        this.currentPlayerIndex = 0;
        this.dealerIndex = 0;
        this.phase = GamePhase.WAITING;
        this.consecutiveDealerWins = 0;
        this.dealerChangesSinceCycleStart = 0;
        this.cycleStartDealerIndex = 0;
        this.continueDecisions = new HashMap<>();
        this.discardedTiles = new ArrayList<>();
        this.availableActions = new HashMap<>();
        this.currentActionPlayerId = null;
        this.currentActionType = null;
        this.qiangJinWindowActive = false;
        this.lastDrawnTile = null;
        this.lastDrawPlayerIndex = -1;
        this.lastDrawValidHandCountBefore = -1;
        this.lastWinPlayerId = null;
        this.lastWinType = null;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public void setPlayers(List<Player> players) {
        this.players = players;
    }

    public List<Tile> getWallTiles() {
        return wallTiles;
    }

    public void setWallTiles(List<Tile> wallTiles) {
        this.wallTiles = wallTiles;
    }

    public Tile getGoldTile() {
        return goldTile;
    }

    public void setGoldTile(Tile goldTile) {
        this.goldTile = goldTile;
    }

    public int getCurrentPlayerIndex() {
        return currentPlayerIndex;
    }

    public void setCurrentPlayerIndex(int currentPlayerIndex) {
        this.currentPlayerIndex = currentPlayerIndex;
    }

    public int getDealerIndex() {
        return dealerIndex;
    }

    public void setDealerIndex(int dealerIndex) {
        this.dealerIndex = dealerIndex;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public Tile getLastDiscardedTile() {
        return lastDiscardedTile;
    }

    public void setLastDiscardedTile(Tile lastDiscardedTile) {
        this.lastDiscardedTile = lastDiscardedTile;
    }

    public int getLastDiscardPlayerIndex() {
        return lastDiscardPlayerIndex;
    }

    public void setLastDiscardPlayerIndex(int lastDiscardPlayerIndex) {
        this.lastDiscardPlayerIndex = lastDiscardPlayerIndex;
    }

    public int getConsecutiveDealerWins() {
        return consecutiveDealerWins;
    }

    public void setConsecutiveDealerWins(int consecutiveDealerWins) {
        this.consecutiveDealerWins = consecutiveDealerWins;
    }

    public int getDealerChangesSinceCycleStart() {
        return dealerChangesSinceCycleStart;
    }

    public void setDealerChangesSinceCycleStart(int dealerChangesSinceCycleStart) {
        this.dealerChangesSinceCycleStart = dealerChangesSinceCycleStart;
    }

    public int getCycleStartDealerIndex() {
        return cycleStartDealerIndex;
    }

    public void setCycleStartDealerIndex(int cycleStartDealerIndex) {
        this.cycleStartDealerIndex = cycleStartDealerIndex;
    }

    public Map<String, Boolean> getContinueDecisions() {
        return continueDecisions;
    }

    public void setContinueDecisions(Map<String, Boolean> continueDecisions) {
        this.continueDecisions = continueDecisions;
    }

    public void initContinueDecisions() {
        continueDecisions.clear();
        for (Player p : players) {
            continueDecisions.put(p.getId(), null);
        }
    }

    public void setContinueDecision(String playerId, Boolean decision) {
        continueDecisions.put(playerId, decision);
    }

    public boolean allContinueDecided() {
        if (continueDecisions == null || continueDecisions.isEmpty()) return false;
        for (Boolean v : continueDecisions.values()) {
            if (v == null) return false;
        }
        return true;
    }

    public boolean allContinueYes() {
        if (!allContinueDecided()) return false;
        for (Boolean v : continueDecisions.values()) {
            if (!Boolean.TRUE.equals(v)) return false;
        }
        return true;
    }

    public List<Tile> getDiscardedTiles() {
        return discardedTiles;
    }

    public void setDiscardedTiles(List<Tile> discardedTiles) {
        this.discardedTiles = discardedTiles;
    }

    /**
     * 添加玩家
     */
    public boolean addPlayer(Player player) {
        if (players.size() < 4) {
            players.add(player);
            return true;
        }
        return false;
    }

    /**
     * 获取当前行动玩家
     */
    public Player getCurrentPlayer() {
        if (currentPlayerIndex >= 0 && currentPlayerIndex < players.size()) {
            return players.get(currentPlayerIndex);
        }
        return null;
    }

    /**
     * 获取庄家
     */
    public Player getDealer() {
        if (dealerIndex >= 0 && dealerIndex < players.size()) {
            return players.get(dealerIndex);
        }
        return null;
    }

    /**
     * 下一个玩家
     */
    public void nextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % 4;
    }

    /**
     * 检查是否所有玩家都已准备
     */
    public boolean isAllPlayersReady() {
        return players.size() == 4;
    }

    /**
     * 从牌墙抓一张牌（从牌头）
     */
    public Tile drawTile() {
        if (!wallTiles.isEmpty()) {
            return wallTiles.remove(0);
        }
        return null;
    }

    /**
     * 从牌墙尾部抓一张牌（从牌尾）
     */
    public Tile drawTileFromTail() {
        if (!wallTiles.isEmpty()) {
            return wallTiles.remove(wallTiles.size() - 1);
        }
        return null;
    }

    /**
     * 检查牌墙是否还有牌（考虑海底牌）
     */
    public boolean hasRemainingTiles() {
        // 基本留18张，每有一个明杠多留1张，暗杠多留2张
        int reservedCount = 18;
        for (Player player : players) {
            for (List<Tile> meld : player.getExposedMelds()) {
                if (meld.size() == 4) {
                    reservedCount += 1; // 明杠
                }
            }
            // 暗杠需要在游戏逻辑中额外处理
        }
        return wallTiles.size() > reservedCount;
    }

    public Map<String, Map<String, Object>> getAvailableActions() {
        return availableActions;
    }

    public void setAvailableActions(Map<String, Map<String, Object>> availableActions) {
        this.availableActions = availableActions;
    }

    /**
     * 清除所有玩家的可用操作
     */
    public void clearAllActions() {
        availableActions.clear();
    }

    /**
     * 设置某个玩家的可用操作
     */
    public void setPlayerActions(String playerId, Map<String, Object> actions) {
        availableActions.put(playerId, actions);
    }

    /**
     * 获取某个玩家的可用操作
     */
    public Map<String, Object> getPlayerActions(String playerId) {
        return availableActions.getOrDefault(playerId, new HashMap<>());
    }

    public String getCurrentActionPlayerId() {
        return currentActionPlayerId;
    }

    public void setCurrentActionPlayerId(String currentActionPlayerId) {
        this.currentActionPlayerId = currentActionPlayerId;
    }

    public String getCurrentActionType() {
        return currentActionType;
    }

    public void setCurrentActionType(String currentActionType) {
        this.currentActionType = currentActionType;
    }

    public boolean isQiangJinWindowActive() {
        return qiangJinWindowActive;
    }

    public void setQiangJinWindowActive(boolean qiangJinWindowActive) {
        this.qiangJinWindowActive = qiangJinWindowActive;
    }

    public Tile getLastDrawnTile() {
        return lastDrawnTile;
    }

    public void setLastDrawnTile(Tile lastDrawnTile) {
        this.lastDrawnTile = lastDrawnTile;
    }

    public int getLastDrawPlayerIndex() {
        return lastDrawPlayerIndex;
    }

    public void setLastDrawPlayerIndex(int lastDrawPlayerIndex) {
        this.lastDrawPlayerIndex = lastDrawPlayerIndex;
    }

    public int getLastDrawValidHandCountBefore() {
        return lastDrawValidHandCountBefore;
    }

    public void setLastDrawValidHandCountBefore(int lastDrawValidHandCountBefore) {
        this.lastDrawValidHandCountBefore = lastDrawValidHandCountBefore;
    }

    public String getLastWinPlayerId() {
        return lastWinPlayerId;
    }

    public void setLastWinPlayerId(String lastWinPlayerId) {
        this.lastWinPlayerId = lastWinPlayerId;
    }

    public String getLastWinType() {
        return lastWinType;
    }

    public void setLastWinType(String lastWinType) {
        this.lastWinType = lastWinType;
    }
}
