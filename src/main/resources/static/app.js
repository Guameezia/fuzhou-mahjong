let stompClient = null;
let currentRoomId = null;
let currentPlayerId = null;
let selectedTileId = null;
let gameState = null;
let continuePromptInitialized = false;
let chiDialogInitialized = false;

// 本地存储的键
const STORAGE_KEYS = {
    PLAYER_ID: 'mahjong_player_id',
    ROOM_ID: 'mahjong_room_id',
    PLAYER_NAME: 'mahjong_player_name'
};

// 生成随机玩家ID
function generatePlayerId() {
    return 'PLAYER_' + Math.random().toString(36).substr(2, 9);
}

// 保存游戏会话到本地存储
function saveGameSession(playerId, roomId, playerName) {
    localStorage.setItem(STORAGE_KEYS.PLAYER_ID, playerId);
    localStorage.setItem(STORAGE_KEYS.ROOM_ID, roomId);
    localStorage.setItem(STORAGE_KEYS.PLAYER_NAME, playerName);
    console.log('游戏会话已保存');
}

// 获取保存的游戏会话
function getSavedGameSession() {
    const playerId = localStorage.getItem(STORAGE_KEYS.PLAYER_ID);
    const roomId = localStorage.getItem(STORAGE_KEYS.ROOM_ID);
    const playerName = localStorage.getItem(STORAGE_KEYS.PLAYER_NAME);
    
    if (playerId && roomId && playerName) {
        return { playerId, roomId, playerName };
    }
    return null;
}

// 清除游戏会话
function clearGameSession() {
    localStorage.removeItem(STORAGE_KEYS.PLAYER_ID);
    localStorage.removeItem(STORAGE_KEYS.ROOM_ID);
    localStorage.removeItem(STORAGE_KEYS.PLAYER_NAME);
    console.log('游戏会话已清除');
}

// 页面加载时尝试恢复游戏
window.addEventListener('load', function() {
    const savedSession = getSavedGameSession();
    if (savedSession) {
        console.log('检测到已保存的游戏会话:', savedSession);
        
        // 询问用户是否要恢复游戏
        if (confirm(`检测到您有一个正在进行的游戏\n房间ID: ${savedSession.roomId}\n玩家: ${savedSession.playerName}\n\n是否要恢复游戏？`)) {
            reconnectToGame(savedSession);
        } else {
            clearGameSession();
        }
    }
});

// 重新连接到游戏
async function reconnectToGame(session) {
    currentPlayerId = session.playerId;
    currentRoomId = session.roomId;
    
    try {
        // 尝试重新加入房间
        const joinResponse = await fetch('/api/room/join', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                roomId: session.roomId,
                playerId: session.playerId,
                playerName: session.playerName
            })
        });
        
        const joinData = await joinResponse.json();
        
        if (joinData.success) {
            document.getElementById('displayRoomId').textContent = session.roomId;
            document.getElementById('loginSection').classList.remove('active');
            document.getElementById('gameSection').classList.add('active');
            
            // 连接 WebSocket
            connectWebSocket();
            
            console.log('成功重新连接到游戏');
        } else {
            alert('无法重新连接到游戏，可能房间已不存在');
            clearGameSession();
        }
        
    } catch (error) {
        console.error('重新连接游戏失败:', error);
        alert('重新连接失败，请重新加入游戏');
        clearGameSession();
    }
}

// 加入游戏
async function joinGame() {
    const playerName = document.getElementById('playerName').value.trim();
    let roomId = document.getElementById('roomId').value.trim();
    
    if (!playerName) {
        alert('请输入昵称');
        return;
    }
    
    currentPlayerId = generatePlayerId();
    
    try {
        // 如果没有房间ID，先创建房间
        if (!roomId) {
            const createResponse = await fetch('/api/room/create', {
                method: 'POST'
            });
            const createData = await createResponse.json();
            roomId = createData.roomId;
            console.log('创建房间:', roomId);
        }
        
        currentRoomId = roomId;
        
        // 加入房间
        const joinResponse = await fetch('/api/room/join', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                roomId: roomId,
                playerId: currentPlayerId,
                playerName: playerName
            })
        });
        
        const joinData = await joinResponse.json();
        
        if (joinData.success) {
            // 保存游戏会话
            saveGameSession(currentPlayerId, roomId, playerName);
            
            document.getElementById('displayRoomId').textContent = roomId;
            document.getElementById('loginSection').classList.remove('active');
            document.getElementById('gameSection').classList.add('active');
            
            // 连接 WebSocket
            connectWebSocket();
        } else {
            alert('加入房间失败');
        }
        
    } catch (error) {
        console.error('加入游戏失败:', error);
        alert('连接服务器失败');
    }
}

// 连接 WebSocket
function connectWebSocket() {
    const socket = new SockJS('/ws-mahjong');
    stompClient = Stomp.over(socket);
    
    // 设置断线重连
    stompClient.reconnect_delay = 5000; // 5秒后重连
    
    stompClient.connect({}, function(frame) {
        console.log('WebSocket 连接成功');
        
        // 订阅房间公共消息
        stompClient.subscribe('/topic/room/' + currentRoomId, function(message) {
            const data = JSON.parse(message.body);
            console.log('收到公共消息:', data);
            updatePublicView(data);
        });
        
        // 订阅玩家私有消息
        stompClient.subscribe('/topic/room/' + currentRoomId + '/player/' + currentPlayerId, 
            function(message) {
                const data = JSON.parse(message.body);
                console.log('收到私有消息:', data);
                gameState = data;
                updateGameView(data);
            }
        );
        
        // 连接成功后，请求同步游戏状态
        console.log('请求同步游戏状态...');
        stompClient.send('/app/game/sync', {}, JSON.stringify({
            playerId: currentPlayerId
        }));
    }, function(error) {
        console.error('WebSocket 连接失败:', error);
        // 自动尝试重新连接
        setTimeout(function() {
            console.log('尝试重新连接 WebSocket...');
            connectWebSocket();
        }, 5000);
    });
}

// 更新公共视图
function updatePublicView(data) {
    const goldInfoContainer = document.getElementById('goldTileInfo');
    const goldTableContainer = document.getElementById('goldTileTable');

    // 开金：同时更新信息栏里的小金牌和桌面本家上方的仿真金牌
    const hasGold = data && data.goldTile && data.phase !== 'FINISHED';
    if (hasGold) {
        if (goldInfoContainer) {
            goldInfoContainer.innerHTML = '';

            // 小号金牌牌面（信息栏）
            const smallTile = document.createElement('div');
            smallTile.className = 'tile';
            smallTile.style.width = '28px';
            smallTile.style.height = '40px';

            const smallImgUrl = typeof getTileImageUrl === 'function'
                ? getTileImageUrl(data.goldTile.type, data.goldTile.value)
                : null;

            if (smallImgUrl) {
                const img = document.createElement('img');
                img.src = smallImgUrl;
                img.alt = formatTile(data.goldTile);
                img.onerror = function() {
                    this.style.display = 'none';
                    smallTile.textContent = formatTile(data.goldTile);
                    smallTile.style.fontSize = '12px';
                };
                img.onload = function() {
                    smallTile.style.fontSize = '0';
                };
                smallTile.appendChild(img);
                smallTile.style.fontSize = '0';
            } else {
                smallTile.textContent = formatTile(data.goldTile);
                smallTile.style.fontSize = '12px';
            }

            goldInfoContainer.appendChild(smallTile);
        }

        if (goldTableContainer) {
            goldTableContainer.innerHTML = '';

            // 本家上方的金牌，只用牌面，不再用文字提示
            const tableTile = document.createElement('div');
            tableTile.className = 'tile gold';

            const tableImgUrl = typeof getTileImageUrl === 'function'
                ? getTileImageUrl(data.goldTile.type, data.goldTile.value)
                : null;

            if (tableImgUrl) {
                const img = document.createElement('img');
                img.src = tableImgUrl;
                img.alt = formatTile(data.goldTile);
                img.onerror = function() {
                    this.style.display = 'none';
                    tableTile.textContent = formatTile(data.goldTile);
                    tableTile.style.fontSize = '16px';
                };
                img.onload = function() {
                    tableTile.style.fontSize = '0';
                };
                tableTile.appendChild(img);
                tableTile.style.fontSize = '0';
            } else {
                tableTile.textContent = formatTile(data.goldTile);
                tableTile.style.fontSize = '16px';
            }

            goldTableContainer.appendChild(tableTile);
        }
    } else {
        // 没有金牌（还未开金 / 一局结束 / 新一局开始）时，清空展示，避免上一局残留
        if (goldInfoContainer) {
            goldInfoContainer.innerHTML = '';
        }
        if (goldTableContainer) {
            goldTableContainer.innerHTML = '';
        }
    }
    
    if (data.remainingTiles !== undefined) {
        document.getElementById('remainingTiles').textContent = data.remainingTiles;
    }
    
    if (data.phase) {
        updateGameStatus(data);
    }
    
    if (data.players) {
        updatePlayersGrid(data.players);
    }
    
    if (data.discardedTiles) {
        updateDiscardPile(data.discardedTiles);
    }

    // 根据当前公共状态，在桌面上高亮显示正在执行吃/碰/杠/胡的玩家
    updateActionHighlights(data);
}

