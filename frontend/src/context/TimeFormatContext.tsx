import { createContext, useContext, useMemo } from 'react';
import { useTimeFormatPreference, type TimeFormat } from '../hooks/useTimeFormatPreference';

interface TimeFormatContextValue {
  format: TimeFormat;
  toggle: () => void;
  /** Formats a zone-less server timestamp ("...THH:mm...") per the current preference. */
  formatTime: (iso: string | null) => string | null;
  formatDuration: (minutes: number | null) => string | null;
}

const TimeFormatContext = createContext<TimeFormatContextValue | null>(null);

// Backend LocalDateTime strings are naive wall-clock digits already in the record's own
// resolved zone (browser-reported at Check-In/Web Clock-In, see AttendanceService.resolveZone)
// — there is nothing left to convert. Parsing with 'Z' and formatting with timeZone: 'UTC' reads
// those digits back out verbatim, regardless of the *viewer's* own browser timezone. Previously
// this appended '+05:30' (assumed IST) and let toLocaleTimeString re-project into the viewer's
// local zone — for any employee whose resolved zone isn't IST, or any viewer whose browser isn't
// IST either, that mislabeled the digits and then shifted them again.
function formatTimeAs(format: TimeFormat, iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  if (format === '24h') {
    return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' });
  }
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}

// Duration is format-independent (always "Xh Ym") — kept here only so callers have a single
// import for both, and so a future format that changes duration display has one place to change.
// Defensively floored at 0: a negative input (e.g. from an upstream gap/overlap calculation)
// must never render as "-48m" — no duration is ever meaningfully negative to a viewer.
export function formatDurationMinutes(minutes: number | null): string | null {
  if (minutes == null) return null;
  const clamped = Math.max(0, minutes);
  const h = Math.floor(clamped / 60);
  const m = clamped % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** Precise "Late by" text from total elapsed SECONDS — the caller computes this directly from
 * AttendanceResponse's own checkInAt/shiftStartAt (see shiftMarkers.ts's secondsBetween), never
 * rounded or truncated before this call. Distinct from formatDurationMinutes above (which is for
 * worked-hours "Xh Ym" display and stays minute-granularity). Carries whole minutes into hours
 * once they reach 60 (e.g. 351m20s -> "5h 51m 20s", 60m0s -> "1h") so a long lateness never
 * renders as raw triple-digit minutes — each of h/m/s is shown only when non-zero, and 0 total
 * seconds renders as null (no "Late by" line at all, same as before). */
export function formatLateBySeconds(totalSeconds: number | null): string | null {
  if (totalSeconds == null) return null;
  const clamped = Math.max(0, Math.trunc(totalSeconds));
  if (clamped === 0) return null;
  const totalMinutes = Math.floor(clamped / 60);
  const s = clamped % 60;
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  const parts: string[] = [];
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  if (s > 0) parts.push(`${s}s`);
  return parts.join(' ');
}

/** Attendance-page-scoped 12h/24h preference — see AttendancePage's usage for the call-site list. */
export function TimeFormatProvider({ children }: { children: React.ReactNode }) {
  const [format, toggle] = useTimeFormatPreference();

  const value = useMemo<TimeFormatContextValue>(() => ({
    format,
    toggle,
    formatTime: (iso) => formatTimeAs(format, iso),
    formatDuration: formatDurationMinutes,
  }), [format, toggle]);

  return <TimeFormatContext.Provider value={value}>{children}</TimeFormatContext.Provider>;
}

export function useTimeFormat(): TimeFormatContextValue {
  const ctx = useContext(TimeFormatContext);
  if (!ctx) throw new Error('useTimeFormat must be used inside TimeFormatProvider');
  return ctx;
}
