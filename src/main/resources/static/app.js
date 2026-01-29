let stompClient = null;
let currentRoomId = null;
let currentPlayerId = null;
let selectedTileId = null;
let gameState = null;
let continuePromptInitialized = false;

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
    if (data.goldTile) {
        document.getElementById('goldTile').textContent = formatTile(data.goldTile);
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
    
    // 更新可用操作和进张提示
    if (data.availableActions) {
        updateAvailableActions(data.availableActions);
    }

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
    
    if (nameElement) {
        // 名字旁展示连庄数（仅庄家显示；下庄清0）
        const streak = player.dealerStreak || 0;
        if (player.isDealer && streak > 0) {
            nameElement.textContent = `${player.name} Dealer ${streak}`;
        } else {
            nameElement.textContent = player.name;
        }
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
        tileDiv.style.animationDelay = `${index * 0.05}s`;
        
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
        
        tileDiv.onclick = function() {
            selectTile(tile.id, tileDiv);
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
    
    // 如果有多个选择，让用户选择
    if (candidates.length === 1) {
        doChi(candidates[0].tile1.id, candidates[0].tile2.id);
    } else {
        // 显示选择对话框
        let message = '请选择要吃的组合：\n';
        candidates.forEach((cand, index) => {
            message += `${index + 1}. ${formatTile(cand.tile1)} ${formatTile(cand.tile2)} ${formatTile(discardedTile)}\n`;
        });
        const choice = prompt(message + '\n请输入序号（1-' + candidates.length + '）：');
        const choiceIndex = parseInt(choice) - 1;
        if (choiceIndex >= 0 && choiceIndex < candidates.length) {
            doChi(candidates[choiceIndex].tile1.id, candidates[choiceIndex].tile2.id);
        }
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
