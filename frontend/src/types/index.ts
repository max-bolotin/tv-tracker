export type WatchStatus = 'WATCHING_NOW' | 'NOT_WATCHED' | 'UP_TO_DATE' | 'FINISHED' | 'DROPPED';
export type ProductionStatus = 'ONGOING' | 'ENDED';

export interface Episode {
  number: number;
  name: string;
  watched: boolean;
}

export interface Season {
  number: number;
  episodes: Episode[];
}

export interface TrackedShow {
  id: string;
  tmdbId?: number;
  tvmazeId?: number;
  imdbId?: string;
  title: string;
  posterPath?: string;
  overview?: string;
  totalSeasons: number;
  productionStatus: ProductionStatus;
  watchStatus: WatchStatus;
  seasons: Season[];
}

export interface ShowSearchResult {
  tmdbId?: number;
  tvmazeId?: number;
  imdbId?: string;
  title: string;
  posterPath?: string;
  overview?: string;
  totalSeasons: number;
  productionStatus?: ProductionStatus;
}
