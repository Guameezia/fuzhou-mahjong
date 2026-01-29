package com.fzmahjong.engine;

import com.fzmahjong.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 游戏引擎 - 核心游戏逻辑
 */
public class GameEngine {
    
    private static final Logger log = LoggerFactory.getLogger(GameEngine.class);
    
    private GameState gameState;

    public GameEngine(GameState gameState) {
        this.gameState = gameState;
    }

    /**
     * 开始游戏
     * 开局顺序：
     * 1. 牌头摸牌（庄家17张，其余16张）
     * 2. 按轮次补花（所有人补花完毕后）
     * 3. 牌尾开出金牌
     * 4. 开始对局
     */
    public void startGame() {
        log.info("游戏开始！房间ID: {}", gameState.getRoomId());

        // 第一局庄家规则：第一个进入房间的玩家（position=0）先做庄
        // 这里依赖 RoomManager 按加入顺序分配 position（0-3）
        if (gameState.getPlayers().size() == 4 && gameState.getDealerIndex() == 0) {
            // 只有在未明确设置过时才做一次兜底：把position=0设为庄
            // （多局/重连时不会乱改）
            gameState.setDealerIndex(0);
        }

        // 第一局开始时初始化“轮庄一圈确认”的计数（只做一次）
        if (gameState.getPhase() == GamePhase.WAITING) {
            gameState.setDealerChangesSinceCycleStart(0);
            gameState.setCycleStartDealerIndex(gameState.getDealerIndex());
            gameState.getContinueDecisions().clear();
        }
        startHand();
    }

    /**
     * 开始“一局”（一盘牌）：清理上局数据、按当前 dealerIndex 发牌、补花、开金、进入 PLAYING
     */
    private void startHand() {
        // 清理上局动作与局面数据
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        gameState.setLastDiscardedTile(null);
        gameState.setLastDiscardPlayerIndex(-1);
        gameState.getDiscardedTiles().clear();
        gameState.setGoldTile(null);

        // 重置每个玩家本局数据（手牌/明牌/花牌）
        for (Player p : gameState.getPlayers()) {
            p.resetForNewHand();
            p.setDealer(false);
        }

        // 标记庄家
        if (gameState.getDealerIndex() >= 0 && gameState.getDealerIndex() < gameState.getPlayers().size()) {
            gameState.getPlayers().get(gameState.getDealerIndex()).setDealer(true);
        }

        // 1. 初始化牌墙
        initializeWall();

        gameState.setPhase(GamePhase.DEALING);

        // 2. 发牌（庄17其余16）
        log.info("开始牌头摸牌...");
        dealTiles();

        // 3. 补花
        gameState.setPhase(GamePhase.REPLACING_FLOWERS);
        log.info("开始按轮次补花...");
        replaceAllFlowers();

        // 4. 开金
        gameState.setPhase(GamePhase.OPENING_GOLD);
        log.info("牌尾开出金牌...");
        openGoldTile();

        // 5. 进入对局
        gameState.setPhase(GamePhase.PLAYING);
        gameState.setCurrentPlayerIndex(gameState.getDealerIndex());

        // 开局阶段：庄家在首打前可判断“天胡/三金倒”
        // 三金倒优先级 > 天胡（最终以 WinValidator 的顺序保证）
        // 同时，重置抢金窗口（必须等庄家首打后才开启）
        gameState.setQiangJinWindowActive(false);
        gameState.setLastDrawnTile(null);
        gameState.setLastDrawPlayerIndex(-1);
        gameState.setLastDrawValidHandCountBefore(-1);
        Player dealer = gameState.getDealer();
        if (dealer != null) {
            checkAvailableActionsAfterDraw(dealer);
        }

        log.info("开局完成，庄家是：{}", gameState.getDealer().getName());
        log.info("金牌是：{}", gameState.getGoldTile());
        log.info("开始对局，当前玩家：{}", gameState.getCurrentPlayer().getName());
    }

    /**
     * 初始化牌墙
     */
    private void initializeWall() {
        List<Tile> wall = TileFactory.createAndShuffleWall();
        gameState.setWallTiles(wall);
        log.info("牌墙初始化完成，共{}张牌", wall.size());
    }

