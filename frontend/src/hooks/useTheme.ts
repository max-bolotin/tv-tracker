import { useSyncExternalStore } from 'react';

type Theme = 'light' | 'dark';

const THEME_STORAGE_KEY = 'theme';
const THEME_CHANGE_EVENT = 'theme-change';

function readStoredTheme(): Theme | null {
  try {
    const theme = localStorage.getItem(THEME_STORAGE_KEY);
    return theme === 'light' || theme === 'dark' ? theme : null;
  } catch {
    return null;
  }
}

function getSystemTheme(): Theme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function getThemeSnapshot(): Theme {
  const attributeTheme = document.documentElement.dataset.theme;
  return attributeTheme === 'light' || attributeTheme === 'dark'
    ? attributeTheme
    : readStoredTheme() ?? getSystemTheme();
}

function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
}

function subscribe(onStoreChange: () => void): () => void {
  const onStorageChange = (event: StorageEvent) => {
    if (event.key !== null && event.key !== THEME_STORAGE_KEY) return;
    applyTheme(event.newValue === 'light' || event.newValue === 'dark' ? event.newValue : getSystemTheme());
    onStoreChange();
  };
  const onSystemThemeChange = () => {
    if (readStoredTheme() !== null) return;
    applyTheme(getSystemTheme());
    onStoreChange();
  };
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');

  window.addEventListener(THEME_CHANGE_EVENT, onStoreChange);
  window.addEventListener('storage', onStorageChange);
  mediaQuery.addEventListener('change', onSystemThemeChange);

  return () => {
    window.removeEventListener(THEME_CHANGE_EVENT, onStoreChange);
    window.removeEventListener('storage', onStorageChange);
    mediaQuery.removeEventListener('change', onSystemThemeChange);
  };
}

export function setTheme(theme: Theme): void {
  applyTheme(theme);
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch (error) {
    console.warn('Unable to persist theme preference.', error);
  }
  window.dispatchEvent(new Event(THEME_CHANGE_EVENT));
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getThemeSnapshot, () => 'light');
}
