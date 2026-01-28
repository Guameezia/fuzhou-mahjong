package com.fzmahjong.model;

/**
 * 游戏阶段
 */
public enum GamePhase {
    WAITING,        // 等待玩家
    DEALING,        // 发牌阶段
    REPLACING_FLOWERS,  // 补花阶段
    OPENING_GOLD,   // 开金阶段
    CONFIRM_CONTINUE, // 轮庄一圈后确认是否继续
    PLAYING,        // 游戏进行中
    FINISHED        // 游戏结束
}
