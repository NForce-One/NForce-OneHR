import { describe, expect, it } from 'vitest';
import { groupRequestsByType, TYPE_LABELS } from '../components/TypeBadge';
import type { ApprovalItem } from '../api/approvalCenter';

function mockRequest(requestType: 'LEAVE' | 'REGULARIZATION', id: string): ApprovalItem {
  return {
    id,
    requestType,
    employeeUserId: 'user-1',
    employeeName: 'Test User',
    createdAt: '2026-09-10T10:00:00Z',
  };
}

describe('groupRequestsByType', () => {
  it('returns empty array when there are no requests', () => {
    expect(groupRequestsByType([])).toEqual([]);
  });

  it('handles a single leave request without grouping', () => {
    const requests = [mockRequest('LEAVE', 'req-1')];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 1 },
    ]);
  });

  it('groups multiple leave requests and counts them (e.g. 3 leave requests)', () => {
    const requests = [
      mockRequest('LEAVE', 'req-1'),
      mockRequest('LEAVE', 'req-2'),
      mockRequest('LEAVE', 'req-3'),
    ];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 3 },
    ]);
  });

  it('groups mixed leave and regularization requests in consistent order', () => {
    const requests = [
      mockRequest('REGULARIZATION', 'reg-1'),
      mockRequest('LEAVE', 'lv-1'),
      mockRequest('LEAVE', 'lv-2'),
      mockRequest('REGULARIZATION', 'reg-2'),
      mockRequest('LEAVE', 'lv-3'),
    ];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 3 },
      { type: 'REGULARIZATION', count: 2 },
    ]);
  });
});

describe('TypeBadge badge text calculation', () => {
  function getBadgeText(type: 'LEAVE' | 'REGULARIZATION', count?: number): string {
    const label = TYPE_LABELS[type] ?? type;
    const suffix = count && count > 1 ? ` +${count - 1}` : '';
    return `${label}${suffix}`;
  }

  it('renders "Leave" when count is 1 or undefined', () => {
    expect(getBadgeText('LEAVE')).toBe('Leave');
    expect(getBadgeText('LEAVE', 1)).toBe('Leave');
  });

  it('renders "Leave +1" when count is 2', () => {
    expect(getBadgeText('LEAVE', 2)).toBe('Leave +1');
  });

  it('renders "Leave +2" when count is 3 (3 requests -> Leave +2)', () => {
    expect(getBadgeText('LEAVE', 3)).toBe('Leave +2');
  });

  it('renders "Attendance Reg." when count is 1', () => {
    expect(getBadgeText('REGULARIZATION', 1)).toBe('Attendance Reg.');
  });

  it('renders "Attendance Reg. +1" when count is 2', () => {
    expect(getBadgeText('REGULARIZATION', 2)).toBe('Attendance Reg. +1');
  });

  it('renders "Attendance Reg. +2" when count is 3', () => {
    expect(getBadgeText('REGULARIZATION', 3)).toBe('Attendance Reg. +2');
  });
});
