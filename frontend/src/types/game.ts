export type TileType = 'WAN' | 'TIAO' | 'BING' | 'WIND' | 'DRAGON' | 'FLOWER';

export interface Tile {
  type: TileType;
  value: number;
  id: string;
}

export interface Player {
  id: string;
  name: string;
  position: number;
  handSize?: number;
  flowerTiles?: Tile[];
  exposedMelds?: Tile[][];
  score?: number;
  isDealer?: boolean;
  dealerStreak?: number;
}

export interface AvailableActions {
  canChi?: boolean;
  canPeng?: boolean;
  canGang?: boolean;
  canHu?: boolean;
  canAnGang?: boolean;
  canSanJinDao?: boolean;
  discardedTile?: Tile;
  anGangTiles?: Tile[];
  tingTiles?: Tile[];
}

export type GamePhase =
  | 'WAITING'
  | 'DEALING'
  | 'REPLACING_FLOWERS'
  | 'OPENING_GOLD'
  | 'PLAYING'
  | 'FINISHED'
  | 'CONFIRM_CONTINUE';

export interface GameState {
  roomId?: string;
  players?: Player[];
  goldTile?: Tile;
  currentPlayerIndex?: number;
  dealerIndex?: number;
  phase?: GamePhase;
  remainingTiles?: number;
  discardedTiles?: Tile[];
  myHandTiles?: Tile[];
  myFlowerTiles?: Tile[];
  myExposedMelds?: Tile[][];
  availableActions?: AvailableActions;
  replacingFlowers?: boolean;
  currentFlowerPlayerIndex?: number;
  waitingOpenGold?: boolean;
  continueDecisions?: Record<string, boolean | null>;
  lastActionPlayerId?: string | null;
  lastActionType?: string | null;
  lastWinPlayerId?: string | null;
  lastWinType?: string | null;
  lastDrawnTile?: Tile | null;
  lastDrawPlayerIndex?: number;
}
