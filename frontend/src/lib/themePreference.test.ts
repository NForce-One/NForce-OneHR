import { describe, expect, it } from 'vitest';
import { readStoredTheme, resolveInitialTheme } from './themePreference';

// Minimal fake — implements just the `getItem` slice of Storage that readStoredTheme reads.
function fakeStorage(value: string | null): Pick<Storage, 'getItem'> {
  return { getItem: () => value };
}

function throwingStorage(): Pick<Storage, 'getItem'> {
  return {
    getItem: () => {
      throw new Error('storage unavailable');
    },
  };
}

describe('readStoredTheme', () => {
  it('returns the persisted theme when it is a valid value', () => {
    expect(readStoredTheme(fakeStorage('dark'))).toBe('dark');
    expect(readStoredTheme(fakeStorage('light'))).toBe('light');
  });

  it('returns null when nothing has ever been persisted', () => {
    expect(readStoredTheme(fakeStorage(null))).toBeNull();
  });

  it('returns null for a garbled/unexpected stored value instead of throwing', () => {
    expect(readStoredTheme(fakeStorage('purple'))).toBeNull();
  });

  it('returns null (not a throw) when storage access itself fails, e.g. private browsing', () => {
    expect(readStoredTheme(throwingStorage())).toBeNull();
  });
});

describe('resolveInitialTheme', () => {
  it('honors a persisted preference over the system/media-query preference', () => {
    // Scenario 1: user last selected Dark, even if the OS is set to light — last choice wins.
    expect(resolveInitialTheme('dark', /* prefersLight */ true)).toBe('dark');
    // Scenario 2: user last selected Light, even if the OS is set to dark — last choice wins.
    expect(resolveInitialTheme('light', /* prefersLight */ false)).toBe('light');
  });

  it('falls back to the existing system-preference default when nothing is persisted (Scenario 3)', () => {
    expect(resolveInitialTheme(null, true)).toBe('light');
    expect(resolveInitialTheme(null, false)).toBe('dark');
  });

  it('is stable across repeated resolution with the same persisted value (navigation/refresh)', () => {
    for (let i = 0; i < 3; i++) {
      expect(resolveInitialTheme('dark', true)).toBe('dark');
    }
  });

  it('tracks repeated switching, always reflecting the most recent selection (Scenario 4)', () => {
    // Light -> Dark -> Light -> Dark, each persisted in turn and re-resolved as if reloaded.
    expect(resolveInitialTheme('light', false)).toBe('light');
    expect(resolveInitialTheme('dark', false)).toBe('dark');
    expect(resolveInitialTheme('light', false)).toBe('light');
    expect(resolveInitialTheme('dark', false)).toBe('dark');
  });
});
