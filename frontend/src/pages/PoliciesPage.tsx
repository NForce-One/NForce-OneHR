import { useEffect, useState } from 'react';
import { Plus, Megaphone, CheckCircle, Clock, Search } from 'lucide-react';
import { KebabMenu } from '../components/KebabMenu';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  listAllPolicies, publishPolicy, editPolicy, deactivatePolicy, reactivatePolicy, deletePolicy,
  listAcknowledgments, listAllAnnouncements, createAnnouncement, publishAnnouncement,
  updateAnnouncement, deactivateAnnouncement, reactivateAnnouncement, deleteAnnouncement,
  resetAcknowledgment, remindEmployee, globalPendingAckCount,
  type Policy, type PolicyAcknowledgment, type Announcement,
} from '../api/policies';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputS: React.CSSProperties = { width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box' };

const AUDIENCE_OPTIONS = [
  { value: 'EMPLOYEE', label: 'Employee' },
  { value: 'MANAGER', label: 'Manager' },
  { value: 'HR_ADMIN', label: 'HR Admin' },
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
];
const ALL_AUDIENCE = AUDIENCE_OPTIONS.map(o => o.value);

function audienceLabel(raw: string): string {
  if (!raw || raw === 'ALL' || raw === 'All Employees') return 'All';
  const parts = raw.split(',').map(s => s.trim());
  if (parts.length === 4 && ALL_AUDIENCE.every(a => parts.includes(a))) return 'All';
  return parts.map(code => AUDIENCE_OPTIONS.find(o => o.value === code)?.label ?? code).join(', ');
}

function parseAudienceToArr(raw: string): string[] {
  if (!raw || raw === 'ALL' || raw === 'All Employees') return [...ALL_AUDIENCE];
  return raw.split(',').map(s => s.trim()).filter(Boolean);
}


// ── Multi-select Audience Picker ──────────────────────────

function AudiencePicker({ value, onChange }: { value: string[]; onChange(v: string[]): void }) {
  const allSelected = ALL_AUDIENCE.every(a => value.includes(a));

  function toggle(code: string) {
    onChange(value.includes(code) ? value.filter(v => v !== code) : [...value, code]);
  }

  function toggleAll() {
    onChange(allSelected ? [] : [...ALL_AUDIENCE]);
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>
        <input type="checkbox" checked={allSelected} onChange={toggleAll} style={{ width: 15, height: 15, cursor: 'pointer' }} />
        All (every role)
      </label>
      <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, paddingLeft: 4 }}>
        {AUDIENCE_OPTIONS.map(opt => (
          <label key={opt.value} style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 13, color: 'var(--txt)' }}>
            <input type="checkbox" checked={value.includes(opt.value)} onChange={() => toggle(opt.value)} style={{ width: 15, height: 15, cursor: 'pointer' }} />
            {opt.label}
          </label>
        ))}
      </div>
    </div>
  );
}

// ── Publish Policy Modal ──────────────────────────────────

