import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Plus, ChevronRight, ChevronLeft, Clock, FileText, Package, RefreshCw, Check,
  Archive, Calendar, Users, AlertTriangle, ClipboardList,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  onboardingApi, type OnboardingSummary, type OnboardingDetail, type OnboardingItem,
  type OnboardingStatus, type StartOnboardingPayload,
} from '../api/onboarding';
import type { EmployeeRecord } from '../api/employees';
import { EmployeeAvatar } from '../components/EmployeeAvatar';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', background: 'var(--raised)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', padding: '9px 10px', borderRadius: 6, border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)', fontSize: 13, fontFamily: 'inherit', boxSizing: 'border-box' };
const btnStyle: React.CSSProperties = { padding: '8px 16px', background: 'var(--raised2)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' };
const btnPrimaryStyle: React.CSSProperties = { ...btnStyle, background: 'var(--brand)', borderColor: 'var(--brand)', color: '#fff', fontWeight: 600 };

// ── Small shared bits ──────────────────────────────────────

function fmtDate(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function StatusPill({ status }: { status: OnboardingStatus | string }) {
  const map: Record<string, { label: string; color: string; bg: string }> = {
    ON_TRACK: { label: 'On track', color: '#4C8DD6', bg: 'rgba(76,141,214,.15)' },
    ATTENTION: { label: 'Attention', color: '#E0A93B', bg: 'rgba(224,169,59,.15)' },
    OVERDUE: { label: 'Overdue', color: '#E4373D', bg: 'rgba(228,55,61,.15)' },
    COMPLETE: { label: 'Complete', color: '#2FB67C', bg: 'rgba(47,182,124,.15)' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)', bg: 'var(--raised2)' };
  return (
    <span style={{ fontSize: 11.5, fontWeight: 600, padding: '3px 9px', borderRadius: 20, color: cfg.color, background: cfg.bg, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

function DocStatusBadge({ status }: { status: string }) {
  const map: Record<string, { color: string; bg: string; label: string }> = {
    VERIFIED: { color: '#2FB67C', bg: 'rgba(47,182,124,.15)', label: 'Verified' },
    PENDING_VERIFICATION: { color: '#E0A93B', bg: 'rgba(224,169,59,.15)', label: 'Pending' },
    REJECTED: { color: '#E4373D', bg: 'rgba(228,55,61,.15)', label: 'Rejected' },
    MISSING: { color: '#E4373D', bg: 'rgba(228,55,61,.15)', label: 'Missing' },
  };
  const cfg = map[status] ?? { color: 'var(--txt-dim)', bg: 'var(--raised2)', label: status };
  return <span style={{ fontSize: 10.5, padding: '2px 7px', borderRadius: 20, fontWeight: 600, color: cfg.color, background: cfg.bg }}>{cfg.label}</span>;
}

function Kpi({ icon, label, value, note, danger }: { icon: React.ReactNode; label: string; value: string | number; note: string; danger?: boolean }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: danger ? '#E4373D' : 'var(--txt)', lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>
    </div>
  );
}

function tabStyle(active: boolean): React.CSSProperties {
  return { padding: '9px 2px', color: active ? 'var(--txt)' : 'var(--txt-mut)', border: 'none', borderBottom: `2px solid ${active ? 'var(--brand)' : 'transparent'}`, background: 'none', fontSize: 13, fontWeight: active ? 600 : 400, cursor: 'pointer' };
}

function Section({ icon, title, count, children }: { icon: React.ReactNode; title: string; count?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '12px 18px', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <h3 style={{ fontSize: 13, flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
        {count && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{count}</span>}
      </div>
      {children}
    </div>
  );
}

function ItemRow({ item, onToggle, toggling, linkTo }: { item: OnboardingItem; onToggle?: () => void; toggling?: boolean; linkTo?: string }) {
  const checkable = !!onToggle;
  const todayMidnight = new Date(new Date().toDateString()).getTime();
  const dueTime = item.dueDate ? new Date(item.dueDate).getTime() : null;
  const overdue = !item.done && dueTime !== null && dueTime < todayMidnight;
  const dueSoon = !item.done && dueTime !== null && !overdue && (dueTime - todayMidnight) <= 2 * 86400000;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
      <button
        disabled={!checkable || toggling}
        onClick={onToggle}
        aria-label={item.done ? `Mark ${item.label} as not done` : `Mark ${item.label} as done`}
        style={{
          width: 19, height: 19, borderRadius: 5, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
          borderWidth: 1.5, borderColor: item.done ? '#2FB67C' : 'var(--line2)', borderStyle: item.auto ? 'dashed' : 'solid',
          background: item.done ? '#2FB67C' : 'var(--shell)', color: '#fff', cursor: checkable ? 'pointer' : 'default', padding: 0,
          opacity: toggling ? 0.6 : 1,
        }}
      >
        {item.done && <Check size={12} />}
      </button>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, color: item.done ? 'var(--txt-mut)' : 'var(--txt)', textDecorationLine: item.done ? 'line-through' : 'none', textDecorationColor: 'var(--line2)' }}>
          {item.label}
        </div>
        {(item.auto || (item.done && item.doneByName)) && (
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            {item.auto && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: '#4C8DD6', background: 'rgba(76,141,214,.12)', padding: '1px 7px', borderRadius: 20, fontSize: 10, fontWeight: 600 }}>
                <RefreshCw size={10} /> auto-synced from {item.source}
              </span>
            )}
            {item.done && item.doneByName && (
              <span>{item.doneByName === 'System' ? 'synced automatically' : `checked off by ${item.doneByName}`}{item.meta ? ` · ${item.meta}` : ''}</span>
            )}
          </div>
        )}
      </div>
      {!item.done && item.dueDate && (
        <span style={{ fontSize: 11.5, whiteSpace: 'nowrap', color: overdue ? '#E4373D' : dueSoon ? '#E0A93B' : 'var(--txt-dim)', fontWeight: overdue || dueSoon ? 600 : 400 }}>
          {overdue ? 'overdue · ' : dueSoon ? 'due soon · ' : 'due '}{fmtDate(item.dueDate)}
        </span>
      )}
      {item.auto && !item.done && linkTo && (
        <Link to={linkTo} style={{ fontSize: 11.5, color: 'var(--info)', textDecoration: 'none', border: '1px dashed var(--line2)', padding: '5px 10px', borderRadius: 6, whiteSpace: 'nowrap' }}>
          Go to {item.source} →
        </Link>
      )}
    </div>
  );
}

// ── Detail view ─────────────────────────────────────────────

function OnboardingDetailView({ checklistId, onBack, onChanged }: { checklistId: string; onBack: () => void; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [detail, setDetail] = useState<OnboardingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [toggling, setToggling] = useState<string | null>(null);
  const [showDocsBreakdown, setShowDocsBreakdown] = useState(false);

  useEffect(() => {
    setLoading(true);
    onboardingApi.detail(checklistId, token)
      .then(setDetail)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Failed to load'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checklistId, token]);

  async function toggle(item: OnboardingItem) {
    if (!detail || !item.id) return;
    setToggling(item.id);
    try {
      const updated = await onboardingApi.toggleItem(detail.checklistId, item.id, token);
      setDetail(updated);
      if (updated.archived) {
        showToast('success', `${updated.employeeName}'s onboarding archived`);
        onChanged();
      }
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Update failed');
    } finally {
      setToggling(null);
    }
  }

  if (loading || !detail) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  const pct = detail.totalItems > 0 ? Math.round(100 * detail.doneItems / detail.totalItems) : 0;

  return (
    <div>
      <button onClick={onBack} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 13, cursor: 'pointer', marginBottom: 16, padding: 0 }}>
        <ChevronLeft size={14} /> Back to onboarding queue
      </button>

      {detail.archived && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'rgba(47,182,124,.12)', border: '1px solid rgba(47,182,124,.3)', color: '#2FB67C', borderRadius: 10, padding: '13px 18px', marginBottom: 16, fontSize: 13.5, fontWeight: 600 }}>
          <Archive size={16} /> Onboarding complete — archived on {fmtDate(detail.completedAt)}.
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 14 }}>
          <EmployeeAvatar
            userId={detail.employeeUserId}
            name={detail.employeeName}
            size={46}
            fontSize={15}
            background="var(--brand)"
            style={{ fontFamily: 'Inter, sans-serif' }}
          />
          <div>
            <h2 style={{ fontSize: 17, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{detail.employeeName}</h2>
            <div style={{ color: 'var(--txt-mut)', fontSize: 12.5, marginTop: 3 }}>
              {[detail.designationName, detail.departmentName, detail.locationName].filter(Boolean).join(' · ')}
              {' · '}<span style={{ fontFamily: 'Inter, sans-serif' }}>{detail.employeeCode}</span>
            </div>
            <div style={{ display: 'flex', gap: 16, marginTop: 10, fontSize: 12, color: 'var(--txt-dim)', flexWrap: 'wrap' }}>
              <span><Calendar size={12} style={{ verticalAlign: -1.5, marginRight: 3 }} /> Joining <b style={{ color: 'var(--txt)' }}>{fmtDate(detail.joiningDate)}</b></span>
              {detail.managerName && <span>Manager <b style={{ color: 'var(--txt)' }}>{detail.managerName}</b></span>}
            </div>
          </div>
        </div>
        <div style={{ textAlign: 'right', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8, minWidth: 150, flexShrink: 0 }}>
          <StatusPill status={detail.status} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ fontSize: 20, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{pct}%</span>
            <div style={{ width: 80, height: 6, borderRadius: 20, background: 'var(--raised2)', overflow: 'hidden' }}>
              <div style={{ width: `${pct}%`, height: '100%', background: pct === 100 ? '#2FB67C' : 'var(--brand)' }} />
            </div>
          </div>
          <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{detail.doneItems} of {detail.totalItems} tasks done</span>
        </div>
      </div>

      <Section icon={<ClipboardList size={14} />} title="Pre-boarding" count={`${detail.preBoarding.filter(i => i.done).length}/${detail.preBoarding.length}`}>
        {detail.preBoarding.map(i => (
          <ItemRow key={i.itemKey} item={i} onToggle={() => toggle(i)} toggling={toggling === i.id} />
        ))}
      </Section>

      <Section icon={<FileText size={14} />} title="Document collection" count={detail.documentsItem.done ? '1/1' : '0/1'}>
        <ItemRow item={detail.documentsItem} linkTo="/documents" />
        <div style={{ padding: '0 18px 12px' }}>
          <button onClick={() => setShowDocsBreakdown(v => !v)} style={{ fontSize: 11.5, color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', padding: 0 }}>
            {showDocsBreakdown ? 'hide breakdown' : 'show breakdown'}
          </button>
          {showDocsBreakdown && (
            <div style={{ marginTop: 8, display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px,1fr))', gap: 8 }}>
              {detail.documentsBreakdown.length === 0 ? (
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No required documents apply to this employee.</span>
              ) : detail.documentsBreakdown.map((d, idx) => (
                <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12.5, background: 'var(--raised)', padding: '7px 10px', borderRadius: 8 }}>
                  <span>{d.documentTypeName}</span>
                  <DocStatusBadge status={d.status} />
                </div>
              ))}
            </div>
          )}
        </div>
      </Section>

      <Section icon={<Package size={14} />} title="Setup tasks" count={`${detail.setup.filter(i => i.done).length}/${detail.setup.length}`}>
        {detail.setup.map(i => (
          <ItemRow
            key={i.itemKey}
            item={i}
            onToggle={i.auto ? undefined : () => toggle(i)}
            toggling={toggling === i.id}
            linkTo={i.auto ? '/assets' : undefined}
          />
        ))}
      </Section>

      <Section icon={<Clock size={14} />} title="Activity">
        <div style={{ padding: '6px 18px 16px' }}>
          {detail.timeline.map((t, idx) => (
            <div key={idx} style={{ display: 'flex', gap: 12, padding: '8px 0', fontSize: 12.5 }}>
              <span style={{ color: 'var(--txt-dim)', width: 60, flexShrink: 0 }}>{fmtDate(t.at)}</span>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--line2)', marginTop: 5, flexShrink: 0 }} />
              <div>
                <b style={{ fontWeight: 600, color: 'var(--txt)' }}>{t.text}</b>
                <div style={{ color: 'var(--txt-dim)' }}>{t.meta}</div>
              </div>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}

// ── Start onboarding modal ──────────────────────────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <label style={{ display: 'block', fontSize: 11.5, color: 'var(--txt-mut)', marginBottom: 5, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.04em' }}>{label}</label>
      {children}
    </div>
  );
}

function StepBadge({ n, label, state }: { n: number; label: string; state: 'active' | 'done' | 'upcoming' }) {
  const filled = state !== 'upcoming';
  const bg = state === 'done' ? '#2FB67C' : state === 'active' ? 'var(--brand)' : 'var(--shell)';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, width: 120, flexShrink: 0 }}>
      <div style={{ width: 26, height: 26, borderRadius: '50%', border: `2px solid ${filled ? bg : 'var(--line2)'}`, background: bg, color: filled ? '#fff' : 'var(--txt-dim)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 700, fontFamily: 'Inter, sans-serif' }}>
        {n}
      </div>
      <div style={{ fontSize: 11, color: state === 'active' ? 'var(--txt)' : 'var(--txt-dim)', fontWeight: state === 'active' ? 600 : 400, textAlign: 'center' }}>{label}</div>
    </div>
  );
}

function PreviewRow({ label, value }: { label: string; value: string | number }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, padding: '4px 0', color: 'var(--txt)' }}>
      <span>{label}</span><b style={{ fontFamily: 'Inter, sans-serif' }}>{value}</b>
    </div>
  );
}

