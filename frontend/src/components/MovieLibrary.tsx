import { useRef } from 'react';
import type { MovieEntry } from '../types';
import { MovieCard } from './MovieCard';

interface Props {
  movies: MovieEntry[];
  onSelect: (movie: MovieEntry) => void;
  onStatusChange: (movie: MovieEntry) => void;
  onDelete: (movie: MovieEntry) => void;
  onRefresh: (movie: MovieEntry) => Promise<void>;
  onReorder?: (orderedMovies: MovieEntry[]) => void;
}

export function MovieLibrary({ movies, onSelect, onStatusChange, onDelete, onRefresh, onReorder }: Props) {
  const planned = movies.filter(movie => movie.status === 'PLANNED');
  const watched = movies.filter(movie => movie.status === 'WATCHED');
  const draggedId = useRef<string | null>(null);

  const renderCards = (items: MovieEntry[]) => (
    <div className="show-grid movie-grid">
      {items.map(movie => (
        <div
          key={movie.id}
          className="drag-wrapper"
          draggable
          onDragStart={() => { draggedId.current = movie.id; }}
          onDragOver={event => event.preventDefault()}
          onDrop={() => {
            const draggedMovieId = draggedId.current;
            draggedId.current = null;
            if (!draggedMovieId || draggedMovieId === movie.id || !onReorder) return;
            const start = items.findIndex(entry => entry.id === draggedMovieId);
            const end = items.findIndex(entry => entry.id === movie.id);
            if (start < 0 || end < 0) return;

            const reorderedSection = [...items];
            const [moved] = reorderedSection.splice(start, 1);
            reorderedSection.splice(end, 0, moved);
            let sectionIndex = 0;
            onReorder(movies.map(entry => entry.status === movie.status
              ? reorderedSection[sectionIndex++]
              : entry));
          }}
          onDragEnd={() => { draggedId.current = null; }}
        >
          <MovieCard
            movie={movie}
            onSelect={() => onSelect(movie)}
            onStatusChange={onStatusChange}
            onDelete={() => onDelete(movie)}
            onRefresh={() => onRefresh(movie)}
          />
        </div>
      ))}
    </div>
  );

  return (
    <div className="movie-library">
      <section aria-labelledby="planned-movies-heading">
        <h2 id="planned-movies-heading" className="section-heading">Planned to watch</h2>
        {planned.length
          ? renderCards(planned)
          : <p className="empty">{movies.length ? 'No planned movies.' : 'No movies yet. Search for a movie to add it!'}</p>}
      </section>
      <hr className="section-divider" />
      <section aria-labelledby="watched-movies-heading">
        <h2 id="watched-movies-heading" className="section-heading">Watched</h2>
        {watched.length ? renderCards(watched) : <p className="empty">No watched movies.</p>}
      </section>
    </div>
  );
}
