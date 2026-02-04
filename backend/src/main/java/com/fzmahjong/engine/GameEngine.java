package com.fzmahjong.engine;

import com.fzmahjong.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Iterator;

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
     * 开始“一局”（一盘牌）：清理上局数据、按当前 dealerIndex 发牌，
     * 然后进入“手动补花阶段”和“等待庄家开金”，实际补花/开金由前端按钮驱动。
     * 牌型规则、补花/开金的具体逻辑保持不变，仅拆开时间轴。
     *
     * 注意：该方法在本类内部以及少数控制入口中被调用，
     * 单局结束后的自动开局不再直接在 finishHand 内触发，
     * 而是通过控制器在短暂停留后显式调用公开方法 {@link #startNextHand()}。
     */
    private void startHand() {
        // 清理上局动作与局面数据
        gameState.clearAllActions();
        gameState.setCurrentActionPlayerId(null);
        gameState.setCurrentActionType(null);
        gameState.setLastActionPlayerId(null);
        gameState.setLastActionType(null);
        gameState.setLastDiscardedTile(null);
        gameState.setLastDiscardPlayerIndex(-1);
        gameState.getDiscardedTiles().clear();
        gameState.setGoldTile(null);
        gameState.setReplacingFlowers(false);
        gameState.setCurrentFlowerPlayerIndex(-1);
        gameState.setFlowerRoundCount(0);
        gameState.setWaitingOpenGold(false);

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

        // 3. 进入“手动补花阶段”：从庄家开始，一轮一轮补花，实际补花由前端按钮驱动
        gameState.setPhase(GamePhase.REPLACING_FLOWERS);
        gameState.setReplacingFlowers(true);
        gameState.setFlowerRoundCount(0);
        gameState.setWaitingOpenGold(false);

        // 自动选择第一个“手上有花”的玩家作为补花起点；若全桌都没有花，直接进入开金阶段
        selectInitialFlowerPlayer();
        log.info("进入手动补花阶段，从庄家开始一轮一轮补花");
    }

    /**
     * 初始化牌墙
     * 第一局若配置了 {@link FirstHandPreset#WALL_ORDER}（144 张），则使用预设牌序；否则及第二局起均随机。
     */
    private void initializeWall() {
        List<Tile> wall;
        boolean isFirstHand = gameState.isFirstHandAfterStart();
        boolean hasPreset = FirstHandPreset.hasPreset();
        
        log.info("初始化牌墙 - isFirstHandAfterStart: {}, hasPreset: {}", isFirstHand, hasPreset);
        
        if (isFirstHand && hasPreset) {
            wall = FirstHandPreset.buildWall();
            if (wall != null) {
                log.info("第一局使用预设牌序，共{}张牌", wall.size());
            } else {
                wall = TileFactory.createAndShuffleWall();
                log.warn("预设牌序无效（buildWall返回null），改用随机牌墙");
            }
            gameState.setFirstHandAfterStart(false);
        } else {
            if (!isFirstHand) {
                log.info("非第一局，使用随机牌墙");
            } else if (!hasPreset) {
                log.warn("第一局但未启用预设（WALL_ORDER为null或长度非144），使用随机牌墙");
                if (FirstHandPreset.WALL_ORDER != null) {
                    log.warn("WALL_ORDER实际长度: {}", FirstHandPreset.WALL_ORDER.size());
                } else {
                    log.warn("WALL_ORDER为null");
                }
            }
            wall = TileFactory.createAndShuffleWall();
            if (gameState.isFirstHandAfterStart()) {
                gameState.setFirstHandAfterStart(false);
            }
            log.info("牌墙初始化完成，共{}张牌", wall.size());
        }
        gameState.setWallTiles(wall);
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
     * 对单个玩家执行“一轮补花”：将其当前手牌中的花全部移入花区，并从牌尾补回。
     * 该逻辑与原先 replaceAllFlowers 内对单个玩家的处理完全一致。
     */
    private void replaceFlowersForPlayer(Player player) {
        List<Tile> flowers = player.getFlowerTilesInHand();
        if (flowers.isEmpty()) {
            return;
        }

        for (Tile flower : flowers) {
            // 移除花牌
            player.replaceFlowerTile(flower);

            // 补一张新牌，从牌尾取牌
            Tile newTile = drawNonGoldTile();
            if (newTile != null) {
                player.addTile(newTile);
                log.debug("玩家 {} 补花：{} -> {}", player.getName(), flower, newTile);
            }
        }

        // 补花后整理手牌（金牌排在最左侧）
        player.sortHand(gameState.getGoldTile());
    }

    /**
     * 选择补花阶段的起始玩家：
     * - 从庄家开始逆时针寻找第一位“手上有花”的玩家；
     * - 如果全桌都没有花，则直接结束补花，进入“等待开金”阶段。
     */
    private void selectInitialFlowerPlayer() {
        if (!gameState.isReplacingFlowers()) {
            return;
        }
        List<Player> players = gameState.getPlayers();
        if (players == null || players.isEmpty()) {
            return;
        }

        int dealerIndex = gameState.getDealerIndex();
        int size = players.size();
        int firstWithFlower = -1;
        for (int offset = 0; offset < size; offset++) {
            int idx = (dealerIndex + offset) % size;
            Player p = players.get(idx);
            if (!p.getFlowerTilesInHand().isEmpty()) {
                firstWithFlower = idx;
                break;
            }
        }

        if (firstWithFlower >= 0) {
            gameState.setCurrentFlowerPlayerIndex(firstWithFlower);
        } else {
            // 全桌都没有花，直接结束补花，进入开金阶段
            finishFlowerPhaseAndWaitOpenGold();
        }
    }

    /**
     * 按轮次补花（批量版本）
     * 保留该方法以兼容原有一次性补花逻辑，方便单元测试或将来需要“全自动模式”时使用。
     * 当前正常对局流程不再直接调用此方法。
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

                if (!player.getFlowerTilesInHand().isEmpty()) {
                    hasFlowers = true;
                    replaceFlowersForPlayer(player);
                }
            }
        }

        log.info("所有人补花完毕，共进行{}轮", roundCount);
    }
    
    /**
     * 从牌墙抓一张牌（用于补花）
     * 补花从牌尾开始，补花阶段可以取最后一张
     * 补花完成后，剩余牌墙的最后一张才是金牌
     */
    private Tile drawNonGoldTile() {
        // 补花从牌尾开始，直接取最后一张
        return gameState.drawTileFromTail();
    }

    /**
     * 手动补花：在补花阶段，轮到的那个玩家点击“补花”按钮。
     * 规则完全沿用 replaceAllFlowers 中对该玩家的一轮处理。
     */
    public boolean playerReplaceFlowers(String playerId) {
        if (gameState.getPhase() != GamePhase.REPLACING_FLOWERS || !gameState.isReplacingFlowers()) {
            log.warn("当前不在补花阶段，忽略补花请求");
            return false;
        }

        Player player = findPlayerById(playerId);
        if (player == null) {
            log.warn("玩家不存在：{}", playerId);
            return false;
        }

        int expectedIndex = gameState.getCurrentFlowerPlayerIndex();
        if (expectedIndex < 0 || expectedIndex >= gameState.getPlayers().size()) {
            log.warn("当前补花索引非法: {}", expectedIndex);
            return false;
        }

        Player expectedPlayer = gameState.getPlayers().get(expectedIndex);
        if (!expectedPlayer.getId().equals(playerId)) {
            log.warn("还没轮到玩家 {} 补花", player.getName());
            return false;
        }

        // 执行该玩家本轮所有补花（可能摸多张，内部自己处理摸到花继续补）
        if (!player.getFlowerTilesInHand().isEmpty()) {
            replaceFlowersForPlayer(player);
        } else {
            log.debug("玩家 {} 本轮没有花牌，无需补花", player.getName());
        }

        // 轮到下一个补花玩家，或结束补花阶段
        advanceFlowerTurnAfterPlayer();
        return true;
    }

    /**
     * 在某玩家完成一轮补花后，推进补花状态机：
     * - 判断全桌是否还有花
     * - 若没有：结束补花，进入“等待开金”
     * - 若有：currentFlowerPlayerIndex 指向下一家（按庄家逆时针顺序）
     */
    private void advanceFlowerTurnAfterPlayer() {
        int maxRounds = 10;
        int currentRound = gameState.getFlowerRoundCount();

        // 防御性：轮次过多时强行结束，避免极端情况
        if (currentRound >= maxRounds) {
            log.warn("补花轮次达到上限 {}，强制结束补花", maxRounds);
            finishFlowerPhaseAndWaitOpenGold();
            return;
        }

        List<Player> players = gameState.getPlayers();
        if (players == null || players.isEmpty()) {
            finishFlowerPhaseAndWaitOpenGold();
            return;
        }

        int size = players.size();
        int dealerIndex = gameState.getDealerIndex();

        // 从当前玩家的下一家开始，自动跳过所有“手上没有花”的玩家
        int nextIndex = (gameState.getCurrentFlowerPlayerIndex() + 1) % size;
        int loops = 0;
        boolean found = false;

        while (loops < size) {
            if (nextIndex == dealerIndex) {
                // 每次绕回庄家，视为新一轮开始
                currentRound++;
                if (currentRound >= maxRounds) {
                    log.warn("补花轮次达到上限 {}，强制结束补花", maxRounds);
                    finishFlowerPhaseAndWaitOpenGold();
                    return;
                }
            }

            Player p = players.get(nextIndex);
            if (!p.getFlowerTilesInHand().isEmpty()) {
                found = true;
                break;
            }

            nextIndex = (nextIndex + 1) % size;
            loops++;
        }

        if (!found) {
            // 整圈找不到任何有花的玩家，结束补花
            finishFlowerPhaseAndWaitOpenGold();
            return;
        }

        gameState.setFlowerRoundCount(currentRound);
        gameState.setCurrentFlowerPlayerIndex(nextIndex);
    }

    /**
     * 补花全部结束，进入“等待庄家开金”状态
     */
    private void finishFlowerPhaseAndWaitOpenGold() {
        gameState.setReplacingFlowers(false);
        gameState.setPhase(GamePhase.OPENING_GOLD);
        gameState.setWaitingOpenGold(true);
        // 这里不调用 openGoldTile()，等待庄家点击按钮
        log.info("所有人补花完毕，进入开金阶段，等待庄家点击“开金”");
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
            // 注意：此时花牌是从牌墙牌尾直接翻出的，并不在庄家手牌中，
            // 如果直接调用 replaceFlowerTile 会因为 removeTile 失败而无法计入花牌区。
            // 因此这里需要显式加入庄家的 flowerTiles，用于后续花胡/花数统计。
            if (dealer.getFlowerTiles() != null) {
                dealer.getFlowerTiles().add(goldTile);
            }
            
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
     * 庄家点击“开金”按钮。
     * 使用原有的 openGoldTile 逻辑，不改规则，只改时机。
     */
    public boolean playerOpenGold(String playerId) {
        if (gameState.getPhase() != GamePhase.OPENING_GOLD || !gameState.isWaitingOpenGold()) {
            log.warn("当前不在等待开金阶段，忽略开金请求");
            return false;
        }

        Player dealer = gameState.getDealer();
        if (dealer == null || !dealer.getId().equals(playerId)) {
            log.warn("只有庄家才能开金，当前请求玩家: {}", playerId);
            return false;
        }

        // 采用现有的开金逻辑（内部处理开到花 -> 算作庄家补花 的情况）
        openGoldTile();

        gameState.setWaitingOpenGold(false);

        // 开金完成，进入 PLAYING 阶段（逻辑从原 startHand 中迁移）
        gameState.setPhase(GamePhase.PLAYING);
        gameState.setCurrentPlayerIndex(gameState.getDealerIndex());

        // 开局阶段：庄家在首打前可判断“天胡/三金倒”
        // 三金倒优先级 > 天胡（最终以 WinValidator 的顺序保证）
        // 同时，重置抢金窗口（必须等庄家首打后才开启）
        gameState.setQiangJinWindowActive(false);
        gameState.setLastDrawnTile(null);
        gameState.setLastDrawPlayerIndex(-1);
        gameState.setLastDrawValidHandCountBefore(-1);
        Player d = gameState.getDealer();
        if (d != null) {
            checkAvailableActionsAfterDraw(d);
        }

        log.info("庄家 {} 点击开金完成，进入对局阶段，金牌={}", dealer.getName(), gameState.getGoldTile());
        return true;
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
        
        // 关键：在摸牌前检查是否满足抢金条件
        // 抢金条件：1) 抢金窗口已打开 2) 摸牌前暗牌数为16
        //          3) 摸牌前16张已听牌，且“听牌数 ≥ 2”（避免两张金起手时三金倒被当成抢金）
        boolean canQiangJinBeforeDraw = false;
        if (gameState.isQiangJinWindowActive() && validHandCountBefore == 16) {
            Tile goldTile = gameState.getGoldTile();
            if (goldTile != null) {
                // 检查当前16张是否听牌
                List<Tile> tingTiles = ActionChecker.getTingTiles(player, goldTile);
                // 仅当“听牌张数 ≥ 2”时才允许抢金，防止两张金起手直接三金倒被视作抢金
                canQiangJinBeforeDraw = tingTiles != null && tingTiles.size() >= 2;
                if (canQiangJinBeforeDraw) {
                    log.info("玩家 {} 摸牌前满足抢金条件：16张已听牌，听牌数={}", player.getName(),
                        tingTiles.size());
                }
            }
        }
        gameState.setCanQiangJinBeforeDraw(canQiangJinBeforeDraw);

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
        boolean canHuNormal = ActionChecker.canHu(player, null, goldTile, isQiangJin);

        boolean canSanJinDao = false;
        if (goldTile != null) {
            long goldCount = player.getHandTiles().stream().filter(t -> t.isSameAs(goldTile)).count();
            canSanJinDao = goldCount >= 3;
        }

        // 花胡：累计花牌数达到 20 张，直接胡牌（独立于普通牌型）
        boolean canHuaHu = isHuaHu(player);
        boolean canHu = canHuNormal || canHuaHu;

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
        
        log.debug("玩家 {} 摸牌后可用操作：暗杠={}, 胡={}, 三金倒={}, 花胡={}",
            player.getName(),
            canAnGang,
            canHu,
            canSanJinDao,
            canHuaHu);

        // 庄家首次再摸牌后，关闭抢金窗口（本局只开放这一轮的抢金）
        if (gameState.isQiangJinWindowActive() && player.getPosition() == gameState.getDealerIndex()) {
            gameState.setQiangJinWindowActive(false);
        }
    }

    /**
     * 抢金判定（本项目约定）：
     * - 在庄家首打之后开启抢金窗口；
     * - 任意玩家在"暗牌 16 张（不含花）"时进张，若进的是金牌，则可直接胡（抢金）；
     * - 三金倒优先级更高，由 WinValidator 保证最终裁决顺序。
     * 
     * 抢金触发条件（需同时满足）：
     * 1. 抢金窗口已打开（庄家首打后到庄家再次摸牌前）
     * 2. 摸牌前暗牌数为 16 张（不含花）
     * 3. 摸牌前已经听牌（getTingTiles 返回非空）
     * 
     * 注意：抢金必须在摸牌前完成判断，摸牌后直接使用摸牌前检查的结果。
     */
    private boolean isQiangJinForCurrentDraw(Player player, Tile goldTile) {
        if (player == null || goldTile == null) {
            return false;
        }
        if (gameState.getLastDrawPlayerIndex() != player.getPosition()) {
            return false;
        }
        Tile lastDrawn = gameState.getLastDrawnTile();
        if (lastDrawn == null || lastDrawn.getType() == TileType.FLOWER) {
            return false;
        }
        
        // 直接使用摸牌前检查的结果（在 playerDraw 中已设置）
        boolean canQiangJin = gameState.isCanQiangJinBeforeDraw();
        
        if (canQiangJin) {
            log.info("玩家 {} 触发抢金：摸牌前16张已听牌，摸到牌 {}", player.getName(), lastDrawn);
        }
        
        return canQiangJin;
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
        // 新的一轮“别人出牌后可操作”检测开始时，清空最近动作记录，
        // 防止上一轮吃/碰/杠/胡的提示残留到当前局面。
        gameState.setLastActionPlayerId(null);
        gameState.setLastActionType(null);

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
        // 重要：需要给所有能执行"最高优先级或更高优先级"操作的玩家设置操作信息
        // 例如：如果最高优先级是"碰"，那么能"胡"的玩家也应该能操作（因为"胡"优先级更高）
        int highestPriorityIndex = -1;
        for (int i = 0; i < actionTypes.length; i++) {
            if (actionTypes[i].equals(highestPriorityAction)) {
                highestPriorityIndex = i;
                break;
            }
        }
        
        for (Map.Entry<String, Map<String, Boolean>> entry : playerActionsMap.entrySet()) {
            String playerId = entry.getKey();
            Map<String, Boolean> actions = entry.getValue();
            
            // 找到对应的玩家
            Player player = findPlayerById(playerId);
            if (player == null) {
                continue;
            }
            
            // 检查玩家是否能执行最高优先级或更高优先级的操作
            boolean canExecuteHighestOrHigher = false;
            if (highestPriorityIndex >= 0) {
                // 检查从"胡"到最高优先级之间的所有操作
                for (int i = 0; i <= highestPriorityIndex; i++) {
                    String actionType = actionTypes[i];
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
                        canExecuteHighestOrHigher = true;
                        break;
                    }
                }
            }
            
            // 如果玩家可以执行最高优先级或更高优先级的操作，设置操作信息
            if (canExecuteHighestOrHigher) {
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
            // 如果是胡牌，需要按照"下家 > 对家 > 上家"的优先级排序
            if ("hu".equals(highestPriorityAction)) {
                // 按照优先级顺序排序：下家 > 对家 > 上家
                List<String> sortedHuPlayers = sortHuPlayersByPriority(
                    highestPriorityPlayers, discardPlayerIndex);
                if (!sortedHuPlayers.isEmpty()) {
                    String firstPlayerId = sortedHuPlayers.get(0);
                    gameState.setCurrentActionPlayerId(firstPlayerId);
                    gameState.setCurrentActionType(highestPriorityAction);
                    Player firstPlayer = findPlayerById(firstPlayerId);
                    if (firstPlayer != null) {
                        log.info("设置优先级操作：玩家 {} 可以 {}（最高优先级，按位置优先级：下家>对家>上家）", 
                            firstPlayer.getName(), highestPriorityAction);
                    }
                }
            } else {
                // 其他操作（杠/碰/吃）仍然按逆时针顺序
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

        // 记录最近一次已执行的动作，供前端在玩家头像旁弹出“吃”提示
        gameState.setLastActionPlayerId(player.getId());
        gameState.setLastActionType("chi");
        
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

        // 记录最近一次已执行的动作：碰
        gameState.setLastActionPlayerId(player.getId());
        gameState.setLastActionType("peng");
        
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

        // 记录最近一次已执行的动作：明杠
        gameState.setLastActionPlayerId(player.getId());
        gameState.setLastActionType("gang");
        
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

        // 记录最近一次已执行的动作：暗杠
        gameState.setLastActionPlayerId(player.getId());
        gameState.setLastActionType("anGang");

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
            
            // 点炮时，需要检查优先级：只有优先级最高的玩家才能胡
            // 如果当前玩家不是优先级最高的，拒绝胡牌
            if (!isHighestPriorityHuPlayer(playerId, lastDiscardPlayerIndex)) {
                log.warn("玩家 {} 不能胡牌：存在优先级更高的玩家可以胡（点炮优先级：下家>对家>上家）", 
                    player.getName());
                return false;
            }
        }

        boolean isQiangJin = false;
        if (isZiMo) {
            isQiangJin = isQiangJinForCurrentDraw(player, gameState.getGoldTile());
        }
        boolean canHuNormal = ActionChecker.canHu(player, tileForHuCheck,
            gameState.getGoldTile(), isQiangJin);
        boolean isHuaHu = isHuaHu(player);
        boolean canHu = canHuNormal || isHuaHu;
        
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

        // 依据当前局面与规则，判定本次胡牌的“牌型类型”标签
        String winType = determineWinType(player, isZiMo, isQiangJin);
        gameState.setLastWinPlayerId(player.getId());
        gameState.setLastWinType(winType);

        // 按当前规则进行一局结算（一个人赢三家赔）
        applyScoring(player, isZiMo, winType);

        // 记录最近一次已执行的动作：胡
        gameState.setLastActionPlayerId(player.getId());
        gameState.setLastActionType("hu");

        log.info("玩家 {} 胡牌！类型={}", player.getName(), winType);
        finishHand(player.getId());
        
        return true;
    }

    /**
     * 根据当前局面判定胡牌类型（只记录一个最终类型标签）
     *
     * 优先级：
     * 清一色 > 混一色 > 金龙 > 金雀 > 三金倒 > 无花无杠 > 天胡 > 抢金 > 花胡 > 一张花 > 自摸 > 胡
     */
    private String determineWinType(Player player, boolean isZiMo, boolean isQiangJin) {
        Tile goldTile = gameState.getGoldTile();

        // 统计手牌中金牌数量（包含点炮牌已加入后的完整胡牌牌组）
        int goldCount = 0;
        for (Tile t : player.getHandTiles()) {
            if (goldTile != null && t.isSameAs(goldTile)) {
                goldCount++;
            }
        }

        // 统计花牌与杠
        int flowerCount = player.getFlowerTiles() == null ? 0 : player.getFlowerTiles().size();
        boolean hasKong = false;
        if (player.getExposedMelds() != null) {
            for (List<Tile> meld : player.getExposedMelds()) {
                if (meld != null && meld.size() == 4) {
                    hasKong = true;
                    break;
                }
            }
        }
        if (!hasKong && player.getConcealedKongs() != null && !player.getConcealedKongs().isEmpty()) {
            hasKong = true;
        }

        boolean noFlowerNoKong = flowerCount == 0 && !hasKong;
        boolean oneFlower = flowerCount == 1;
        boolean isHuaHu = flowerCount >= 20;

        // 自摸场景下，先判断“是否满足正常牌型胡”（不包含花胡等特殊判定）
        boolean canHuNormalSelf = false;
        if (isZiMo) {
            canHuNormalSelf = ActionChecker.canHu(player, null, goldTile, isQiangJin);
        }

        // 天胡：庄家开局17张、未有任何出牌且直接“正常牌型自摸胡”
        // 注意：如果只是靠 20 张花牌达成花胡，不应计为天胡。
        boolean isTianHu = isZiMo
                && player.isDealer()
                && gameState.getDiscardedTiles().isEmpty()
                && canHuNormalSelf;

        // 三金倒：自摸且手上至少三张金（包括庄家起手17张三金）
        boolean isSanJinDao = isZiMo && goldCount >= 3;

        // 花色统计（用于清一色 / 混一色）
        // 忽略花牌和字牌（字牌在福州麻将中也是花），只看所有参与胡牌的牌（手牌 + 明牌 + 暗杠）
        // 注意：统计花色时排除金牌，因为金牌在混一色中被当作"花牌"处理，不应影响花色判断
        Set<Integer> suitSet = new HashSet<>(); // 0:万,1:条,2:饼

        // 收集所有非花牌（手牌），排除金牌
        for (Tile t : player.getHandTiles()) {
            if (t == null || t.getType() == TileType.FLOWER) continue;
            // 排除金牌：金牌在混一色中被当作"花牌"处理，不应影响花色判断
            if (goldTile != null && t.isSameAs(goldTile)) continue;
            int idx = mapSuitIndex(t.getType());
            if (idx >= 0) {
                suitSet.add(idx);
            }
            // 字牌（WIND/DRAGON）在福州麻将中是花牌，直接跳过
        }
        // 明牌，排除金牌
        if (player.getExposedMelds() != null) {
            for (List<Tile> meld : player.getExposedMelds()) {
                if (meld == null) continue;
                for (Tile t : meld) {
                    if (t == null || t.getType() == TileType.FLOWER) continue;
                    // 排除金牌
                    if (goldTile != null && t.isSameAs(goldTile)) continue;
                    int idx = mapSuitIndex(t.getType());
                    if (idx >= 0) {
                        suitSet.add(idx);
                    }
                    // 字牌（WIND/DRAGON）在福州麻将中是花牌，直接跳过
                }
            }
        }
        // 暗杠，排除金牌
        if (player.getConcealedKongs() != null) {
            for (List<Tile> kong : player.getConcealedKongs()) {
                if (kong == null) continue;
                for (Tile t : kong) {
                    if (t == null || t.getType() == TileType.FLOWER) continue;
                    // 排除金牌
                    if (goldTile != null && t.isSameAs(goldTile)) continue;
                    int idx = mapSuitIndex(t.getType());
                    if (idx >= 0) {
                        suitSet.add(idx);
                    }
                    // 字牌（WIND/DRAGON）在福州麻将中是花牌，直接跳过
                }
            }
        }

        boolean hasSuit = !suitSet.isEmpty();
        boolean singleSuit = suitSet.size() == 1;

        // 混一色：同一花色 + 字牌，同时包含金牌
        // 这里按照常见福州麻将习惯进行近似：不追踪金牌是否“代替其他花色”。
        // 混一色：同一花色 + 有金（且金不是该花色）
        // 优先级：清一色 > 混一色 > 金龙 > 金雀
        if (hasSuit && singleSuit && goldCount > 0 && goldTile != null) {
            // 检查金的花色：如果金是万/条/饼，则不能是手牌的花色
            int goldSuitIndex = mapSuitIndex(goldTile.getType());
            // 如果金是字牌/花牌（goldSuitIndex == -1），或者金的花色与手牌花色不同，则符合混一色条件
            if (goldSuitIndex < 0 || !suitSet.contains(goldSuitIndex)) {
                return "混一色";
            }
        }

        // 清一色：所有非花牌都在同一花色（且金牌也是该花色，或者没有金牌）
        if (hasSuit && singleSuit) {
            return "清一色";
        }

        // 按照优先级判断：金龙 > 金雀 > 三金倒 > 抢金 > 无花无杠
        
        // 先检查金龙：至少3张金，且去掉3张金后剩下的牌能组成胡牌
        if (goldCount >= 3 && isSanJinDao) {
            if (canWinWithoutThreeGolds(player, goldTile)) {
                return "金龙";
            }
            // 如果不是金龙，但有3张金且自摸，则是三金倒
            return "三金倒";
        }
        
        // 再检查金雀：2张金做对子，金不代替任何牌
        // 需要验证：去掉两张金后，剩下的牌能组成标准胡牌（不使用金补）
        if (goldCount == 2 && isJinQue(player, goldTile)) {
            return "金雀";
        }

        // 抢金：优先级放在三金倒之后、无花无杠之前
        if (isQiangJin) {
            return "抢金";
        }

        // 无花无杠：胡牌时既无花牌也无任何杠
        if (noFlowerNoKong) {
            return "无花无杠";
        }

        // 天胡
        if (isTianHu) {
            return "天胡";
        }

        // 花胡：补到/抓到花累计达到 20 张
        if (isHuaHu) {
            return "花胡";
        }

        // 一张花：胡牌时刚好只有一张花
        if (oneFlower) {
            return "一张花";
        }

        // 自摸（非以上特殊牌型）
        if (isZiMo) {
            return "自摸";
        }

        // 普通胡（平胡）
        return "胡";
    }

    /**
     * 检查是否是金雀：用两张金做对子，金不代替任何牌
     * 条件：去掉两张金后，剩下的牌能组成标准胡牌（不使用金补）
     * 注意：去掉2张金后，剩下的牌应该是15张（3n），能组成5个面子
     */
    private boolean isJinQue(Player player, Tile goldTile) {
        if (player == null || goldTile == null) {
            return false;
        }
        
        // 复制手牌列表
        List<Tile> handTiles = new ArrayList<>(player.getHandTiles());
        
        // 去掉2张金牌
        int removed = 0;
        Iterator<Tile> it = handTiles.iterator();
        while (it.hasNext() && removed < 2) {
            Tile tile = it.next();
            if (tile.isSameAs(goldTile)) {
                it.remove();
                removed++;
            }
        }
        
        // 如果去掉2张金后不是15张，不能是金雀
        // 过滤掉花牌
        List<Tile> validTiles = new ArrayList<>();
        for (Tile tile : handTiles) {
            if (tile.getType() != com.fzmahjong.model.TileType.FLOWER) {
                validTiles.add(tile);
            }
        }
        
        // 如果去掉2张金后不是15张，不能是金雀
        if (validTiles.size() != 15) {
            return false;
        }
        
        // 统计牌的数量
        int[][] counts = new int[5][10]; // 0:万,1:条,2:饼,3:风,4:箭; 点数用1..9
        for (Tile tile : validTiles) {
            int typeIndex = mapTypeIndexForWinValidator(tile.getType());
            int value = tile.getValue();
            if (value >= 1 && value <= 9 && typeIndex >= 0) {
                counts[typeIndex][value]++;
            }
        }
        
        // 检查能否组成5个面子（不使用金补）
        return canFormFiveMelds(counts);
    }
    
    /**
     * 检查能否用给定的牌组成5个面子（不使用金补）
     */
    private boolean canFormFiveMelds(int[][] counts) {
        // 使用递归检查能否组成5个面子
        return canFormFiveMeldsRecursive(counts, 0);
    }
    
    /**
     * 递归检查能否组成5个面子
     */
    private boolean canFormFiveMeldsRecursive(int[][] counts, int meldCount) {
        // 如果已经组成5个面子，检查是否所有牌都用完了
        if (meldCount == 5) {
            for (int i = 0; i < counts.length; i++) {
                for (int j = 1; j <= 9; j++) {
                    if (counts[i][j] > 0) {
                        return false;
                    }
                }
            }
            return true;
        }
        
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
        
        // 如果没有牌了，但还没组成5个面子，返回false
        if (type == -1) {
            return false;
        }
        
        // 尝试一：以 (type, value) 组刻子 AAA
        if (counts[type][value] >= 3) {
            counts[type][value] -= 3;
            if (canFormFiveMeldsRecursive(counts, meldCount + 1)) {
                counts[type][value] += 3;
                return true;
            }
            counts[type][value] += 3;
        }
        
        // 尝试二：如果是万/条/饼，尝试顺子 ABC
        if (isShunziTypeForJinQue(type) && value <= 7) {
            if (counts[type][value] > 0 && counts[type][value + 1] > 0 && counts[type][value + 2] > 0) {
                counts[type][value]--;
                counts[type][value + 1]--;
                counts[type][value + 2]--;
                if (canFormFiveMeldsRecursive(counts, meldCount + 1)) {
                    counts[type][value]++;
                    counts[type][value + 1]++;
                    counts[type][value + 2]++;
                    return true;
                }
                counts[type][value]++;
                counts[type][value + 1]++;
                counts[type][value + 2]++;
            }
        }
        
        return false;
    }
    
    /**
     * 将 TileType 映射到内部计数数组的下标（用于WinValidator）
     */
    private int mapTypeIndexForWinValidator(com.fzmahjong.model.TileType type) {
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
    private boolean isShunziTypeForJinQue(int typeIndex) {
        return typeIndex == 0 || typeIndex == 1 || typeIndex == 2;
    }

    /**
     * 检查去掉3张金后，剩下的牌能否组成胡牌（用于判断是否是金龙）
     * 金龙的条件：不仅要有三个金，剩下的3n+2手牌也要是胡的才行
     */
    private boolean canWinWithoutThreeGolds(Player player, Tile goldTile) {
        if (player == null || goldTile == null) {
            return false;
        }
        
        // 复制手牌列表
        List<Tile> handTiles = new ArrayList<>(player.getHandTiles());
        
        // 去掉3张金牌
        int removed = 0;
        Iterator<Tile> it = handTiles.iterator();
        while (it.hasNext() && removed < 3) {
            Tile tile = it.next();
            if (tile.isSameAs(goldTile)) {
                it.remove();
                removed++;
            }
        }
        
        // 如果去掉3张金后，剩下的牌能组成胡牌，就是金龙
        // 注意：这里不传 isQiangJin，因为抢金是另一种胡牌方式
        return WinValidator.canWin(handTiles, goldTile, false);
    }

    /**
     * 将 TileType 映射为“可组成顺子”的花色下标（与 WinValidator 中类似，但用于胡型统计）
     */
    private int mapSuitIndex(TileType type) {
        switch (type) {
            case WAN:
                return 0;
            case TIAO:
                return 1;
            case BING:
                return 2;
            default:
                return -1;
        }
    }

    /**
     * 花胡判定：当前玩家累计补到/持有的花牌数量达到 20 张。
     * 这里按“玩家面前的花牌区”来统计（player.getFlowerTiles），
     * 既包括起手补花，也包括对局过程中补到的所有花。
     */
    private boolean isHuaHu(Player player) {
        if (player == null || player.getFlowerTiles() == null) {
            return false;
        }
        return player.getFlowerTiles().size() >= 20;
    }

    /**
     * 根据当前规则为本局胡牌进行计分，并把分数直接累加到各玩家的 Player.score 上。
     *
     * 规则（一个人赢三家赔）：
     * - 基础分：底 + 花 + 金 + 杠
     *   - 花：每张 1 分（使用玩家面前的花牌区）
     *   - 杠：明杠每个 1 分，暗杠每个 2 分
     *   - 金：每张 1 分（手牌/明牌/暗杠中所有金牌）
     *   - 底：按庄数计，1 庄 = 1 分，2 庄 = 2 分，3 庄及以上封顶为 3 分
     * - 自摸： (底 + 花 + 金 + 杠) × 2
     * - 特殊胡牌：底 + 花 + 金 + 特殊牌分数（不再额外计算杠分，也不区分是否自摸）
     *
     * 说明：
     * - 这里采用“庄数 = 连庄次数 + 1，最多 3”的近似：连庄 0/1/2+ 分别对应底 1/2/3。
     * - 金牌计数时，不统计花牌区，只统计参与胡牌的牌（手牌 + 明牌 + 暗杠）。
     */
    private void applyScoring(Player winner, boolean isZiMo, String winType) {
        if (winner == null || gameState.getPlayers() == null || gameState.getPlayers().isEmpty()) {
            return;
        }

        // 1. 底分：按连庄次数 + 1 计算，最多 3 分
        int dealerBase = Math.min(gameState.getConsecutiveDealerWins() + 1, 3);

        // 2. 花分：玩家面前花牌数量（包括起手和对局中补到的所有花）
        int flowerCount = winner.getFlowerTiles() == null ? 0 : winner.getFlowerTiles().size();

        // 3. 金分：手牌 + 明牌 + 暗杠中的所有金
        Tile goldTile = gameState.getGoldTile();
        int goldCount = 0;
        if (goldTile != null) {
            // 手牌
            for (Tile t : winner.getHandTiles()) {
                if (t != null && t.isSameAs(goldTile)) {
                    goldCount++;
                }
            }
            // 明牌
            if (winner.getExposedMelds() != null) {
                for (List<Tile> meld : winner.getExposedMelds()) {
                    if (meld == null) continue;
                    for (Tile t : meld) {
                        if (t != null && t.isSameAs(goldTile)) {
                            goldCount++;
                        }
                    }
                }
            }
            // 暗杠
            if (winner.getConcealedKongs() != null) {
                for (List<Tile> kong : winner.getConcealedKongs()) {
                    if (kong == null) continue;
                    for (Tile t : kong) {
                        if (t != null && t.isSameAs(goldTile)) {
                            goldCount++;
                        }
                    }
                }
            }
        }

        // 4. 杠分：明杠每个 1 分，暗杠每个 2 分
        int mingGangCount = 0;
        if (winner.getExposedMelds() != null) {
            for (List<Tile> meld : winner.getExposedMelds()) {
                if (meld != null && meld.size() == 4) {
                    mingGangCount++;
                }
            }
        }
        int anGangCount = winner.getConcealedKongs() == null ? 0 : winner.getConcealedKongs().size();
        int gangScore = mingGangCount * 1 + anGangCount * 2;

        // 5. 特殊牌型分数表
        Map<String, Integer> specialScore = new HashMap<>();
        specialScore.put("天胡", 30);
        specialScore.put("抢金", 30);
        specialScore.put("无花无杠", 30);
        specialScore.put("一张花", 15);
        specialScore.put("花胡", 20);
        specialScore.put("三金倒", 40);
        specialScore.put("金雀", 60);
        specialScore.put("金龙", 120);
        specialScore.put("混一色", 120);
        specialScore.put("清一色", 240);

        int singlePay;
        if (winType != null && specialScore.containsKey(winType)) {
            // 特殊胡牌：底 + 花 + 金 + 特殊牌分数（不额外计杠分，也不乘自摸）
            singlePay = dealerBase + flowerCount + goldCount + specialScore.get(winType);
        } else {
            int base = dealerBase + flowerCount + goldCount + gangScore;
            if (isZiMo) {
                singlePay = base * 2;
            } else {
                singlePay = base;
            }
        }

        if (singlePay <= 0) {
            return;
        }

        // 6. 一个赢三家赔：三家各付 singlePay，赢家收 3 * singlePay
        int totalGain = 0;
        for (Player p : gameState.getPlayers()) {
            if (p == null) continue;
            if (p.getId().equals(winner.getId())) {
                continue;
            }
            p.setScore(p.getScore() - singlePay);
            totalGain += singlePay;
        }
        winner.setScore(winner.getScore() + totalGain);

        log.info("本局结算：赢家={}，类型={}，自摸={}，单家赔付={}，赢家本局进账={}，当前总分={}",
                winner.getName(), winType, isZiMo, singlePay, totalGain, winner.getScore());
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

        // 不需要确认：单局结束，但暂不立刻开下一局，交由控制器在短暂停留后调用 startNextHand。
        // 这样可以给前端保留一段时间展示胡牌结果与结算信息。
        gameState.setPhase(GamePhase.HAND_FINISHED);
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

        // 全员继续：立刻开下一局（此处属于轮庄确认后的新一圈开始，不需要额外的 5 秒停顿）
        startHand();
        return true;
    }

    /**
     * 由控制器在“单局结束并短暂停留”之后显式调用，真正开始下一局。
     * 仅当当前阶段为 HAND_FINISHED 时才会生效，避免被误调用。
     */
    public void startNextHand() {
        if (gameState.getPhase() != GamePhase.HAND_FINISHED) {
            log.warn("当前阶段不是 HAND_FINISHED，忽略 startNextHand 调用，当前阶段={}", gameState.getPhase());
            return;
        }
        log.info("控制器触发 startNextHand，开始新的一局");
        startHand();
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
            
            // 如果是胡牌，需要按照"下家 > 对家 > 上家"的优先级顺序查找下一个玩家
            if ("hu".equals(actionType)) {
                // 收集所有可以胡的玩家
                List<String> canHuPlayers = new ArrayList<>();
                for (Player p : gameState.getPlayers()) {
                    if (p == null || p.getPosition() == discardPlayerIndex) {
                        continue; // 跳过出牌的玩家
                    }
                    Map<String, Object> actions = gameState.getPlayerActions(p.getId());
                    if (!actions.isEmpty() && Boolean.TRUE.equals(actions.get("canHu"))) {
                        canHuPlayers.add(p.getId());
                    }
                }
                
                // 按照优先级排序：下家 > 对家 > 上家
                List<String> sortedHuPlayers = sortHuPlayersByPriority(canHuPlayers, discardPlayerIndex);
                
                // 找到当前玩家在排序列表中的位置，下一个就是优先级次高的玩家
                int currentIndex = sortedHuPlayers.indexOf(playerId);
                if (currentIndex >= 0 && currentIndex < sortedHuPlayers.size() - 1) {
                    String nextPlayerId = sortedHuPlayers.get(currentIndex + 1);
                    Player nextPlayer = findPlayerById(nextPlayerId);
                    if (nextPlayer != null) {
                        gameState.setCurrentActionPlayerId(nextPlayerId);
                        gameState.setCurrentActionType(actionType);
                        log.info("玩家 {} 过，轮到玩家 {} 执行 {}（按位置优先级：下家>对家>上家）", 
                            player.getName(), nextPlayer.getName(), actionType);
                        return true;
                    }
                }
                // 如果当前玩家是最后一个，或者找不到下一个，继续检查下一个优先级
            } else {
                // 其他操作（杠/碰/吃）仍然按逆时针顺序查找
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
    
    /**
     * 按照"下家 > 对家 > 上家"的优先级对可以胡牌的玩家列表进行排序
     * @param playerIds 可以胡牌的玩家ID列表
     * @param discardPlayerIndex 出牌玩家的索引
     * @return 按优先级排序后的玩家ID列表
     */
    private List<String> sortHuPlayersByPriority(List<String> playerIds, int discardPlayerIndex) {
        if (playerIds == null || playerIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 计算各玩家的优先级：下家=1（最高），对家=2，上家=3
        Map<String, Integer> priorityMap = new HashMap<>();
        for (String playerId : playerIds) {
            Player p = findPlayerById(playerId);
            if (p == null) continue;
            
            int playerIndex = p.getPosition();
            int relativePosition = (playerIndex - discardPlayerIndex + 4) % 4;
            
            // 相对位置：1=下家，2=对家，3=上家（0是出牌者自己，不应该出现）
            int priority;
            if (relativePosition == 1) {
                priority = 1; // 下家，最高优先级
            } else if (relativePosition == 2) {
                priority = 2; // 对家
            } else if (relativePosition == 3) {
                priority = 3; // 上家
            } else {
                priority = 999; // 其他情况（不应该出现）
            }
            priorityMap.put(playerId, priority);
        }
        
        // 按照优先级排序
        List<String> sorted = new ArrayList<>(playerIds);
        sorted.sort((id1, id2) -> {
            int p1 = priorityMap.getOrDefault(id1, 999);
            int p2 = priorityMap.getOrDefault(id2, 999);
            return Integer.compare(p1, p2);
        });
        
        return sorted;
    }
    
    /**
     * 检查当前玩家是否是优先级最高的可以胡牌的玩家
     * @param playerId 当前玩家ID
     * @param discardPlayerIndex 出牌玩家的索引
     * @return 如果是优先级最高的玩家，返回true；否则返回false
     */
    private boolean isHighestPriorityHuPlayer(String playerId, int discardPlayerIndex) {
        // 收集所有可以胡的玩家
        List<String> canHuPlayers = new ArrayList<>();
        for (Player p : gameState.getPlayers()) {
            if (p == null || p.getPosition() == discardPlayerIndex) {
                continue; // 跳过出牌的玩家
            }
            Map<String, Object> actions = gameState.getPlayerActions(p.getId());
            if (!actions.isEmpty() && Boolean.TRUE.equals(actions.get("canHu"))) {
                canHuPlayers.add(p.getId());
            }
        }
        
        if (canHuPlayers.isEmpty()) {
            return true; // 没有其他玩家可以胡，当前玩家可以胡
        }
        
        // 按照优先级排序
        List<String> sortedHuPlayers = sortHuPlayersByPriority(canHuPlayers, discardPlayerIndex);
        
        // 检查当前玩家是否是第一个（优先级最高的）
        return !sortedHuPlayers.isEmpty() && sortedHuPlayers.get(0).equals(playerId);
    }
    
    public GameState getGameState() {
        return gameState;
    }
}
