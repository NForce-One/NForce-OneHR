import { useEffect, useState } from 'react';
import { CheckCircle, Clock, XCircle, Eye, Search, Users } from 'lucide-react';
import { KebabMenu } from '../components/KebabMenu';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  listAllDocuments, listPendingDocuments, verifyDocument, getAdminKpis, fetchDocumentFile, listMissingDocuments, remindMissingDocument,
  type EmployeeDocument, type DocumentAdminKpi, type MissingDocument,
} from '../api/documents';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; color: string }> = {
    VERIFIED: { label: 'Verified', color: '#22c55e' },
    PENDING_VERIFICATION: { label: 'Pending Review', color: '#eab308' },
    REJECTED: { label: 'Rejected', color: '#ef4444' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)' };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 600, color: cfg.color }}>
      {status === 'VERIFIED' && <CheckCircle size={12} />}
      {status === 'PENDING_VERIFICATION' && <Clock size={12} />}
      {status === 'REJECTED' && <XCircle size={12} />}
      {cfg.label}
    </span>
  );
}


// ── Document Detail Modal (shows file + approve/reject) ───

function DetailModal({
  doc,
  onVerify,
  onReject,
  onClose,
}: {
  doc: EmployeeDocument;
  onVerify(): Promise<void>;
  onReject(reason: string): Promise<void>;
  onClose(): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [fileLoading, setFileLoading] = useState(true);
  const [fileError, setFileError] = useState(false);
  const [mode, setMode] = useState<'view' | 'reject'>('view');
  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);

  const ext = doc.fileName.split('.').pop()?.toLowerCase() ?? '';
  const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext);
  const isPdf = ext === 'pdf';

  useEffect(() => {
    fetchDocumentFile(token, doc.id)
      .then(url => setBlobUrl(url))
      .catch(() => setFileError(true))
      .finally(() => setFileLoading(false));
    return () => { if (blobUrl) URL.revokeObjectURL(blobUrl); };
  }, [doc.id, token]);

  async function handleVerify() {
    setActing(true);
    try { await onVerify(); } finally { setActing(false); }
  }

  async function handleReject(e: React.FormEvent) {
    e.preventDefault();
    if (!reason.trim()) return;
    setActing(true);
    try { await onReject(reason); } finally { setActing(false); }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 680, maxWidth: '94vw', maxHeight: '90vh', display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{doc.documentTypeName}</h3>
            <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--txt-dim)' }}>
              {doc.employeeName ?? doc.employeeUserId.slice(0, 8)} · Uploaded {new Date(doc.uploadedAt).toLocaleDateString()} · {doc.fileName}
            </p>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', fontSize: 22, lineHeight: 1, padding: 0 }}>×</button>
        </div>

        {/* Document preview */}
        <div style={{ flex: 1, minHeight: 320, background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {fileLoading && <span style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Loading document…</span>}
          {fileError && (
            <div style={{ textAlign: 'center' }}>
              <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 10 }}>Preview unavailable</p>
              {blobUrl && <a href={blobUrl} target="_blank" rel="noreferrer"
                style={{ display: 'flex', alignItems: 'center', gap: 5, color: '#A01418', textDecoration: 'none', fontSize: 13, justifyContent: 'center' }}>
                <Eye size={14} /> Open file
              </a>}
            </div>
          )}
          {!fileLoading && !fileError && blobUrl && (
            isPdf ? (
              <iframe src={blobUrl} style={{ width: '100%', height: 380, border: 'none' }} title={doc.fileName} />
            ) : isImage ? (
              <img src={blobUrl} alt={doc.fileName} style={{ maxWidth: '100%', maxHeight: 380, objectFit: 'contain' }} />
            ) : (
              <div style={{ textAlign: 'center' }}>
                <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 10 }}>No inline preview for this file type</p>
                <a href={blobUrl} target="_blank" rel="noreferrer"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: '#A01418', textDecoration: 'none', fontSize: 13, fontWeight: 600 }}>
                  <Eye size={14} /> Open file
                </a>
              </div>
            )
          )}
        </div>

        {/* Expiry / issue dates */}
        {(doc.issueDate || doc.expiryDate) && (
          <div style={{ display: 'flex', gap: 20, fontSize: 12, color: 'var(--txt-dim)' }}>
            {doc.issueDate && <span>Issue date: <strong style={{ color: 'var(--txt)' }}>{new Date(doc.issueDate).toLocaleDateString()}</strong></span>}
            {doc.expiryDate && <span>Expiry: <strong style={{ color: 'var(--txt)' }}>{new Date(doc.expiryDate).toLocaleDateString()}</strong></span>}
          </div>
        )}

        {/* Status badge for already-actioned docs */}
        {doc.status !== 'PENDING_VERIFICATION' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <StatusBadge status={doc.status} />
            {doc.rejectionReason && <span style={{ fontSize: 12, color: '#ef4444' }}>Reason: {doc.rejectionReason}</span>}
          </div>
        )}

        {/* Actions — only for pending docs */}
        {doc.status === 'PENDING_VERIFICATION' && (
          mode === 'view' ? (
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              <button onClick={onClose} disabled={acting}
                style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
                Close
              </button>
              <button onClick={() => setMode('reject')} disabled={acting}
                style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid #ef4444', borderRadius: 6, color: '#ef4444', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
                Reject
              </button>
              <button onClick={handleVerify} disabled={acting || fileLoading}
                style={{ padding: '8px 20px', background: '#22c55e', border: 'none', borderRadius: 6, color: '#fff', cursor: acting || fileLoading ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 600, opacity: acting || fileLoading ? .7 : 1 }}>
                {acting ? 'Verifying…' : 'Approve'}
              </button>
            </div>
          ) : (
            <form onSubmit={handleReject}>
              <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 6 }}>Rejection reason *</label>
              <textarea value={reason} onChange={e => setReason(e.target.value)} rows={3} required
                style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13, resize: 'vertical', boxSizing: 'border-box', marginBottom: 12 }} />
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" onClick={() => setMode('view')} disabled={acting}
                  style={{ padding: '8px 18px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
                  Back
                </button>
                <button type="submit" disabled={acting || !reason.trim()}
                  style={{ padding: '8px 18px', background: '#ef4444', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
                  {acting ? 'Rejecting…' : 'Confirm Reject'}
                </button>
              </div>
            </form>
          )
        )}

        {doc.status !== 'PENDING_VERIFICATION' && (
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button onClick={onClose}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
              Close
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Not Submitted Tab with Remind ────────────────────────

function MissingTab({ missing, searchEmpty }: { missing: MissingDocument[]; searchEmpty: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [reminding, setReminding] = useState<string | null>(null);

  async function doRemind(m: MissingDocument) {
    const key = `${m.employeeUserId}-${m.documentTypeId}`;
    setReminding(key);
    try {
      await remindMissingDocument(token, m.employeeUserId, m.documentTypeId);
      showToast('success', `Reminder sent to ${m.employeeName}`);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Remind failed');
    } finally {
      setReminding(null);
    }
  }

  return (
    <div style={card}>
      <div className="nf-doc-table-scroll">
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={thS}>Employee</th>
              <th style={thS}>Missing Document</th>
              <th style={thS}></th>
            </tr>
          </thead>
          <tbody>
            {missing.length === 0 ? (
              <tr><td colSpan={3} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                {searchEmpty ? 'No results match your search.' : (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                    <Users size={28} color="var(--txt-dim)" />
                    <span>All employees have submitted their required documents.</span>
                  </div>
                )}
              </td></tr>
            ) : missing.map((m, i) => {
              const key = `${m.employeeUserId}-${m.documentTypeId}`;
              return (
                <tr key={`${key}-${i}`}>
                  <td style={tdS}>
                    <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{m.employeeName}</div>
                  </td>
                  <td style={{ ...tdS, color: '#ef4444', fontWeight: 600 }}>{m.documentTypeName}</td>
                  <td style={{ ...tdS, width: 48 }}>
                    <KebabMenu items={[{
                      label: reminding === key ? 'Sending…' : 'Send Reminder',
                      onClick: () => doRemind(m),
                    }]} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────

export default function DocumentsCompliancePage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [tab, setTab] = useState<'pending' | 'verified' | 'missing'>('pending');
  const [search, setSearch] = useState('');
  const [kpis, setKpis] = useState<DocumentAdminKpi | null>(null);
  const [pending, setPending] = useState<EmployeeDocument[]>([]);
  const [verified, setVerified] = useState<EmployeeDocument[]>([]);
  const [missing, setMissing] = useState<MissingDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailDoc, setDetailDoc] = useState<EmployeeDocument | null>(null);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      getAdminKpis(token),
      listPendingDocuments(token),
      listAllDocuments(token),
      listMissingDocuments(token),
    ]).then(([k, p, all, m]) => {
      setKpis(k);
      setPending(p);
      setVerified(all.filter(d => d.status === 'VERIFIED'));
      setMissing(m);
    }).catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'))
      .finally(() => setLoading(false));
  }, [token]);

  async function doVerify(doc: EmployeeDocument) {
    try {
      const updated = await verifyDocument(token, doc.id, 'VERIFY');
      setPending(p => p.filter(d => d.id !== doc.id));
      setVerified(v => [updated, ...v]);
      setKpis(k => k ? { ...k, pendingVerification: Math.max(0, k.pendingVerification - 1), totalDocuments: k.totalDocuments } : k);
      setDetailDoc(null);
      showToast('success', 'Document verified');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Verify failed');
    }
  }

  async function doReject(doc: EmployeeDocument, reason: string) {
    try {
      await verifyDocument(token, doc.id, 'REJECT', reason);
      setPending(p => p.filter(d => d.id !== doc.id));
      setKpis(k => k ? { ...k, pendingVerification: Math.max(0, k.pendingVerification - 1) } : k);
      setDetailDoc(null);
      showToast('success', 'Document rejected');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
    }
  }

  const tabStyle = (t: typeof tab): React.CSSProperties => ({
    padding: '8px 18px', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 13,
    background: tab === t ? '#A01418' : 'transparent', color: tab === t ? '#fff' : 'var(--txt-dim)',
    display: 'flex', alignItems: 'center', gap: 6,
  });

  const [docTypeFilter, setDocTypeFilter] = useState('');

  const allDocTypes = [...new Set([
    ...pending.map(d => d.documentTypeName),
    ...verified.map(d => d.documentTypeName),
    ...missing.map(m => m.documentTypeName),
  ])].sort();

  const q = search.trim().toLowerCase();

  const filteredPending = pending.filter(d => {
    if (docTypeFilter && d.documentTypeName !== docTypeFilter) return false;
    return !q || (d.employeeName ?? '').toLowerCase().includes(q) || d.documentTypeName.toLowerCase().includes(q) || d.fileName.toLowerCase().includes(q);
  });

  const filteredVerified = verified.filter(d => {
    if (docTypeFilter && d.documentTypeName !== docTypeFilter) return false;
    return !q || (d.employeeName ?? '').toLowerCase().includes(q) || d.documentTypeName.toLowerCase().includes(q) || d.fileName.toLowerCase().includes(q);
  });

  const filteredMissing = missing.filter(m => {
    if (docTypeFilter && m.documentTypeName !== docTypeFilter) return false;
    return !q || m.employeeName.toLowerCase().includes(q) || m.documentTypeName.toLowerCase().includes(q);
  });

  if (loading) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  return (
    <div>
      <h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Documents & Compliance</h1>
      <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 22 }}>Verify employee documents and track compliance.</p>

      {/* KPI row */}
      {kpis && (
        <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12, marginBottom: 24 }}>
          {[
            { label: 'Pending Verification', value: kpis.pendingVerification, color: '#eab308' },
            { label: 'Employees Pending', value: kpis.employeesWithPending, color: '#f97316' },
            { label: 'Expiring in 30 Days', value: kpis.expiringWithin30Days, color: '#ef4444' },
            { label: 'Total Documents', value: kpis.totalDocuments, color: '#22c55e' },
          ].map(k => (
            <div key={k.label} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 20px' }}>
              <div style={{ fontSize: 26, fontWeight: 800, color: k.color, fontFamily: 'Inter, sans-serif' }}>{k.value}</div>
              <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4, fontWeight: 600 }}>{k.label}</div>
            </div>
          ))}
        </div>
      )}

      {/* Tabs */}
      <div className="nf-doc-tabs" style={{ display: 'flex', gap: 6, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 4, width: 'fit-content', marginBottom: 14 }}>
        <button className="nf-doc-tab-btn" style={tabStyle('pending')} onClick={() => setTab('pending')}>
          <span className="nf-doc-tab-label">Pending Verification</span>
          {pending.length > 0 && <span style={{ background: tab === 'pending' ? 'rgba(255,255,255,.25)' : '#eab308', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 7px' }}>{pending.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={tabStyle('verified')} onClick={() => setTab('verified')}>
          <span className="nf-doc-tab-label">Verified</span>
          {verified.length > 0 && <span style={{ background: tab === 'verified' ? 'rgba(255,255,255,.25)' : '#22c55e', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 7px' }}>{verified.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={tabStyle('missing')} onClick={() => setTab('missing')}>
          <span className="nf-doc-tab-label">Not Submitted</span>
          {missing.length > 0 && <span style={{ background: tab === 'missing' ? 'rgba(255,255,255,.25)' : '#ef4444', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 7px' }}>{missing.length}</span>}
        </button>
      </div>

      {/* Search + Filter */}
      <div style={{ display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center', flexWrap: 'wrap' }}>
        <div className="nf-search-full-mobile" style={{ position: 'relative' }}>
          <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input value={search} onChange={e => setSearch(e.target.value)}
            placeholder={tab === 'missing' ? 'Search by name, type…' : 'Search by name, type, file…'}
            className="nf-search-full-mobile-input"
            style={{ paddingLeft: 30, padding: '7px 12px 7px 30px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, outline: 'none', width: 240 }} />
        </div>
        {allDocTypes.length > 1 && (
          <select value={docTypeFilter} onChange={e => setDocTypeFilter(e.target.value)}
            style={{ padding: '7px 12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}>
            <option value="">All document types</option>
            {allDocTypes.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
      </div>

      {/* ── Pending Verification tab ── */}
      {tab === 'pending' && (
        <div style={card}>
          <div className="nf-doc-table-scroll">
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={thS}>Employee</th>
                  <th style={thS}>Document Type</th>
                  <th style={thS}>File</th>
                  <th style={thS}>Uploaded</th>
                  <th style={thS}>Expiry</th>
                  <th style={thS}></th>
                </tr>
              </thead>
              <tbody>
                {filteredPending.length === 0 ? (
                  <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                    {q ? 'No results match your search.' : 'No documents pending verification.'}
                  </td></tr>
                ) : filteredPending.map(d => (
                  <tr key={d.id}>
                    <td style={tdS}>
                      <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{d.employeeName ?? <span style={{ fontFamily: 'Inter, sans-serif', fontSize: 11, color: 'var(--txt-dim)' }}>{d.employeeUserId.slice(0, 8)}…</span>}</div>
                    </td>
                    <td style={{ ...tdS, fontWeight: 600, color: 'var(--txt)' }}>{d.documentTypeName}</td>
                    <td style={tdS}>
                      <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--txt-dim)', fontSize: 12 }}>
                        <Eye size={12} /> {d.fileName}
                      </span>
                    </td>
                    <td style={tdS}>{new Date(d.uploadedAt).toLocaleDateString()}</td>
                    <td style={tdS}>{d.expiryDate ? new Date(d.expiryDate).toLocaleDateString() : '—'}</td>
                    <td style={{ ...tdS, width: 48 }}>
                      <KebabMenu items={[
                        { label: 'Review', onClick: () => setDetailDoc(d) },
                      ]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* ── Verified tab ── */}
      {tab === 'verified' && (
        <div style={card}>
          <div className="nf-doc-table-scroll">
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={thS}>Employee</th>
                  <th style={thS}>Document Type</th>
                  <th style={thS}>File</th>
                  <th style={thS}>Verified At</th>
                  <th style={thS}>Expiry</th>
                  <th style={thS}></th>
                </tr>
              </thead>
              <tbody>
                {filteredVerified.length === 0 ? (
                  <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                    {q ? 'No results match your search.' : 'No verified documents yet.'}
                  </td></tr>
                ) : filteredVerified.map(d => (
                  <tr key={d.id}>
                    <td style={tdS}>
                      <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{d.employeeName ?? <span style={{ fontFamily: 'Inter, sans-serif', fontSize: 11 }}>{d.employeeUserId.slice(0, 8)}…</span>}</div>
                    </td>
                    <td style={{ ...tdS, fontWeight: 600, color: 'var(--txt)' }}>{d.documentTypeName}</td>
                    <td style={tdS}>
                      <span style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--txt-dim)', fontSize: 12 }}>
                        <Eye size={12} /> {d.fileName}
                      </span>
                    </td>
                    <td style={tdS}>{d.verifiedAt ? new Date(d.verifiedAt).toLocaleDateString() : '—'}</td>
                    <td style={tdS}>{d.expiryDate ? new Date(d.expiryDate).toLocaleDateString() : '—'}</td>
                    <td style={{ ...tdS, width: 48 }}>
                      <KebabMenu items={[{ label: 'View Document', onClick: () => setDetailDoc(d) }]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* ── Not Submitted tab ── */}
      {tab === 'missing' && (
        <MissingTab missing={filteredMissing} searchEmpty={q.length > 0} />
      )}

      {detailDoc && (
        <DetailModal
          key={detailDoc.id}
          doc={detailDoc}
          onVerify={() => doVerify(detailDoc)}
          onReject={reason => doReject(detailDoc, reason)}
          onClose={() => setDetailDoc(null)}
        />
      )}
    </div>
  );
}
