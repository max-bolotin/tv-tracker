import { useRef, useState, forwardRef, useImperativeHandle } from 'react';
import type { ShowSearchResult } from '../types';
import { api } from '../api/client';

interface Props {
  onAdd: (result: ShowSearchResult) => void;
  onPreview: (result: ShowSearchResult) => void;
}

export interface SearchBarHandle {
  triggerSearch: (query: string) => void;
}

export const SearchBar = forwardRef<SearchBarHandle, Props>(function SearchBar({ onAdd, onPreview }, ref) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<ShowSearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const search = async (q = query) => {
    if (!q.trim()) return;
    setLoading(true);
    try {
      setResults(await api.searchShows(q));
    } finally {
      setLoading(false);
    }
  };

  useImperativeHandle(ref, () => ({
    triggerSearch(q: string) {
      setQuery(q);
      inputRef.current?.focus();
      inputRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      search(q);
    },
  }));

  const dismiss = () => setResults([]);

  return (
    <div className="search-bar" ref={containerRef}>
      <div className="search-input-row">
        <input
          ref={inputRef}
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter') search();
            if (e.key === 'Escape') dismiss();
          }}
          placeholder="Search for a TV show..."
        />
        <button onClick={() => search()} disabled={loading}>
          {loading ? '...' : 'Search'}
        </button>
      </div>
      {results.length > 0 && (
        <>
          <div className="search-backdrop" onClick={dismiss} />
          <ul className="search-results">
            {results.map(r => (
              <li key={r.tmdbId ?? r.tvmazeId} onClick={() => { dismiss(); onPreview(r); }}>
                {r.posterPath && <img src={r.posterPath} alt={r.title} />}
                <div>
                  <strong>{r.title}</strong>
                  <p>{r.overview?.slice(0, 100)}{r.overview && r.overview.length > 100 ? '…' : ''}</p>
                </div>
                <button onClick={e => { e.stopPropagation(); onAdd(r); dismiss(); setQuery(''); }}>
                  + Track
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
});
