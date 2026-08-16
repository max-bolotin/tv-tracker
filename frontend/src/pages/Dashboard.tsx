import { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import type { TrackedShow, WatchStatus, CurrentUser } from '../types';
import type { ShowSearchResult } from '../types';
import { api } from '../api/client';
import { DraggableGrid } from '../components/DraggableGrid';
import { ShowDetail } from '../components/ShowDetail';
import { SearchBar, type SearchBarHandle } from '../components/SearchBar';
import { SearchPreview } from '../components/SearchPreview';
import { ScrollToTop } from '../components/ScrollToTop';
import { RefreshButton } from '../components/RefreshButton';

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

const TABS: { label: string; value: WatchStatus | 'ALL' | 'POPULAR' }[] = [
  { label: 'Trending now',  value: 'POPULAR' },
  { label: 'My Shows (All)', value: 'ALL' },
  { label: 'Watching Now', value: 'WATCHING_NOW' },
  { label: 'Not Watched',  value: 'NOT_WATCHED' },
  { label: 'Up to Date',   value: 'UP_TO_DATE' },
  { label: 'Finished',     value: 'FINISHED' },
  { label: 'Dropped',      value: 'DROPPED' },
];

export function Dashboard() {
  const [allShows, setAllShows] = useState<TrackedShow[]>([]);
  const [tab, setTab] = useState<WatchStatus | 'ALL' | 'POPULAR'>('POPULAR');
  const [popularShows, setPopularShows] = useState<ShowSearchResult[]>([]);
  const popularRef = useRef<HTMLDivElement | null>(null);
  const [popularRows, setPopularRows] = useState(0); // number of rows currently requested
  const MIN_POPULAR = 20;
  const [selected, setSelected] = useState<TrackedShow | null>(null);
  const [preview, setPreview] = useState<ShowSearchResult | null>(null);
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const importRef = useRef<HTMLInputElement>(null);
  const searchBarRef = useRef<SearchBarHandle>(null);
  const reorderTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const localWrites = useRef(0);
  const [menuOpen, setMenuOpen] = useState(false);

  function dedupeShows(shows: any[]) {
    const seen = new Set<string|number>();
    const out: any[] = [];
    for (const s of shows) {
      const id = s.tmdbId ?? s.tvmazeId ?? (s.title || '').toLowerCase();
      if (id == null) continue;
      if (seen.has(id)) continue;
      seen.add(id);
      out.push(s);
    }
    return out;
  }

  useEffect(() => {
    api.getMe()
      .then(user => {
        setCurrentUser(user);
        const post = localStorage.getItem('postLoginTab');
        if (user && post) {
          setTab(post as any);
          localStorage.removeItem('postLoginTab');
        }
      })
      .catch(() => setCurrentUser(null))
      .finally(() => setAuthLoading(false));
  }, []);

  useEffect(() => {
    if (!currentUser) return;
    let mounted = true;
    (async () => {
      try {
        const shows = await api.getShows();
        if (!mounted) return;
        setAllShows(shows);

        const pending = localStorage.getItem('postLoginAdd');
        if (!pending) return;
        localStorage.removeItem('postLoginAdd');
        const parsed = JSON.parse(pending);
        // Check if it is already tracked
        const existing = (() => {
          const byId = shows.find(s => (parsed.tmdbId && s.tmdbId === parsed.tmdbId) || (parsed.tvmazeId && s.tvmazeId === parsed.tvmazeId));
          if (byId) return byId;
          const title = parsed.title?.toLowerCase().trim();
          if (title) return shows.find(s => s.title && s.title.toLowerCase().trim() === title) ?? null;
          return null;
        })();

        if (existing) {
          // Open card and show a non-blocking toast that it's already tracked
          setTab(existing.watchStatus || 'ALL');
          openModal(() => setSelected(existing));
          setImportToast(`Show already tracked: ${existing.title}`);
          setTimeout(() => setImportToast(null), 5000);
          return;
        }

        try {
          const created = await api.addShow(parsed.tmdbId, parsed.tvmazeId);
          if (!mounted) return;
          // Deduplicate: if already present in allShows, open existing instead of inserting duplicate
          setAllShows(prev => {
            const exists = prev.find(s => (created.tmdbId && s.tmdbId === created.tmdbId) || (created.tvmazeId && s.tvmazeId === created.tvmazeId) || (created.title && s.title && s.title.toLowerCase().trim() === created.title.toLowerCase().trim()));
            if (exists) {
              // open existing after state update below by returning same array (no-op)
              setTab(exists.watchStatus || 'ALL');
              openModal(() => setSelected(exists));
              setImportToast(`Show already tracked: ${exists.title}`);
              setTimeout(() => setImportToast(null), 5000);
              return prev;
            }
            // not present — insert at head
            setTab(created.watchStatus || 'ALL');
            openModal(() => setSelected(created));
            setImportToast(`Added show: ${created.title}`);
            setTimeout(() => setImportToast(null), 5000);
            return [created, ...prev];
          });
        } catch (err) {
          console.error('Failed to add pending show after login', err);
        }
      } catch (err) {
        console.error(err);
      }
    })();
    return () => { mounted = false; };
  }, [currentUser]);

  useEffect(() => {
    // Compute columns based on container width and request full rows (>= MIN_POPULAR)
    function dedupeShows(shows: any[]) {
      const seen = new Set<string|number>();
      const out: any[] = [];
      for (const s of shows) {
        const id = s.tmdbId ?? s.tvmazeId ?? (s.title || '').toLowerCase();
        if (id == null) continue;
        if (seen.has(id)) continue;
        seen.add(id);
        out.push(s);
      }
      return out;
    }

    function computeAndFetch() {
      const container = popularRef.current || document.documentElement;
      const width = container.getBoundingClientRect().width || window.innerWidth;
      // Use min card width matching CSS (160px) + gap (approx 16px)
      const minCard = 160 + 16;
      let cols = Math.max(1, Math.floor(width / minCard));
      // Mobile override: small screens often display more compact cards — prefer 3 columns on narrow widths
      if (width < 600) cols = 3;
      const rowsNeeded = Math.max(1, Math.ceil(MIN_POPULAR / cols));
      const rows = Math.max(rowsNeeded, popularRows || 0);
      const limit = rows * cols;
      setPopularRows(rows);
      api.getPopular(limit).then(shows => { const unique = dedupeShows(shows); setPopularShows(unique); }).catch(() => setPopularShows([]));
    }

    computeAndFetch();
    const onResize = () => computeAndFetch();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    if (!currentUser) return;
    const es = new EventSource('/api/events');
    es.addEventListener('data-changed', () => {
      if (localWrites.current > 0) { localWrites.current--; return; }
      api.getShows().then(shows => setAllShows(shows));
    });
    return () => es.close();
  }, [currentUser]);

  // Push a history entry when a modal opens, pop it to close on back gesture/button
  const openModal = useCallback((open: () => void) => {
    open();
    history.pushState({ modal: true }, '');
  }, []);

  const closeAll = useCallback(() => {
    setSelected(null);
    setPreview(null);
  }, []);

  useEffect(() => {
    const onPop = () => closeAll();
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [closeAll]);

  const handleSelectShow = (show: TrackedShow) => openModal(() => setSelected(show));
  const handlePreview = (result: ShowSearchResult) => openModal(() => setPreview(result));

  const handleCloseModal = () => {
    // If we pushed a history entry, go back (which fires popstate → closeAll)
    // If history state doesn't have modal flag (e.g. direct URL), just close
    if (history.state?.modal) history.back();
    else closeAll();
  };

  const visibleShows = useMemo(() => {
    if (tab === 'ALL') return allShows;
    if (tab === 'POPULAR') return [];
    return allShows.filter(s => s.watchStatus === tab as WatchStatus);
  }, [allShows, tab]);

  const groups = useMemo(() => {
    if (tab !== 'ALL') return null;
    return STATUS_ORDER
      .map(status => ({ status, shows: allShows.filter(s => s.watchStatus === status) }))
      .filter(g => g.shows.length > 0);
  }, [allShows, tab]);

  const [authPromptPayload, setAuthPromptPayload] = useState<ShowSearchResult | null>(null);

  const handleAdd = async (result: ShowSearchResult): Promise<TrackedShow | undefined> => {
    if (!currentUser) {
      // show a small confirmation modal asking the user to sign in first
      setAuthPromptPayload(result);
      return;
    }

    try {
      localWrites.current++;
      const show = await api.addShow(result.tmdbId, result.tvmazeId);
      setAllShows(prev => [show, ...prev]);
      return show;
    } catch (e) {
      localWrites.current--;
      alert('Failed to add show. Check console.');
      console.error(e);
      return undefined;
    }
  };

  const cancelAuthPrompt = () => setAuthPromptPayload(null);
  const continueAuthPrompt = () => {
    if (!authPromptPayload) return;
    // persist desired add across OAuth redirect
    localStorage.setItem('postLoginAdd', JSON.stringify({ tmdbId: authPromptPayload.tmdbId, tvmazeId: authPromptPayload.tvmazeId, title: authPromptPayload.title }));
    localStorage.setItem('postLoginTab', 'ALL');
    setAuthPromptPayload(null);
    handleSignIn('ALL');
  };

  const handleDelete = async (id: string) => {
    localWrites.current++;
    await api.deleteShow(id);
    setAllShows(prev => prev.filter(s => s.id !== id));
    if (selected?.id === id) setSelected(null);
  };

  const handleUpdate = (updated: TrackedShow) => {
    localWrites.current++;
    setAllShows(prev => prev.map(s => s.id === updated.id ? updated : s));
    setSelected(updated);
  };

  const handleReorder = useCallback((reordered: TrackedShow[]) => {
    setAllShows(reordered);
    if (reorderTimer.current) clearTimeout(reorderTimer.current);
    reorderTimer.current = setTimeout(() => {
      localWrites.current++;
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
    // Frontend file size limit check (configurable via Vite env VITE_MAX_UPLOAD_BYTES)
    const maxBytes = Number(import.meta.env.VITE_MAX_UPLOAD_BYTES) || 52428800;
    if (file.size > maxBytes) {
      alert(`Selected file is too large (${(file.size/1024/1024).toFixed(1)} MB). Maximum allowed is ${(maxBytes/1024/1024).toFixed(1)} MB.`);
      e.target.value = '';
      return;
    }
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
      if (show) searchBarRef.current?.triggerSearch(show.title);
    }
  };


  const trackedForResult = (r: ShowSearchResult | null) : TrackedShow | null => {
    if (!r) return null;
    const byId = allShows.find(s => (r.tmdbId && s.tmdbId === r.tmdbId) || (r.tvmazeId && s.tvmazeId === r.tvmazeId));
    if (byId) return byId;
    const title = r.title?.toLowerCase().trim();
    if (title) return allShows.find(s => s.title && s.title.toLowerCase().trim() === title) ?? null;
    return null;
  };

  const handleSignIn = (targetTab?: string) => {
    // Remember desired tab after login (frontend-only). Backend redirects to frontend root.
    if (targetTab) localStorage.setItem('postLoginTab', targetTab);
    const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
    const backend = isLocal ? 'http://localhost:8080' : '';
    window.location.href = backend + '/oauth2/authorization/google';
  };

  const handleSignOut = async () => {
    await fetch('/logout', { method: 'POST', credentials: 'same-origin' });
    setCurrentUser(null);
    setAllShows([]);
  };

  const gridProps = { shows: allShows, tab, onReorder: handleReorder, onSelect: handleSelectShow, onDelete: handleDelete, onRefresh: handleRefreshShow };

  if ((tab as any) === 'POPULAR') {
    return (
      <div className="dashboard">
        <header className="app-header">
          <h1>📺 TV Tracker</h1>
          <nav className="header-nav">
            <button onClick={() => handleExport()}>Export</button>
            <button onClick={() => importRef.current?.click()}>Import</button>
            <input ref={importRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
            <RefreshButton onDone={() => api.getShows().then(setAllShows)} />
            {currentUser ? (
              <>
                <div className="user-pill">
                  {currentUser.picture ? (
                    <img src={currentUser.picture} alt={currentUser.name} className="user-avatar" />
                  ) : (
                    <div className="avatar-initials">{(currentUser.name || '').split(' ').map(s => s[0]).filter(Boolean).slice(0,2).join('').toUpperCase()}</div>
                  )}
                  <span className="user-name">{currentUser.name}</span>
                </div>
                <button onClick={() => handleSignOut()}>Log out</button>
              </>
            ) : (
              <button onClick={() => handleSignIn()}>Sign in with Google</button>
            )}
            {/* Mobile hamburger */}
            <button className="hamburger" onClick={() => setMenuOpen(o => !o)} aria-label="Open menu">☰</button>
            {menuOpen && (
              <div className="mobile-menu" onClick={() => setMenuOpen(false)}>
                <button onClick={() => { handleExport(); }}>Export</button>
                <button onClick={() => { importRef.current?.click(); }}>Import</button>
                <button onClick={() => api.getShows().then(setAllShows)}>Refresh</button>
                {currentUser ? (
                  <button onClick={() => { handleSignOut(); }}>Log out</button>
                ) : (
                  <button onClick={() => { handleSignIn(); }}>Sign in</button>
                )}
              </div>
            )}
          </nav>
        </header>

        <SearchBar ref={searchBarRef} onAdd={handleAdd} onPreview={handlePreview} />

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

        <div className="popular-grid" ref={el => { popularRef.current = el; }}>
          {popularShows.map(p => (
            <div key={p.tmdbId} className="popular-card" onClick={() => handlePreview(p)}>
              {p.posterPath && <img src={p.posterPath} alt={p.title} />}
              <div className="popular-title">{p.title}</div>
            </div>
          ))}
        </div>
        <div style={{ textAlign: 'center', marginTop: '1rem' }}>
          <button className="auth-button" onClick={() => {
            // add one more row
            const container = popularRef.current || document.documentElement;
            const width = container.getBoundingClientRect().width || window.innerWidth;
            const minCard = 160 + 16;
            let cols = Math.max(1, Math.floor(width / minCard));
            if (width < 600) cols = 3;
            const nextRows = Math.max(1, popularRows) + 1;
            const nextLimit = nextRows * cols;
            setPopularRows(nextRows);
            api.getPopular(nextLimit).then(shows => { const unique = dedupeShows(shows); setPopularShows(unique); }).catch(() => setPopularShows([]));
          }}>Explore more</button>
        </div>

        <ScrollToTop />

        {selected && (
          <ShowDetail
            show={selected}
            onClose={handleCloseModal}
            onUpdate={handleUpdate}
            onUntrack={currentUser ? () => handleDelete(selected.id) : undefined}
          />
        )}

        {preview && (
          <SearchPreview
            result={preview}
            onClose={handleCloseModal}
            onTrack={handleAdd}
            trackedShow={trackedForResult(preview)}
            onUpdate={handleUpdate}
          />
        )}

        {authPromptPayload && (
          <div className="modal-overlay" onClick={cancelAuthPrompt}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <h3>Sign in required</h3>
              <p>You need to sign in with your Google account to track shows. Continue to sign in?</p>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: '1rem' }}>
                <button onClick={cancelAuthPrompt}>Cancel</button>
                <button className="auth-button" onClick={continueAuthPrompt}>Continue</button>
              </div>
            </div>
          </div>
        )}

      </div>
    );
  }

  if (authLoading) {
    return (
      <div className="dashboard">
        <header className="app-header">
          <h1>📺 TV Tracker</h1>
        </header>
        <div className="auth-gate">
          <p>Loading…</p>
        </div>
      </div>
    );
  }

  if (!currentUser) {
    return (
      <div className="dashboard">
        <header className="app-header">
          <h1>📺 TV Tracker</h1>
          <nav className="header-nav">
            <button onClick={() => handleSignIn()}>Sign in with Google</button>
            <button className="hamburger" onClick={() => setMenuOpen(o => !o)} aria-label="Open menu">☰</button>
            {menuOpen && (
              <div className="mobile-menu" onClick={() => setMenuOpen(false)}>
                <button onClick={() => handleSignIn()}>Sign in</button>
              </div>
            )}
          </nav>
        </header>

        <SearchBar ref={searchBarRef} onAdd={handleAdd} onPreview={handlePreview} />

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

        {(tab as any) === 'POPULAR' ? (
          <div className="popular-grid">
            {popularShows.map(p => (
              <div key={p.tmdbId ?? p.tvmazeId ?? p.title} className="popular-card" onClick={() => handlePreview(p)}>
                {p.posterPath && <img src={p.posterPath} alt={p.title} />}
                <div className="popular-title">{p.title}</div>
              </div>
            ))}
          </div>
        ) : (
          <div className="auth-gate">
            <h2>Sign in to continue</h2>
            <p>Use your Google account to access your personal TV library.</p>
            <button className="auth-button" onClick={() => handleSignIn(tab as string)}>Continue with Google</button>
          </div>
        )}

        {selected && (
          <ShowDetail
            show={selected}
            onClose={handleCloseModal}
            onUpdate={handleUpdate}
            onUntrack={currentUser ? () => handleDelete(selected.id) : undefined}
          />
        )}

        {preview && (
          <SearchPreview
            result={preview}
            onClose={handleCloseModal}
            onTrack={handleAdd}
            trackedShow={trackedForResult(preview)}
            onUpdate={handleUpdate}
          />
        )}

        {authPromptPayload && (
          <div className="modal-overlay" onClick={cancelAuthPrompt}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <h3>Sign in required</h3>
              <p>You need to sign in with your Google account to track shows. Continue to sign in?</p>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: '1rem' }}>
                <button onClick={cancelAuthPrompt}>Cancel</button>
                <button className="auth-button" onClick={continueAuthPrompt}>Continue</button>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="dashboard">
      <header className="app-header">
        <h1>📺 TV Tracker</h1>
        <nav className="header-nav">
          <button onClick={handleExport}>Export</button>
          <button onClick={() => importRef.current?.click()}>Import</button>
          <input ref={importRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
          <RefreshButton onDone={() => api.getShows().then(setAllShows)} />
          <div className="user-pill">
            {currentUser.picture ? (
              <img src={currentUser.picture} alt={currentUser.name} className="user-avatar" />
            ) : (
              <div className="avatar-initials">{(currentUser.name || '').split(' ').map(s => s[0]).filter(Boolean).slice(0,2).join('').toUpperCase()}</div>
            )}
            <span className="user-name">{currentUser.name}</span>
          </div>
          <button onClick={handleSignOut}>Log out</button>
          <button className="hamburger" onClick={() => setMenuOpen(o => !o)} aria-label="Open menu">☰</button>
          {menuOpen && (
            <div className="mobile-menu" onClick={() => setMenuOpen(false)}>
              <button onClick={() => handleExport()}>Export</button>
              <button onClick={() => importRef.current?.click()}>Import</button>
              <button onClick={() => api.getShows().then(setAllShows)}>Refresh</button>
              <button onClick={() => handleSignOut()}>Log out</button>
            </div>
          )}
        </nav>
      </header>

      <SearchBar ref={searchBarRef} onAdd={handleAdd} onPreview={handlePreview} />

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
          onClose={handleCloseModal}
          onUpdate={handleUpdate}
          onUntrack={currentUser ? () => handleDelete(selected.id) : undefined}
        />
      )}

      {preview && (
        <SearchPreview
          result={preview}
          onClose={handleCloseModal}
          onTrack={handleAdd}
          trackedShow={trackedForResult(preview)}
          onUpdate={handleUpdate}
        />
      )}

      {authPromptPayload && (
        <div className="modal-overlay" onClick={cancelAuthPrompt}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h3>Sign in required</h3>
            <p>You need to sign in with your Google account to track shows. Continue to sign in?</p>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: '1rem' }}>
              <button onClick={cancelAuthPrompt}>Cancel</button>
              <button className="auth-button" onClick={continueAuthPrompt}>Continue</button>
            </div>
          </div>
        </div>
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
