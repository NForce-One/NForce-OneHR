import { describe, expect, it } from 'vitest';
import {
  minutesSinceMidnight,
  minutesBetween,
  resolveWorkdayWindow,
  shiftMarkerPositions,
  segmentBarPosition,
  breakMarkerPosition,
  punchCalendarDateIfDiffers,
  type WorkdayWindow,
} from './shiftMarkers';

// These mirror the exact zone-less wall-clock strings AttendanceResponse sends for
// checkInAt/checkOutAt/shiftStartAt/shiftEndAt/workdayStartAt/workdayEndAt — no "Z"/offset, so
// this module's own string-parsing (never `new Date(iso)`, which would re-interpret it in the
// browser's own timezone) is what keeps every position immune to the viewer's own timezone.
function iso(date: string, time: string): string {
  return `${date}T${time}`;
}

describe('resolveWorkdayWindow', () => {
  it("the spec's own worked example: 10:00-19:00 shift, 18h max workday duration -> workday 04:00 -> 04:00 next day, never 00:00-24:00", () => {
    const window = resolveWorkdayWindow(
      '2026-09-08',
      iso('2026-09-08', '04:00:00'),
      iso('2026-09-09', '04:00:00'),
    );
    expect(window.startIso).toBe(iso('2026-09-08', '04:00:00'));
    expect(window.endIso).toBe(iso('2026-09-09', '04:00:00'));
    expect(window.totalMinutes).toBe(24 * 60);
  });

  it('overnight shift (22:00-07:00-shaped workday): window still derived purely from workdayStartAt/workdayEndAt, never a fixed clock time', () => {
    const window = resolveWorkdayWindow(
      '2026-09-08',
      iso('2026-09-08', '16:00:00'),
      iso('2026-09-09', '16:00:00'),
    );
    expect(window.totalMinutes).toBe(24 * 60);
    expect(window.startIso).toBe(iso('2026-09-08', '16:00:00'));
  });

  it('legacy record with no workday snapshot: falls back to the record\'s own calendar day (00:00 -> next day 00:00), not a crash or a zero-length window', () => {
    const window = resolveWorkdayWindow('2025-01-01', null, null);
    expect(window.startIso).toBe('2025-01-01T00:00:00');
    expect(window.endIso).toBe('2025-01-02T00:00:00');
    expect(window.totalMinutes).toBe(1440);
  });
});

describe('minutesBetween', () => {
  it('computes real elapsed minutes across a calendar-day boundary (a post-midnight punch is NOT collapsed back near its own time-of-day)', () => {
    // 12:21 AM the next day is 14h56m (896 minutes) after 9:25 AM the previous day.
    expect(minutesBetween(iso('2026-09-08', '09:25:00'), iso('2026-09-09', '00:21:00'))).toBe(896);
  });

  it('is immune to the browser/system timezone: pure calendar-field arithmetic, no real Date/offset involved', () => {
    expect(minutesBetween(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '18:00:00'))).toBe(9 * 60);
  });
});

describe('shiftMarkerPositions', () => {
  const normalWindow: WorkdayWindow = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '00:00:00'), iso('2026-03-11', '00:00:00'));

  it('normal 09:00-18:00 shift on a 00:00-24:00-shaped workday: markers land exactly on the shift boundaries', () => {
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '18:00:00'), normalWindow);
    expect(positions).not.toBeNull();
    expect(positions!.startPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    expect(positions!.endPct).toBeCloseTo((18 * 60 / 1440) * 100, 6);
  });

  it("the spec's own worked example: 10:00-19:00 shift, workday 04:00 -> 04:00 next day — shift-start marker at 25%, shift-end marker at 62.5%", () => {
    const window = resolveWorkdayWindow('2026-09-08', iso('2026-09-08', '04:00:00'), iso('2026-09-09', '04:00:00'));
    const positions = shiftMarkerPositions(iso('2026-09-08', '10:00:00'), iso('2026-09-08', '19:00:00'), window);
    expect(positions).not.toBeNull();
    expect(positions!.startPct).toBeCloseTo(25, 6); // 6h into a 24h window
    expect(positions!.endPct).toBeCloseTo(62.5, 6); // 15h into a 24h window
  });

  it('late check-in: the start marker stays at the scheduled 09:00, not the actual 09:30 check-in', () => {
    const shiftStart = iso('2026-03-10', '09:00:00');
    const actualCheckIn = iso('2026-03-10', '09:30:00');
    const positions = shiftMarkerPositions(shiftStart, iso('2026-03-10', '18:00:00'), normalWindow);

    expect(positions!.startPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    const actualLeft = segmentBarPosition(actualCheckIn, null, normalWindow);
    expect(actualLeft!.leftPct).toBeGreaterThan(positions!.startPct);
  });

  it('overnight shift: the end marker (on the next calendar day) lands to the RIGHT of the start marker on the workday-window track, not wrapped back to its own hour-of-day', () => {
    // Shift 22:00 - 06:00 (next day), workday window 16:00 -> 16:00 next day (22:00 + 18h max).
    const window = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '16:00:00'), iso('2026-03-11', '16:00:00'));
    const positions = shiftMarkerPositions(iso('2026-03-10', '22:00:00'), iso('2026-03-11', '06:00:00'), window);

    expect(positions).not.toBeNull();
    expect(positions!.startPct).toBeCloseTo((6 * 60 / 1440) * 100, 6); // 6h into the window = 25%
    expect(positions!.endPct).toBeCloseTo((14 * 60 / 1440) * 100, 6); // 14h into the window
    expect(positions!.endPct).toBeGreaterThan(positions!.startPct);
  });

  it('returns null for a legacy record with no scheduled window at all, rather than drawing a marker at 0%', () => {
    expect(shiftMarkerPositions(null, null, normalWindow)).toBeNull();
    expect(shiftMarkerPositions(iso('2026-03-10', '09:00:00'), null, normalWindow)).toBeNull();
    expect(shiftMarkerPositions(undefined, undefined, normalWindow)).toBeNull();
  });

  it('is immune to the browser/system timezone: no timezone-sensitive Date construction is involved', () => {
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '18:00:00'), normalWindow);
    expect(positions!.startPct).toBeCloseTo(37.5, 6); // 9h/24h, regardless of process.env.TZ
    expect(positions!.endPct).toBeCloseTo(75, 6); // 18h/24h
  });
});

