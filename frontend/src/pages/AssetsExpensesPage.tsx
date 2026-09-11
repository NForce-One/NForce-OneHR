import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, ExternalLink, Paperclip, Plus, X } from 'lucide-react';
import { KebabMenu } from '../components/KebabMenu';
import { useAuthStore } from '../store/authStore';
import { toShellRole } from '../lib/nav.config';
import { useToast } from '../context/ToastContext';
import {
  assetsApi,
  type AssetAssignmentResponse,
  type AssetCategory,
  type AssetRequestResponse,
  type AssetResponse,
  type AssetTileEmployee,
  type AssetTileHR,
  type AssetTileManager,
} from '../api/assets';
import {
  expensesApi,
  type ExpenseCategory,
  type ExpenseClaimResponse,
  type ExpenseTileEmployee,
  type ExpenseTileHR,
  type ExpenseTileManager,
} from '../api/expenses';
import { employeesApi } from '../api/employees';

// ── Shared styles ─────────────────────────────────────────

const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };

function fmtCurrency(n?: number | null) {
  if (n == null) return '—';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);
}

function fmtDate(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function todayIsoDate(): string {
  const d = new Date();
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function isExpenseDateInFuture(dateStr: string, todayStr: string = todayIsoDate()): boolean {
  if (!dateStr) return false;
  return dateStr > todayStr;
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { bg: string; color: string }> = {
    SUBMITTED: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    MANAGER_APPROVED: { bg: 'rgba(99,102,241,.15)', color: '#818CF8' },
    CLEARED_FOR_PAYROLL: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    PAID: { bg: 'rgba(47,182,124,.15)', color: '#2FB67C' },
    MANAGER_REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    FINAL_REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    PENDING: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    APPROVED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    AVAILABLE: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    ASSIGNED: { bg: 'rgba(99,102,241,.15)', color: '#818CF8' },
    IN_REPAIR: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    RETIRED: { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' },
    FULFILLED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
  };
  const style = map[status] ?? { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' };
  return <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: style.bg, color: style.color }}>{status.replace(/_/g, ' ')}</span>;
}

interface TileProps {
  label: string;
  value: string | number;
  sub?: string;
  clickable?: boolean;
  onClick?: () => void;
  clickHint?: string;
}
function Tile({ label, value, sub, clickable, onClick, clickHint }: TileProps) {
  return (
    <div
      onClick={clickable ? onClick : undefined}
      style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px',
        cursor: clickable ? 'pointer' : 'default',
        transition: clickable ? 'border-color .15s' : undefined,
        minWidth: 0,
      }}
      onMouseEnter={clickable && onClick ? e => (e.currentTarget.style.borderColor = 'var(--brand)') : undefined}
      onMouseLeave={clickable && onClick ? e => (e.currentTarget.style.borderColor = 'var(--line)') : undefined}
    >
      <div style={{ fontSize: 24, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{value}</div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3, fontWeight: 600 }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>{sub}</div>}
      {clickable && onClick && clickHint && <div style={{ fontSize: 10, color: 'var(--brand)', marginTop: 6, fontWeight: 600 }}>{clickHint}</div>}
    </div>
  );
}

function SectionHead({ title, action }: { title: string; action?: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
      <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{title}</h2>
      {action}
    </div>
  );
}

function EmptyRow({ cols, msg }: { cols: number; msg: string }) {
  return (
    <tr>
      <td colSpan={cols} style={{ padding: '36px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>{msg}</td>
    </tr>
  );
}

// ── Modal wrapper ─────────────────────────────────────────

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: 20 }}>{children}</div>
      </div>
    </div>
  );
}

function FormRow({ children }: { children: React.ReactNode }) {
  return <div style={{ marginBottom: 14 }}>{children}</div>;
}

