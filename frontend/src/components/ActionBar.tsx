import { useGame } from '../context/GameContext';
import type { Tile } from '../types/game';
import '../styles/actionBar.css';

interface ActionBarProps {
  onRequestChi?: (discardedTile: Tile) => void;
}

export function ActionBar({ onRequestChi }: ActionBarProps) {
  const {
    gameState,
    playerId,
    sendPeng,
    sendGang,
    sendAnGang,
    sendHu,
    sendPass,
    sendReplaceFlower,
    sendOpenGold,
  } = useGame();

  const phase = gameState?.phase;
  const actions = gameState?.availableActions;
  const players = gameState?.players ?? [];
  const myIndex = players.findIndex((p) => p.id === playerId);
  const dealerIndex = gameState?.dealerIndex ?? -1;

  const fromDiscard = !!actions?.discardedTile;
  const hasActions = !!(actions?.canChi || actions?.canPeng || actions?.canGang || actions?.canHu);
  const canSelfAction = !!(actions?.canHu || actions?.canAnGang || actions?.canSanJinDao);

  const showReplaceFlower =
    phase === 'REPLACING_FLOWERS' &&
    gameState?.replacingFlowers &&
    gameState?.currentFlowerPlayerIndex === myIndex;
  const showOpenGold =
    phase === 'OPENING_GOLD' &&
    gameState?.waitingOpenGold &&
    dealerIndex === myIndex;
  const isConfirmContinue = phase === 'CONFIRM_CONTINUE';

  const handleAnGangClick = () => {
    const hand = gameState?.myHandTiles ?? [];
    const options = actions?.anGangTiles ?? [];
    if (options.length === 1) {
      const t = options[0];
      const match = hand.find((h) => h.type === t.type && h.value === t.value);
      if (match) sendAnGang(match.id);
    } else if (options.length > 1) {
        const choice = prompt(
          'Choose tile to conceal kong (enter 1-' + options.length + '):\n' +
          options.map((o, i) => `${i + 1}. ${o.type}${o.value}`).join('\n')
        );
      const i = parseInt(choice ?? '', 10) - 1;
      if (i >= 0 && i < options.length) {
        const t = options[i];
        const match = hand.find((h) => h.type === t.type && h.value === t.value);
        if (match) sendAnGang(match.id);
      }
    }
  };

  if (isConfirmContinue) return null;

  if (showReplaceFlower) {
    return (
      <div className="action-bar-float">
        <button type="button" className="action-bar-btn btn-replace-flower" onClick={sendReplaceFlower}>
          补花
        </button>
      </div>
    );
  }

  if (showOpenGold) {
    return (
      <div className="action-bar-float">
        <button type="button" className="action-bar-btn btn-open-gold" onClick={sendOpenGold}>
          开金
        </button>
      </div>
    );
  }

  if (canSelfAction && !fromDiscard) {
    return (
      <div className="action-bar-float">
        <div className="action-bar-buttons">
          {actions?.canHu && (
            <button type="button" className="action-bar-btn btn-hu" onClick={sendHu}>
              胡
            </button>
          )}
          {actions?.canAnGang && (actions?.anGangTiles?.length ?? 0) > 0 && (
            <button type="button" className="action-bar-btn btn-gang" onClick={handleAnGangClick}>
              杠
            </button>
          )}
          <button type="button" className="action-bar-btn btn-pass" onClick={sendPass}>
            Continue
          </button>
        </div>
      </div>
    );
  }

  if (hasActions) {
    return (
      <div className="action-bar-float">
        <div className="action-bar-buttons">
          {actions?.canChi && actions?.discardedTile && (
            <button
              type="button"
              className="action-bar-btn btn-chi"
              onClick={() => onRequestChi?.(actions.discardedTile!)}
            >
              吃
            </button>
          )}
          {actions?.canPeng && (
            <button type="button" className="action-bar-btn btn-peng" onClick={sendPeng}>
              碰
            </button>
          )}
          {actions?.canGang && (
            <button type="button" className="action-bar-btn btn-gang" onClick={sendGang}>
              杠
            </button>
          )}
          {actions?.canHu && (
            <button type="button" className="action-bar-btn btn-hu" onClick={sendHu}>
              胡
            </button>
          )}
          <button type="button" className="action-bar-btn btn-pass" onClick={sendPass}>
            过
          </button>
        </div>
      </div>
    );
  }

  return null;
}
