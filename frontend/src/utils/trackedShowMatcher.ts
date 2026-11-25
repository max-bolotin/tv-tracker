import type { ShowSearchResult, TrackedShow } from '../types';

export function trackedForResult(
  result: ShowSearchResult | null,
  trackedShows: TrackedShow[],
): TrackedShow | null {
  if (!result) return null;
  return trackedShows.find(show =>
    (result.tmdbId != null && show.tmdbId === result.tmdbId)
    || (result.tvmazeId != null && show.tvmazeId === result.tvmazeId),
  ) ?? null;
}
