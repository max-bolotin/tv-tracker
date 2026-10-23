import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';

describe('movie API client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('encodes imported movie IDs before using them in endpoint paths', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await api.deleteMovie('movie/id ?');

    expect(fetchMock).toHaveBeenCalledWith('/api/movies/movie%2Fid%20%3F', { method: 'DELETE' });
  });
});
