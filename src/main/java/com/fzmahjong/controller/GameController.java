package com.fzmahjong.controller;

import com.fzmahjong.engine.GameEngine;
import com.fzmahjong.model.GamePhase;
import com.fzmahjong.model.GameState;
import com.fzmahjong.model.Player;
import com.fzmahjong.model.Tile;
import com.fzmahjong.service.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏控制器
 */
@Controller
public class GameController {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    private final RoomManager roomManager;
    private final SimpMessagingTemplate messagingTemplate;

    public GameController(RoomManager roomManager, SimpMessagingTemplate messagingTemplate) {
        this.roomManager = roomManager;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 创建房间
     */
    @PostMapping("/api/room/create")
    @ResponseBody
    public Map<String, String> createRoom() {
        String roomId = roomManager.createRoom();
        Map<String, String> response = new HashMap<>();
        response.put("roomId", roomId);
        return response;
    }

    /**
     * 加入房间
     */
    @PostMapping("/api/room/join")
    @ResponseBody
    public Map<String, Object> joinRoom(@RequestBody JoinRoomRequest request) {
        boolean success = roomManager.joinRoom(
            request.getRoomId(),
            request.getPlayerId(),
            request.getPlayerName()
        );
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        
        if (success) {
            // 延迟广播，给新玩家的WebSocket连接留一点时间
            // 即使新玩家错过这次广播，也会在WebSocket连接后通过 /game/sync 主动同步
            new Thread(() -> {
                try {
                    Thread.sleep(500); // 延迟500毫秒
                    GameEngine engine = roomManager.getEngine(request.getRoomId());
                    if (engine != null) {
                        broadcastGameState(request.getRoomId(), engine.getGameState());
                        log.info("延迟广播游戏状态完成");
                    }
                } catch (InterruptedException e) {
                    log.error("延迟广播被中断", e);
                }
            }).start();
        }
        
        return response;
    }

    /**
     * 玩家出牌
     */
    @MessageMapping("/game/discard")
    public void discard(@Payload DiscardRequest request) {
        log.info("收到出牌请求：玩家={}, 牌={}", request.getPlayerId(), request.getTileId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            log.warn("玩家不在任何房间中");
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            log.warn("房间不存在");
            return;
        }
        
        boolean success = engine.playerDiscard(request.getPlayerId(), request.getTileId());
        
        if (success) {
            GameState gameState = engine.getGameState();
            
            // 检查是否有玩家可以吃碰杠胡
            boolean hasAvailableActions = gameState.getCurrentActionPlayerId() != null;
            
            if (!hasAvailableActions) {
                // 没有玩家可以吃碰杠胡：检查下家是否有暗杠，否则进入下一轮摸牌
                handleNextPlayerAfterNoOneCanActionOrAllPassed(engine, gameState, "出牌后无人可操作");
            }
            
            // 广播游戏状态
            broadcastGameState(roomId, gameState);
        }
    }

    /**
     * 玩家抓牌
     */
    @MessageMapping("/game/draw")
    public void draw(@Payload DrawRequest request) {
        log.info("收到抓牌请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerDraw(request.getPlayerId());
        
        if (success) {
            GameState state = engine.getGameState();
            broadcastGameState(roomId, state);

            // 如果因为流局等原因导致本局结束，但无需轮庄确认，则在短暂停留后自动开下一局
            if (state.getPhase() == GamePhase.HAND_FINISHED) {
                startNextHandWithDelay(roomId, engine);
            }
        }
    }

    /**
     * 同步游戏状态（用于玩家刚连接WebSocket时获取最新状态）
     */
    @MessageMapping("/game/sync")
    public void syncGameState(@Payload SyncRequest request) {
        log.info("收到同步请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            log.warn("玩家不在任何房间中");
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            log.warn("房间不存在");
            return;
        }
        
        // 广播当前游戏状态
        broadcastGameState(roomId, engine.getGameState());
        log.info("已为玩家 {} 同步游戏状态", request.getPlayerId());
    }

    /**
     * 玩家吃牌
     */
    @MessageMapping("/game/chi")
    public void chi(@Payload ChiRequest request) {
        log.info("收到吃牌请求：玩家={}, 牌1={}, 牌2={}", 
            request.getPlayerId(), request.getTileId1(), request.getTileId2());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerChi(request.getPlayerId(), 
            request.getTileId1(), request.getTileId2());
        
        if (success) {
            // 吃牌后，该玩家需要出牌
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 玩家碰牌
     */
    @MessageMapping("/game/peng")
    public void peng(@Payload ActionRequest request) {
        log.info("收到碰牌请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerPeng(request.getPlayerId());
        
        if (success) {
            // 碰牌后，该玩家需要出牌
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 玩家杠牌（明杠）
     */
    @MessageMapping("/game/gang")
    public void gang(@Payload ActionRequest request) {
        log.info("收到杠牌请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerGang(request.getPlayerId());
        
        if (success) {
            // playerGang 内部已经处理了摸牌逻辑，这里只需要广播状态
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 玩家暗杠
     */
    @MessageMapping("/game/anGang")
    public void anGang(@Payload AnGangRequest request) {
        log.info("收到暗杠请求：玩家={}, 牌={}", request.getPlayerId(), request.getTileId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerAnGang(request.getPlayerId(), request.getTileId());
        
        if (success) {
            // playerAnGang 内部已经处理了摸牌逻辑，这里只需要广播状态
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 玩家胡牌
     */
    @MessageMapping("/game/hu")
    public void hu(@Payload ActionRequest request) {
        log.info("收到胡牌请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerHu(request.getPlayerId());
        
        if (success) {
            GameState state = engine.getGameState();
            broadcastGameState(roomId, state);

            // 若单局结束且不需要轮庄确认，为了给前端预留结算/胡牌展示时间，
            // 延迟约 5 秒再自动开下一局。
            if (state.getPhase() == GamePhase.HAND_FINISHED) {
                startNextHandWithDelay(roomId, engine);
            }
        }
    }

    /**
     * 玩家过（不进行任何操作）
     */
    @MessageMapping("/game/pass")
    public void pass(@Payload ActionRequest request) {
        log.info("收到过牌请求：玩家={}", request.getPlayerId());
        
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }
        
        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }
        
        boolean success = engine.playerPass(request.getPlayerId());
        
        if (success) {
            GameState gameState = engine.getGameState();
            
            // 检查是否还有玩家需要执行操作
            if (gameState.getCurrentActionPlayerId() == null) {
                // 所有玩家都过了：检查下家是否有暗杠，否则进入下一轮摸牌
                handleNextPlayerAfterNoOneCanActionOrAllPassed(engine, gameState, "所有玩家都过");
            }
            
            broadcastGameState(roomId, gameState);
        }
    }

    /**
     * 轮庄一圈后：确认是否继续对局
     */
    @MessageMapping("/game/continue")
    public void confirmContinue(@Payload ContinueRequest request) {
        log.info("收到继续对局确认：玩家={}, continue={}", request.getPlayerId(), request.isContinue());

        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }

        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }

        boolean success = engine.playerContinue(request.getPlayerId(), request.isContinue());
        if (success) {
            GameState state = engine.getGameState();
            broadcastGameState(roomId, state);

            // 如果对局阶段已经被置为 FINISHED，说明已有玩家选择“End”，
            // 此时直接解散房间，后续该房间将不再接收任何请求。
            if (state.getPhase() == com.fzmahjong.model.GamePhase.FINISHED) {
                roomManager.destroyRoom(roomId);
                log.info("收到 End 选择后，已解散房间 {}", roomId);
            }
        }
    }

    /**
     * 手动补花：补花阶段，当前轮到的玩家点击“补花”按钮。
     */
    @MessageMapping("/game/replaceFlower")
    public void replaceFlower(@Payload ReplaceFlowerRequest request) {
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }

        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }

        boolean success = engine.playerReplaceFlowers(request.getPlayerId());
        if (success) {
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 开金：补花全部完成后，只允许庄家点击“开金”按钮。
     */
    @MessageMapping("/game/openGold")
    public void openGold(@Payload OpenGoldRequest request) {
        String roomId = roomManager.getRoomIdByPlayerId(request.getPlayerId());
        if (roomId == null) {
            return;
        }

        GameEngine engine = roomManager.getEngine(roomId);
        if (engine == null) {
            return;
        }

        boolean success = engine.playerOpenGold(request.getPlayerId());
        if (success) {
            broadcastGameState(roomId, engine.getGameState());
        }
    }

    /**
     * 广播游戏状态（过滤敏感信息）
     */
    private void broadcastGameState(String roomId, GameState gameState) {
        // 广播公共信息
        Map<String, Object> publicView = buildPublicView(gameState);
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomId,
            publicView
        );

        // 为每个玩家发送定制化的游戏状态（只能看到自己的手牌 + 自己的暗杠）
        for (Player player : gameState.getPlayers()) {
            Map<String, Object> playerView = buildPlayerView(gameState, player.getId());
            messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/player/" + player.getId(),
                playerView
            );
        }
    }

    /**
     * 构建玩家视图（包含自己的手牌）
     */
    private Map<String, Object> buildPlayerView(GameState gameState, String playerId) {
        Map<String, Object> view = new HashMap<>();

        Player currentPlayer = gameState.getPlayers().stream()
            .filter(p -> p.getId().equals(playerId))
            .findFirst()
            .orElse(null);

        if (currentPlayer != null) {
            view.put("myHandTiles", currentPlayer.getHandTiles());
            view.put("myFlowerTiles", currentPlayer.getFlowerTiles());
            // 自己视角：明牌 + 暗杠都要看得到
            java.util.List<java.util.List<Tile>> myAllMelds = new java.util.ArrayList<>();
            if (currentPlayer.getExposedMelds() != null) {
                myAllMelds.addAll(currentPlayer.getExposedMelds());
            }
            if (currentPlayer.getConcealedKongs() != null) {
                myAllMelds.addAll(currentPlayer.getConcealedKongs());
            }
            view.put("myExposedMelds", myAllMelds);

            // 添加可用操作信息
            Map<String, Object> availableActions = gameState.getPlayerActions(playerId);
            view.put("availableActions", availableActions);
        }
        
        view.putAll(buildPublicView(gameState));
        
        return view;
    }

    /**
     * 构建公共视图（所有玩家都能看到的信息）
     */
    private Map<String, Object> buildPublicView(GameState gameState) {
        Map<String, Object> view = new HashMap<>();
        
        view.put("roomId", gameState.getRoomId());
        view.put("phase", gameState.getPhase());
        view.put("currentPlayerIndex", gameState.getCurrentPlayerIndex());
        view.put("dealerIndex", gameState.getDealerIndex());
        view.put("consecutiveDealerWins", gameState.getConsecutiveDealerWins());
        view.put("dealerChangesSinceCycleStart", gameState.getDealerChangesSinceCycleStart());
        view.put("continueDecisions", gameState.getContinueDecisions());
        view.put("goldTile", gameState.getGoldTile());
        // 补花 / 开金阶段状态
        view.put("replacingFlowers", gameState.isReplacingFlowers());
        view.put("currentFlowerPlayerIndex", gameState.getCurrentFlowerPlayerIndex());
        view.put("flowerRoundCount", gameState.getFlowerRoundCount());
        view.put("waitingOpenGold", gameState.isWaitingOpenGold());
        // 最近一次摸到/补到的最终有效牌（非花）及其相关信息
        view.put("lastDrawnTile", gameState.getLastDrawnTile());
        view.put("lastDrawPlayerIndex", gameState.getLastDrawPlayerIndex());
        view.put("lastDrawValidHandCountBefore", gameState.getLastDrawValidHandCountBefore());
        view.put("lastDiscardedTile", gameState.getLastDiscardedTile());
        view.put("discardedTiles", gameState.getDiscardedTiles());
        view.put("remainingTiles", gameState.getWallTiles().size());
        view.put("currentActionPlayerId", gameState.getCurrentActionPlayerId());
        view.put("currentActionType", gameState.getCurrentActionType());
        // 最近一次已实际执行的动作（用于前端“吃/碰/杠/胡”提示，只在确认后才设置）
        view.put("lastActionPlayerId", gameState.getLastActionPlayerId());
        view.put("lastActionType", gameState.getLastActionType());
        view.put("lastWinPlayerId", gameState.getLastWinPlayerId());
        view.put("lastWinType", gameState.getLastWinType());
        
        // 玩家信息（隐藏手牌）
        List<Map<String, Object>> playersInfo = new java.util.ArrayList<>();
        for (Player p : gameState.getPlayers()) {
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("id", p.getId());
            playerInfo.put("name", p.getName());
            playerInfo.put("position", p.getPosition());
            playerInfo.put("handSize", p.getHandSize());
            playerInfo.put("flowerTiles", p.getFlowerTiles());
            playerInfo.put("exposedMelds", p.getExposedMelds());
            playerInfo.put("isDealer", p.isDealer());
            // 连庄数只对当前庄家展示；下庄自动清0
            playerInfo.put("dealerStreak", p.isDealer() ? gameState.getConsecutiveDealerWins() : 0);
            playersInfo.add(playerInfo);
        }
        view.put("players", playersInfo);
        
        return view;
    }

    /**
     * 无人可吃碰杠胡/所有人都过后：
     * - 若下家有暗杠，等待其选择
     * - 否则进入下一轮并摸牌
     *
     * 说明：该逻辑原先在 discard() 与 pass() 中各写了一份，易产生维护不一致，因此抽取成单一实现。
     */
    private void handleNextPlayerAfterNoOneCanActionOrAllPassed(GameEngine engine, GameState gameState, String reason) {
        int discardPlayerIndex = gameState.getLastDiscardPlayerIndex();
        if (discardPlayerIndex < 0 || discardPlayerIndex >= gameState.getPlayers().size()) {
            log.warn("无法处理下一步（{}）：lastDiscardPlayerIndex={}", reason, discardPlayerIndex);
            return;
        }

        int nextPlayerIndex = (discardPlayerIndex + 1) % 4;
        Player nextPlayer = gameState.getPlayers().get(nextPlayerIndex);

        List<Tile> anGangTiles = com.fzmahjong.engine.ActionChecker.canAnGang(nextPlayer);
        if (anGangTiles != null && !anGangTiles.isEmpty()) {
            Map<String, Object> actions = new HashMap<>();
            actions.put("canAnGang", true);
            actions.put("anGangTiles", anGangTiles);
            gameState.setPlayerActions(nextPlayer.getId(), actions);
            gameState.setCurrentActionPlayerId(nextPlayer.getId());
            gameState.setCurrentActionType("anGang");
            log.info("{}：下家 {} 有暗杠，等待选择", reason, nextPlayer.getName());
            return;
        }

        engine.nextTurn();
        Player currentPlayer = gameState.getCurrentPlayer();
        if (currentPlayer != null) {
            engine.playerDraw(currentPlayer.getId());
        }
    }

    /**
     * 在当前局已经结束（HAND_FINISHED）且不需要轮庄确认的情况下，
     * 预留一小段时间（约 5 秒）用于前端展示胡牌结果和结算信息，然后自动开新的一局。
     */
    private void startNextHandWithDelay(String roomId, GameEngine engine) {
        new Thread(() -> {
            try {
                // 预留约 5 秒时间给前端展示胡牌原因 / 结算信息
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                log.warn("等待下一局开始的延迟被中断", e);
                Thread.currentThread().interrupt();
            }

            GameState state = engine.getGameState();
            // 只有在阶段仍然是 HAND_FINISHED 时才真正开启下一局，避免与其它流程（如轮庄确认）冲突
            if (state.getPhase() == GamePhase.HAND_FINISHED) {
                engine.startNextHand();
                GameState newState = engine.getGameState();
                broadcastGameState(roomId, newState);
                log.info("已在延迟后自动开启新的一局");
            } else {
                log.info("阶段已从 HAND_FINISHED 变更为 {}，放弃自动开新局", state.getPhase());
            }
        }).start();
    }

    // === 请求对象 ===
    
    public static class JoinRoomRequest {
        private String roomId;
        private String playerId;
        private String playerName;

        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
    }

    public static class DiscardRequest {
        private String playerId;
        private String tileId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getTileId() { return tileId; }
        public void setTileId(String tileId) { this.tileId = tileId; }
    }

    public static class DrawRequest {
        private String playerId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
    }

    public static class SyncRequest {
        private String playerId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
    }

    public static class ActionRequest {
        private String playerId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
    }

    public static class ChiRequest {
        private String playerId;
        private String tileId1;
        private String tileId2;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getTileId1() { return tileId1; }
        public void setTileId1(String tileId1) { this.tileId1 = tileId1; }
        public String getTileId2() { return tileId2; }
        public void setTileId2(String tileId2) { this.tileId2 = tileId2; }
    }

    public static class AnGangRequest {
        private String playerId;
        private String tileId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getTileId() { return tileId; }
        public void setTileId(String tileId) { this.tileId = tileId; }
    }

    public static class ContinueRequest {
        private String playerId;
        private boolean isContinue;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public boolean isContinue() { return isContinue; }
        public void setContinue(boolean aContinue) { isContinue = aContinue; }
    }

    public static class ReplaceFlowerRequest {
        private String playerId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
    }

    public static class OpenGoldRequest {
        private String playerId;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
    }

    public static class SetTestHandRequest {
        private String roomId;
        private List<String> tiles;

        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public List<String> getTiles() { return tiles; }
        public void setTiles(List<String> tiles) { this.tiles = tiles; }
    }
}
