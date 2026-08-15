import type { ShowSearchResult } from '../types';

interface Props {
  result: ShowSearchResult;
  onClose: () => void;
  onTrack: (result: ShowSearchResult) => void;
  isTracked: boolean;
}

export function SearchPreview({ result, onClose, onTrack, isTracked }: Props) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose}>✕</button>
        <div className="modal-header">
          {result.posterPath && <img src={result.posterPath} alt={result.title} className="modal-poster" />}
          <div className="modal-meta">
            <h2>{result.title}</h2>
            <p className="overview">{result.overview}</p>
            <div className="badges">
              {result.productionStatus && (
                <span className={`badge production-${result.productionStatus.toLowerCase()}`}>
                  {result.productionStatus === 'ONGOING' ? 'Ongoing' : 'Ended'}
                </span>
              )}
              {result.totalSeasons > 0 && (
                <span className="badge status-not_watched">
                  {result.totalSeasons} season{result.totalSeasons !== 1 ? 's' : ''}
                </span>
              )}
            </div>
          </div>
        </div>
        <button
          className={`track-btn${isTracked ? ' track-btn-tracked' : ''}`}
          onClick={() => { if (!isTracked) { onTrack(result); onClose(); } }}
          disabled={isTracked}
        >
          {isTracked ? '✓ Already tracked' : '+ Track This Show'}
        </button>
      </div>
    </div>
  );
}
