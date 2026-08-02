import { useRef } from 'react';
import type { TrackedShow, WatchStatus } from '../types';
import { ShowCard } from './ShowCard';

interface Props {
  shows: TrackedShow[];           // the full ordered master list (all statuses)
  visibleShows: TrackedShow[];    // what's actually rendered in this grid
  tab: WatchStatus | 'ALL';
  onReorder: (reordered: TrackedShow[]) => void;
  onSelect: (show: TrackedShow) => void;
  onDelete: (id: string) => void;
}

export function DraggableGrid({ shows, visibleShows, tab, onReorder, onSelect, onDelete }: Props) {
  const dragId = useRef<string | null>(null);

  const handleDragStart = (id: string) => {
    dragId.current = id;
  };

  const handleDrop = (targetId: string) => {
    const srcId = dragId.current;
    dragId.current = null;
    if (!srcId || srcId === targetId) return;

    const src = shows.find(s => s.id === srcId)!;
    const target = shows.find(s => s.id === targetId)!;

    let next: TrackedShow[];

    if (tab !== 'ALL') {
      // Single-tier tab: free reorder within the visible list
      next = reorderWithin(shows, srcId, targetId);
    } else {
      // All tab: src stays in its own tier
      if (src.watchStatus === target.watchStatus) {
        // Same tier — normal reorder
        next = reorderWithin(shows, srcId, targetId);
      } else {
        // Different tier — snap src to first or last position of its own tier
        const srcIndex = shows.findIndex(s => s.id === srcId);
        const targetIndex = shows.findIndex(s => s.id === targetId);
        const tierShows = shows.filter(s => s.watchStatus === src.watchStatus);

        const snapTargetId = targetIndex < srcIndex ? tierShows[0].id : tierShows[tierShows.length - 1].id;
        if (snapTargetId === srcId) return;
        next = reorderWithin(shows, srcId, snapTargetId);
      }
    }

    onReorder(next);
  };

  return (
    <div className="show-grid">
      {visibleShows.map(show => (
        <div
          key={show.id}
          draggable
          onDragStart={() => handleDragStart(show.id)}
          onDragOver={e => e.preventDefault()}
          onDrop={() => handleDrop(show.id)}
          className="drag-wrapper"
        >
          <ShowCard
            show={show}
            onClick={() => onSelect(show)}
            onDelete={() => onDelete(show.id)}
          />
        </div>
      ))}
    </div>
  );
}

function reorderWithin(shows: TrackedShow[], srcId: string, targetId: string): TrackedShow[] {
  const next = [...shows];
  const srcIdx = next.findIndex(s => s.id === srcId);
  const tgtIdx = next.findIndex(s => s.id === targetId);
  const [item] = next.splice(srcIdx, 1);
  next.splice(tgtIdx, 0, item);
  return next;
}
