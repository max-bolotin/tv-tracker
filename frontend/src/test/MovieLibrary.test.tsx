import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MovieEntry } from '../types';
import { MovieLibrary } from '../components/MovieLibrary';

const plannedMovie: MovieEntry = {
  id: 'm1',
  tmdbId: 1,
  title: 'Planned film',
  status: 'PLANNED',
  imdbRating: '8.1',
  rottenTomatoesScore: '93%',
};
const watchedMovie: MovieEntry = {
  id: 'm2',
  tmdbId: 2,
  title: 'Watched film',
  status: 'WATCHED',
  watchedDate: '2026-10-20',
};

describe('MovieLibrary', () => {
  it('renders planned and watched sections separated by a divider without show episode details', () => {
    render(
      <MovieLibrary
        movies={[plannedMovie, watchedMovie]}
        onSelect={vi.fn()}
        onStatusChange={vi.fn()}
        onDelete={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Planned to watch' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Watched' })).toBeInTheDocument();
    expect(screen.getByText('Planned film')).toBeInTheDocument();
    expect(screen.getByText('Watched film')).toBeInTheDocument();
    expect(document.querySelector('.movie-library > .section-divider')).toBeInTheDocument();
    expect(screen.queryByText(/season|episode/i)).not.toBeInTheDocument();
  });

  it('moves movies between sections when marked watched and unmarked', () => {
    const changes: MovieEntry[] = [];
    function StatefulLibrary() {
      const [movies, setMovies] = useState([plannedMovie]);
      return (
        <MovieLibrary
          movies={movies}
          onSelect={vi.fn()}
          onStatusChange={movie => {
            changes.push(movie);
            setMovies([movie]);
          }}
          onDelete={vi.fn()}
          onRefresh={vi.fn()}
        />
      );
    }

    render(<StatefulLibrary />);
    fireEvent.click(screen.getByRole('button', { name: 'Mark Planned film as watched' }));
    expect(screen.getByText('No planned movies.')).toBeInTheDocument();
    expect(screen.getByText('Planned film')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Unmark Planned film as watched' }));
    expect(screen.getByText('No watched movies.')).toBeInTheDocument();
    expect(changes.map(movie => movie.status)).toEqual(['WATCHED', 'PLANNED']);
  });
});
