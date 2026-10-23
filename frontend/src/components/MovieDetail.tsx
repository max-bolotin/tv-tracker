import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { MovieEntry, MoviePreview } from '../types';
import { formatMovieReleaseDate } from '../utils/formatMovieReleaseDate';

interface Props {
  movie: MovieEntry | MoviePreview;
  onClose: () => void;
  onUpdate: (movie: MovieEntry) => void;
  onDelete: () => void;
  tracked?: boolean;
  onTrack?: () => Promise<MovieEntry | undefined>;
}

export function MovieDetail({
  movie: initialMovie,
  onClose,
  onUpdate,
  onDelete,
  tracked,
  onTrack,
}: Props) {
  const [movie, setMovie] = useState<MovieEntry | MoviePreview>(initialMovie);
  const [isTracked, setIsTracked] = useState(tracked ?? Boolean(initialMovie.id));
  const [ratingOpen, setRatingOpen] = useState(false);
  const [rating, setRating] = useState<number | null>(initialMovie.personalRating ?? null);
  const [error, setError] = useState<string | null>(null);
  const watched = movie.status === 'WATCHED';
  const releaseDate = formatMovieReleaseDate(movie.releaseDate);

  useEffect(() => {
    setMovie(initialMovie);
    setRating(initialMovie.personalRating ?? null);
  }, [initialMovie]);

  useEffect(() => {
    setIsTracked(tracked ?? Boolean(initialMovie.id));
  }, [initialMovie, tracked]);

  const updateMovie = (updated: MovieEntry) => {
    setMovie(updated);
    onUpdate(updated);
  };

  const setStatus = async (status: 'PLANNED' | 'WATCHED') => {
    if (!isTracked || !movie.id) return;
    try {
      setError(null);
      const updated = await api.updateMovieStatus(movie.id, status);
      updateMovie(updated);
    } catch (cause) {
      console.error('Failed to update movie status', cause);
      setError('Could not update the movie status. Please try again.');
    }
  };

  const saveRating = async () => {
    if (!isTracked || !movie.id) return;
    try {
      setError(null);
      const updated = await api.updateMovieRating(movie.id, rating);
      updateMovie(updated);
      setRatingOpen(false);
    } catch (cause) {
      console.error('Failed to save movie rating', cause);
      setError('Could not save your rating. Please try again.');
    }
  };

  const track = async () => {
    if (!onTrack) return;
    try {
      setError(null);
      const trackedMovie = await onTrack();
      if (trackedMovie) {
        updateMovie(trackedMovie);
        setIsTracked(true);
      }
    } catch (cause) {
      console.error('Failed to track movie', cause);
      setError('Could not track this movie. Please try again.');
    }
  };

  return (
    <div className="modal-overlay movie-detail-overlay" onClick={onClose}>
      <div className="modal movie-detail" onClick={event => event.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close movie details">✕</button>
        <div className="modal-header">
          <div className="modal-poster-wrap">
            {movie.poster && <img className="modal-poster" src={movie.poster} alt={movie.title} />}
          </div>
          <div className="modal-meta">
            <h2>{movie.title}</h2>
            <div className="modal-action-row" style={{ marginTop: '0.5rem' }}>
              {isTracked ? (
                <>
                  <button className="untrack-btn" onClick={onDelete}>Untrack</button>
                  <button className="my-rating-btn" onClick={() => {
                    setRating(movie.personalRating ?? 6);
                    setRatingOpen(true);
                  }}>
                    {movie.personalRating != null ? `My Rating: ${movie.personalRating.toFixed(1)}/10` : 'My Rating'}
                  </button>
                  <button className="primary-action" onClick={() => void setStatus(watched ? 'PLANNED' : 'WATCHED')}>
                    {watched ? 'Move to Planned' : 'Mark as watched'}
                  </button>
                </>
              ) : (
                <button className="track-button" onClick={() => void track()}>Track this movie</button>
              )}
            </div>
            <div className="movie-meta-line">
              {releaseDate && <span>Release: {releaseDate}</span>}
              {movie.runtime != null && <span>{movie.runtime} min</span>}
              {isTracked && <span className={`badge movie-status-${movie.status.toLowerCase()}`}>
                {watched ? `Watched${movie.watchedDate ? ` · ${movie.watchedDate}` : ''}` : 'Planned to watch'}
              </span>}
            </div>
            {movie.overview && <p className="overview">{movie.overview}</p>}
            <div className="badges">
              {movie.imdbRating && (
                <a
                  className="rating-imdb"
                  href={movie.imdbId ? `https://www.imdb.com/title/${movie.imdbId}/` : undefined}
                  target={movie.imdbId ? '_blank' : undefined}
                  rel={movie.imdbId ? 'noreferrer noopener' : undefined}
                >
                  IMDb {movie.imdbRating}/10
                </a>
              )}
              {movie.rottenTomatoesScore && (
                <span className="rating-rt">🍅 {movie.rottenTomatoesScore}</span>
              )}
              {movie.personalRating != null && (
                <span className="rating-personal">★ {movie.personalRating.toFixed(1).replace(/\.0$/, '')}/10</span>
              )}
            </div>
            {error && <p className="movie-error" role="alert">{error}</p>}
            {movie.cast && movie.cast.length > 0 && (
              <div className="cast-section" aria-label="Cast members">
                <div className="cast-row">
                  {movie.cast.slice(0, 5).map(actor => (
                    <a
                      key={`${movie.tmdbId}-${actor.name}`}
                      className="cast-card"
                      href={actor.linkUrl || undefined}
                      target={actor.linkUrl ? '_blank' : undefined}
                      rel={actor.linkUrl ? 'noreferrer noopener' : undefined}
                      aria-label={actor.linkUrl ? `Open ${actor.name} profile` : `Actor ${actor.name}`}
                    >
                      {actor.profilePath ? (
                        <img src={actor.profilePath} alt={actor.name} className="cast-photo" />
                      ) : (
                        <div className="cast-photo cast-placeholder">{actor.name.charAt(0).toUpperCase()}</div>
                      )}
                      <span className="cast-name">{actor.name}</span>
                    </a>
                  ))}
                </div>
              </div>
            )}
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
              <div className="rating-scale-labels" aria-hidden="true">
                <span>0</span><span>2</span><span>4</span><span>6</span><span>8</span><span>10</span>
              </div>
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
