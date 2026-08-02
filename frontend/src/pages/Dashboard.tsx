import { useEffect, useRef, useState, useMemo } from 'react';
import type { TrackedShow, WatchStatus } from '../types';
import { api } from '../api/client';
import { ShowCard } from '../components/ShowCard';
import { ShowDetail } from '../components/ShowDetail';
import { SearchBar } from '../components/SearchBar';
import { ScrollToTop } from '../components/ScrollToTop';
import { RefreshButton } from '../components/RefreshButton';
import type { ShowSearchResult } from '../types';

const STATUS_ORDER: (WatchStatus)[] = [
  'WATCHING_NOW', 'NOT_WATCHED', 'UP_TO_DATE', 'FINISHED', 'DROPPED',
];

const STATUS_LABELS: Record<WatchStatus, string> = {
  WATCHING_NOW: 'Watching Now',
  NOT_WATCHED:  'Not Watched',
  UP_TO_DATE:   'Up to Date',
  FINISHED:     'Finished',
  DROPPED:      'Dropped',
};

const TABS: { label: string; value: WatchStatus | 'ALL' }[] = [
  { label: 'All',          value: 'ALL' },
  { label: 'Watching Now', value: 'WATCHING_NOW' },
  { label: 'Not Watched',  value: 'NOT_WATCHED' },
  { label: 'Up to Date',   value: 'UP_TO_DATE' },
  { label: 'Finished',     value: 'FINISHED' },
  { label: 'Dropped',      value: 'DROPPED' },
];

export function Dashboard() {
  // Master list — loaded once, never re-fetched on tab switch
  const [allShows, setAllShows] = useState<TrackedShow[]>([]);
  const [tab, setTab] = useState<WatchStatus | 'ALL'>('ALL');
  const [selected, setSelected] = useState<TrackedShow | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.getShows().then(setAllShows);
  }, []);

  // Pure in-memory filter — instant, no network
  const visibleShows = useMemo(
    () => tab === 'ALL' ? allShows : allShows.filter(s => s.watchStatus === tab),
    [allShows, tab],
  );

  // Groups for the "All" tab divider view
  const groups = useMemo(() => {
    if (tab !== 'ALL') return null;
    return STATUS_ORDER
      .map(status => ({ status, shows: allShows.filter(s => s.watchStatus === status) }))
      .filter(g => g.shows.length > 0);
  }, [allShows, tab]);

  const handleAdd = async (result: ShowSearchResult) => {
    try {
      const show = await api.addShow(result.tmdbId, result.tvmazeId);
      setAllShows(prev => [show, ...prev]);
    } catch (e) {
      alert('Failed to add show. Check console.');
      console.error(e);
    }
  };

  const handleDelete = async (id: string) => {
    await api.deleteShow(id);
    setAllShows(prev => prev.filter(s => s.id !== id));
    if (selected?.id === id) setSelected(null);
  };

  // Bug fix: replace in master list — filtered view re-derives instantly via useMemo
  const handleUpdate = (updated: TrackedShow) => {
    setAllShows(prev => prev.map(s => s.id === updated.id ? updated : s));
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
      const fresh = await api.getShows();
      setAllShows(fresh);
    } catch (err) {
      alert('Import failed.');
      console.error(err);
    } finally {
      e.target.value = '';
    }
  };

  const renderGrid = (shows: TrackedShow[]) => (
    <div className="show-grid">
      {shows.map(show => (
        <ShowCard
          key={show.id}
          show={show}
          onClick={() => setSelected(show)}
          onDelete={() => handleDelete(show.id)}
        />
      ))}
    </div>
  );

  return (
    <div className="dashboard">
      <header className="app-header">
        <h1>📺 TV Tracker</h1>
        <nav className="header-nav">
          <button onClick={handleExport}>Export</button>
          <button onClick={() => importRef.current?.click()}>Import</button>
          <input ref={importRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
          <RefreshButton onDone={() => api.getShows().then(setAllShows)} />
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

      {groups ? (
        // ALL tab: grouped sections with dividers
        groups.length === 0
          ? <p className="empty">No shows yet. Search and add one!</p>
          : groups.map((g, i) => (
              <div key={g.status}>
                {i > 0 && <hr className="section-divider" />}
                <h2 className="section-heading">{STATUS_LABELS[g.status]}</h2>
                {renderGrid(g.shows)}
              </div>
            ))
      ) : (
        // Single-status tab
        visibleShows.length === 0
          ? <p className="empty">No shows here yet.</p>
          : renderGrid(visibleShows)
      )}

      {selected && (
        <ShowDetail
          show={selected}
          onClose={() => setSelected(null)}
          onUpdate={handleUpdate}
        />
      )}

      <ScrollToTop />
    </div>
  );
}