// 更新游戏视图
function updateGameView(data) {
    // 合并本家的吃碰杠信息到玩家列表中
    if (data.players && data.myExposedMelds) {
        const currentPlayer = data.players.find(p => p.id === currentPlayerId);
        if (currentPlayer) {
            currentPlayer.exposedMelds = data.myExposedMelds;
        }
    }
    
    updatePublicView(data);
    
    if (data.myHandTiles) {
        updateMyHand(data.myHandTiles);
    }
    
    if (data.myFlowerTiles) {
        updateMyFlowers(data.myFlowerTiles);
    }
    
    // 更新可用操作和进张提示（对局阶段）
    if (data.availableActions) {
        updateAvailableActions(data.availableActions);
    }

    // 开局前置阶段：补花 / 开金 按钮
    updateSetupPhaseUI(data);

    // 轮庄一圈后：确认是否继续
    handleContinuePrompt(data);
}

// 更新游戏状态
function updateGameStatus(data) {
    let statusText = '';
    
    switch (data.phase) {
        case 'WAITING':
            statusText = 'Waiting for players to join...';
            break;
        case 'DEALING':
            statusText = 'Dealing...';  
            break;
        case 'REPLACING_FLOWERS':
            statusText = 'Replacing flowers...';  
            break;
        case 'OPENING_GOLD':
            statusText = 'Opening gold...';  
            break;
        case 'PLAYING':
            if (data.players && data.currentPlayerIndex !== undefined) {
                const currentPlayer = data.players[data.currentPlayerIndex];
                if (currentPlayer) {
                    statusText = 'It\'s [' + currentPlayer.name + '] turn';
                    
                    if (currentPlayer.id === currentPlayerId) {
                        statusText = 'It\'s YOUR TURN!';  
                    }
                }
            }
            break;
        case 'FINISHED':
            statusText = 'Game Over';    
            // 游戏结束后自动解散房间并返回初始界面：
            // 1. 断开 WebSocket
            // 2. 清除本地会话，避免下次自动恢复
            // 3. 重置前端状态并回到登录界面
            (function handleGameFinishedOnce() {
                // 避免重复执行：简单判断当前是否仍在游戏界面
                const gameSection = document.getElementById('gameSection');
                const loginSection = document.getElementById('loginSection');
                if (!gameSection || !loginSection) return;
                if (!gameSection.classList.contains('active')) {
                    // 已经回到登录界面，无需再次处理
                    return;
                }

                // 略延迟一点点，给玩家看到 "Game Over" 提示的时间
                setTimeout(function() {
                    try {
                        if (stompClient) {
                            stompClient.disconnect();
                            stompClient = null;
                            console.log('Game finished, WebSocket disconnected');
                        }
                    } catch (e) {
                        console.error('断开 WebSocket 时出错:', e);
                    }

                    clearGameSession();
                    console.log('游戏结束，已清除会话');

                    // 重置前端状态
                    currentRoomId = null;
                    currentPlayerId = null;
                    selectedTileId = null;
                    gameState = null;

                    // 切换回登录界面
                    gameSection.classList.remove('active');
                    loginSection.classList.add('active');

                    // 清空输入
                    const nameInput = document.getElementById('playerName');
                    const roomInput = document.getElementById('roomId');
                    if (nameInput) nameInput.value = '';
                    if (roomInput) roomInput.value = '';
                }, 1500);
            })();
            break;
        case 'CONFIRM_CONTINUE':
            statusText = 'Waiting for confirmation to continue...'; 
            break;
    }
    
    document.getElementById('gameStatus').textContent = statusText;
}

// === 吃 / 碰 / 杠 / 胡 全桌提示 ===

// 记录最近一次已经展示过的动作，避免重复闪烁
let lastShownActionKey = null;

function clearAllPlayerActionBadges() {
    document.querySelectorAll('.player-action-badge').forEach(badge => {
        if (badge.parentNode) {
            badge.parentNode.removeChild(badge);
        }
    });
}

function createBadgeForPosition(positionId, text) {
    const posEl = document.getElementById(positionId);
    if (!posEl) return;

    // 清理该位置上旧的 badge
    const old = posEl.querySelector('.player-action-badge');
    if (old && old.parentNode) {
        old.parentNode.removeChild(old);
    }

    const badge = document.createElement('div');
    badge.className = 'player-action-badge';
    badge.textContent = text;
    posEl.appendChild(badge);

    // 一段时间后自动移除（略长于 CSS 动画时间，保证能看清）
    setTimeout(() => {
        if (badge.parentNode) {
            badge.parentNode.removeChild(badge);
        }
    }, 2200);
}

// 根据后端给出的胡牌类型，统一映射成需要展示的文案
// - 普通胡：显示“胡”
// - 自摸：显示“自摸”（若字符串本身包含“自摸”也视为自摸）
// - 特殊胡牌：直接显示原始类型字符串，例如“抢金”“三金倒”
function normalizeHuLabel(winType) {
    if (!winType) return '胡';
    const text = String(winType);
    // 明确标识自摸
    if (text.includes('自摸')) return '自摸';
    // 简单兜底：后端若直接给“胡”
    if (text === '胡') return '胡';
    // 其他情况按类型原文显示，例如“抢金”“三金倒”
    return text;
}

function showHuResultOverlay(playerName, winType) {
    const overlay = document.getElementById('huResultOverlay');
    const nameEl = document.getElementById('huResultPlayer');
    const typeEl = document.getElementById('huResultType');
    if (!overlay || !nameEl || !typeEl) return;

    nameEl.textContent = playerName || '-';
    typeEl.textContent = normalizeHuLabel(winType);

    overlay.style.display = 'flex';

    // 点击任意位置关闭
    overlay.onclick = function() {
        overlay.style.display = 'none';
    };

    // 自动关闭兜底
    setTimeout(() => {
        if (overlay.style.display !== 'none') {
            overlay.style.display = 'none';
        }
    }, 5000);
}

