import { useEffect, useRef, useState } from 'react';
import { CheckCircle, Clock, Upload, XCircle, AlertTriangle, Eye, Search } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  myDocuments, myRequiredDocuments, uploadDocument, listActiveDocTypes, fetchDocumentFile,
  type EmployeeDocument, type RequiredDocument, type DocumentType,
} from '../api/documents';
import { myPolicies, acknowledgePolicy, publishedAnnouncements, type Policy, type Announcement } from '../api/policies';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

function StatusBadge({ status }: { status: string | null }) {
  if (!status) return <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Not uploaded</span>;
  const map: Record<string, { label: string; color: string }> = {
    VERIFIED: { label: 'Verified', color: '#22c55e' },
    PENDING_VERIFICATION: { label: 'Pending Review', color: '#eab308' },
    REJECTED: { label: 'Rejected', color: '#ef4444' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)' };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: cfg.color }}>
      {status === 'VERIFIED' && <CheckCircle size={12} />}
      {status === 'PENDING_VERIFICATION' && <Clock size={12} />}
      {status === 'REJECTED' && <XCircle size={12} />}
      {cfg.label}
    </span>
  );
}

// ── Upload Modal ──────────────────────────────────────────

function UploadModal({
  docType, existing, onClose, onUploaded,
}: {
  docType: DocumentType | RequiredDocument;
  existing?: EmployeeDocument | null;
  onClose(): void;
  onUploaded(doc: EmployeeDocument): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [issueDate, setIssueDate] = useState(existing?.issueDate?.split('T')[0] ?? '');
  const [expiryDate, setExpiryDate] = useState(existing?.expiryDate?.split('T')[0] ?? '');
  const [loading, setLoading] = useState(false);

  const dateError = issueDate && expiryDate && expiryDate < issueDate
    ? 'Expiry date cannot be earlier than issue date'
    : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const file = fileRef.current?.files?.[0];
    if (!file) return showToast('error', 'Please select a file');
    if (dateError) return showToast('error', dateError);
    setLoading(true);
    try {
      const doc = await uploadDocument(token, {
        documentTypeId: (docType as any).id ?? (docType as any).documentTypeId,
        file,
        issueDate: issueDate || null,
        expiryDate: expiryDate || null,
      });
      onUploaded(doc);
      showToast('success', existing ? 'Document re-uploaded' : 'Document uploaded');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setLoading(false);
    }
  }

  const requiresExpiry = (docType as any).requiresExpiryDate;
  const name = (docType as any).name ?? (docType as any).documentTypeName;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 420, maxWidth: '94vw' }}>
        <h3 style={{ margin: '0 0 18px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{existing ? 'Re-upload' : 'Upload'}: {name}</h3>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>File *</label>
            <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx" required
              style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
          </div>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 18 }}>
            <div>
              <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Issue Date</label>
              <input type="date" value={issueDate} onChange={e => setIssueDate(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Expiry Date {requiresExpiry ? '*' : ''}</label>
              <input type="date" value={expiryDate} onChange={e => setExpiryDate(e.target.value)} required={requiresExpiry}
                min={issueDate || undefined}
                style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: `1px solid ${dateError ? '#ef4444' : 'var(--line)'}`, borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
            </div>
          </div>
          {dateError && (
            <p style={{ margin: '-10px 0 14px', fontSize: 12, color: '#ef4444' }}>{dateError}</p>
          )}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
              Cancel
            </button>
            <button type="submit" disabled={loading || !!dateError}
              style={{ padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: loading || dateError ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 600, opacity: loading || dateError ? .7 : 1 }}>
              {loading ? 'Uploading…' : 'Upload'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Policy Read-First Modal ───────────────────────────────

function AcknowledgeModal({ policy, onConfirm, onClose }: {
  policy: Policy;
  onConfirm(): Promise<void>;
  onClose(): void;
}) {
  const [checked, setChecked] = useState(false);
  const [loading, setLoading] = useState(false);

  async function submit() {
    setLoading(true);
    try { await onConfirm(); } finally { setLoading(false); }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 520, maxWidth: '94vw', maxHeight: '85vh', display: 'flex', flexDirection: 'column' }}>
        <h3 style={{ margin: '0 0 6px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{policy.title}</h3>
        <p style={{ margin: '0 0 14px', fontSize: 12, color: 'var(--txt-dim)' }}>Version {policy.version} · Audience: {policy.audience} · Published {new Date(policy.publishedAt).toLocaleDateString()}</p>
        <div style={{ flex: 1, overflowY: 'auto', padding: '14px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 8, marginBottom: 18, fontSize: 13, color: 'var(--txt)', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>
          {policy.description}
        </div>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer', marginBottom: 20 }}>
          <input type="checkbox" checked={checked} onChange={e => setChecked(e.target.checked)}
            style={{ width: 16, height: 16, marginTop: 2, flexShrink: 0, cursor: 'pointer' }} />
          <span style={{ fontSize: 13, color: 'var(--txt)', lineHeight: 1.5 }}>
            I have read and understood this policy and agree to comply with its terms.
          </span>
        </label>
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button onClick={onClose} disabled={loading}
            style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
            Cancel
          </button>
          <button onClick={submit} disabled={!checked || loading}
            style={{ padding: '8px 20px', background: checked ? '#A01418' : 'var(--shell)', border: checked ? 'none' : '1px solid var(--line)', borderRadius: 6, color: checked ? '#fff' : 'var(--txt-dim)', cursor: checked ? 'pointer' : 'not-allowed', fontSize: 13, fontWeight: 600, transition: 'all .15s' }}>
            {loading ? 'Saving…' : 'Confirm Acknowledgment'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── File View helper ──────────────────────────────────────

function ViewButton({ docId }: { docId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);

  async function open() {
    setLoading(true);
    try {
      const url = await fetchDocumentFile(token, docId);
      window.open(url, '_blank');
    } catch {
      showToast('error', 'Could not open file');
    } finally {
      setLoading(false);
    }
  }

  return (
    <button onClick={open} disabled={loading}
      style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 5, color: 'var(--txt)', cursor: 'pointer', fontSize: 12 }}>
      <Eye size={12} /> {loading ? '…' : 'View'}
    </button>
  );
}

// ── Main Page ─────────────────────────────────────────────

export default function DocumentsPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [tab, setTab] = useState<'docs' | 'policies' | 'announcements'>('docs');
  const [section, setSection] = useState<'verified' | 'pending' | 'missing'>('pending');
  const [search, setSearch] = useState('');
  const [required, setRequired] = useState<RequiredDocument[]>([]);
  const [myDocs, setMyDocs] = useState<EmployeeDocument[]>([]);
  const [docTypes, setDocTypes] = useState<DocumentType[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [announcements, setAnnouncements] = useState<Announcement[]>([]);
  const [uploadTarget, setUploadTarget] = useState<{ type: RequiredDocument | DocumentType; existing: EmployeeDocument | null } | null>(null);
  const [ackTarget, setAckTarget] = useState<Policy | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      myRequiredDocuments(token),
      myDocuments(token),
      listActiveDocTypes(token),
      myPolicies(token),
      publishedAnnouncements(token),
    ]).then(([req, docs, types, pol, ann]) => {
      setRequired(req);
      setMyDocs(docs);
      setDocTypes(types);
      setPolicies(pol);
      setAnnouncements(ann);
    }).catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'))
      .finally(() => setLoading(false));
  }, [token]);

  async function confirmAck(policyId: number) {
    await acknowledgePolicy(token, policyId);
    setPolicies(prev => prev.map(p => p.id === policyId ? { ...p, acknowledged: true, acknowledgedAt: new Date().toISOString() } : p));
    showToast('success', 'Policy acknowledged');
    setAckTarget(null);
  }

  function docForType(typeId: number): EmployeeDocument | null {
    return myDocs.find(d => d.documentTypeId === typeId) ?? null;
  }

  // KPI data
  const verified = required.filter(r => r.status === 'VERIFIED');
  const pending = required.filter(r => r.status === 'PENDING_VERIFICATION' || r.status === 'REJECTED');
  const missing = required.filter(r => !r.uploaded);
  const pendingPolicies = policies.filter(p => p.required && p.acknowledged === false);

  const tabStyle = (t: typeof tab): React.CSSProperties => ({
    padding: '8px 20px', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 13,
    background: tab === t ? '#A01418' : 'transparent', color: tab === t ? '#fff' : 'var(--txt-dim)',
  });

  const secStyle = (s: typeof section): React.CSSProperties => ({
    padding: '6px 16px', borderRadius: 5, cursor: 'pointer', fontWeight: 600, fontSize: 12,
    background: section === s ? 'var(--txt)' : 'var(--shell)',
    color: section === s ? 'var(--panel)' : 'var(--txt-dim)',
    border: '1px solid var(--line)',
  });

  // Filtered docs for current section
  const sectionDocs = section === 'verified' ? verified : section === 'pending' ? pending : missing;
  const q = search.trim().toLowerCase();
  const filteredDocs = q ? sectionDocs.filter(r => r.documentTypeName.toLowerCase().includes(q)) : sectionDocs;

  if (loading) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  return (
    <div>
      <h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>My Documents & Policies</h1>
      <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 22 }}>Manage your required documents and acknowledge company policies.</p>

      {/* KPI tiles */}
      <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12, marginBottom: 24 }}>
        {[
          { label: 'Required', value: required.length, color: 'var(--txt)' },
          { label: 'Verified', value: verified.length, color: '#22c55e' },
          { label: 'Pending Review', value: pending.length, color: '#eab308' },
          { label: 'Not Submitted', value: missing.length, color: '#ef4444' },
        ].map(k => (
          <div key={k.label} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 20px' }}>
            <div style={{ fontSize: 26, fontWeight: 800, color: k.color, fontFamily: 'Inter, sans-serif' }}>{k.value}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4, fontWeight: 600 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {pendingPolicies.length > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'rgba(234,179,8,.12)', border: '1px solid rgba(234,179,8,.3)', borderRadius: 8, padding: '9px 14px', marginBottom: 18, fontSize: 13 }}>
          <AlertTriangle size={14} color="#eab308" />
          <span><strong style={{ color: '#eab308' }}>{pendingPolicies.length} required polic{pendingPolicies.length > 1 ? 'ies' : 'y'}</strong> need acknowledgment.</span>
        </div>
      )}

      <div className="nf-doc-tabs" style={{ display: 'flex', gap: 6, marginBottom: 20, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 4, width: 'fit-content' }}>
        <button className="nf-doc-tab-btn" style={tabStyle('docs')} onClick={() => setTab('docs')}>
          <span className="nf-doc-tab-label">My Documents</span>
        </button>
        <button className="nf-doc-tab-btn" style={tabStyle('policies')} onClick={() => setTab('policies')}>
          <span className="nf-doc-tab-label">Policies</span> {pendingPolicies.length > 0 && <span style={{ background: '#A01418', color: '#fff', borderRadius: '50%', fontSize: 10, fontWeight: 700, padding: '1px 6px', marginLeft: 6 }}>{pendingPolicies.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={tabStyle('announcements')} onClick={() => setTab('announcements')}>
          <span className="nf-doc-tab-label">Announcements</span>
        </button>
      </div>

      {/* ── My Documents Tab ── */}
      {tab === 'docs' && (
        <>
          <div className="nf-doc-tabs" style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
            <button className="nf-doc-tab-btn" style={secStyle('pending')} onClick={() => setSection('pending')}>
              <span className="nf-doc-tab-label">Pending Review</span> {pending.length > 0 && <span style={{ marginLeft: 4, background: '#eab308', color: '#000', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{pending.length}</span>}
            </button>
            <button className="nf-doc-tab-btn" style={secStyle('verified')} onClick={() => setSection('verified')}>
              <span className="nf-doc-tab-label">Verified</span> {verified.length > 0 && <span style={{ marginLeft: 4, background: '#22c55e', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{verified.length}</span>}
            </button>
            <button className="nf-doc-tab-btn" style={secStyle('missing')} onClick={() => setSection('missing')}>
              <span className="nf-doc-tab-label">Not Submitted</span> {missing.length > 0 && <span style={{ marginLeft: 4, background: '#ef4444', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{missing.length}</span>}
            </button>
          </div>

          <div className="nf-search-full-mobile" style={{ position: 'relative', marginBottom: 14 }}>
            <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search documents…"
              className="nf-search-full-mobile-input"
              style={{ paddingLeft: 28, padding: '6px 10px 6px 28px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 12, width: 200, outline: 'none' }} />
          </div>

          <div style={card}>
            <div className="nf-doc-table-scroll">
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={thS}>Document</th>
                    <th style={thS}>Status</th>
                    <th style={thS}>Expiry</th>
                    <th style={thS}>Rejection Reason</th>
                    <th style={thS}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredDocs.length === 0 ? (
                    <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                      {q ? 'No results.' : section === 'verified' ? 'No verified documents yet.' : section === 'pending' ? 'No documents pending review.' : 'All required documents submitted!'}
                    </td></tr>
                  ) : filteredDocs.map(r => {
                    const doc = docForType(r.documentTypeId);
                    return (
                      <tr key={r.documentTypeId}>
                        <td style={tdS}>
                          <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{r.documentTypeName}</div>
                          {r.requiresVerification && <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Requires HR verification</div>}
                        </td>
                        <td style={tdS}><StatusBadge status={r.status} /></td>
                        <td style={tdS}>
                          {doc?.expiryDate ? (
                            <span style={{ color: r.expiringSoon ? '#eab308' : 'var(--txt-mut)', fontSize: 13 }}>
                              {r.expiringSoon && <AlertTriangle size={12} style={{ marginRight: 4 }} />}
                              {new Date(doc.expiryDate).toLocaleDateString()}
                            </span>
                          ) : '—'}
                        </td>
                        <td style={tdS}>
                          {doc?.rejectionReason
                            ? <span style={{ color: '#ef4444', fontSize: 12 }}>{doc.rejectionReason}</span>
                            : '—'}
                        </td>
                        <td style={tdS}>
                          <div style={{ display: 'flex', gap: 8 }}>
                            <button onClick={() => setUploadTarget({ type: r, existing: doc })}
                              style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: '#A01418', border: 'none', borderRadius: 5, color: '#fff', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
                              <Upload size={12} /> {doc ? 'Re-upload' : 'Upload'}
                            </button>
                            {doc && <ViewButton docId={doc.id} />}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Upload from docTypes for any not in required list */}
          {docTypes.length > required.length && (
            <div style={{ marginTop: 20, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Upload additional document:</span>
              <select onChange={e => {
                const dt = docTypes.find(d => d.id === Number(e.target.value));
                if (dt) setUploadTarget({ type: dt, existing: myDocs.find(d => d.documentTypeId === dt.id) ?? null });
                e.target.value = '';
              }} defaultValue=""
                style={{ padding: '6px 10px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 12, cursor: 'pointer' }}>
                <option value="" disabled>Select document type…</option>
                {docTypes.filter(dt => !required.find(r => r.documentTypeId === dt.id)).map(dt => (
                  <option key={dt.id} value={dt.id}>{dt.name}</option>
                ))}
              </select>
            </div>
          )}
        </>
      )}

      {/* ── Policies Tab ── */}
      {tab === 'policies' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {policies.length === 0 && <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No active policies.</p>}
          {policies.map(p => (
            <div key={p.id} style={{ ...card, padding: 20 }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
                    <span style={{ fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>{p.title}</span>
                    <span style={{ fontSize: 11, background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 8px', color: 'var(--txt-dim)' }}>v{p.version}</span>
                    {p.required && <span style={{ fontSize: 11, background: 'rgba(239,68,68,.12)', color: '#ef4444', borderRadius: 4, padding: '2px 8px', fontWeight: 600 }}>Required</span>}
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--txt-dim)', margin: '0 0 10px', lineHeight: 1.6 }}>{p.description}</p>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                    Published {new Date(p.publishedAt).toLocaleDateString()} · Audience: {p.audience}
                    {p.acknowledgedAt && <span style={{ marginLeft: 12, color: '#22c55e' }}>✓ Acknowledged {new Date(p.acknowledgedAt).toLocaleDateString()}</span>}
                  </div>
                </div>
                <div style={{ flexShrink: 0 }}>
                  {p.acknowledged === false ? (
                    <button onClick={() => setAckTarget(p)}
                      style={{ padding: '7px 16px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
                      Review & Acknowledge
                    </button>
                  ) : (
                    <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: '#22c55e', fontSize: 13, fontWeight: 600 }}>
                      <CheckCircle size={14} /> Acknowledged
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Announcements Tab ── */}
      {tab === 'announcements' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {announcements.length === 0 && <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No announcements yet.</p>}
          {announcements.map(a => (
            <div key={a.id} style={{ ...card, padding: 20 }}>
              <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--txt)', marginBottom: 6 }}>{a.title}</div>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 10px', lineHeight: 1.6 }}>{a.body}</p>
              <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                {a.publishedAt && `Published ${new Date(a.publishedAt).toLocaleDateString()}`} · Audience: {a.audience}
              </div>
            </div>
          ))}
        </div>
      )}

      {uploadTarget && (
        <UploadModal
          key={`${(uploadTarget.type as any).id ?? (uploadTarget.type as any).documentTypeId}-${Date.now()}`}
          docType={uploadTarget.type}
          existing={uploadTarget.existing}
          onClose={() => setUploadTarget(null)}
          onUploaded={doc => {
            setMyDocs(prev => {
              const idx = prev.findIndex(d => d.documentTypeId === doc.documentTypeId);
              return idx >= 0 ? prev.map((d, i) => i === idx ? doc : d) : [doc, ...prev];
            });
            setRequired(prev => prev.map(r => r.documentTypeId === doc.documentTypeId
              ? { ...r, uploaded: true, status: doc.status } : r));
          }}
        />
      )}

      {ackTarget && (
        <AcknowledgeModal
          policy={ackTarget}
          onConfirm={() => confirmAck(ackTarget.id)}
          onClose={() => setAckTarget(null)}
        />
      )}
    </div>
  );
}
