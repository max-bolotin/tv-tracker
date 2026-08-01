import { useState } from 'react';
import type { ShowSearchResult } from '../types';
import { api } from '../api/client';

interface Props {
  onAdd: (result: ShowSearchResult) => void;
}

export function SearchBar({ onAdd }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<ShowSearchResult[]>([]);
  const [loading, setLoading] = useState(false);

  const search = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      setResults(await api.searchShows(query));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="search-bar">
      <div className="search-input-row">
        <input
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && search()}
          placeholder="Search for a TV show..."
        />
        <button onClick={search} disabled={loading}>
          {loading ? '...' : 'Search'}
        </button>
      </div>
      {results.length > 0 && (
        <ul className="search-results">
          {results.map(r => (
            <li key={r.tmdbId ?? r.tvmazeId}>
              {r.posterPath && <img src={r.posterPath} alt={r.title} />}
              <div>
                <strong>{r.title}</strong>
                <p>{r.overview?.slice(0, 100)}{r.overview && r.overview.length > 100 ? '…' : ''}</p>
              </div>
              <button onClick={() => { onAdd(r); setResults([]); setQuery(''); }}>
                + Track
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