function updateActionHighlights(data) {
    if (!data || !data.players || !Array.isArray(data.players)) {
        return;
    }

    // 1. 胡牌结果提示（优先级最高）
    // 不再强制要求 phase === 'FINISHED'，只要后端给出了最近一局的胡牌玩家与胡牌类型，
    // 就在全桌弹一次结算提示。这样每一局胡牌/流局后的那次胡牌结果都能清晰看到，
    // 而不仅限于整房间“Game Over”的场景。
    if (data.lastWinPlayerId && data.lastWinType) {
        const winner = data.players.find(p => p.id === data.lastWinPlayerId);
        const winnerName = winner ? winner.name : '未知玩家';
        const actionKey = `hu-result-${data.lastWinPlayerId}-${data.lastWinType}`;

        if (lastShownActionKey !== actionKey) {
            lastShownActionKey = actionKey;
            clearAllPlayerActionBadges();
            // 在赢家面前也飘一个“小胡牌”提示
            if (winner) {
                // 计算赢家在方桌上的位置（bottom/right/top/left）
                const myIndex = data.players.findIndex(p => p && p.id === currentPlayerId);
                if (myIndex >= 0) {
                    const positionMap = {
                        bottom: myIndex,
                        right: (myIndex + 1) % 4,
                        top: (myIndex + 2) % 4,
                        left: (myIndex + 3) % 4
                    };
                    let winnerPositionId = null;
                    Object.keys(positionMap).forEach(pos => {
                        if (data.players[positionMap[pos]] &&
                            data.players[positionMap[pos]].id === data.lastWinPlayerId) {
                            const idFirstUpper = pos.charAt(0).toUpperCase() + pos.slice(1);
                            winnerPositionId = `player${idFirstUpper}`;
                        }
                    });
                    if (winnerPositionId) {
                        createBadgeForPosition(winnerPositionId, normalizeHuLabel(data.lastWinType));
                    }
                }
            }

            showHuResultOverlay(winnerName, data.lastWinType);
        }
        return;
    }

    // 2. 吃 / 碰 / 杠 / 胡 / 暗杠 等“已经确认执行的动作”提示
    // 使用后端下发的 lastActionPlayerId/lastActionType，只在玩家真正点了按钮并且
    // 后端完成动作处理后才会设置，避免“只是有机会操作就弹窗”的问题。
    const actionPlayerId = data.lastActionPlayerId;
    const actionType = data.lastActionType;

    if (!actionPlayerId || !actionType) {
        // 没有新的已确认动作需要提示时，不主动清理，让已有 badge 自己按超时消失
        return;
    }

    const myIndex = data.players.findIndex(p => p && p.id === currentPlayerId);
    if (myIndex < 0) return;

    const positionMap = {
        bottom: myIndex,
        right: (myIndex + 1) % 4,
        top: (myIndex + 2) % 4,
        left: (myIndex + 3) % 4
    };

    let targetPositionId = null;
    Object.keys(positionMap).forEach(pos => {
        const idx = positionMap[pos];
        const p = data.players[idx];
        if (p && p.id === actionPlayerId) {
            const idFirstUpper = pos.charAt(0).toUpperCase() + pos.slice(1);
            targetPositionId = `player${idFirstUpper}`;
        }
    });

    if (!targetPositionId) {
        clearAllPlayerActionBadges();
        return;
    }

    // 映射动作中文文案
    let label = '';
    switch (actionType) {
        case 'chi':
            label = '吃';
            break;
        case 'peng':
            label = '碰';
            break;
        case 'gang':
            label = '杠';
            break;
        case 'anGang':
            label = '暗杠';
            break;
        case 'hu':
            label = '胡';
            break;
        default:
            label = actionType;
    }

    const actionKey = `${actionPlayerId}-${actionType}`;
    if (lastShownActionKey === actionKey) {
        return;
    }
    lastShownActionKey = actionKey;

    clearAllPlayerActionBadges();
    createBadgeForPosition(targetPositionId, label);
}

// === 补花 / 开金 阶段的前端按钮 ===
function updateSetupPhaseUI(data) {
    const actionButtonsPanel = document.getElementById('actionButtonsPanel');
    const actionButtons = document.getElementById('actionButtons');
    if (!actionButtonsPanel || !actionButtons || !data || !data.players) {
        return;
    }

    // 轮庄确认阶段由 handleContinuePrompt 接管，这里不处理
    if (data.phase === 'CONFIRM_CONTINUE') {
        return;
    }

    const myIndex = data.players.findIndex(p => p && p.id === currentPlayerId);
    if (myIndex < 0) {
        return;
    }

    // 1. 补花阶段：只在 REPLACING_FLOWERS 且 replacingFlowers=true 时生效
    if (data.phase === 'REPLACING_FLOWERS' && data.replacingFlowers) {
        const currentFlowerIndex = data.currentFlowerPlayerIndex;

        // 轮到我补花时，给我一个“补花”按钮
        if (currentFlowerIndex === myIndex) {
            actionButtonsPanel.style.display = 'block';
            actionButtons.innerHTML = '';

            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.textContent = '补花';
            btn.onclick = function() {
                if (!stompClient) return;
                stompClient.send('/app/game/replaceFlower', {}, JSON.stringify({
                    playerId: currentPlayerId
                }));
            };

            actionButtons.appendChild(btn);
        } else {
            // 不轮到我时，不显示补花按钮（保留面板给其他逻辑使用）
            // 这里不强制隐藏，因为其他阶段的逻辑可能需要使用同一面板
        }

        return;
    }

    // 2. 开金阶段：只在 OPENING_GOLD 且 waitingOpenGold=true 时生效
    if (data.phase === 'OPENING_GOLD' && data.waitingOpenGold) {
        const dealerIndex = data.dealerIndex;

        if (dealerIndex === myIndex) {
            actionButtonsPanel.style.display = 'block';
            actionButtons.innerHTML = '';

            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.textContent = '开金';
            btn.onclick = function() {
                if (!stompClient) return;
                stompClient.send('/app/game/openGold', {}, JSON.stringify({
                    playerId: currentPlayerId
                }));
            };

            actionButtons.appendChild(btn);
        } else {
            // 其他三家只看到“开金阶段”的状态提示，不显示按钮
        }

        return;
    }

    // 其他阶段：不动 actionButtonsPanel，由原有逻辑（吃碰杠胡、自摸等）接管
}

function ensureStyleTag(id, cssText) {
    if (document.getElementById(id)) return;
    const style = document.createElement('style');
    style.id = id;
    style.textContent = cssText;
    document.head.appendChild(style);
}

function ensureContinuePromptUI() {
    if (continuePromptInitialized) return;
    continuePromptInitialized = true;

    const container = document.createElement('div');
    container.id = 'continuePromptPanel';
    container.style.display = 'none';
    container.style.position = 'fixed';
    container.style.left = '50%';
    container.style.top = '50%';
    container.style.transform = 'translate(-50%, -50%)';
    container.style.zIndex = '2000';
    container.style.background = 'rgba(255,255,255,0.98)';
    container.style.border = '2px solid rgba(0,0,0,0.15)';
    container.style.borderRadius = '14px';
    container.style.boxShadow = '0 10px 35px rgba(0,0,0,0.35)';
    container.style.padding = '18px 18px 14px';
    container.style.minWidth = '280px';
    container.style.maxWidth = '90vw';
    container.innerHTML = `
        <div style="font-size:18px;font-weight:800;margin-bottom:10px;color:#333;">
            Do you want to continue the game?
        </div>
        <div id="continuePromptText" style="font-size:14px;color:#555;margin-bottom:14px;line-height:1.4;">
            Each player takes turns being the dealer.
        </div>
        <div id="scoreboardContainer" style="font-size:14px;color:#333;margin-bottom:12px;max-height:40vh;overflow:auto;">
        </div>
        <div style="display:flex;gap:10px;justify-content:center;">
            <button id="btnContinueYes" class="action-btn" style="background:linear-gradient(135deg,#4caf50 0%,#45a049 100%);">Continue</button>
            <button id="btnContinueNo" class="action-btn" style="background:linear-gradient(135deg,#f44336 0%,#d32f2f 100%);">End</button>
        </div>
        <div id="continuePromptWaiting" style="display:none;margin-top:12px;font-size:13px;color:#666;text-align:center;">
            Submitted, waiting for other players...
        </div>
    `;
    document.body.appendChild(container);

    document.getElementById('btnContinueYes').onclick = function() {
        sendContinueDecision(true);
    };
    document.getElementById('btnContinueNo').onclick = function() {
        sendContinueDecision(false);
    };
}

