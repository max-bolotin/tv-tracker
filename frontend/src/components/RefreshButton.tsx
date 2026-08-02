import { useState } from 'react';
import { api } from '../api/client';

interface Props {
  onDone: () => void; // reload shows after refresh completes
}

export function RefreshButton({ onDone }: Props) {
  const [state, setState] = useState<'idle' | 'running' | 'done'>('idle');

  const handleClick = async () => {
    setState('running');
    try {
      await api.refresh();
      onDone();
      setState('done');
      setTimeout(() => setState('idle'), 2000);
    } catch {
      setState('idle');
    }
  };

  return (
    <button
      className="refresh-btn"
      onClick={handleClick}
      disabled={state === 'running'}
      title="Check for new episodes now"
    >
      {state === 'running' ? '⟳' : state === 'done' ? '✓' : '⟳'}
    </button>
  );
}
