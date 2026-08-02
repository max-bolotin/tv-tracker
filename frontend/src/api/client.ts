import type { TrackedShow, ShowSearchResult, WatchStatus } from '../types';

const BASE = '/api';

async function req<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, options);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new Error(`Non-JSON response (${res.status}): ${text.slice(0, 200)}`);
  }
}

export const api = {
  getShows: (status?: WatchStatus) =>
    req<TrackedShow[]>('/shows' + (status ? `?status=${status}` : '')),

  getShow: (id: string) => req<TrackedShow>(`/shows/${id}`),

  searchShows: (q: string) => req<ShowSearchResult[]>(`/shows/search?q=${encodeURIComponent(q)}`),

  addShow: (tmdbId?: number, tvmazeId?: number) =>
    req<TrackedShow>('/shows', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tmdbId, tvmazeId }),
    }),

  deleteShow: (id: string) => req<void>(`/shows/${id}`, { method: 'DELETE' }),

  updateStatus: (id: string, status: WatchStatus) =>
    req<TrackedShow>(`/shows/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }),

  toggleEpisode: (id: string, season: number, episode: number, watched: boolean) =>
    req<TrackedShow>(`/shows/${id}/seasons/${season}/episodes/${episode}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ watched }),
    }),

  toggleSeason: (id: string, season: number, watched: boolean) =>
    req<TrackedShow>(`/shows/${id}/seasons/${season}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ watched }),
    }),

  toggleAllWatched: (id: string, watched: boolean) =>
    req<TrackedShow>(`/shows/${id}/watched`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ watched }),
    }),

  exportData: () => fetch(BASE + '/data/export').then(r => r.blob()),

  importData: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return req<{ total: number; failedTitles: string[] }>('/data/import', { method: 'POST', body: form });
  },

  refresh: () => req<string>('/data/refresh', { method: 'POST' }),

  refreshShow: (id: string) => req<TrackedShow>(`/shows/${id}/refresh`, { method: 'POST' }),

  reorder: (orderedIds: string[]) =>
    req<void>('/shows/reorder', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(orderedIds),
    }),
};
