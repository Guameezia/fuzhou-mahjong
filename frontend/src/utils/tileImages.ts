const TILE_IMAGE_BASE_URL = '/images/tiles/';

function getTileFileName(type: string, value: number): string | null {
  switch (type) {
    case 'WAN':
      if (value >= 1 && value <= 9) return `${value}m.png`;
      return null;
    case 'TIAO':
      if (value >= 1 && value <= 9) return `${value}s.png`;
      return null;
    case 'BING':
      if (value >= 1 && value <= 9) return `${value}p.png`;
      return null;
    case 'WIND':
      if (value >= 1 && value <= 4) return `${value}z.png`;
      return null;
    case 'DRAGON':
      if (value === 1) return '5z.png';
      if (value === 2) return '6z.png';
      if (value === 3) return '7z.png';
      return null;
    case 'FLOWER': {
      const map: Record<number, string> = {
        1: 'chun.png', 2: 'xia.png', 3: 'qiu.png', 4: 'dong.png',
        5: 'mei.png', 6: 'lan.png', 7: 'zu.png', 8: 'ju.png',
      };
      return map[value] || null;
    }
    default:
      return null;
  }
}

export function getTileImageUrl(type: string, value: number): string {
  const fileName = getTileFileName(type, value);
  if (fileName) return TILE_IMAGE_BASE_URL + fileName;
  return TILE_IMAGE_BASE_URL + 'default.png';
}

export function preloadTileImages(): HTMLImageElement[] {
  const images: HTMLImageElement[] = [];
  for (let i = 1; i <= 9; i++) {
    ['WAN', 'TIAO', 'BING'].forEach((type) => {
      const url = getTileImageUrl(type, i);
      const img = new Image();
      img.src = url;
      images.push(img);
    });
  }
  for (let i = 1; i <= 4; i++) {
    const img = new Image();
    img.src = getTileImageUrl('WIND', i);
    images.push(img);
  }
  for (let i = 1; i <= 3; i++) {
    const img = new Image();
    img.src = getTileImageUrl('DRAGON', i);
    images.push(img);
  }
  for (let i = 1; i <= 8; i++) {
    const img = new Image();
    img.src = getTileImageUrl('FLOWER', i);
    images.push(img);
  }
  return images;
}
