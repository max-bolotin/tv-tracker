import { useEffect, useState } from 'react';
import type { TrackedShow, Episode, WatchStatus } from '../types';
import { api } from '../api/client';

interface Props {
  show: TrackedShow;
  onClose: () => void;
  onUpdate: (updated: TrackedShow) => void;
  onBeforeWrite?: () => void;
  // Optional handlers: if provided they are used instead of internal API calls.
  onToggleEpisode?: (id: string | null, seasonNum: number, epNum: number, watched: boolean) => Promise<TrackedShow>;
  onToggleSeason?: (id: string | null, seasonNum: number, watched: boolean) => Promise<TrackedShow>;
  onToggleAllWatched?: (id: string | null, watched: boolean) => Promise<TrackedShow>;
  onUpdateStatus?: (id: string | null, status: WatchStatus) => Promise<TrackedShow>;
  // Optional handler to create/track this show when user clicks 'Track this show'
  onTrack?: () => Promise<TrackedShow | undefined>;
  // Optional handler to untrack (delete) the show when viewing in My Shows context
  onUntrack?: () => Promise<void> | (() => void);
}

const TODAY = new Date().toISOString().slice(0, 10);

function ImdbText() {
  return <span className="rating-imdb-text">IMDb</span>;
}

function StarIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="rating-icon-svg" focusable="false">
      <path d="M12 17.3l-5.2 2.8 1-5.9L1.5 9.4l6-.9L12 2.9l4.5 5.6 6 .9-6.3 4.8 1 5.9L12 17.3z" fill="currentColor"/>
    </svg>
  );
}

function isAired(ep: Episode): boolean {
  return !ep.airDate || ep.airDate <= TODAY;
}

function formatDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

// Recalculate watchStatus client-side (mirrors backend logic)
function recalcStatus(show: TrackedShow): WatchStatus {
  if (show.watchStatus === 'DROPPED') return 'DROPPED';
  const anyWatched = show.seasons.some(s => s.episodes.some(e => e.watched));
  const allWatched = show.seasons.length > 0 && show.seasons.every(s => s.episodes.length > 0 && s.episodes.every(e => e.watched)); // unchanged, explicit about non-empty seasons
  if (!anyWatched) return 'NOT_WATCHED';
  if (allWatched) return show.productionStatus === 'ENDED' ? 'FINISHED' : 'UP_TO_DATE';
  return 'WATCHING_NOW';
}

function applyEpisode(show: TrackedShow, seasonNum: number, epNum: number, watched: boolean): TrackedShow {
  const updated = {
    ...show,
    seasons: show.seasons.map(s =>
      s.number !== seasonNum ? s : {
        ...s,
        episodes: s.episodes.map(e => e.number !== epNum ? e : { ...e, watched }),
      }
    ),
  };
  return { ...updated, watchStatus: recalcStatus(updated) };
}

function applySeason(show: TrackedShow, seasonNum: number, watched: boolean): TrackedShow {
  const updated = {
    ...show,
    seasons: show.seasons.map(s =>
      s.number !== seasonNum ? s : {
        ...s,
        episodes: s.episodes.map(e => isAired(e) ? { ...e, watched } : e),
      }
    ),
  };
  return { ...updated, watchStatus: recalcStatus(updated) };
}

function applyAllWatched(show: TrackedShow, watched: boolean): TrackedShow {
  const updated = {
    ...show,
    seasons: show.seasons.map(s => ({
      ...s,
      episodes: s.episodes.map(e => isAired(e) ? { ...e, watched } : e),
    })),
  };
  return { ...updated, watchStatus: recalcStatus(updated) };
}

