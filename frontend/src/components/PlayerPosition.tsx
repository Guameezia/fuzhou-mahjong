import { TileDisplay } from './TileDisplay';
import { getTileImageUrl } from '../utils/tileImages';
import { formatTile } from '../utils/formatTile';
import type { Player as PlayerType } from '../types/game';

interface PlayerPositionProps {
  position: 'top' | 'bottom' | 'left' | 'right';
  player: PlayerType | null;
  isActive: boolean;
  isDealer: boolean;
  actionBadge: string | null;
}

export function PlayerPosition({ position, player, isActive, isDealer, actionBadge }: PlayerPositionProps) {
  if (!player) {
    return (
      <div
        className={`player-position player-${position}`}
        style={{ display: 'none' }}
        id={`player${position.charAt(0).toUpperCase() + position.slice(1)}`}
      />
    );
  }

  const nameDisplay = player.isDealer && (player as { dealerStreak?: number }).dealerStreak
    ? `${player.name} Dealer ${(player as { dealerStreak?: number }).dealerStreak}`
    : player.name;

  const handSize = player.handSize ?? 0;
  const flowers = player.flowerTiles ?? [];
  const melds = player.exposedMelds ?? [];

  return (
    <div
      className={`player-position player-${position} ${isActive ? 'active' : ''} ${isDealer ? 'dealer' : ''}`}
      id={`player${position.charAt(0).toUpperCase() + position.slice(1)}`}
    >
      <div className="player-info">
        <div className="player-name">{nameDisplay}</div>
        <div className="player-stats-score">{player.score ?? 0}</div>
      </div>
      {position === 'bottom' ? (
        <div className="bottom-hand-melds-row">
          <div className="hand-and-flowers">
            <div className="player-hand">
              {handSize > 0 && (
                <div className="tile-back tile-count-wrapper">
                  <span className="tile-count-badge">{handSize}</span>
                </div>
              )}
            </div>
            <div className="player-flowers">
              {flowers.length > 0 && (
                <div className="flower-tile tile-count-wrapper">
                  {flowers[flowers.length - 1] && (
                    <img
                      src={getTileImageUrl(flowers[flowers.length - 1].type, flowers[flowers.length - 1].value)}
                      alt={formatTile(flowers[flowers.length - 1])}
                    />
                  )}
                  <span className="tile-count-badge">{flowers.length}</span>
                </div>
              )}
            </div>
          </div>
          <div className="exposed-melds">
            {melds.map((meld, i) => (
              <div key={i} className="meld-group">
                {meld.map((tile, j) => (
                  <TileDisplay key={tile.id || j} tile={tile} />
                ))}
              </div>
            ))}
          </div>
        </div>
      ) : (
        <>
          <div className="hand-and-flowers">
            <div className="player-hand">
              {handSize > 0 && (
                <div className="tile-back tile-count-wrapper">
                  <span className="tile-count-badge">{handSize}</span>
                </div>
              )}
            </div>
            <div className="player-flowers">
              {flowers.length > 0 && (
                <div className="flower-tile tile-count-wrapper">
                  {flowers[flowers.length - 1] && (
                    <img
                      src={getTileImageUrl(flowers[flowers.length - 1].type, flowers[flowers.length - 1].value)}
                      alt={formatTile(flowers[flowers.length - 1])}
                    />
                  )}
                  <span className="tile-count-badge">{flowers.length}</span>
                </div>
              )}
            </div>
          </div>
          <div className="exposed-melds">
            {melds.map((meld, i) => (
              <div key={i} className="meld-group">
                {meld.map((tile, j) => (
                  <TileDisplay key={tile.id || j} tile={tile} />
                ))}
              </div>
            ))}
          </div>
        </>
      )}
      {actionBadge && (
        <div className="player-action-badge">{actionBadge}</div>
      )}
    </div>
  );
}
