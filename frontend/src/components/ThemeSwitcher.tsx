import { setTheme, useTheme } from '../hooks/useTheme';

interface ThemeSwitcherProps {
  onChange?: (isDark: boolean) => void;
}

export function ThemeSwitcher({ onChange }: ThemeSwitcherProps) {
  const isDark = useTheme() === 'dark';

  const toggleTheme = () => {
    const newIsDark = !isDark;
    setTheme(newIsDark ? 'dark' : 'light');
    onChange?.(newIsDark);
  };

  return (
    <button
      className="theme-switch"
      role="switch"
      aria-checked={isDark}
      aria-label="Dark theme"
      onClick={toggleTheme}
      type="button"
      title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      <span className={`theme-switch-icon${isDark ? ' moon' : ' sun'}`} aria-hidden="true">
        {isDark ? (
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="12" cy="12" r="5" />
            <path d="M12 1V3" />
            <path d="M12 21V23" />
            <path d="M4.22 4.22L5.64 5.64" />
            <path d="M18.36 18.36L19.78 19.78" />
            <path d="M1 12H3" />
            <path d="M21 12H23" />
            <path d="M4.22 19.78L5.64 18.36" />
            <path d="M18.36 5.64L19.78 4.22" />
          </svg>
        )}
      </span>
      <span className="theme-switch-knob" aria-hidden="true" />
    </button>
  );
}
