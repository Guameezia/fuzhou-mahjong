import { useGame } from '../context/GameContext';
import '../styles/dialogs.css';

export function ContinuePrompt() {
  const { gameState, playerId, sendContinue } = useGame();
  const phase = gameState?.phase;
  const decisions = gameState?.continueDecisions ?? {};
  const myDecision = playerId ? decisions[playerId] : undefined;
  const players = gameState?.players ?? [];

  if (phase !== 'CONFIRM_CONTINUE') return null;

  const decidedCount = Object.values(decisions).filter((v) => v !== null && v !== undefined).length;
  const total = Object.keys(decisions).length;
  const sorted = [...(players || [])].sort((a, b) => (b.score ?? 0) - (a.score ?? 0));

  return (
    <div className="continue-prompt-panel">
      <div className="continue-prompt-title">Do you want to continue the game?</div>
      <div className="continue-prompt-text">
        ({decidedCount}/{total} confirmed)
      </div>
      <div className="continue-prompt-scoreboard">
        <div style={{ marginBottom: 6, fontWeight: 700 }}>Score Ranking</div>
        <ol style={{ paddingLeft: 20, margin: 0 }}>
          {sorted.map((p) => (
            <li key={p.id} style={{ marginBottom: 2 }}>
              {p.name}: {p.score ?? 0} pts
            </li>
          ))}
        </ol>
      </div>
      {myDecision === null || myDecision === undefined ? (
        <div className="continue-prompt-buttons">
          <button
            type="button"
            className="action-bar-btn btn-chi"
            style={{ background: 'linear-gradient(135deg, #4caf50 0%, #45a049 100%)', color: 'white' }}
            onClick={() => sendContinue(true)}
          >
            Continue
          </button>
          <button
            type="button"
            className="action-bar-btn"
            style={{ background: 'linear-gradient(135deg, #f44336 0%, #d32f2f 100%)', color: 'white' }}
            onClick={() => sendContinue(false)}
          >
            End
          </button>
        </div>
      ) : (
        <div className="continue-prompt-waiting visible">Submitted, waiting for other players...</div>
      )}
    </div>
  );
}
