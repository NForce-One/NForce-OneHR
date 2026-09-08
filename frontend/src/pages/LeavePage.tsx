import { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarPlus, X } from 'lucide-react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { useToast } from '../context/ToastContext';
import { PieHoverTooltip } from '../components/PieHoverTooltip';
import { subscribeToNewNotifications } from '../lib/notificationEvents';

// Notification types that mean "this employee's own leave balance/status may have changed" —
// mirrors the backend's LeaveService notification events (LEAVE_APPROVED/LEAVE_REJECTED). Every
// other type (asset, regularization, helpdesk, document, ...) is deliberately ignored here.
const LEAVE_DECISION_NOTIFICATION_TYPES = new Set(['LEAVE_APPROVED', 'LEAVE_REJECTED']);

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
// color: var(--txt-mut), not var(--txt-dim) — higher-contrast in both themes (see index.css's
// token table), so headers read as clearly bold/prominent rather than washed out. Same weight/
// size/uppercase/letter-spacing convention used app-wide (AttendancePage/ApprovalsPage/etc).
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

// Primary add-action button for this page — "Request Leave".
const primaryButtonStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

// Mirrors LeaveService#isAnnualBalanceLeaveType — Annual/Sick/Casual are independently
// selectable in the dropdown below, but the balance API returns only ONE row (Annual) for the
// whole group, so a Sick/Casual selection must still resolve to that same row.
const ANNUAL_BALANCE_GROUP_CODES = new Set(['ANNUAL', 'SICK', 'CASUAL']);
function isAnnualBalanceLeaveType(code: string): boolean {
  return ANNUAL_BALANCE_GROUP_CODES.has(code);
}

