import { useCallback, useEffect, useState } from 'react';
import { useGame } from '../context/GameContext';
import { getChiCandidates } from '../utils/chiCandidates';
import { getTileImageUrl } from '../utils/tileImages';
import { Table } from './Table';
import { MyHand } from './MyHand';
import { ActionBar } from './ActionBar';
import { ChiDialog } from './ChiDialog';
import { HuResultOverlay } from './HuResultOverlay';
import { ContinuePrompt } from './ContinuePrompt';
import type { Tile, LastWinSettlement } from '../types/game';

export function Game() {
  const { gameState, publicData, playerId, leaveGame, sendChi } = useGame();
  const [chiDialogTile, setChiDialogTile] = useState<Tile | null>(null);
  const [huResult, setHuResult] = useState<{
    playerName: string;
    winType: string | null;
    settlement?: LastWinSettlement;
  } | null>(null);

  const phase = gameState?.phase;
  const players = gameState?.players ?? [];

  const handleRequestChi = useCallback(
    (discardedTile: Tile) => {
      const hand = gameState?.myHandTiles ?? [];
      const gold = gameState?.goldTile;
      const candidates = getChiCandidates(hand, discardedTile, gold);
      if (candidates.length === 0) {
        alert('Cannot form a valid sequence');
        return;
      }
      if (gold && discardedTile.type === gold.type && discardedTile.value === gold.value) {
        alert('Gold tile cannot be used for 吃');
        return;
      }
      if (discardedTile.type === 'WIND' || discardedTile.type === 'DRAGON') {
        alert('Honor tiles cannot be used for 吃');
        return;
      }
      if (candidates.length === 1) {
        sendChi(candidates[0].tile1.id, candidates[0].tile2.id);
        return;
      }
      setChiDialogTile(discardedTile);
    },
    [gameState?.myHandTiles, gameState?.goldTile, sendChi]
  );

  // Show 胡 result when phase is HAND_FINISHED (one hand just ended) or FINISHED (game over). Clear when entering next hand.
  useEffect(() => {
    if ((phase === 'HAND_FINISHED' || phase === 'FINISHED') && gameState?.lastWinPlayerId != null && gameState?.lastWinType != null) {
      const winner = players.find((p) => p.id === gameState.lastWinPlayerId);
      setHuResult({
        playerName: winner?.name ?? 'Unknown',
        winType: gameState.lastWinType ?? null,
        settlement: gameState.lastWinSettlement ?? undefined,
      });
    } else {
      setHuResult(null);
    }
  }, [phase, gameState?.lastWinPlayerId, gameState?.lastWinType, gameState?.lastWinSettlement, players]);

  useEffect(() => {
    if (phase !== 'FINISHED') return;
    const t = setTimeout(() => {
      leaveGame();
    }, 1500);
    return () => clearTimeout(t);
  }, [phase, leaveGame]);

  const statusText = (() => {
    switch (phase) {
      case 'WAITING':
        return 'Waiting for players to join...';
      case 'DEALING':
        return 'Dealing...';
      case 'REPLACING_FLOWERS':
        return 'Replacing flowers...';
      case 'OPENING_GOLD':
        return 'Opening gold...';
      case 'PLAYING':
        if (players.length && gameState?.currentPlayerIndex !== undefined) {
          const cur = players[gameState.currentPlayerIndex];
          if (cur) return cur.id === playerId ? "It's YOUR TURN!" : `It's [${cur.name}] turn`;
        }
        return '';
      case 'HAND_FINISHED':
        return 'Hand finished';
      case 'FINISHED':
        return 'Game Over';
      case 'CONFIRM_CONTINUE':
        return 'Waiting for confirmation to continue...';
      default:
        return '';
    }
  })();

  const goldTile = publicData?.goldTile ?? gameState?.goldTile;
  const remainingTiles = publicData?.remainingTiles ?? gameState?.remainingTiles;

  return (
    <>
      <div className="info-tip">💡 Game progress is saved automatically. Refresh the page to rejoin.</div>
      <div className="info-panel">
        <div className="info-row">
          <span>Room ID: </span>
          <strong id="displayRoomId">{gameState?.roomId ?? '-'}</strong>
        </div>
        <div className="info-row">
          <span>Gold: </span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {goldTile && phase !== 'FINISHED' && phase !== 'HAND_FINISHED' && (
              <div className="tile has-image" style={{ width: 28, height: 40, fontSize: 0 }}>
                <img src={getTileImageUrl(goldTile.type, goldTile.value)} alt={goldTile.type + goldTile.value} />
              </div>
            )}
          </div>
        </div>
        <div className="info-row">
          <span>Remaining tiles: </span>
          <span id="remainingTiles">{remainingTiles ?? '-'}</span>
        </div>
      </div>

      <div className="status" id="gameStatus">
        {statusText}
      </div>

      <Table />

      <MyHand />

      <div
        id="tingPanel"
        className={`ting-panel ${(gameState?.availableActions?.tingTiles?.length ?? 0) > 0 ? 'visible' : ''}`}
      >
        <h3 style={{ marginBottom: 8 }}>👂</h3>
        <div className="ting-content">
          {(gameState?.availableActions?.tingTiles ?? []).map((tile) => (
            <div key={tile.id} className="tile has-image" style={{ width: 36, height: 50, cursor: 'default' }}>
              <img src={getTileImageUrl(tile.type, tile.value)} alt="" />
            </div>
          ))}
        </div>
      </div>

      <ActionBar onRequestChi={handleRequestChi} />

      {chiDialogTile && (
        <ChiDialog discardedTile={chiDialogTile} onClose={() => setChiDialogTile(null)} />
      )}

      {huResult && (
        <HuResultOverlay
          playerName={huResult.playerName}
          winType={huResult.winType}
          settlement={huResult.settlement}
          onClose={() => setHuResult(null)}
        />
      )}

      <ContinuePrompt />
    </>
  );
}
