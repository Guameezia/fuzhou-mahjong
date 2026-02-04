import React, { createContext, useCallback, useContext, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import type { IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { GameState as GameStateType } from '../types/game';

const STORAGE_KEYS = {
  PLAYER_ID: 'mahjong_player_id',
  ROOM_ID: 'mahjong_room_id',
  PLAYER_NAME: 'mahjong_player_name',
};

function generatePlayerId(): string {
  return 'PLAYER_' + Math.random().toString(36).substring(2, 11);
}

type GameContextValue = {
  roomId: string | null;
  playerId: string | null;
  playerName: string | null;
  gameState: GameStateType | null;
  publicData: Partial<GameStateType> | null;
  isConnected: boolean;
  joinGame: (playerName: string, roomId?: string, rejoinPlayerId?: string) => Promise<boolean>;
  leaveGame: () => void;
  sendSync: () => void;
  sendDiscard: (tileId: string) => void;
  sendChi: (tileId1: string, tileId2: string) => void;
  sendPeng: () => void;
  sendGang: () => void;
  sendAnGang: (tileId: string) => void;
  sendHu: () => void;
  sendPass: () => void;
  sendReplaceFlower: () => void;
  sendOpenGold: () => void;
  sendContinue: (willContinue: boolean) => void;
  getSavedSession: () => { playerId: string; roomId: string; playerName: string } | null;
  clearSavedSession: () => void;
};

const GameContext = createContext<GameContextValue | null>(null);

export function GameProvider({ children }: { children: React.ReactNode }) {
  const [roomId, setRoomId] = useState<string | null>(null);
  const [playerId, setPlayerId] = useState<string | null>(null);
  const [playerName, setPlayerName] = useState<string | null>(null);
  const [gameState, setGameState] = useState<GameStateType | null>(null);
  const [publicData, setPublicData] = useState<Partial<GameStateType> | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  const send = useCallback((destination: string, body: object) => {
    const c = clientRef.current;
    if (!c?.connected) return;
    c.publish({ destination, body: JSON.stringify(body) });
  }, []);

  const joinGame = useCallback(async (name: string, roomIdInput?: string, rejoinPlayerId?: string): Promise<boolean> => {
    const pid = rejoinPlayerId && roomIdInput?.trim() ? rejoinPlayerId : generatePlayerId();
    let rid = roomIdInput?.trim();
    try {
      if (!rid) {
        const res = await fetch('/api/room/create', { method: 'POST' });
        const data = await res.json();
        rid = data.roomId;
      }
      const joinRes = await fetch('/api/room/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ roomId: rid, playerId: pid, playerName: name }),
      });
      const joinData = await joinRes.json();
      if (!joinData.success) return false;
      const finalRid = String(joinData.roomId ?? rid);

      localStorage.setItem(STORAGE_KEYS.PLAYER_ID, pid);
      localStorage.setItem(STORAGE_KEYS.ROOM_ID, finalRid);
      localStorage.setItem(STORAGE_KEYS.PLAYER_NAME, name);

      setRoomId(finalRid);
      setPlayerId(pid);
      setPlayerName(name);
      setGameState(null);
      setPublicData(null);

      const client = new Client({
        webSocketFactory: () => new SockJS('/ws-mahjong') as unknown as WebSocket,
        reconnectDelay: 5000,
        onConnect: () => {
          setIsConnected(true);
          client.subscribe('/topic/room/' + finalRid, (msg: IMessage) => {
            const data = JSON.parse(msg.body);
            setPublicData((prev) => ({ ...prev, ...data }));
          });
          client.subscribe('/topic/room/' + finalRid + '/player/' + pid, (msg: IMessage) => {
            const data = JSON.parse(msg.body);
            setGameState(data);
            setPublicData((prev) => ({ ...prev, ...data }));
          });
          client.publish({
            destination: '/app/game/sync',
            body: JSON.stringify({ playerId: pid }),
          });
        },
        onDisconnect: () => setIsConnected(false),
      });
      client.activate();
      clientRef.current = client;
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  }, []);

  const leaveGame = useCallback(() => {
    const c = clientRef.current;
    if (c) {
      c.deactivate();
      clientRef.current = null;
    }
    setIsConnected(false);
    setRoomId(null);
    setPlayerId(null);
    setPlayerName(null);
    setGameState(null);
    setPublicData(null);
    localStorage.removeItem(STORAGE_KEYS.PLAYER_ID);
    localStorage.removeItem(STORAGE_KEYS.ROOM_ID);
    localStorage.removeItem(STORAGE_KEYS.PLAYER_NAME);
  }, []);

  const sendSync = useCallback(() => {
    if (!playerId) return;
    send('/app/game/sync', { playerId });
  }, [playerId, send]);

  const sendDiscard = useCallback((tileId: string) => {
    if (!playerId) return;
    send('/app/game/discard', { playerId, tileId });
  }, [playerId, send]);

  const sendChi = useCallback((tileId1: string, tileId2: string) => {
    if (!playerId) return;
    send('/app/game/chi', { playerId, tileId1, tileId2 });
  }, [playerId, send]);

  const sendPeng = useCallback(() => {
    if (!playerId) return;
    send('/app/game/peng', { playerId });
  }, [playerId, send]);

  const sendGang = useCallback(() => {
    if (!playerId) return;
    send('/app/game/gang', { playerId });
  }, [playerId, send]);

  const sendAnGang = useCallback((tileId: string) => {
    if (!playerId) return;
    send('/app/game/anGang', { playerId, tileId });
  }, [playerId, send]);

  const sendHu = useCallback(() => {
    if (!playerId) return;
    send('/app/game/hu', { playerId });
  }, [playerId, send]);

  const sendPass = useCallback(() => {
    if (!playerId) return;
    send('/app/game/pass', { playerId });
  }, [playerId, send]);

  const sendReplaceFlower = useCallback(() => {
    if (!playerId) return;
    send('/app/game/replaceFlower', { playerId });
  }, [playerId, send]);

  const sendOpenGold = useCallback(() => {
    if (!playerId) return;
    send('/app/game/openGold', { playerId });
  }, [playerId, send]);

  const sendContinue = useCallback((willContinue: boolean) => {
    if (!playerId) return;
    send('/app/game/continue', { playerId, continue: willContinue });
  }, [playerId, send]);

  const getSavedSession = useCallback(() => {
    const pid = localStorage.getItem(STORAGE_KEYS.PLAYER_ID);
    const rid = localStorage.getItem(STORAGE_KEYS.ROOM_ID);
    const name = localStorage.getItem(STORAGE_KEYS.PLAYER_NAME);
    if (pid && rid && name) return { playerId: pid, roomId: rid, playerName: name };
    return null;
  }, []);

  const clearSavedSession = useCallback(() => {
    localStorage.removeItem(STORAGE_KEYS.PLAYER_ID);
    localStorage.removeItem(STORAGE_KEYS.ROOM_ID);
    localStorage.removeItem(STORAGE_KEYS.PLAYER_NAME);
  }, []);

  const value: GameContextValue = {
    roomId,
    playerId,
    playerName,
    gameState,
    publicData,
    isConnected,
    joinGame,
    leaveGame,
    sendSync,
    sendDiscard,
    sendChi,
    sendPeng,
    sendGang,
    sendAnGang,
    sendHu,
    sendPass,
    sendReplaceFlower,
    sendOpenGold,
    sendContinue,
    getSavedSession,
    clearSavedSession,
  };

  return <GameContext.Provider value={value}>{children}</GameContext.Provider>;
}

export function useGame() {
  const ctx = useContext(GameContext);
  if (!ctx) throw new Error('useGame must be used within GameProvider');
  return ctx;
}
