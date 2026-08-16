import { useState } from 'react';
import type { TrackedShow, Episode, WatchStatus } from '../types';
import { api } from '../api/client';

interface Props {
  show: TrackedShow;
  onClose: () => void;
  onUpdate: (updated: TrackedShow) => void;
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
  const allWatched = show.seasons.length > 0 && show.seasons.every(s => s.episodes.length > 0 && s.episodes.every(e => e.watched));
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
  const { show: initialShow, onClose, onUpdate, onToggleEpisode, onToggleSeason, onToggleAllWatched, onUpdateStatus, onTrack, onUntrack } = props;
  const [show, setShow] = useState(initialShow);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const toggleExpand = (n: number) =>
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(n) ? next.delete(n) : next.add(n);
      return next;
    });

  const handleEpisode = async (seasonNum: number, epNum: number, watched: boolean) => {
    // Optimistic update
    const optimistic = applyEpisode(show, seasonNum, epNum, watched);
    setShow(optimistic);
    onUpdate(optimistic);
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

  const handleDropped = async (dropped: boolean) => {
    const newStatus = dropped ? 'DROPPED' : 'NOT_WATCHED';
    const optimistic = { ...show, watchStatus: newStatus as WatchStatus };
    setShow(optimistic);
    onUpdate(optimistic);
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
          {show.posterPath && <img src={show.posterPath} alt={show.title} className="modal-poster" />}
          <div className="modal-meta">
            <h2>{show.title}</h2>
            {/* Track / Untrack button: if an onUntrack handler provided, show Untrack (for My Shows context). Otherwise, show Already tracked (disabled) for previews */}
            <div style={{ marginTop: '0.5rem' }}>
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
            </div>
            <p className="overview">{show.overview}</p>
            <div className="badges">
              <span className={`badge production-${show.productionStatus.toLowerCase()}`}>
                {show.productionStatus === 'ONGOING' ? 'Ongoing' : 'Ended'}
              </span>
              <span className={`badge status-${show.watchStatus.toLowerCase()}`}>
                {show.watchStatus.replace(/_/g, ' ')}
              </span>
            </div>
          </div>
        </div>

        <label className="mark-all-watched">
          <input
            type="checkbox"
            checked={show.seasons.length > 0 && show.seasons.every(s => s.episodes.every(e => e.watched))}
            onChange={e => handleAllWatched(e.target.checked)}
          />
          Mark entire show as watched
        </label>

        <div className="seasons-list">
          {show.seasons.map(season => {
            const allWatched = season.episodes.every(e => e.watched);
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
