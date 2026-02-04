import { useMemo } from 'react';
import { useGame } from '../context/GameContext';
import { getChiCandidates } from '../utils/chiCandidates';
import { TileDisplay } from './TileDisplay';
import type { Tile } from '../types/game';
import '../styles/dialogs.css';

interface ChiDialogProps {
  discardedTile: Tile | null;
  onClose: () => void;
}

export function ChiDialog({ discardedTile, onClose }: ChiDialogProps) {
  const { gameState, sendChi } = useGame();
  const handTiles = gameState?.myHandTiles ?? [];
  const goldTile = gameState?.goldTile;

  const candidates = useMemo(() => {
    if (!discardedTile) return [];
    return getChiCandidates(handTiles, discardedTile, goldTile);
  }, [handTiles, discardedTile, goldTile]);

  const handleChi = (tileId1: string, tileId2: string) => {
    sendChi(tileId1, tileId2);
    onClose();
  };

  if (!discardedTile) return null;

  if (goldTile && discardedTile.type === goldTile.type && discardedTile.value === goldTile.value) {
    return null;
  }
  if (discardedTile.type === 'WIND' || discardedTile.type === 'DRAGON') {
    return null;
  }

  if (candidates.length === 0) return null;
  if (candidates.length === 1) {
    sendChi(candidates[0].tile1.id, candidates[0].tile2.id);
    onClose();
    return null;
  }

  const sorted = [...candidates].sort((a, b) => {
    const order = { left: 0, middle: 1, right: 2 };
    return (order[a.type] ?? 0) - (order[b.type] ?? 0);
  });

  return (
    <div className="chi-dialog-overlay visible" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="chi-dialog-card" onClick={(e) => e.stopPropagation()}>
        <div className="chi-dialog-title">Choose combination for 吃</div>
        <div className="chi-options-container">
          {sorted.map((cand, i) => {
            const seq: Tile[] =
              cand.type === 'left'
                ? [cand.tile1, cand.tile2, discardedTile]
                : cand.type === 'middle'
                  ? [cand.tile1, discardedTile, cand.tile2]
                  : [discardedTile, cand.tile1, cand.tile2];
            return (
              <div
                key={i}
                className="chi-option-row"
                onClick={() => handleChi(cand.tile1.id, cand.tile2.id)}
              >
                {seq.map((tile, seqIdx) => (
                  <TileDisplay
                    key={`${i}-${seqIdx}-${tile.id}`}
                    tile={tile}
                    width={40}
                    height={56}
                    cursor="pointer"
                    className={tile === discardedTile ? 'chi-center' : ''}
                  />
                ))}
              </div>
            );
          })}
        </div>
        <div className="chi-dialog-actions">
          <button type="button" className="action-bar-btn btn-pass" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