function handleContinuePrompt(data) {
    ensureContinuePromptUI();

    const panel = document.getElementById('continuePromptPanel');
    if (!panel) return;

    const waiting = document.getElementById('continuePromptWaiting');
    const text = document.getElementById('continuePromptText');
    const scoreboard = document.getElementById('scoreboardContainer');

    const isConfirmPhase = data && data.phase === 'CONFIRM_CONTINUE';
    if (!isConfirmPhase) {
        panel.style.display = 'none';
        return;
    }

    // 确认阶段：隐藏吃碰杠胡等操作面板，避免冲突
    const actionButtonsPanel = document.getElementById('actionButtonsPanel');
    if (actionButtonsPanel) {
        actionButtonsPanel.style.display = 'none';
    }

    const decisions = data.continueDecisions || {};
    const myDecision = decisions ? decisions[currentPlayerId] : null;

    panel.style.display = 'block';

    // 排行榜：按分数从高到低排序
    if (scoreboard && Array.isArray(data.players)) {
        const playersCopy = data.players.slice().sort((a, b) => {
            const sa = typeof a.score === 'number' ? a.score : 0;
            const sb = typeof b.score === 'number' ? b.score : 0;
            return sb - sa;
        });
        let html = '<div style="margin-bottom:6px;font-weight:700;">Score Ranking</div><ol style="padding-left:20px;margin:0;">';
        playersCopy.forEach(p => {
            const name = p.name || '-';
            const s = typeof p.score === 'number' ? p.score : 0;
            html += `<li style="margin-bottom:2px;">${name}: ${s} 分</li>`;
        });
        html += '</ol>';
        scoreboard.innerHTML = html;
    }

    // 显示已表态人数
    let decidedCount = 0;
    let total = 0;
    Object.keys(decisions).forEach(k => {
        total++;
        if (decisions[k] !== null && decisions[k] !== undefined) decidedCount++;
    });
    if (text) {
        text.textContent = `Do you want to continue the game? (${decidedCount}/${total} confirmed)`;
    }

    if (myDecision === null || myDecision === undefined) {
        waiting.style.display = 'none';
        document.getElementById('btnContinueYes').disabled = false;
        document.getElementById('btnContinueNo').disabled = false;
    } else {
        waiting.style.display = 'block';
        document.getElementById('btnContinueYes').disabled = true;
        document.getElementById('btnContinueNo').disabled = true;
    }
}

function sendContinueDecision(willContinue) {
    if (!stompClient) return;
    stompClient.send('/app/game/continue', {}, JSON.stringify({
        playerId: currentPlayerId,
        continue: willContinue
    }));
}

// 更新玩家列表（方桌布局）
function updatePlayersGrid(players) {
    if (!players || players.length === 0) {
        return;
    }
    
    // 找到当前玩家的索引
    let currentPlayerIndex = -1;
    for (let i = 0; i < players.length; i++) {
        if (players[i].id === currentPlayerId) {
            currentPlayerIndex = i;
            break;
        }
    }
    
    if (currentPlayerIndex === -1) {
        return;
    }
    
    // 根据当前玩家位置，确定其他玩家的位置
    // 位置关系：0=自己(下), 1=下家(右), 2=对家(上), 3=上家(左)
    const positionMap = {
        bottom: currentPlayerIndex,           // 自己
        right: (currentPlayerIndex + 1) % 4,  // 下家
        top: (currentPlayerIndex + 2) % 4,    // 对家
        left: (currentPlayerIndex + 3) % 4    // 上家
    };
    
    // 更新每个位置的玩家信息
    updatePlayerPosition('bottom', players[positionMap.bottom], currentPlayerIndex === gameState.currentPlayerIndex);
    updatePlayerPosition('right', players[positionMap.right], positionMap.right === gameState.currentPlayerIndex);
    updatePlayerPosition('top', players[positionMap.top], positionMap.top === gameState.currentPlayerIndex);
    updatePlayerPosition('left', players[positionMap.left], positionMap.left === gameState.currentPlayerIndex);
}

