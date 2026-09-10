import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { myRequestsApi, type MyRequestItem, type RequestType } from '../api/myRequests';
import { formatDurationMinutes } from '../context/TimeFormatContext';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

const TYPE_LABELS: Record<RequestType, string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
  WFH: 'Work From Home',
  PARTIAL_DAY: 'Partial Day',
  OVERTIME: 'Overtime',
};

const TYPE_COLORS: Record<RequestType, string> = {
  LEAVE: 'rgba(99,102,241,.18)',
  REGULARIZATION: 'rgba(245,158,11,.18)',
  WFH: 'rgba(76,141,214,.18)',
  PARTIAL_DAY: 'rgba(224,169,59,.18)',
  OVERTIME: 'rgba(236,72,153,.18)',
};

const TYPE_TEXT: Record<RequestType, string> = {
  LEAVE: '#818CF8',
  REGULARIZATION: '#F59E0B',
  WFH: '#4C8DD6',
  PARTIAL_DAY: '#E0A93B',
  OVERTIME: '#EC4899',
};

function TypeBadge({ type }: { type: RequestType }) {
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: TYPE_COLORS[type], color: TYPE_TEXT[type], whiteSpace: 'nowrap' }}>
      {TYPE_LABELS[type]}
    </span>
  );
}

const STATUS_COLORS: Record<string, { bg: string; color: string }> = {
  PENDING: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
  // Regularization-only: manager stage approved, awaiting HR/Super Admin final approval.
  PARTIALLY_APPROVED: { bg: 'rgba(59,130,196,.15)', color: '#3B82C4' },
  APPROVED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
  REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
};

function StatusBadge({ status }: { status: string }) {
  const style = STATUS_COLORS[status] ?? { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' };
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: style.bg, color: style.color, whiteSpace: 'nowrap' }}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

function fmtDate(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
}

/** Overtime's requested duration ("Overtime Hours") — derived from requestedCheckIn/Out rather
 * than shown as raw clock times, since the employee-facing concept is hours claimed, not when. */
function fmtOvertimeHours(startIso?: string | null, endIso?: string | null) {
  if (!startIso || !endIso) return '—';
  const minutes = Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);
  if (minutes <= 0) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

// ── Type-aware row detail summary ─────────────────────────

function ItemDetail({ item }: { item: MyRequestItem }) {
  if (item.requestType === 'LEAVE') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.leaveTypeName} · {item.leaveStartDate}{item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''} · {item.leaveTotalDays} day{item.leaveTotalDays !== 1 ? 's' : ''}{item.leaveHalfDay ? ' (half)' : ''}
      </div>
    );
  }
  if (item.requestType === 'REGULARIZATION') {
    const missing = item.requestedCheckIn && item.requestedCheckOut ? 'Check-in & check-out'
      : item.requestedCheckIn ? 'Check-in' : 'Check-out';
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate} · Missing: {missing}
      </div>
    );
  }
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate}{item.requestType === 'PARTIAL_DAY' && item.partialDayHours != null ? ` · ${formatDurationMinutes(Math.round(item.partialDayHours * 60))}` : ''}
      </div>
    );
  }
  if (item.requestType === 'OVERTIME') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate} · {item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : '—'} → {item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : '—'}
      </div>
    );
  }
  return null;
}

// ── Detail modal (read-only) ───────────────────────────────

function Row({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <div style={labelStyle}>{label}</div>
      <div style={{ fontSize: 13, color: 'var(--txt)' }}>{value ?? '—'}</div>
    </div>
  );
}

