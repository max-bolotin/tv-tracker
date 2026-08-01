import { useState } from 'react';
import type { TrackedShow } from '../types';
import { api } from '../api/client';

interface Props {
  show: TrackedShow;
  onClose: () => void;
  onUpdate: (updated: TrackedShow) => void;
}

export function ShowDetail({ show: initialShow, onClose, onUpdate }: Props) {
  const [show, setShow] = useState(initialShow);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const toggleExpand = (n: number) =>
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(n) ? next.delete(n) : next.add(n);
      return next;
    });

  const handleEpisode = async (seasonNum: number, epNum: number, watched: boolean) => {
    const updated = await api.toggleEpisode(show.id, seasonNum, epNum, watched);
    setShow(updated);
    onUpdate(updated);
  };

  const handleSeason = async (seasonNum: number, watched: boolean) => {
    const updated = await api.toggleSeason(show.id, seasonNum, watched);
    setShow(updated);
    onUpdate(updated);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose}>✕</button>
        <div className="modal-header">
          {show.posterPath && <img src={show.posterPath} alt={show.title} className="modal-poster" />}
          <div className="modal-meta">
            <h2>{show.title}</h2>
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

        <div className="seasons-list">
          {show.seasons.map(season => {
            const allWatched = season.episodes.every(e => e.watched);
            const isOpen = expanded.has(season.number);
            return (
              <div key={season.number} className="season-accordion">
                <div className="season-header" onClick={() => toggleExpand(season.number)}>
                  <span>{isOpen ? '▾' : '▸'} Season {season.number}</span>
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
                    {season.episodes.map(ep => (
                      <li key={ep.number}>
                        <label>
                          <input
                            type="checkbox"
                            checked={ep.watched}
                            onChange={e => handleEpisode(season.number, ep.number, e.target.checked)}
                          />
                          E{ep.number} — {ep.name}
                        </label>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