// 更新单个玩家位置
function updatePlayerPosition(position, player, isActive) {
    const positionElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}`);
    if (!positionElement || !player) {
        return;
    }
    
    positionElement.style.display = 'block';
    
    // 更新活动状态
    positionElement.classList.remove('active');
    if (isActive) {
        positionElement.classList.add('active');
    }
    
    // 更新庄家标记
    positionElement.classList.remove('dealer');
    if (player.isDealer) {
        positionElement.classList.add('dealer');
    }
    
    // 更新玩家信息
    const nameElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}Name`);
    const handCountElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}HandCount`);
    const flowerCountElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}FlowerCount`);
    const meldsElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}Melds`);
    const scoreElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}Score`);
    
    if (nameElement) {
        // 名字旁展示连庄数（仅庄家显示；下庄清0）
        const streak = player.dealerStreak || 0;
        if (player.isDealer && streak > 0) {
            nameElement.textContent = `${player.name} Dealer ${streak}`;
        } else {
            nameElement.textContent = player.name;
        }
    }

    if (scoreElement) {
        const s = typeof player.score === 'number' ? player.score : 0;
        scoreElement.textContent = s;
    }
    
    if (handCountElement) {
        handCountElement.textContent = player.handSize || 0;
    }
    
    if (flowerCountElement) {
        flowerCountElement.textContent = player.flowerTiles ? player.flowerTiles.length : 0;
    }
    
    // 更新手牌背面显示
    const handElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}Hand`);
    if (handElement && player.id !== currentPlayerId) {
        handElement.innerHTML = '';
        const handSize = player.handSize || 0;
        if (handSize > 0) {
            // 暗牌：只显示 1 张背面 + 数量
            const tileBack = document.createElement('div');
            tileBack.className = 'tile-back tile-count-wrapper';

            const countBadge = document.createElement('span');
            countBadge.className = 'tile-count-badge';
            countBadge.textContent = String(handSize);

            tileBack.appendChild(countBadge);
            handElement.appendChild(tileBack);
        }
    }
    
    // 更新花牌显示
    const flowersElement = document.getElementById(`player${position.charAt(0).toUpperCase() + position.slice(1)}Flowers`);
    if (flowersElement && player.flowerTiles && player.flowerTiles.length > 0) {
        flowersElement.innerHTML = '';
        // 花牌：只展示最新补花 1 张 + 花数量
        const flowers = player.flowerTiles;
        const latestFlower = flowers[flowers.length - 1];
        const flowerDiv = createFlowerTileElement(latestFlower, flowers.length, true);
        flowersElement.appendChild(flowerDiv);
    } else if (flowersElement) {
        flowersElement.innerHTML = '';
    }
    
    // 更新明牌（吃、碰、杠）
    if (meldsElement) {
        meldsElement.innerHTML = '';
        if (player.exposedMelds && player.exposedMelds.length > 0) {
            player.exposedMelds.forEach((meld, meldIndex) => {
                const meldGroup = document.createElement('div');
                meldGroup.className = 'meld-group';
                // 桌面尽量少字：不添加“吃/碰/杠”标签，直接摆牌即可
                
                meld.forEach((tile, tileIndex) => {
                    const tileDiv = document.createElement('div');
                    tileDiv.className = 'tile';
                    
                    // 使用真实图片显示麻将牌
                    const imageUrl = getTileImageUrl(tile.type, tile.value);
                    if (imageUrl) {
                        const img = document.createElement('img');
                        img.src = imageUrl;
                        img.alt = formatTile(tile);
                        img.onerror = function() {
                            this.style.display = 'none';
                            tileDiv.textContent = formatTile(tile);
                            tileDiv.style.fontSize = '10px';
                        };
                        img.onload = function() {
                            tileDiv.style.fontSize = '0';
                        };
                        tileDiv.appendChild(img);
                        tileDiv.style.fontSize = '0';
                    } else {
                        tileDiv.textContent = formatTile(tile);
                        tileDiv.style.fontSize = '10px';
                    }
                    
                    meldGroup.appendChild(tileDiv);
                });
                
                meldsElement.appendChild(meldGroup);
                
                // 如果是新添加的明牌，添加高亮动画
                setTimeout(() => {
                    meldGroup.classList.add('new-action');
                    setTimeout(() => {
                        meldGroup.classList.remove('new-action');
                    }, 1000);
                }, 100);
            });
        }
    }
}

// 更新我的手牌
function updateMyHand(tiles) {
    const container = document.getElementById('myHandTiles');
    const countSpan = document.getElementById('myHandCount');
    
    container.innerHTML = '';
    countSpan.textContent = `${tiles.length}`;
    
    // 期望行为：
    // - 新摸进来的那一张（包括杠后补牌）始终在最右侧
    // - 这张牌像金牌一样有特殊标识（高蓝色边框）
    //
    // 后端通常会处理排序，但部分情况下（如杠后补牌）可能会把新牌插入到中间。
    // 这里以前端展示为准：根据 gameState.lastDrawnTile.id 把“新牌”挪到最右侧。
    const displayedTiles = Array.isArray(tiles) ? tiles.slice() : [];
    const lastDrawnTile = gameState && gameState.lastDrawnTile ? gameState.lastDrawnTile : null;
    const myIndex = gameState && Array.isArray(gameState.players)
        ? gameState.players.findIndex(p => p && p.id === currentPlayerId)
        : -1;
    const isMyLastDraw = !!(lastDrawnTile && myIndex >= 0 && gameState.lastDrawPlayerIndex === myIndex);
    const lastDrawnId = isMyLastDraw && lastDrawnTile && lastDrawnTile.id ? lastDrawnTile.id : null;
    const isGoldTile = (t) => !!(gameState && gameState.goldTile && t &&
        t.type === gameState.goldTile.type && t.value === gameState.goldTile.value);

    if (lastDrawnId) {
        const idx = displayedTiles.findIndex(t => t && t.id === lastDrawnId);
        if (idx >= 0 && idx !== displayedTiles.length - 1) {
            const t = displayedTiles[idx];
            // 金牌维持最左侧展示逻辑（不强制挪到最右），但依然可以有“新摸牌”蓝框
            if (!isGoldTile(t)) {
                displayedTiles.splice(idx, 1);
                displayedTiles.push(t);
            }
        }
    }
    
    // 检查是否有暗杠选项
    const anGangTiles = gameState && gameState.availableActions && 
        gameState.availableActions.anGangTiles ? 
        gameState.availableActions.anGangTiles : [];
    
    displayedTiles.forEach((tile, index) => {
        const tileWrapper = document.createElement('div');
        tileWrapper.style.position = 'relative';
        tileWrapper.style.display = 'inline-block';
        
        const tileDiv = document.createElement('div');
        tileDiv.className = 'tile dealing';
        // 起手发牌：四张四张一组，组与组之间稍微停顿一下，便于观看
        const groupIndex = Math.floor(index / 4);
        tileDiv.style.animationDelay = `${groupIndex * 0.25}s`;
        
        if (isGoldTile(tile)) {
            tileDiv.classList.add('gold');
        }
        
        // 刚摸进来的那张：高蓝色边框（像金一样的特殊标识）
        if (lastDrawnId && tile && tile.id === lastDrawnId) {
            tileDiv.classList.add('just-drawn');
        }
        
        // 检查是否可以暗杠
        const canAnGang = anGangTiles.some(t => 
            t.type === tile.type && t.value === tile.value);
        if (canAnGang) {
            tileDiv.style.borderColor = '#9c27b0';
            tileDiv.style.borderWidth = '3px';
        }
        
        // 使用真实图片显示麻将牌
        const imageUrl = getTileImageUrl(tile.type, tile.value);
        if (imageUrl) {
            const img = document.createElement('img');
            img.src = imageUrl;
            img.alt = formatTile(tile);
            img.onerror = function() {
                // 如果图片加载失败，显示文字
                this.style.display = 'none';
                tileDiv.classList.remove('has-image');
                tileDiv.textContent = formatTile(tile);
                tileDiv.style.fontSize = '18px';
            };
            img.onload = function() {
                // 图片加载成功，添加has-image类
                tileDiv.classList.add('has-image');
            };
            tileDiv.appendChild(img);
            // 先添加has-image类，如果加载失败会被移除
            tileDiv.classList.add('has-image');
        } else {
            // 如果没有图片，显示文字
            tileDiv.textContent = formatTile(tile);
            tileDiv.style.fontSize = '18px';
        }
        
        // 单击：只负责高亮选中
        tileDiv.onclick = function() {
            selectTile(tile.id, tileDiv);
        };

        // 双击：选中并立即出牌
        tileDiv.ondblclick = function() {
            // 先按单击逻辑选中这张牌（带高亮/动画）
            selectTile(tile.id, tileDiv);
            // 然后调用统一的出牌逻辑
            discardSelected();
        };
        
        // 如果可以暗杠，添加右键菜单
        if (canAnGang) {
            tileDiv.oncontextmenu = function(e) {
                e.preventDefault();
                doAnGang(tile.id);
            };
        }
        
        tileWrapper.appendChild(tileDiv);
        container.appendChild(tileWrapper);
        
        // 移除发牌动画类（动画结束后）
        setTimeout(() => {
            tileDiv.classList.remove('dealing');
        }, 500 + index * 50);
    });
}

function createFlowerTileElement(tile, count, withCountWrapper) {
    const flowerDiv = document.createElement('div');
    flowerDiv.className = withCountWrapper ? 'flower-tile tile-count-wrapper' : 'flower-tile';

    const imageUrl = getTileImageUrl(tile.type, tile.value);
    if (imageUrl) {
        const img = document.createElement('img');
        img.src = imageUrl;
        img.alt = formatTile(tile);
        img.onerror = function() {
            this.style.display = 'none';
            flowerDiv.textContent = formatTile(tile);
            flowerDiv.style.fontSize = '8px';
        };
        img.onload = function() {
            flowerDiv.style.fontSize = '0';
        };
        flowerDiv.appendChild(img);
        flowerDiv.style.fontSize = '0';
    } else {
        flowerDiv.textContent = formatTile(tile);
        flowerDiv.style.fontSize = '8px';
    }

    if (count !== null && count !== undefined && withCountWrapper) {
        const countBadge = document.createElement('span');
        countBadge.className = 'tile-count-badge';
        countBadge.textContent = String(count);
        flowerDiv.appendChild(countBadge);
    }

    return flowerDiv;
}

// 更新我的花牌（在桌面上显示）
function updateMyFlowers(flowers) {
    // 更新桌面上的花牌显示
    const container = document.getElementById('playerBottomFlowers');
    if (container) {
        container.innerHTML = '';
        
        if (flowers && flowers.length > 0) {
            flowers.forEach(flower => {
                const flowerDiv = createFlowerTileElement(flower, null, false);
                container.appendChild(flowerDiv);
            });
        }
    }
}

// 更新牌池（显示在桌子中心）
function updateDiscardPile(tiles) {
    const container = document.getElementById('discardPileCenter');
    if (!container) {
        return;
    }
    
    container.innerHTML = '';
    
    // 只显示最近打出的牌（最多显示20张）
    const recentTiles = tiles.slice(-20);
    
    recentTiles.forEach((tile, index) => {
        const tileDiv = document.createElement('div');
        tileDiv.className = 'tile';
        tileDiv.style.cursor = 'default';
        tileDiv.style.opacity = '0.9';
        
        // 使用真实图片显示麻将牌
        const imageUrl = getTileImageUrl(tile.type, tile.value);
        if (imageUrl) {
            const img = document.createElement('img');
            img.src = imageUrl;
            img.alt = formatTile(tile);
            img.onerror = function() {
                this.style.display = 'none';
                tileDiv.classList.remove('has-image');
                tileDiv.textContent = formatTile(tile);
                tileDiv.style.fontSize = '10px';
            };
            img.onload = function() {
                tileDiv.classList.add('has-image');
            };
            tileDiv.appendChild(img);
            tileDiv.classList.add('has-image');
        } else {
            tileDiv.textContent = formatTile(tile);
            tileDiv.style.fontSize = '10px';
        }
        
        // 添加淡入动画
        tileDiv.style.animation = `fadeIn 0.3s ease-out ${index * 0.05}s forwards`;
        container.appendChild(tileDiv);
    });
}

// 添加淡入动画
ensureStyleTag('fadeInAnimation', `
    @keyframes fadeIn {
        from {
            opacity: 0;
            transform: scale(0.8);
        }
        to {
            opacity: 0.9;
            transform: scale(0.9);
        }
    }
