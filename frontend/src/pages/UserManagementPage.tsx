import { useEffect, useRef, useState } from 'react';
import { UserPlus, X, ChevronDown } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { usersApi, employeesApi, type EmployeeRecord, type CreateUserPayload, type UpdateUserPayload, type UpdateJoiningDatePayload, type ResetPasswordResult } from '../api/employees';
import { orgApi, type ShiftRow } from '../api/org';
import { onboardingApi } from '../api/onboarding';
import { useToast } from '../context/ToastContext';
import { KebabMenu } from '../components/KebabMenu';
import { ShiftFormModal, fmtShiftTime } from './OrgSetupPage';
import { StatusBadge, InactiveEditBanner, InactiveFieldsConfirm } from '../components/EmployeeStatus';

// The organization's default shift — mirrors the backend's Shift.DEFAULT_SHIFT_NAME (same stable,
// unique, seeded name; never a hardcoded id). The backend already defaults a newly-created
// employee to this shift server-side when none is explicitly chosen (see
// UserManagementService#createUser) — preselecting it here is purely a UX convenience so the
// admin sees what will actually be assigned, and can still freely pick a different active shift.
const DEFAULT_SHIFT_NAME = 'Default Shift';

const ROLES = [
  { value: 'EMPLOYEE',    label: 'Employee' },
  { value: 'MANAGER',     label: 'Manager' },
  { value: 'HR_ADMIN',    label: 'HR Admin' },
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
];

const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];
const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'];

// Letters (any language), spaces, hyphens, and apostrophes only — blocks emojis, digits,
// and other symbols while still allowing names like "O'Brien" or "Anne-Marie". Mirrors the
// backend's CreateUserRequest @Pattern so both sides reject the same inputs.
const NAME_PATTERN = /^(?=.*\p{L})[\p{L}\s'-]+$/u;
// Requires a real, letters-only TLD (2+ chars) with nothing after it — rejects domains with
// no dot (e.g. "a@99999999999") and trailing junk after the TLD (e.g. "a@example.com123").
// Mirrors the backend's CreateUserRequest @Pattern so both sides reject the same inputs.
const EMAIL_PATTERN = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;

const ROLE_COLOR: Record<string, string> = {
  EMPLOYEE: '#2FB67C', MANAGER: '#4C8DD6', HR_ADMIN: '#E0A93B', SUPER_ADMIN: '#E4373D',
};

function RoleBadge({ role }: { role: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: ROLE_COLOR[role] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {role.replace(/_/g, ' ')}
    </span>
  );
}

interface OrgOptions { businessUnits: any[]; departments: any[]; designations: any[]; locations: any[]; managers: EmployeeRecord[]; shifts: ShiftRow[]; }

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

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

// ─── Creatable Location Select ────────────────────────────────────────────────
interface Location { id: string; name: string; active?: boolean; }

function CreatableLocationSelect({ locations, value, onChange, token }: { locations: Location[]; value: string | undefined; onChange: (id: string | undefined) => void; token: string }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const currentName = value ? (locations.find(l => l.id === value)?.name ?? '') : '';
  useEffect(() => { if (!open) setQuery(currentName); }, [open, currentName]);
  useEffect(() => {
    function out(e: MouseEvent) { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); }
    document.addEventListener('mousedown', out);
    return () => document.removeEventListener('mousedown', out);
  }, []);
  const filtered = query.trim() ? locations.filter(l => l.name.toLowerCase().includes(query.toLowerCase())) : locations;
  const exactMatch = locations.some(l => l.name.toLowerCase() === query.trim().toLowerCase());
  const showCreate = query.trim().length > 0 && !exactMatch;
  async function handleCreate() {
    setCreating(true);
    try {
      const newLoc = await orgApi.createLocation(token, { name: query.trim() });
      locations.push(newLoc); onChange(newLoc.id); setOpen(false);
    } finally { setCreating(false); }
  }
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }}>
        <input style={{ ...inputStyle, paddingRight: 32 }} placeholder="Select or type a new location…"
          value={open ? query : currentName}
          onFocus={() => { setOpen(true); setQuery(currentName); }}
          onChange={e => { setQuery(e.target.value); setOpen(true); if (!e.target.value) onChange(undefined); }} />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 200, overflowY: 'auto' }}>
          {filtered.length === 0 && !showCreate && <div style={{ padding: '10px 14px', fontSize: 13, color: 'var(--txt-dim)' }}>No locations found</div>}
          {filtered.map(l => (
            <div key={l.id} onMouseDown={() => { onChange(l.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === l.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === l.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === l.id ? 'rgba(176,17,22,.12)' : 'transparent')}>
              {l.name}
            </div>
          ))}
          {showCreate && (
            <div onMouseDown={creating ? undefined : handleCreate}
              style={{ padding: '9px 14px', fontSize: 13, color: '#4C8DD6', borderTop: filtered.length > 0 ? '1px solid var(--line)' : 'none', cursor: creating ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ fontWeight: 700 }}>+</span> {creating ? 'Creating…' : `Create "${query.trim()}"`}
            </div>
          )}
          <div onMouseDown={() => { onChange(undefined); setQuery(''); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', cursor: 'pointer' }}>
            — Clear —
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Shift Select (with inline "+ Add New Shift") ─────────────────────────────
function ShiftSelect({ shifts, value, onChange, onCreated, token }: {
  shifts: ShiftRow[]; value: string | undefined; onChange: (id: string | undefined) => void;
  onCreated: (shift: ShiftRow) => void; token: string;
}) {
  const [open, setOpen] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const current = shifts.find(s => s.id === value);
  useEffect(() => {
    function out(e: MouseEvent) { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); }
    document.addEventListener('mousedown', out);
    return () => document.removeEventListener('mousedown', out);
  }, []);
  const label = (s: ShiftRow) => `${s.name} — ${fmtShiftTime(s.startTime)}–${fmtShiftTime(s.endTime)}`;
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }} onClick={() => setOpen(o => !o)}>
        <input readOnly style={{ ...inputStyle, paddingRight: 32, cursor: 'pointer' }} placeholder="Select a shift…"
          value={current ? label(current) : ''} />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 220, overflowY: 'auto' }}>
          <div onMouseDown={() => { onChange(undefined); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', cursor: 'pointer' }}>
            — None —
          </div>
          {shifts.map(s => (
            <div key={s.id} onMouseDown={() => { onChange(s.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === s.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === s.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === s.id ? 'rgba(176,17,22,.12)' : 'transparent')}>
              {label(s)}
            </div>
          ))}
          <div onMouseDown={() => { setShowCreate(true); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 13, color: '#4C8DD6', borderTop: '1px solid var(--line)', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontWeight: 700 }}>+</span> Add New Shift
          </div>
        </div>
      )}
      {showCreate && (
        <ShiftFormModal
          token={token}
          onClose={() => setShowCreate(false)}
          onSaved={(saved) => { onCreated(saved); onChange(saved.id); setShowCreate(false); }}
        />
      )}
    </div>
  );
}

