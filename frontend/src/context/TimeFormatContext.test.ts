import { describe, expect, it } from 'vitest';
import { formatLateBySeconds } from './TimeFormatContext';

describe('formatLateBySeconds', () => {
  it('renders "30m 26s" for 30 minutes 26 seconds', () => {
    expect(formatLateBySeconds(1826)).toBe('30m 26s');
  });

  it('renders seconds only, no leading "0m", for under a minute', () => {
    expect(formatLateBySeconds(26)).toBe('26s');
  });

  it('renders "1m 5s" for 1 minute 5 seconds', () => {
    expect(formatLateBySeconds(65)).toBe('1m 5s');
  });

  it('renders an exact minute with no trailing "0s"', () => {
    expect(formatLateBySeconds(1800)).toBe('30m');
  });

  it('renders null (no "Late by" display) for exactly 0 seconds', () => {
    expect(formatLateBySeconds(0)).toBeNull();
  });

  it('renders null for a missing value', () => {
    expect(formatLateBySeconds(null)).toBeNull();
  });

  it('clamps a negative input to 0 rather than showing a negative duration', () => {
    expect(formatLateBySeconds(-5)).toBeNull();
  });

  it('truncates a fractional second rather than rounding', () => {
    // 59.9s must still read as "59s", never rounded up to "1m".
    expect(formatLateBySeconds(59.9)).toBe('59s');
  });

  // ── Hour-carrying: once elapsed minutes reach 60, they must roll into an "Xh" component
  // instead of rendering as raw triple-digit minutes (e.g. "351m 20s"). ──────────────────

  it('carries 351m20s into hours: "5h 51m 20s"', () => {
    expect(formatLateBySeconds(351 * 60 + 20)).toBe('5h 51m 20s');
  });

  it('carries 381m0s into hours with no trailing "0s": "6h 21m"', () => {
    expect(formatLateBySeconds(381 * 60)).toBe('6h 21m');
  });

  it('carries 61m37s into hours: "1h 1m 37s"', () => {
    expect(formatLateBySeconds(61 * 60 + 37)).toBe('1h 1m 37s');
  });

  it('stays under an hour unchanged: 59m55s is "59m 55s", not carried', () => {
    expect(formatLateBySeconds(59 * 60 + 55)).toBe('59m 55s');
  });

  it('exactly 60m0s is a bare "1h", never "1h 0m" or "1h 0m 0s"', () => {
    expect(formatLateBySeconds(60 * 60)).toBe('1h');
  });

  it('an exact hour with a seconds remainder omits the zero-minutes component: "1h 5s"', () => {
    expect(formatLateBySeconds(60 * 60 + 5)).toBe('1h 5s');
  });
});
