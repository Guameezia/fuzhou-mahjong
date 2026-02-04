import { useEffect } from 'react';
import { normalizeHuLabel } from '../utils/formatTile';
import type { LastWinSettlement } from '../types/game';
import '../styles/dialogs.css';

interface HuResultOverlayProps {
  playerName: string;
  winType: string | null;
  settlement?: LastWinSettlement | null;
  onClose: () => void;
}

function formatSettlement(s: LastWinSettlement): string {
  const base = s.base ?? 0;
  const flower = s.flower ?? 0;
  const gold = s.gold ?? 0;
  const gang = s.gang ?? 0;
  const special = s.special ?? 0;
  const isZiMo = s.isZiMo ?? false;
  const singlePay = s.singlePay ?? 0;

  const parts: string[] = [];
  parts.push(`${base}(底)`);
  parts.push(`+${flower}(花)`);
  parts.push(`+${gold}(金)`);
  if (special > 0) {
    parts.push(`+${special}(特殊)`);
  } else {
    parts.push(`+${gang}(杠)`);
  }
  if (isZiMo) {
    parts.push(' ×2(自摸)');
  }
  return parts.join('') + ` = ${singlePay} pts each`;
}

export function HuResultOverlay({ playerName, winType, settlement, onClose }: HuResultOverlayProps) {
  useEffect(() => {
    const t = setTimeout(onClose, 8000);
    return () => clearTimeout(t);
  }, [onClose]);

  return (
    <div className="hu-result-overlay visible" onClick={onClose}>
      <div className="hu-card" onClick={(e) => e.stopPropagation()}>
        <div className="hu-title">胡</div>
        <div className="hu-player">{playerName || '-'}</div>
        <div className="hu-type">{normalizeHuLabel(winType)}</div>
        {settlement && (
          <div className="hu-settlement">
            {formatSettlement(settlement)}
          </div>
        )}
        <div className="hu-tip">Click anywhere to close</div>
      </div>
    </div>
  );
}