// `opts.managers` (the potential-managers list) is fetched once per page load and shared by
// Add/Edit modals — see UserManagementPage's own comment on that state. Any action here that
// changes a user's active status, role, or name (status toggle, edit, soft delete) needs to
// patch this cached list in place too, or a since-deactivated (or renamed/re-roled) user keeps
// showing up as a selectable manager until the whole page is reloaded.
function syncManagerOption(managers: EmployeeRecord[], updated: EmployeeRecord): EmployeeRecord[] {
  return managers.some(m => m.userId === updated.userId)
    ? managers.map(m => (m.userId === updated.userId ? updated : m))
    : [...managers, updated];
}

// Same reasoning/shape as getManagersForRole just below: `shifts` (opts.shifts) is fetched once
// per page load and shared by Add/Edit — an inactive shift must not be a pickable option for a
// new or changed assignment, but an employee already on a shift that's since been deactivated
// must still see their own current assignment rendered (not silently blanked out) when the Edit
// modal opens. `currentShiftId` is `form.shiftId`, so this is a no-op filter for Add (starts
// undefined) and only keeps the one already-assigned entry for Edit.
function getShiftOptions(shifts: ShiftRow[], currentShiftId: string | undefined): ShiftRow[] {
  return shifts.filter(s => s.active !== false || s.id === currentShiftId);
}

// Same reasoning/shape as getShiftOptions just above, for the 3 other master-data dropdowns that
// were still handing out every row (including deactivated ones) unfiltered.
function getDepartmentOptions(departments: any[], currentId: string | undefined): any[] {
  return departments.filter(d => d.active !== false || d.id === currentId);
}

function getDesignationOptions(designations: any[], currentId: string | undefined): any[] {
  return designations.filter(d => d.active !== false || d.id === currentId);
}

function getLocationOptions(locations: Location[], currentId: string | undefined): Location[] {
  return locations.filter(l => l.active !== false || l.id === currentId);
}

// Deactivated managers are deliberately kept in this list (not filtered out) so an employee
// already reporting to one still shows that name in the dropdown instead of it silently
// vanishing — the option is rendered disabled (see the two <option> call sites below) rather
// than excluded, so it can't be picked for a NEW assignment. A genuinely deleted manager is
// excluded server-side already (EmployeeService.listPotentialManagers), so nothing to do here
// for that case.
//
// An Employee can report to a Manager or an HR Admin; a Manager can report to an HR Admin or a
// Super Admin; HR Admin/Super Admin still only report to a Super Admin. HR Admin was previously
// excluded from every tier even though EmployeeService.listPotentialManagers already treats it
// as an eligible manager role.
function getManagersForRole(role: string, managers: EmployeeRecord[]): EmployeeRecord[] {
  if (role === 'EMPLOYEE') return managers.filter(m => m.role === 'MANAGER' || m.role === 'HR_ADMIN');
  if (role === 'MANAGER') return managers.filter(m => m.role === 'HR_ADMIN' || m.role === 'SUPER_ADMIN');
  return managers.filter(m => m.role === 'SUPER_ADMIN');
}

