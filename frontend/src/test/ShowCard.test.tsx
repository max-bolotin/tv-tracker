import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { TrackedShow } from '../types';
import { ShowCard } from '../components/ShowCard';

const show: TrackedShow = {
  id: 'show-1',
  title: 'Tracked show',
  totalSeasons: 1,
  productionStatus: 'ONGOING',
  watchStatus: 'WATCHING_NOW',
  seasons: [],
};

describe('ShowCard', () => {
  it('exposes and invokes the remove action', () => {
    const onDelete = vi.fn();
    render(
      <ShowCard
        show={show}
        onClick={vi.fn()}
        onDelete={onDelete}
        onRefresh={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Remove Tracked show' }));

    expect(onDelete).toHaveBeenCalledOnce();
  });
});