    /**
     * 牌头摸牌
     * 庄家17张，其他玩家16张
     * 从牌墙顶部（牌头）按轮次摸牌
     */
    private void dealTiles() {
        int dealerIndex = gameState.getDealerIndex();
        
        // 四轮，每轮每人4张（16张）
        // 从庄家开始，逆时针顺序摸牌
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 4; i++) {
                int playerIndex = (dealerIndex + i) % 4;
                Player player = gameState.getPlayers().get(playerIndex);
                for (int j = 0; j < 4; j++) {
                    Tile tile = gameState.drawTile(); // 从牌头（索引0）取牌
                    if (tile != null) {
                        player.addTile(tile);
                    }
                }
            }
        }
        
        // 庄家再多抓一张（开门，17张）
        Player dealer = gameState.getDealer();
        Tile tile = gameState.drawTile(); // 从牌头取牌
        if (tile != null) {
            dealer.addTile(tile);
        }
        
        // 手牌排序（此时金牌还未开出，传入null）
        for (Player player : gameState.getPlayers()) {
            player.sortHand(null);
            log.info("玩家 {} 手牌数：{}", player.getName(), player.getHandSize());
        }
    }

    /**
     * 按轮次补花
     * 所有玩家按庄家开始逆时针顺序补花
     * 如果补到的牌又是花牌，继续补，直到所有人补花完毕
     * 注意：补花的牌不能是金牌（牌尾最后一张）
     */
    private void replaceAllFlowers() {
        boolean hasFlowers = true;
        int maxRounds = 10; // 防止无限循环
        int roundCount = 0;
        
        while (hasFlowers && roundCount < maxRounds) {
            hasFlowers = false;
            roundCount++;
            
            // 从庄家开始，逆时针按轮次补花
            for (int i = 0; i < 4; i++) {
                int playerIndex = (gameState.getDealerIndex() + i) % 4;
                Player player = gameState.getPlayers().get(playerIndex);
                
                List<Tile> flowers = player.getFlowerTilesInHand();
                if (!flowers.isEmpty()) {
                    hasFlowers = true;
                    for (Tile flower : flowers) {
                        // 移除花牌
                        player.replaceFlowerTile(flower);
                        
                        // 补一张新牌，确保不是牌尾最后一张（金牌）
                        Tile newTile = drawNonGoldTile();
                        if (newTile != null) {
                            player.addTile(newTile);
                            log.debug("玩家 {} 补花：{} -> {}", 
                                player.getName(), flower, newTile);
                        }
                    }
                    player.sortHand(gameState.getGoldTile());
                }
            }
        }
        
        log.info("所有人补花完毕，共进行{}轮", roundCount);
    }
    
    /**
     * 从牌墙抓一张非金牌的牌（用于补花）
     * 牌尾最后一张是金牌，补花时不能补到它
     * drawTile()从牌墙顶部取牌，所以不会取到最后一张（金牌）
     */
    private Tile drawNonGoldTile() {
        List<Tile> wallTiles = gameState.getWallTiles();
        if (wallTiles.isEmpty()) {
            return null;
        }
        
        // 如果牌墙只有一张，那这张就是金牌，不能补
        if (wallTiles.size() == 1) {
            log.warn("牌墙只剩一张牌（金牌），无法补花");
            return null;
        }
        
        // 从牌墙顶部取牌（drawTile从索引0取，不会取到最后一张）
        return gameState.drawTile();
    }

    /**
     * 牌尾开出金牌
     * 从牌墙尾部（最后一张）取出金牌
     * 如果开金开到花牌，则算作庄家的补花，从牌尾再摸一张牌作为新金牌，循环直到不是花牌
     */
    private void openGoldTile() {
        Player dealer = gameState.getDealer();
        if (dealer == null) {
            log.warn("庄家不存在，无法开出金牌");
            return;
        }
        
        List<Tile> wallTiles = gameState.getWallTiles();
        if (wallTiles.isEmpty()) {
            log.warn("牌墙为空，无法开出金牌");
            return;
        }
        
        // 从牌尾（最后一张）取金牌
        Tile goldTile = gameState.drawTileFromTail();
        if (goldTile == null) {
            log.warn("无法从牌尾取出金牌");
            return;
        }
        
        // 如果开金开到花牌，算作庄家的补花，从牌尾再摸一张牌作为新金牌
        while (goldTile.isFlowerTile() && !wallTiles.isEmpty()) {
            log.info("开金开到花牌：{}，算作庄家补花", goldTile);
            dealer.replaceFlowerTile(goldTile);
            
            // 从牌尾再摸一张牌作为新金牌
            goldTile = gameState.drawTileFromTail();
            if (goldTile == null) {
                log.warn("牌墙已空，无法继续开金");
                break;
            }
        }
        
        gameState.setGoldTile(goldTile);
        log.info("牌尾开出金牌：{}", goldTile);
    }

    /**
     * 玩家抓牌
     * 如果摸到花牌，算作玩家的补花，从牌尾摸一张牌，如果仍是花牌，继续补花循环
     */
    public boolean playerDraw(String playerId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        if (!gameState.hasRemainingTiles()) {
            log.info("牌墙已空，流局");
            finishHand(null);
            return true; // 需要广播新状态（确认继续/下一局）
        }
        
        // 记录摸牌前的“暗牌（不含花）”张数，用于抢金判定
        int validHandCountBefore = 0;
        for (Tile t : player.getHandTiles()) {
            if (t != null && t.getType() != TileType.FLOWER) {
                validHandCountBefore++;
            }
        }
        gameState.setLastDrawValidHandCountBefore(validHandCountBefore);
        gameState.setLastDrawPlayerIndex(player.getPosition());

        Tile tile = gameState.drawTile();
        if (tile != null) {
            player.addTile(tile);
            // 先不排序，等检查完进张后再排序
            log.debug("玩家 {} 抓牌：{}", player.getName(), tile);
            
            // 检查是否抓到花牌需要补
            // 如果摸到花牌，算作玩家的补花，从牌尾摸一张牌，如果仍是花牌，继续补花循环
            while (tile.isFlowerTile() && gameState.hasRemainingTiles()) {
                player.replaceFlowerTile(tile);
                log.debug("玩家 {} 摸到花牌：{}，算作补花", player.getName(), tile);
                
                // 从牌尾摸一张牌
                tile = gameState.drawTileFromTail();
                if (tile == null) {
                    log.warn("牌墙已空，无法补花");
                    break;
                }
                
                player.addTile(tile);
                log.debug("玩家 {} 从牌尾补花摸到：{}", player.getName(), tile);
            }

            // 记录最终有效进张（非花），用于抢金/自摸判定
            gameState.setLastDrawnTile(tile);
            
            // 检查进张（可以吃、碰、杠、胡的牌）
            checkAvailableActionsAfterDraw(player);
            
            // 摸牌后不立即排序，新牌先放在最右边，等出牌后再排序
            // player.sortHand(gameState.getGoldTile());
            
            return true;
        }
        
        return false;
    }

    /**
     * 玩家出牌
     */
    public boolean playerDiscard(String playerId, String tileId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        // 检查是否轮到该玩家
        if (!gameState.getCurrentPlayer().getId().equals(playerId)) {
            log.warn("还没轮到玩家 {} 出牌", player.getName());
            return false;
        }
        
        // 查找并移除该牌
        Tile tileToDiscard = null;
        for (Tile tile : player.getHandTiles()) {
            if (tile.getId().equals(tileId)) {
                tileToDiscard = tile;
                break;
            }
        }
        
        if (tileToDiscard == null) {
            log.warn("玩家 {} 手中没有牌：{}", player.getName(), tileId);
            return false;
        }

        // 金牌不能被当作普通牌打出
        Tile goldTile = gameState.getGoldTile();
        if (goldTile != null && tileToDiscard.isSameAs(goldTile)) {
            log.warn("玩家 {} 不能打出金牌：{}", player.getName(), tileToDiscard);
            return false;
        }
        
        player.removeTile(tileToDiscard);
        gameState.setLastDiscardedTile(tileToDiscard);
        gameState.setLastDiscardPlayerIndex(player.getPosition());
        gameState.getDiscardedTiles().add(tileToDiscard);

        // 抢金窗口：庄家首打之后开启；直到庄家首次再摸牌（见 playerDraw）后关闭
        if (player.getPosition() == gameState.getDealerIndex() && gameState.getDiscardedTiles().size() == 1) {
            gameState.setQiangJinWindowActive(true);
        }
        
        // 出牌后重新排序手牌（金牌排在最左边）
        player.sortHand(gameState.getGoldTile());
        
        log.info("玩家 {} 打出：{}", player.getName(), tileToDiscard);
        
        // 检查其他玩家是否可以吃碰杠胡
        checkAvailableActionsAfterDiscard(tileToDiscard, player.getPosition());
        
        return true;
    }

    /**
     * 下一个玩家
     */
    public void nextTurn() {
        gameState.nextPlayer();
        Player nextPlayer = gameState.getCurrentPlayer();
        log.debug("轮到玩家：{}", nextPlayer.getName());
    }

    /**
     * 根据ID查找玩家
     */
    private Player findPlayerById(String playerId) {
        return gameState.getPlayers().stream()
            .filter(p -> p.getId().equals(playerId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 检查玩家摸牌后可以进行的操作
     * 摸牌后只判断暗杠、自摸、三金倒
     */
    private void checkAvailableActionsAfterDraw(Player player) {
        Tile goldTile = gameState.getGoldTile();

        // 摸牌后只判断：暗杠 / 自摸 / 三金倒（吃碰杠只在别人出牌时判断）
        List<Tile> anGangTiles = ActionChecker.canAnGang(player);
        boolean canAnGang = anGangTiles != null && !anGangTiles.isEmpty();
        boolean isQiangJin = isQiangJinForCurrentDraw(player, goldTile);
        boolean canHu = ActionChecker.canHu(player, null, goldTile, isQiangJin);

        boolean canSanJinDao = false;
        if (goldTile != null) {
            long goldCount = player.getHandTiles().stream().filter(t -> t.isSameAs(goldTile)).count();
            canSanJinDao = goldCount >= 3;
        }

        Map<String, Object> playerActions = new HashMap<>();
        playerActions.put("canChi", false);
        playerActions.put("canPeng", false);
        playerActions.put("canGang", false);
        playerActions.put("canAnGang", canAnGang);
        playerActions.put("canHu", canHu);
        playerActions.put("canSanJinDao", canSanJinDao); // 三金倒

        // 不传递吃、碰、杠的牌列表（摸牌阶段不展示）
        playerActions.put("chiTiles", new ArrayList<>());
        playerActions.put("pengTiles", new ArrayList<>());
        playerActions.put("gangTiles", new ArrayList<>());
        playerActions.put("anGangTiles", anGangTiles == null ? new ArrayList<>() : anGangTiles);

        // 听牌提示：ActionChecker 会按张数约束自动返回空/非空
        playerActions.put("tingTiles", ActionChecker.getTingTiles(player, goldTile));

        // 如果摸牌后可以自摸、暗杠或三金倒，设置当前操作玩家和操作类型
        boolean canSelfAction = canHu || canAnGang || canSanJinDao;
        if (canSelfAction) {
            gameState.setCurrentActionPlayerId(player.getId());
            // 如果同时有多种操作，设置为"drawAction"让前端显示选择界面
            gameState.setCurrentActionType("drawAction");
        }
        
        gameState.setPlayerActions(player.getId(), playerActions);
        
        log.debug("玩家 {} 摸牌后可用操作：暗杠={}, 胡={}, 三金倒={}",
            player.getName(),
            canAnGang,
            canHu,
            canSanJinDao);

        // 庄家首次再摸牌后，关闭抢金窗口（本局只开放这一轮的抢金）
        if (gameState.isQiangJinWindowActive() && player.getPosition() == gameState.getDealerIndex()) {
            gameState.setQiangJinWindowActive(false);
        }
    }

    /**
     * 抢金判定（本项目约定）：
     * - 在庄家首打之后开启抢金窗口；
     * - 任意玩家在“暗牌 16 张（不含花）”时进张，若进的是金牌，则可直接胡（抢金）；
     * - 三金倒优先级更高，由 WinValidator 保证最终裁决顺序。
     */
    private boolean isQiangJinForCurrentDraw(Player player, Tile goldTile) {
        if (player == null || goldTile == null) {
            return false;
        }
        if (!gameState.isQiangJinWindowActive()) {
            return false;
        }
        if (gameState.getLastDrawPlayerIndex() != player.getPosition()) {
            return false;
        }
        Tile lastDrawn = gameState.getLastDrawnTile();
        if (lastDrawn == null || lastDrawn.getType() == TileType.FLOWER) {
            return false;
        }
        if (!lastDrawn.isSameAs(goldTile)) {
            return false;
        }
        return gameState.getLastDrawValidHandCountBefore() == 16;
    }
    
    /**
     * 检查其他玩家在有人出牌后可以进行的操作
     * 按照优先级顺序：胡 > 杠 > 碰 > 吃（只有下家可以吃）
     * 注意：如果玩家可以同时"胡"和"碰"，应该都能选择
     */
    private void checkAvailableActionsAfterDiscard(Tile discardedTile, int discardPlayerIndex) {
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);

        // 先给出牌者设置基础动作（主要是听牌列表），避免前端无数据无法展示
        if (discardPlayerIndex >= 0 && discardPlayerIndex < gameState.getPlayers().size()) {
            Player discarder = gameState.getPlayers().get(discardPlayerIndex);
            Map<String, Object> discarderActions = new HashMap<>();
            discarderActions.put("canHu", false);
            discarderActions.put("canGang", false);
            discarderActions.put("canPeng", false);
            discarderActions.put("canChi", false);
            discarderActions.put("canAnGang", false);
            discarderActions.put("canSanJinDao", false);
            discarderActions.put("chiTiles", new ArrayList<>());
            discarderActions.put("pengTiles", new ArrayList<>());
            discarderActions.put("gangTiles", new ArrayList<>());
            discarderActions.put("anGangTiles", new ArrayList<>());
            discarderActions.put("tingTiles", ActionChecker.getTingTiles(discarder, gameState.getGoldTile()));
            gameState.setPlayerActions(discarder.getId(), discarderActions);
        }
        
        // 按优先级顺序检查：胡 > 杠 > 碰 > 吃
        String[] actionTypes = {"hu", "gang", "peng", "chi"};
        String highestPriorityAction = null;
        List<String> highestPriorityPlayers = new ArrayList<>();
        
        // 先检查所有玩家可以执行的所有操作
        Map<String, Map<String, Boolean>> playerActionsMap = new HashMap<>();
        
        for (int i = 0; i < gameState.getPlayers().size(); i++) {
            Player otherPlayer = gameState.getPlayers().get(i);
            if (i == discardPlayerIndex) {
                continue; // 跳过出牌的玩家
            }
            
            Map<String, Boolean> actions = new HashMap<>();
            actions.put("canHu", false);
            actions.put("canGang", false);
            actions.put("canPeng", false);
            actions.put("canChi", false);
            
            // 检查胡（所有玩家都可以）
            boolean canHu = ActionChecker.canHu(otherPlayer, discardedTile, 
                gameState.getGoldTile(), false);
            actions.put("canHu", canHu);
            
            // 检查杠（所有玩家都可以）
            boolean canGang = ActionChecker.canGang(otherPlayer, discardedTile);
            actions.put("canGang", canGang);
            
            // 检查碰（所有玩家都可以）
            boolean canPeng = ActionChecker.canPeng(otherPlayer, discardedTile);
            actions.put("canPeng", canPeng);
            
            // 检查吃（只有下家可以）
            int nextPlayerIndex = (discardPlayerIndex + 1) % 4;
            if (i == nextPlayerIndex) {
                boolean canChi = ActionChecker.canChi(otherPlayer, discardedTile,
                    discardPlayerIndex, i, gameState.getGoldTile());
                actions.put("canChi", canChi);
            }
            
            // 如果玩家有任何可用操作，记录
            if (canHu || canGang || canPeng || actions.get("canChi")) {
                playerActionsMap.put(otherPlayer.getId(), actions);
            }
        }
        
        // 按优先级顺序，找到最高优先级的操作
        for (String actionType : actionTypes) {
            List<String> canActionPlayers = new ArrayList<>();
            
            for (Map.Entry<String, Map<String, Boolean>> entry : playerActionsMap.entrySet()) {
                String playerId = entry.getKey();
                Map<String, Boolean> actions = entry.getValue();
                
                boolean canAction = false;
                switch (actionType) {
                    case "hu":
                        canAction = Boolean.TRUE.equals(actions.get("canHu"));
                        break;
                    case "gang":
                        canAction = Boolean.TRUE.equals(actions.get("canGang"));
                        break;
                    case "peng":
                        canAction = Boolean.TRUE.equals(actions.get("canPeng"));
                        break;
                    case "chi":
                        canAction = Boolean.TRUE.equals(actions.get("canChi"));
                        break;
                }
                
                if (canAction) {
                    canActionPlayers.add(playerId);
                }
            }
            
            // 如果找到可以执行该优先级的玩家，记录并停止检查低优先级
            if (!canActionPlayers.isEmpty()) {
                highestPriorityAction = actionType;
                highestPriorityPlayers = canActionPlayers;
                break;
            }
        }
        
        // 设置每个玩家的可用操作
        // 注意：如果玩家可以同时"胡"和"碰"，应该都能选择
        for (Map.Entry<String, Map<String, Boolean>> entry : playerActionsMap.entrySet()) {
            String playerId = entry.getKey();
            Map<String, Boolean> actions = entry.getValue();
            
            // 找到对应的玩家
            Player player = findPlayerById(playerId);
            if (player == null) {
                continue;
            }
            
            // 如果玩家可以执行最高优先级的操作，设置操作信息
            // 但也要保留其他可用操作（例如：可以同时"胡"和"碰"）
            if (highestPriorityPlayers.contains(playerId)) {
                Map<String, Object> playerActions = new HashMap<>();
                playerActions.put("discardedTile", discardedTile);
                playerActions.put("discardPlayerIndex", discardPlayerIndex);
                
                // 设置所有可用操作（如果玩家可以同时"胡"和"碰"，都设置为true）
                playerActions.put("canHu", actions.get("canHu"));
                playerActions.put("canGang", actions.get("canGang"));
                playerActions.put("canPeng", actions.get("canPeng"));
                playerActions.put("canChi", actions.get("canChi"));
                // 听牌提示（别人出牌阶段也可能需要展示本家听牌）
                playerActions.put("tingTiles", ActionChecker.getTingTiles(player, gameState.getGoldTile()));
                
                gameState.setPlayerActions(playerId, playerActions);
                
                // 记录玩家可以执行的操作
                List<String> availableOps = new ArrayList<>();
                if (actions.get("canHu")) availableOps.add("胡");
                if (actions.get("canGang")) availableOps.add("杠");
                if (actions.get("canPeng")) availableOps.add("碰");
                if (actions.get("canChi")) availableOps.add("吃");
                log.info("玩家 {} 可以：{}", player.getName(), String.join("、", availableOps));
            }
        }
        
        // 设置第一个应该执行操作的玩家（按玩家顺序）
        if (!highestPriorityPlayers.isEmpty()) {
            // 从出牌玩家的下家开始，按逆时针顺序找到第一个可以执行操作的玩家
            int startIndex = (discardPlayerIndex + 1) % 4;
            for (int offset = 0; offset < 4; offset++) {
                int playerIndex = (startIndex + offset) % 4;
                Player player = gameState.getPlayers().get(playerIndex);
                if (highestPriorityPlayers.contains(player.getId())) {
                    gameState.setCurrentActionPlayerId(player.getId());
                    gameState.setCurrentActionType(highestPriorityAction);
                    log.info("设置优先级操作：玩家 {} 可以 {}（最高优先级）", player.getName(), highestPriorityAction);
                    break;
                }
            }
        }
    }
    
    /**
     * 玩家吃牌
     */
    public boolean playerChi(String playerId, String tileId1, String tileId2) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        Tile discardedTile = gameState.getLastDiscardedTile();
        if (discardedTile == null) {
            log.warn("没有打出的牌可以吃");
            return false;
        }
        
        // 检查是否可以吃
        int playerIndex = player.getPosition();
        int discardPlayerIndex = gameState.getLastDiscardPlayerIndex();
        if (!ActionChecker.canChi(player, discardedTile, discardPlayerIndex, playerIndex, gameState.getGoldTile())) {
            log.warn("玩家 {} 不能吃这张牌", player.getName());
            return false;
        }
        
        // 从手牌中找到对应的两张牌
        List<Tile> handTiles = player.getHandTiles();
        Tile tile1 = findTileById(handTiles, tileId1);
        Tile tile2 = findTileById(handTiles, tileId2);
        
        if (tile1 == null || tile2 == null) {
            log.warn("找不到指定的牌");
            return false;
        }

        // 金牌不能参与吃：既不能吃金牌本身，也不能用手中的金牌去吃
        Tile goldTile = gameState.getGoldTile();
        if (goldTile != null && (tile1.isSameAs(goldTile) || tile2.isSameAs(goldTile))) {
            log.warn("吃牌组合包含金牌，拒绝执行：玩家={}, tile1={}, tile2={}, gold={}", player.getName(), tile1, tile2, goldTile);
            return false;
        }
        
        // 验证是否可以组成顺子
        List<Tile> meld = new ArrayList<>();
        meld.add(tile1);
        meld.add(tile2);
        meld.add(discardedTile);
        meld.sort(Tile::compareTo);
        
        // 验证是否是顺子
        if (!isValidChiMeld(meld)) {
            log.warn("不能组成有效的顺子");
            return false;
        }
        
        // 移除手牌中的两张牌
        player.removeTile(tile1);
        player.removeTile(tile2);
        
        // 添加到明牌
        player.addExposedMeld(meld);
        
        // 从牌堆中移除被吃的牌
        gameState.getDiscardedTiles().removeIf(t -> t.getId().equals(discardedTile.getId()));
        gameState.setLastDiscardedTile(null);
        
        // 设置当前玩家为吃牌的玩家
        gameState.setCurrentPlayerIndex(playerIndex);
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        
        log.info("玩家 {} 吃：{}", player.getName(), meld);
        
        return true;
    }
    
    /**
     * 玩家碰牌
     */
    public boolean playerPeng(String playerId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        Tile discardedTile = gameState.getLastDiscardedTile();
        if (discardedTile == null) {
            log.warn("没有打出的牌可以碰");
            return false;
        }
        
        // 检查是否可以碰
        if (!ActionChecker.canPeng(player, discardedTile)) {
            log.warn("玩家 {} 不能碰这张牌", player.getName());
            return false;
        }
        
        // 从手牌中找到两张相同的牌
        List<Tile> handTiles = player.getHandTiles();
        List<Tile> matchingTiles = handTiles.stream()
            .filter(t -> t.isSameAs(discardedTile))
            .limit(2)
            .collect(Collectors.toList());
        
        if (matchingTiles.size() < 2) {
            log.warn("手牌中没有足够的牌可以碰");
            return false;
        }
        
        // 移除手牌中的两张牌
        matchingTiles.forEach(player::removeTile);
        
        // 添加到明牌
        List<Tile> meld = new ArrayList<>(matchingTiles);
        meld.add(discardedTile);
        player.addExposedMeld(meld);
        
        // 从牌堆中移除被碰的牌
        gameState.getDiscardedTiles().removeIf(t -> t.getId().equals(discardedTile.getId()));
        gameState.setLastDiscardedTile(null);
        
        // 设置当前玩家为碰牌的玩家
        gameState.setCurrentPlayerIndex(player.getPosition());
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        
        log.info("玩家 {} 碰：{}", player.getName(), meld);
        
        return true;
    }
    
    /**
     * 玩家杠牌（明杠）
     */
    public boolean playerGang(String playerId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        Tile discardedTile = gameState.getLastDiscardedTile();
        if (discardedTile == null) {
            log.warn("没有打出的牌可以杠");
            return false;
        }
        
        // 检查是否可以杠
        if (!ActionChecker.canGang(player, discardedTile)) {
            log.warn("玩家 {} 不能杠这张牌", player.getName());
            return false;
        }
        
        // 从手牌中找到三张相同的牌
        List<Tile> handTiles = player.getHandTiles();
        List<Tile> matchingTiles = handTiles.stream()
            .filter(t -> t.isSameAs(discardedTile))
            .limit(3)
            .collect(Collectors.toList());
        
        if (matchingTiles.size() < 3) {
            log.warn("手牌中没有足够的牌可以杠");
            return false;
        }
        
        // 移除手牌中的三张牌
        matchingTiles.forEach(player::removeTile);
        
        // 添加到明牌
        List<Tile> meld = new ArrayList<>(matchingTiles);
        meld.add(discardedTile);
        player.addExposedMeld(meld);
        
        // 从牌堆中移除被杠的牌
        gameState.getDiscardedTiles().removeIf(t -> t.getId().equals(discardedTile.getId()));
        gameState.setLastDiscardedTile(null);
        
        // 设置当前玩家为杠牌的玩家
        gameState.setCurrentPlayerIndex(player.getPosition());
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        
        log.info("玩家 {} 杠：{}", player.getName(), meld);
        
        // 杠牌后从牌尾摸牌
        if (gameState.hasRemainingTiles()) {
            Tile tile = gameState.drawTileFromTail();
            if (tile != null) {
                // 记录“杠后进张”的摸牌信息，供前端高亮与抢金等逻辑使用
                gameState.setLastDrawValidHandCountBefore(
                    (int) player.getHandTiles().stream()
                        .filter(t -> t != null && t.getType() != TileType.FLOWER)
                        .count()
                );
                gameState.setLastDrawPlayerIndex(player.getPosition());

                player.addTile(tile);
                log.info("玩家 {} 杠后从牌尾摸牌：{}", player.getName(), tile);
                
                // 如果摸到花牌，算作玩家的补花，从牌尾摸一张牌，如果仍是花牌，继续补花循环
                while (tile.isFlowerTile() && gameState.hasRemainingTiles()) {
                    player.replaceFlowerTile(tile);
                    log.debug("玩家 {} 杠后摸到花牌：{}，算作补花", player.getName(), tile);
                    
                    // 从牌尾摸一张牌
                    tile = gameState.drawTileFromTail();
                    if (tile == null) {
                        log.warn("牌墙已空，无法补花");
                        break;
                    }
                    
                    player.addTile(tile);
                    log.debug("玩家 {} 杠后从牌尾补花摸到：{}", player.getName(), tile);
                }
                
                // 记录最终有效进张（非花），用于前端“新牌高亮”以及抢金等判定
                gameState.setLastDrawnTile(tile);

                // 检查进张（可以吃、碰、杠、胡的牌）
                checkAvailableActionsAfterDraw(player);

                // 杠后摸牌的展示与普通摸牌保持一致：
                // 不在这里立即排序，保留“新摸牌在最右侧”的前端表现
                // player.sortHand(gameState.getGoldTile());
            }
        }
        
        return true;
    }
    
    /**
     * 玩家暗杠
     */
    public boolean playerAnGang(String playerId, String tileId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }
        
        // 找到要杠的牌
        Tile tile = findTileById(player.getHandTiles(), tileId);
        if (tile == null) {
            log.warn("找不到指定的牌");
            return false;
        }
        
        // 检查是否可以暗杠
        List<Tile> anGangTiles = ActionChecker.canAnGang(player);
        boolean canAnGang = anGangTiles.stream()
            .anyMatch(t -> t.isSameAs(tile));
        
        if (!canAnGang) {
            log.warn("玩家 {} 不能暗杠这张牌", player.getName());
            return false;
        }
        
        // 从手牌中找到四张相同的牌
        List<Tile> handTiles = player.getHandTiles();
        List<Tile> matchingTiles = handTiles.stream()
            .filter(t -> t.isSameAs(tile))
            .limit(4)
            .collect(Collectors.toList());
        
        if (matchingTiles.size() < 4) {
            log.warn("手牌中没有足够的牌可以暗杠");
            return false;
        }
        
        // 移除手牌中的四张牌
        matchingTiles.forEach(player::removeTile);

        // 暗杠：牌面只对自己可见，不应出现在公共明牌中
        // 因此不放入 exposedMelds，而是放入专门的暗杠列表
        player.addConcealedKong(matchingTiles);
        
        // 清除操作状态
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);

        // 暗杠属于“本轮出牌玩家”的操作，暗杠后依然应该轮到该玩家继续行动（补牌再出牌），
        // 因此需要显式将当前行动玩家索引指向暗杠玩家，避免仍停留在上一个出牌者身上。
        gameState.setCurrentPlayerIndex(player.getPosition());

        log.info("玩家 {} 暗杠：{}", player.getName(), matchingTiles);
        
        // 暗杠后从牌尾摸牌
        if (gameState.hasRemainingTiles()) {
            Tile drawnTile = gameState.drawTileFromTail();
            if (drawnTile != null) {
                // 记录“暗杠后进张”的摸牌信息，供前端高亮与抢金等逻辑使用
                gameState.setLastDrawValidHandCountBefore(
                    (int) player.getHandTiles().stream()
                        .filter(t -> t != null && t.getType() != TileType.FLOWER)
                        .count()
                );
                gameState.setLastDrawPlayerIndex(player.getPosition());

                player.addTile(drawnTile);
                log.info("玩家 {} 暗杠后从牌尾摸牌：{}", player.getName(), drawnTile);
                
                // 如果摸到花牌，算作玩家的补花，从牌尾摸一张牌，如果仍是花牌，继续补花循环
                while (drawnTile.isFlowerTile() && gameState.hasRemainingTiles()) {
                    player.replaceFlowerTile(drawnTile);
                    log.debug("玩家 {} 暗杠后摸到花牌：{}，算作补花", player.getName(), drawnTile);
                    
                    // 从牌尾摸一张牌
                    drawnTile = gameState.drawTileFromTail();
                    if (drawnTile == null) {
                        log.warn("牌墙已空，无法补花");
                        break;
                    }
                    
                    player.addTile(drawnTile);
                    log.debug("玩家 {} 暗杠后从牌尾补花摸到：{}", player.getName(), drawnTile);
                }
                
                // 记录最终有效进张（非花），用于前端“新牌高亮”以及抢金等判定
                gameState.setLastDrawnTile(drawnTile);

                // 检查进张（可以吃、碰、杠、胡的牌）
                checkAvailableActionsAfterDraw(player);

                // 暗杠后摸牌的展示与普通摸牌保持一致：
                // 不在这里立即排序，保留“新摸牌在最右侧”的前端表现
                // player.sortHand(gameState.getGoldTile());
            }
        }
        
        return true;
    }
    
    /**
     * 玩家胡牌
     */
    public boolean playerHu(String playerId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }

        // 区分自摸与点炮：
        // - 自摸：当前处于摸牌后的“自摸/暗杠/三金倒”选择窗口（drawAction），
        //         或逻辑上没有最近弃牌可依赖，此时使用当前手牌直接判断胡牌；
        // - 点炮：当前是针对最近弃牌的“胡”操作，需要把别人打出的那张牌临时加入再判断胡牌。
        Tile discardedTile = gameState.getLastDiscardedTile();
        int lastDiscardPlayerIndex = gameState.getLastDiscardPlayerIndex();

        Tile tileForHuCheck = null;
        boolean isZiMo;

        // 1）如果当前操作类型就是摸牌后的 drawAction，并且当前操作玩家是自己，
        //    明确视为“自摸”，不要再把最近弃牌当作点炮牌使用
        boolean isDrawActionForSelf =
            "drawAction".equals(gameState.getCurrentActionType()) &&
            playerId.equals(gameState.getCurrentActionPlayerId());

        if (isDrawActionForSelf) {
            isZiMo = true;
        } else if (discardedTile == null) {
            // 2）兜底：没有最近弃牌，也只能按自摸处理
            isZiMo = true;
        } else if (lastDiscardPlayerIndex == player.getPosition()) {
            // 3）保护性兜底：最近弃牌来自自己（理论上不该出现），也按自摸处理
            isZiMo = true;
        } else {
            // 4）最近弃牌来自其他玩家，且当前并非摸牌后的自摸窗口，这是点炮
            isZiMo = false;
            tileForHuCheck = discardedTile;
        }

        boolean isQiangJin = false;
        if (isZiMo) {
            isQiangJin = isQiangJinForCurrentDraw(player, gameState.getGoldTile());
        }
        boolean canHu = ActionChecker.canHu(player, tileForHuCheck,
            gameState.getGoldTile(), isQiangJin);
        
        if (!canHu) {
            log.warn("玩家 {} 不能胡牌（类型={}，最近弃牌={}，弃牌玩家索引={}）",
                player.getName(),
                isZiMo ? "自摸" : "点炮",
                discardedTile,
                lastDiscardPlayerIndex);
            return false;
        }
        
        // 如果胡的是别人打出的牌，需要添加到手牌
        if (!isZiMo && discardedTile != null && lastDiscardPlayerIndex != player.getPosition()) {
            player.addTile(discardedTile);
        }
        
        log.info("玩家 {} 胡牌！", player.getName());
        finishHand(player.getId());
        
        return true;
    }

    /**
     * 结算一局并决定是否开下一局 / 是否进入“确认继续”
     * @param winnerPlayerId 胜者（胡牌者），流局则为null
     */
    private void finishHand(String winnerPlayerId) {
        // 清理本局动作
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);

        int oldDealerIndex = gameState.getDealerIndex();
        String oldDealerId = (gameState.getDealer() != null) ? gameState.getDealer().getId() : null;

        boolean dealerWins = winnerPlayerId != null && winnerPlayerId.equals(oldDealerId);

        if (dealerWins) {
            // 连庄：庄家赢了则继续做庄，连庄次数+1
            gameState.setConsecutiveDealerWins(gameState.getConsecutiveDealerWins() + 1);
            log.info("庄家胡牌，连庄次数={}", gameState.getConsecutiveDealerWins());
        } else {
            // 换庄：逆时针轮庄（索引+1）
            gameState.setConsecutiveDealerWins(0);
            int newDealerIndex = (oldDealerIndex + 1) % 4;
            gameState.setDealerIndex(newDealerIndex);
            gameState.setDealerChangesSinceCycleStart(gameState.getDealerChangesSinceCycleStart() + 1);
            log.info("换庄：{} -> {}", oldDealerIndex, newDealerIndex);
        }

        // 轮庄一圈：仅统计“换庄”次数，达到4次表示庄位转了一圈
        boolean needConfirmContinue = gameState.getDealerChangesSinceCycleStart() >= 4;
        if (needConfirmContinue) {
            gameState.setPhase(GamePhase.CONFIRM_CONTINUE);
            gameState.initContinueDecisions();
            // 重置下一轮计数（下一轮从当前庄家开始）
            gameState.setDealerChangesSinceCycleStart(0);
            gameState.setCycleStartDealerIndex(gameState.getDealerIndex());
            log.info("已轮庄一圈，进入确认继续阶段");
            return;
        }

        // 不需要确认：自动开下一局
        startHand();
    }

    /**
     * 玩家对“是否继续对局”的表态
     */
    public boolean playerContinue(String playerId, boolean willContinue) {
        if (gameState.getPhase() != GamePhase.CONFIRM_CONTINUE) {
            return false;
        }
        Player p = findPlayerById(playerId);
        if (p == null) return false;

        gameState.setContinueDecision(playerId, willContinue);
        log.info("玩家 {} 选择{}继续对局", p.getName(), willContinue ? "" : "不");

        // 只要有任意一名玩家选择“不继续”，立刻结束本房间对局，
        // 后续由控制器根据 FINISHED 阶段解散房间并让所有客户端回到初始界面。
        if (!willContinue) {
            gameState.setPhase(GamePhase.FINISHED);
            log.info("玩家 {} 选择不继续，对局结束（无需等待其他玩家表态）", p.getName());
            return true;
        }

        if (!gameState.allContinueDecided()) {
            return true;
        }

        if (!gameState.allContinueYes()) {
            gameState.setPhase(GamePhase.FINISHED);
            log.info("有人选择不继续，对局结束");
            return true;
        }

        // 全员继续：开下一局
        startHand();
        return true;
    }
    
    /**
     * 玩家过（不进行任何操作）
     * 实现优先级轮转：一个玩家选择"过"后，轮到下一个优先级玩家执行
     */
    public boolean playerPass(String playerId) {
        Player player = findPlayerById(playerId);
        if (player == null) {
            return false;
        }
        
        // 清除该玩家的可用操作
        gameState.setPlayerActions(player.getId(), new HashMap<>());
        
        log.debug("玩家 {} 选择过", player.getName());
        
        // 找到下一个可以执行操作的玩家
        String currentActionType = gameState.getCurrentActionType();
        if (currentActionType == null) {
            return true; // 没有待处理的操作
        }
        
        int discardPlayerIndex = gameState.getLastDiscardPlayerIndex();
        
        // 按优先级顺序检查：胡 > 杠 > 碰 > 吃
        String[] actionTypes = {"hu", "gang", "peng", "chi"};
        int currentActionIndex = -1;
        for (int i = 0; i < actionTypes.length; i++) {
            if (actionTypes[i].equals(currentActionType)) {
                currentActionIndex = i;
                break;
            }
        }
        
        if (currentActionIndex == -1) {
            return true;
        }
        
        // 从当前优先级开始，查找下一个可以执行操作的玩家
        for (int actionIdx = currentActionIndex; actionIdx < actionTypes.length; actionIdx++) {
            String actionType = actionTypes[actionIdx];
            
            // 从当前玩家的下家开始，按逆时针顺序查找
            int currentPlayerIndex = player.getPosition();
            int startIndex = (currentPlayerIndex + 1) % 4;
            
            for (int offset = 0; offset < 4; offset++) {
                int playerIndex = (startIndex + offset) % 4;
                if (playerIndex == discardPlayerIndex) {
                    continue; // 跳过出牌的玩家
                }
                
                // 吃只能下家执行
                if ("chi".equals(actionType)) {
                    int nextPlayerIndex = (discardPlayerIndex + 1) % 4;
                    if (playerIndex != nextPlayerIndex) {
                        continue; // 跳过非下家
                    }
                }
                
                Player otherPlayer = gameState.getPlayers().get(playerIndex);
                Map<String, Object> actions = gameState.getPlayerActions(otherPlayer.getId());
                
                if (actions.isEmpty()) {
                    continue; // 该玩家没有可用操作
                }
                
                // 检查该玩家是否可以执行当前优先级的操作
                boolean canAction = false;
                switch (actionType) {
                    case "hu":
                        canAction = Boolean.TRUE.equals(actions.get("canHu"));
                        break;
                    case "gang":
                        canAction = Boolean.TRUE.equals(actions.get("canGang"));
                        break;
                    case "peng":
                        canAction = Boolean.TRUE.equals(actions.get("canPeng"));
                        break;
                    case "chi":
                        canAction = Boolean.TRUE.equals(actions.get("canChi"));
                        break;
                }
                
                if (canAction) {
                    // 找到下一个可以执行操作的玩家
                    gameState.setCurrentActionPlayerId(otherPlayer.getId());
                    gameState.setCurrentActionType(actionType);
                    log.info("玩家 {} 过，轮到玩家 {} 执行 {}", player.getName(), otherPlayer.getName(), actionType);
                    return true;
                }
            }
            
            // 如果当前优先级没有其他玩家可以执行，检查下一个优先级
            if (actionIdx < actionTypes.length - 1) {
                // 重置，检查下一个优先级
                continue;
            }
        }
        
        // 所有优先级都没有可用操作，清除状态
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        log.info("所有玩家都选择过，进入下一阶段");
        
        return true;
    }
    
    /**
     * 根据ID查找牌
     */
    private Tile findTileById(List<Tile> tiles, String tileId) {
        return tiles.stream()
            .filter(t -> t.getId().equals(tileId))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 验证是否是有效的吃牌组合（顺子）
     */
    private boolean isValidChiMeld(List<Tile> meld) {
        if (meld.size() != 3) {
            return false;
        }
        
        // 必须是同一种类型
        TileType type = meld.get(0).getType();
        if (type == TileType.WIND || type == TileType.DRAGON) {
            return false; // 字牌不能吃
        }
        
        // 必须是连续的数值
        int[] values = meld.stream()
            .mapToInt(Tile::getValue)
            .sorted()
            .toArray();
        
        return values[0] + 1 == values[1] && values[1] + 1 == values[2];
    }
    
    public GameState getGameState() {
        return gameState;
    }
}
