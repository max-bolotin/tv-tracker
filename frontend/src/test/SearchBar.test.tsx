import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { SearchBar } from '../components/SearchBar';
import type { ShowSearchResult } from '../types';

vi.mock('../api/client', () => ({
  api: {
    searchShows: vi.fn(),
  },
}));

import { api } from '../api/client';

const searchResult: ShowSearchResult = {
  tmdbId: 123,
  title: 'Example Show',
  totalSeasons: 1,
};

describe('SearchBar', () => {
  beforeEach(() => {
    vi.mocked(api.searchShows).mockReset();
    history.replaceState({ myShows: true }, '', '/');
  });

  it('closes search results on browser back without dropping the underlying history entry', async () => {
    vi.mocked(api.searchShows).mockResolvedValue([searchResult]);
    const onResultsOpenChange = vi.fn();
    render(<SearchBar onAdd={vi.fn()} onPreview={vi.fn()} onResultsOpenChange={onResultsOpenChange} />);

    fireEvent.change(screen.getByPlaceholderText('Search for a TV show...'), {
      target: { value: 'Example' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('Example Show')).toBeInTheDocument();
    expect(history.state).toMatchObject({ myShows: true, searchModal: true });

    history.back();

    await waitFor(() => {
      expect(screen.queryByText('Example Show')).not.toBeInTheDocument();
      expect(history.state).toMatchObject({ myShows: true });
    });
    expect(onResultsOpenChange).toHaveBeenLastCalledWith(false);
  });

  it('shows the clear button only for a non-empty query and clears/refocuses the input', () => {
    render(<SearchBar onAdd={vi.fn()} onPreview={vi.fn()} />);
    const input = screen.getByPlaceholderText('Search for a TV show...');

    expect(screen.queryByRole('button', { name: 'Clear search' })).not.toBeInTheDocument();

    fireEvent.change(input, { target: { value: 'Example' } });
    const clearButton = screen.getByRole('button', { name: 'Clear search' });
    expect(clearButton).toBeInTheDocument();

    fireEvent.click(clearButton);

    expect(input).toHaveValue('');
    expect(screen.queryByRole('button', { name: 'Clear search' })).not.toBeInTheDocument();
    expect(document.activeElement).toBe(input);
  });
});
