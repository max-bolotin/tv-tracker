import { useEffect, useState } from 'react';
import type { ShowSearchResult, TrackedShow } from '../types';
import { api } from '../api/client';
import { ShowDetail } from './ShowDetail';

interface Props {
  result: ShowSearchResult;
  onClose: () => void;
  onTrack: (result: ShowSearchResult) => Promise<TrackedShow | undefined>; // create & add to tracked list; returns created show
  trackedShow?: TrackedShow | null; // existing tracked show if any
  onUpdate?: (updated: TrackedShow) => void; // notify parent about updates
}
export function SearchPreview({ result, onClose, onTrack, trackedShow, onUpdate }: Props) {
  const [loading, setLoading] = useState(false);
  const [show, setShow] = useState<TrackedShow | null>(trackedShow || null);

  useEffect(() => {
    let mounted = true;
    if (trackedShow) { setShow(trackedShow); return; }
    setLoading(true);
    api.getShowDetails(result.tmdbId, result.tvmazeId)
      .then(s => { if (mounted) setShow(s); })
      .catch(() => { if (mounted) setShow(null); })
      .finally(() => { if (mounted) setLoading(false); });
    return () => { mounted = false; };
  }, [result, trackedShow]);

  // Handler wrappers: if show is not persisted yet, create it when user toggles.
  const ensurePersistAndRun = async (op: () => Promise<TrackedShow>) => {
    if (!show) throw new Error('No show loaded');
    if (show.id) return await op();
    // ask parent to create and return the created show (handleAdd returns the created show)
    const created = onTrack ? await onTrack(result) : await api.addShow(show.tmdbId || undefined, show.tvmazeId || undefined);
    if (!created) throw new Error('Failed to create show');
    setShow(created);
    if (onUpdate) onUpdate(created);
    return await op();
  };

  const onToggleEpisode = (_id: string | null, seasonNum: number, epNum: number, watched: boolean) => {
    return ensurePersistAndRun(() => api.toggleEpisode((show!.id)!, seasonNum, epNum, watched));
  };
  const onToggleSeason = (_id: string | null, seasonNum: number, watched: boolean) => {
    return ensurePersistAndRun(() => api.toggleSeason((show!.id)!, seasonNum, watched));
  };
  const onToggleAll = (_id: string | null, watched: boolean) => {
    return ensurePersistAndRun(() => api.toggleAllWatched((show!.id)!, watched));
  };
  const onUpdateStatus = (_id: string | null, status: any) => {
    return ensurePersistAndRun(() => api.updateStatus((show!.id)!, status));
  };

  if (loading && !show) return (
    <div className="modal-overlay" onClick={onClose}><div className="modal">Loading…</div></div>
  );

  if (!show) return (
    <div className="modal-overlay" onClick={onClose}><div className="modal">Failed to load show details.</div></div>
  );

  return (
    <ShowDetail
      show={show}
      onClose={onClose}
      onUpdate={(u) => { setShow(u); if (onUpdate) onUpdate(u); }}
      onToggleEpisode={onToggleEpisode}
      onToggleSeason={onToggleSeason}
      onToggleAllWatched={onToggleAll}
      onUpdateStatus={onUpdateStatus}
    />
  );
}
