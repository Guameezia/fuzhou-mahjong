package com.fzmahjong.model;

/**
 * 游戏阶段
 */
public enum GamePhase {
    WAITING,        // 等待玩家
    DEALING,        // 发牌阶段
    REPLACING_FLOWERS,  // 补花阶段
    OPENING_GOLD,   // 开金阶段
    PLAYING,        // 游戏进行中
    HAND_FINISHED,  // 单局结束，等待短暂停留后自动开下一局（或等待轮庄确认逻辑接管）
    CONFIRM_CONTINUE, // 轮庄一圈后确认是否继续
    FINISHED        // 整个房间游戏结束
}
