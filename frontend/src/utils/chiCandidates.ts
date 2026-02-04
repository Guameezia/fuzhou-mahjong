import type { Tile } from '../types/game';

export type ChiCandidate = { tile1: Tile; tile2: Tile; type: 'left' | 'middle' | 'right' };

export function getChiCandidates(
  handTiles: Tile[],
  discardedTile: Tile,
  goldTile: Tile | null | undefined
): ChiCandidate[] {
  const candidates: ChiCandidate[] = [];
  const type = discardedTile.type;
  const value = discardedTile.value;

  if (type === 'WIND' || type === 'DRAGON') return [];
  const isGold = (t: Tile) =>
    !!goldTile && t.type === goldTile.type && t.value === goldTile.value;

  if (value >= 3) {
    const t1 = handTiles.find((t) => t.type === type && t.value === value - 2);
    const t2 = handTiles.find((t) => t.type === type && t.value === value - 1);
    if (t1 && t2 && !isGold(t1) && !isGold(t2))
      candidates.push({ tile1: t1, tile2: t2, type: 'left' });
  }
  if (value >= 2 && value <= 8) {
    const t1 = handTiles.find((t) => t.type === type && t.value === value - 1);
    const t2 = handTiles.find((t) => t.type === type && t.value === value + 1);
    if (t1 && t2 && !isGold(t1) && !isGold(t2))
      candidates.push({ tile1: t1, tile2: t2, type: 'middle' });
  }
  if (value <= 7) {
    const t1 = handTiles.find((t) => t.type === type && t.value === value + 1);
    const t2 = handTiles.find((t) => t.type === type && t.value === value + 2);
    if (t1 && t2 && !isGold(t1) && !isGold(t2))
      candidates.push({ tile1: t1, tile2: t2, type: 'right' });
  }
  return candidates;
}
