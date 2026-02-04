import React from 'react';
import { getTileImageUrl } from '../utils/tileImages';
import { formatTile } from '../utils/formatTile';
import type { Tile } from '../types/game';

interface TileDisplayProps {
  tile: Tile;
  className?: string;
  style?: React.CSSProperties;
  width?: number;
  height?: number;
  cursor?: string;
}

export function TileDisplay({ tile, className = '', style, width, height, cursor }: TileDisplayProps) {
  const url = getTileImageUrl(tile.type, tile.value);
  const s: React.CSSProperties = { ...style };
  if (width) s.width = width;
  if (height) s.height = height;
  if (cursor) s.cursor = cursor;

  return (
    <div className={`tile has-image ${className}`} style={s}>
      <img
        src={url}
        alt={formatTile(tile)}
        onError={(e) => {
          (e.target as HTMLImageElement).style.display = 'none';
          e.currentTarget.parentElement?.classList.remove('has-image');
        }}
      />
      <span className="tile-fallback">{formatTile(tile)}</span>
    </div>
  );
}
