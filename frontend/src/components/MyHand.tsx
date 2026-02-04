import { useMemo } from 'react';
import { useGame } from '../context/GameContext';
import { getTileImageUrl } from '../utils/tileImages';
import { formatTile } from '../utils/formatTile';
import type { Tile } from '../types/game';

export function MyHand() {
  const { gameState, playerId, sendDiscard, sendAnGang } = useGame();

  const tiles = gameState?.myHandTiles ?? [];
  const goldTile = gameState?.goldTile;
  const lastDrawnTile = gameState?.lastDrawnTile;
  const lastDrawPlayerIndex = gameState?.lastDrawPlayerIndex ?? -1;
  const myIndex = gameState?.players?.findIndex((p) => p.id === playerId) ?? -1;
  const isMyLastDraw = myIndex >= 0 && lastDrawPlayerIndex === myIndex && lastDrawnTile;
  const anGangTiles = gameState?.availableActions?.anGangTiles ?? [];

  const isGold = (t: Tile) =>
    !!goldTile && t.type === goldTile.type && t.value === goldTile.value;

  const displayedTiles = useMemo(() => {
    const list = [...tiles];
    if (!isMyLastDraw || !lastDrawnTile?.id) return list;
    const idx = list.findIndex((t) => t.id === lastDrawnTile.id);
    if (idx >= 0 && idx !== list.length - 1 && !isGold(list[idx])) {
      const [t] = list.splice(idx, 1);
      list.push(t);
    }
    return list;
  }, [tiles, isMyLastDraw, lastDrawnTile, isGold]);

  const handleClick = (_tile: Tile, el: HTMLElement) => {
    document.querySelectorAll('.tile.selected').forEach((n) => n.classList.remove('selected'));
    el.classList.add('selected');
  };

  const handleDoubleClick = (tile: Tile, el: HTMLElement) => {
    el.classList.add('selected');
    sendDiscard(tile.id);
  };

  const handleContextMenu = (e: React.MouseEvent, tile: Tile) => {
    const canAnGang = anGangTiles.some((t) => t.type === tile.type && t.value === tile.value);
    if (canAnGang) {
      e.preventDefault();
      sendAnGang(tile.id);
    }
  };

  return (
    <div className="my-hand">
      <h3>🤲 <span id="myHandCount">{tiles.length}</span></h3>
      <div className="tiles-container" id="myHandTiles">
        {displayedTiles.map((tile) => {
          const isGoldTile = isGold(tile);
          const justDrawn = isMyLastDraw && lastDrawnTile && tile.id === lastDrawnTile.id;
          const canAnGang = anGangTiles.some((t) => t.type === tile.type && t.value === tile.value);
          return (
            <div key={tile.id} style={{ position: 'relative', display: 'inline-block' }}>
              <div
                className={`tile has-image dealing ${isGoldTile ? 'gold' : ''} ${justDrawn ? 'just-drawn' : ''}`}
                style={{
                  borderColor: canAnGang ? '#9c27b0' : undefined,
                  borderWidth: canAnGang ? 3 : undefined,
                }}
                onClick={(e) => handleClick(tile, e.currentTarget)}
                onDoubleClick={(e) => handleDoubleClick(tile, e.currentTarget)}
                onContextMenu={(e) => handleContextMenu(e, tile)}
              >
                <img
                  src={getTileImageUrl(tile.type, tile.value)}
                  alt={formatTile(tile)}
                  onLoad={(ev) => ev.currentTarget.parentElement?.classList.add('has-image')}
                  onError={(ev) => {
                    (ev.target as HTMLImageElement).style.display = 'none';
                    ev.currentTarget.parentElement?.classList.remove('has-image');
                  }}
                />
                <span className="tile-fallback">{formatTile(tile)}</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
