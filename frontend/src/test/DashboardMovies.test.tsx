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
    addMovie: vi.fn(),
    getPopular: vi.fn(),
    searchShows: vi.fn(),
    searchMovies: vi.fn(),
    getMovieDetails: vi.fn(),
  },
}));

describe('Dashboard movie tab', () => {
  beforeEach(() => {
    localStorage.clear();
    sseListeners.clear();
    vi.mocked(api.addMovie).mockReset();
    vi.mocked(api.searchMovies).mockReset();
    vi.mocked(api.getMovieDetails).mockReset();
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

  it('opens movie search details and can track the movie from the detail view', async () => {
    vi.mocked(api.searchMovies).mockResolvedValue([{
      tmdbId: 7,
      title: 'Arrival',
      overview: 'A first-contact story',
    }]);
    vi.mocked(api.getMovieDetails).mockResolvedValue({
      tmdbId: 7,
      title: 'Arrival',
      overview: 'The full movie synopsis.',
      status: 'PLANNED',
      cast: [{
        name: 'Amy Adams',
        profilePath: 'https://images.example/amy.jpg',
        linkUrl: 'https://www.themoviedb.org/person/9273',
      }],
    });
    const trackedMovie = {
      id: 'movie-7',
      tmdbId: 7,
      title: 'Arrival',
      overview: 'The full movie synopsis.',
      status: 'PLANNED' as const,
      cast: [{
        name: 'Amy Adams',
        profilePath: 'https://images.example/amy.jpg',
        linkUrl: 'https://www.themoviedb.org/person/9273',
      }],
    };
    vi.mocked(api.addMovie).mockResolvedValue({ movie: trackedMovie, created: true });

    render(<Dashboard />);
    await waitFor(() => expect(api.getShows).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: 'My Movies' }));
    fireEvent.change(await screen.findByPlaceholderText('Search for movies...'), {
      target: { value: 'Arrival' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    fireEvent.click(await screen.findByRole('button', { name: /Arrival/ }));

    expect(await screen.findByText('The full movie synopsis.')).toBeInTheDocument();
    const actorLink = screen.getByRole('link', { name: 'Open Amy Adams profile' });
    expect(actorLink).toHaveAttribute('href', 'https://www.themoviedb.org/person/9273');
    expect(actorLink.querySelector('img')).toHaveAttribute('src', 'https://images.example/amy.jpg');
    expect(api.getMovieDetails).toHaveBeenCalledWith(7);

    fireEvent.click(screen.getByRole('button', { name: 'Track this movie' }));
    await waitFor(() => expect(api.addMovie).toHaveBeenCalledWith(7));
    expect(await screen.findByRole('button', { name: 'Mark as watched' })).toBeInTheDocument();
  });

  it('loads a missing description when opening an older tracked movie', async () => {
    vi.mocked(api.getMovies).mockResolvedValue([{
      id: 'movie-7',
      tmdbId: 7,
      title: 'Arrival',
      status: 'PLANNED',
    }]);
    vi.mocked(api.getMovieDetails).mockResolvedValue({
      tmdbId: 7,
      title: 'Arrival',
      overview: 'A first-contact story',
      status: 'PLANNED',
    });

    render(<Dashboard />);
    await waitFor(() => expect(api.getShows).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: 'My Movies' }));
    fireEvent.click(await screen.findByTestId('movie-card-movie-7'));

    expect(await screen.findByText('A first-contact story')).toBeInTheDocument();
    expect(api.getMovieDetails).toHaveBeenCalledWith(7);
  });
});
