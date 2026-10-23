import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MovieDetail } from '../components/MovieDetail';
import { api } from '../api/client';
import type { MovieEntry } from '../types';

vi.mock('../api/client', () => ({
  api: {
    updateMovieRating: vi.fn(),
  },
}));

const movie: MovieEntry = {
  id: 'movie/1',
  tmdbId: 1,
  title: 'Arrival',
  status: 'PLANNED',
};

describe('MovieDetail personal rating', () => {
  it('keeps an unset rating null unless the slider is changed', () => {
    vi.mocked(api.updateMovieRating).mockResolvedValue(movie);
    render(
      <MovieDetail
        movie={movie}
        onClose={vi.fn()}
        onUpdate={vi.fn()}
        onDelete={vi.fn()}
        tracked
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'My Rating' }));
    expect(screen.getByText('Not rated')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Rate!' }));

    expect(api.updateMovieRating).toHaveBeenCalledWith('movie/1', null);
  });

  it('allows choosing an explicit zero rating from an unset rating', () => {
    vi.mocked(api.updateMovieRating).mockResolvedValue(movie);
    render(
      <MovieDetail
        movie={movie}
        onClose={vi.fn()}
        onUpdate={vi.fn()}
        onDelete={vi.fn()}
        tracked
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'My Rating' }));
    fireEvent.click(screen.getByRole('button', { name: 'Set rating to 0' }));
    expect(screen.getByText('0/10')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Rate!' }));

    expect(api.updateMovieRating).toHaveBeenCalledWith('movie/1', 0);
  });

  it('does not create a default rating and saves null when cleared', async () => {
    vi.mocked(api.updateMovieRating).mockResolvedValue(movie);
    render(
      <MovieDetail
        movie={{ ...movie, personalRating: 8 }}
        onClose={vi.fn()}
        onUpdate={vi.fn()}
        onDelete={vi.fn()}
        tracked
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'My Rating: 8.0/10' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear rating' }));
    expect(screen.getByText('Not rated')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Rate!' }));

    expect(api.updateMovieRating).toHaveBeenCalledWith('movie/1', null);
  });
});
