import { useEffect, useRef, useState } from 'react';
import { X, ChevronDown } from 'lucide-react';
import { KebabMenu } from '../components/KebabMenu';
import { useAuthStore } from '../store/authStore';
import { employeesApi, type EmployeeRecord, type UpdateEmployeePayload } from '../api/employees';
import { orgApi } from '../api/org';
import { StatusBadge, inactiveDimStyle, InactiveEditBanner, InactiveFieldsConfirm } from '../components/EmployeeStatus';

const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];
const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'];

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

// Department/Designation/Location dropdowns must only offer records still assignable for a NEW
// selection — a deactivated (not deleted) master row shouldn't be pickable going forward. A
// hard-deleted row never reaches here at all: it's physically gone from what the list endpoint
// returns. `currentId` keeps this employee's EXISTING value visible/selected even if it was since
// deactivated, rather than silently dropping it from the options.
function assignableOptions<T extends { id: string; active?: boolean }>(rows: T[], currentId: string | null | undefined): T[] {
  return rows.filter(r => r.active !== false || r.id === currentId);
}

// ─── Creatable Location Select ────────────────────────────────────────────────
interface Location { id: string; name: string; active?: boolean; }

function CreatableLocationSelect({
  locations, value, onChange, token,
}: {
  locations: Location[];
  value: string | undefined;
  onChange: (id: string | undefined) => void;
  token: string;
}) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const currentName = value ? (locations.find(l => l.id === value)?.name ?? '') : '';

  useEffect(() => {
    if (!open) setQuery(currentName);
  }, [open, currentName]);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const filtered = query.trim()
    ? locations.filter(l => l.name.toLowerCase().includes(query.toLowerCase()))
    : locations;
  const exactMatch = locations.some(l => l.name.toLowerCase() === query.trim().toLowerCase());
  const showCreate = query.trim().length > 0 && !exactMatch;

  async function handleCreate() {
    setCreating(true);
    try {
      const newLoc = await orgApi.createLocation(token, { name: query.trim() });
      locations.push(newLoc);
      onChange(newLoc.id);
      setOpen(false);
    } finally {
      setCreating(false);
    }
  }

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }}>
        <input
          style={{ ...inputStyle, paddingRight: 32 }}
          placeholder="Select or type a new location…"
          value={open ? query : currentName}
          onFocus={() => { setOpen(true); setQuery(currentName); }}
          onChange={e => { setQuery(e.target.value); setOpen(true); if (!e.target.value) onChange(undefined); }}
        />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 200, overflowY: 'auto' }}>
          {filtered.length === 0 && !showCreate && (
            <div style={{ padding: '10px 14px', fontSize: 13, color: 'var(--txt-dim)' }}>No locations found</div>
          )}
          {filtered.map(l => (
            <div key={l.id}
              onMouseDown={() => { onChange(l.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === l.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === l.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === l.id ? 'rgba(176,17,22,.12)' : 'transparent')}
            >
              {l.name}
            </div>
          ))}
          {showCreate && (
            <div
              onMouseDown={creating ? undefined : handleCreate}
              style={{ padding: '9px 14px', fontSize: 13, color: '#4C8DD6', borderTop: filtered.length > 0 ? '1px solid var(--line)' : 'none', cursor: creating ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}
            >
              <span style={{ fontWeight: 700 }}>+</span> {creating ? 'Creating…' : `Create "${query.trim()}"`}
            </div>
          )}
          <div
            onMouseDown={() => { onChange(undefined); setQuery(''); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', cursor: 'pointer' }}
          >
            — Clear —
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Edit Modal ───────────────────────────────────────────────────────────────
function EditModal({ emp, onClose, onUpdated, token }: { emp: EmployeeRecord; onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string }) {
  const [form, setForm] = useState<UpdateEmployeePayload>({
    fullName: emp.fullName,
    businessUnitId: emp.businessUnitId ?? undefined,
    departmentId: emp.departmentId ?? undefined,
    designationId: emp.designationId ?? undefined,
    locationId: emp.locationId ?? undefined,
    employmentType: emp.employmentType,
    workMode: emp.workMode ?? 'ONSITE',
    timezone: emp.timezone ?? '',
  });
  const [opts, setOpts] = useState<{ businessUnits: any[]; departments: any[]; designations: any[]; locations: any[] }>({ businessUnits: [], departments: [], designations: [], locations: [] });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isInactive = !emp.active;
  const [confirmInactiveEdit, setConfirmInactiveEdit] = useState(false);
  const employmentFieldsLocked = isInactive && !confirmInactiveEdit;

  useEffect(() => {
    Promise.all([orgApi.listBusinessUnits(token), orgApi.listDepartments(token), orgApi.listDesignations(token), orgApi.listLocations(token)])
      .then(([bu, d, des, l]) => setOpts({ businessUnits: bu, departments: d, designations: des, locations: l }));
  }, [token]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true); setError(null);
    try {
      const updated = await employeesApi.update(emp.userId, { ...form, confirmInactiveEdit }, token);
      onUpdated(updated);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Update failed');
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 540 }}>
        <ModalHeader title={`Edit — ${emp.fullName}`} onClose={onClose} />
        <form onSubmit={handleSubmit} className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', fontSize: 13, padding: '10px 14px', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6 }}>{error}</div>}
          {isInactive && <InactiveEditBanner />}
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Full Name">
              <input style={inputStyle} value={form.fullName ?? ''} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} />
            </Field>
          </div>
          <Field label="Business Unit">
            <select style={inputStyle} value={form.businessUnitId ?? ''} onChange={e => setForm(f => ({ ...f, businessUnitId: e.target.value || undefined }))}>
              <option value="">— None —</option>
              {opts.businessUnits.map((b: any) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Department">
            <select style={inputStyle} disabled={employmentFieldsLocked} value={form.departmentId ?? ''} onChange={e => setForm(f => ({ ...f, departmentId: e.target.value || undefined }))}>
              <option value="">— None —</option>
              {assignableOptions(opts.departments, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Designation">
            <select style={inputStyle} disabled={employmentFieldsLocked} value={form.designationId ?? ''} onChange={e => setForm(f => ({ ...f, designationId: e.target.value || undefined }))}>
              <option value="">— None —</option>
              {assignableOptions(opts.designations, form.designationId).map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </Field>
          <Field label="Employment Type">
            <select style={inputStyle} disabled={employmentFieldsLocked} value={form.employmentType ?? 'FULL_TIME'} onChange={e => setForm(f => ({ ...f, employmentType: e.target.value }))}>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </Field>
          <Field label="Work Mode">
            <select style={inputStyle} value={form.workMode ?? 'ONSITE'} onChange={e => setForm(f => ({ ...f, workMode: e.target.value }))}>
              {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Location">
              <CreatableLocationSelect
                locations={opts.locations}
                value={form.locationId}
                onChange={id => setForm(f => ({ ...f, locationId: id }))}
                token={token}
              />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Timezone (overrides Location)">
              <input
                style={inputStyle}
                value={form.timezone ?? ''}
                onChange={e => setForm(f => ({ ...f, timezone: e.target.value }))}
                placeholder="e.g. Asia/Kolkata"
                list="nf-employee-iana-timezones"
              />
              <datalist id="nf-employee-iana-timezones">
                <option value="Asia/Kolkata" />
                <option value="America/New_York" />
                <option value="America/Los_Angeles" />
                <option value="America/Chicago" />
                <option value="Europe/London" />
                <option value="Australia/Sydney" />
                <option value="Asia/Singapore" />
                <option value="Asia/Dubai" />
                <option value="Pacific/Auckland" />
                <option value="Asia/Kathmandu" />
                <option value="Asia/Chittagong" />
              </datalist>
              <span style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 3, display: 'block' }}>
                IANA zone id — takes precedence over this employee's Location timezone for their check-in/out, lateness, and shift-day calculations. Leave blank to inherit from Location.
              </span>
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', fontSize: 11, color: 'var(--txt-dim)' }}>
            Manager &amp; Role are managed by Super Admin only.
          </div>
          {isInactive && (
            <InactiveFieldsConfirm checked={confirmInactiveEdit} onChange={setConfirmInactiveEdit} fields="Department, Designation, or Employment Type" />
          )}
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || employmentFieldsLocked} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || employmentFieldsLocked) ? 'not-allowed' : 'pointer', opacity: (submitting || employmentFieldsLocked) ? 0.7 : 1 }}>{submitting ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}


// ─── Main Page ────────────────────────────────────────────────────────────────
export default function EmployeeMasterPage() {
  const token = useAuthStore(s => s.token)!;
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<EmployeeRecord | null>(null);

  // Filters
  const [search, setSearch] = useState('');
  const [deptFilter, setDeptFilter] = useState('');
  const [modeFilter, setModeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [page, setPage] = useState(1);
  const PAGE_SIZE = 15;

  useEffect(() => {
    employeesApi.list(token).then(setEmployees).finally(() => setLoading(false));
  }, [token]);

  const departments = Array.from(new Set(employees.map(e => e.departmentName).filter(Boolean))) as string[];

  const filtered = employees.filter(e => {
    const q = search.toLowerCase();
    if (q && !e.fullName.toLowerCase().includes(q) && !e.email.toLowerCase().includes(q) && !(e.employeeCode ?? '').toLowerCase().includes(q)) return false;
    if (deptFilter && e.departmentName !== deptFilter) return false;
    if (modeFilter && (e.workMode ?? 'ONSITE') !== modeFilter) return false;
    if (statusFilter === 'ACTIVE' && !e.active) return false;
    if (statusFilter === 'INACTIVE' && e.active) return false;
    return true;
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  useEffect(() => { setPage(1); }, [search, deptFilter, modeFilter, statusFilter]);

  const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
  const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
  const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', color: 'var(--txt-mut)', fontSize: 12, cursor: 'pointer', outline: 'none' };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <div>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Employee Master</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Manage employee records. New hires, role and access are added from Super Admin → User Management.</p>
        </div>
      </div>

      {/* Search + Filters */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
        <input
          placeholder="Search name, email, or employee ID…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ flex: '1 1 240px', minWidth: 200, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 12px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
        />
        <select value={statusFilter} onChange={e => setStatusFilter(e.target.value as any)} style={filterSelect}>
          <option value="ALL">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
        <select value={deptFilter} onChange={e => setDeptFilter(e.target.value)} style={filterSelect}>
          <option value="">All Departments</option>
          {departments.map(d => <option key={d} value={d}>{d}</option>)}
        </select>
        <select value={modeFilter} onChange={e => setModeFilter(e.target.value)} style={filterSelect}>
          <option value="">All Work Modes</option>
          <option value="ONSITE">Onsite</option>
          <option value="HYBRID">Hybrid</option>
          <option value="REMOTE">Remote</option>
        </select>
        {(search || deptFilter || modeFilter || statusFilter !== 'ALL') && (
          <button onClick={() => { setSearch(''); setDeptFilter(''); setModeFilter(''); setStatusFilter('ALL'); }}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Clear
          </button>
        )}
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>{employees.length === 0 ? 'No employees yet' : 'No results'}</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {employees.length === 0 ? 'New hires are added from Super Admin → User Management.' : 'Try adjusting search or filters.'}
            </div>
          </div>
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Employee ID', 'Name', 'Email', 'Dept', 'Designation', 'Manager', 'Location', 'Mode', 'Status', ''].map(h => (
                      <th key={h} style={thStyle}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(emp => (
                    <tr key={emp.userId}
                      style={inactiveDimStyle(emp.active)}
                      onMouseEnter={e => (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'}
                      onMouseLeave={e => (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'}>
                      <td style={{ ...tdStyle, fontFamily: 'Inter, sans-serif', fontSize: 12 }}>{emp.employeeCode}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{emp.fullName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{emp.email}</td>
                      <td style={tdStyle}>{emp.departmentName ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>{emp.designationName ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>{emp.currentManager ? emp.currentManager.fullName : <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>{emp.locationName ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>
                        <span style={{ fontSize: 11, fontWeight: 600, color: emp.workMode === 'REMOTE' ? '#4C8DD6' : emp.workMode === 'HYBRID' ? '#E0A93B' : '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
                          {emp.workMode ?? 'ONSITE'}
                        </span>
                      </td>
                      <td style={tdStyle}><StatusBadge active={emp.active} /></td>
                      <td style={{ ...tdStyle, padding: '8px 12px', width: 44 }}>
                        <KebabMenu items={[
                          { label: 'Edit', onClick: () => setEditing(emp) },
                        ]} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {totalPages > 1 && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 16px', borderTop: '1px solid var(--line)', flexWrap: 'wrap', gap: 8 }}>
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
                  {filtered.length} result{filtered.length !== 1 ? 's' : ''} · page {page} of {totalPages}
                </span>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 12px', fontSize: 12, color: page === 1 ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === 1 ? 'not-allowed' : 'pointer' }}>
                    ← Prev
                  </button>
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    const start = Math.max(1, Math.min(page - 2, totalPages - 4));
                    const p = start + i;
                    return p <= totalPages ? (
                      <button key={p} onClick={() => setPage(p)}
                        style={{ background: p === page ? 'var(--brand)' : 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: p === page ? '#fff' : 'var(--txt-mut)', cursor: 'pointer', minWidth: 32 }}>
                        {p}
                      </button>
                    ) : null;
                  })}
                  <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page === totalPages}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 12px', fontSize: 12, color: page === totalPages ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === totalPages ? 'not-allowed' : 'pointer' }}>
                    Next →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {editing && <EditModal emp={editing} token={token} onClose={() => setEditing(null)} onUpdated={updated => setEmployees(prev => prev.map(e => e.userId === updated.userId ? updated : e))} />}
    </div>
  );
}
