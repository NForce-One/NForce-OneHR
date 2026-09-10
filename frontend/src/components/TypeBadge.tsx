import type { ApprovalItem } from '../api/approvalCenter';

export const TYPE_LABELS: Record<'LEAVE' | 'REGULARIZATION', string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
};

export const TYPE_COLORS: Record<'LEAVE' | 'REGULARIZATION', string> = {
  LEAVE: 'rgba(99,102,241,.18)',
  REGULARIZATION: 'rgba(245,158,11,.18)',
};

export const TYPE_TEXT: Record<'LEAVE' | 'REGULARIZATION', string> = {
  LEAVE: '#818CF8',
  REGULARIZATION: '#F59E0B',
};

export function groupRequestsByType(requests: ApprovalItem[]): Array<{ type: 'LEAVE' | 'REGULARIZATION'; count: number }> {
  const counts = new Map<'LEAVE' | 'REGULARIZATION', number>();
  for (const r of requests) {
    const type = r.requestType as 'LEAVE' | 'REGULARIZATION';
    counts.set(type, (counts.get(type) ?? 0) + 1);
  }
  const preferredOrder: Array<'LEAVE' | 'REGULARIZATION'> = ['LEAVE', 'REGULARIZATION'];
  const orderedKeys = [
    ...preferredOrder.filter(t => counts.has(t)),
    ...Array.from(counts.keys()).filter(t => !preferredOrder.includes(t)),
  ];
  return orderedKeys.map(type => ({ type, count: counts.get(type)! }));
}

export function TypeBadge({ type, count }: { type: 'LEAVE' | 'REGULARIZATION'; count?: number }) {
  const label = TYPE_LABELS[type] ?? type;
  const suffix = count && count > 1 ? ` +${count - 1}` : '';
  return (
    <span
      title={count && count > 1 ? `${count} pending ${label.toLowerCase()} requests` : undefined}
      style={{
        fontSize: 10.5,
        fontWeight: 700,
        padding: '3px 8px',
        borderRadius: 20,
        background: TYPE_COLORS[type] ?? 'rgba(99,102,241,.18)',
        color: TYPE_TEXT[type] ?? '#818CF8',
        whiteSpace: 'nowrap',
      }}
    >
      {label}{suffix}
    </span>
  );
}