`);

// 选择牌
function selectTile(tileId, element) {
    // 取消之前的选择（带动画）
    document.querySelectorAll('.tile.selected').forEach(t => {
        t.classList.remove('selected');
        t.style.transition = 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)';
    });
    
    selectedTileId = tileId;
    element.classList.add('selected');
    
    // 添加选中动画效果
    element.style.transition = 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)';
    
    // 添加轻微的震动效果
    element.style.animation = 'selectPulse 0.3s ease-out';
    
    console.log('选中牌:', tileId);
}

// 添加选中脉冲动画
ensureStyleTag('selectPulseAnimation', `
    @keyframes selectPulse {
        0% {
            transform: translateY(-20px) rotateX(10deg) scale(1.15);
        }
        50% {
            transform: translateY(-25px) rotateX(12deg) scale(1.2);
        }
        100% {
            transform: translateY(-20px) rotateX(10deg) scale(1.15);
        }
    }
`);

// 打出选中的牌
function discardSelected() {
    if (!selectedTileId) {
        alert('请先选择一张牌');
        return;
    }
    
    if (!gameState || gameState.currentPlayerIndex === undefined) {
        alert('游戏还未开始');
        return;
    }
    
    // 检查是否轮到自己
    const currentPlayer = gameState.players[gameState.currentPlayerIndex];
    if (currentPlayer.id !== currentPlayerId) {
        alert('还没轮到你出牌');
        return;
    }
    
    // 找到选中的牌元素，添加打出动画
    const selectedElement = document.querySelector('.tile.selected');
    if (selectedElement) {
        selectedElement.style.animation = 'discardTile 0.5s ease-out forwards';
        selectedElement.style.pointerEvents = 'none';
        
        // 动画结束后发送请求
        setTimeout(() => {
            console.log('打出牌:', selectedTileId);
            
            stompClient.send('/app/game/discard', {}, JSON.stringify({
                playerId: currentPlayerId,
                tileId: selectedTileId
            }));
            
            selectedTileId = null;
        }, 300);
    } else {
        console.log('打出牌:', selectedTileId);
        
        stompClient.send('/app/game/discard', {}, JSON.stringify({
            playerId: currentPlayerId,
            tileId: selectedTileId
        }));
        
        selectedTileId = null;
    }
}

// 添加打出牌动画
ensureStyleTag('discardTileAnimation', `
    @keyframes discardTile {
        0% {
            transform: translateY(-20px) rotateX(10deg) scale(1.15);
            opacity: 1;
        }
        50% {
            transform: translateY(-40px) rotateX(20deg) scale(1.3) rotateZ(10deg);
            opacity: 0.8;
        }
        100% {
            transform: translateY(-60px) rotateX(30deg) scale(0.8) rotateZ(20deg);
            opacity: 0;
        }
    }
