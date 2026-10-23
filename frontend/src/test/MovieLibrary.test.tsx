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

  it('shows the full movie release date when TMDB provides it', () => {
    render(
      <MovieLibrary
        movies={[{ ...plannedMovie, releaseDate: '2027-11-05' }]}
        onSelect={vi.fn()}
        onStatusChange={vi.fn()}
        onDelete={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    expect(screen.getByText('Release: Nov 5, 2027')).toBeInTheDocument();
  });

  it('shows the release month when TMDB provides only a year and month', () => {
    render(
      <MovieLibrary
        movies={[{ ...plannedMovie, releaseDate: '2027-11' }]}
        onSelect={vi.fn()}
        onStatusChange={vi.fn()}
        onDelete={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    expect(screen.getByText('Release: Nov 2027')).toBeInTheDocument();
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

  it('allows removing the last movie in a section', () => {
    const onDelete = vi.fn();
    render(
      <MovieLibrary
        movies={[plannedMovie, watchedMovie]}
        onSelect={vi.fn()}
        onStatusChange={vi.fn()}
        onDelete={onDelete}
        onRefresh={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Remove Watched film' }));

    expect(onDelete).toHaveBeenCalledWith(watchedMovie);
  });

  it('reorders movies only within their current section', () => {
    const movies: MovieEntry[] = [
      { id: 'planned-1', tmdbId: 1, title: 'Planned One', status: 'PLANNED' },
      { id: 'watched-1', tmdbId: 2, title: 'Watched One', status: 'WATCHED' },
      { id: 'planned-2', tmdbId: 3, title: 'Planned Two', status: 'PLANNED' },
      { id: 'watched-2', tmdbId: 4, title: 'Watched Two', status: 'WATCHED' },
    ];
    const onReorder = vi.fn();
    const { container } = render(
      <MovieLibrary
        movies={movies}
        onSelect={vi.fn()}
        onStatusChange={vi.fn()}
        onDelete={vi.fn()}
        onRefresh={vi.fn()}
        onReorder={onReorder}
      />,
    );
    const plannedOne = screen.getByTestId('movie-card-planned-1').parentElement!;
    const plannedTwo = screen.getByTestId('movie-card-planned-2').parentElement!;
    const watchedOne = screen.getByTestId('movie-card-watched-1').parentElement!;

    fireEvent.dragStart(plannedTwo);
    fireEvent.drop(plannedOne);

    expect(onReorder).toHaveBeenCalledWith([
      movies[2],
      movies[1],
      movies[0],
      movies[3],
    ]);

    onReorder.mockClear();
    fireEvent.dragStart(plannedOne);
    fireEvent.drop(watchedOne);

    expect(onReorder).not.toHaveBeenCalled();
    expect(container.querySelectorAll('.drag-wrapper')).toHaveLength(4);
  });
});