function StartOnboardingModal({ onClose, onCreated }: { onClose: () => void; onCreated: (checklistId: string) => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [step, setStep] = useState<1 | 2>(1);
  const [eligible, setEligible] = useState<EmployeeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [selectedId, setSelectedId] = useState('');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    setLoading(true);
    setLoadError('');
    onboardingApi.eligibleEmployees(token)
      .then(e => {
        setEligible(e);
        if (e.length > 0) setSelectedId(e[0].userId);
      })
      .catch(err => {
        const message = err instanceof Error ? err.message : 'Failed to load';
        setLoadError(message);
        showToast('error', message);
      })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, loadAttempt]);

  const selectedEmployee = eligible.find(e => e.userId === selectedId) ?? null;

  function handleContinue() {
    if (!selectedEmployee) return;
    setStep(2);
  }

  async function handleCreate() {
    if (!selectedEmployee) return;
    setCreating(true);
    try {
      const payload: StartOnboardingPayload = { employeeUserId: selectedEmployee.userId };
      const created = await onboardingApi.start(payload, token);
      showToast('success', `Checklist created for ${created.employeeName}`);
      onCreated(created.checklistId);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to start onboarding');
    } finally {
      setCreating(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 400, padding: '40px 16px' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '100%', maxWidth: 480, maxHeight: '88vh', overflowY: 'auto', padding: 26, boxSizing: 'border-box' }}>
        <h2 style={{ fontSize: 16, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Start onboarding</h2>
        <p style={{ color: 'var(--txt-mut)', fontSize: 12.5, margin: '0 0 18px' }}>
          Pick a new hire already in Employee Master. We'll generate their pre-boarding, document and setup checklist automatically.
        </p>

        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 20 }}>
          <StepBadge n={1} label="New hire" state={step === 1 ? 'active' : 'done'} />
          <div style={{ flex: 1, height: 2, margin: '0 4px 20px', background: step === 2 ? '#2FB67C' : 'var(--line2)' }} />
          <StepBadge n={2} label="Review & create" state={step === 2 ? 'active' : 'upcoming'} />
        </div>

        {loading ? (
          <p style={{ color: 'var(--txt-dim)' }}>Loading…</p>
        ) : loadError ? (
          <>
            <p style={{ color: '#E4373D', fontSize: 13, marginBottom: 16 }}>
              Couldn't load new hires ({loadError}). Your session may have expired — try refreshing the page and signing in again.
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
              <button onClick={onClose} style={btnStyle}>Cancel</button>
              <button onClick={() => setLoadAttempt(a => a + 1)} style={btnPrimaryStyle}>Retry</button>
            </div>
          </>
        ) : step === 1 ? (
          <>
            {eligible.length === 0 ? (
              <p style={{ color: 'var(--txt-mut)', fontSize: 13, marginBottom: 14 }}>
                Every employee in Employee Master already has an onboarding checklist. Add the new hire there first, then start onboarding for them.
              </p>
            ) : (
              <>
                <Field label="New hire">
                  <select value={selectedId} onChange={e => setSelectedId(e.target.value)} style={inputStyle}>
                    {eligible.map(e => (
                      <option key={e.userId} value={e.userId}>{e.fullName} — {e.employeeCode} — {e.departmentName ?? 'No department'}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Joining date">
                  <input value={selectedEmployee ? fmtDate(selectedEmployee.joiningDate) : ''} readOnly style={{ ...inputStyle, color: 'var(--txt-mut)' }} />
                </Field>
              </>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
              <button onClick={onClose} style={btnStyle}>Cancel</button>
              <button onClick={handleContinue} style={btnPrimaryStyle} disabled={eligible.length === 0}>Continue</button>
            </div>
          </>
        ) : (
          <>
            <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '14px 16px', marginBottom: 14, display: 'flex', gap: 12, alignItems: 'center' }}>
              <EmployeeAvatar
                userId={selectedEmployee?.userId}
                name={selectedEmployee?.fullName ?? '?'}
                size={38}
                fontSize={13}
                background="var(--brand)"
                style={{ fontFamily: 'Inter, sans-serif' }}
              />
              <div>
                <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13.5 }}>{selectedEmployee?.fullName ?? '—'}</div>
                <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>
                  {[selectedEmployee?.designationName, selectedEmployee?.departmentName].filter(Boolean).join(' · ')}
                  {selectedEmployee?.joiningDate ? ` · joining ${fmtDate(selectedEmployee.joiningDate)}` : ''}
                </div>
              </div>
            </div>
            <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px', marginBottom: 18 }}>
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginBottom: 8 }}>Checklist that will be created</div>
              <PreviewRow label="Pre-boarding tasks" value={4} />
              <PreviewRow label="Required documents (auto-tracked)" value="Auto" />
              <PreviewRow label="Setup tasks" value={4} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
              <button onClick={() => setStep(1)} style={btnStyle} disabled={creating}>Back</button>
              <button onClick={handleCreate} style={btnPrimaryStyle} disabled={creating}>{creating ? 'Creating…' : 'Create checklist'}</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

// ── Main page ────────────────────────────────────────────────

export default function OnboardingPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [tab, setTab] = useState<'active' | 'archived'>('active');
  const [rows, setRows] = useState<OnboardingSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setLoadError('');
    onboardingApi.queue(token)
      .then(setRows)
      .catch(e => {
        const message = e instanceof Error ? e.message : 'Failed to load';
        setLoadError(message);
        showToast('error', message);
      })
      .finally(() => setLoading(false));
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [token]);

  if (selected) {
    return (
      <OnboardingDetailView
        checklistId={selected}
        onBack={() => { setSelected(null); load(); }}
        onChanged={() => load()}
      />
    );
  }

  const active = rows.filter(r => !r.archived);
  const archived = rows.filter(r => r.archived);
  const overdueCount = active.filter(r => r.status === 'OVERDUE').length;
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 1);
  const completedThisMonth = archived.filter(r => {
    if (!r.completedDate) return false;
    const d = new Date(r.completedDate);
    return d >= monthStart && d < monthEnd;
  }).length;
  const avgDays = archived.length
    ? Math.round(archived.reduce((s, r) => s + (r.durationDays ?? 0), 0) / archived.length)
    : 0;
  const list = tab === 'active' ? active : archived;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Onboarding</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>Every great career starts with NForceOne.</p>
        </div>
        <button onClick={() => setShowModal(true)} style={{ ...btnPrimaryStyle, display: 'flex', alignItems: 'center', gap: 6 }}>
          <Plus size={14} /> Start onboarding
        </button>
      </div>

      <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12, marginBottom: 20 }}>
        <Kpi icon={<Users size={14} />} label="Active flows" value={active.length} note="In progress right now" />
        <Kpi icon={<AlertTriangle size={14} />} label="Overdue tasks" value={overdueCount} note="Flows with a missed due date" danger={overdueCount > 0} />
        <Kpi icon={<Check size={14} />} label="Completed this month" value={completedThisMonth} note="Archived onboarding flows this calendar month" />
        <Kpi icon={<Clock size={14} />} label="Avg. time to complete" value={`${avgDays} d`} note="Joining date to full checklist" />
      </div>

      <div style={{ display: 'flex', gap: 18, borderBottom: '1px solid var(--line)', marginBottom: 16 }}>
        <button onClick={() => setTab('active')} style={tabStyle(tab === 'active')}>Active ({active.length})</button>
        <button onClick={() => setTab('archived')} style={tabStyle(tab === 'archived')}>Completed & archived ({archived.length})</button>
      </div>

      <div style={card}>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 740 }}>
            <thead>
              <tr>
                <th style={thS}>New hire</th>
                <th style={thS}>Department</th>
                <th style={thS}>{tab === 'active' ? 'Joining date' : 'Completed on'}</th>
                {tab === 'active' ? <th style={thS}>Progress</th> : <th style={thS}>Duration</th>}
                <th style={thS}>Status</th>
                {tab === 'active' && <th style={thS}>Next due</th>}
                <th style={thS}></th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>Loading…</td></tr>
              ) : loadError ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                  <span style={{ color: '#E4373D' }}>Couldn't load onboarding flows ({loadError}).</span>{' '}
                  <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
                </td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>Nothing here yet.</td></tr>
              ) : list.map(r => {
                const pct = r.totalItems > 0 ? Math.round(100 * r.doneItems / r.totalItems) : 0;
                return (
                  <tr
                    key={r.checklistId}
                    onClick={() => setSelected(r.checklistId)}
                    style={{ cursor: 'pointer' }}
                    onMouseEnter={e => { (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'; }}
                    onMouseLeave={e => { (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'; }}
                  >
                    <td style={tdS}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <EmployeeAvatar userId={r.employeeUserId} name={r.employeeName} size={30} fontSize={11} background="var(--brand)" />
                        <div>
                          <div style={{ fontWeight: 600, color: 'var(--txt)' }}>{r.employeeName}</div>
                          <div style={{ color: 'var(--txt-dim)', fontSize: 11.5, fontFamily: 'Inter, sans-serif' }}>{r.employeeCode}</div>
                        </div>
                      </div>
                    </td>
                    <td style={tdS}>
                      {r.departmentName ?? '—'}
                      {r.designationName && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 1 }}>{r.designationName}</div>}
                    </td>
                    <td style={tdS}>{fmtDate(tab === 'active' ? r.joiningDate : r.completedDate)}</td>
                    {tab === 'active' ? (
                      <td style={tdS}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 150 }}>
                          <div style={{ flex: 1, height: 6, borderRadius: 20, background: 'var(--raised2)', overflow: 'hidden' }}>
                            <div style={{ width: `${pct}%`, height: '100%', background: pct === 100 ? '#2FB67C' : 'var(--brand)' }} />
                          </div>
                          <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>{r.doneItems}/{r.totalItems}</span>
                        </div>
                      </td>
                    ) : (
                      <td style={tdS}>{r.durationDays} days</td>
                    )}
                    <td style={tdS}><StatusPill status={r.status} /></td>
                    {tab === 'active' && (
                      <td style={tdS}>{r.nextDueLabel ?? '—'}{r.nextDueDate ? ` · ${fmtDate(r.nextDueDate)}` : ''}</td>
                    )}
                    <td style={{ ...tdS, color: 'var(--txt-dim)' }}><ChevronRight size={14} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {showModal && (
        <StartOnboardingModal
          onClose={() => setShowModal(false)}
          onCreated={(id) => { setShowModal(false); load(); setSelected(id); }}
        />
      )}
    </div>
  );
}
