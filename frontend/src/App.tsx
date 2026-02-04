import { useEffect, useState } from 'react';
import { GameProvider, useGame } from './context/GameContext';
import { Login } from './components/Login';
import { Game } from './components/Game';
import './styles/global.css';
import './styles/layout.css';
import './styles/table.css';
import './styles/tiles.css';
import './styles/dialogs.css';
import './styles/mobile.css';

function AppContent() {
  const { roomId, getSavedSession, joinGame, clearSavedSession } = useGame();
  const [checkedRestore, setCheckedRestore] = useState(false);

  useEffect(() => {
    if (checkedRestore) return;
    setCheckedRestore(true);
    const saved = getSavedSession();
    if (!saved) return;
    if (window.confirm(`Found an ongoing game.\nRoom ID: ${saved.roomId}\nPlayer: ${saved.playerName}\nRestore?`)) {
      joinGame(saved.playerName, saved.roomId, saved.playerId);
    } else {
      clearSavedSession();
    }
  }, [checkedRestore, getSavedSession, joinGame, clearSavedSession]);

  const inGame = !!roomId;

  return (
    <div className="container">
      <h1>🀄 FZMahjong 🀄</h1>
      <div className={`login-section ${!inGame ? 'active' : ''}`} id="loginSection">
        <Login />
      </div>
      <div className={`game-section ${inGame ? 'active' : ''}`} id="gameSection">
        <Game />
      </div>
    </div>
  );
}

export default function App() {
  return (
    <GameProvider>
      <AppContent />
    </GameProvider>
  );
}