function BtnPrimary({ children, onClick, disabled, type = 'button' }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit' }) {
  return (
    <button type={type} onClick={onClick} disabled={disabled} style={{ background: disabled ? 'var(--raised)' : 'var(--brand)', color: disabled ? 'var(--txt-dim)' : '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}

function BtnGhost({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return (
    <button onClick={onClick} style={{ background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>
      {children}
    </button>
  );
}

// ── File → base64 helper ──────────────────────────────────

function fileToBase64(file: File): Promise<string> {
  return new Promise((res, rej) => {
    const reader = new FileReader();
    reader.onload = () => res(reader.result as string);
    reader.onerror = rej;
    reader.readAsDataURL(file);
  });
}

// ══════════════════════════════════════════════════════════
// EMPLOYEE VIEW
// ══════════════════════════════════════════════════════════

function EmployeeView({ token }: { token: string }) {
  const { showToast } = useToast();
  const [tiles, setTiles] = useState<AssetTileEmployee | null>(null);
  const [expTiles, setExpTiles] = useState<ExpenseTileEmployee | null>(null);
  const [assignments, setAssignments] = useState<AssetAssignmentResponse[]>([]);
  const [requests, setRequests] = useState<AssetRequestResponse[]>([]);
  const [claims, setClaims] = useState<ExpenseClaimResponse[]>([]);
  const [categories, setCategories] = useState<AssetCategory[]>([]);
  const [expCategories, setExpCategories] = useState<ExpenseCategory[]>([]);
  const [showRequestModal, setShowRequestModal] = useState(false);
  const [showExpModal, setShowExpModal] = useState(false);
  const [activeTab, setActiveTab] = useState<'assets' | 'requests' | 'claims'>('assets');
  const [ackConfirmId, setAckConfirmId] = useState<number | null>(null);
  const [assetCatFilter, setAssetCatFilter] = useState('');
  const [reqStatusFilter, setReqStatusFilter] = useState('');
  const [claimStatusFilter, setClaimStatusFilter] = useState('');
  const [claimCatFilter, setClaimCatFilter] = useState('');

  function reload() {
    assetsApi.employeeTiles(token).then(setTiles).catch(() => {});
    expensesApi.employeeTiles(token).then(setExpTiles).catch(() => {});
    assetsApi.myAssignments(token).then(setAssignments).catch(() => {});
    assetsApi.myRequests(token).then(setRequests).catch(() => {});
    expensesApi.myClaims(token).then(setClaims).catch(() => {});
  }

  useEffect(() => {
    reload();
    assetsApi.categories(token).then(setCategories).catch(() => {});
    expensesApi.categories(token).then(setExpCategories).catch(() => {});
  }, [token]);

  async function handleAcknowledge(id: number) {
    try {
      const updated = await assetsApi.acknowledge(id, token);
      setAssignments(prev => prev.map(a => a.id === updated.id ? updated : a));
      assetsApi.employeeTiles(token).then(setTiles).catch(() => {});
      showToast('success', 'Receipt acknowledged');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    }
    setAckConfirmId(null);
  }

  const ackTarget = ackConfirmId != null ? assignments.find(a => a.id === ackConfirmId) : null;

  const filteredAssignments = assetCatFilter
    ? assignments.filter(a => a.categoryName === assetCatFilter)
    : assignments;
  const filteredRequests = reqStatusFilter
    ? requests.filter(r => r.status === reqStatusFilter)
    : requests;
  const filteredClaims = claims
    .filter(c => !claimStatusFilter || c.status === claimStatusFilter)
    .filter(c => !claimCatFilter || String(c.categoryId) === claimCatFilter);

  const assetCategoryOptions = Array.from(new Set(assignments.map(a => a.categoryName).filter(Boolean))) as string[];
  const requestStatusOptions = Array.from(new Set(requests.map(r => r.status)));
  const claimStatusOptions = Array.from(new Set(claims.map(c => c.status)));

  const tabDef = [
    { key: 'assets' as const, label: 'My Assets', count: assignments.length },
    { key: 'requests' as const, label: 'My Asset Requests', count: requests.length },
    { key: 'claims' as const, label: 'My Expense Claims', count: claims.length },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Tiles */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <Tile label="My Assigned Assets" value={tiles?.assignedCount ?? '—'} sub={tiles?.assignedSubtitle} clickable onClick={() => setActiveTab('assets')} />
        <Tile label="Open Expense Claims" value={expTiles?.openClaimCount ?? '—'} sub={expTiles?.openClaimCount ? fmtCurrency(expTiles.openClaimAmount) : undefined} clickable onClick={() => setActiveTab('claims')} />
        <Tile label="Approved This Month" value={expTiles ? fmtCurrency(expTiles.approvedThisMonthAmount) : '—'} clickable onClick={() => setActiveTab('claims')} />
        <Tile label="My Asset Requests" value={tiles?.openRequestCount ?? '—'} clickable onClick={() => setActiveTab('requests')} />
      </div>

      {/* Tab bar */}
      <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', paddingBottom: 0 }}>
        {tabDef.map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
            background: 'none', border: 'none', borderBottom: activeTab === t.key ? '2px solid var(--brand)' : '2px solid transparent',
            padding: '8px 16px', fontSize: 13, fontWeight: activeTab === t.key ? 700 : 500,
            color: activeTab === t.key ? 'var(--brand)' : 'var(--txt-mut)', cursor: 'pointer', marginBottom: -1,
          }}>
            {t.label}
            {t.count > 0 && <span style={{ marginLeft: 6, fontSize: 11, fontWeight: 700, background: activeTab === t.key ? 'var(--brand)' : 'var(--raised)', color: activeTab === t.key ? '#fff' : 'var(--txt-dim)', borderRadius: 10, padding: '1px 6px' }}>{t.count}</span>}
          </button>
        ))}
      </div>

      {/* My Assets tab */}
      {activeTab === 'assets' && (
        <div>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <select value={assetCatFilter} onChange={e => setAssetCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Categories</option>
              {assetCategoryOptions.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div style={panelStyle}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Asset Tag', 'Category', 'Brand / Model', 'Assigned', 'Condition', 'Acknowledged', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {filteredAssignments.length === 0 ? <EmptyRow cols={7} msg="No assets assigned." /> : filteredAssignments.map(a => (
                    <tr key={a.id}>
                      <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{a.assetTag ?? '—'}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{a.categoryName ?? '—'}</td>
                      <td style={tdStyle}>{[a.brand, a.model].filter(Boolean).join(' ') || '—'}</td>
                      <td style={tdStyle}>{fmtDate(a.effectiveFrom)}</td>
                      <td style={tdStyle}>
                        {a.condition ? (
                          <div>
                            <StatusBadge status={a.condition} />
                            <div style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 2 }}>Assessed by HR/Admin</div>
                          </div>
                        ) : '—'}
                      </td>
                      <td style={tdStyle}>{a.acknowledgedAt ? <span style={{ color: '#10B981', fontWeight: 600, fontSize: 12 }}><Check size={12} style={{ display: 'inline', verticalAlign: 'middle', marginRight: 3 }} />Yes</span> : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Pending</span>}</td>
                      <td style={{ ...tdStyle, padding: '8px 12px' }}>
                        {!a.acknowledgedAt && (
                          <button onClick={() => setAckConfirmId(a.id)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}>
                            Acknowledge Receipt
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* My Asset Requests tab */}
      {activeTab === 'requests' && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
            <select value={reqStatusFilter} onChange={e => setReqStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Statuses</option>
              {requestStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>
            <button onClick={() => setShowRequestModal(true)} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}><Plus size={13} /> Request Asset</button>
          </div>
          <div style={panelStyle}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Category', 'Reason', 'Status', 'Submitted', 'Manager Decision', 'Fulfilled By'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {filteredRequests.length === 0 ? <EmptyRow cols={6} msg="No asset requests yet." /> : filteredRequests.map(r => (
                    <tr key={r.id}>
                      <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{r.categoryName}</td>
                      <td style={{ ...tdStyle, maxWidth: 200 }}><div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }} title={r.reason}>{r.reason}</div></td>
                      <td style={tdStyle}>
                        <StatusBadge status={r.status} />
                        {r.rejectionReason && <div style={{ fontSize: 10, color: '#E4373D', marginTop: 2 }}>Reason: {r.rejectionReason}</div>}
                      </td>
                      <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                      <td style={tdStyle}>
                        {r.managerDecidedByName
                          ? <div><div style={{ color: 'var(--txt)', fontSize: 12, fontWeight: 600 }}>{r.managerDecidedByName}</div><div style={{ color: 'var(--txt-dim)', fontSize: 10 }}>{fmtDate(r.managerDecidedAt)}</div></div>
                          : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Pending</span>}
                      </td>
                      <td style={tdStyle}>
                        {r.fulfilledByName
                          ? <div><div style={{ color: 'var(--txt)', fontSize: 12, fontWeight: 600 }}>{r.fulfilledByName}</div><div style={{ color: 'var(--txt-dim)', fontSize: 10 }}>{fmtDate(r.fulfilledAt)}</div></div>
                          : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>{r.status === 'APPROVED' ? 'Awaiting HR' : '—'}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* My Expense Claims tab */}
      {activeTab === 'claims' && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between', flexWrap: 'wrap', marginBottom: 10 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <select value={claimStatusFilter} onChange={e => setClaimStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
                <option value="">All Statuses</option>
                {claimStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
              </select>
              <select value={claimCatFilter} onChange={e => setClaimCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
                <option value="">All Categories</option>
                {expCategories.map(c => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
              </select>
            </div>
            <button onClick={() => setShowExpModal(true)} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}><Plus size={13} /> Submit Expense</button>
          </div>
          <div style={panelStyle}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Category', 'Amount', 'Expense Date', 'Purpose', 'Status', 'Submitted'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {filteredClaims.length === 0 ? <EmptyRow cols={6} msg="No expense claims yet." /> : filteredClaims.map(c => (
                    <tr key={c.id}>
                      <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{c.categoryName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{fmtCurrency(c.amount)}</td>
                      <td style={tdStyle}>{fmtDate(c.expenseDate)}</td>
                      <td style={{ ...tdStyle, maxWidth: 180 }}><div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }} title={c.businessPurpose}>{c.businessPurpose}</div></td>
                      <td style={tdStyle}>
                        <StatusBadge status={c.status} />
                        {(c.managerRejectionReason || c.finalRejectionReason) && (
                          <div style={{ fontSize: 10, color: '#E4373D', marginTop: 2 }}>Reason: {c.managerRejectionReason ?? c.finalRejectionReason}</div>
                        )}
                      </td>
                      <td style={tdStyle}>{fmtDate(c.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Acknowledge confirmation modal */}
      {ackTarget && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 440 }}>
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)', fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>Acknowledge Asset Receipt</div>
            <div style={{ padding: 20 }}>
              <div style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '14px 16px', marginBottom: 16 }}>
                <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 20px' }}>
                  <div>
                    <div style={{ ...labelStyle, marginBottom: 2 }}>Asset Tag</div>
                    <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{ackTarget.assetTag ?? '—'}</div>
                  </div>
                  <div>
                    <div style={{ ...labelStyle, marginBottom: 2 }}>Category</div>
                    <div style={{ fontSize: 13, color: 'var(--txt)' }}>{ackTarget.categoryName ?? '—'}</div>
                  </div>
                  <div>
                    <div style={{ ...labelStyle, marginBottom: 2 }}>Brand / Model</div>
                    <div style={{ fontSize: 13, color: 'var(--txt)' }}>{[ackTarget.brand, ackTarget.model].filter(Boolean).join(' ') || '—'}</div>
                  </div>
                  <div>
                    <div style={{ ...labelStyle, marginBottom: 2 }}>Condition</div>
                    <div>{ackTarget.condition ? <StatusBadge status={ackTarget.condition} /> : '—'}</div>
                    <div style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 3 }}>Assessed by HR/Admin</div>
                  </div>
                  <div style={{ gridColumn: 'span 2' }}>
                    <div style={{ ...labelStyle, marginBottom: 2 }}>Assigned Date</div>
                    <div style={{ fontSize: 13, color: 'var(--txt)' }}>{fmtDate(ackTarget.effectiveFrom)}</div>
                  </div>
                </div>
              </div>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 16 }}>
                By confirming, you acknowledge receiving this asset in the condition stated above.
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <BtnGhost onClick={() => setAckConfirmId(null)}>Cancel</BtnGhost>
                <BtnPrimary onClick={() => handleAcknowledge(ackTarget.id)}>Confirm Receipt</BtnPrimary>
              </div>
            </div>
          </div>
        </div>
      )}

      {showRequestModal && (
        <RequestAssetModal
          categories={categories}
          token={token}
          onClose={() => setShowRequestModal(false)}
          onCreated={r => { setRequests(prev => [r, ...prev]); assetsApi.employeeTiles(token).then(setTiles).catch(() => {}); showToast('success', 'Asset request submitted — pending manager approval'); }}
        />
      )}
      {showExpModal && (
        <SubmitExpenseModal
          categories={expCategories}
          token={token}
          onClose={() => setShowExpModal(false)}
          onCreated={c => { setClaims(prev => [c, ...prev]); expensesApi.employeeTiles(token).then(setExpTiles).catch(() => {}); showToast('success', 'Expense claim submitted'); }}
        />
      )}
    </div>
  );
}

