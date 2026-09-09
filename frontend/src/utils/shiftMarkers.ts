// ─── Attendance Log: workday-window-relative marker positioning ───────────────────────────────
// Backs the whole AttendanceTimeline track (AttendancePage.tsx) — the actual-attendance bar(s),
// the break marker(s), and the two small scheduled-shift-boundary markers it draws on top — so an
// employee can compare "when I was scheduled" against "when I actually showed up" at a glance.
//
// IMPORTANT: the calendar midnight boundary does NOT determine an attendance workday — the
// backend's ShiftDayPolicy is the single source of truth for that (see
// ShiftDayPolicy#workdayStartAt/#workdayEndAt, exposed per-record as
// AttendanceResponse.workdayStartAt/workdayEndAt). This module positions everything against THAT
// workday window (workdayStartAt -> workdayEndAt), never against a fixed 00:00-24:00 calendar
// day — so a post-midnight punch (e.g. 12:21 AM/3:43 AM, still the same workday as a 10:00 AM
// shift start) lands correctly to the right of the shift-start marker, not wrapped back to the
// track's own left edge. See AttendanceResponse.shiftStartAt/shiftEndAt and
// AttendanceInterpretationService#resolveScheduledWindow on the backend for how every one of
// these instants is resolved (against the record's OWN snapshotted ShiftVersion/timezone, never
// the employee's current shift or the browser's timezone).

/**
 * Minutes since local midnight, parsed the same zone-less way AttendancePage's own
 * checkIn/checkOut bar positioning does: sliced straight out of the ISO string's "HH:MM"
 * rather than through `new Date()`, which would re-interpret it in the browser's own timezone
 * and shift the result. Server timestamps here are wall-clock strings with no offset (see
 * AttendancePage's own header comment). Used for time-of-day-only comparisons elsewhere in
 * AttendancePage (e.g. "shift starts in Xm") — NOT by the workday-window track positioning below,
 * which needs the full date+time (a workday spans across midnight, so "minutes since midnight"
 * alone can't tell a 12:21 AM punch from one 24h earlier or later).
 */
export function minutesSinceMidnight(iso: string): number | null {
  const time = iso.slice(11, 16);
  if (time.length < 5) return null;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

/**
 * Parses a zone-less wall-clock ISO string ("2026-09-08T04:00:00", or with a "Z"/offset stripped
 * off — none of AttendanceResponse's timestamps carry one) into a pure calendar/clock value
 * usable for differencing, via `Date.UTC` — NOT to interpret it as a real UTC instant, but as an
 * immune-to-DST, immune-to-browser-timezone arithmetic base: every timestamp this module compares
 * is parsed the exact same way, so the differences between them come out correct regardless of
 * the viewer's own timezone (the actual guarantee `minutesSinceMidnight`'s own doc comment already
 * relies on `new Date()` NOT providing). Seconds are captured (defaulting to 0 when absent) so
 * callers needing exact elapsed time — see {@link secondsBetween} — aren't silently truncated to
 * whole minutes; existing minute-granularity callers (marker positioning) are unaffected, since a
 * fractional minute only makes their positioning more accurate, never wrong.
 */
function parseWallClockMs(iso: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(iso);
  if (!m) return null;
  const [, y, mo, d, h, mi, s] = m;
  return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), s ? Number(s) : 0);
}

/** Whole minutes from {@code fromIso} to {@code toIso} (negative if {@code toIso} is earlier). Null if either is unparseable. */
export function minutesBetween(fromIso: string, toIso: string): number | null {
  const from = parseWallClockMs(fromIso);
  const to = parseWallClockMs(toIso);
  if (from == null || to == null) return null;
  return (to - from) / 60000;
}

/**
 * Whole elapsed SECONDS from {@code fromIso} to {@code toIso} (negative if {@code toIso} is
 * earlier), truncated toward zero — never rounded. Null if either is unparseable. The
 * seconds-precision sibling of {@link minutesBetween}, for exact-duration display (e.g. "Late by
 * 1m 37s") rather than marker positioning.
 */
export function secondsBetween(fromIso: string, toIso: string): number | null {
  const from = parseWallClockMs(fromIso);
  const to = parseWallClockMs(toIso);
  if (from == null || to == null) return null;
  return Math.trunc((to - from) / 1000);
}