function RequestDetailModal({ item, onClose }: { item: MyRequestItem; onClose: () => void }) {
  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <TypeBadge type={item.requestType} />
            <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
              Request Details
            </span>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
            {item.requestType === 'LEAVE' && (
              <>
                <Row label="Type" value={item.leaveTypeName} />
                <Row label="Dates" value={`${item.leaveStartDate}${item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`} />
                <Row label="Days" value={String(item.leaveTotalDays)} />
                <Row label="Reason" value={item.leaveReason} />
              </>
            )}

            {item.requestType === 'REGULARIZATION' && (
              <>
                <Row label="Attendance Date" value={item.attendanceDate} />
                <Row label="Requested Check-in" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
                <Row label="Requested Check-out" value={item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : 'Not provided'} />
                <Row label="Reason" value={item.regularizationReason} />
              </>
            )}

            {(item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') && (
              <>
                <Row label="Date" value={item.attendanceDate} />
                {item.requestType === 'PARTIAL_DAY' && <Row label="Duration" value={item.partialDayHours != null ? (formatDurationMinutes(Math.round(item.partialDayHours * 60)) ?? undefined) : undefined} />}
                <Row label="Reason" value={item.regularizationReason} />
              </>
            )}

            {item.requestType === 'OVERTIME' && (
              <>
                <Row label="Date" value={item.attendanceDate} />
                <Row label="Overtime Hours" value={fmtOvertimeHours(item.requestedCheckIn, item.requestedCheckOut)} />
                <Row label="Reason" value={item.regularizationReason} />
              </>
            )}

            <div>
              <div style={labelStyle}>Status</div>
              <StatusBadge status={item.status} />
            </div>
            {item.decisionReason && <Row label="Decision Reason" value={item.decisionReason} />}
            {item.decidedByName && <Row label="Decided By" value={`${item.decidedByName}${item.decidedAt ? ` on ${fmtDate(item.decidedAt)}` : ''}`} />}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const ALL_TYPES: RequestType[] = ['LEAVE', 'REGULARIZATION', 'WFH', 'PARTIAL_DAY', 'OVERTIME'];

export default function MyRequestsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<MyRequestItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
  const [viewing, setViewing] = useState<MyRequestItem | null>(null);

  useEffect(() => {
    myRequestsApi.list(token)
      .then(setItems)
      .finally(() => setLoading(false));
  }, [token]);

  const filtered = typeFilter === 'ALL' ? items : items.filter(i => i.requestType === typeFilter);

  const counts: Record<string, number> = { ALL: items.length };
  ALL_TYPES.forEach(t => { counts[t] = items.filter(i => i.requestType === t).length; });

  return (
    <div>
      <div style={{ marginBottom: 18 }}>
        <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>My Requests</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>All your submitted leave and attendance requests in one place.</p>
      </div>

      {/* Type filter bar */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        {(['ALL', ...ALL_TYPES] as const).map(t => (
          <button key={t} onClick={() => setTypeFilter(t)} style={{
            padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
            cursor: 'pointer', border: 'none',
            background: typeFilter === t ? 'var(--brand)' : 'var(--raised)',
            color: typeFilter === t ? '#fff' : 'var(--txt-mut)',
          }}>
            {t === 'ALL' ? 'All' : TYPE_LABELS[t]} {counts[t] > 0 && <span style={{ marginLeft: 4, background: 'rgba(255,255,255,.2)', borderRadius: 10, padding: '0 5px' }}>{counts[t]}</span>}
          </button>
        ))}
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>
              {items.length === 0 ? "You haven't submitted any requests yet." : 'No requests of this type.'}
            </div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {typeFilter === 'ALL' ? 'Requests you submit for leave and attendance will show up here.' : `No ${TYPE_LABELS[typeFilter]} requests.`}
            </div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Summary', 'Status', 'Submitted'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => (
                  <tr key={`${item.requestType}:${item.id}`} style={{ cursor: 'pointer' }} onClick={() => setViewing(item)}>
                    <td style={tdStyle}><TypeBadge type={item.requestType} /></td>
                    <td style={tdStyle}><ItemDetail item={item} /></td>
                    <td style={tdStyle}><StatusBadge status={item.status} /></td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDate(item.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {viewing && (
        <RequestDetailModal item={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}