// ─── Add User Modal ───────────────────────────────────────────────────────────
function AddModal({ onClose, onCreated, token, opts, setOpts }: {
  onClose: () => void; onCreated: (e: EmployeeRecord) => void; token: string;
  // Org-wide reference data (departments/designations/locations/managers/shifts) — fetched ONCE
  // by the parent UserManagementPage and shared with EditModal, instead of each modal re-fetching
  // it (including the expensive potential-managers lookup) on every single open. See
  // UserManagementPage's own orgOptions state/effect for where this actually loads.
  opts: OrgOptions; setOpts: React.Dispatch<React.SetStateAction<OrgOptions>>;
}) {
  const { showToast } = useToast();
  const [form, setForm] = useState<CreateUserPayload>({ fullName: '', email: '', role: 'EMPLOYEE', joiningDate: new Date().toISOString().slice(0, 10), workMode: 'ONSITE' });
  const [startOnboarding, setStartOnboarding] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Set instead of `error` specifically when the backend rejects the submitted Employee ID as
  // already taken (EmployeeCodeGenerator#claim's exact conflict message) — rendered as its own
  // banner with a "Click here" action that fetches a fresh suggestion via the same non-consuming
  // preview endpoint, without touching any other field or closing this modal.
  const [employeeCodeConflict, setEmployeeCodeConflict] = useState(false);
  const [fetchingNewId, setFetchingNewId] = useState(false);
  const [created, setCreated] = useState<EmployeeRecord | null>(null);
  const [onboardingOutcome, setOnboardingOutcome] = useState<'started' | 'skipped' | 'failed' | null>(null);
  // Auto-populated suggestion only (see employeesApi.previewNextCode) — fetched once on open
  // and dropped straight into the (editable) Employee ID field below as a starting value. The
  // admin can freely overwrite it; this fetch never reserves/consumes the ID. Whatever ends up
  // in the field — untouched or edited — is submitted to the backend verbatim and validated as
  // the exact requested Employee ID (see handleSubmit below and EmployeeCodeGenerator#claim on
  // the backend): a stale submission must fail with a clear conflict, never silently resolve to
  // a different ID.
  const [previewLoading, setPreviewLoading] = useState(true);

  useEffect(() => {
    employeesApi.previewNextCode(token)
      .then(r => setForm(f => ({ ...f, employeeCode: f.employeeCode ?? r.employeeCode })))
      .catch(() => { /* leave the field blank — the backend auto-assigns one if none is submitted */ })
      .finally(() => setPreviewLoading(false));
  }, [token]);

  // Preselects the organization's default shift (Default Shift) once the reference shift list is
  // available — matches what the backend will actually assign server-side if the admin submits
  // without touching this field at all (see UserManagementService#createUser). Never overrides a
  // selection already present (an admin's own pick, or a re-run of this effect after `opts.shifts`
  // reloads post-inline-create) — only ever fills a still-empty field.
  useEffect(() => {
    if (form.shiftId) return;
    const regularShift = opts.shifts.find(s => s.name === DEFAULT_SHIFT_NAME && s.active);
    if (regularShift) setForm(f => (f.shiftId ? f : { ...f, shiftId: regularShift.id }));
  }, [opts.shifts]);

  // Fetches a fresh suggestion via the same non-consuming preview endpoint used on open, and
  // drops it straight into the Employee ID field — nothing else in the form is touched, and the
  // modal stays open. Backs the "Click here" action in the conflict banner below.
  async function handleGetNewId() {
    setFetchingNewId(true);
    try {
      const r = await employeesApi.previewNextCode(token);
      setForm(f => ({ ...f, employeeCode: r.employeeCode }));
      setEmployeeCodeConflict(false);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Could not fetch a new Employee ID';
      showToast('error', msg);
    } finally {
      setFetchingNewId(false);
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmedName = form.fullName.trim();
    const rawEmail = form.email;
    if (!trimmedName || !rawEmail) { setError('Name and email required.'); return; }
    if (!NAME_PATTERN.test(trimmedName)) { setError("Full name can only contain letters, spaces, hyphens, and apostrophes — no emojis or symbols."); return; }
    // Deliberately checked against the raw value, not a trimmed one — a leading/trailing
    // space is rejected outright rather than silently stripped before validating.
    if (rawEmail !== rawEmail.trim()) { setError('Email must not have leading or trailing spaces.'); return; }
    if (!EMAIL_PATTERN.test(rawEmail)) { setError('Enter a valid email address with a proper domain (e.g. name@company.com).'); return; }
    if (!form.locationId) { setError('Location is required — Leave & Holidays depends on it.'); return; }
    if (form.role !== 'SUPER_ADMIN' && !form.managerId) { setError('Reporting Manager is required for this role.'); return; }
    setSubmitting(true); setError(null); setEmployeeCodeConflict(false);
    // Submit the Employee ID exactly as displayed — untouched or edited — and let the backend
    // validate that exact value. If it's already taken (e.g. another admin's form showed the
    // same suggestion and got there first), the backend rejects it outright rather than
    // silently assigning a different one; see the catch block below.
    const trimmedCode = (form.employeeCode ?? '').trim();
    try {
      const emp = await usersApi.create({ ...form, fullName: trimmedName, email: rawEmail, employeeCode: trimmedCode || undefined }, token);
      onCreated(emp);
      showToast('success', `${emp.fullName} created successfully`);
      // The account is already fully created at this point — show the success screen right away
      // instead of making the user wait on a second, unrelated request. Starting onboarding is a
      // genuinely separate concern (its own checklist rows, its own "soft failure is fine, retry
      // from the Onboarding page" story below) that doesn't need to block navigation; it now runs
      // in the background and just updates the outcome banner in place once it settles.
      setCreated(emp);
      if (startOnboarding) {
        onboardingApi.start({ employeeUserId: emp.userId }, token)
          .then(() => setOnboardingOutcome('started'))
          // Account is already created and safe either way — onboarding can always be started
          // later from the Onboarding page, so this is a soft failure, not a blocker.
          .catch(() => setOnboardingOutcome('failed'));
      } else {
        setOnboardingOutcome('skipped');
      }
    } catch (err) {
      // The backend rejects a submitted Employee ID that's already in use (or was just taken by
      // a concurrent request) with this exact message — surfaced as its own banner with a
      // "Click here" recovery action instead of the generic error text, so the admin doesn't
      // have to reopen the form (and lose everything else they typed) to get a fresh ID.
      const msg = err instanceof Error ? err.message : 'Create failed';
      if (msg === 'Employee ID is unavailable. Please go back and retry.') {
        setEmployeeCodeConflict(true);
      } else {
        setError(msg);
      }
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  if (created) {
    return (
      <div style={overlayStyle}>
        <div style={modalStyle}>
          <ModalHeader title="User Created" onClose={onClose} />
          <div style={{ padding: 24 }}>
            <div style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
              <div style={{ color: '#2FB67C', fontWeight: 600, marginBottom: 8 }}>Account created successfully</div>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.8 }}>
                <b style={{ color: 'var(--txt)' }}>Name:</b> {created.fullName}<br />
                <b style={{ color: 'var(--txt)' }}>Email:</b> {created.email}<br />
                <b style={{ color: 'var(--txt)' }}>Employee ID:</b> {created.employeeCode}<br />
                <b style={{ color: 'var(--txt)' }}>Role:</b> {created.role}
              </div>
            </div>
            {onboardingOutcome === null && (
              <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13, color: 'var(--txt-mut)' }}>
                Starting onboarding checklist…
              </div>
            )}
            {onboardingOutcome === 'started' && (
              <div style={{ background: 'rgba(76,141,214,.1)', border: '1px solid rgba(76,141,214,.25)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13, color: '#4C8DD6' }}>
                Onboarding checklist started — find it under Onboarding → Active.
              </div>
            )}
            {onboardingOutcome === 'failed' && (
              <div style={{ background: 'rgba(224,169,59,.1)', border: '1px solid rgba(224,169,59,.25)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13, color: '#E0A93B' }}>
                Account created, but starting onboarding didn't go through. Start it manually from the Onboarding page.
              </div>
            )}
            {created.tempPassword && (
              <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14 }}>
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.06em' }}>Temp password — share once, store nowhere</div>
                <code style={{ fontSize: 14, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', userSelect: 'all' }}>{created.tempPassword}</code>
              </div>
            )}
            <button onClick={onClose} style={{ marginTop: 20, width: '100%', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '10px 16px', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Done</button>
          </div>
        </div>
      </div>
    );
  }

  const set = (key: keyof CreateUserPayload, val: any) => setForm(f => ({ ...f, [key]: val || undefined }));

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 580 }}>
        <ModalHeader title="Add User" onClose={onClose} />
        <form onSubmit={handleSubmit} className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && !employeeCodeConflict && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}><Field label="Full Name *"><input style={inputStyle} value={form.fullName} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} placeholder="Jane Smith" /></Field></div>
          <div style={{ gridColumn: '1/-1' }}><Field label="Company Email *"><input type="email" style={inputStyle} value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@nforceone.com" /></Field></div>
          <Field label="Role *">
            <select style={inputStyle} value={form.role} onChange={e => {
              const newRole = e.target.value;
              const newMgrs = getManagersForRole(newRole, opts.managers);
              setForm(f => ({
                ...f, role: newRole,
                managerId: f.managerId && newMgrs.some(m => m.userId === f.managerId) ? f.managerId : undefined,
              }));
            }}>
              {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </Field>
          <Field label="Employee ID">
            <input
              style={inputStyle}
              value={form.employeeCode ?? ''}
              onChange={e => setForm(f => ({ ...f, employeeCode: e.target.value }))}
              placeholder={previewLoading ? 'Loading…' : 'e.g. NF-20260007'}
            />
            {employeeCodeConflict && (
              <div style={{ marginTop: 6, color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>
                Employee ID already exists.{' '}
                <button
                  type="button"
                  onClick={handleGetNewId}
                  disabled={fetchingNewId}
                  style={{ background: 'none', border: 'none', padding: 0, margin: 0, font: 'inherit', color: 'inherit', textDecoration: 'underline', cursor: fetchingNewId ? 'wait' : 'pointer' }}
                >
                  {fetchingNewId ? 'Fetching…' : 'Click here'}
                </button>
                {' '}to get a new ID.
              </div>
            )}
          </Field>
          <Field label="Joining Date *"><input type="date" style={inputStyle} value={form.joiningDate} onChange={e => setForm(f => ({ ...f, joiningDate: e.target.value }))} /></Field>
          <Field label="Employment Type">
            <select style={inputStyle} value={form.employmentType ?? 'FULL_TIME'} onChange={e => set('employmentType', e.target.value)}>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </Field>
          <Field label="Work Mode">
            <select style={inputStyle} value={form.workMode ?? 'ONSITE'} onChange={e => set('workMode', e.target.value)}>
              {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
            </select>
          </Field>
          <Field label="Business Unit">
            <select style={inputStyle} value={form.businessUnitId ?? ''} onChange={e => set('businessUnitId', e.target.value)}>
              <option value="">— None —</option>{opts.businessUnits.map((b: any) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Department">
            <select style={inputStyle} value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value)}>
              <option value="">— None —</option>{getDepartmentOptions(opts.departments, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Designation">
            <select style={inputStyle} value={form.designationId ?? ''} onChange={e => set('designationId', e.target.value)}>
              <option value="">— None —</option>{getDesignationOptions(opts.designations, form.designationId).map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Location *">
              <CreatableLocationSelect locations={getLocationOptions(opts.locations, form.locationId)} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Timezone (overrides Location)">
              <input style={inputStyle} value={form.timezone ?? ''} onChange={e => set('timezone', e.target.value)}
                placeholder="e.g. Asia/Kolkata" list="nf-user-iana-timezones" />
              <datalist id="nf-user-iana-timezones">
                <option value="Asia/Kolkata" /><option value="America/New_York" /><option value="America/Los_Angeles" />
                <option value="America/Chicago" /><option value="Europe/London" /><option value="Australia/Sydney" />
                <option value="Asia/Singapore" /><option value="Asia/Dubai" /><option value="Pacific/Auckland" />
                <option value="Asia/Kathmandu" /><option value="Asia/Chittagong" />
              </datalist>
              <span style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 3, display: 'block' }}>
                IANA zone id — takes precedence over Location for check-in/out, lateness, and shift-day calculations. Leave blank to inherit from Location.
              </span>
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            {(() => {
              const isSA = form.role === 'SUPER_ADMIN';
              const mgrList = getManagersForRole(form.role, opts.managers);
              const mgrRoleLabel = form.role === 'EMPLOYEE' ? 'Manager or HR Admin'
                : form.role === 'MANAGER' ? 'HR Admin or Super Admin'
                : 'Super Admin';
              return (
                <Field label={isSA ? 'Reporting Manager' : 'Reporting Manager *'}>
                  <select style={inputStyle} value={form.managerId ?? ''} onChange={e => set('managerId', e.target.value)}>
                    <option value="">{isSA ? '— None (optional) —' : '— Select a Reporting Manager —'}</option>
                    {/* Deactivated managers stay in the list (see getManagersForRole) but as a
                        disabled option, so they can't be picked for a new assignment — they're
                        only shown to explain an EXISTING selection that already points at one. */}
                    {mgrList.map((m: any) => (
                      <option key={m.userId} value={m.userId} disabled={m.active === false}>
                        {m.fullName} ({m.email}){m.active === false ? ' — Inactive' : ''}
                      </option>
                    ))}
                  </select>
                  {!isSA && mgrList.length === 0 && (
                    <div style={{ fontSize: 11, color: '#E0A93B', marginTop: 4 }}>
                      No {mgrRoleLabel} users found — add one first.
                    </div>
                  )}
                </Field>
              );
            })()}
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Shift">
              <ShiftSelect
                shifts={getShiftOptions(opts.shifts, form.shiftId)}
                value={form.shiftId}
                onChange={id => setForm(f => ({ ...f, shiftId: id }))}
                onCreated={s => setOpts(o => ({ ...o, shifts: [...o.shifts, s] }))}
                token={token}
              />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={startOnboarding}
                onChange={e => setStartOnboarding(e.target.checked)}
                style={{ marginTop: 2, width: 16, height: 16, flexShrink: 0, accentColor: 'var(--brand)' }}
              />
              <span>
                <span style={{ display: 'block', fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Start onboarding for this employee</span>
                <span style={{ display: 'block', fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>
                  Creates their pre-boarding, document and setup checklist right away. Leave unchecked to add the account only and start onboarding later.
                </span>
              </span>
            </label>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>{submitting ? 'Creating…' : 'Create User'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─── Edit Modal ───────────────────────────────────────────────────────────────
function EditModal({ user, onClose, onUpdated, token, opts, setOpts }: {
  user: EmployeeRecord; onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string;
  // Shared with AddModal — see AddModal's own comment on these two props for why this isn't
  // fetched fresh here.
  opts: OrgOptions; setOpts: React.Dispatch<React.SetStateAction<OrgOptions>>;
}) {
  const { showToast } = useToast();
  const [form, setForm] = useState<UpdateUserPayload>({
    fullName: user.fullName,
    role: user.role,
    businessUnitId: user.businessUnitId ?? undefined,
    departmentId: user.departmentId ?? undefined,
    designationId: user.designationId ?? undefined,
    locationId: user.locationId ?? undefined,
    shiftId: user.shiftId ?? undefined,
    employmentType: user.employmentType,
    workMode: user.workMode ?? 'ONSITE',
    managerId: user.currentManager?.userId ?? undefined,
    timezone: user.timezone ?? '',
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isInactive = !user.active;
  const [confirmInactiveEdit, setConfirmInactiveEdit] = useState(false);
  const gatedFieldsLocked = isInactive && !confirmInactiveEdit;

  // Joining date moved in from the old standalone "Update Date of Joining" modal — same
  // usersApi.updateJoiningDate call and audit-trail note, just triggered from this form instead
  // of a separate 3-dot menu entry. Kept as its own request (not part of `form`/usersApi.update)
  // because the backend deliberately keeps it a separate endpoint with its own audit trail —
  // see UserManagementService.updateJoiningDate.
  const [joiningDate, setJoiningDate] = useState(user.joiningDate);
  const [joiningDateNote, setJoiningDateNote] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (form.role !== 'SUPER_ADMIN' && !form.managerId) { setError('Reporting Manager is required for this role.'); return; }
    setSubmitting(true); setError(null);
    try {
      let updated = await usersApi.update(user.userId, { ...form, confirmInactiveEdit }, token);
      if (joiningDate !== user.joiningDate) {
        const payload: UpdateJoiningDatePayload = { newJoiningDate: joiningDate, note: joiningDateNote.trim() || undefined };
        updated = await usersApi.updateJoiningDate(user.userId, payload, token);
      }
      onUpdated(updated);
      showToast('success', `${updated.fullName} updated successfully`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Update failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  const set = (key: keyof UpdateUserPayload, val: any) => setForm(f => ({ ...f, [key]: val || undefined }));

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 580 }}>
        <ModalHeader title={`Edit — ${user.fullName}`} onClose={onClose} />
        <form onSubmit={handleSubmit} className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          {isInactive && <InactiveEditBanner />}
          <div style={{ gridColumn: '1/-1' }}><Field label="Full Name"><input style={inputStyle} value={form.fullName ?? ''} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} /></Field></div>
          <Field label="Role">
            <select style={inputStyle} disabled={gatedFieldsLocked} value={form.role ?? 'EMPLOYEE'} onChange={e => {
              const newRole = e.target.value;
              const newMgrs = getManagersForRole(newRole, opts.managers).filter(m => m.userId !== user.userId);
              setForm(f => ({
                ...f, role: newRole,
                managerId: f.managerId && newMgrs.some(m => m.userId === f.managerId) ? f.managerId : undefined,
              }));
            }}>
              {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </Field>
          <Field label="Employment Type">
            <select style={inputStyle} disabled={gatedFieldsLocked} value={form.employmentType ?? 'FULL_TIME'} onChange={e => set('employmentType', e.target.value)}>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </Field>
          <Field label="Work Mode">
            <select style={inputStyle} value={form.workMode ?? 'ONSITE'} onChange={e => set('workMode', e.target.value)}>
              {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
            </select>
          </Field>
          <Field label="Business Unit">
            <select style={inputStyle} value={form.businessUnitId ?? ''} onChange={e => set('businessUnitId', e.target.value)}>
              <option value="">— None —</option>{opts.businessUnits.map((b: any) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Department">
            <select style={inputStyle} disabled={gatedFieldsLocked} value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value)}>
              <option value="">— None —</option>{getDepartmentOptions(opts.departments, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Designation">
            <select style={inputStyle} disabled={gatedFieldsLocked} value={form.designationId ?? ''} onChange={e => set('designationId', e.target.value)}>
              <option value="">— None —</option>{getDesignationOptions(opts.designations, form.designationId).map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Timezone (overrides Location)">
              <input style={inputStyle} value={form.timezone ?? ''} onChange={e => setForm(f => ({ ...f, timezone: e.target.value }))}
                placeholder="e.g. Asia/Kolkata" list="nf-user-iana-timezones" />
              <span style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 3, display: 'block' }}>
                IANA zone id — takes precedence over Location for check-in/out, lateness, and shift-day calculations. Leave blank to inherit from Location.
              </span>
            </Field>
          </div>
          <Field label="Date of Joining">
            <input type="date" style={inputStyle} value={joiningDate} onChange={e => setJoiningDate(e.target.value)} />
          </Field>
          {joiningDate !== user.joiningDate && (
            <div style={{ gridColumn: '1/-1' }}>
              <Field label="Reason for date change">
                <textarea
                  style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
                  value={joiningDateNote}
                  onChange={e => setJoiningDateNote(e.target.value)}
                  placeholder="Reason for changing the date of joining…"
                />
              </Field>
            </div>
          )}
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Location">
              <CreatableLocationSelect locations={getLocationOptions(opts.locations, form.locationId)} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            {(() => {
              const isSA = form.role === 'SUPER_ADMIN';
              const mgrList = getManagersForRole(form.role ?? 'EMPLOYEE', opts.managers).filter(m => m.userId !== user.userId);
              const mgrRoleLabel = (form.role ?? '') === 'EMPLOYEE' ? 'Manager or HR Admin'
                : form.role === 'MANAGER' ? 'HR Admin or Super Admin'
                : 'Super Admin';
              return (
                <Field label={isSA ? 'Reporting Manager' : 'Reporting Manager *'}>
                  <select style={inputStyle} disabled={gatedFieldsLocked} value={form.managerId ?? ''} onChange={e => set('managerId', e.target.value)}>
                    <option value="">{isSA ? '— None (optional) —' : '— Select a Reporting Manager —'}</option>
                    {/* Deactivated managers stay in the list (see getManagersForRole) but as a
                        disabled option, so they can't be picked for a new assignment — they're
                        only shown to explain an EXISTING selection that already points at one. */}
                    {mgrList.map((m: any) => (
                      <option key={m.userId} value={m.userId} disabled={m.active === false}>
                        {m.fullName} ({m.email}){m.active === false ? ' — Inactive' : ''}
                      </option>
                    ))}
                  </select>
                  {!isSA && mgrList.length === 0 && (
                    <div style={{ fontSize: 11, color: '#E0A93B', marginTop: 4 }}>
                      No {mgrRoleLabel} users found — add one first.
                    </div>
                  )}
                </Field>
              );
            })()}
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Shift">
              <ShiftSelect
                shifts={getShiftOptions(opts.shifts, form.shiftId)}
                value={form.shiftId}
                onChange={id => setForm(f => ({ ...f, shiftId: id }))}
                onCreated={s => setOpts(o => ({ ...o, shifts: [...o.shifts, s] }))}
                token={token}
              />
            </Field>
          </div>
          {isInactive && (
            <InactiveFieldsConfirm checked={confirmInactiveEdit} onChange={setConfirmInactiveEdit} fields="Role, Reporting Manager, Department, Designation, or Employment Type" />
          )}
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || gatedFieldsLocked} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || gatedFieldsLocked) ? 'not-allowed' : 'pointer', opacity: (submitting || gatedFieldsLocked) ? 0.7 : 1 }}>{submitting ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// Date-of-joining update now lives inline in EditModal above (see its joiningDate/joiningDateNote
// state) instead of this separate modal — same usersApi.updateJoiningDate call, just reached from
// the "Edit" action instead of its own 3-dot menu entry.

// ─── Reset Password Modal ─────────────────────────────────────────────────────
function ResetPasswordModal({ user, onClose, token }: { user: EmployeeRecord; onClose: () => void; token: string }) {
  const { showToast } = useToast();
  const [state, setState] = useState<'confirm' | 'loading' | 'done' | 'error'>('confirm');
  const [result, setResult] = useState<ResetPasswordResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function doReset() {
    setState('loading');
    try {
      const r = await usersApi.resetPassword(user.userId, token);
      setResult(r);
      setState('done');
      showToast('success', `Password reset for ${user.fullName}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reset failed';
      setError(msg);
      setState('error');
      showToast('error', msg);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title="Reset Password" onClose={onClose} />
        <div style={{ padding: 24 }}>
          {state === 'confirm' && (
            <>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 20 }}>
                Reset password for <b style={{ color: 'var(--txt)' }}>{user.fullName}</b>? A new temporary password will be generated. The user must change it on next login.
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
                <button onClick={doReset} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>Reset Password</button>
              </div>
            </>
          )}
          {state === 'loading' && <div style={{ textAlign: 'center', color: 'var(--txt-dim)', padding: 20 }}>Generating…</div>}
          {state === 'error' && (
            <>
              <div style={{ color: 'var(--risk)', marginBottom: 16 }}>{error}</div>
              <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Close</button>
            </>
          )}
          {state === 'done' && result && (
            <>
              <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20 }}>
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.06em' }}>New temp password — share once, store nowhere</div>
                <code style={{ fontSize: 15, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', userSelect: 'all' }}>{result.tempPassword}</code>
              </div>
              <button onClick={onClose} style={{ width: '100%', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '10px 16px', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Done</button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Status Toggle Confirm ────────────────────────────────────────────────────
// isSelf/isLastActiveSuperAdmin are defense-in-depth only — the row's Deactivate action is
// already hidden for both cases (see the row menu below), so this modal shouldn't normally
// open for them. But the API is the real boundary (UserManagementService.setActiveStatus
// re-checks both server-side), so if it's somehow reached anyway the explicit warning/blocked
// states below still apply, and a stale-list race just surfaces the backend's rejection message.
function StatusModal({ user, isSelf, isLastActiveSuperAdmin, onClose, onUpdated, token }: {
  user: EmployeeRecord; isSelf: boolean; isLastActiveSuperAdmin: boolean;
  onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string;
}) {
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const action = user.active ? 'Deactivate' : 'Activate';
  const blocked = user.active && (isSelf || isLastActiveSuperAdmin);

  async function confirm() {
    setLoading(true); setError(null);
    try {
      const updated = await usersApi.setStatus(user.userId, !user.active, token);
      onUpdated(updated);
      showToast('success', `${user.fullName} ${user.active ? 'deactivated' : 'reactivated'}`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Status change failed';
      setError(msg);
      showToast('error', msg);
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 400 }}>
        <ModalHeader title={`${action} User`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {isSelf && user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deactivating your account will end your session. Another Super Admin is required to restore access.
            </div>
          ) : isLastActiveSuperAdmin && user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> is the last active Super Admin. Deactivating this account would leave nobody able to manage users — assign Super Admin to another account first.
            </div>
          ) : user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deactivating <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> will invalidate their active JWT immediately. They cannot log in until reactivated.
            </div>
          ) : (
            <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 20 }}>
              Reactivate <b style={{ color: 'var(--txt)' }}>{user.fullName}</b>? They will be able to log in again.
            </p>
          )}
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={confirm} disabled={loading || blocked} style={{ background: user.active ? '#C0392B' : '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (loading || blocked) ? 'not-allowed' : 'pointer', opacity: (loading || blocked) ? 0.5 : 1 }}>
              {loading ? '…' : action}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Delete (Soft-Delete) Confirm ─────────────────────────────────────────────
function DeleteModal({ user, onClose, onDeleted, token }: { user: EmployeeRecord; onClose: () => void; onDeleted: (userId: string) => void; token: string }) {
  const { showToast } = useToast();
  const [typed, setTyped] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const match = typed === user.email;

  async function confirm() {
    if (!match) return;
    setLoading(true); setError(null);
    try {
      await usersApi.softDelete(user.userId, token);
      onDeleted(user.userId);
      showToast('success', `${user.fullName} permanently deleted`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Delete failed';
      setError(msg);
      showToast('error', msg);
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title="Delete User" onClose={onClose} />
        <div style={{ padding: 24 }}>
          <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: 14, marginBottom: 20 }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--risk)', marginBottom: 8 }}>This action is permanent</div>
            <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deleting <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> ({user.email}) will immediately invalidate their session. This cannot be undone.
            </div>
          </div>
          <label style={{ ...labelStyle, marginBottom: 6 }}>Type the user's email to confirm</label>
          <input
            style={{ ...inputStyle, marginBottom: 4, border: `1px solid ${match ? 'rgba(228,55,61,.5)' : 'var(--line2)'}` }}
            placeholder={user.email}
            value={typed}
            onChange={e => setTyped(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <p style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 20, marginTop: 4 }}>
            Must match exactly: <code style={{ fontFamily: 'Inter, sans-serif', color: 'var(--txt-mut)' }}>{user.email}</code>
          </p>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={confirm} disabled={!match || loading} style={{ background: match ? '#C0392B' : 'var(--raised2)', color: match ? '#fff' : 'var(--txt-dim)', border: `1px solid ${match ? 'transparent' : 'var(--line2)'}`, borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: !match || loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Deleting…' : 'Delete Permanently'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}


// ─── Main Page ────────────────────────────────────────────────────────────────
export default function UserManagementPage() {
  const token = useAuthStore(s => s.token)!;
  const currentUserEmail = useAuthStore(s => s.user?.email)?.toLowerCase();
  const [users, setUsers] = useState<EmployeeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<EmployeeRecord | null>(null);
  const [resetting, setResetting] = useState<EmployeeRecord | null>(null);
  const [toggling, setToggling] = useState<EmployeeRecord | null>(null);
  const [deleting, setDeleting] = useState<EmployeeRecord | null>(null);
  // Reference data for the Add/Edit User forms — departments/designations/locations/managers/
  // shifts. Fetched ONCE here (not per-modal-open) and shared by both AddModal and EditModal, so
  // opening either repeatedly doesn't re-run 5 API calls (including the expensive
  // potential-managers lookup) every single time. A newly-created location/shift from inside
  // either modal is appended straight into this shared state, so it's immediately available to
  // the other modal too without a re-fetch.
  const [orgOptions, setOrgOptions] = useState<OrgOptions>({ businessUnits: [], departments: [], designations: [], locations: [], managers: [], shifts: [] });

  // Filters
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [deptFilter, setDeptFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [page, setPage] = useState(1);
  const PAGE_SIZE = 15;

  useEffect(() => {
    usersApi.list(token).then(setUsers).finally(() => setLoading(false));
  }, [token]);

  useEffect(() => {
    Promise.all([
      orgApi.listBusinessUnits(token),
      orgApi.listDepartments(token),
      orgApi.listDesignations(token),
      orgApi.listLocations(token),
      employeesApi.potentialManagers(token),
      orgApi.listShifts(token),
    ]).then(([bu, d, des, l, m, sh]) => setOrgOptions({ businessUnits: bu, departments: d, designations: des, locations: l, managers: m, shifts: sh }));
  }, [token]);

  // Derived filter options from real data
  const departments = Array.from(new Set(users.map(u => u.departmentName).filter(Boolean))) as string[];
  const roles = Array.from(new Set(users.map(u => u.role).filter(Boolean))) as string[];

  // Stats
  const total = users.length;
  const active = users.filter(u => u.active).length;
  const inactive = total - active;
  const roleCounts: Record<string, number> = {};
  users.forEach(u => { if (u.role) roleCounts[u.role] = (roleCounts[u.role] ?? 0) + 1; });
  const activeSuperAdminCount = users.filter(u => u.role === 'SUPER_ADMIN' && u.active).length;

  // Filter logic
  const filtered = users.filter(u => {
    const q = search.toLowerCase();
    if (q && !u.fullName.toLowerCase().includes(q) && !u.email.toLowerCase().includes(q) && !(u.employeeCode ?? '').toLowerCase().includes(q)) return false;
    if (statusFilter === 'ACTIVE' && !u.active) return false;
    if (statusFilter === 'INACTIVE' && u.active) return false;
    if (deptFilter && u.departmentName !== deptFilter) return false;
    if (roleFilter && u.role !== roleFilter) return false;
    return true;
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  // Reset to page 1 when filters change
  useEffect(() => { setPage(1); }, [search, statusFilter, deptFilter, roleFilter]);

  const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
  const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
  const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', color: 'var(--txt-mut)', fontSize: 12, cursor: 'pointer', outline: 'none' };

  const ROLE_LABEL: Record<string, string> = { EMPLOYEE: 'Employee', MANAGER: 'Manager', HR_ADMIN: 'HR Admin', SUPER_ADMIN: 'Super Admin' };

  const tileStyle = (accent: string): React.CSSProperties => ({
    background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
    padding: '14px 18px', flex: '1 1 140px',
    borderLeft: `3px solid ${accent}`,
  });

  return (
    <div>
      {/* Header */}
      <div className="nf-policy-header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <div>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>User Management</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Manage access, roles, and account status for all users.</p>
        </div>
        <div className="nf-policy-actions" style={{ display: 'flex' }}>
          <button onClick={() => setShowAdd(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer', justifyContent: 'center' }}>
            <UserPlus size={14} /> Add User
          </button>
        </div>
      </div>

      {/* Stats tiles */}
      {!loading && (
        <div style={{ display: 'flex', gap: 10, marginBottom: 18, flexWrap: 'wrap' }}>
          <div style={tileStyle('#9BA1AC')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{total}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Total Users</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>
              {Object.entries(roleCounts).map(([r, c]) => `${c} ${ROLE_LABEL[r] ?? r}`).join(' · ')}
            </div>
          </div>
          <div style={tileStyle('#2FB67C')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#2FB67C', fontFamily: 'Inter, sans-serif' }}>{active}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Active</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>{total > 0 ? Math.round((active / total) * 100) : 0}% of total</div>
          </div>
          <div style={tileStyle('#E4373D')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#E4373D', fontFamily: 'Inter, sans-serif' }}>{inactive}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Inactive</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>{total > 0 ? Math.round((inactive / total) * 100) : 0}% of total</div>
          </div>
        </div>
      )}

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
        <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)} style={filterSelect}>
          <option value="">All Roles</option>
          {roles.map(r => <option key={r} value={r}>{ROLE_LABEL[r] ?? r}</option>)}
        </select>
        {(search || statusFilter !== 'ALL' || deptFilter || roleFilter) && (
          <button onClick={() => { setSearch(''); setStatusFilter('ALL'); setDeptFilter(''); setRoleFilter(''); }}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Clear
          </button>
        )}
      </div>

      {/* Table */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>{users.length === 0 ? 'No users yet' : 'No results'}</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {users.length === 0 ? 'Click "Add User" to create the first account.' : 'Try adjusting the search or filters.'}
            </div>
          </div>
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Employee ID', 'Name', 'Email', 'Role', 'Department', 'Reporting Manager', 'Status', ''].map(h => (
                      <th key={h} style={thStyle}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(u => {
                    const isSelf = !!currentUserEmail && u.email.toLowerCase() === currentUserEmail;
                    // Only meaningful while this row is itself an active Super Admin — deactivating
                    // anyone else never touches the Super Admin headcount.
                    const isLastActiveSuperAdmin = u.role === 'SUPER_ADMIN' && u.active && activeSuperAdminCount <= 1;
                    return (
                    <tr key={u.userId} style={{ opacity: u.active ? 1 : 0.6 }}
                      onMouseEnter={e => (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'}
                      onMouseLeave={e => (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'}>
                      <td style={{ ...tdStyle, fontFamily: 'Inter, sans-serif', fontSize: 12 }}>{u.employeeCode}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{u.fullName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{u.email}</td>
                      <td style={tdStyle}><RoleBadge role={u.role} /></td>
                      <td style={tdStyle}>{u.departmentName ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>{u.currentManager ? u.currentManager.fullName : <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}><StatusBadge active={u.active} /></td>
                      <td style={{ ...tdStyle, padding: '8px 12px', width: 44 }}>
                        <KebabMenu items={[
                          { label: 'Edit', onClick: () => setEditing(u) },
                          { label: 'Reset Password', onClick: () => setResetting(u) },
                          // Deactivate is hidden outright for the logged-in Super Admin's own row
                          // and for the last remaining active Super Admin — both are unrecoverable
                          // in-app once a session ends, so there's no "disabled with tooltip"
                          // middle ground here (see setActiveStatus on the backend for the
                          // matching, authoritative check).
                          ...(u.active && (isSelf || isLastActiveSuperAdmin) ? [] : [
                            { label: u.active ? 'Deactivate' : 'Reactivate', onClick: () => setToggling(u) },
                          ]),
                          // Self-delete carries the exact same lockout risk as self-deactivation.
                          ...(isSelf ? [] : [
                            { label: 'Delete', onClick: () => setDeleting(u), danger: true, dividerBefore: true },
                          ]),
                        ]} />
                      </td>
                    </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
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

      {showAdd && <AddModal token={token} opts={orgOptions} setOpts={setOrgOptions} onClose={() => setShowAdd(false)} onCreated={u => setUsers(prev => [u, ...prev])} />}
      {editing && <EditModal user={editing} token={token} opts={orgOptions} setOpts={setOrgOptions} onClose={() => setEditing(null)} onUpdated={updated => {
        setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u));
        setOrgOptions(o => ({ ...o, managers: syncManagerOption(o.managers, updated) }));
      }} />}
      {resetting && <ResetPasswordModal user={resetting} token={token} onClose={() => setResetting(null)} />}
      {toggling && (
        <StatusModal
          user={toggling}
          isSelf={!!currentUserEmail && toggling.email.toLowerCase() === currentUserEmail}
          isLastActiveSuperAdmin={toggling.role === 'SUPER_ADMIN' && toggling.active && activeSuperAdminCount <= 1}
          token={token}
          onClose={() => setToggling(null)}
          onUpdated={updated => {
            setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u));
            setOrgOptions(o => ({ ...o, managers: syncManagerOption(o.managers, updated) }));
          }}
        />
      )}
      {deleting && <DeleteModal user={deleting} token={token} onClose={() => setDeleting(null)} onDeleted={userId => {
        setUsers(prev => prev.filter(u => u.userId !== userId));
        setOrgOptions(o => ({ ...o, managers: o.managers.filter(m => m.userId !== userId) }));
      }} />}
    </div>
  );
}