// ── Request Asset Modal ───────────────────────────────────

function RequestAssetModal({ categories: propCategories, token, onClose, onCreated }: { categories: AssetCategory[]; token: string; onClose: () => void; onCreated: (r: AssetRequestResponse) => void }) {
  const [categoryId, setCategoryId] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [localCats, setLocalCats] = useState<AssetCategory[]>([]);
  const { showToast } = useToast();

  // Self-fetch as fallback when parent state hasn't propagated yet
  useEffect(() => {
    if (propCategories.length === 0) {
      assetsApi.categories(token).then(setLocalCats).catch(() => {});
    }
  }, []);

  const categories = propCategories.length > 0 ? propCategories : localCats;

  async function submit() {
    if (!categoryId || !reason.trim()) return;
    setSubmitting(true);
    try {
      const result = await assetsApi.submitRequest({ categoryId: Number(categoryId), reason: reason.trim() }, token);
      onCreated(result);
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Submit failed');
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Request Asset" onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Category *</label>
        <select value={categoryId} onChange={e => setCategoryId(e.target.value)} style={inputStyle}>
          <option value="">Select category…</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} placeholder="Why do you need this asset?" />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!categoryId || !reason.trim() || submitting}>{submitting ? 'Submitting…' : 'Submit Request'}</BtnPrimary>
      </div>
    </Modal>
  );
}

// ── Submit Expense Modal ──────────────────────────────────

function SubmitExpenseModal({ categories: propCategories, token, onClose, onCreated }: { categories: ExpenseCategory[]; token: string; onClose: () => void; onCreated: (c: ExpenseClaimResponse) => void }) {
  const [categoryId, setCategoryId] = useState('');
  const [amount, setAmount] = useState('');
  const [expenseDate, setExpenseDate] = useState('');
  const [businessPurpose, setBusinessPurpose] = useState('');
  const [receiptFile, setReceiptFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [localCats, setLocalCats] = useState<ExpenseCategory[]>([]);
  const { showToast } = useToast();

  // Self-fetch as fallback when parent state hasn't propagated yet
  useEffect(() => {
    if (propCategories.length === 0) {
      expensesApi.categories(token).then(setLocalCats).catch(() => {});
    }
  }, []);

  const categories = propCategories.length > 0 ? propCategories : localCats;
  const selectedCat = categories.find(c => c.id === Number(categoryId));
  const amountNum = parseFloat(amount) || 0;
  const receiptRequired = selectedCat != null && amountNum > selectedCat.requiresReceiptAbove;
  const maxDate = todayIsoDate();
  const isFutureDate = isExpenseDateInFuture(expenseDate, maxDate);

  async function submit() {
    if (!categoryId || !amount || !expenseDate || !businessPurpose.trim()) return;
    if (isFutureDate) {
      showToast('error', 'Expense date cannot be in the future');
      return;
    }
    if (receiptRequired && !receiptFile) { showToast('error', `Receipt required for ${selectedCat!.name} amounts above ${fmtCurrency(selectedCat!.requiresReceiptAbove)}`); return; }
    setSubmitting(true);
    try {
      let receiptUrl: string | null = null;
      if (receiptFile) receiptUrl = await fileToBase64(receiptFile);
      const result = await expensesApi.submit({ categoryId: Number(categoryId), amount: amountNum, expenseDate, businessPurpose: businessPurpose.trim(), receiptUrl }, token);
      onCreated(result);
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Submit failed');
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Submit Expense Claim" onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Category *</label>
        <select value={categoryId} onChange={e => setCategoryId(e.target.value)} style={inputStyle}>
          <option value="">Select category…</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}{c.dailyLimit ? ` (limit: ${fmtCurrency(c.dailyLimit)}/day)` : ''}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Amount (₹) *</label>
        <input type="number" min="0" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} style={inputStyle} placeholder="0.00" />
        {selectedCat && amountNum > 0 && (
          <div style={{ fontSize: 11, marginTop: 4, color: receiptRequired ? '#F59E0B' : 'var(--txt-dim)' }}>
            {receiptRequired ? `⚠ Receipt required (threshold: ${fmtCurrency(selectedCat.requiresReceiptAbove)})` : `No receipt required below ${fmtCurrency(selectedCat.requiresReceiptAbove)}`}
          </div>
        )}
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Expense Date *</label>
        <input
          type="date"
          max={maxDate}
          value={expenseDate}
          onChange={e => setExpenseDate(e.target.value)}
          style={{
            ...inputStyle,
            borderColor: isFutureDate ? '#E4373D' : undefined,
          }}
        />
        {isFutureDate && (
          <div style={{ fontSize: 11, color: '#E4373D', marginTop: 4 }}>
            Expense date cannot be in the future.
          </div>
        )}
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Business Purpose *</label>
        <textarea value={businessPurpose} onChange={e => setBusinessPurpose(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} placeholder="What was this expense for?" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Receipt {receiptRequired ? '(required)' : '(optional)'}</label>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Paperclip size={14} color="var(--txt-dim)" aria-hidden />
          <input type="file" accept="image/*,application/pdf" onChange={e => setReceiptFile(e.target.files?.[0] ?? null)} style={{ fontSize: 12, color: 'var(--txt-mut)' }} />
        </div>
        {receiptFile && <div style={{ fontSize: 11, color: 'var(--ok)', marginTop: 4 }}>{receiptFile.name}</div>}
        {receiptRequired && !receiptFile && <div style={{ fontSize: 11, color: '#E4373D', marginTop: 4 }}>A receipt image is required for this amount.</div>}
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!categoryId || !amount || !expenseDate || isFutureDate || !businessPurpose.trim() || (receiptRequired && !receiptFile) || submitting}>{submitting ? 'Submitting…' : 'Submit Claim'}</BtnPrimary>
      </div>
    </Modal>
  );
}

// ══════════════════════════════════════════════════════════
// MANAGER VIEW (read-only)
// ══════════════════════════════════════════════════════════

