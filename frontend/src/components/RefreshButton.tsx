import { useState } from 'react';
import { ApiRequestError, api } from '../api/client';

interface Props {
  onDone: () => void; // reload shows after refresh completes
  label?: string;
}

export function RefreshButton({ onDone, label }: Props) {
  const [state, setState] = useState<'idle' | 'running' | 'done'>('idle');

  const showToast = (message: string) => {
    window.dispatchEvent(new CustomEvent('app-toast', {
      detail: { message, durationMs: 4000 },
    }));
  };

  const remainingTime = (nextRefreshAt: string) => {
    const minutes = Math.max(1, Math.ceil((Date.parse(nextRefreshAt) - Date.now()) / 60000));
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    if (hours === 0) return `${remainingMinutes} minute${remainingMinutes === 1 ? '' : 's'}`;
    if (remainingMinutes === 0) return `${hours} hour${hours === 1 ? '' : 's'}`;
    return `${hours} hour${hours === 1 ? '' : 's'} ${remainingMinutes} minute${remainingMinutes === 1 ? '' : 's'}`;
  };

  const handleClick = async () => {
    setState('running');
    try {
      const result = await api.refresh();
      onDone();
      showToast(`Next refresh available in ${remainingTime(result.nextRefreshAt)}.`);
      setState('done');
      setTimeout(() => setState('idle'), 2000);
    } catch (error) {
      let message = 'Refresh failed. Your existing data was kept.';
      if (error instanceof ApiRequestError && error.status === 429) {
        try {
          const body = JSON.parse(error.body) as { nextRefreshAt?: string };
          if (body.nextRefreshAt) {
            message = `Next refresh available in ${remainingTime(body.nextRefreshAt)}.`;
          }
        } catch {
          // Keep the generic refresh failure message if the server response is malformed.
        }
      }
      showToast(message);
      setState('idle');
    }
  };

  return (
    <button
      className="refresh-btn"
      onClick={handleClick}
      disabled={state === 'running'}
      title="Check for new episodes now"
      aria-label={label ?? 'Refresh'}
    >
      {state === 'running' ? '⟳' : state === 'done' ? '✓' : '⟳'}{label ? ` ${label}` : ''}
    </button>
  );
}
