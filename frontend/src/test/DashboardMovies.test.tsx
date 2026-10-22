import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Dashboard } from '../pages/Dashboard';
import { api } from '../api/client';

const sseListeners = new Map<string, () => void>();

vi.mock('../api/client', () => ({
  api: {
    getMe: vi.fn(),
    getShows: vi.fn(),
    getMovies: vi.fn(),
    addShow: vi.fn(),
    getPopular: vi.fn(),
    searchShows: vi.fn(),
    searchMovies: vi.fn(),
  },
}));

describe('Dashboard movie tab', () => {
  beforeEach(() => {
    localStorage.clear();
    sseListeners.clear();
    HTMLElement.prototype.scrollIntoView = vi.fn();
    vi.mocked(api.getMe).mockResolvedValue({ id: 'user-1', name: 'Test user' });
    vi.mocked(api.getShows).mockResolvedValue([]);
    vi.mocked(api.getMovies).mockResolvedValue([]);
    vi.mocked(api.getPopular).mockResolvedValue([]);
    vi.stubGlobal('EventSource', class {
      addEventListener(name: string, callback: () => void) {
        sseListeners.set(name, callback);
      }
      close() {}
    });
  });

  it('places My Movies last and shows its sections with movie search after selecting it', async () => {
    render(<Dashboard />);
    await waitFor(() => expect(api.getShows).toHaveBeenCalled());
    const tabs = screen.getAllByRole('button', { name: /Trending now|My Shows|Watching Now|Not Watched|Up to Date|Finished|Dropped|My Movies/ });
    expect(tabs.at(-1)).toHaveTextContent('My Movies');

    fireEvent.click(tabs.at(-1)!);

    expect(await screen.findByPlaceholderText('Search for movies...')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Planned to watch' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Watched' })).toBeInTheDocument();
    expect(document.querySelector('.movie-library > .section-divider')).toBeInTheDocument();

    const showFetchesBeforeEvent = vi.mocked(api.getShows).mock.calls.length;
    sseListeners.get('movies-changed')?.();
    await waitFor(() => expect(api.getMovies).toHaveBeenCalledTimes(2));
    expect(api.getShows).toHaveBeenCalledTimes(showFetchesBeforeEvent);
  });

  it('requires login for My Movies for unauthenticated users', async () => {
    vi.mocked(api.getMe).mockRejectedValue(new Error('Unauthenticated'));
    render(<Dashboard />);

    const moviesTab = await screen.findByRole('button', { name: 'My Movies' });
    fireEvent.click(moviesTab);

    expect(await screen.findByText('Sign in to continue')).toBeInTheDocument();
    expect(screen.getByText(/personal movie library/)).toBeInTheDocument();
  });

  it('continues pending show handling when movie loading fails', async () => {
    localStorage.setItem('postLoginAdd', JSON.stringify({ tmdbId: 42 }));
    vi.mocked(api.getMovies).mockRejectedValue(new Error('Movies unavailable'));
    vi.mocked(api.addShow).mockResolvedValue({
      id: 'show-42',
      tmdbId: 42,
      title: 'Test show',
      totalSeasons: 0,
      productionStatus: 'ONGOING',
      watchStatus: 'NOT_WATCHED',
      seasons: [],
    });
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<Dashboard />);

    await waitFor(() => expect(api.addShow).toHaveBeenCalledWith(42, undefined));
    expect(localStorage.getItem('postLoginAdd')).toBeNull();
    expect(consoleError).toHaveBeenCalledWith('Failed to load movies', expect.any(Error));
    consoleError.mockRestore();
  });
});
