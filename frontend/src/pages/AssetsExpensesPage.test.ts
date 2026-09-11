import { describe, expect, it } from 'vitest';
import { isExpenseDateInFuture, todayIsoDate } from './AssetsExpensesPage';

describe('Expense Date Validation', () => {
  it('formats todayIsoDate as YYYY-MM-DD', () => {
    const today = todayIsoDate();
    expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('allows past dates', () => {
    expect(isExpenseDateInFuture('2026-09-01', '2026-09-10')).toBe(false);
    expect(isExpenseDateInFuture('2025-12-31', '2026-09-10')).toBe(false);
  });

  it('allows today date', () => {
    expect(isExpenseDateInFuture('2026-09-10', '2026-09-10')).toBe(false);
  });

  it('flags future dates as invalid', () => {
    expect(isExpenseDateInFuture('2026-09-11', '2026-09-10')).toBe(true);
    expect(isExpenseDateInFuture('2026-10-01', '2026-09-10')).toBe(true);
    expect(isExpenseDateInFuture('2027-01-01', '2026-09-10')).toBe(true);
  });

  it('handles empty date string safely', () => {
    expect(isExpenseDateInFuture('', '2026-09-10')).toBe(false);
  });
});
