package com.fzmahjong.service;

import com.fzmahjong.engine.GameEngine;
import com.fzmahjong.model.GameState;
import com.fzmahjong.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间管理器
 */
@Service
public class RoomManager {
    
    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);
    
    private final Map<String, GameEngine> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> playerRoomMap = new ConcurrentHashMap<>(); // playerId -> roomId

    /**
     * 创建房间
     */
    public String createRoom() {
        String roomId = "ROOM_" + UUID.randomUUID().toString().substring(0, 8);
        GameState gameState = new GameState(roomId);
        GameEngine engine = new GameEngine(gameState);
        rooms.put(roomId, engine);
        
        log.info("房间创建成功：{}", roomId);
        return roomId;
    }

    /**
     * 加入房间
     */
    public boolean joinRoom(String roomId, String playerId, String playerName) {
        GameEngine engine = rooms.get(roomId);
        if (engine == null) {
            log.warn("房间不存在：{}", roomId);
            return false;
        }

        GameState gameState = engine.getGameState();
        
        // 检查玩家是否已在这个房间中（重连情况）
        boolean playerExists = gameState.getPlayers().stream()
            .anyMatch(p -> p.getId().equals(playerId));
        
        if (playerExists) {
            log.info("玩家 {} 重新连接到房间 {}", playerName, roomId);
            playerRoomMap.put(playerId, roomId);
            return true;
        }
        
        // 检查房间是否已满（针对新玩家）
        if (gameState.getPlayers().size() >= 4) {
            log.warn("房间已满：{}", roomId);
            return false;
        }

        // 检查玩家是否已在其他房间
        String existingRoom = playerRoomMap.get(playerId);
        if (existingRoom != null && !existingRoom.equals(roomId)) {
            log.warn("玩家 {} 已在房间 {} 中", playerId, existingRoom);
            return false;
        }

        int position = gameState.getPlayers().size();
        Player player = new Player(playerId, playerName, position);
        gameState.addPlayer(player);
        playerRoomMap.put(playerId, roomId);

        log.info("玩家 {} 加入房间 {}，位置：{}", playerName, roomId, position);

        // 如果4个玩家都到齐，自动开始游戏
        if (gameState.isAllPlayersReady()) {
            log.info("房间 {} 人数已满，开始游戏", roomId);
            engine.startGame();
        }

        return true;
    }

    /**
     * 离开房间
     */
    public void leaveRoom(String playerId) {
        String roomId = playerRoomMap.remove(playerId);
        if (roomId != null) {
            GameEngine engine = rooms.get(roomId);
            if (engine != null) {
                GameState gameState = engine.getGameState();
                gameState.getPlayers().removeIf(p -> p.getId().equals(playerId));
                
                // 如果房间空了，删除房间
                if (gameState.getPlayers().isEmpty()) {
                    rooms.remove(roomId);
                    log.info("房间 {} 已删除", roomId);
                }
            }
        }
    }

    /**
     * 获取游戏引擎
     */
    public GameEngine getEngine(String roomId) {
        return rooms.get(roomId);
    }

    /**
     * 根据玩家ID获取所在房间ID
     */
    public String getRoomIdByPlayerId(String playerId) {
        return playerRoomMap.get(playerId);
    }

    /**
     * 获取所有房间
     */
    public Map<String, GameEngine> getAllRooms() {
        return rooms;
    }
}
