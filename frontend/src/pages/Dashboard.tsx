import { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import type { TrackedShow, WatchStatus } from '../types';
import { api } from '../api/client';
import { DraggableGrid } from '../components/DraggableGrid';
import { ShowDetail } from '../components/ShowDetail';
import { SearchBar, type SearchBarHandle } from '../components/SearchBar';
import { ScrollToTop } from '../components/ScrollToTop';
import { RefreshButton } from '../components/RefreshButton';
import type { ShowSearchResult } from '../types';

const STATUS_ORDER: WatchStatus[] = [
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
  const [allShows, setAllShows] = useState<TrackedShow[]>([]);
  const [tab, setTab] = useState<WatchStatus | 'ALL'>('ALL');
  const [selected, setSelected] = useState<TrackedShow | null>(null);
  const importRef = useRef<HTMLInputElement>(null);
  const searchBarRef = useRef<SearchBarHandle>(null);
  const reorderTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    api.getShows().then(setAllShows);
  }, []);

  const visibleShows = useMemo(
    () => tab === 'ALL' ? allShows : allShows.filter(s => s.watchStatus === tab),
    [allShows, tab],
  );

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

  const handleUpdate = (updated: TrackedShow) => {
    setAllShows(prev => prev.map(s => s.id === updated.id ? updated : s));
    setSelected(updated);
  };

  const handleReorder = useCallback((reordered: TrackedShow[]) => {
    setAllShows(reordered);
    // Debounce the API call — only persist after dragging stops for 400ms
    if (reorderTimer.current) clearTimeout(reorderTimer.current);
    reorderTimer.current = setTimeout(() => {
      api.reorder(reordered.map(s => s.id));
    }, 400);
  }, []);

  const handleExport = async () => {
    const blob = await api.exportData();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'tv-tracker-export.json';
    a.click();
    URL.revokeObjectURL(url);
  };

  const [importing, setImporting] = useState(false);
  const [importToast, setImportToast] = useState<string | null>(() => {
    const msg = sessionStorage.getItem('importToast');
    if (msg) { sessionStorage.removeItem('importToast'); return msg; }
    return null;
  });

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    setImporting(true);
    try {
      const result = await api.importData(file);
      console.info('[Import] complete:', result);
      if (result.failedTitles.length > 0) {
        sessionStorage.setItem('importToast', `Imported ${result.total} shows. Could not fetch metadata for: ${result.failedTitles.join(', ')}`);
      }
      window.location.reload();
    } catch (err) {
      console.error('[Import] failed:', err);
      setImporting(false);
      setImportToast('Import failed. Check the file and try again.');
    }
  };

  const handleRefreshShow = async (id: string) => {
    const show = allShows.find(s => s.id === id);
    try {
      const updated = await api.refreshShow(id);
      setAllShows(prev => prev.map(s => s.id === id ? updated : s));
      if (selected?.id === id) setSelected(updated);
    } catch {
      // No API data — fall back to searching by title
      if (show) searchBarRef.current?.triggerSearch(show.title);
    }
  };

  const gridProps = { shows: allShows, tab, onReorder: handleReorder, onSelect: setSelected, onDelete: handleDelete, onRefresh: handleRefreshShow };

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

      <SearchBar ref={searchBarRef} onAdd={handleAdd} />

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
        groups.length === 0
          ? <p className="empty">No shows yet. Search and add one!</p>
          : groups.map((g, i) => (
              <div key={g.status}>
                {i > 0 && <hr className="section-divider" />}
                <h2 className="section-heading">{STATUS_LABELS[g.status]}</h2>
                <DraggableGrid {...gridProps} visibleShows={g.shows} />
              </div>
            ))
      ) : (
        visibleShows.length === 0
          ? <p className="empty">No shows here yet.</p>
          : <DraggableGrid {...gridProps} visibleShows={visibleShows} />
      )}

      {selected && (
        <ShowDetail
          show={selected}
          onClose={() => setSelected(null)}
          onUpdate={handleUpdate}
        />
      )}

      <ScrollToTop />

      {importToast && (
        <div className="import-toast" onClick={() => setImportToast(null)}>
          ⚠️ {importToast}
        </div>
      )}

      {importing && (
        <div className="import-overlay">
          <div className="import-spinner" />
          <p>Importing & fetching metadata…</p>
        </div>
      )}
    </div>
  );
}
