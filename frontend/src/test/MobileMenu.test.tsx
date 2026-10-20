import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMobileMenu } from '../hooks/useMobileMenu';

describe('useMobileMenu', () => {
  beforeEach(() => {
    history.replaceState({ myShows: true }, '', '/');
  });

  it('closes on browser back without navigating the underlying page', async () => {
    const backHandler = vi.fn();
    window.addEventListener('popstate', backHandler);
    const { result } = renderHook(() => useMobileMenu());

    act(() => result.current.openMenu());
    expect(result.current.isOpen).toBe(true);
    expect(history.state).toMatchObject({ myShows: true, mobileMenu: true });

    act(() => history.back());

    await waitFor(() => {
      expect(result.current.isOpen).toBe(false);
      expect(history.state).toMatchObject({ myShows: true });
    });
    expect(backHandler).not.toHaveBeenCalled();

    window.removeEventListener('popstate', backHandler);
  });

  it('removes the menu history entry when closed by an in-menu action', async () => {
    const { result } = renderHook(() => useMobileMenu());

    act(() => result.current.openMenu());
    act(() => result.current.closeMenu());

    expect(result.current.isOpen).toBe(false);
    await waitFor(() => {
      expect(history.state).toMatchObject({ myShows: true });
    });
  });
});