function ManagerView({ token }: { token: string }) {
  const navigate = useNavigate();
  const [tiles, setTiles] = useState<AssetTileManager | null>(null);
  const [expTiles, setExpTiles] = useState<ExpenseTileManager | null>(null);
  const [teamAssignments, setTeamAssignments] = useState<AssetAssignmentResponse[]>([]);
  const [teamRequests, setTeamRequests] = useState<AssetRequestResponse[]>([]);
  const [teamClaims, setTeamClaims] = useState<ExpenseClaimResponse[]>([]);
  const [activeTab, setActiveTab] = useState<'assets' | 'requests' | 'claims'>('assets');
  const [assetSearch, setAssetSearch] = useState('');
  const [assetCatFilter, setAssetCatFilter] = useState('');
  const [reqSearch, setReqSearch] = useState('');
  const [reqStatusFilter, setReqStatusFilter] = useState('');
  const [reqCatFilter, setReqCatFilter] = useState('');
  const [claimSearch, setClaimSearch] = useState('');
  const [claimStatusFilter, setClaimStatusFilter] = useState('');
  const [claimCatFilter, setClaimCatFilter] = useState('');

  useEffect(() => {
    assetsApi.managerTiles(token).then(setTiles).catch(() => {});
    expensesApi.managerTiles(token).then(setExpTiles).catch(() => {});
    assetsApi.teamAssignments(token).then(setTeamAssignments).catch(() => {});
    assetsApi.teamRequests(token).then(setTeamRequests).catch(() => {});
    expensesApi.allTeamClaims(token).then(setTeamClaims).catch(() => {});
  }, [token]);

  const filteredAssignments = teamAssignments
    .filter(a => !assetSearch || a.employeeName.toLowerCase().includes(assetSearch.toLowerCase()))
    .filter(a => !assetCatFilter || a.categoryName === assetCatFilter);
  const filteredRequests = teamRequests
    .filter(r => !reqSearch || r.employeeName.toLowerCase().includes(reqSearch.toLowerCase()))
    .filter(r => !reqStatusFilter || r.status === reqStatusFilter)
    .filter(r => !reqCatFilter || String(r.categoryId) === reqCatFilter);
  const filteredClaims = teamClaims
    .filter(c => !claimSearch || c.employeeName.toLowerCase().includes(claimSearch.toLowerCase()))
    .filter(c => !claimStatusFilter || c.status === claimStatusFilter)
    .filter(c => !claimCatFilter || String(c.categoryId) === claimCatFilter);

  const assetCategoryOptions = Array.from(new Set(teamAssignments.map(a => a.categoryName).filter(Boolean))) as string[];
  const reqStatusOptions = Array.from(new Set(teamRequests.map(r => r.status)));
  const reqCategoryOptions = teamRequests.map(r => ({ id: r.categoryId, name: r.categoryName })).filter((v, i, a) => a.findIndex(x => x.id === v.id) === i);
  const claimStatusOptions = Array.from(new Set(teamClaims.map(c => c.status)));
  const claimCategoryOptions = teamClaims.map(c => ({ id: c.categoryId, name: c.categoryName })).filter((v, i, a) => a.findIndex(x => x.id === v.id) === i);

  const managerTabs = [
    { key: 'assets' as const, label: 'Team Assets', count: teamAssignments.length },
    { key: 'requests' as const, label: 'Team Asset Requests', count: teamRequests.length },
    { key: 'claims' as const, label: 'Team Expense Claims', count: teamClaims.length },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Tiles */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <Tile label="Team Assets Assigned" value={tiles?.teamAssignedCount ?? '—'} sub={tiles ? `${tiles.teamMemberCount} team members` : undefined} clickable onClick={() => setActiveTab('assets')} />
        <Tile label="Pending Expense Claims" value={expTiles?.pendingCount ?? '—'} sub={expTiles?.pendingCount ? fmtCurrency(expTiles.pendingAmount) : undefined} clickable={!!expTiles?.pendingCount} onClick={() => navigate('/approvals?type=EXPENSE')} clickHint="Review in Approval Center →" />
        <Tile label="Overdue Returns" value={tiles?.overdueReturnCount ?? '—'} clickable onClick={() => setActiveTab('assets')} />
        <Tile label="Pending Asset Requests" value={tiles?.pendingRequestCount ?? '—'} clickable={!!tiles?.pendingRequestCount} onClick={() => navigate('/approvals?type=ASSET_REQUEST')} clickHint="Review in Approval Center →" />
        <Tile label="Approved This Month" value={expTiles ? fmtCurrency(expTiles.approvedThisMonthAmount) : '—'} clickable onClick={() => setActiveTab('claims')} />
      </div>

      {/* Tab bar */}
      <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', paddingBottom: 0 }}>
        {managerTabs.map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
            background: 'none', border: 'none', borderBottom: activeTab === t.key ? '2px solid var(--brand)' : '2px solid transparent',
            padding: '8px 16px', fontSize: 13, fontWeight: activeTab === t.key ? 700 : 500,
            color: activeTab === t.key ? 'var(--brand)' : 'var(--txt-mut)', cursor: 'pointer', marginBottom: -1,
          }}>
            {t.label}
            {t.count > 0 && <span style={{ marginLeft: 6, fontSize: 11, fontWeight: 700, background: activeTab === t.key ? 'var(--brand)' : 'var(--raised)', color: activeTab === t.key ? '#fff' : 'var(--txt-dim)', borderRadius: 10, padding: '1px 6px' }}>{t.count}</span>}
          </button>
        ))}
        <button onClick={() => navigate('/approvals?type=EXPENSE')} style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: '1px solid var(--brand)', borderRadius: 7, padding: '5px 12px', fontSize: 12, color: 'var(--brand)', cursor: 'pointer', marginBottom: 8 }}>
          <ExternalLink size={12} /> Approval Center
        </button>
      </div>

      {/* Team Assets tab */}
      {activeTab === 'assets' && (
        <div>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <input value={assetSearch} onChange={e => setAssetSearch(e.target.value)} placeholder="Search employee…" style={{ ...inputStyle, width: 180, fontSize: 12, padding: '6px 10px' }} />
            <select value={assetCatFilter} onChange={e => setAssetCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Categories</option>
              {assetCategoryOptions.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
        <div style={panelStyle}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Employee', 'Asset Tag', 'Category', 'Brand / Model', 'Assigned', 'Acknowledged'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {filteredAssignments.length === 0 ? <EmptyRow cols={6} msg="No team assets found." /> : filteredAssignments.map(a => (
                  <tr key={a.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{a.employeeName}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{a.assetTag ?? '—'}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)' }}>{a.categoryName ?? '—'}</td>
                    <td style={tdStyle}>{[a.brand, a.model].filter(Boolean).join(' ') || '—'}</td>
                    <td style={tdStyle}>{fmtDate(a.effectiveFrom)}</td>
                    <td style={tdStyle}>{a.acknowledgedAt ? <span style={{ color: '#10B981', fontWeight: 600, fontSize: 12 }}>Yes</span> : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Pending</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        </div>
      )}

      {/* Team Asset Requests tab */}
      {activeTab === 'requests' && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 8, padding: '8px 12px', background: 'var(--raised)', borderRadius: 6, border: '1px solid var(--line2)' }}>
            Approval decisions are made in the <button onClick={() => navigate('/approvals?type=ASSET_REQUEST')} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 12 }}>Approval Center</button>. This view shows the full lifecycle.
          </div>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <input value={reqSearch} onChange={e => setReqSearch(e.target.value)} placeholder="Search employee…" style={{ ...inputStyle, width: 180, fontSize: 12, padding: '6px 10px' }} />
            <select value={reqStatusFilter} onChange={e => setReqStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Statuses</option>
              {reqStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>
            <select value={reqCatFilter} onChange={e => setReqCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Categories</option>
              {reqCategoryOptions.map(c => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
            </select>
          </div>
          <div style={panelStyle}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Employee', 'Category', 'Status', 'Submitted', 'Approved By', 'Fulfilled By'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {filteredRequests.length === 0 ? <EmptyRow cols={6} msg="No asset requests from your team." /> : filteredRequests.map(r => (
                    <tr key={r.id}>
                      <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{r.employeeName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{r.categoryName}</td>
                      <td style={tdStyle}>
                        <StatusBadge status={r.status} />
                        {r.rejectionReason && <div style={{ fontSize: 10, color: '#E4373D', marginTop: 2 }}>{r.rejectionReason}</div>}
                      </td>
                      <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                      <td style={tdStyle}>
                        {r.managerDecidedByName
                          ? <div><div style={{ color: 'var(--txt)', fontSize: 12, fontWeight: 600 }}>{r.managerDecidedByName}</div><div style={{ color: 'var(--txt-dim)', fontSize: 10 }}>{fmtDate(r.managerDecidedAt)}</div></div>
                          : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Pending</span>}
                      </td>
                      <td style={tdStyle}>
                        {r.fulfilledByName
                          ? <div><div style={{ color: 'var(--txt)', fontSize: 12, fontWeight: 600 }}>{r.fulfilledByName}</div><div style={{ color: 'var(--txt-dim)', fontSize: 10 }}>{fmtDate(r.fulfilledAt)}</div></div>
                          : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>{r.status === 'APPROVED' ? 'Awaiting HR' : '—'}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Team Expense Claims tab */}
      {activeTab === 'claims' && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 8, padding: '8px 12px', background: 'var(--raised)', borderRadius: 6, border: '1px solid var(--line2)' }}>
            Decisions are made in the <button onClick={() => navigate('/approvals?type=EXPENSE')} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 12 }}>Approval Center</button>. This view shows all statuses.
          </div>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <input value={claimSearch} onChange={e => setClaimSearch(e.target.value)} placeholder="Search employee…" style={{ ...inputStyle, width: 180, fontSize: 12, padding: '6px 10px' }} />
            <select value={claimStatusFilter} onChange={e => setClaimStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Statuses</option>
              {claimStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>
            <select value={claimCatFilter} onChange={e => setClaimCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
              <option value="">All Categories</option>
              {claimCategoryOptions.map(c => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
            </select>
          </div>
          <div style={panelStyle}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Employee', 'Category', 'Amount', 'Expense Date', 'Purpose', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {filteredClaims.length === 0 ? <EmptyRow cols={6} msg="No expense claims for your team." /> : filteredClaims.map(c => (
                    <tr key={c.id}>
                      <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{c.employeeName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{c.categoryName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{fmtCurrency(c.amount)}</td>
                      <td style={tdStyle}>{fmtDate(c.expenseDate)}</td>
                      <td style={{ ...tdStyle, maxWidth: 180 }}><div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }} title={c.businessPurpose}>{c.businessPurpose}</div></td>
                      <td style={tdStyle}><StatusBadge status={c.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ══════════════════════════════════════════════════════════
// HR / SUPER ADMIN VIEW
// ══════════════════════════════════════════════════════════

function HRView({ token }: { token: string }) {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const assetRequestsRef = useRef<HTMLDivElement>(null);

  const [hrTiles, setHrTiles] = useState<AssetTileHR | null>(null);
  const [hrExpTiles, setHrExpTiles] = useState<ExpenseTileHR | null>(null);
  const [inventory, setInventory] = useState<AssetResponse[]>([]);
  const [assetRequests, setAssetRequests] = useState<AssetRequestResponse[]>([]);
  const [expCategories, setExpCategories] = useState<ExpenseCategory[]>([]);
  const [payrollClaims, setPayrollClaims] = useState<ExpenseClaimResponse[]>([]);
  const [inventoryFilter, setInventoryFilter] = useState<'ALL' | 'OVERDUE'>('ALL');
  const [inventorySearch, setInventorySearch] = useState('');
  const [inventoryCatFilter, setInventoryCatFilter] = useState('');
  const [inventoryStatusFilter, setInventoryStatusFilter] = useState('');
  const [reqStatusFilter, setReqStatusFilter] = useState('');
  const [payrollSearch, setPayrollSearch] = useState('');
  const [activeTab, setActiveTab] = useState<'inventory' | 'requests' | 'categories' | 'payroll'>('inventory');

  const [showAddAsset, setShowAddAsset] = useState(false);
  const [showAssignModal, setShowAssignModal] = useState<AssetResponse | null>(null);
  const [showReassignModal, setShowReassignModal] = useState<AssetResponse | null>(null);
  const [showReturnModal, setShowReturnModal] = useState<AssetResponse | null>(null);
  const [showFulfillModal, setShowFulfillModal] = useState<AssetRequestResponse | null>(null);
  const [showCatModal, setShowCatModal] = useState<ExpenseCategory | null | 'new'>(null);

  function reload() {
    assetsApi.hrTiles(token).then(setHrTiles).catch(() => {});
    expensesApi.hrTiles(token).then(setHrExpTiles).catch(() => {});
    assetsApi.listAll(token).then(setInventory).catch(() => {});
    assetsApi.pendingApprovals(token).then(setAssetRequests).catch(() => {});
    expensesApi.categories(token).then(setExpCategories).catch(() => {});
    expensesApi.clearedForPayroll(token).then(setPayrollClaims).catch(() => {});
  }

  useEffect(() => { reload(); }, [token]);

  const inventoryCategoryOptions = Array.from(new Set(inventory.map(a => a.categoryName).filter(Boolean))) as string[];
  const inventoryStatusOptions = Array.from(new Set(inventory.map(a => a.status)));

  const filteredInventory = inventory
    .filter(a => inventoryFilter === 'ALL' || a.status === 'ASSIGNED')
    .filter(a => !inventorySearch || a.assetTag.toLowerCase().includes(inventorySearch.toLowerCase()) || (a.serialNumber ?? '').toLowerCase().includes(inventorySearch.toLowerCase()) || (a.brand ?? '').toLowerCase().includes(inventorySearch.toLowerCase()) || (a.model ?? '').toLowerCase().includes(inventorySearch.toLowerCase()))
    .filter(a => !inventoryCatFilter || a.categoryName === inventoryCatFilter)
    .filter(a => !inventoryStatusFilter || a.status === inventoryStatusFilter);

  async function handleRetire(assetId: number) {
    try {
      const updated = await assetsApi.retireAsset(assetId, token);
      setInventory(prev => prev.map(a => a.id === updated.id ? updated : a));
      showToast('success', 'Asset retired');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); }
  }

  async function handleMarkPaid(claimId: string) {
    try {
      const updated = await expensesApi.markPaid(claimId, token);
      setPayrollClaims(prev => prev.filter(c => c.id !== updated.id));
      showToast('success', 'Claim marked as paid');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); }
  }

  const hrTabs = [
    { key: 'inventory' as const, label: 'Asset Inventory', count: inventory.length },
    { key: 'requests' as const, label: 'Asset Requests', count: assetRequests.length },
    { key: 'categories' as const, label: 'Expense Categories', count: expCategories.length },
    { key: 'payroll' as const, label: 'Payroll-Ready Claims', count: payrollClaims.length },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Tiles */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <Tile label="Assets Assigned" value={hrTiles?.totalAssigned ?? '—'} clickable onClick={() => { setInventoryFilter('ALL'); setInventoryStatusFilter(''); setActiveTab('inventory'); }} />
        <Tile label="Available Inventory" value={hrTiles?.available ?? '—'} clickable onClick={() => { setInventoryFilter('ALL'); setInventoryStatusFilter('AVAILABLE'); setActiveTab('inventory'); }} />
        <Tile label="Overdue Returns" value={hrTiles?.overdueReturns ?? '—'} clickable={!!hrTiles?.overdueReturns} onClick={() => { setInventoryFilter('OVERDUE'); setInventoryStatusFilter(''); setActiveTab('inventory'); }} />
        <Tile label="Pending Expense Clearance" value={hrExpTiles?.pendingClearanceCount ?? '—'} sub={hrExpTiles?.pendingClearanceCount ? fmtCurrency(hrExpTiles.pendingAmount) : undefined} clickable={!!hrExpTiles?.pendingClearanceCount} onClick={() => navigate('/approvals?type=EXPENSE&stage=FINAL')} clickHint="Review in Approval Center →" />
        <Tile label="Pending Asset Fulfillment" value={assetRequests.filter(r => r.status === 'APPROVED').length} clickable onClick={() => setActiveTab('requests')} />
      </div>

      {/* Tab bar */}
      <div className="nf-tab-scroll" style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', paddingBottom: 0 }}>
        {hrTabs.map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
            background: 'none', border: 'none', borderBottom: activeTab === t.key ? '2px solid var(--brand)' : '2px solid transparent',
            padding: '8px 16px', fontSize: 13, fontWeight: activeTab === t.key ? 700 : 500,
            color: activeTab === t.key ? 'var(--brand)' : 'var(--txt-mut)', cursor: 'pointer', marginBottom: -1,
          }}>
            {t.label}
            {t.count > 0 && <span style={{ marginLeft: 6, fontSize: 11, fontWeight: 700, background: activeTab === t.key ? 'var(--brand)' : 'var(--raised)', color: activeTab === t.key ? '#fff' : 'var(--txt-dim)', borderRadius: 10, padding: '1px 6px' }}>{t.count}</span>}
          </button>
        ))}
      </div>

      {/* Asset Inventory tab */}
      {activeTab === 'inventory' && (<div>
        <SectionHead
          title="Company Asset Inventory"
          action={
            <button onClick={() => setShowAddAsset(true)} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
              <Plus size={13} /> Add Asset
            </button>
          }
        />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
          <input value={inventorySearch} onChange={e => setInventorySearch(e.target.value)} placeholder="Search tag / serial / brand…" style={{ ...inputStyle, width: 210, fontSize: 12, padding: '6px 10px' }} />
          <select value={inventoryCatFilter} onChange={e => setInventoryCatFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
            <option value="">All Categories</option>
            {inventoryCategoryOptions.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
          <select value={inventoryStatusFilter} onChange={e => setInventoryStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
            <option value="">All Statuses</option>
            {inventoryStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
          </select>
          <select value={inventoryFilter} onChange={e => setInventoryFilter(e.target.value as 'ALL' | 'OVERDUE')} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
            <option value="ALL">All Assets</option>
            <option value="OVERDUE">Overdue Returns</option>
          </select>
        </div>
        <div style={panelStyle}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Tag', 'Category', 'Brand / Model', 'Status', 'Condition', 'Custodian', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {filteredInventory.length === 0 ? <EmptyRow cols={7} msg="No assets in inventory." /> : filteredInventory.map(a => (
                  <tr key={a.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{a.assetTag}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)' }}>{a.categoryName}</td>
                    <td style={tdStyle}>{[a.brand, a.model].filter(Boolean).join(' ') || '—'}</td>
                    <td style={tdStyle}><StatusBadge status={a.status} /></td>
                    <td style={tdStyle}><StatusBadge status={a.condition} /></td>
                    <td style={{ ...tdStyle, color: a.currentCustodianName ? 'var(--txt)' : 'var(--txt-dim)' }}>{a.currentCustodianName ?? 'Unassigned'}</td>
                    <td style={{ ...tdStyle, padding: '8px 10px' }}>
                      <KebabMenu items={[
                        ...(a.status === 'AVAILABLE' ? [{ label: 'Assign', onClick: () => setShowAssignModal(a) }] : []),
                        ...(a.status === 'ASSIGNED' ? [
                          { label: 'Reassign', onClick: () => setShowReassignModal(a) },
                          { label: 'Mark Returned', onClick: () => setShowReturnModal(a) },
                        ] : []),
                        ...(a.status !== 'RETIRED' ? [{ label: 'Retire', onClick: () => handleRetire(a.id), danger: true }] : []),
                      ]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>)}

      {/* Asset Requests tab */}
      {activeTab === 'requests' && (<div ref={assetRequestsRef}>
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 8, padding: '8px 12px', background: 'var(--raised)', borderRadius: 6, border: '1px solid var(--line2)' }}>
          Manager-approved requests ready for fulfillment. Approval decisions are made in the <button onClick={() => navigate('/approvals?type=ASSET_REQUEST')} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 12 }}>Approval Center</button>.
        </div>
        <div style={{ marginBottom: 10 }}>
          <select value={reqStatusFilter} onChange={e => setReqStatusFilter(e.target.value)} style={{ ...inputStyle, width: 'auto', fontSize: 12, padding: '6px 10px' }}>
            <option value="">All Statuses</option>
            {Array.from(new Set(assetRequests.map(r => r.status))).map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
          </select>
        </div>
        <div style={panelStyle}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Employee', 'Category', 'Reason', 'Status', 'Submitted', 'Fulfill'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {assetRequests.filter(r => !reqStatusFilter || r.status === reqStatusFilter).length === 0 ? <EmptyRow cols={6} msg="No asset requests." /> : assetRequests.filter(r => !reqStatusFilter || r.status === reqStatusFilter).map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{r.employeeName}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)' }}>{r.categoryName}</td>
                    <td style={{ ...tdStyle, maxWidth: 200 }}><div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }} title={r.reason}>{r.reason}</div></td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                    <td style={{ ...tdStyle, padding: '8px 10px' }}>
                      {r.status === 'APPROVED' && (
                        <button onClick={() => setShowFulfillModal(r)} style={{ background: 'rgba(99,102,241,.15)', border: 'none', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: '#818CF8', cursor: 'pointer', fontWeight: 600 }}>
                          Fulfill
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>)}

      {/* Categories tab */}
      {activeTab === 'categories' && (<div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }}>
          <button onClick={() => setShowCatModal('new')} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}><Plus size={13} /> Add Category</button>
        </div>
        <div style={panelStyle}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Category', 'Receipt Required Above', 'Daily Limit', 'Second Approval Above', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {expCategories.length === 0 ? <EmptyRow cols={5} msg="No expense categories configured." /> : expCategories.map(c => (
                  <tr key={c.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{c.name}</td>
                    <td style={tdStyle}>{c.requiresReceiptAbove > 0 ? fmtCurrency(c.requiresReceiptAbove) : 'Not required'}</td>
                    <td style={tdStyle}>{c.dailyLimit ? fmtCurrency(c.dailyLimit) : '—'}</td>
                    <td style={tdStyle}>{c.secondApprovalAbove ? fmtCurrency(c.secondApprovalAbove) : '—'}</td>
                    <td style={{ ...tdStyle, padding: '8px 10px' }}>
                      <button onClick={() => setShowCatModal(c)} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: 'var(--txt-dim)', cursor: 'pointer' }}>Edit</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>)}

      {/* Payroll-Ready Claims tab */}
      {activeTab === 'payroll' && (<div>
        <div style={{ marginBottom: 10 }}>
          <input value={payrollSearch} onChange={e => setPayrollSearch(e.target.value)} placeholder="Search employee…" style={{ ...inputStyle, width: 200, fontSize: 12, padding: '6px 10px' }} />
        </div>
        <div style={{ ...panelStyle, padding: '14px 18px', marginBottom: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
          <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
            {hrExpTiles?.pendingClearanceCount ? `${hrExpTiles.pendingClearanceCount} claims pending final approval in Approval Center` : 'No claims pending final approval'}
          </div>
          <button onClick={() => navigate('/approvals?type=EXPENSE&stage=FINAL')} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
            <ExternalLink size={13} /> Approve in Approval Center
          </button>
        </div>
        <div style={panelStyle}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Employee', 'Category', 'Amount', 'Expense Date', 'Approved By', 'Action'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {payrollClaims.filter(c => !payrollSearch || c.employeeName.toLowerCase().includes(payrollSearch.toLowerCase())).length === 0 ? <EmptyRow cols={6} msg="No claims cleared for payroll." /> : payrollClaims.filter(c => !payrollSearch || c.employeeName.toLowerCase().includes(payrollSearch.toLowerCase())).map(c => (
                  <tr key={c.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{c.employeeName}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)' }}>{c.categoryName}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{fmtCurrency(c.amount)}</td>
                    <td style={tdStyle}>{fmtDate(c.expenseDate)}</td>
                    <td style={tdStyle}>{c.finalDecidedByName ?? c.managerDecidedByName ?? '—'}</td>
                    <td style={{ ...tdStyle, padding: '8px 10px' }}>
                      <button onClick={() => handleMarkPaid(c.id)} style={{ background: 'rgba(47,182,124,.12)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}>
                        Mark as Paid
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>)}

      {/* Modals — always rendered regardless of active tab */}
      {showAddAsset && <AddAssetModal token={token} onClose={() => setShowAddAsset(false)} onCreated={a => { setInventory(prev => [a, ...prev]); showToast('success', 'Asset added'); }} />}
      {showAssignModal && <AssignAssetModal asset={showAssignModal} token={token} mode="assign" onClose={() => setShowAssignModal(null)} onDone={a => { setInventory(prev => prev.map(x => x.id === a.id ? a : x)); reload(); showToast('success', 'Asset assigned'); setShowAssignModal(null); }} />}
      {showReassignModal && <AssignAssetModal asset={showReassignModal} token={token} mode="reassign" onClose={() => setShowReassignModal(null)} onDone={a => { setInventory(prev => prev.map(x => x.id === a.id ? a : x)); reload(); showToast('success', 'Asset reassigned'); setShowReassignModal(null); }} />}
      {showReturnModal && <MarkReturnedModal asset={showReturnModal} token={token} onClose={() => setShowReturnModal(null)} onDone={a => { setInventory(prev => prev.map(x => x.id === a.id ? a : x)); setShowReturnModal(null); showToast('success', 'Asset marked returned'); }} />}
      {showFulfillModal && <FulfillRequestModal request={showFulfillModal} token={token} onClose={() => setShowFulfillModal(null)} onDone={r => { setAssetRequests(prev => prev.map(x => x.id === r.id ? r : x)); setShowFulfillModal(null); showToast('success', 'Request fulfilled'); }} />}
      {showCatModal && <ExpenseCategoryModal category={showCatModal === 'new' ? null : showCatModal} token={token} onClose={() => setShowCatModal(null)} onSaved={c => { setExpCategories(prev => { const idx = prev.findIndex(x => x.id === c.id); return idx >= 0 ? prev.map((x, i) => i === idx ? c : x) : [c, ...prev]; }); showToast('success', 'Category saved'); setShowCatModal(null); }} />}
    </div>
  );
}



// ── HR Modals ─────────────────────────────────────────────

function AddAssetModal({ token, onClose, onCreated }: { token: string; onClose: () => void; onCreated: (a: AssetResponse) => void }) {
  const { showToast } = useToast();
  const [categories, setCategories] = useState<AssetCategory[]>([]);
  const [form, setForm] = useState({ assetTag: '', categoryId: '', brand: '', model: '', serialNumber: '', purchaseDate: '', purchaseCost: '', condition: 'GOOD' });
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => { assetsApi.categories(token).then(setCategories).catch(() => {}); }, [token]);

  function set(k: string, v: string) { setForm(f => ({ ...f, [k]: v })); }

  async function submit() {
    if (!form.assetTag || !form.categoryId) return;
    setSubmitting(true);
    try {
      const result = await assetsApi.createAsset({ assetTag: form.assetTag, categoryId: Number(form.categoryId), brand: form.brand || undefined, model: form.model || undefined, serialNumber: form.serialNumber || undefined, purchaseDate: form.purchaseDate || undefined, purchaseCost: form.purchaseCost ? Number(form.purchaseCost) : undefined, condition: form.condition }, token);
      onCreated(result);
      onClose();
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); setSubmitting(false); }
  }

  return (
    <Modal title="Add Asset" onClose={onClose}>
      <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 12px' }}>
        <FormRow><label style={labelStyle}>Asset Tag *</label><input style={inputStyle} value={form.assetTag} onChange={e => set('assetTag', e.target.value)} placeholder="e.g. LT-2024-001" /></FormRow>
        <FormRow><label style={labelStyle}>Category *</label><select style={inputStyle} value={form.categoryId} onChange={e => set('categoryId', e.target.value)}><option value="">Select…</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></FormRow>
        <FormRow><label style={labelStyle}>Brand</label><input style={inputStyle} value={form.brand} onChange={e => set('brand', e.target.value)} placeholder="e.g. Dell" /></FormRow>
        <FormRow><label style={labelStyle}>Model</label><input style={inputStyle} value={form.model} onChange={e => set('model', e.target.value)} placeholder="e.g. XPS 15" /></FormRow>
        <FormRow><label style={labelStyle}>Serial No.</label><input style={inputStyle} value={form.serialNumber} onChange={e => set('serialNumber', e.target.value)} /></FormRow>
        <FormRow><label style={labelStyle}>Condition</label><select style={inputStyle} value={form.condition} onChange={e => set('condition', e.target.value)}><option>GOOD</option><option>FAIR</option><option>DAMAGED</option></select></FormRow>
        <FormRow><label style={labelStyle}>Purchase Date</label><input type="date" style={inputStyle} value={form.purchaseDate} onChange={e => set('purchaseDate', e.target.value)} /></FormRow>
        <FormRow><label style={labelStyle}>Purchase Cost (₹)</label><input type="number" min="0" step="0.01" style={inputStyle} value={form.purchaseCost} onChange={e => set('purchaseCost', e.target.value)} /></FormRow>
      </div>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 4 }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!form.assetTag || !form.categoryId || submitting}>{submitting ? 'Adding…' : 'Add Asset'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function AssignAssetModal({ asset, token, mode, onClose, onDone }: { asset: AssetResponse; token: string; mode: 'assign' | 'reassign'; onClose: () => void; onDone: (a: AssetResponse) => void }) {
  const { showToast } = useToast();
  const [employeeUserId, setEmployeeUserId] = useState('');
  const [employees, setEmployees] = useState<{ id: string; name: string }[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    employeesApi.list(token)
      .then(data => {
        if (cancelled) return;
        setEmployees(
          data
            .filter(e => e.active !== false && e.role !== 'SUPER_ADMIN')
            .map(e => ({ id: e.userId, name: e.fullName }))
        );
      })
      .catch(() => { if (!cancelled) showToast('error', 'Could not load employees'); });
    return () => { cancelled = true; };
  }, [token, showToast]);

  async function submit() {
    if (!employeeUserId) return;
    setSubmitting(true);
    try {
      const result = mode === 'assign'
        ? await assetsApi.assignAsset(asset.id, employeeUserId, token)
        : await assetsApi.reassignAsset(asset.id, employeeUserId, token);
      onDone(result);
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); setSubmitting(false); }
  }

  return (
    <Modal title={`${mode === 'assign' ? 'Assign' : 'Reassign'} ${asset.assetTag}`} onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Assign To *</label>
        <select value={employeeUserId} onChange={e => setEmployeeUserId(e.target.value)} style={inputStyle}>
          <option value="">Select employee…</option>
          {employees.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}
        </select>
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!employeeUserId || submitting}>{submitting ? 'Saving…' : mode === 'assign' ? 'Assign' : 'Reassign'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function MarkReturnedModal({ asset, token, onClose, onDone }: { asset: AssetResponse; token: string; onClose: () => void; onDone: (a: AssetResponse) => void }) {
  const { showToast } = useToast();
  const [returnCondition, setReturnCondition] = useState('GOOD');
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    setSubmitting(true);
    try {
      const result = await assetsApi.markReturned(asset.id, returnCondition, token);
      onDone(result);
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); setSubmitting(false); }
  }

  return (
    <Modal title={`Mark Returned — ${asset.assetTag}`} onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Return Condition</label>
        <select value={returnCondition} onChange={e => setReturnCondition(e.target.value)} style={inputStyle}>
          <option value="GOOD">Good</option>
          <option value="FAIR">Fair</option>
          <option value="DAMAGED">Damaged (will move to In Repair)</option>
        </select>
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={submitting}>{submitting ? 'Saving…' : 'Mark Returned'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function FulfillRequestModal({ request, token, onClose, onDone }: { request: AssetRequestResponse; token: string; onClose: () => void; onDone: (r: AssetRequestResponse) => void }) {
  const { showToast } = useToast();
  const [availableAssets, setAvailableAssets] = useState<AssetResponse[]>([]);
  const [assetId, setAssetId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    assetsApi.availableByCategory(request.categoryId, token).then(setAvailableAssets).catch(() => {});
  }, [request.categoryId, token]);

  async function submit() {
    if (!assetId) return;
    setSubmitting(true);
    try {
      const result = await assetsApi.fulfillRequest(request.id, Number(assetId), token);
      onDone(result);
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); setSubmitting(false); }
  }

  return (
    <Modal title={`Fulfill Request — ${request.employeeName}`} onClose={onClose}>
      <div style={{ marginBottom: 14, padding: '10px 12px', background: 'var(--raised)', borderRadius: 6, fontSize: 13, color: 'var(--txt-mut)' }}>
        Requested: <strong style={{ color: 'var(--txt)' }}>{request.categoryName}</strong> — "{request.reason}"
      </div>
      <FormRow>
        <label style={labelStyle}>Select Asset to Assign *</label>
        {availableAssets.length === 0 ? (
          <div style={{ fontSize: 13, color: '#F59E0B', padding: '8px 0' }}>No available {request.categoryName} assets in inventory.</div>
        ) : (
          <select value={assetId} onChange={e => setAssetId(e.target.value)} style={inputStyle}>
            <option value="">Select available asset…</option>
            {availableAssets.map(a => <option key={a.id} value={a.id}>{a.assetTag} — {[a.brand, a.model].filter(Boolean).join(' ') || 'No details'} ({a.condition})</option>)}
          </select>
        )}
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!assetId || submitting}>{submitting ? 'Fulfilling…' : 'Fulfill & Assign'}</BtnPrimary>
      </div>
    </Modal>
  );
}

