import { useState } from 'react';
import type { MovieEntry } from '../types';

interface Props {
  movie: MovieEntry;
  onSelect: () => void;
  onStatusChange: (movie: MovieEntry) => void;
  onDelete: () => void;
  onRefresh: () => Promise<void>;
}

export function MovieCard({ movie, onSelect, onStatusChange, onDelete, onRefresh }: Props) {
  const [refreshing, setRefreshing] = useState(false);
  const watched = movie.status === 'WATCHED';

  const refresh = async (event: React.MouseEvent) => {
    event.stopPropagation();
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <div className="show-card movie-card" onClick={onSelect} data-testid={`movie-card-${movie.id}`}>
      {movie.poster
        ? <img src={movie.poster} alt={movie.title} />
        : <div className="poster-placeholder">{movie.title[0]}</div>}
      <div className="card-info">
        <h3>{movie.title}</h3>
        {movie.releaseDate && <span className="seasons-count">{movie.releaseDate.slice(0, 4)}</span>}
        {movie.runtime != null && <span className="seasons-count">{movie.runtime} min</span>}
        <span className={`badge movie-status-${movie.status.toLowerCase()}`}>
          {watched ? 'Watched' : 'Planned to watch'}
        </span>
        {(movie.personalRating != null || movie.imdbRating || movie.rottenTomatoesScore) && (
          <div className="card-ratings">
            {movie.personalRating != null && <span className="rating-personal">★ {movie.personalRating.toFixed(1).replace(/\.0$/, '')}/10</span>}
            {movie.imdbRating && <span className="rating-imdb">IMDb {movie.imdbRating}</span>}
            {movie.rottenTomatoesScore && <span className="rating-rt">🍅 {movie.rottenTomatoesScore}</span>}
          </div>
        )}
      </div>
      <button
        className={`refresh-card-btn${refreshing ? ' spinning' : ''}`}
        onClick={refresh}
        title="Refresh metadata"
        disabled={refreshing}
        aria-label={`Refresh ${movie.title}`}
      >⟳</button>
      <button className="delete-btn" onClick={event => { event.stopPropagation(); onDelete(); }}
              title="Remove" aria-label={`Remove ${movie.title}`}>✕</button>
      <button
        className="movie-seen-toggle"
        onClick={event => {
          event.stopPropagation();
          onStatusChange({...movie, status: watched ? 'PLANNED' : 'WATCHED',
            watchedDate: watched ? undefined : new Date().toISOString().slice(0, 10)});
        }}
        aria-label={watched ? `Unmark ${movie.title} as watched` : `Mark ${movie.title} as watched`}
      >
        {watched ? 'Undo watched' : 'Mark watched'}
      </button>
    </div>
  );
}