function PublishModal({ policies, onClose, onPublished }: { policies: Policy[]; onClose(): void; onPublished(p: Policy): void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [title, setTitle] = useState('');
  const [version, setVersion] = useState('1.0');
  const [description, setDescription] = useState('');
  const [audience, setAudience] = useState<string[]>([...ALL_AUDIENCE]);
  const [required, setRequired] = useState(true);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!title.trim()) { setVersion('1.0'); return; }
    const match = policies
      .filter(p => p.title.trim().toLowerCase() === title.trim().toLowerCase())
      .sort((a, b) => new Date(b.publishedAt).getTime() - new Date(a.publishedAt).getTime())[0];
    if (match) {
      const major = (parseInt(match.version.split('.')[0] ?? '1', 10) || 1) + 1;
      setVersion(`${major}.0`);
    } else {
      setVersion('1.0');
    }
  }, [title, policies]);

  const isUpdate = policies.some(p => p.title.trim().toLowerCase() === title.trim().toLowerCase());

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (audience.length === 0) return showToast('error', 'Select at least one audience');
    setLoading(true);
    try {
      const audienceStr = audience.length === 4 ? 'ALL' : audience.join(',');
      const p = await publishPolicy(token, { title, version, description, audience: audienceStr, required });
      onPublished(p);
      showToast('success', 'Policy published');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Publish failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 500, maxWidth: '94vw', maxHeight: '90vh', overflowY: 'auto' }}>
        <h3 style={{ margin: '0 0 20px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>
          {isUpdate ? 'Publish New Version' : 'Publish New Policy'}
        </h3>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Title *</label>
            <input style={inputS} value={title} onChange={e => setTitle(e.target.value)} required list="policy-titles-list" />
            <datalist id="policy-titles-list">
              {[...new Set(policies.map(p => p.title))].map(t => <option key={t} value={t} />)}
            </datalist>
            {isUpdate && <p style={{ margin: '4px 0 0', fontSize: 11, color: '#eab308' }}>Existing policy — will publish v{version}, supersede current.</p>}
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Version <span style={{ fontSize: 11 }}>(auto-suggested)</span></label>
            <input style={inputS} value={version} onChange={e => setVersion(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Description *</label>
            <textarea style={{ ...inputS, resize: 'vertical' }} rows={5} value={description} onChange={e => setDescription(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 8 }}>Audience *</label>
            <div style={{ background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px' }}>
              <AudiencePicker value={audience} onChange={setAudience} />
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 20 }}>
            <input type="checkbox" id="req" checked={required} onChange={e => setRequired(e.target.checked)} style={{ width: 16, height: 16, cursor: 'pointer' }} />
            <label htmlFor="req" style={{ fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>Required acknowledgment</label>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>Cancel</button>
            <button type="submit" disabled={loading}
              style={{ padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
              {loading ? 'Publishing…' : 'Publish'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Edit Policy Modal ─────────────────────────────────────

function EditPolicyModal({ policy, onClose, onSaved }: { policy: Policy; onClose(): void; onSaved(p: Policy): void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [title, setTitle] = useState(policy.title);
  const [description, setDescription] = useState(policy.description);
  const [audience, setAudience] = useState<string[]>(parseAudienceToArr(policy.audience));
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (audience.length === 0) return showToast('error', 'Select at least one audience');
    setLoading(true);
    try {
      const audienceStr = audience.length === 4 ? 'ALL' : audience.join(',');
      const p = await editPolicy(token, policy.id, { title, description, audience: audienceStr });
      onSaved(p);
      showToast('success', 'Policy updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Update failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 500, maxWidth: '94vw', maxHeight: '90vh', overflowY: 'auto' }}>
        <h3 style={{ margin: '0 0 20px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>Edit Policy — v{policy.version}</h3>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Title *</label>
            <input style={inputS} value={title} onChange={e => setTitle(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Description *</label>
            <textarea style={{ ...inputS, resize: 'vertical' }} rows={5} value={description} onChange={e => setDescription(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 8 }}>Audience *</label>
            <div style={{ background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px' }}>
              <AudiencePicker value={audience} onChange={setAudience} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>Cancel</button>
            <button type="submit" disabled={loading}
              style={{ padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
              {loading ? 'Saving…' : 'Save Changes'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Create/Edit Announcement Modal ────────────────────────

function AnnounceModal({ initial, onClose, onSaved }: {
  initial?: Announcement;
  onClose(): void;
  onSaved(a: Announcement): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [title, setTitle] = useState(initial?.title ?? '');
  const [body, setBody] = useState(initial?.body ?? '');
  const [audience, setAudience] = useState<string[]>(parseAudienceToArr(initial?.audience ?? 'ALL'));
  const [publishNow, setPublishNow] = useState(true);
  const [loading, setLoading] = useState(false);
  const isEdit = !!initial;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (audience.length === 0) return showToast('error', 'Select at least one audience');
    setLoading(true);
    try {
      const audienceStr = audience.length === 4 ? 'ALL' : audience.join(',');
      let a: Announcement;
      if (isEdit) {
        a = await updateAnnouncement(token, initial.id, { title, body, audience: audienceStr });
      } else {
        a = await createAnnouncement(token, { title, body, audience: audienceStr, publishNow });
      }
      onSaved(a);
      showToast('success', isEdit ? 'Announcement updated' : (publishNow ? 'Announcement published' : 'Saved as draft'));
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 500, maxWidth: '94vw', maxHeight: '90vh', overflowY: 'auto' }}>
        <h3 style={{ margin: '0 0 20px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{isEdit ? 'Edit Announcement' : 'Create Announcement'}</h3>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Title *</label>
            <input style={inputS} value={title} onChange={e => setTitle(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Body *</label>
            <textarea style={{ ...inputS, resize: 'vertical' }} rows={5} value={body} onChange={e => setBody(e.target.value)} required />
          </div>
          <div style={{ marginBottom: 18 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 8 }}>Audience *</label>
            <div style={{ background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px' }}>
              <AudiencePicker value={audience} onChange={setAudience} />
            </div>
          </div>
          {!isEdit && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 20 }}>
              <input type="checkbox" id="now" checked={publishNow} onChange={e => setPublishNow(e.target.checked)} style={{ width: 16, height: 16, cursor: 'pointer' }} />
              <label htmlFor="now" style={{ fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>Publish immediately</label>
            </div>
          )}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>Cancel</button>
            <button type="submit" disabled={loading}
              style={{ padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
              {loading ? 'Saving…' : isEdit ? 'Save Changes' : (publishNow ? 'Publish' : 'Save Draft')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Acknowledgment Drawer ─────────────────────────────────

function AckDrawer({ policy, onClose, onReset }: { policy: Policy; onClose(): void; onReset(): void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [acks, setAcks] = useState<PolicyAcknowledgment[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  useEffect(() => {
    listAcknowledgments(token, policy.id)
      .then(setAcks)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'))
      .finally(() => setLoading(false));
  }, [policy.id, token]);

  async function doReset(userId: string) {
    setActionLoading(userId);
    try {
      await resetAcknowledgment(token, policy.id, userId);
      setAcks(prev => prev.map(a => a.employeeUserId === userId ? { ...a, acknowledgedAt: null, pending: true } : a));
      onReset();
      showToast('success', 'Acknowledgment reset');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reset failed');
    } finally {
      setActionLoading(null);
    }
  }

  async function doRemind(userId: string) {
    setActionLoading(userId + '_r');
    try {
      await remindEmployee(token, policy.id, userId);
      showToast('success', 'Reminder sent');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Remind failed');
    } finally {
      setActionLoading(null);
    }
  }

  const acknowledged = acks.filter(a => !a.pending).length;
  const total = acks.length;
  const q = search.trim().toLowerCase();
  const filtered = q ? acks.filter(a => (a.employeeName ?? '').toLowerCase().includes(q)) : acks;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 600, maxWidth: '94vw', maxHeight: '80vh', display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
          <div>
            <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>{policy.title} — v{policy.version}</h3>
            {!loading && (
              <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--txt-dim)' }}>
                {acknowledged}/{total} acknowledged
                {total > 0 && <span style={{ marginLeft: 8, color: '#22c55e', fontWeight: 600 }}>{Math.round(acknowledged / total * 100)}% read rate</span>}
              </p>
            )}
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', fontSize: 20, lineHeight: 1 }}>×</button>
        </div>
        <div style={{ marginBottom: 12, position: 'relative' }}>
          <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search by name…"
            style={{ width: '100%', paddingLeft: 30, padding: '7px 12px 7px 30px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' }} />
        </div>
        <div style={{ overflowY: 'auto', overflowX: 'auto', flex: 1 }}>
          {loading ? <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={thS}>Employee</th>
                  <th style={thS}>Status</th>
                  <th style={thS}>Date</th>
                  <th style={thS}></th>
                </tr>
              </thead>
              <tbody>
                {filtered.length === 0 ? (
                  <tr><td colSpan={4} style={{ ...tdS, textAlign: 'center', padding: 24, color: 'var(--txt-dim)' }}>No results.</td></tr>
                ) : filtered.map(a => (
                  <tr key={a.id}>
                    <td style={{ ...tdS, fontWeight: 600, color: 'var(--txt)' }}>
                      {a.employeeName ?? <span style={{ fontFamily: 'Inter, sans-serif', fontSize: 11, color: 'var(--txt-dim)' }}>{a.employeeUserId.slice(0, 8)}…</span>}
                    </td>
                    <td style={tdS}>
                      {a.pending
                        ? <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: '#eab308', fontSize: 12, fontWeight: 600 }}><Clock size={12} /> Pending</span>
                        : <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: '#22c55e', fontSize: 12, fontWeight: 600 }}><CheckCircle size={12} /> Acknowledged</span>}
                    </td>
                    <td style={tdS}>{a.acknowledgedAt ? new Date(a.acknowledgedAt).toLocaleDateString() : '—'}</td>
                    <td style={{ ...tdS, width: 48 }}>
                      <KebabMenu items={[
                        ...(!a.pending ? [{ label: 'Reset Acknowledgment', onClick: () => doReset(a.employeeUserId) }] : []),
                        { label: actionLoading === a.employeeUserId + '_r' ? 'Sending…' : 'Send Reminder', onClick: () => doRemind(a.employeeUserId) },
                      ]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────

export default function PoliciesPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [tab, setTab] = useState<'policies' | 'announcements'>('policies');
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [announcements, setAnnouncements] = useState<Announcement[]>([]);
  const [pendingAckTotal, setPendingAckTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [showPublish, setShowPublish] = useState(false);
  const [showAnnounce, setShowAnnounce] = useState(false);
  const [editPolicyTarget, setEditPolicyTarget] = useState<Policy | null>(null);
  const [editAnnouncement, setEditAnnouncement] = useState<Announcement | null>(null);
  const [ackPolicy, setAckPolicy] = useState<Policy | null>(null);
  const [publishingAnn, setPublishingAnn] = useState<number | null>(null);
  const [policySearch, setPolicySearch] = useState('');
  const [policyFilter, setPolicyFilter] = useState<'all' | 'required' | 'optional'>('all');
  const [policyAudienceFilter, setPolicyAudienceFilter] = useState('');
  const [annSearch, setAnnSearch] = useState('');
  const [annStatusFilter, setAnnStatusFilter] = useState<'all' | 'published' | 'draft'>('all');
  const [annAudienceFilter, setAnnAudienceFilter] = useState('');

  useEffect(() => {
    setLoading(true);
    Promise.all([listAllPolicies(token), listAllAnnouncements(token), globalPendingAckCount(token)])
      .then(([p, a, count]) => { setPolicies(p); setAnnouncements(a); setPendingAckTotal(count); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'))
      .finally(() => setLoading(false));
  }, [token]);

  function refreshPendingAckTotal() {
    globalPendingAckCount(token)
      .then(setPendingAckTotal)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'));
  }

  async function doPublishAnn(id: number) {
    setPublishingAnn(id);
    try {
      const updated = await publishAnnouncement(token, id);
      setAnnouncements(prev => prev.map(a => a.id === id ? updated : a));
      showToast('success', 'Announcement published');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    } finally {
      setPublishingAnn(null);
    }
  }

  async function doDeactivatePolicy(id: number) {
    try {
      const updated = await deactivatePolicy(token, id);
      setPolicies(prev => prev.map(p => p.id === id ? updated : p));
      showToast('success', 'Policy deactivated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    }
  }

  async function doReactivatePolicy(id: number) {
    try {
      const updated = await reactivatePolicy(token, id);
      setPolicies(prev => prev.map(p => p.id === id ? updated : p));
      showToast('success', 'Policy reactivated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    }
  }

  async function doDeletePolicy(id: number) {
    try {
      await deletePolicy(token, id);
      setPolicies(prev => prev.filter(p => p.id !== id));
      showToast('success', 'Policy deleted');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  async function doDeactivateAnn(id: number) {
    try {
      const updated = await deactivateAnnouncement(token, id);
      setAnnouncements(prev => prev.map(a => a.id === id ? updated : a));
      showToast('success', 'Announcement deactivated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    }
  }

  async function doReactivateAnn(id: number) {
    try {
      const updated = await reactivateAnnouncement(token, id);
      setAnnouncements(prev => prev.map(a => a.id === id ? updated : a));
      showToast('success', 'Announcement reactivated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed');
    }
  }

  async function doDeleteAnn(id: number) {
    try {
      await deleteAnnouncement(token, id);
      setAnnouncements(prev => prev.filter(a => a.id !== id));
      showToast('success', 'Announcement deleted');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  const tabStyle = (t: typeof tab): React.CSSProperties => ({
    padding: '8px 18px', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 13,
    background: tab === t ? '#A01418' : 'transparent', color: tab === t ? '#fff' : 'var(--txt-dim)',
    display: 'flex', alignItems: 'center', gap: 6,
  });

  const activePolicies = policies.filter(p => p.active).length;
  const uniqueAudiences = [...new Set(announcements.map(a => a.audience))];

  const pq = policySearch.trim().toLowerCase();
  const filteredPolicies = policies.filter(p => {
    if (policyFilter === 'required' && !p.required) return false;
    if (policyFilter === 'optional' && p.required) return false;
    if (policyAudienceFilter) {
      const aud = parseAudienceToArr(p.audience);
      if (!aud.includes(policyAudienceFilter)) return false;
    }
    if (pq && !p.title.toLowerCase().includes(pq) && !p.audience.toLowerCase().includes(pq)) return false;
    return true;
  });

  const aq = annSearch.trim().toLowerCase();
  const filteredAnn = announcements.filter(a => {
    if (annStatusFilter === 'published' && !a.published) return false;
    if (annStatusFilter === 'draft' && a.published) return false;
    if (annAudienceFilter && !parseAudienceToArr(a.audience).includes(annAudienceFilter)) return false;
    if (aq && !a.title.toLowerCase().includes(aq)) return false;
    return true;
  });

  if (loading) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  return (
    <div>
      <div className="nf-policy-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 22 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 800, margin: 0, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Policies & Announcements</h1>
          <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginTop: 4 }}>Publish company policies and broadcast announcements.</p>
        </div>
        <div className="nf-policy-actions" style={{ display: 'flex', gap: 10 }}>
          <button onClick={() => setShowAnnounce(true)}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
            <Megaphone size={14} /> New Announcement
          </button>
          <button onClick={() => setShowPublish(true)}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px', background: '#A01418', border: 'none', borderRadius: 7, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
            <Plus size={14} /> New Policy
          </button>
        </div>
      </div>

      {/* KPI tiles */}
      <div className="nf-policy-kpi" style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 12, marginBottom: 24 }}>
        {[
          { label: 'Active Policies', value: activePolicies, color: '#22c55e' },
          { label: 'Pending Acknowledgments', value: pendingAckTotal, color: '#eab308' },
          { label: 'Total Announcements', value: announcements.length, color: '#3b82f6' },
        ].map(k => (
          <div key={k.label} className="nf-policy-kpi-tile" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 20px' }}>
            <div className="nf-policy-kpi-value" style={{ fontSize: 26, fontWeight: 800, color: k.color, fontFamily: 'Inter, sans-serif' }}>{k.value}</div>
            <div className="nf-policy-kpi-label" style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4, fontWeight: 600 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {/* Tabs — colored badge style */}
      <div className="nf-tab-scroll" style={{ display: 'flex', gap: 6, marginBottom: 18, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 4, width: 'fit-content', maxWidth: '100%' }}>
        <button style={tabStyle('policies')} onClick={() => setTab('policies')}>
          Policies
          {policies.length > 0 && (
            <span style={{ background: tab === 'policies' ? 'rgba(255,255,255,.25)' : '#22c55e', color: tab === 'policies' ? '#fff' : '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 7px' }}>
              {policies.length}
            </span>
          )}
        </button>
        <button style={tabStyle('announcements')} onClick={() => setTab('announcements')}>
          Announcements
          {announcements.length > 0 && (
            <span style={{ background: tab === 'announcements' ? 'rgba(255,255,255,.25)' : '#3b82f6', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 7px' }}>
              {announcements.length}
            </span>
          )}
        </button>
      </div>

      {/* ── Policies ── */}
      {tab === 'policies' && (
        <>
          <div style={{ display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center', flexWrap: 'wrap' }}>
            <div style={{ position: 'relative' }}>
              <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
              <input value={policySearch} onChange={e => setPolicySearch(e.target.value)} placeholder="Search policies…"
                style={{ paddingLeft: 30, padding: '7px 12px 7px 30px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, width: 220, outline: 'none' }} />
            </div>
            <select value={policyFilter} onChange={e => setPolicyFilter(e.target.value as any)}
              style={{ padding: '7px 12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}>
              <option value="all">All types</option>
              <option value="required">Required</option>
              <option value="optional">Optional</option>
            </select>
            <select value={policyAudienceFilter} onChange={e => setPolicyAudienceFilter(e.target.value)}
              style={{ padding: '7px 12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}>
              <option value="">All audiences</option>
              {AUDIENCE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div style={card}>
            <div className="nf-doc-table-scroll">
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={thS}>Title</th>
                    <th style={thS}>Version</th>
                    <th style={thS}>Audience</th>
                    <th style={thS}>Published</th>
                    <th style={thS}>Status</th>
                    <th style={thS}>Actions</th>
                    <th style={thS}></th>
                  </tr>
                </thead>
                <tbody>
                  {filteredPolicies.length === 0 ? (
                    <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                      {pq || policyFilter !== 'all' || policyAudienceFilter ? 'No policies match the filter.' : 'No policies yet.'}
                    </td></tr>
                  ) : filteredPolicies.map(p => (
                    <tr key={p.id}>
                      <td style={{ ...tdS, fontWeight: 600, color: 'var(--txt)' }}>
                        {p.title}
                        {p.required && <span style={{ marginLeft: 8, fontSize: 10, background: 'rgba(239,68,68,.12)', color: '#ef4444', borderRadius: 3, padding: '1px 6px', fontWeight: 700 }}>Required</span>}
                      </td>
                      <td style={tdS}>v{p.version}</td>
                      <td style={tdS}><span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{audienceLabel(p.audience)}</span></td>
                      <td style={tdS}>{new Date(p.publishedAt).toLocaleDateString()}</td>
                      <td style={tdS}>
                        {p.active
                          ? <span style={{ color: '#22c55e', fontSize: 12, fontWeight: 600 }}>Active</span>
                          : <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Inactive</span>}
                      </td>
                      <td style={tdS}>
                        <button onClick={() => setAckPolicy(p)}
                          style={{ padding: '5px 12px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 5, color: 'var(--txt)', cursor: 'pointer', fontSize: 12, whiteSpace: 'nowrap' }}>
                          View Acknowledgments
                        </button>
                      </td>
                      <td style={{ ...tdS, width: 48 }}>
                        <KebabMenu items={[
                          { label: 'Edit', onClick: () => setEditPolicyTarget(p) },
                          ...(p.active ? [{ label: 'Deactivate', onClick: () => doDeactivatePolicy(p.id) }] : [{ label: 'Reactivate', onClick: () => doReactivatePolicy(p.id) }]),
                          { label: 'Delete', onClick: () => doDeletePolicy(p.id), danger: true },
                        ]} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}

      {/* ── Announcements ── */}
      {tab === 'announcements' && (
        <>
          <div style={{ display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center', flexWrap: 'wrap' }}>
            <div style={{ position: 'relative' }}>
              <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
              <input value={annSearch} onChange={e => setAnnSearch(e.target.value)} placeholder="Search announcements…"
                style={{ paddingLeft: 30, padding: '7px 12px 7px 30px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, width: 220, outline: 'none' }} />
            </div>
            <select value={annStatusFilter} onChange={e => setAnnStatusFilter(e.target.value as any)}
              style={{ padding: '7px 12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}>
              <option value="all">All statuses</option>
              <option value="published">Published</option>
              <option value="draft">Draft</option>
            </select>
            {uniqueAudiences.length > 1 && (
              <select value={annAudienceFilter} onChange={e => setAnnAudienceFilter(e.target.value)}
                style={{ padding: '7px 12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}>
                <option value="">All audiences</option>
                {AUDIENCE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            )}
          </div>
          <div style={card}>
            <div className="nf-doc-table-scroll">
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={thS}>Title</th>
                    <th style={thS}>Audience</th>
                    <th style={thS}>Created</th>
                    <th style={thS}>Published</th>
                    <th style={thS}>Visible</th>
                    <th style={thS}></th>
                  </tr>
                </thead>
                <tbody>
                  {filteredAnn.length === 0 ? (
                    <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                      {aq || annStatusFilter !== 'all' || annAudienceFilter ? 'No announcements match the filter.' : 'No announcements yet.'}
                    </td></tr>
                  ) : filteredAnn.map(a => (
                    <tr key={a.id}>
                      <td style={{ ...tdS, fontWeight: 600, color: 'var(--txt)', maxWidth: 220 }}>
                        <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.title}</div>
                      </td>
                      <td style={tdS}><span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{audienceLabel(a.audience)}</span></td>
                      <td style={tdS}>{new Date(a.createdAt).toLocaleDateString()}</td>
                      <td style={tdS}>
                        {a.publishedAt
                          ? <span style={{ color: '#22c55e', fontSize: 12, fontWeight: 600 }}>{new Date(a.publishedAt).toLocaleDateString()}</span>
                          : <span style={{ color: '#eab308', fontSize: 12, fontWeight: 600 }}>Draft</span>}
                      </td>
                      <td style={tdS}>
                        <span style={{ fontSize: 12, fontWeight: 600, color: a.active ? '#22c55e' : 'var(--txt-dim)' }}>
                          {a.active ? 'Visible' : 'Hidden'}
                        </span>
                      </td>
                      <td style={{ ...tdS, width: 48 }}>
                        <KebabMenu items={[
                          ...(!a.published ? [{ label: publishingAnn === a.id ? 'Publishing…' : 'Publish Now', onClick: () => doPublishAnn(a.id) }] : []),
                          { label: 'Edit', onClick: () => setEditAnnouncement(a) },
                          ...(a.active ? [{ label: 'Deactivate', onClick: () => doDeactivateAnn(a.id) }] : [{ label: 'Reactivate', onClick: () => doReactivateAnn(a.id) }]),
                          { label: 'Delete', onClick: () => doDeleteAnn(a.id), danger: true },
                        ]} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}

      {showPublish && (
        <PublishModal
          policies={policies}
          onClose={() => setShowPublish(false)}
          onPublished={p => { setPolicies(prev => [p, ...prev.map(old => old.title === p.title ? { ...old, active: false } : old)]); refreshPendingAckTotal(); }}
        />
      )}
      {showAnnounce && (
        <AnnounceModal
          onClose={() => setShowAnnounce(false)}
          onSaved={a => setAnnouncements(prev => [a, ...prev])}
        />
      )}
      {editPolicyTarget && (
        <EditPolicyModal
          policy={editPolicyTarget}
          onClose={() => setEditPolicyTarget(null)}
          onSaved={updated => { setPolicies(prev => prev.map(p => p.id === updated.id ? updated : p)); setEditPolicyTarget(null); }}
        />
      )}
      {editAnnouncement && (
        <AnnounceModal
          initial={editAnnouncement}
          onClose={() => setEditAnnouncement(null)}
          onSaved={updated => { setAnnouncements(prev => prev.map(a => a.id === updated.id ? updated : a)); setEditAnnouncement(null); }}
        />
      )}
      {ackPolicy && <AckDrawer policy={ackPolicy} onClose={() => setAckPolicy(null)} onReset={refreshPendingAckTotal} />}
    </div>
  );
}
