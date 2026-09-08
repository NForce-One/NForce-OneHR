import { describe, expect, it } from 'vitest';
import { minutesSinceMidnight, shiftMarkerPositions, segmentBarPosition, breakMarkerPosition } from './shiftMarkers';

// These mirror the exact zone-less wall-clock strings AttendanceResponse sends for
// checkInAt/checkOutAt/shiftStartAt/shiftEndAt — no "Z"/offset, so minutesSinceMidnight's
// string-slice parsing (never `new Date()`) is what both the actual-attendance bar and these
// markers rely on to stay immune to the browser/system timezone (requirement: browser/system
// timezone must not affect marker positions).
function iso(date: string, time: string): string {
  return `${date}T${time}`;
}

describe('shiftMarkerPositions', () => {
  it('normal 09:00-18:00 shift: markers land exactly on the shift boundaries', () => {
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '18:00:00'));
    expect(positions).not.toBeNull();
    expect(positions!.startPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    expect(positions!.endPct).toBeCloseTo((18 * 60 / 1440) * 100, 6);
  });

  it('late check-in: the start marker stays at the scheduled 09:00, not the actual 09:30 check-in', () => {
    const shiftStart = iso('2026-03-10', '09:00:00');
    const actualCheckIn = iso('2026-03-10', '09:30:00');
    const positions = shiftMarkerPositions(shiftStart, iso('2026-03-10', '18:00:00'));

    expect(positions!.startPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    // The actual bar's own left position (same minutesSinceMidnight basis) must differ from the
    // marker — that gap IS the "late" visual the feature exists to show.
    const actualLeftPct = (minutesSinceMidnight(actualCheckIn)! / 1440) * 100;
    expect(actualLeftPct).toBeGreaterThan(positions!.startPct);
  });

  it('early checkout: the end marker stays at the scheduled 18:00, not the actual 17:45 checkout', () => {
    const shiftEnd = iso('2026-03-10', '18:00:00');
    const actualCheckOut = iso('2026-03-10', '17:45:00');
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), shiftEnd);

    expect(positions!.endPct).toBeCloseTo((18 * 60 / 1440) * 100, 6);
    const actualRightPct = (minutesSinceMidnight(actualCheckOut)! / 1440) * 100;
    expect(actualRightPct).toBeLessThan(positions!.endPct);
  });

  it('overnight shift: the end marker (rolled onto workDate+1) lands on its own hour-of-day, before the start marker on the single-day track', () => {
    // Shift 22:00 - 06:00 (next day) — AttendanceResponse.shiftEndAt is workDate+1 T06:00:00.
    const positions = shiftMarkerPositions(iso('2026-03-10', '22:00:00'), iso('2026-03-11', '06:00:00'));

    expect(positions).not.toBeNull();
    expect(positions!.startPct).toBeCloseTo((22 * 60 / 1440) * 100, 6); // 91.67%
    expect(positions!.endPct).toBeCloseTo((6 * 60 / 1440) * 100, 6); // 25%
    // Only the boundary's own wall-clock HH:MM matters — the calendar date rolled onto workDate+1
    // is irrelevant to where it lands, exactly like an overnight actual checkout already renders
    // on this same single-day (0-1440-minute) track.
    expect(positions!.endPct).toBeLessThan(positions!.startPct);
  });

  it('actual attendance extending beyond the scheduled end: end marker stays at 18:00 even though actual checkout is 18:15', () => {
    const shiftEnd = iso('2026-03-10', '18:00:00');
    const actualCheckOut = iso('2026-03-10', '18:15:00');
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), shiftEnd);

    expect(positions!.endPct).toBeCloseTo((18 * 60 / 1440) * 100, 6);
    const actualRightPct = (minutesSinceMidnight(actualCheckOut)! / 1440) * 100;
    expect(actualRightPct).toBeGreaterThan(positions!.endPct);
  });

  it('historical attendance after an employee shift change: uses whatever shiftStartAt/shiftEndAt this record carries, never a "current" shift window', () => {
    // The immutability guarantee itself (an old record's window never moving after a later shift
    // reassignment) is enforced server-side — see
    // AttendanceInterpretationServiceTest#resolveScheduledWindow_employeeReassignedSince_historicalRecordStillUsesItsOwnSnapshottedShift.
    // Here we only verify the frontend never substitutes any other value: two records for the
    // same employee, one under an old Shift A (9-18) and one (after reassignment) under a new
    // Shift B (14-22), must each resolve to THEIR OWN window, not the other's.
    const oldRecordWindow = shiftMarkerPositions(iso('2025-06-01', '09:00:00'), iso('2025-06-01', '18:00:00'));
    const newRecordWindow = shiftMarkerPositions(iso('2026-03-10', '14:00:00'), iso('2026-03-10', '22:00:00'));

    expect(oldRecordWindow!.startPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    expect(oldRecordWindow!.endPct).toBeCloseTo((18 * 60 / 1440) * 100, 6);
    expect(newRecordWindow!.startPct).toBeCloseTo((14 * 60 / 1440) * 100, 6);
    expect(newRecordWindow!.endPct).toBeCloseTo((22 * 60 / 1440) * 100, 6);
  });

  it('returns null for a legacy record with no scheduled window at all, rather than drawing a marker at 0%', () => {
    expect(shiftMarkerPositions(null, null)).toBeNull();
    expect(shiftMarkerPositions(iso('2026-03-10', '09:00:00'), null)).toBeNull();
    expect(shiftMarkerPositions(undefined, undefined)).toBeNull();
  });

  it('is immune to the browser/system timezone: no Date object is ever constructed from the wall-clock string', () => {
    // A regression here would most likely come from swapping the string-slice parse for
    // `new Date(iso)`, which re-interprets a zone-less string in the LOCAL runtime's timezone.
    const positions = shiftMarkerPositions(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '18:00:00'));
    expect(positions!.startPct).toBeCloseTo(37.5, 6); // 9h/24h, regardless of process.env.TZ
    expect(positions!.endPct).toBeCloseTo(75, 6); // 18h/24h
  });
});