/**
 * Keeps only digits and a single decimal point — strips '-', letters, and anything else typed
 * or pasted in. Applied identically to Daily Limit and Second Approval Above so neither field
 * can ever hold a negative or otherwise invalid value in state.
 *
 * These two fields deliberately use type="text" (not type="number"): a number input's `.value`
 * getter sanitizes an invalid intermediate string like "-" down to "" per the HTML spec, which
 * matches this function's own output — so React sees no state change and never re-syncs the
 * DOM, leaving the stray "-" visible even though the real value is already empty. A text input
 * has no such sanitization step, so forcing state back to the cleaned value actually clears it.
 */
function sanitizePositiveDecimalInput(raw: string): string {
  const digitsAndDots = raw.replace(/[^0-9.]/g, '');
  const firstDot = digitsAndDots.indexOf('.');
  if (firstDot === -1) return digitsAndDots;
  return digitsAndDots.slice(0, firstDot + 1) + digitsAndDots.slice(firstDot + 1).replace(/\./g, '');
}

function ExpenseCategoryModal({ category, token, onClose, onSaved }: { category: ExpenseCategory | null; token: string; onClose: () => void; onSaved: (c: ExpenseCategory) => void }) {
  const { showToast } = useToast();
  const [form, setForm] = useState({
    name: category?.name ?? '',
    requiresReceiptAbove: String(category?.requiresReceiptAbove ?? 0),
    dailyLimit: String(category?.dailyLimit ?? ''),
    secondApprovalAbove: String(category?.secondApprovalAbove ?? ''),
  });
  const [submitting, setSubmitting] = useState(false);

  function set(k: string, v: string) { setForm(f => ({ ...f, [k]: v })); }
  function setPositiveDecimal(k: string, v: string) { set(k, sanitizePositiveDecimalInput(v)); }

  async function submit() {
    const trimmedName = form.name.trim();
    if (!trimmedName) return;
    // Mirrors OrgSetupPage's Department/Designation name check: must include at least one
    // letter, rejecting numeric-only ("123"), negative-number ("-5"), and symbol-only ("@#$")
    // category names before they ever reach the backend's identical @Pattern check.
    if (!/^(?=.*[A-Za-z])[^0-9]+$/.test(trimmedName)) {
      showToast('error', 'Category name must contain letters and cannot contain numbers or be made up of special characters only');
      return;
    }
    const dailyLimitNum = form.dailyLimit ? parseFloat(form.dailyLimit) : null;
    const secondApprovalNum = form.secondApprovalAbove ? parseFloat(form.secondApprovalAbove) : null;
    if (dailyLimitNum != null && (isNaN(dailyLimitNum) || dailyLimitNum < 0)) {
      showToast('error', 'Daily Limit must be a valid positive number');
      return;
    }
    if (secondApprovalNum != null && (isNaN(secondApprovalNum) || secondApprovalNum < 0)) {
      showToast('error', 'Second Approval Above must be a valid positive number');
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        name: trimmedName,
        requiresReceiptAbove: parseFloat(form.requiresReceiptAbove) || 0,
        dailyLimit: dailyLimitNum,
        secondApprovalAbove: secondApprovalNum,
      };
      const result = category
        ? await expensesApi.updateCategory(category.id, payload, token)
        : await expensesApi.createCategory(payload, token);
      onSaved(result);
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Failed'); setSubmitting(false); }
  }

  return (
    <Modal title={category ? `Edit — ${category.name}` : 'Add Expense Category'} onClose={onClose}>
      <FormRow><label style={labelStyle}>Name *</label><input style={inputStyle} value={form.name} onChange={e => set('name', e.target.value)} /></FormRow>
      <FormRow><label style={labelStyle}>Receipt Required Above (₹)</label><input type="number" min="0" step="0.01" style={inputStyle} value={form.requiresReceiptAbove} onChange={e => set('requiresReceiptAbove', e.target.value)} /><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>Set to 0 to never require a receipt.</div></FormRow>
      <FormRow><label style={labelStyle}>Daily Limit (₹, optional)</label><input type="text" inputMode="decimal" style={inputStyle} value={form.dailyLimit} onChange={e => setPositiveDecimal('dailyLimit', e.target.value)} placeholder="No limit" /></FormRow>
      <FormRow><label style={labelStyle}>Second Approval Above (₹, optional)</label><input type="text" inputMode="decimal" style={inputStyle} value={form.secondApprovalAbove} onChange={e => setPositiveDecimal('secondApprovalAbove', e.target.value)} placeholder="No second approval" /></FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!form.name.trim() || submitting}>{submitting ? 'Saving…' : 'Save'}</BtnPrimary>
      </div>
    </Modal>
  );
}

// ══════════════════════════════════════════════════════════
// PAGE ENTRY POINT
// ══════════════════════════════════════════════════════════

export default function AssetsExpensesPage() {
  const token = useAuthStore(s => s.token)!;
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);

  const pageTitle = role === 'Manager' ? 'Team Assets & Expenses'
    : role === 'HR Admin' || role === 'Super Admin' ? 'Assets & Expense Management'
    : 'My Assets & Expenses';

  return (
    <div>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>{pageTitle}</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
          {role === 'Manager' ? 'Read-only view of your team\'s assets and expenses. Approval decisions are made in the Approval Center.'
            : role === 'HR Admin' || role === 'Super Admin' ? 'Manage company inventory, expense policies, and payroll-ready claims.'
            : 'Track your assigned assets and submit expense claims.'}
        </p>
      </div>

      {(role === 'Employee') && <EmployeeView token={token} />}
      {(role === 'Manager') && <ManagerView token={token} />}
      {(role === 'HR Admin' || role === 'Super Admin') && <HRView token={token} />}
    </div>
  );
}
