import { describe, expect, it } from 'vitest';
import { trackedForResult } from '../utils/trackedShowMatcher';
import type { ShowSearchResult, TrackedShow } from '../types';

describe('trackedForResult', () => {
  it('matches same-title results by TMDB ID, not title', () => {
    const results: ShowSearchResult[] = [
      { tmdbId: 67557, title: 'The Grand Tour', totalSeasons: 6 },
      { tmdbId: 329471, title: 'The Grand Tour', totalSeasons: 1 },
    ];
    const tracked: TrackedShow[] = [{
      id: 'tracked-original',
      tmdbId: 67557,
      title: 'The Grand Tour',
      totalSeasons: 6,
      productionStatus: 'ENDED',
      watchStatus: 'NOT_WATCHED',
      seasons: [],
    }];

    expect(trackedForResult(results[0], tracked)).toBe(tracked[0]);
    expect(trackedForResult(results[1], tracked)).toBeNull();
  });
});
