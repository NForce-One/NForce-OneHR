import { useEffect, useRef, useState } from 'react';
import { CalendarDays, ChevronLeft, ChevronRight, Pencil, Plus, Trash2, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { orgApi, type LocationRow } from '../api/org';

// Split out of LeavePage.tsx (formerly the "Company Holidays" section there) — same component
// code, API calls, filtering, and styling, just relocated onto its own route/page. See LeavePage
// for the Applied Leaves / Available Leave Balance sections that stayed behind.

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
// Holiday table only — compact, center-aligned.
const holidayThStyle: React.CSSProperties = { ...thStyle, textAlign: 'center', padding: '8px 10px' };
const holidayTdStyle: React.CSSProperties = { ...tdStyle, textAlign: 'center', padding: '8px 10px' };
// Mirrors CreateHolidayRequest's @Pattern on the backend: must contain at least
// one actual letter (rejects emoji-only, symbol-only, and digit-only input),
// otherwise letters (Unicode-aware — accented characters like "Deepāvali" are
// \p{L}), digits ("Independence Day 2026"), spaces, apostrophes, and hyphens
// ("New Year's Day", "Eid-ul-Fitr").
const HOLIDAY_NAME_PATTERN = /^(?=.*[\p{L}])[\p{L}\p{N} '-]+$/u;
const HOLIDAY_NAME_MAX_LENGTH = 100;

// Primary add-action button for this page — matches LeavePage's "Request Leave" button in
// size/weight rather than drifting independently.
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

const HOLIDAY_STATUS_BADGE = (active: boolean) => (
  <span style={{
    display: 'inline-flex', alignItems: 'center', gap: 4,
    padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
    color: active ? 'var(--ok)' : 'var(--txt-dim)',
  }}>
    {active ? 'Active' : 'Inactive'}
  </span>
);

function formatHolidayDate(iso: string) {
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' });
}

function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function HolidayMonthCalendar({ holidays }: { holidays: HolidayRow[] }) {
  const today = new Date();
  const [viewDate, setViewDate] = useState(new Date(today.getFullYear(), today.getMonth(), 1));

  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();
  const totalDays = daysInMonth(year, month);
  const firstWeekday = new Date(year, month, 1).getDay();
  const todayIso = toISODate(today.getFullYear(), today.getMonth(), today.getDate());
  // Group by date rather than keying a single holiday per date — two or more
  // holidays can legitimately fall on the same date (e.g. an admin viewing
  // "All Locations", or duplicate entries), and a single-value Map would
  // silently drop every holiday but the last one for that date.
  const holidaysByDate = new Map<string, HolidayRow[]>();
  for (const h of holidays) {
    const list = holidaysByDate.get(h.holidayDate);
    if (list) list.push(h);
    else holidaysByDate.set(h.holidayDate, [h]);
  }

  const cells: Array<{ day: number; iso: string } | null> = [];
  for (let i = 0; i < firstWeekday; i++) cells.push(null);
  for (let d = 1; d <= totalDays; d++) cells.push({ day: d, iso: toISODate(year, month, d) });
  while (cells.length % 7 !== 0) cells.push(null);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <button
          onClick={() => setViewDate(new Date(year, month - 1, 1))}
          aria-label="Previous month"
          style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
        >
          <ChevronLeft size={12} />
        </button>
        <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 12, color: 'var(--txt)' }}>
          {viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
        </span>
        <button
          onClick={() => setViewDate(new Date(year, month + 1, 1))}
          aria-label="Next month"
          style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
        >
          <ChevronRight size={12} />
        </button>
      </div>

      {/* minmax(0, 1fr), not plain 1fr — a plain 1fr column will still grow past its
          fair share to fit unbroken (nowrap) content, e.g. a long holiday name,
          which is what was making columns uneven / pushing the last column off
          the edge. minmax(0, 1fr) hard-caps every track at 1fr regardless of
          content, which is also what makes the ellipsis truncation below actually
          take effect (it needs a fixed-width box to truncate against). */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3, marginBottom: 4 }}>
        {WEEKDAY_LABELS.map(d => (
          <div key={d} style={{ textAlign: 'center', fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.03em', padding: '2px 0' }}>
            {d[0]}
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3 }}>
        {cells.map((c, i) => {
          if (!c) return <div key={i} />;
          const dayHolidays = holidaysByDate.get(c.iso) ?? [];
          const hasHoliday = dayHolidays.length > 0;
          const visible = dayHolidays.slice(0, 2);
          const extraCount = dayHolidays.length - visible.length;
          const isToday = c.iso === todayIso;
          return (
            <div
              key={i}
              title={dayHolidays.map(h => h.holidayName).join(', ') || undefined}
              style={{
                // Fixed height, not minHeight — the cell must never resize based on
                // content; anything beyond what fits is summarized as "+N more"
                // and overflow:hidden is a hard backstop against the rest.
                height: 74,
                minWidth: 0,
                width: '100%',
                overflow: 'hidden',
                boxSizing: 'border-box',
                borderRadius: 8,
                padding: '6px 5px',
                background: hasHoliday ? 'color-mix(in srgb, var(--warn) 16%, transparent)' : 'var(--raised)',
                border: isToday ? '2px solid var(--brand)' : hasHoliday ? '1.5px solid var(--warn)' : '1px solid var(--line)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: 3,
              }}
            >
              <span style={{ fontSize: 12, fontWeight: isToday ? 700 : 500, color: hasHoliday ? 'var(--warn)' : 'var(--txt)' }}>
                {c.day}
              </span>
              {visible.map(h => (
                <span key={h.id} style={{
                  fontSize: 9, fontWeight: 700, color: '#fff', background: 'var(--warn)',
                  borderRadius: 5, padding: '2px 5px', lineHeight: 1.4,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  maxWidth: '100%', width: '100%', boxSizing: 'border-box', flexShrink: 0,
                }}>
                  {h.holidayName}
                </span>
              ))}
              {extraCount > 0 && (
                <span style={{
                  fontSize: 8.5, fontWeight: 700, color: 'var(--warn)',
                  lineHeight: 1.3, flexShrink: 0,
                }}>
                  +{extraCount} more
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AddHolidayModal({ token, editing, onClose, onCreated }: { token: string; editing?: HolidayRow; onClose: () => void; onCreated: (locationId: string) => void }) {
  const [holidayName, setHolidayName] = useState(editing?.holidayName ?? '');
  const [holidayDate, setHolidayDate] = useState(editing?.holidayDate ?? '');
  const [locationId, setLocationId] = useState(editing?.locationId ?? '');
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);
  const todayIso = new Date().toISOString().slice(0, 10);

  useEffect(() => {
    firstRef.current?.focus();
    orgApi.listLocations(token).then(setLocations).catch(() => {});
  }, [token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const name = holidayName.trim();
    if (!name) { setError('Holiday name is required'); return; }
    if (name.length > HOLIDAY_NAME_MAX_LENGTH) { setError(`Holiday name must be ${HOLIDAY_NAME_MAX_LENGTH} characters or fewer`); return; }
    if (!HOLIDAY_NAME_PATTERN.test(name)) {
      setError('Holiday name must contain at least one letter, and only letters, numbers, spaces, apostrophes, or hyphens');
      return;
    }
    if (!holidayDate) { setError('Date is required'); return; }
    if (!editing && holidayDate < todayIso) { setError('Holiday date cannot be in the past'); return; }
    if (!locationId) { setError('Location is required'); return; }

    setLoading(true);
    try {
      if (editing) {
        await holidaysApi.updateHoliday(token, editing.id, { holidayName: name, holidayDate, locationId });
      } else {
        await holidaysApi.createHoliday(token, { holidayName: name, holidayDate, locationId });
      }
      onCreated(locationId);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Holiday' : 'Add Holiday'} onClose={onClose} />
        {error && <div style={{ margin: '16px 20px 0', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
        <form onSubmit={submit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Field label="Holiday Name *">
            <input ref={firstRef} maxLength={HOLIDAY_NAME_MAX_LENGTH} style={inputStyle} value={holidayName} onChange={e => setHolidayName(e.target.value)} placeholder="e.g. Diwali" />
          </Field>
          <Field label="Date *">
            <input type="date" min={editing ? undefined : todayIso} style={inputStyle} value={holidayDate} onChange={e => setHolidayDate(e.target.value)} />
          </Field>
          <Field label="Location *">
            <select style={inputStyle} value={locationId} onChange={e => setLocationId(e.target.value)}>
              <option value="">Select a location…</option>
              {/* Active-only for a NEW selection — a deactivated (not deleted) location shouldn't
                  be pickable going forward; `locationId` keeps this holiday's existing location
                  visible/selected if it was assigned before that location was deactivated. */}
              {locations.filter(l => l.active !== false || l.id === locationId).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>{loading ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function HolidaysPage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const isAdmin = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [holidayError, setHolidayError] = useState('');
  const [showAddHoliday, setShowAddHoliday] = useState(false);
  const [editingHoliday, setEditingHoliday] = useState<HolidayRow | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [adminLocations, setAdminLocations] = useState<LocationRow[]>([]);
  const [locationFilter, setLocationFilter] = useState(''); // admin only; '' = All Locations

  useEffect(() => {
    if (isAdmin && token) orgApi.listLocations(token).then(setAdminLocations).catch(() => {});
  }, [isAdmin, token]);

  // HR Admin/Super Admin manage holidays across locations, not just their own —
  // "my-location" would silently hide anything they create for a location that
  // isn't their own (or return nothing at all if they have no location set).
  async function fetchHolidays(overrideLocationId?: string) {
    const locId = overrideLocationId !== undefined ? overrideLocationId : locationFilter;
    setHolidayError('');
    try {
      const rows = isAdmin
        ? (locId ? await holidaysApi.listByLocation(token, locId) : await holidaysApi.listAll(token))
        : await holidaysApi.listForMyLocation(token);
      setHolidays(rows);
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to load holidays');
    }
  }

  useEffect(() => { if (token) fetchHolidays(); }, [token, isAdmin]);

  // An employee's tab may already be open when HR Admin edits/deletes a
  // holiday for their location — there's no push/websocket in this app, so
  // without this the tab would only pick up the change on its next full
  // navigation. Refetch when the tab regains focus/visibility instead of
  // requiring a manual reload.
  useEffect(() => {
    if (!token) return;
    function onFocus() { fetchHolidays(); }
    function onVisibility() { if (document.visibilityState === 'visible') fetchHolidays(); }
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, isAdmin, locationFilter]);

  async function handleDeleteHoliday(h: HolidayRow) {
    if (!window.confirm(`Delete "${h.holidayName}"? This can't be undone from here.`)) return;
    setDeletingId(h.id);
    try {
      await holidaysApi.deleteHoliday(token, h.id);
      await fetchHolidays();
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to delete holiday');
    } finally {
      setDeletingId(null);
    }
  }

  const holidayScopeLabel = isAdmin
    ? (locationFilter ? adminLocations.find(l => l.id === locationFilter)?.name : 'All Locations')
    : holidays[0]?.locationName;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
        <div>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>
            Company Holidays{holidayScopeLabel ? ` — ${holidayScopeLabel}` : ''}
          </h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
            {isAdmin ? 'Holidays across the company, by location.' : 'Holidays for your work location.'}
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          {isAdmin && (
            <select
              value={locationFilter}
              onChange={e => { const v = e.target.value; setLocationFilter(v); fetchHolidays(v); }}
              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}
            >
              <option value="">All Locations</option>
              {adminLocations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          )}
          {isAdmin && (
            <button onClick={() => setShowAddHoliday(true)} style={primaryButtonStyle}>
              <Plus size={14} /> Add Holiday
            </button>
          )}
        </div>
      </div>

      {holidayError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {holidayError}
        </div>
      )}

      <div className={holidays.length > 0 ? 'nf-grid-side-collapse' : undefined} style={holidays.length > 0 ? { display: 'grid', gridTemplateColumns: 'minmax(310px, 380px) 1fr', gap: 20, alignItems: 'start' } : undefined}>
        {holidays.length > 0 && <HolidayMonthCalendar holidays={holidays} />}

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {holidays.length === 0 ? (
            <div style={{ padding: 48, textAlign: 'center' }}>
              <CalendarDays size={28} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
                {isAdmin ? 'No holidays have been added yet.' : 'No holidays have been added for your location yet.'}
              </div>
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                <colgroup>
                  {isAdmin ? (
                    <>
                      <col style={{ width: '28%' }} />
                      <col style={{ width: '22%' }} />
                      <col style={{ width: '20%' }} />
                      <col style={{ width: '15%' }} />
                      <col style={{ width: '15%' }} />
                    </>
                  ) : (
                    <>
                      <col style={{ width: '45%' }} />
                      <col style={{ width: '35%' }} />
                      <col style={{ width: '20%' }} />
                    </>
                  )}
                </colgroup>
                <thead>
                  <tr>
                    {(isAdmin ? ['Holiday Name', 'Date', 'Location', 'Status', 'Actions'] : ['Holiday Name', 'Date', 'Status']).map(h => <th key={h} style={holidayThStyle}>{h}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {holidays.map(h => (
                    <tr key={h.id}>
                      <td style={{ ...holidayTdStyle, color: 'var(--txt)', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.holidayName}>{h.holidayName}</td>
                      <td style={holidayTdStyle}>{formatHolidayDate(h.holidayDate)}</td>
                      {isAdmin && <td style={{ ...holidayTdStyle, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.locationName}>{h.locationName}</td>}
                      <td style={holidayTdStyle}>{HOLIDAY_STATUS_BADGE(h.active)}</td>
                      {isAdmin && (
                        <td style={holidayTdStyle}>
                          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
                            <button
                              onClick={() => setEditingHoliday(h)}
                              aria-label={`Edit ${h.holidayName}`}
                              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
                            >
                              <Pencil size={12} />
                            </button>
                            <button
                              onClick={() => handleDeleteHoliday(h)}
                              disabled={deletingId === h.id}
                              aria-label={`Delete ${h.holidayName}`}
                              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: deletingId === h.id ? 'not-allowed' : 'pointer', color: 'var(--risk)', opacity: deletingId === h.id ? 0.5 : 1 }}
                            >
                              <Trash2 size={12} />
                            </button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {(showAddHoliday || editingHoliday) && (
        <AddHolidayModal
          token={token}
          editing={editingHoliday ?? undefined}
          onClose={() => { setShowAddHoliday(false); setEditingHoliday(null); }}
          onCreated={(locId) => { setLocationFilter(locId); fetchHolidays(locId); }}
        />
      )}
    </div>
  );
}
