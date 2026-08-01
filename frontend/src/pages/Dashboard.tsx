import { useEffect, useRef, useState } from 'react';
import type { TrackedShow, WatchStatus } from '../types';
import { api } from '../api/client';
import { ShowCard } from '../components/ShowCard';
import { ShowDetail } from '../components/ShowDetail';
import { SearchBar } from '../components/SearchBar';
import type { ShowSearchResult } from '../types';

const TABS: { label: string; value: WatchStatus | 'ALL' }[] = [
  { label: 'All', value: 'ALL' },
  { label: 'Watching Now', value: 'WATCHING_NOW' },
  { label: 'Not Watched', value: 'NOT_WATCHED' },
  { label: 'Up to Date', value: 'UP_TO_DATE' },
  { label: 'Finished', value: 'FINISHED' },
  { label: 'Dropped', value: 'DROPPED' },
];

export function Dashboard() {
  const [shows, setShows] = useState<TrackedShow[]>([]);
  const [tab, setTab] = useState<WatchStatus | 'ALL'>('ALL');
  const [selected, setSelected] = useState<TrackedShow | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const load = async () => {
    setShows(await api.getShows(tab === 'ALL' ? undefined : tab));
  };

  useEffect(() => { load(); }, [tab]);

  const handleAdd = async (result: ShowSearchResult) => {
    try {
      const show = await api.addShow(result.tmdbId, result.tvmazeId);
      setShows(prev => [show, ...prev]);
    } catch (e) {
      alert('Failed to add show. Check console.');
      console.error(e);
    }
  };

  const handleDelete = async (id: string) => {
    await api.deleteShow(id);
    setShows(prev => prev.filter(s => s.id !== id));
  };

  const handleUpdate = (updated: TrackedShow) => {
    setShows(prev => prev.map(s => s.id === updated.id ? updated : s));
    setSelected(updated);
  };

  const handleExport = async () => {
    const blob = await api.exportData();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'tv-tracker-export.json';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await api.importData(file);
      await load();
    } catch (err) {
      alert('Import failed.');
      console.error(err);
    } finally {
      e.target.value = '';
    }
  };

  return (
    <div className="dashboard">
      <header className="app-header">
        <h1>📺 TV Tracker</h1>
        <nav className="header-nav">
          <button onClick={handleExport}>Export</button>
          <button onClick={() => importRef.current?.click()}>Import</button>
          <input ref={importRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
        </nav>
      </header>

      <SearchBar onAdd={handleAdd} />

      <div className="tabs">
        {TABS.map(t => (
          <button
            key={t.value}
            className={tab === t.value ? 'tab active' : 'tab'}
            onClick={() => setTab(t.value)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="show-grid">
        {shows.length === 0
          ? <p className="empty">No shows here yet. Search and add one!</p>
          : shows.map(show => (
              <ShowCard
                key={show.id}
                show={show}
                onClick={() => setSelected(show)}
                onDelete={() => handleDelete(show.id)}
              />
            ))
        }
      </div>

      {selected && (
        <ShowDetail
          show={selected}
          onClose={() => setSelected(null)}
          onUpdate={handleUpdate}
        />
      )}
    </div>
  );
}
