import { createContext, useContext, useState, type ReactNode } from 'react';
import { THEME_STORAGE_KEY, readStoredTheme, resolveInitialTheme, type Theme } from './themePreference';

export type { Theme };

function getInitialTheme(): Theme {
  const stored = readStoredTheme(window.localStorage);
  let prefersLight = false;
  try {
    prefersLight = window.matchMedia('(prefers-color-scheme: light)').matches;
  } catch {
    // ignore — resolveInitialTheme's default (dark) covers this case too.
  }
  return resolveInitialTheme(stored, prefersLight);
}

let _theme: Theme = getInitialTheme();

function applyTheme(theme: Theme) {
  const html = document.documentElement;
  html.setAttribute('data-theme', theme);
  html.classList.add('nf-theme-transitioning');
  setTimeout(() => html.classList.remove('nf-theme-transitioning'), 200);
}

function persistTheme(theme: Theme) {
  try { window.localStorage.setItem(THEME_STORAGE_KEY, theme); } catch { /* best effort */ }
}

// Runs at module load, before ThemeProvider ever mounts — so the persisted theme is applied
// on the very first paint instead of flashing the default and then switching.
applyTheme(_theme);

interface ThemeContextValue {
  theme: Theme;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'dark',
  toggleTheme: () => {},
});

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(_theme);

  function toggleTheme() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    _theme = next;
    applyTheme(next);
    persistTheme(next);
    setTheme(next);
  }

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
