import { useEffect } from 'react';
import { normalizeHuLabel } from '../utils/formatTile';
import '../styles/dialogs.css';

interface HuResultOverlayProps {
  playerName: string;
  winType: string | null;
  onClose: () => void;
}

export function HuResultOverlay({ playerName, winType, onClose }: HuResultOverlayProps) {
  useEffect(() => {
    const t = setTimeout(onClose, 5000);
    return () => clearTimeout(t);
  }, [onClose]);

  return (
    <div className="hu-result-overlay visible" onClick={onClose}>
      <div className="hu-card" onClick={(e) => e.stopPropagation()}>
        <div className="hu-title">胡</div>
        <div className="hu-player">{playerName || '-'}</div>
        <div className="hu-type">{normalizeHuLabel(winType)}</div>
        <div className="hu-tip">Click anywhere to close</div>
      </div>
    </div>
  );
}
