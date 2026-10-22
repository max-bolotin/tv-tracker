export type WatchStatus = 'WATCHING_NOW' | 'NOT_WATCHED' | 'UP_TO_DATE' | 'FINISHED' | 'DROPPED';
export type ProductionStatus = 'ONGOING' | 'ENDED';
export type MovieStatus = 'PLANNED' | 'WATCHED';

export interface MovieEntry {
  id: string;
  tmdbId: number;
  title: string;
  releaseDate?: string;
  runtime?: number;
  poster?: string;
  cast?: Actor[];
  imdbRating?: string;
  rottenTomatoesScore?: string;
  personalRating?: number | null;
  status: MovieStatus;
  watchedDate?: string;
}

export interface MovieSearchResult {
  tmdbId: number;
  title: string;
  releaseDate?: string;
  poster?: string;
  overview?: string;
}

export interface Episode {
  number: number;
  name: string;
  watched: boolean;
  airDate?: string; // ISO-8601, e.g. "2024-03-15", absent if unknown
}

export interface Season {
  number: number;
  episodes: Episode[];
  imdbRating?: string;
  rtRating?: string;
}

export interface Actor {
  name: string;
  profilePath?: string;
  linkUrl?: string;
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
  cast?: Actor[];
  imdbRating?: string;
  rtRating?: string;
  personalRating?: number | null;
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

export interface CurrentUser {
  id: string;
  name: string;
  email?: string | null;
  picture?: string | null;
}
