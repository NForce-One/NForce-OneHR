// ─── Attendance Log: scheduled-shift-boundary marker positioning ──────────────────────────────
// Backs the two small start/end markers AttendanceTimeline (AttendancePage.tsx) draws on top of
// the actual-attendance bar's own 24-hour track, so an employee can compare "when I was scheduled"
// against "when I actually showed up" at a glance — see AttendanceResponse.shiftStartAt/shiftEndAt
// and AttendanceInterpretationService#resolveScheduledWindow on the backend for how those two
// instants are resolved (against the record's OWN snapshotted ShiftVersion/timezone, never the
// employee's current shift or the browser's timezone).

/**
 * Minutes since local midnight, parsed the same zone-less way AttendancePage's own
 * checkIn/checkOut bar positioning does: sliced straight out of the ISO string's "HH:MM"
 * rather than through `new Date()`, which would re-interpret it in the browser's own timezone
 * and shift the result. Server timestamps here are wall-clock strings with no offset (see
 * AttendancePage's own header comment) — this is the single shared parser both the actual-bar
 * and the shift markers use, so they stay on the exact same time basis.
 */
export function minutesSinceMidnight(iso: string): number | null {
  const time = iso.slice(11, 16);
  if (time.length < 5) return null;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

export interface ShiftMarkerPositions {
  /** Left-offset percent (0-100) of the scheduled-shift-start marker on the 24h track. */
  startPct: number;
  /** Left-offset percent (0-100) of the scheduled-shift-end marker on the 24h track. */
  endPct: number;
}

/**
 * Left-offset percentages for the scheduled-shift-start/end markers, against the identical
 * 0-1440-minute (24h) track the actual-attendance bar is positioned on (see AttendanceTimeline's
 * `leftPct`/`widthPct` math) — so the two line up correctly for comparison.
 *
 * `shiftStartAt`/`shiftEndAt` must already be the record's own resolved scheduled window
 * (AttendanceRecord.shiftStartAt/shiftEndAt) — this function makes no historical/timezone
 * decision itself; it only turns an already-correct instant into a position, exactly like
 * `minutesSinceMidnight` already does for checkInAt/checkOutAt.
 *
 * Each boundary is positioned purely from its own wall-clock HH:MM, so an overnight shift's end
 * (which the backend rolls onto workDate+1) still lands on its own correct hour-of-day tick —
 * the same way an overnight actual checkout already renders on this single-day track. Returns
 * `null` when either boundary is missing (a legacy pre-shift-snapshot record) or unparseable, so
 * callers can skip rendering the markers entirely rather than drawing one at 0%.
 */
export function shiftMarkerPositions(
  shiftStartAt: string | null | undefined,
  shiftEndAt: string | null | undefined,
): ShiftMarkerPositions | null {
  if (!shiftStartAt || !shiftEndAt) return null;
  const startMin = minutesSinceMidnight(shiftStartAt);
  const endMin = minutesSinceMidnight(shiftEndAt);
  if (startMin == null || endMin == null) return null;
  return { startPct: (startMin / 1440) * 100, endPct: (endMin / 1440) * 100 };
}

export interface SegmentBarPosition {
  leftPct: number;
  widthPct: number;
}

/**
 * Left-offset/width percentages for one ACTUAL check-in/check-out segment's bar, on the exact
 * same 0-1440-minute (24h) track {@link shiftMarkerPositions} positions its own markers on.
 *
 * An overnight session's checkout genuinely falls on the next calendar day (e.g. check-in 23:00,
 * checkout 07:00 the next morning) — its own "HH:MM since midnight" reading is then numerically
 * SMALLER than the check-in's, which would otherwise collapse the bar to a near-invisible sliver
 * (a negative duration floored to the minimum width). Same rollover convention already used
 * identically for this exact reason elsewhere in this codebase (ShiftDayPolicy.shiftEndAt,
 * ExpectedWorkHoursService.shiftMinutes, OrgService.validateShiftDuration,
 * WorkHoursShortageCalculationService.shiftBoundedGrossMinutes): a smaller end-of-day clock
 * reading than the start means it rolled past midnight, so a full day is added back.
 *
 * A still-open session (`checkOutAt: null`) gets a small fixed 10-minute width so it stays
 * visible/hoverable; every result is floored to a minimum 0.6% width. A single-calendar-day track
 * has no way to draw a session that genuinely continues past midnight as one unbroken bar without
 * either wrapping (splitting into two pieces) or bleeding into whatever sits to the right of the
 * track — so for an overnight session whose true duration would overflow the track, the width is
 * instead capped at the track's own right edge: an honest "this continues past today's view"
 * signal, not a claim that the bar's pixel width equals the session's true duration. Returns
 * `null` only when `checkInAt` itself doesn't parse (a segment with no check-in has nothing to
 * draw at all).
 */
export function segmentBarPosition(checkInAt: string, checkOutAt: string | null): SegmentBarPosition | null {
  const inMin = minutesSinceMidnight(checkInAt);
  if (inMin == null) return null;
  let outMin = checkOutAt ? minutesSinceMidnight(checkOutAt) : null;
  if (outMin != null && outMin < inMin) outMin += 1440;
  const leftPct = (inMin / 1440) * 100;
  const widthPct = Math.min(100 - leftPct, Math.max(0.6, (((outMin ?? inMin + 10) - inMin) / 1440) * 100));
  return { leftPct, widthPct };
}

export interface BreakMarkerPosition {
  leftPct: number;
  widthPct: number;
}

/**
 * Left-offset/width for the break gap between one session's `checkOutAt` and the next session's
 * `checkInAt` — same overnight-rollover handling as {@link segmentBarPosition} (a break spanning
 * midnight, inside an overnight shift, otherwise reads as a negative/zero span and is silently
 * dropped instead of rendered). Returns `null` for a zero-length or unparseable gap.
 */
export function breakMarkerPosition(checkOutAt: string, nextCheckInAt: string): BreakMarkerPosition | null {
  const breakStartMin = minutesSinceMidnight(checkOutAt);
  let breakEndMin = minutesSinceMidnight(nextCheckInAt);
  if (breakStartMin == null || breakEndMin == null) return null;
  if (breakEndMin < breakStartMin) breakEndMin += 1440;
  if (breakEndMin === breakStartMin) return null;
  const leftPct = (breakStartMin / 1440) * 100;
  return { leftPct, widthPct: Math.min(100 - leftPct, ((breakEndMin - breakStartMin) / 1440) * 100) };
}
