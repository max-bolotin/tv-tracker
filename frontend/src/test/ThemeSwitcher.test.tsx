import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeSwitcher } from '../components/ThemeSwitcher';

function setSystemPreference(prefersDark: boolean) {
  vi.mocked(window.matchMedia).mockImplementation(query => ({
    matches: prefersDark,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    setSystemPreference(false);
  });

  it('renders with the light theme by default', () => {
    render(<ThemeSwitcher />);
    const button = screen.getByRole('switch', { name: 'Dark theme' });
    expect(button).toBeInTheDocument();
    expect(button).toHaveAttribute('aria-checked', 'false');
  });

  it('initializes from the saved theme without a post-mount flash', () => {
    localStorage.setItem('theme', 'dark');
    render(<ThemeSwitcher />);

    expect(screen.getByRole('switch', { name: 'Dark theme' })).toHaveAttribute('aria-checked', 'true');
  });

  it('uses the system preference when there is no saved theme', () => {
    setSystemPreference(true);
    render(<ThemeSwitcher />);

    expect(screen.getByRole('switch', { name: 'Dark theme' })).toHaveAttribute('aria-checked', 'true');
  });

  it('keeps multiple switchers synchronized when toggled', () => {
    render(
      <>
        <ThemeSwitcher />
        <ThemeSwitcher />
      </>,
    );
    const [first, second] = screen.getAllByRole('switch', { name: 'Dark theme' });

    fireEvent.click(first);

    expect(first).toHaveAttribute('aria-checked', 'true');
    expect(second).toHaveAttribute('aria-checked', 'true');
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('toggles back to light theme and persists the choice', () => {
    render(<ThemeSwitcher />);
    const button = screen.getByRole('switch', { name: 'Dark theme' });

    fireEvent.click(button);
    fireEvent.click(button);

    expect(document.documentElement).toHaveAttribute('data-theme', 'light');
    expect(button).toHaveAttribute('aria-checked', 'false');
    expect(localStorage.getItem('theme')).toBe('light');
  });

  it('calls onChange when the theme changes', () => {
    const onChange = vi.fn();
    render(<ThemeSwitcher onChange={onChange} />);
    const button = screen.getByRole('switch', { name: 'Dark theme' });

    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledWith(true);

    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledWith(false);
  });
});