const STATUS_COLOR: Record<string, string> = { PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D' };

function StatusBadge({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

// Same recharts primitives as DashboardPage's LeaveBalancePanel donut (a PieChart + an
// absolutely-positioned center-label overlay), applied to a single balance: two slices
// (Available vs Consumed/Reserved) summing to that leave type's annual quota. The backend
// (LeaveService#availableBalance) is the sole source of truth for both numbers — this component
// only visualizes remainingDays/totalDays as returned by GET /api/leave/balances; it never
// recomputes or re-derives the balance itself.
//
// Dark/light brand-red pair (not the green/amber pair used elsewhere) so the chart reads as
// professional and on-brand: Available gets the darker, more prominent shade since it's the
// actionable number; Consumed/Reserved gets the lighter tint since it's already spent. The same
// two colors double as the swatches in the Available/Consumed line above the chart, so that line
// also serves as the chart's legend.
const BALANCE_DONUT_COLORS = { available: '#7A0C10', consumed: '#E8B4B6' };

function LeaveBalanceDonut({ balance }: { balance: LeaveBalance }) {
  const total = Number(balance.totalDays);
  const available = Math.max(0, Number(balance.remainingDays));
  const consumed = Math.max(0, total - available);
  const data = [
    { name: 'Available', value: available },
    { name: 'Consumed/Reserved', value: consumed },
  ];
  const isEmptyQuota = total <= 0;
  const donutRef = useRef<HTMLDivElement>(null);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', justifyContent: 'center', boxSizing: 'border-box' }}>
      {/* Content is capped/centered, not stretched — the outer card fills its grid track (so the
          section uses the page's available width instead of leaving a blank gap), but the
          heading/legend/chart/quota stack stays compact instead of sprawling on wide screens. */}
      <div style={{ width: '100%', maxWidth: 260 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', textAlign: 'center', marginBottom: 10 }}>
          {balance.leaveTypeName}
        </div>

        {/* Available/Consumed — sits above the chart and doubles as its legend (color swatches
            match the Cell fills below), per the requested Available/Consumed-then-chart order. */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flexWrap: 'wrap', columnGap: 16, rowGap: 4, marginBottom: 10, fontSize: 12, color: 'var(--txt-mut)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap' }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: BALANCE_DONUT_COLORS.available, flexShrink: 0 }} />
            Available: <b style={{ color: 'var(--txt)', fontWeight: 700 }}>{available}</b> day{available === 1 ? '' : 's'}
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap' }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: BALANCE_DONUT_COLORS.consumed, flexShrink: 0 }} />
            Consumed: <b style={{ color: 'var(--txt)', fontWeight: 700 }}>{consumed}</b> day{consumed === 1 ? '' : 's'}
          </span>
        </div>

        {/* aspect-ratio + ResponsiveContainer (percentage cx/cy/radii), not a fixed pixel
            PieChart — scales with the card instead of relying on a small fixed size, and the 8%
            margin between outerRadius and the container edge means the ring is never clipped. */}
        <div style={{ position: 'relative', width: '100%', maxWidth: 150, aspectRatio: '1 / 1', margin: '0 auto' }} ref={donutRef}>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={isEmptyQuota ? [{ name: 'No quota', value: 1 }] : data}
                cx="50%"
                cy="50%"
                innerRadius="58%"
                outerRadius="92%"
                dataKey="value"
                startAngle={90}
                endAngle={-270}
                strokeWidth={0}
              >
                {isEmptyQuota
                  ? <Cell fill="var(--line2)" />
                  : data.map((d, i) => (
                      <Cell key={d.name} fill={i === 0 ? BALANCE_DONUT_COLORS.available : BALANCE_DONUT_COLORS.consumed} />
                    ))}
              </Pie>
              {!isEmptyQuota && (
                <Tooltip
                  /* See DashboardPage's LeaveBalancePanel donut — same left/right-aware custom
                     content, needed because Recharts' own positioning always offsets to the right. */
                  content={props => (
                    <PieHoverTooltip
                      {...props}
                      containerRef={donutRef}
                      formatter={(val, name) => [`${val} day${val === 1 ? '' : 's'}`, name]}
                    />
                  )}
                  allowEscapeViewBox={{ x: true, y: true }}
                />
              )}
            </PieChart>
          </ResponsiveContainer>
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
            <span style={{ fontSize: 20, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)', lineHeight: 1 }}>{available}</span>
          </div>
        </div>

        {/* Annual Quota — below the chart, set off by a divider + brand-colored value so it reads
            as distinct from the Available/Consumed legend above while matching the page's palette. */}
        <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px solid var(--line)', textAlign: 'center', fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>
          Annual Quota: <span style={{ color: 'var(--brand)', fontWeight: 700 }}>{total}</span> day{total === 1 ? '' : 's'}
        </div>
      </div>
    </div>
  );
}

function RequestLeaveModal({ types, balances, onClose, onCreated, token }: { types: LeaveType[]; balances: LeaveBalance[]; onClose: () => void; onCreated: (r: LeaveRequestRecord) => void; token: string }) {
  const { showToast } = useToast();
  // Local calendar date, not new Date().toISOString().slice(0, 10) — the ISO/UTC form can land
  // on the wrong side of midnight relative to the user's actual local day, which would let the
  // date picker's min slip a day off from what "today" really is.
  const now = new Date();
  const today = toISODate(now.getFullYear(), now.getMonth(), now.getDate());
  const [form, setForm] = useState<SubmitLeaveRequestPayload>({ leaveTypeCode: types[0]?.code ?? '', startDate: today, endDate: today, halfDay: false, reason: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Early-UX only, mirroring the backend's own day-count formula (LeaveService#submitRequest) —
  // the backend independently re-validates against the same status-aware balance regardless of
  // what's computed here, so this can never be relied on to enforce the limit by itself.
  const selectedBalance = balances.find(b =>
    b.leaveTypeCode === form.leaveTypeCode
    || (isAnnualBalanceLeaveType(form.leaveTypeCode) && isAnnualBalanceLeaveType(b.leaveTypeCode)));
  const effectiveEndDate = form.halfDay ? form.startDate : form.endDate;
  const requestedDays = form.halfDay
    ? 0.5
    : (new Date(effectiveEndDate).getTime() - new Date(form.startDate).getTime()) / 86400000 + 1;
  const exceedsBalance = !!selectedBalance && Number.isFinite(requestedDays) && requestedDays > selectedBalance.remainingDays;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.reason.trim()) { setError('A reason is required.'); return; }
    // Early-UX only, same as the balance check below — the backend independently re-validates
    // this against its own (timezone-correct) notion of "today" regardless of what's checked here.
    if (form.startDate < today) { setError('Leave cannot be requested for a date before today.'); return; }
    if (exceedsBalance && selectedBalance) {
      setError(`Leave request exceeds your available ${selectedBalance.leaveTypeName} balance of ${selectedBalance.remainingDays} days.`);
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const created = await leaveApi.submit({ ...form, endDate: form.halfDay ? form.startDate : form.endDate }, token);
      onCreated(created);
      showToast('success', 'Leave request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submit failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Request Leave" onClose={onClose} />
        <form onSubmit={handleSubmit} className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Leave Type *">
              <select style={inputStyle} value={form.leaveTypeCode} onChange={e => setForm(f => ({ ...f, leaveTypeCode: e.target.value }))}>
                {types.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
              </select>
            </Field>
            {selectedBalance && (
              <div style={{ fontSize: 11.5, color: exceedsBalance ? 'var(--risk)' : 'var(--txt-dim)', marginTop: 5 }}>
                Available: {selectedBalance.remainingDays} day{selectedBalance.remainingDays === 1 ? '' : 's'}
                {exceedsBalance && ' — this request exceeds your available balance'}
              </div>
            )}
          </div>
          <Field label="Start Date *">
            <input type="date" min={today} style={inputStyle} value={form.startDate} onChange={e => setForm(f => ({ ...f, startDate: e.target.value, endDate: f.halfDay ? e.target.value : f.endDate }))} />
          </Field>
          <Field label="End Date *">
            <input type="date" style={inputStyle} value={form.halfDay ? form.startDate : form.endDate} disabled={form.halfDay} min={form.startDate} onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))} />
          </Field>
          <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'center', gap: 8 }}>
            <input id="halfDay" type="checkbox" checked={form.halfDay} onChange={e => setForm(f => ({ ...f, halfDay: e.target.checked, endDate: e.target.checked ? f.startDate : f.endDate }))} />
            <label htmlFor="halfDay" style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Half day</label>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Reason *">
              <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="Reason for leave" />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || exceedsBalance} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// r.decidedAt is an ISO instant (see LeaveRequestResponse#decidedAt) — formatted for the
// "Approved/Rejected By" column, e.g. "18 Aug 2026, 10:30 AM".
function formatDecisionTimestamp(iso: string) {
  return new Date(iso).toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true });
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export default function LeavePage() {
  const token = useAuthStore(s => s.token)!;
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [leaveError, setLeaveError] = useState('');
  const [showRequest, setShowRequest] = useState(false);

  useEffect(() => {
    setLeaveError('');
    Promise.all([leaveApi.listTypes(token), leaveApi.listBalances(token), leaveApi.listMine(token)])
      .then(([t, b, r]) => { setTypes(t); setBalances(b); setRequests(r); })
      .catch(e => setLeaveError(e instanceof Error ? e.message : 'Failed to load leave data'))
      .finally(() => setLoading(false));
  }, [token]);

  // Single source of truth for "re-fetch balances + requests without disturbing anything else
  // on the page" — used both after the employee's own submission and after a
  // LEAVE_APPROVED/LEAVE_REJECTED notification arrives for this employee (see the effect below).
  // Deliberately never touches `loading` (no full-page skeleton flash) or any holiday/filter/
  // pagination state. Overlap-safe: a call that arrives while one is already in flight is
  // coalesced into a single trailing re-run instead of firing a second concurrent request, so a
  // submit-triggered refresh and a notification-triggered refresh landing close together can
  // never race each other or the UI backwards with stale data.
  const refreshInFlightRef = useRef(false);
  const refreshQueuedRef = useRef(false);
  const refreshLeaveData = useCallback(async () => {
    if (refreshInFlightRef.current) { refreshQueuedRef.current = true; return; }
    refreshInFlightRef.current = true;
    try {
      const [freshBalances, freshRequests] = await Promise.all([leaveApi.listBalances(token), leaveApi.listMine(token)]);
      setBalances(freshBalances);
      setRequests(freshRequests);
    } catch (e) {
      setLeaveError(e instanceof Error ? e.message : 'Failed to refresh leave data');
    } finally {
      refreshInFlightRef.current = false;
      if (refreshQueuedRef.current) {
        refreshQueuedRef.current = false;
        refreshLeaveData();
      }
    }
  }, [token]);

  // React to this employee's own leave decisions as the app-wide notification poll (Shell)
  // detects them — no separate polling loop here, and no browser refresh needed. Notifications
  // for other employees never reach this listener: the backend's /api/notifications endpoints
  // are scoped to the authenticated caller (see NotificationController#resolveUserId), so every
  // item Shell publishes already belongs to this signed-in user. Unrelated notification types
  // (asset/regularization/helpdesk/document/...) are filtered out and never trigger a refresh.
  // A batch containing several LEAVE_APPROVED/REJECTED items (e.g. two requests decided within
  // the same 30s poll window) still triggers exactly one refreshLeaveData() call, not one per
  // item. Unsubscribes on unmount so remounting this page never accumulates listeners.
  useEffect(() => {
    return subscribeToNewNotifications(items => {
      if (items.some(n => LEAVE_DECISION_NOTIFICATION_TYPES.has(n.type))) {
        refreshLeaveData();
      }
    });
  }, [refreshLeaveData]);

  // A newly-submitted PENDING request is immediately reserved against the balance (see
  // LeaveService#availableBalance — it subtracts PENDING days, not just APPROVED usedDays), so
  // the pie chart must be refreshed right away instead of waiting for the next full page load.
  // The optimistic prepend shows the new request instantly; refreshLeaveData then reconciles
  // both the requests list and the balances against the server (and safely coalesces with any
  // notification-triggered refresh that happens to land around the same time — see its comment).
  async function handleCreated(r: LeaveRequestRecord) {
    setRequests(prev => [r, ...prev]);
    await refreshLeaveData();
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12, marginBottom: 22 }}>
        <div>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>View your balance, request leave, and track approvals.</p>
        </div>
        <button onClick={() => setShowRequest(true)} disabled={types.length === 0} style={{ ...primaryButtonStyle, cursor: types.length === 0 ? 'not-allowed' : 'pointer', opacity: types.length === 0 ? 0.6 : 1 }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {leaveError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {leaveError}
        </div>
      )}

      {!loading && (
        // auto-fit + 1fr (same responsive-card-row convention as AuditStatCards) — a single
        // balance card fills the row instead of leaving a blank gap beside it, and any future
        // additional balance types would wrap into an even multi-column row instead of overflowing.
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12, marginBottom: 22 }}>
          {balances.map(b => <LeaveBalanceDonut key={b.leaveTypeCode} balance={b} />)}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : requests.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Dates', 'Days', 'Status', 'Reason', 'Approved/Rejected By'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {requests.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
                    <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                    <td style={tdStyle}>{r.totalDays}</td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}>
                      <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.employeeReason}>{r.employeeReason}</div>
                    </td>
                    <td style={{ ...tdStyle, maxWidth: 240 }}>
                      {r.status === 'PENDING' ? (
                        <span style={{ color: 'var(--txt-dim)' }}>Awaiting decision</span>
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <span style={{ fontSize: 10.5, fontWeight: 700, color: r.status === 'APPROVED' ? 'var(--ok)' : 'var(--risk)', textTransform: 'uppercase', letterSpacing: '.04em' }}>
                            {r.status === 'APPROVED' ? 'Approved by' : 'Rejected by'}
                          </span>
                          <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.decidedByName}</span>
                          {r.decidedAt && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{formatDecisionTimestamp(r.decidedAt)}</span>}
                          {r.decisionReason && (
                            <span
                              style={{ fontSize: 12, color: 'var(--txt-mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220, display: 'block' }}
                              title={r.decisionReason}
                            >
                              Comment: {r.decisionReason}
                            </span>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showRequest && (
        <RequestLeaveModal types={types} balances={balances} token={token} onClose={() => setShowRequest(false)} onCreated={handleCreated} />
      )}
    </div>
  );
}