describe('segmentBarPosition', () => {
  it('normal same-day session: bar spans exactly from check-in to check-out', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '17:00:00'));
    expect(pos).not.toBeNull();
    expect(pos!.leftPct).toBeCloseTo((9 * 60 / 1440) * 100, 6);
    expect(pos!.widthPct).toBeCloseTo((8 * 60 / 1440) * 100, 6);
  });

  it('still-open session (no checkout): gets a small fixed-width bar, not a zero-width one', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), null);
    expect(pos).not.toBeNull();
    expect(pos!.widthPct).toBeCloseTo((10 / 1440) * 100, 6);
  });

  /**
   * Regression for a real bug: an overnight session's checkout (e.g. 23:00 → 07:00 the next
   * morning) has a SMALLER "HH:MM since midnight" reading than its own check-in, since the date
   * portion is discarded. Before the fix, this collapsed an ~8-hour overnight session down to the
   * minimum 0.6% floor width — an almost-invisible sliver positioned as if the session were
   * essentially instantaneous. A single 0-1440-minute (one calendar day) track has no way to draw
   * a session that genuinely continues past midnight as one unbroken bar without either wrapping
   * (splitting into two pieces) or bleeding into whatever sits to the right of the track (this
   * track has no `overflow: hidden` ancestor to clip it) — so the fix instead extends the bar all
   * the way to the track's own right edge, an honest "this continues past today's view" signal,
   * rather than either the old near-invisible sliver or a bar that visually escapes the track.
   */
  it('overnight session: checkout rolls onto the next day, bar extends to the track\'s right edge (not collapsed to the floor)', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '23:00:00'), iso('2026-03-11', '07:00:00'));
    expect(pos).not.toBeNull();
    expect(pos!.leftPct).toBeCloseTo((23 * 60 / 1440) * 100, 6); // 95.83%
    // Extends exactly to the track's right edge (100%) — clearly visible, unlike the old 0.6%
    // floor sliver — rather than a bar that would otherwise bleed past the track's own bounds.
    expect(pos!.leftPct + pos!.widthPct).toBeCloseTo(100, 6);
    expect(pos!.widthPct).toBeGreaterThan(0.6);
  });

  it('same-minute check-in/check-out is a near-zero-width bar, not a full 24h one', () => {
    const pos = segmentBarPosition(iso('2026-03-10', '09:00:00'), iso('2026-03-10', '09:00:00'));
    expect(pos).not.toBeNull();
    expect(pos!.widthPct).toBeCloseTo(0.6, 6); // floored, not treated as a rolled-over full day
  });

  it('returns null when check-in itself is unparseable', () => {
    expect(segmentBarPosition('not-an-iso', iso('2026-03-10', '18:00:00'))).toBeNull();
  });
});

describe('breakMarkerPosition', () => {
  it('normal same-day break: spans exactly from checkout to the next check-in', () => {
    const pos = breakMarkerPosition(iso('2026-03-10', '13:00:00'), iso('2026-03-10', '14:00:00'));
    expect(pos).not.toBeNull();
    expect(pos!.leftPct).toBeCloseTo((13 * 60 / 1440) * 100, 6);
    expect(pos!.widthPct).toBeCloseTo((60 / 1440) * 100, 6);
  });

  /** Regression: a break inside an overnight shift (e.g. checkout 23:30, resume 00:15 the next
   * calendar day) must still render — before the fix this read as a negative/zero span and was
   * silently dropped instead of shown as a break. Same right-edge clamp as segmentBarPosition:
   * the break's true 45-minute span (23:30-00:15) partly falls outside this single-day track, so
   * it renders up to the track's own edge rather than escaping it. */
  it('overnight break: resume check-in rolls onto the next day, break still renders (clamped to the track edge)', () => {
    const pos = breakMarkerPosition(iso('2026-03-10', '23:30:00'), iso('2026-03-11', '00:15:00'));
    expect(pos).not.toBeNull();
    expect(pos!.leftPct + pos!.widthPct).toBeCloseTo(100, 6);
    expect(pos!.widthPct).toBeGreaterThan(0);
  });

  it('zero-length gap (back-to-back punches) renders nothing', () => {
    expect(breakMarkerPosition(iso('2026-03-10', '13:00:00'), iso('2026-03-10', '13:00:00'))).toBeNull();
  });

  it('returns null for an unparseable boundary', () => {
    expect(breakMarkerPosition('bad', iso('2026-03-10', '14:00:00'))).toBeNull();
  });
});