/**
 * The calendar date {@code punchIso} falls on ("YYYY-MM-DD"), but ONLY when it differs from
 * {@code workDate} — {@code null} when they match. Backs the Attendance punch list's (Check-In/
 * Check-Out and Web Check-In/Check-Out sections in AttendancePage.tsx's PunchSourceGroup) date
 * qualifier: a punch whose own calendar date differs from the attendance row's own {@code
 * workDate} — e.g. a legitimate post-midnight punch still correctly attributed to the PREVIOUS
 * logical workday by {@link resolveWorkdayWindow}'s own backend counterpart, ShiftDayPolicy
 * #shiftDayOf — must stay visually distinct from a same-day punch landing on the same clock time,
 * rather than looking indistinguishable from (or ahead of) "now."
 *
 * Pure string slicing on the zone-less wall-clock ISO timestamp (the same "YYYY-MM-DDTHH:mm..."
 * shape every timestamp in this module already assumes — see this module's own header comment),
 * never `Date`-parsed, so this can never disagree with the underlying business timestamp or be
 * shifted by the viewer's own browser timezone. Does not decide or re-derive which workday a
 * punch belongs to — {@code workDate} must already be the attendance row's own resolved value.
 */
export function punchCalendarDateIfDiffers(punchIso: string, workDate: string): string | null {
  const datePart = punchIso.slice(0, 10);
  return datePart === workDate ? null : datePart;
}

