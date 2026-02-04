import type { Tile } from '../types/game';

export function formatTile(tile: Tile | null | undefined): string {
  if (!tile) return '-';
  switch (tile.type) {
    case 'WAN':
      return tile.value + '万';
    case 'TIAO':
      return tile.value + '条';
    case 'BING':
      return tile.value + '饼';
    case 'WIND':
      return ['', '东', '南', '西', '北'][tile.value] ?? '?';
    case 'DRAGON': {
      const names = ['', '中', '白', '发'];
      if (tile.value >= 1 && tile.value <= 3) return names[tile.value];
      return '字' + tile.value;
    }
    case 'FLOWER': {
      const names = ['', '春', '夏', '秋', '冬', '梅', '兰', '竹', '菊'];
      if (tile.value >= 1 && tile.value <= 8) return names[tile.value];
      return '花' + tile.value;
    }
    default:
      return '?';
  }
}

export function normalizeHuLabel(winType: string | null | undefined): string {
  if (!winType) return '胡';
  const text = String(winType);
  if (text.includes('自摸')) return '自摸';
  if (text === '胡') return '胡';
  return text;
}