`);

// 更新可用操作和进张提示
function updateAvailableActions(actions) {
    const jinZhangPanel = document.getElementById('jinZhangPanel');
    const jinZhangContent = document.getElementById('jinZhangContent');
    const tingPanel = document.getElementById('tingPanel');
    const tingContent = document.getElementById('tingContent');
    const actionButtonsPanel = document.getElementById('actionButtonsPanel');
    const actionButtons = document.getElementById('actionButtons');
    
    // 检查是否有操作（别人出牌后可以吃碰杠胡）
    const hasActions = actions.canChi || actions.canPeng || actions.canGang || actions.canHu;
    
    // 检查摸牌后可以进行的操作（胡〈包括自摸/三金倒/抢金/天胡等〉、暗杠）
    const canSelfAction = actions.canHu || actions.canAnGang || actions.canSanJinDao;
    // 后端在“别人出牌后”的判定中会下发 discardedTile 字段；
    // 摸牌后的自摸/暗杠/三金倒场景，不会带 discardedTile。
    const fromDiscard = !!actions.discardedTile;
    
    // 如果是摸牌后的操作选择（胡/暗杠等自操作）：
    // 仅依赖“有自操作 & 当前不是吃碰杠胡（无 discardedTile）”来判断，
    // 避免 currentActionType / currentActionPlayerId 状态异常导致前端不弹窗。
    if (canSelfAction && !fromDiscard && actionButtonsPanel && actionButtons) {
        actionButtonsPanel.style.display = 'block';
        actionButtons.innerHTML = '';
        
        // 显示提示信息
        const tipDiv = document.createElement('div');
        tipDiv.style.marginBottom = '15px';
        tipDiv.style.fontSize = '18px';
        tipDiv.style.fontWeight = 'bold';
        tipDiv.style.color = '#1976d2';
        tipDiv.textContent = '';
        actionButtons.appendChild(tipDiv);
        
        // 胡牌按钮（包括自摸 / 三金倒 / 抢金 / 天胡 等自摸型胡）
        if (actions.canHu) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #9c27b0 0%, #7b1fa2 100%)';
            btn.style.fontSize = '22px';
            btn.style.fontWeight = 'bold';
            btn.textContent = '胡';
            btn.onclick = function() {
                doHu();
            };
            actionButtons.appendChild(btn);
        }
        
        // 暗杠按钮
        if (actions.canAnGang && actions.anGangTiles && actions.anGangTiles.length > 0) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #9c27b0 0%, #7b1fa2 100%)';
            btn.style.fontSize = '20px';
            btn.textContent = '杠';
            btn.onclick = function() {
                // 从手牌中找到对应的牌
                const handTiles = gameState && gameState.myHandTiles ? gameState.myHandTiles : [];
                
                // 如果有多个暗杠选择，显示选择对话框
                if (actions.anGangTiles.length === 1) {
                    // 只有一个选择，从手牌中找到第一张匹配的牌
                    const targetTile = actions.anGangTiles[0];
                    const matchingTile = handTiles.find(t => 
                        t.type === targetTile.type && t.value === targetTile.value);
                    if (matchingTile) {
                        doAnGang(matchingTile.id);
                    } else {
                        alert('无法找到对应的牌');
                    }
                } else {
                    // 多个选择，让用户选择
                    let message = '请选择要暗杠的牌：\n';
                    actions.anGangTiles.forEach((tile, index) => {
                        message += `${index + 1}. ${formatTile(tile)}\n`;
                    });
                    const choice = prompt(message + '\n请输入序号（1-' + actions.anGangTiles.length + '）：');
                    const choiceIndex = parseInt(choice) - 1;
                    if (choiceIndex >= 0 && choiceIndex < actions.anGangTiles.length) {
                        const targetTile = actions.anGangTiles[choiceIndex];
                        const matchingTile = handTiles.find(t => 
                            t.type === targetTile.type && t.value === targetTile.value);
                        if (matchingTile) {
                            doAnGang(matchingTile.id);
                        } else {
                            alert('无法找到对应的牌');
                        }
                    }
                }
            };
            actionButtons.appendChild(btn);
        }
        
        // 过按钮（继续游戏）
        const passBtn = document.createElement('button');
        passBtn.className = 'action-btn';
        passBtn.style.background = 'linear-gradient(135deg, #9e9e9e 0%, #757575 100%)';
        passBtn.textContent = '继续游戏';
        passBtn.onclick = function() {
            // 清除操作状态，继续游戏
            if (stompClient) {
                stompClient.send('/app/game/pass', {}, JSON.stringify({
                    playerId: currentPlayerId
                }));
            }
        };
        actionButtons.appendChild(passBtn);
        
        // 隐藏进张提示面板
        if (jinZhangPanel) {
            jinZhangPanel.style.display = 'none';
        }
        return;
    }
    
    // 摸牌后不显示进张提示，隐藏进张提示面板
    if (jinZhangPanel) {
        jinZhangPanel.style.display = 'none';
    }

    // 听牌提示：只显示能听哪些牌（后端已按“金万能”代入计算）
    if (tingPanel && tingContent) {
        const tingTiles = actions && actions.tingTiles ? actions.tingTiles : [];
        if (tingTiles && tingTiles.length > 0) {
            tingPanel.style.display = 'block';
            tingContent.innerHTML = '';

            tingTiles.forEach((tile) => {
                const tileDiv = document.createElement('div');
                tileDiv.className = 'tile';
                tileDiv.style.cursor = 'default';

                const imageUrl = getTileImageUrl(tile.type, tile.value);
                if (imageUrl) {
                    const img = document.createElement('img');
                    img.src = imageUrl;
                    img.alt = formatTile(tile);
                    img.onerror = function() {
                        this.style.display = 'none';
                        tileDiv.textContent = formatTile(tile);
                        tileDiv.style.fontSize = '10px';
                    };
                    img.onload = function() {
                        tileDiv.style.fontSize = '0';
                    };
                    tileDiv.appendChild(img);
                    tileDiv.style.fontSize = '0';
                } else {
                    tileDiv.textContent = formatTile(tile);
                    tileDiv.style.fontSize = '10px';
                }

                tingContent.appendChild(tileDiv);
            });
        } else {
            tingPanel.style.display = 'none';
            tingContent.innerHTML = '';
        }
    }
    
    // 显示操作按钮（别人出牌后可以吃碰杠胡）
    if (hasActions) {
        actionButtonsPanel.style.display = 'block';
        actionButtons.innerHTML = '';
        
        if (actions.canChi) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #4caf50 0%, #45a049 100%)';
            btn.textContent = '吃';
            btn.onclick = function() {
                showChiDialog(actions.discardedTile);
            };
            actionButtons.appendChild(btn);
        }
        
        if (actions.canPeng) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #ff9800 0%, #f57c00 100%)';
            btn.textContent = '碰';
            btn.onclick = function() {
                doPeng();
            };
            actionButtons.appendChild(btn);
        }
        
        if (actions.canGang) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #f44336 0%, #d32f2f 100%)';
            btn.textContent = '杠';
            btn.onclick = function() {
                doGang();
            };
            actionButtons.appendChild(btn);
        }
        
        if (actions.canHu) {
            const btn = document.createElement('button');
            btn.className = 'action-btn';
            btn.style.background = 'linear-gradient(135deg, #9c27b0 0%, #7b1fa2 100%)';
            btn.style.fontSize = '24px';
            btn.style.fontWeight = 'bold';
            btn.textContent = '胡';
            btn.onclick = function() {
                doHu();
            };
            actionButtons.appendChild(btn);
        }
        
        // 过按钮
        const passBtn = document.createElement('button');
        passBtn.className = 'action-btn';
        passBtn.style.background = 'linear-gradient(135deg, #9e9e9e 0%, #757575 100%)';
        passBtn.textContent = '过';
        passBtn.onclick = function() {
            doPass();
        };
        actionButtons.appendChild(passBtn);
    } else {
        actionButtonsPanel.style.display = 'none';
    }
}

function ensureChiDialogUI() {
    if (chiDialogInitialized) return;
    chiDialogInitialized = true;

    const overlay = document.createElement('div');
    overlay.id = 'chiDialogOverlay';
    overlay.style.position = 'fixed';
    overlay.style.left = '0';
    overlay.style.top = '0';
    overlay.style.width = '100%';
    overlay.style.height = '100%';
    overlay.style.display = 'none';
    overlay.style.alignItems = 'center';
    overlay.style.justifyContent = 'center';
    overlay.style.zIndex = '2500';
    overlay.style.background = 'radial-gradient(circle at center, rgba(0,0,0,0.45) 0%, rgba(0,0,0,0.8) 60%)';

    overlay.innerHTML = `
        <div id="chiDialogCard" style="
            min-width: 260px;
            max-width: 90vw;
            padding: 18px 20px 14px;
            border-radius: 16px;
            background: radial-gradient(circle at top, #ffffff 0%, #e3f2fd 40%, #bbdefb 100%);
            box-shadow: 0 10px 35px rgba(0,0,0,0.6);
        ">
            <div style="font-size:18px;font-weight:800;margin-bottom:10px;color:#1a237e;">
                请选择要吃的组合
            </div>
            <div id="chiOptionsContainer" style="
                display:flex;
                flex-direction:column;
                gap:10px;
                max-height:50vh;
                overflow-y:auto;
            "></div>
            <div style="margin-top:12px;text-align:right;">
                <button id="chiCancelBtn" class="action-btn" style="
                    background:linear-gradient(135deg,#9e9e9e 0%,#757575 100%);
                    padding:6px 18px;
                    font-size:14px;
                ">取消</button>
            </div>
        </div>
    `;

    overlay.addEventListener('click', function (e) {
        if (e.target === overlay) {
            hideChiDialog();
        }
    });

    document.body.appendChild(overlay);

    const cancelBtn = document.getElementById('chiCancelBtn');
    if (cancelBtn) {
        cancelBtn.onclick = function () {
            hideChiDialog();
        };
    }
}

function hideChiDialog() {
    const overlay = document.getElementById('chiDialogOverlay');
    if (overlay) {
        overlay.style.display = 'none';
    }
}

function createChiOptionTileElement(tile, isCenter) {
    const tileDiv = document.createElement('div');
    tileDiv.className = 'tile';
    tileDiv.style.width = '40px';
    tileDiv.style.height = '56px';
    tileDiv.style.cursor = 'pointer';
    tileDiv.style.margin = '0 2px';

    if (isCenter) {
        tileDiv.style.borderColor = '#1976d2';
        tileDiv.style.boxShadow =
            '0 0 12px rgba(25,118,210,0.6), inset 0 1px 0 rgba(255,255,255,0.9)';
    }

    const imageUrl = typeof getTileImageUrl === 'function'
        ? getTileImageUrl(tile.type, tile.value)
        : null;

    if (imageUrl) {
        const img = document.createElement('img');
        img.src = imageUrl;
        img.alt = formatTile(tile);
        img.onerror = function () {
            this.style.display = 'none';
            tileDiv.classList.remove('has-image');
            tileDiv.textContent = formatTile(tile);
            tileDiv.style.fontSize = '14px';
        };
        img.onload = function () {
            tileDiv.classList.add('has-image');
        };
        tileDiv.appendChild(img);
        tileDiv.classList.add('has-image');
    } else {
        tileDiv.textContent = formatTile(tile);
        tileDiv.style.fontSize = '14px';
    }

    return tileDiv;
}

function openChiDialog(candidates, discardedTile) {
    ensureChiDialogUI();

    const overlay = document.getElementById('chiDialogOverlay');
    const container = document.getElementById('chiOptionsContainer');
    if (!overlay || !container) return;

    container.innerHTML = '';

    const typeOrder = { left: 0, middle: 1, right: 2 };
    candidates.sort((a, b) => {
        const av = (a && a.type && typeOrder[a.type] !== undefined) ? typeOrder[a.type] : 0;
        const bv = (b && b.type && typeOrder[b.type] !== undefined) ? typeOrder[b.type] : 0;
        return av - bv;
    });

    candidates.forEach((cand) => {
        const optionRow = document.createElement('div');
        optionRow.style.display = 'flex';
        optionRow.style.alignItems = 'center';
        optionRow.style.justifyContent = 'center';
        optionRow.style.padding = '6px 4px';
        optionRow.style.borderRadius = '10px';
        optionRow.style.background = 'rgba(255,255,255,0.8)';
        optionRow.style.boxShadow = '0 2px 6px rgba(0,0,0,0.25)';
        optionRow.style.cursor = 'pointer';
        optionRow.style.transition = 'transform 0.15s ease, box-shadow 0.15s ease';

        optionRow.onmouseenter = function () {
            optionRow.style.transform = 'translateY(-2px)';
            optionRow.style.boxShadow = '0 4px 12px rgba(0,0,0,0.35)';
        };
        optionRow.onmouseleave = function () {
            optionRow.style.transform = 'translateY(0)';
            optionRow.style.boxShadow = '0 2px 6px rgba(0,0,0,0.25)';
        };

        let seq = [];
        if (cand.type === 'left') {
            seq = [cand.tile1, cand.tile2, discardedTile];
        } else if (cand.type === 'middle') {
            seq = [cand.tile1, discardedTile, cand.tile2];
        } else {
            seq = [discardedTile, cand.tile1, cand.tile2];
        }

        seq.forEach((tile, idx) => {
            const isCenter = (tile === discardedTile);
            const tileEl = createChiOptionTileElement(tile, isCenter);
            optionRow.appendChild(tileEl);
            if (idx < seq.length - 1) {
                const spacer = document.createElement('div');
                spacer.style.width = '4px';
                optionRow.appendChild(spacer);
            }
        });

        optionRow.onclick = function () {
            hideChiDialog();
            doChi(cand.tile1.id, cand.tile2.id);
        };

        container.appendChild(optionRow);
    });

    overlay.style.display = 'flex';
}

// 显示吃牌对话框
function showChiDialog(discardedTile) {
    if (!gameState || !gameState.myHandTiles) {
        alert('无法获取手牌信息');
        return;
    }
    
    // 金牌不能参与吃：既不能吃金牌本身，也不能用手中的金牌去吃
    if (gameState.goldTile && discardedTile &&
        discardedTile.type === gameState.goldTile.type &&
        discardedTile.value === gameState.goldTile.value) {
        alert('金牌不能吃');
        return;
    }
    
    // 找到可以组成顺子的两张牌
    const handTiles = gameState.myHandTiles;
    const tileType = discardedTile.type;
    const tileValue = discardedTile.value;
    
    // 只有万条饼可以吃
    if (tileType === 'WIND' || tileType === 'DRAGON') {
        alert('字牌不能吃');
        return;
    }
    
    // 找出可以组成顺子的牌
    const candidates = [];
    const isGold = (t) => {
        return !!(gameState && gameState.goldTile && t &&
            t.type === gameState.goldTile.type &&
            t.value === gameState.goldTile.value);
    };
    
    // 检查左吃：需要 value-2, value-1
    if (tileValue >= 3) {
        const tile1 = handTiles.find(t => t.type === tileType && t.value === tileValue - 2);
        const tile2 = handTiles.find(t => t.type === tileType && t.value === tileValue - 1);
        if (tile1 && tile2 && !isGold(tile1) && !isGold(tile2)) {
            candidates.push({tile1: tile1, tile2: tile2, type: 'left'});
        }
    }
    
    // 检查中吃：需要 value-1, value+1
    if (tileValue >= 2 && tileValue <= 8) {
        const tile1 = handTiles.find(t => t.type === tileType && t.value === tileValue - 1);
        const tile2 = handTiles.find(t => t.type === tileType && t.value === tileValue + 1);
        if (tile1 && tile2 && !isGold(tile1) && !isGold(tile2)) {
            candidates.push({tile1: tile1, tile2: tile2, type: 'middle'});
        }
    }
    
    // 检查右吃：需要 value+1, value+2
    if (tileValue <= 7) {
        const tile1 = handTiles.find(t => t.type === tileType && t.value === tileValue + 1);
        const tile2 = handTiles.find(t => t.type === tileType && t.value === tileValue + 2);
        if (tile1 && tile2 && !isGold(tile1) && !isGold(tile2)) {
            candidates.push({tile1: tile1, tile2: tile2, type: 'right'});
        }
    }
    
    if (candidates.length === 0) {
        alert('无法组成有效的顺子');
        return;
    }
    
    // 如果只有一个组合，直接执行
    if (candidates.length === 1) {
        doChi(candidates[0].tile1.id, candidates[0].tile2.id);
    } else {
        // 多个选择：使用前端弹窗展示仿真牌面，点击选择
        openChiDialog(candidates, discardedTile);
    }
}

// 执行吃牌
function doChi(tileId1, tileId2) {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }

    // 前端兜底：金牌不能参与吃
    if (gameState && gameState.goldTile && gameState.myHandTiles) {
        const t1 = gameState.myHandTiles.find(t => t.id === tileId1);
        const t2 = gameState.myHandTiles.find(t => t.id === tileId2);
        const isGold = (t) => !!(t && t.type === gameState.goldTile.type && t.value === gameState.goldTile.value);
        if (isGold(t1) || isGold(t2)) {
            alert('金牌不能参与吃');
            return;
        }
    }

    stompClient.send('/app/game/chi', {}, JSON.stringify({
        playerId: currentPlayerId,
        tileId1: tileId1,
        tileId2: tileId2
    }));
}

// 执行碰牌
function doPeng() {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }

    stompClient.send('/app/game/peng', {}, JSON.stringify({
        playerId: currentPlayerId
    }));
}

// 执行杠牌
function doGang() {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }

    stompClient.send('/app/game/gang', {}, JSON.stringify({
        playerId: currentPlayerId
    }));
}

// 执行暗杠
function doAnGang(tileId) {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }

    stompClient.send('/app/game/anGang', {}, JSON.stringify({
        playerId: currentPlayerId,
        tileId: tileId
    }));
}

// 执行胡牌
function doHu() {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }

    stompClient.send('/app/game/hu', {}, JSON.stringify({
        playerId: currentPlayerId
    }));
}

// 执行过
function doPass() {
    if (!stompClient) {
        alert('未连接到服务器');
        return;
    }
    
    stompClient.send('/app/game/pass', {}, JSON.stringify({
        playerId: currentPlayerId
    }));
}

// 格式化牌的显示（用于alt文本和图片加载失败时的备用显示）
function formatTile(tile) {
    if (!tile) return '-';
    
    switch (tile.type) {
        case 'WAN':
            return tile.value + '万';
        case 'TIAO':
            return tile.value + '条';
        case 'BING':
            return tile.value + '饼';
        case 'WIND':
            return ['', '东', '南', '西', '北'][tile.value];
        case 'DRAGON':
            const dragonNames = ['', '中', '白', '发'];
            if (tile.value >= 1 && tile.value <= 3) {
                return dragonNames[tile.value];
            }
            return '字' + tile.value;
        case 'FLOWER':
            const flowerNames = ['', '春', '夏', '秋', '冬', '梅', '兰', '竹', '菊'];
            if (tile.value >= 1 && tile.value <= 8) {
                return flowerNames[tile.value];
            }
            return '花' + tile.value;
        default:
            return '?';
    }
}

// 页面加载时预加载图片
window.addEventListener('load', function() {
    if (typeof preloadTileImages === 'function') {
        console.log('开始预加载麻将牌图片...');
        preloadTileImages();
    }
});