describe('segmentBarPosition', () => {
  const normalWindow: WorkdayWindow = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '00:00:00'), iso('2026-03-11', '00:00:00'));

  it('normal same-day session: bar spans exactly from check-in to check-out', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '17:00:00'), normalWindow);
    expect(pos).not.toBeNull();
    expect(pos!.leftPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    expect(pos!.widthPct).toBeCloseTo((8 * 60 / 1440) * 100, 6);
  });

  it('still-open session (no checkout): gets a small fixed-width bar, not a zero-width one', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), null, normalWindow);
    expect(pos).not.toBeNull();
    expect(pos!.widthPct).toBeCloseTo((10 / 1440) * 100, 6);
  });

  /**
   * The spec's own worked example, end to end: workday 04:00 (Sep 8) -> 04:00 (Sep 9), shift
   * 10:00-19:00. A post-midnight punch (12:21 AM / 3:43 AM Sep 9) must position to the RIGHT of
   * the shift-end marker — i.e. later in the SAME workday — never wrap back near the start.
   */
  it('post-midnight punches within the same workday position strictly after the evening ones, never wrapping back to the start', () => {
    const window = resolveWorkdayWindow('2026-09-08', iso('2026-09-08', '04:00:00'), iso('2026-09-09', '04:00:00'));

    const morning = segmentBarPosition(iso('2026-09-08', '09:25:00'), iso('2026-09-08', '10:03:00'), window)!;
    const afternoon = segmentBarPosition(iso('2026-09-08', '15:43:00'), null, window)!;
    const postMidnight = segmentBarPosition(iso('2026-09-09', '00:21:00'), iso('2026-09-09', '03:45:00'), window)!;

    expect(morning.leftPct).toBeLessThan(afternoon.leftPct);
    expect(afternoon.leftPct).toBeLessThan(postMidnight.leftPct);
    // Check-in 12:21 AM Sep 9 is 20h21m (1221 min) into the 04:00-Sep8 -> 04:00-Sep9 window.
    expect(postMidnight.leftPct).toBeCloseTo((1221 / 1440) * 100, 6);
    // Check-out 3:45 AM Sep 9 is 23h45m (1425 min) in — right at the track's own trailing edge.
    expect(postMidnight.leftPct + postMidnight.widthPct).toBeCloseTo((1425 / 1440) * 100, 6);
  });

  it('overnight session: checkout on the next calendar day naturally lands further right, no rollover heuristic needed', () => {
    const window = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '16:00:00'), iso('2026-03-11', '16:00:00'));
    const pos = segmentBarPosition(iso('2026-03-10', '23:00:00'), iso('2026-03-11', '07:00:00'), window);
    expect(pos).not.toBeNull();
    // 23:00 is 7h into the 16:00->16:00 window; 07:00 next day is 15h in.
    expect(pos!.leftPct).toBeCloseTo((7 * 60 / 1440) * 100, 6);
    expect(pos!.leftPct + pos!.widthPct).toBeCloseTo((15 * 60 / 1440) * 100, 6);
  });

  it('a session that runs past the workday\'s own end renders up to the track\'s right edge rather than escaping it', () => {
    const window = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '00:00:00'), iso('2026-03-11', '00:00:00'));
    const pos = segmentBarPosition(iso('2026-03-10', '23:50:00'), iso('2026-03-11', '02:00:00'), window);
    expect(pos).not.toBeNull();
    expect(pos!.leftPct + pos!.widthPct).toBeCloseTo(100, 6);
  });

  it('same-minute check-in/check-out is a near-zero-width bar, not a full-window one', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '09:00:00'), normalWindow);
    expect(pos).not.toBeNull();
    expect(pos!.widthPct).toBeCloseTo(0.6, 6);
  });

  it('returns null when check-in itself is unparseable', () => {
    expect(segmentBarPosition('not-an-iso', iso('2026-03-10', '18:00:00'), normalWindow)).toBeNull();
  });
});

