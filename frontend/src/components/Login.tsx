import React, { useState } from 'react';
import { useGame } from '../context/GameContext';

export function Login() {
  const { joinGame } = useGame();
  const [playerName, setPlayerName] = useState('Player1');
  const [roomId, setRoomId] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const name = playerName.trim();
    if (!name) {
      alert('Please enter your nickname');
      return;
    }
    setLoading(true);
    try {
      const ok = await joinGame(name, roomId.trim() || undefined);
      if (!ok) alert('Failed to join room');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="form-group">
        <label>Nickname:</label>
        <input
          type="text"
          value={playerName}
          onChange={(e) => setPlayerName(e.target.value)}
          placeholder="Enter your nickname"
        />
      </div>
      <div className="form-group">
        <label>Room ID (optional, leave empty to create a new room):</label>
        <input
          type="text"
          value={roomId}
          onChange={(e) => setRoomId(e.target.value)}
          placeholder="Leave empty to create a new room"
        />
      </div>
      <button type="submit" className="btn-primary" disabled={loading}>
        {loading ? 'Joining…' : 'Join Game'}
      </button>
    </form>
  );
}
