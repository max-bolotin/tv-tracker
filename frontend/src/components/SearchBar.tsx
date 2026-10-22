import { useRef, useState, useEffect, useCallback, forwardRef, useImperativeHandle } from 'react';
import type { ShowSearchResult } from '../types';
import type { MovieEntry, MovieSearchResult } from '../types';
import { api } from '../api/client';

interface Props {
  onAdd: (result: ShowSearchResult) => void;
  onPreview: (result: ShowSearchResult) => void;
  onResultsOpenChange?: (isOpen: boolean) => void;
  mode?: 'shows' | 'movies';
  onAddMovie?: (result: MovieSearchResult) => void;
  onPreviewMovie?: (result: MovieSearchResult) => void;
  trackedMovies?: MovieEntry[];
}

export interface SearchBarHandle {
  triggerSearch: (query: string) => void;
  clearQuery: () => void;
}

export const SearchBar = forwardRef<SearchBarHandle, Props>(function SearchBar(
  { onAdd, onPreview, onResultsOpenChange, mode = 'shows', onAddMovie, onPreviewMovie, trackedMovies = [] },
  ref,
) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<ShowSearchResult[]>([]);
  const [movieResults, setMovieResults] = useState<MovieSearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const resultsOpenRef = useRef(false);

  const setResultsOpen = useCallback((isOpen: boolean) => {
    resultsOpenRef.current = isOpen;
    onResultsOpenChange?.(isOpen);
  }, [onResultsOpenChange]);

  useEffect(() => {
    setQuery('');
    setResults([]);
    setMovieResults([]);
    setError(null);
  }, [mode]);

  const search = useCallback(async (q = query) => {
    if (!q.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const nextResults = mode === 'movies' ? await api.searchMovies(q) : await api.searchShows(q);
      if (mode === 'movies') {
        setMovieResults(nextResults as MovieSearchResult[]);
        setResults([]);
      } else {
        setResults(nextResults as ShowSearchResult[]);
        setMovieResults([]);
      }
      if (nextResults.length > 0 && !resultsOpenRef.current) {
        const currentState = history.state;
        const state = currentState && typeof currentState === 'object' ? currentState : {};
        history.pushState({ ...state, searchModal: true }, '');
        setResultsOpen(true);
      } else if (nextResults.length === 0 && resultsOpenRef.current) {
        if (history.state?.searchModal) history.back();
        else setResultsOpen(false);
      }
    } catch (cause) {
      console.error(`Failed to search ${mode}`, cause);
      setError(`Search failed. Please try again.`);
    } finally {
      setLoading(false);
    }
  }, [mode, query, setResultsOpen]);

  useImperativeHandle(ref, () => ({
    triggerSearch(q: string) {
      setQuery(q);
      inputRef.current?.focus();
      inputRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      search(q);
    },
    clearQuery() {
      setQuery('');
      setResults([]);
      setMovieResults([]);
      setError(null);
      if (resultsOpenRef.current && history.state?.searchModal) history.back();
      else setResultsOpen(false);
    },
  }), [search, setResultsOpen]);

  const closeResults = () => {
    setResults([]);
    setMovieResults([]);
    setError(null);
    setResultsOpen(false);
  };

  useEffect(() => {
    const onPopState = () => {
      if (resultsOpenRef.current) {
        setResults([]);
        setResultsOpen(false);
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [setResultsOpen]);

  const dismiss = () => {
    if (resultsOpenRef.current && history.state?.searchModal) history.back();
    else closeResults();
  };

  const clearQuery = () => {
    setQuery('');
    dismiss();
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
            placeholder={mode === 'movies' ? 'Search for movies...' : 'Search for a TV show...'}
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
              <li key={r.tmdbId ?? r.tvmazeId} onClick={() => { closeResults(); onPreview(r); }}>
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
      {movieResults.length > 0 && (
        <>
          <div className="search-backdrop" onClick={dismiss} />
          <ul className="search-results">
            {movieResults.map(movie => {
              const tracked = trackedMovies.find(entry => entry.tmdbId === movie.tmdbId);
              return (
                <li key={movie.tmdbId}>
                  <button
                    className="search-result-preview"
                    type="button"
                    onClick={() => {
                      closeResults();
                      onPreviewMovie?.(movie);
                    }}
                  >
                    {movie.poster && <img src={movie.poster} alt="" />}
                    <span>
                      <strong>{movie.title}</strong>
                      {movie.releaseDate && <span className="search-result-year">{movie.releaseDate.slice(0, 4)}</span>}
                      {movie.overview && <span className="search-result-overview">
                        {movie.overview.slice(0, 100)}{movie.overview.length > 100 ? '…' : ''}
                      </span>}
                    </span>
                  </button>
                  {tracked ? (
                    <span className="search-tracked-label">Tracked</span>
                  ) : (
                    <button onClick={() => {
                      onAddMovie?.(movie);
                      dismiss();
                      setQuery('');
                    }}>+ Track</button>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      )}
      {error && <p className="search-error" role="alert">{error}</p>}
    </div>
  );
});
