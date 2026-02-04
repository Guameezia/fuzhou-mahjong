import { useMemo } from 'react';
import { useGame } from '../context/GameContext';
import { PlayerPosition } from './PlayerPosition';
import { TileDisplay } from './TileDisplay';
import { normalizeHuLabel } from '../utils/formatTile';

export function Table() {
  const { gameState, publicData, playerId } = useGame();
  const players = gameState?.players ?? [];
  const currentIndex = gameState?.currentPlayerIndex ?? -1;
  const myIndex = useMemo(() => players.findIndex((p) => p.id === playerId), [players, playerId]);

  const positionMap = useMemo(() => {
    if (myIndex >= 0) {
      return {
        bottom: myIndex,
        right: (myIndex + 1) % 4,
        top: (myIndex + 2) % 4,
        left: (myIndex + 3) % 4,
      };
    }
    return { bottom: 0, right: 1, top: 2, left: 3 };
  }, [myIndex]);

  const goldTile = publicData?.goldTile ?? gameState?.goldTile;
  const discardedTiles = publicData?.discardedTiles ?? gameState?.discardedTiles ?? [];
  const lastActionPlayerId = gameState?.lastActionPlayerId;
  const lastActionType = gameState?.lastActionType;
  const lastWinPlayerId = gameState?.lastWinPlayerId;
  const lastWinType = gameState?.lastWinType;

  const actionLabel = (type: string) => {
    switch (type) {
      case 'chi': return '吃';
      case 'peng': return '碰';
      case 'gang': return '杠';
      case 'anGang': return '暗杠';
      case 'hu': return '胡';
      default: return type;
    }
  };

  const badgeForPosition = (pos: keyof typeof positionMap): string | null => {
    const idx = positionMap[pos];
    const p = players[idx];
    if (!p) return null;
    if (lastWinPlayerId && lastWinType && p.id === lastWinPlayerId) return normalizeHuLabel(lastWinType);
    if (lastActionPlayerId && lastActionType && p.id === lastActionPlayerId) return actionLabel(lastActionType);
    return null;
  };

  const bottomPlayer = useMemo(() => {
    const p = players[positionMap.bottom];
    if (!p || p.id !== playerId) return p ?? null;
    return {
      ...p,
      exposedMelds: gameState?.myExposedMelds ?? p.exposedMelds,
      flowerTiles: gameState?.myFlowerTiles ?? p.flowerTiles,
    };
  }, [players, positionMap.bottom, playerId, gameState?.myExposedMelds, gameState?.myFlowerTiles]);

  return (
    <div className="table-container" id="tableContainer">
      <PlayerPosition
        position="top"
        player={players[positionMap.top] ?? null}
        isActive={currentIndex === positionMap.top}
        isDealer={players[positionMap.top]?.isDealer ?? false}
        actionBadge={badgeForPosition('top')}
      />
      <PlayerPosition
        position="left"
        player={players[positionMap.left] ?? null}
        isActive={currentIndex === positionMap.left}
        isDealer={players[positionMap.left]?.isDealer ?? false}
        actionBadge={badgeForPosition('left')}
      />
      <PlayerPosition
        position="right"
        player={players[positionMap.right] ?? null}
        isActive={currentIndex === positionMap.right}
        isDealer={players[positionMap.right]?.isDealer ?? false}
        actionBadge={badgeForPosition('right')}
      />
      <PlayerPosition
        position="bottom"
        player={bottomPlayer}
        isActive={currentIndex === positionMap.bottom}
        isDealer={bottomPlayer?.isDealer ?? false}
        actionBadge={badgeForPosition('bottom')}
      />

      <div className="table-center">
        <div className="discard-pile-center">
          {discardedTiles.slice(-20).map((tile, i) => (
            <TileDisplay key={tile.id || i} tile={tile} />
          ))}
        </div>
      </div>

      {goldTile && gameState?.phase !== 'FINISHED' && (
        <div className="gold-tile-on-table">
          <TileDisplay tile={goldTile} className="gold" />
        </div>
      )}
    </div>
  );
}
