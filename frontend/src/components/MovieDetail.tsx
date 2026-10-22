import { useState } from 'react';
import { api } from '../api/client';
import type { MovieEntry } from '../types';

interface Props {
  movie: MovieEntry;
  onClose: () => void;
  onUpdate: (movie: MovieEntry) => void;
  onDelete: () => void;
}

export function MovieDetail({ movie, onClose, onUpdate, onDelete }: Props) {
  const [ratingOpen, setRatingOpen] = useState(false);
  const [rating, setRating] = useState<number | null>(movie.personalRating ?? null);
  const [error, setError] = useState<string | null>(null);
  const watched = movie.status === 'WATCHED';

  const setStatus = async (status: 'PLANNED' | 'WATCHED') => {
    try {
      setError(null);
      const updated = await api.updateMovieStatus(movie.id, status);
      onUpdate(updated);
    } catch (cause) {
      console.error('Failed to update movie status', cause);
      setError('Could not update the movie status. Please try again.');
    }
  };

  const saveRating = async () => {
    try {
      setError(null);
      const updated = await api.updateMovieRating(movie.id, rating);
      onUpdate(updated);
      setRatingOpen(false);
    } catch (cause) {
      console.error('Failed to save movie rating', cause);
      setError('Could not save your rating. Please try again.');
    }
  };

  return (
    <div className="modal-overlay movie-detail-overlay" onClick={onClose}>
      <div className="modal movie-detail" onClick={event => event.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close movie details">✕</button>
        {movie.poster && <img className="movie-detail-poster" src={movie.poster} alt={movie.title} />}
        <div className="modal-meta">
          <h2>{movie.title}</h2>
          <div className="badges">
            <span className={`badge movie-status-${movie.status.toLowerCase()}`}>
              {watched ? `Watched${movie.watchedDate ? ` · ${movie.watchedDate}` : ''}` : 'Planned to watch'}
            </span>
            {movie.releaseDate && <span>{movie.releaseDate.slice(0, 4)}</span>}
            {movie.runtime != null && <span>{movie.runtime} min</span>}
          </div>
          <div className="modal-ratings">
            {movie.personalRating != null && <span className="rating-personal">★ {movie.personalRating}/10</span>}
            {movie.imdbRating && <span className="rating-imdb">IMDb {movie.imdbRating}/10</span>}
            {movie.rottenTomatoesScore && <span className="rating-rt">🍅 {movie.rottenTomatoesScore}</span>}
          </div>
          {error && <p className="movie-error" role="alert">{error}</p>}
          {movie.cast?.length ? (
            <p className="movie-cast">Cast: {movie.cast.map(actor => actor.name).join(', ')}</p>
          ) : null}
          <div className="movie-actions">
            <button className="primary-action" onClick={() => void setStatus(watched ? 'PLANNED' : 'WATCHED')}>
              {watched ? 'Move to Planned' : 'Mark as watched'}
            </button>
            <button className="secondary-action" onClick={() => {
              setRating(movie.personalRating ?? 6);
              setRatingOpen(true);
            }}>My Rating</button>
            <button className="untrack-btn" onClick={onDelete}>Remove movie</button>
          </div>
        </div>
      </div>
      {ratingOpen && (
        <div className="rating-modal-backdrop" onClick={event => {
          event.stopPropagation();
          setRatingOpen(false);
        }}>
          <div className="rating-modal" onClick={event => event.stopPropagation()}>
            <button className="modal-close" onClick={() => setRatingOpen(false)} aria-label="Close personal rating">✕</button>
            <h3>My Rating</h3>
            <div className="rating-editor">
              <input type="range" min={0} max={10} step={0.5} value={rating ?? 0}
                     onChange={event => setRating(Number(event.target.value))} aria-label="Personal rating" />
              <div className="rating-value-display">{(rating ?? 0).toFixed(1).replace(/\.0$/, '')}/10</div>
            </div>
            <div className="rating-modal-actions">
              <button type="button" className="secondary-action" onClick={() => setRatingOpen(false)}>Close</button>
              <button type="button" className="primary-action" onClick={() => void saveRating()}>Rate!</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