export function ShowDetail(props: Props) {
  const { show: initialShow, onClose, onUpdate, onBeforeWrite, onToggleEpisode, onToggleSeason, onToggleAllWatched, onUpdateStatus, onTrack, onUntrack } = props;
  const [show, setShow] = useState(initialShow);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [ratingModalOpen, setRatingModalOpen] = useState(false);
  const [draftRating, setDraftRating] = useState<number | null>(initialShow.personalRating ?? null);

  // Keep local state in sync when parent updates the selected show (silent refreshes etc.)
  useEffect(() => {
    setShow(initialShow);
    setDraftRating(initialShow.personalRating ?? null);
  }, [initialShow]);

  useEffect(() => {
    const onPopState = () => {
      setRatingModalOpen(false);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const openRatingModal = () => {
    if (!window.history.state || (window.history.state as { modal?: string }).modal !== 'show-rating-modal') {
      window.history.pushState({ modal: 'show-rating-modal' }, '', window.location.pathname + window.location.search);
    }
    setDraftRating(show.personalRating ?? 6.0);
    setRatingModalOpen(true);
  };

  const closeRatingModal = () => {
    setRatingModalOpen(false);
    if ((window.history.state as { modal?: string } | null)?.modal === 'show-rating-modal') {
      window.history.back();
    }
  };

  const toggleExpand = (n: number) =>
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(n) ? next.delete(n) : next.add(n);
      return next;
    });

  const handleEpisode = async (seasonNum: number, epNum: number, watched: boolean) => {
    const optimistic = applyEpisode(show, seasonNum, epNum, watched);
    setShow(optimistic);
    onUpdate(optimistic);
    onBeforeWrite?.();
    try {
      const confirmed = await (onToggleEpisode
        ? onToggleEpisode(show.id || null, seasonNum, epNum, watched)
        : api.toggleEpisode(show.id!, seasonNum, epNum, watched)
      );
      setShow(confirmed);
      onUpdate(confirmed);
    } catch {
      // Roll back
      setShow(show);
      onUpdate(show);
    }
  };

  const handleSeason = async (seasonNum: number, watched: boolean) => {
    const optimistic = applySeason(show, seasonNum, watched);
    setShow(optimistic);
    onUpdate(optimistic);
    onBeforeWrite?.();
    try {
      const confirmed = await (onToggleSeason
        ? onToggleSeason(show.id || null, seasonNum, watched)
        : api.toggleSeason(show.id!, seasonNum, watched)
      );
      setShow(confirmed);
      onUpdate(confirmed);
    } catch {
      setShow(show);
      onUpdate(show);
    }
  };

  const handleAllWatched = async (watched: boolean) => {
    const optimistic = applyAllWatched(show, watched);
    setShow(optimistic);
    onUpdate(optimistic);
    onBeforeWrite?.();
    try {
      const confirmed = await (onToggleAllWatched
        ? onToggleAllWatched(show.id || null, watched)
        : api.toggleAllWatched(show.id!, watched)
      );
      setShow(confirmed);
      onUpdate(confirmed);
    } catch {
      setShow(show);
      onUpdate(show);
    }
  };

  const handlePersonalRating = async () => {
    if (draftRating === null) return;
    const value = Number(draftRating);
    try {
      const confirmed = await api.updatePersonalRating(show.id, value);
      setShow(confirmed);
      onUpdate(confirmed);
      setRatingModalOpen(false);
      if ((window.history.state as { modal?: string } | null)?.modal === 'show-rating-modal') {
        window.history.back();
      }
    } catch (error) {
      console.error('Rating save failed', error);
      alert('Failed to save your rating.');
    }
  };

  const handleDropped = async (dropped: boolean) => {
    const newStatus = dropped ? 'DROPPED' : 'NOT_WATCHED';
    const optimistic = { ...show, watchStatus: newStatus as WatchStatus };
    setShow(optimistic);
    onUpdate(optimistic);
    onBeforeWrite?.();
    try {
      const confirmed = await (onUpdateStatus
        ? onUpdateStatus(show.id || null, newStatus as WatchStatus)
        : api.updateStatus(show.id!, newStatus as WatchStatus)
      );
      setShow(confirmed);
      onUpdate(confirmed);
    } catch {
      setShow(show);
      onUpdate(show);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose}>✕</button>
        <div className="modal-header">
          <div className="modal-poster-wrap">
            {show.posterPath && <img src={show.posterPath} alt={show.title} className="modal-poster" />}
          </div>
          <div className="modal-meta">
            <h2>{show.title}</h2>
            {/* Track / Untrack button: if an onUntrack handler provided, show Untrack (for My Shows context). Otherwise, show Already tracked (disabled) for previews */}
            <div className="modal-action-row" style={{ marginTop: '0.5rem' }}>
              {show.id ? (
                onUntrack ? (
                  <button
                    className="untrack-btn"
                    onClick={async (e) => { e.stopPropagation();
                      try {
                        const maybe: any = onUntrack ? (onUntrack() as any) : undefined;
                        if (maybe && typeof maybe.then === 'function') await maybe;
                        onClose();
                      } catch (err) {
                        console.error('Untrack failed', err);
                        alert('Failed to untrack show.');
                      }
                    }}
                    style={{ background: '#737070', color: 'white', padding: '6px 10px', borderRadius: 6 }}
                  >
                    Untrack
                  </button>
                ) : (
                  <button className="track-button tracked" disabled style={{ background: '#ddd', color: '#333', padding: '6px 10px', borderRadius: 6 }}>
                    ✓ Already tracked
                  </button>
                )
              ) : (
                <button
                  className="track-button"
                  onClick={async (e) => { e.stopPropagation();
                    // prefer parent-provided creator, otherwise call API directly
                    try {
                      const created = onTrack ? await onTrack() : await api.addShow(show.tmdbId, show.tvmazeId);
                      if (created) { setShow(created); onUpdate(created); }
                    } catch (err) {
                      // If creation requires auth, bubble up a simple alert — parent typically handles sign-in flow when provided
                      console.error('Track failed', err);
                      alert('Failed to track show. Please sign in or try again.');
                    }
                  }}
                  style={{ background: '#ff7a18', color: 'white', padding: '6px 10px', borderRadius: 6 }}
                >
                  Track this show
                </button>
              )}
              {show.id && (
                <button
                  className="my-rating-btn"
                  onClick={e => {
                    e.stopPropagation();
                    openRatingModal();
                  }}
                >
                  {show.personalRating != null ? `My Rating: ${show.personalRating.toFixed(1)}/10` : 'My Rating'}
                </button>
              )}
            </div>
            <p className="overview">{show.overview}</p>
            {show.cast && show.cast.length > 0 && (
              <div className="cast-section" aria-label="Cast members">
                <div className="cast-row">
                  {show.cast.slice(0, 5).map(actor => (
                    <a
                      key={`${show.id}-${actor.name}`}
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
            <div className="badges">
              <span className={`badge production-${show.productionStatus.toLowerCase()}`}>
                {show.productionStatus === 'ONGOING' ? 'Ongoing' : 'Ended'}
              </span>
              <span className={`badge status-${show.watchStatus.toLowerCase()}`}>
                {show.watchStatus.replace(/_/g, ' ')}
              </span>
              {show.personalRating != null && (
                <span className="rating-personal" aria-label={`Personal rating ${show.personalRating} out of 10`}>
                  <StarIcon />
                  {show.personalRating.toFixed(1).replace(/\.0$/, '')}/10
                </span>
              )}
              {show.imdbRating && (
                <a
                  className="rating-imdb"
                  href={show.imdbId ? `https://www.imdb.com/title/${show.imdbId}/` : undefined}
                  target={show.imdbId ? '_blank' : undefined}
                  rel={show.imdbId ? 'noreferrer noopener' : undefined}
                  aria-label={`IMDb rating ${show.imdbRating} out of 10`}
                >
                  <ImdbText />
                  {show.imdbRating}/10
                </a>
              )}
              {show.rtRating && (
                <span className="rating-rt" aria-label={`Rotten Tomatoes score ${show.rtRating}`}>
                  <span aria-hidden="true">🍅</span> {show.rtRating}
                </span>
              )}
            </div>
          </div>
        </div>

        {ratingModalOpen && (
          <div className="rating-modal-backdrop" onClick={closeRatingModal}>
            <div className="rating-modal" onClick={e => e.stopPropagation()}>
             <button className="modal-close" onClick={closeRatingModal} aria-label="Close personal rating">✕</button>
             <h3>My Rating</h3>
             <div className="rating-editor">
               <input
                 type="range"
                 min={0}
                 max={10}
                 step={0.5}
                 value={draftRating ?? 0}
                 onChange={e => setDraftRating(Number(e.target.value))}
                 aria-label="Personal rating"
               />
               <div className="rating-scale-labels" aria-hidden="true">
                 <span>0</span>
                 <span>2</span>
                 <span>4</span>
                 <span>6</span>
                 <span>8</span>
                 <span>10</span>
               </div>
               <div className="rating-value-display">
                 {(draftRating ?? 0).toFixed(1).replace(/\.0$/, '')}/10
               </div>
             </div>
             <div className="rating-modal-actions">
               <button type="button" className="secondary-action" onClick={closeRatingModal}>Close</button>
               <button type="button" className="primary-action" onClick={handlePersonalRating}>Rate!</button>
             </div>
            </div>
          </div>
        )}

        <label className="mark-all-watched">
          <input
            type="checkbox"
            checked={show.seasons.length > 0 && show.seasons.every(s => s.episodes.length > 0 && s.episodes.every(e => e.watched))}
            onChange={e => handleAllWatched(e.target.checked)}
          />
          Mark entire show as watched
        </label>

        <div className="seasons-list">
          {show.seasons.map(season => {
            const allWatched = season.episodes.length > 0 && season.episodes.every(e => e.watched);
            const isOpen = expanded.has(season.number);
            const airedCount = season.episodes.filter(isAired).length;
            const watchedCount = season.episodes.filter(e => e.watched).length;

            return (
              <div key={season.number} className="season-accordion">
                <div className="season-header" onClick={() => toggleExpand(season.number)}>
                  <span>
                    {isOpen ? '▾' : '▸'} Season {season.number}
                    <span className="season-progress"> {watchedCount}/{airedCount} watched</span>
                  </span>
                  <label onClick={e => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={allWatched}
                      onChange={e => handleSeason(season.number, e.target.checked)}
                    />
                    All watched
                  </label>
                </div>
                {isOpen && (
                  <ul className="episode-list">
                    {season.episodes.map(ep => {
                      const aired = isAired(ep);
                      return (
                        <li key={ep.number} className={aired ? '' : 'episode-unaired'}>
                          <label>
                            <input
                              type="checkbox"
                              checked={ep.watched}
                              disabled={!aired}
                              onChange={e => handleEpisode(season.number, ep.number, e.target.checked)}
                            />
                            <span className="ep-label">
                              <span>E{ep.number} — {ep.name}</span>
                              {ep.airDate && (
                                <span className="ep-airdate">
                                  {aired ? formatDate(ep.airDate) : `Airs ${formatDate(ep.airDate)}`}
                                </span>
                              )}
                            </span>
                          </label>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            );
          })}
        </div>

        <label className="mark-dropped">
          <input
            type="checkbox"
            checked={show.watchStatus === 'DROPPED'}
            onChange={e => handleDropped(e.target.checked)}
          />
          Stopped watching (Dropped)
        </label>
      </div>
    </div>
  );
}
