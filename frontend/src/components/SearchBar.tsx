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
  const clearQuery = () => {
    setQuery('');
    setResults([]);
    inputRef.current?.focus();
  };

  return (
    <div className="search-bar" ref={containerRef}>
      <div className="search-input-row">
        <div className="search-input-container">
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
          {query && (
            <button
              className="search-clear-btn"
              onClick={clearQuery}
              type="button"
              aria-label="Clear search"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 16 16">
                <path d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14m0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16"/>
                <path d="M4.646 4.646a.5.5 0 0 1 .708 0L8 7.293l2.646-2.647a.5.5 0 0 1 .708.708L8.707 8l2.647 2.646a.5.5 0 0 1-.708.708L8 8.707l-2.646 2.647a.5.5 0 0 1-.708-.708L7.293 8 4.646 5.354a.5.5 0 0 1 0-.708"/>
              </svg>
            </button>
          )}
        </div>
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