/** The calendar date one day after {@code dateOnlyIso} ("YYYY-MM-DD" in, "YYYY-MM-DD" out) — pure calendar arithmetic, never a real-timezone `Date`. */
function nextCalendarDay(dateOnlyIso: string): string {
  const [y, mo, d] = dateOnlyIso.split('-').map(Number);
  const next = new Date(Date.UTC(y, mo - 1, d + 1));
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${next.getUTCFullYear()}-${pad(next.getUTCMonth() + 1)}-${pad(next.getUTCDate())}`;
}

/** The workday window the AttendanceTimeline track is positioned against — see this module's header comment. */
export interface WorkdayWindow {
  /** ISO wall-clock instant the track's left edge (0%) represents. */
  startIso: string;
  /** ISO wall-clock instant the track's right edge (100%) represents. */
  endIso: string;
  /** {@code endIso - startIso}, in minutes — the denominator every position below is divided by. */
  totalMinutes: number;
}

/**
 * Resolves the workday window a record's timeline track should span. Reuses the record's own
 * {@code workdayStartAt}/{@code workdayEndAt} (backend-resolved via ShiftDayPolicy) whenever
 * present — this is the ONLY workday-attribution logic this module ever applies; it never
 * recomputes a boundary itself. Falls back to the record's own calendar day (00:00 -> 00:00 the
 * next day) only for a legacy record predating the shiftId snapshot (same case
 * shiftStartAt/shiftEndAt already go null for), so it still renders something reasonable.
 */
export function resolveWorkdayWindow(
  workDate: string,
  workdayStartAt: string | null | undefined,
  workdayEndAt: string | null | undefined,
): WorkdayWindow {
  if (workdayStartAt && workdayEndAt) {
    const total = minutesBetween(workdayStartAt, workdayEndAt);
    if (total != null && total > 0) return { startIso: workdayStartAt, endIso: workdayEndAt, totalMinutes: total };
  }
  const endIso = `${nextCalendarDay(workDate)}T00:00:00`;
  return { startIso: `${workDate}T00:00:00`, endIso, totalMinutes: 1440 };
}

/** Left-offset percent (0-100) of {@code iso} within {@code window}, clamped to the track's own bounds. Null if unparseable. */
function pctInWindow(iso: string | null | undefined, window: WorkdayWindow): number | null {
  if (!iso) return null;
  const min = minutesBetween(window.startIso, iso);
  if (min == null) return null;
  return Math.max(0, Math.min(100, (min / window.totalMinutes) * 100));
}

export interface ShiftMarkerPositions {
  /** Left-offset percent (0-100) of the scheduled-shift-start marker on the workday-window track. */
  startPct: number;
  /** Left-offset percent (0-100) of the scheduled-shift-end marker on the workday-window track. */
  endPct: number;
}

/**
 * Left-offset percentages for the scheduled-shift-start/end markers, against {@code window} — the
 * SAME workday-window track the actual-attendance bar is positioned on (see
 * {@link segmentBarPosition}) — so the two line up correctly for comparison. This is the small
 * marker that shows where the shift sits within the workday; per the Attendance UI correction
 * spec it must keep existing and rendering, only its reference timeline changes (workday window,
 * not 00:00-24:00) — see AttendanceTimeline's own call site.
 *
 * `shiftStartAt`/`shiftEndAt` must already be the record's own resolved scheduled window
 * (AttendanceRecord.shiftStartAt/shiftEndAt) — this function makes no historical/timezone
 * decision itself; it only turns an already-correct instant into a position. Returns `null` when
 * either boundary is missing (a legacy pre-shift-snapshot record) or unparseable, so callers can
 * skip rendering the markers entirely rather than drawing one at 0%.
 */
export function shiftMarkerPositions(
  shiftStartAt: string | null | undefined,
  shiftEndAt: string | null | undefined,
  window: WorkdayWindow,
): ShiftMarkerPositions | null {
  if (!shiftStartAt || !shiftEndAt) return null;
  const startPct = pctInWindow(shiftStartAt, window);
  const endPct = pctInWindow(shiftEndAt, window);
  if (startPct == null || endPct == null) return null;
  return { startPct, endPct };
}

export interface SegmentBarPosition {
  leftPct: number;
  widthPct: number;
}

/**
 * Left-offset/width percentages for one ACTUAL check-in/check-out segment's bar, against
 * {@code window} — the same workday-window track {@link shiftMarkerPositions} positions its own
 * markers on. Because positions are now derived from each timestamp's full date+time against the
 * workday's own start (rather than a bare "HH:MM since midnight" reading on a fixed 00:00-24:00
 * track), an overnight session's checkout — genuinely on the next calendar day — naturally lands
 * further right than its own check-in with no separate rollover heuristic needed.
 *
 * A still-open session (`checkOutAt: null`) gets a small fixed 10-minute width so it stays
 * visible/hoverable; every result is floored to a minimum 0.6% width and clamped to the track's
 * own right edge — a session that (rarely) runs past the workday's own end renders up to that
 * edge rather than escaping the track. Returns `null` only when `checkInAt` itself doesn't parse
 * (a segment with no check-in has nothing to draw at all).
 */
export function segmentBarPosition(
  checkInAt: string,
  checkOutAt: string | null,
  window: WorkdayWindow,
): SegmentBarPosition | null {
  const leftPct = pctInWindow(checkInAt, window);
  if (leftPct == null) return null;
  const inMin = minutesBetween(window.startIso, checkInAt);
  if (inMin == null) return null;
  const outMin = checkOutAt ? minutesBetween(window.startIso, checkOutAt) : null;
  const rawWidthMin = (outMin ?? inMin + 10) - inMin;
  const widthPct = Math.min(100 - leftPct, Math.max(0.6, (rawWidthMin / window.totalMinutes) * 100));
  return { leftPct, widthPct };
}

export interface BreakMarkerPosition {
  leftPct: number;
  widthPct: number;
}

/**
 * Left-offset/width for the break gap between one session's `checkOutAt` and the next session's
 * `checkInAt`, against {@code window} — same basis as {@link segmentBarPosition}. Returns `null`
 * for a zero-length, negative (out of order), or unparseable gap.
 */
export function breakMarkerPosition(
  checkOutAt: string,
  nextCheckInAt: string,
  window: WorkdayWindow,
): BreakMarkerPosition | null {
  const breakStartMin = minutesBetween(window.startIso, checkOutAt);
  const breakEndMin = minutesBetween(window.startIso, nextCheckInAt);
  if (breakStartMin == null || breakEndMin == null || breakEndMin <= breakStartMin) return null;
  const leftPct = Math.max(0, Math.min(100, (breakStartMin / window.totalMinutes) * 100));
  return { leftPct, widthPct: Math.min(100 - leftPct, ((breakEndMin - breakStartMin) / window.totalMinutes) * 100) };
}
