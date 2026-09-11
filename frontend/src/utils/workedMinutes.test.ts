import { describe, expect, it } from 'vitest';
import { computeWorkedMinutesFromPunches, msToMinuteEpoch, type WorkedInterval } from './workedMinutes';

// Mirrors shiftMarkers.test.ts's own convention: these are the exact zone-less wall-clock strings
// AttendanceResponse/PunchResponse send for checkInAt/checkOutAt — no "Z"/offset.
function iso(date: string, time: string): string {
  return `${date}T${time}`;
}

/** `nowMinuteEpoch` equivalent of a given wall-clock instant, for feeding as the "now" stand-in
 * for a still-open punch — mirrors how AttendancePage.tsx derives it via msToMinuteEpoch. */
function nowAt(date: string, time: string): number {
  // Date.UTC gives epoch ms for the same zone-less digits `computeWorkedMinutesFromPunches`
  // itself parses via its own wallClockMs — see that module's own header comment on why.
  const [y, mo, d] = date.split('-').map(Number);
  const [h, mi, s = '0'] = time.split(':');
  return msToMinuteEpoch(Date.UTC(y, mo - 1, d, Number(h), Number(mi), Number(s)));
}

const DAY = '2026-09-10';

describe('computeWorkedMinutesFromPunches', () => {
  it('1. completed interval + open interval: sums the closed interval plus the open interval up to "now"', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '11:00:00') }, // 120m, closed
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: null }, // open
    ];
    const now = nowAt(DAY, '15:00:00'); // open interval so far: 120m
    expect(computeWorkedMinutesFromPunches(punches, now)).toBe(120 + 120);
  });

  it('2. break between intervals: the gap between two non-overlapping intervals is excluded automatically, not subtracted separately', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '12:00:00') }, // 180m
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: iso(DAY, '15:00:00') }, // 120m, 1h break in between
    ];
    // 180 + 120 = 300 — the 60m gap (12:00-13:00) contributes nothing, and is never added back or
    // subtracted as a separate step; it simply falls outside both intervals.
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(300);
  });

  it('3. multiple intervals: three separate closed sessions the same day all sum correctly, breaks between every pair excluded', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '10:00:00') }, // 60m
      { checkInAt: iso(DAY, '11:00:00'), checkOutAt: iso(DAY, '12:00:00') }, // 60m
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: iso(DAY, '14:00:00') }, // 60m
    ];
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(180);
  });

  it('3b. multiple intervals supplied out of order still sum correctly (the function sorts by checkInAt itself)', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: iso(DAY, '14:00:00') },
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '10:00:00') },
      { checkInAt: iso(DAY, '11:00:00'), checkOutAt: iso(DAY, '12:00:00') },
    ];
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(180);
  });

  it('4. normal + Web Clock mixed punches: an overlapping Web Clock-In session inside a normal Check-In session is counted once, never twice', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '18:00:00') }, // normal, 540m
      { checkInAt: iso(DAY, '12:00:00'), checkOutAt: iso(DAY, '13:00:00') }, // web, fully inside the normal session
    ];
    // Must be 540 (the merged span), not 540 + 60 = 600 (double-counting the overlap).
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(540);
  });

  it('4b. normal + Web Clock mixed punches, back-to-back (no overlap, no gap) merge into one continuous span', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '12:00:00') }, // normal, 180m
      { checkInAt: iso(DAY, '12:00:00'), checkOutAt: iso(DAY, '14:00:00') }, // web, starts exactly when normal ends
    ];
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(300);
  });

  it('4c. a currently-open Web Clock-In can run concurrently with an open normal Check-In — both open at once, merged and counted once', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: null }, // normal, still open
      { checkInAt: iso(DAY, '10:00:00'), checkOutAt: null }, // web, also still open, overlapping
    ];
    const now = nowAt(DAY, '11:00:00');
    // Both intervals run from their own start to the same "now" and fully overlap — merged span
    // is 09:00-11:00 = 120m, not double-counted as 120 + 60.
    expect(computeWorkedMinutesFromPunches(punches, now)).toBe(120);
  });

  it('5. final value after checkout equals the live value computed at that same instant — Worked Today converges to Effective Hours, not a different number', () => {
    const openPunches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '11:00:00') }, // 120m, already closed earlier
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: null }, // still open
    ];
    const checkoutTime = nowAt(DAY, '16:30:00');
    const liveValue = computeWorkedMinutesFromPunches(openPunches, checkoutTime);

    // The instant the employee actually checks out, the backend closes that same punch at the
    // exact same wall-clock time — recomputeCombinedWorkedMinutes then sees it as a genuinely
    // closed interval, which this settled call reproduces (nowMinuteEpoch no longer needed, since
    // nothing is open anymore).
    const settledPunches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '11:00:00') },
      { checkInAt: iso(DAY, '13:00:00'), checkOutAt: iso(DAY, '16:30:00') },
    ];
    const settledValue = computeWorkedMinutesFromPunches(settledPunches, null);

    expect(liveValue).toBe(120 + 210); // 09:00-11:00 (120m) + 13:00-16:30 (210m)
    expect(liveValue).toBe(settledValue);
  });

  it('6. live Worked Today while checkout is missing: grows as "now" advances, and only the open interval (no other closed ones) still counts', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: null }, // never checked out
    ];
    const at0915 = computeWorkedMinutesFromPunches(punches, nowAt(DAY, '09:15:00'));
    const at0945 = computeWorkedMinutesFromPunches(punches, nowAt(DAY, '09:45:00'));
    const at1000 = computeWorkedMinutesFromPunches(punches, nowAt(DAY, '10:00:00'));

    expect(at0915).toBe(15);
    expect(at0945).toBe(45);
    expect(at1000).toBe(60);
    // Strictly increasing as time passes — a live-ticking figure, not a static one.
    expect(at0945).toBeGreaterThan(at0915 as number);
    expect(at1000).toBeGreaterThan(at0945 as number);
  });

  it('6b. with checkout genuinely missing and no "now" supplied (nothing open), the open punch is excluded — matches the backend\'s own "only closed sessions count" rule', () => {
    const punches: WorkedInterval[] = [
      { checkInAt: iso(DAY, '09:00:00'), checkOutAt: iso(DAY, '10:00:00') }, // 60m, closed
      { checkInAt: iso(DAY, '11:00:00'), checkOutAt: null }, // open, but nowMinuteEpoch is null
    ];
    expect(computeWorkedMinutesFromPunches(punches, null)).toBe(60);
  });

  it('returns null when there is nothing to sum at all (no punches)', () => {
    expect(computeWorkedMinutesFromPunches([], null)).toBeNull();
  });

  it('returns null when the only punch is open and there is no "now" to substitute', () => {
    const punches: WorkedInterval[] = [{ checkInAt: iso(DAY, '09:00:00'), checkOutAt: null }];
    expect(computeWorkedMinutesFromPunches(punches, null)).toBeNull();
  });
});