describe('breakMarkerPosition', () => {
  const normalWindow: WorkdayWindow = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '00:00:00'), iso('2026-03-11', '00:00:00'));

  it('normal same-day break: spans exactly from checkout to the next check-in', () => {
    const pos = breakMarkerPosition(iso('2026-03-10', '13:00:00'), iso('2026-03-10', '14:00:00'), normalWindow);
    expect(pos).not.toBeNull();
    expect(pos!.leftPct).toBeCloseTo((13 * 60 / 1440) * 100, 6);
    expect(pos!.widthPct).toBeCloseTo((60 / 1440) * 100, 6);
  });

  it('overnight break: resume check-in on the next calendar day still renders correctly (clamped to the track edge)', () => {
    const window = resolveWorkdayWindow('2026-03-10', iso('2026-03-10', '16:00:00'), iso('2026-03-11', '16:00:00'));
    const pos = breakMarkerPosition(iso('2026-03-10', '23:30:00'), iso('2026-03-11', '00:15:00'), window);
    expect(pos).not.toBeNull();
    expect(pos!.widthPct).toBeGreaterThan(0);
  });

  it('zero-length gap (back-to-back punches) renders nothing', () => {
    expect(breakMarkerPosition(iso('2026-03-10', '13:00:00'), iso('2026-03-10', '13:00:00'), normalWindow)).toBeNull();
  });

  it('returns null for an unparseable boundary', () => {
    expect(breakMarkerPosition('bad', iso('2026-03-10', '14:00:00'), normalWindow)).toBeNull();
  });
});

describe('minutesSinceMidnight (unchanged — used elsewhere for time-of-day-only comparisons, not the workday-window track)', () => {
  it('still parses HH:MM regardless of date', () => {
    expect(minutesSinceMidnight(iso('2026-03-10', '09:30:00'))).toBe(9 * 60 + 30);
  });
});

// Backs the Attendance punch list's (PunchSourceGroup, in AttendancePage.tsx) date qualifier —
// see the Attendance audit's "PunchSourceGroup date-display defect" fix. Source-agnostic: the
// exact same function call backs both the "Check-In / Check-Out" and "Web Check-In / Check-Out"
// sections, and both a punch's checkInAt and its checkOutAt, so one set of tests here covers all
// four call sites in PunchSourceGroup identically.
describe('punchCalendarDateIfDiffers', () => {
  it('(a) same-day punch: returns null — renders as time only, exactly as before this fix', () => {
    expect(punchCalendarDateIfDiffers(iso('2026-09-08', '14:55:00'), '2026-09-08')).toBeNull();
  });

  it('(b) next-calendar-day overnight punch: returns the punch\'s own (later) calendar date, not the workDate', () => {
    // The exact "12:11 AM" shape from the audit — a post-midnight punch still attributed to the
    // PREVIOUS logical workday by ShiftDayPolicy#shiftDayOf, which must render with a date
    // qualifier rather than looking indistinguishable from (or ahead of) a same-day punch.
    expect(punchCalendarDateIfDiffers(iso('2026-09-09', '00:11:00'), '2026-09-08')).toBe('2026-09-09');
  });

  it('(c) previous-calendar-day case: returns the punch\'s own (earlier) calendar date', () => {
    // Supported by the existing workday model too — e.g. a Regularization-created row whose
    // corrected check-in legitimately lands the calendar day BEFORE its own workDate.
    expect(punchCalendarDateIfDiffers(iso('2026-09-07', '23:50:00'), '2026-09-08')).toBe('2026-09-07');
  });

  it('never re-derives the date via `Date` parsing — pure string slicing, immune to any browser timezone', () => {
    // A malformed/partial time portion must not throw or fall through to a `Date`-based guess;
    // the date portion (first 10 characters) is all this function ever looks at.
    expect(punchCalendarDateIfDiffers('2026-09-08T00:00', '2026-09-08')).toBeNull();
    expect(punchCalendarDateIfDiffers('2026-09-09T00:00', '2026-09-08')).toBe('2026-09-09');
  });
});
