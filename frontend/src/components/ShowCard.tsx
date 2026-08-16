import { useState } from 'react';
import type { TrackedShow } from '../types';

const STATUS_LABELS: Record<string, string> = {
  WATCHING_NOW: 'Watching Now',
  NOT_WATCHED: 'Not Watched',
  UP_TO_DATE: 'Up to Date',
  FINISHED: 'Finished',
  DROPPED: 'Dropped',
};

interface Props {
  show: TrackedShow;
  onClick: () => void;
  onDelete: () => void;
  onRefresh: () => Promise<void>;
}

export function ShowCard({ show, onClick, onDelete, onRefresh }: Props) {
  const [refreshing, setRefreshing] = useState(false);

  const handleClick = (e: React.MouseEvent) => {
    if ((e.target as HTMLElement).closest('button')) return;
    onClick();
  };

  const handleRefresh = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <div className="show-card" onClick={handleClick}>
      {show.posterPath
        ? <img src={show.posterPath} alt={show.title} />
        : <div className="poster-placeholder">{show.title[0]}</div>
      }
      <div className="card-info">
        <h3>{show.title}</h3>
        <span className="seasons-count">{show.totalSeasons} season{show.totalSeasons !== 1 ? 's' : ''}</span>
        <span className={`badge production-${show.productionStatus.toLowerCase()}`}>
          {show.productionStatus === 'ONGOING' ? 'Ongoing' : 'Ended'}
        </span>
        <span className={`badge status-${show.watchStatus.toLowerCase()}`}>
          {STATUS_LABELS[show.watchStatus]}
        </span>
      </div>
      <button
        className={`refresh-card-btn${refreshing ? ' spinning' : ''}`}
        onClick={handleRefresh}
        title="Refresh metadata"
        disabled={refreshing}
      >⟳</button>
      <button
        className="delete-btn"
        onClick={e => { e.stopPropagation(); onDelete(); }}
        title="Remove"
      >✕</button>
    </div>
  );
}
