import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users, Clock, Calendar, TrendingUp, UserCheck, X,
  ChevronLeft, ChevronRight, FileText, HelpCircle, Zap, AlertTriangle,
  CheckCircle2, CalendarCheck, BarChart2,
  Layers, ShieldCheck, CheckSquare, Megaphone, FileCheck, Shield, Package,
} from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from 'recharts';
import { useAuthStore } from '../store/authStore';
import { dashboardApi, type TeamJoiner, type ManagerDashboard } from '../api/dashboard';
import { usersApi, type EmployeeRecord } from '../api/employees';
import { auditApi, type AuditLogEntry, type AuditLogStats } from '../api/audit';
import { approvalCenterApi, type ApprovalItem } from '../api/approvalCenter';
import { getAdminKpis, type DocumentAdminKpi } from '../api/documents';
import { assetsApi } from '../api/assets';
import {
  attendanceApi,
  type AttendanceRecord, type AttendanceStats, type AttendanceConfig,
  type AttendanceStatus,
} from '../api/attendance';
import { leaveApi, type LeaveBalance, type LeaveRequestRecord } from '../api/leave';
import { myRequestsApi, type MyRequestItem } from '../api/myRequests';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { AttendanceHeroBanner } from '../components/AttendanceHeroBanner';
import { StatusBadge, inactiveDimStyle } from '../components/EmployeeStatus';
import { PieHoverTooltip } from '../components/PieHoverTooltip';
import { EmployeeAvatar } from '../components/EmployeeAvatar';

// ── Helpers ─────────────────────────────────────────────────────────────────────

// Backend LocalDateTime strings are naive wall-clock digits already in the record's own
// resolved zone (browser-reported at Check-In/Web Clock-In, see AttendanceService.resolveZone)
// — there is nothing left to convert. Parsing with 'Z' and formatting with timeZone: 'UTC' reads
// those digits back out verbatim, regardless of the *viewer's* own browser timezone. Previously
// this appended '+05:30' (assumed IST) and let toLocaleTimeString re-project into the viewer's
// local zone — for any employee whose resolved zone isn't IST, or any viewer whose browser isn't
// IST either, that mislabeled the digits and then shifted them again.
function formatClockTime(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function monthFromOffset(offset: number): { year: number; month: number; from: string; to: string } {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - offset);
  const year  = d.getFullYear();
  const month = d.getMonth() + 1;
  const pad = (n: number) => String(n).padStart(2, '0');
  const from  = `${year}-${pad(month)}-01`;
  const lastDay = new Date(year, month, 0).getDate();
  const to = `${year}-${pad(month)}-${lastDay}`;
  return { year, month, from, to };
}

function formatWorkedMinutes(mins: number | null): string {
  if (mins == null) return '—';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

function greetingPrefix(): string {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}

// ── Calendar helpers (employee attendance) ───────────────────────────────────────

interface MonthDay {
  date: string;
  isWeekend: boolean;
  isFuture: boolean;
  status: AttendanceStatus | null;
  workedMinutes: number | null;
  lateByMinutes: number | null;
  checkInAt: string | null;
  checkOutAt: string | null;
}

const DOW_NAMES = ['SUNDAY','MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY'];

function buildMonthDays(
  year: number,
  month: number,
  records: AttendanceRecord[],
  weeklyOffDays: string[],
  todayStr: string,
): MonthDay[] {
  const byDate = new Map(records.map(r => [r.workDate, r]));
  const daysInMonth = new Date(year, month, 0).getDate();
  const days: MonthDay[] = [];
  const pad = (n: number) => String(n).padStart(2, '0');
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${pad(month)}-${pad(d)}`;
    const dow = new Date(dateStr + 'T12:00:00').getDay();
    const isWeekend = weeklyOffDays.includes(DOW_NAMES[dow]);
    const isFuture  = dateStr > todayStr;
    const rec = byDate.get(dateStr);
    days.push({
      date: dateStr,
      isWeekend,
      isFuture,
      status: rec?.status ?? null,
      workedMinutes: rec?.workedMinutes ?? null,
      lateByMinutes: rec?.lateByMinutes ?? null,
      checkInAt: rec?.checkInAt ?? null,
      checkOutAt: rec?.checkOutAt ?? null,
    });
  }
  return days;
}

function calCellTint(day: MonthDay): string {
  if (day.isWeekend || day.isFuture || day.status == null) return 'var(--raised2)';
  switch (day.status) {
    case 'PRESENT':          return 'color-mix(in srgb, #2FB67C 55%, var(--raised2))';
    case 'LATE':             return 'color-mix(in srgb, #E0A93B 50%, var(--raised2))';
    case 'HALF_DAY':         return 'color-mix(in srgb, #2FB67C 28%, var(--raised2))';
    case 'ABSENT':           return 'color-mix(in srgb, #B11116 25%, var(--raised2))';
    case 'MISSING_CHECKOUT': return 'color-mix(in srgb, #F97316 55%, var(--raised2))';
    default:                 return 'var(--raised2)';
  }
}

function calCellTextColor(day: MonthDay): string {
  if (day.isWeekend || day.isFuture || day.status == null) return 'var(--txt-dim)';
  if (day.status === 'PRESENT' || day.status === 'LATE' || day.status === 'MISSING_CHECKOUT') return 'rgba(255,255,255,0.85)';
  if (day.status === 'ABSENT') return 'rgba(255,255,255,0.7)';
  return 'var(--txt-mut)';
}

function calCellTooltip(day: MonthDay): string {
  const d = new Date(day.date + 'T12:00:00');
  const label = d.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'short' });
  if (day.isWeekend) return `${label} — Off day`;
  if (day.isFuture)  return `${label} — Upcoming`;
  if (day.status == null) return `${label} — No record`;
  const statusLabel: Record<string, string> = {
    PRESENT:          'Present',
    // formatWorkedMinutes gives "1h 30m"/"1h"/"45m", consistent with the "worked" text below
    // instead of a raw, unconverted minute count.
    LATE:             `Late${day.lateByMinutes ? ` by ${formatWorkedMinutes(day.lateByMinutes)}` : ''}`,
    HALF_DAY:         'Half day',
    ABSENT:           'Absent',
    MISSING_CHECKOUT: 'Missing checkout',
  };
  const worked = day.workedMinutes != null ? ` · ${formatWorkedMinutes(day.workedMinutes)} worked` : '';
  return `${label} — ${statusLabel[day.status] ?? day.status}${worked}`;
}

// ── Shared placeholder ───────────────────────────────────────────────────────────

function PendingCard({ icon: Icon, title, note }: {
  icon: React.FC<{ size: number; style?: React.CSSProperties }>;
  title: string;
  note: string;
}) {
  return (
    <div style={{
      background: 'var(--panel)', border: '1px dashed var(--line2)', borderRadius: 10,
      padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
        <Icon size={14} style={{ color: 'var(--txt-dim)' }} />
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{title}</span>
        <span style={{ marginLeft: 'auto', fontSize: 10, fontWeight: 600, padding: '2px 7px', borderRadius: 20, background: 'rgba(107,114,128,.12)', color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', whiteSpace: 'nowrap' }}>
          Not yet built
        </span>
      </div>
      <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{note}</p>
    </div>
  );
}

// ── Manager view helpers ─────────────────────────────────────────────────────────

function buildJoinerData(joiners: TeamJoiner[]): { month: string; joiners: number }[] {
  const counts: Record<string, number> = {};
  const today = new Date();
  for (let i = 11; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    counts[key] = 0;
  }
  for (const j of joiners) {
    const key = j.joinedTeamOn.slice(0, 7);
    if (key in counts) counts[key] += 1;
  }
  return Object.entries(counts).map(([month, joiners]) => ({
    month: new Date(month + '-01').toLocaleDateString('en-US', { month: 'short', year: '2-digit' }),
    joiners,
  }));
}

function JoinersChart({ joiners, title = 'Team Joiners per Month', description = 'New team members joining per calendar month. Click for details.', modalTitle = 'Team Joiners — Last 12 Months', emptyMessage = 'No one joined your team in the last 12 months.' }: {
  joiners: TeamJoiner[];
  title?: string;
  description?: string;
  modalTitle?: string;
  emptyMessage?: string;
}) {
  const [showModal, setShowModal] = useState(false);
  const data = useMemo(() => buildJoinerData(joiners), [joiners]);

  return (
    <>
      <div
        onClick={() => setShowModal(true)}
        role="button"
        tabIndex={0}
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setShowModal(true); }}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', cursor: 'pointer' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            {title}
          </span>
          <span style={{ fontSize: 11, color: 'var(--txt-dim)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 5, padding: '2px 8px' }}>
            Last 12 months
          </span>
        </div>
        <p style={{ margin: '0 0 16px', fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
          {description}
        </p>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={data} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
            <XAxis dataKey="month" tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} allowDecimals={false} />
            <Tooltip
              contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }}
              cursor={{ fill: 'rgba(177,17,22,.08)' }}
            />
            <Bar dataKey="joiners" name="New joiners" fill="#B11116" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      {showModal && (
        <TeamJoinersModal joiners={joiners} onClose={() => setShowModal(false)} modalTitle={modalTitle} emptyMessage={emptyMessage} />
      )}
    </>
  );
}

function TeamJoinersModal({ joiners, onClose, modalTitle = 'Team Joiners — Last 12 Months', emptyMessage = 'No one joined your team in the last 12 months.' }: {
  joiners: TeamJoiner[];
  onClose: () => void;
  modalTitle?: string;
  emptyMessage?: string;
}) {
  const navigate = useNavigate();
  const data = useMemo(() => buildJoinerData(joiners), [joiners]);
  const sorted = useMemo(
    () => [...joiners].sort((a, b) => b.joinedTeamOn.localeCompare(a.joinedTeamOn)),
    [joiners]
  );

  // Same "open this person's record" navigation Shell's global search uses for anyone who
  // isn't the signed-in user — there's no separate "view someone else's profile" page, so this
  // opens their Company Directory entry instead.
  function openProfile(userId: string) {
    onClose();
    navigate(`/directory?userId=${userId}`);
  }

  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 880, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            {modalTitle}
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>

        <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px' }}>
          <div style={{ padding: 20, borderRight: '1px solid var(--line)' }}>
            <ResponsiveContainer width="100%" height={320}>
              <BarChart data={data} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                <XAxis dataKey="month" tick={{ fill: 'var(--txt-dim)', fontSize: 11 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 11 }} axisLine={false} tickLine={false} allowDecimals={false} />
                <Tooltip
                  contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }}
                  cursor={{ fill: 'rgba(177,17,22,.08)' }}
                />
                <Bar dataKey="joiners" name="New joiners" fill="#B11116" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 460, overflowY: 'auto' }}>
            <span style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
              Employees ({sorted.length})
            </span>
            {sorted.length === 0 ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>{emptyMessage}</div>
            ) : sorted.map((j, i) => (
              <div
                key={`${j.userId}-${j.joinedTeamOn}-${i}`}
                role="button"
                tabIndex={0}
                onClick={() => openProfile(j.userId)}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openProfile(j.userId); } }}
                style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '10px 12px', cursor: 'pointer', ...inactiveDimStyle(j.active) }}
                onMouseEnter={e => { (e.currentTarget as HTMLDivElement).style.background = 'var(--raised2)'; }}
                onMouseLeave={e => { (e.currentTarget as HTMLDivElement).style.background = 'var(--raised)'; }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{j.fullName}</div>
                  {!j.active && <StatusBadge active={j.active} />}
                </div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 1 }}>
                  {j.designationName ?? '—'}{j.departmentName ? ` · ${j.departmentName}` : ''}
                </div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', fontFamily: 'Inter, sans-serif', marginTop: 2 }}>{j.employeeCode}</div>
                <div style={{ fontSize: 11.5, color: 'var(--brand)', marginTop: 6 }}>
                  Joined team: {new Date(j.joinedTeamOn).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// Same avatar + bold name + employee code identity block People Directory's table rows use
// (see DirectoryPage's Avatar/row-cell styling) — replaces the plain underlined-red name link
// this used to be, so an employee "card" here reads the same way it does everywhere else in the
// app. Still navigates to the same Directory detail-panel deep link on click.
function EmployeeCard({ userId, name, code }: { userId: string; name: string; code?: string | null }) {
  const navigate = useNavigate();
  return (
    <button
      onClick={() => navigate(`/directory?userId=${userId}`)}
      style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'none', border: 'none', padding: 0, cursor: 'pointer', font: 'inherit', textAlign: 'left' }}
    >
      <EmployeeAvatar
        userId={userId}
        name={name}
        size={34}
        fontSize={34 * 0.33}
        background="rgba(177,17,22,.18)"
        color="#e4373d"
        style={{ fontFamily: 'Inter, sans-serif' }}
      />
      <div>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{name}</div>
        {code && <div style={{ fontSize: 11, color: 'var(--txt-dim)', fontFamily: 'Inter, sans-serif', marginTop: 1 }}>{code}</div>}
      </div>
    </button>
  );
}

function PresentTodayModal({ records, loading, scopeLabel, onClose }: {
  records: AttendanceRecord[];
  loading: boolean;
  scopeLabel: string;
  onClose: () => void;
}) {
  const present = useMemo(() => records.filter(r => r.checkInAt), [records]);

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 560, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            Present Today — {scopeLabel} ({present.length})
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
          {loading ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>Loading…</div>
          ) : present.length === 0 ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>No one has checked in yet today.</div>
          ) : present.map(r => (
            <div key={r.employeeUserId} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '10px 12px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
              <EmployeeCard userId={r.employeeUserId} name={r.fullName} code={r.employeeCode} />
              <div style={{ textAlign: 'right' }}>
                {/* sessionStartedAt is the latest check-in of the day; checkInAt is fixed to the
                    day's first one and never updated on a same-day checkout+checkin resume — see
                    AttendanceService.checkIn. Same fallback as AttendanceHeroBanner/AttendancePage. */}
                <div style={{ fontSize: 11.5, color: 'var(--ok)' }}>Checked in {formatClockTime(r.sessionStartedAt ?? r.checkInAt) ?? '—'}</div>
                {r.status === 'LATE' && (
                  <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--risk)', marginTop: 2 }}>
                    Late{r.lateByMinutes ? ` by ${formatWorkedMinutes(r.lateByMinutes)}` : ''}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function LeaveSection({ title, accent, rows }: { title: string; accent: string; rows: LeaveRequestRecord[] }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: accent, flexShrink: 0 }} />
        <span style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
          {title} ({rows.length})
        </span>
      </div>
      {rows.length === 0 ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '2px 0 0' }}>None today.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {rows.map(r => (
            <div key={r.id} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '10px 12px' }}>
              <EmployeeCard userId={r.employeeUserId} name={r.employeeName} code={r.employeeCode} />
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 6 }}>
                {r.leaveTypeName} · {new Date(r.startDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                {r.startDate !== r.endDate ? ` – ${new Date(r.endDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}` : ''}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function OnLeaveModal({ rows, loading, scopeLabel, onClose }: {
  rows: LeaveRequestRecord[];
  loading: boolean;
  scopeLabel: string;
  onClose: () => void;
}) {
  const fullDay = useMemo(() => rows.filter(r => !r.halfDay), [rows]);
  const halfDay = useMemo(() => rows.filter(r => r.halfDay), [rows]);

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 560, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            On Leave Today — {scopeLabel} ({rows.length})
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>
        <div style={{ padding: 20 }}>
          {loading ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>Loading…</div>
          ) : rows.length === 0 ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>No one is on leave today.</div>
          ) : (
            <>
              <LeaveSection title="Full Day Leave" accent="#B11116" rows={fullDay} />
              <LeaveSection title="Half Day Leave" accent="#E0A93B" rows={halfDay} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

type DashboardScope = 'manager' | 'hr';

function useTeamAttendanceToday(token: string, scope: DashboardScope) {
  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchToday = scope === 'hr' ? attendanceApi.day : attendanceApi.team;
    fetchToday(todayIsoDate(), token)
      .then(setRecords)
      .catch(() => setRecords([]))
      .finally(() => setLoading(false));
  }, [token, scope]);

  return { records, loading };
}

function formatRole(code: string | null | undefined): string {
  if (!code) return '—';
  return code.split('_').map(w => w[0] + w.slice(1).toLowerCase()).join(' ');
}

function TeamDashboardView({ scope }: { scope: DashboardScope }) {
  const isHr = scope === 'hr';
  const token = useAuthStore(s => s.token) ?? '';
  const user  = useAuthStore(s => s.user);
  const navigate = useNavigate();
  const [data, setData]   = useState<ManagerDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]   = useState('');
  const { records: teamToday, loading: teamLoading } = useTeamAttendanceToday(token, scope);
  const [onLeaveRows, setOnLeaveRows] = useState<LeaveRequestRecord[]>([]);
  const [onLeaveLoading, setOnLeaveLoading] = useState(true);
  const [pendingLeaveCount, setPendingLeaveCount] = useState<number | null>(null);
  const [showPresentModal, setShowPresentModal] = useState(false);
  const [showLeaveModal, setShowLeaveModal] = useState(false);

  useEffect(() => {
    const fetchDashboard = isHr ? dashboardApi.hrDashboard : dashboardApi.managerDashboard;
    fetchDashboard(token)
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : `Failed to load ${isHr ? 'organization' : 'team'} data`))
      .finally(() => setLoading(false));
  }, [token, isHr]);

  useEffect(() => {
    const today = todayIsoDate();
    const fetchOnLeave = isHr ? leaveApi.organization : leaveApi.team;
    fetchOnLeave(today, today, token)
      .then(setOnLeaveRows)
      .catch(() => setOnLeaveRows([]))
      .finally(() => setOnLeaveLoading(false));
  }, [token, isHr]);

  const onLeaveCount = useMemo(() => new Set(onLeaveRows.map(r => r.employeeUserId)).size, [onLeaveRows]);

  useEffect(() => {
    leaveApi.listApprovals(token)
      .then(rows => setPendingLeaveCount(rows.length))
      .catch(() => setPendingLeaveCount(0));
  }, [token]);

  const firstName = user?.fullName ?? user?.email?.split('@')[0] ?? (isHr ? 'there' : 'Manager');
  const presentCount = teamToday.filter(r => r.checkInAt).length;
  const lateCount = teamToday.filter(r => r.status === 'LATE').length;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          Welcome back, {firstName}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{isHr ? 'HR Dashboard' : 'Manager Dashboard'}</p>
      </div>

      <AttendanceHeroBanner />

      <QuickActions actions={isHr ? HR_ADMIN_QUICK_ACTIONS : MANAGER_QUICK_ACTIONS} />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Users size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
              {isHr ? 'Total Employees' : 'Direct Reports'}
            </span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
            {loading ? '—' : error ? '—' : (data?.directReportCount ?? 0)}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
            {isHr ? 'across the organization' : 'current reports from HR record'}
          </div>
        </div>

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <UserCheck size={14} style={{ color: 'var(--ok)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Active</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
            {loading ? '—' : error ? '—' : (data?.directReports.filter(r => r.active).length ?? 0)}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
            {isHr ? 'active org-wide' : 'active in team'}
          </div>
        </div>

        <button
          onClick={() => setShowPresentModal(true)}
          style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', textAlign: 'left', cursor: 'pointer', font: 'inherit', width: '100%' }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Clock size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Present Today</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
            {teamLoading ? '—' : `${presentCount}/${teamToday.length}`}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>checked in so far today · view list →</div>
        </button>

        <button
          onClick={() => setShowLeaveModal(true)}
          style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', textAlign: 'left', cursor: 'pointer', font: 'inherit', width: '100%' }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Calendar size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>On Leave</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
            {onLeaveLoading ? '—' : onLeaveCount}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
            on leave today · view list →
          </div>
        </button>
      </div>

      <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
            <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
              {isHr ? 'All Employees' : 'Your Team'}
            </span>
          </div>
          {loading ? (
            <div style={{ padding: '32px 20px', fontSize: 13, color: 'var(--txt-mut)' }}>Loading…</div>
          ) : error ? (
            <div style={{ padding: '20px', fontSize: 13, color: 'var(--risk)' }}>{error}</div>
          ) : !data || data.directReports.length === 0 ? (
            <div style={{ padding: '32px 20px', fontSize: 13, color: 'var(--txt-mut)' }}>
              {isHr ? 'No employees found.' : 'No direct reports assigned. HR assigns them in Employee Master.'}
            </div>
          ) : (
            // Scroll treatment (vertical scroll capped to ~4 rows, sticky header, fixed table
            // layout) matches "All Employees" (HR) exactly, for every role and every screen size —
            // previously only HR got this and Manager's "Your Team" table just grew the whole panel
            // to fit every direct report instead of scrolling.
            <div style={{
              overflowY: 'auto',
              overflowX: 'hidden',
              maxHeight: 4 * 52,
            }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                <thead>
                  <tr>
                    {(isHr ? ['Name', 'Designation', 'Role', 'Status'] : ['Name', 'Designation', 'Status']).map(h => (
                      <th
                        key={h}
                        style={{
                          padding: '8px 14px', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)',
                          textTransform: 'uppercase', letterSpacing: '.06em', textAlign: 'left',
                          borderBottom: '1px solid var(--line)', background: 'var(--raised)',
                          position: 'sticky', top: 0, zIndex: 1,
                        }}
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.directReports.map(r => (
                    <tr key={r.userId} style={{ borderBottom: '1px solid var(--line)', ...inactiveDimStyle(r.active) }}>
                      <td style={{ padding: '10px 14px', fontSize: 12.5, overflow: 'hidden' }}>
                        <div style={{ fontWeight: 600, color: 'var(--txt)', marginBottom: 1, overflowWrap: 'break-word' }}>{r.fullName}</div>
                        <div style={{ fontSize: 11, color: 'var(--txt-dim)', fontFamily: 'Inter, sans-serif' }}>{r.employeeCode}</div>
                      </td>
                      <td style={{ padding: '10px 14px', fontSize: 12, color: 'var(--txt-mut)', overflowWrap: 'break-word' }}>{r.designationName ?? '—'}</td>
                      {isHr && (
                        <td style={{ padding: '10px 14px', fontSize: 12, color: 'var(--txt-mut)', overflowWrap: 'break-word' }}>{formatRole(r.roleCode)}</td>
                      )}
                      <td style={{ padding: '10px 14px' }}>
                        <span style={{
                          display: 'inline-block', fontSize: isHr ? 10.5 : 11, fontWeight: 600,
                          padding: isHr ? '2px 6px' : '2px 8px', borderRadius: 20, whiteSpace: 'nowrap',
                          background: r.active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
                          color: r.active ? 'var(--ok)' : 'var(--txt-dim)',
                        }}>
                          {r.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <JoinersChart
          joiners={data?.teamJoiners ?? []}
          title={isHr ? 'Organization Joiners per Month' : 'Team Joiners per Month'}
          description={isHr
            ? 'New employees joining the organization per calendar month. Click for details.'
            : 'New team members joining per calendar month. Click for details.'}
          modalTitle={isHr ? 'Organization Joiners — Last 12 Months' : 'Team Joiners — Last 12 Months'}
          emptyMessage={isHr ? 'No one joined the organization in the last 12 months.' : 'No one joined your team in the last 12 months.'}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 10 }}>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <Clock size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Attendance Summary</span>
          </div>
          <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
            {teamLoading
              ? `Loading ${isHr ? 'organization' : 'team'} check-in status…`
              : `${presentCount} of ${teamToday.length} checked in today${lateCount ? `, ${lateCount} late` : ''}.`}
          </p>
          <button
            onClick={() => navigate('/attendance')}
            style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            {isHr ? 'View organization attendance →' : 'View team attendance →'}
          </button>
        </div>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <Calendar size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Pending Leave Requests</span>
          </div>
          <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
            {pendingLeaveCount === null
              ? 'Loading…'
              : pendingLeaveCount === 0
                ? 'No leave requests waiting on your approval.'
                : `${pendingLeaveCount} leave request${pendingLeaveCount === 1 ? '' : 's'} waiting on your approval.`}
          </p>
          <button
            onClick={() => navigate('/approvals?type=LEAVE')}
            style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            Review in Approval Center →
          </button>
        </div>
        <PendingCard
          icon={TrendingUp}
          title="Performance Overview"
          note="Goals and review cycles. Available once the Performance module is built."
        />
      </div>

      {showPresentModal && (
        <PresentTodayModal
          records={teamToday}
          loading={teamLoading}
          scopeLabel={isHr ? 'Organization' : 'Your Team'}
          onClose={() => setShowPresentModal(false)}
        />
      )}
      {showLeaveModal && (
        <OnLeaveModal
          rows={onLeaveRows}
          loading={onLeaveLoading}
          scopeLabel={isHr ? 'Organization' : 'Your Team'}
          onClose={() => setShowLeaveModal(false)}
        />
      )}
    </div>
  );
}

// ── Quick Actions ────────────────────────────────────────────────────────────────

interface QuickActionItem {
  icon: React.FC<{ size: number }>;
  label: string;
  sub: string;
  path: string;
  color: string;
}

function QuickActions({ actions }: { actions: QuickActionItem[] }) {
  const navigate = useNavigate();

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
      {actions.map(({ icon: Icon, label, sub, path, color }) => (
        <button
          key={label}
          onClick={() => navigate(path)}
          style={{
            background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
            padding: '16px 18px', cursor: 'pointer', textAlign: 'left',
            display: 'flex', flexDirection: 'column', gap: 8, transition: 'border-color 0.15s',
          }}
          onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.borderColor = color; }}
          onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--line)'; }}
        >
          <div style={{
            width: 34, height: 34, borderRadius: 8,
            background: `color-mix(in srgb, ${color} 12%, var(--raised2))`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color,
          }}>
            <Icon size={16} />
          </div>
          <div>
            <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 2 }}>{label}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{sub}</div>
          </div>
        </button>
      ))}
    </div>
  );
}

const EMPLOYEE_QUICK_ACTIONS: QuickActionItem[] = [
  { icon: Calendar,   label: 'Apply Leave',     sub: 'Request time off',        path: '/leave',     color: '#4E9EE8' },
  { icon: HelpCircle, label: 'Raise a Ticket',  sub: 'Report an issue or query', path: '/help',     color: '#8B5CF6' },
  { icon: Zap,        label: 'Submit Expense',   sub: 'Log reimbursement claim', path: '/assets',   color: '#E0A93B' },
  { icon: FileText,   label: 'My Requests',      sub: 'Track your submissions',  path: '/requests', color: '#2FB67C' },
];

const MANAGER_QUICK_ACTIONS: QuickActionItem[] = [
  { icon: CheckSquare, label: 'Approval Center',      sub: 'Process pending requests',  path: '/approvals',  color: '#2FB67C' },
  { icon: Users,       label: 'My Team',              sub: 'View your direct reports',  path: '/my-team',    color: '#4E9EE8' },
  { icon: Clock,       label: 'Team Attendance',      sub: 'Monitor team check-ins',    path: '/attendance', color: '#E0A93B' },
  { icon: Calendar,    label: 'Team Leave',           sub: 'Leave & holidays overview', path: '/leave',      color: '#8B5CF6' },
];

const HR_ADMIN_QUICK_ACTIONS: QuickActionItem[] = [
  { icon: Users,      label: 'Employee Master',         sub: 'View & edit employee records', path: '/employees',  color: '#4E9EE8' },
  { icon: CheckSquare,label: 'Approval Center',         sub: 'Process pending requests',     path: '/approvals',  color: '#2FB67C' },
  { icon: FileCheck,  label: 'Documents & Compliance',  sub: 'Manage org documents',         path: '/documents',  color: '#E0A93B' },
  { icon: Megaphone,  label: 'Policies',                sub: 'Announcements & policies',     path: '/policies',   color: '#8B5CF6' },
];

const SUPER_ADMIN_QUICK_ACTIONS: QuickActionItem[] = [
  { icon: Shield,      label: 'User Management',      sub: 'All users, roles & lifecycle', path: '/access',    color: '#E4373D' },
  { icon: Layers,      label: 'Org Masters',          sub: 'Departments, locations, shifts', path: '/masters', color: '#8B5CF6' },
  { icon: ShieldCheck, label: 'Audit & Security',     sub: 'System-wide security events',  path: '/audit',     color: '#E0A93B' },
  { icon: CheckSquare, label: 'Approval Center',      sub: 'Company-wide pending queue',   path: '/approvals', color: '#2FB67C' },
];

// ── Stat tiles ───────────────────────────────────────────────────────────────────

function EmployeeStatTiles({
  stats, balances, statsLoading,
}: {
  stats: AttendanceStats | null;
  balances: LeaveBalance[];
  statsLoading: boolean;
}) {
  const totalLeaveRemaining = useMemo(
    () => balances.reduce((sum, b) => sum + (b.remainingDays ?? 0), 0),
    [balances]
  );

  const tiles = [
    {
      icon: <CalendarCheck size={16} />,
      label: 'Present this month',
      value: statsLoading ? '—' : String(stats?.me.presentDays ?? 0),
      sub: 'working days checked in',
      accent: '#2FB67C',
    },
    {
      icon: <Clock size={16} />,
      label: 'Avg hours per day',
      value: statsLoading ? '—' : stats?.me.avgHoursPerDay != null
        ? formatWorkedMinutes(Math.round(stats.me.avgHoursPerDay * 60))
        : '—',
      sub: 'on days you were present',
      accent: '#4E9EE8',
    },
    {
      icon: <BarChart2 size={16} />,
      label: 'On-time arrival',
      value: statsLoading ? '—' : stats?.me.onTimeArrivalPercent != null
        ? `${Math.round(stats.me.onTimeArrivalPercent)}%`
        : '—',
      sub: 'of present days',
      accent: '#E0A93B',
    },
    {
      icon: <Calendar size={16} />,
      label: 'Leave remaining',
      value: balances.length === 0 && !statsLoading ? '—' : String(totalLeaveRemaining),
      sub: 'days across all leave types',
      accent: '#B11116',
    },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
      {tiles.map(({ icon, label, value, sub, accent }) => (
        <div key={label} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{
            width: 34, height: 34, borderRadius: 8, marginBottom: 12,
            background: `color-mix(in srgb, ${accent} 12%, var(--raised2))`,
            display: 'flex', alignItems: 'center', justifyContent: 'center', color: accent,
          }}>
            {icon}
          </div>
          <div style={{
            fontSize: 28, fontWeight: 700, color: accent, lineHeight: 1, marginBottom: 6,
            fontFamily: 'Inter, sans-serif', fontVariantNumeric: 'tabular-nums',
          }}>
            {value}
          </div>
          <div style={{ fontSize: 12, color: 'var(--txt)', fontWeight: 500, marginBottom: 2 }}>{label}</div>
          <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{sub}</div>
        </div>
      ))}
    </div>
  );
}

// ── Attendance calendar heatmap ──────────────────────────────────────────────────

const CAL_DAY_HEADERS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const CAL_CELL_PX = 48;
const CAL_GAP = 5;

function AttendanceCalendar({ token, config }: { token: string; config: AttendanceConfig | null }) {
  const [monthOffset, setMonthOffset] = useState(0);
  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [loading, setCalLoading] = useState(true);
  const todayStr = todayIsoDate();
  const MAX_OFFSET = 5;

  const { year, month, from, to, monthLabel } = useMemo(() => {
    const { year, month, from, to } = monthFromOffset(monthOffset);
    const d = new Date(year, month - 1, 1);
    const label = d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    return { year, month, from, to, monthLabel: label };
  }, [monthOffset]);

  useEffect(() => {
    setCalLoading(true);
    attendanceApi.myHistory(from, to, token)
      .then(setRecords)
      .catch(() => setRecords([]))
      .finally(() => setCalLoading(false));
  }, [from, to, token]);

  const weeklyOffDays = config?.weeklyOffDays ?? ['SATURDAY', 'SUNDAY'];
  const days = useMemo(
    () => buildMonthDays(year, month, records, weeklyOffDays, todayStr),
    [year, month, records, weeklyOffDays, todayStr]
  );

  const leadingEmpties = useMemo(() => {
    const firstDate = `${year}-${String(month).padStart(2,'0')}-01`;
    const dow = new Date(firstDate + 'T12:00:00').getDay(); // 0=Sun
    return dow === 0 ? 6 : dow - 1;
  }, [year, month]);

  const gridWidth = `calc(${CAL_CELL_PX}px * 7 + ${CAL_GAP * 6}px)`;

  const navBtnStyle = (disabled: boolean): React.CSSProperties => ({
    display: 'inline-flex', alignItems: 'center', gap: 4,
    padding: '5px 10px', borderRadius: 6,
    background: disabled ? 'transparent' : 'var(--raised2)',
    border: `1px solid ${disabled ? 'transparent' : 'var(--line)'}`,
    color: disabled ? 'var(--line2)' : 'var(--txt-mut)',
    cursor: disabled ? 'default' : 'pointer',
    fontSize: 11, fontWeight: 600,
  });

  const legend = [
    { bg: 'color-mix(in srgb, #2FB67C 55%, var(--raised2))', label: 'Present' },
    { bg: 'color-mix(in srgb, #E0A93B 50%, var(--raised2))', label: 'Late' },
    { bg: 'color-mix(in srgb, #2FB67C 28%, var(--raised2))', label: 'Half day' },
    { bg: 'color-mix(in srgb, #B11116 25%, var(--raised2))', label: 'Absent' },
    { bg: 'color-mix(in srgb, #F97316 55%, var(--raised2))', label: 'Missing checkout' },
    { bg: 'var(--raised2)', label: 'Off / future', border: '1px solid var(--line)' },
  ];

  return (
    <div className="nf-atn-cal-panel" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 18 }}>
        Attendance Calendar
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        {/* Nav */}
        <div className="nf-atn-cal-row" style={{ display: 'flex', alignItems: 'center', width: gridWidth, marginBottom: 14 }}>
          <button
            onClick={() => setMonthOffset(o => Math.min(o + 1, MAX_OFFSET))}
            disabled={monthOffset >= MAX_OFFSET}
            style={navBtnStyle(monthOffset >= MAX_OFFSET)}
          >
            <ChevronLeft size={13} /> Prev
          </button>
          <span style={{ flex: 1, textAlign: 'center', fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            {monthLabel}
          </span>
          <button
            onClick={() => setMonthOffset(o => Math.max(o - 1, 0))}
            disabled={monthOffset === 0}
            style={navBtnStyle(monthOffset === 0)}
          >
            Next <ChevronRight size={13} />
          </button>
        </div>

        {/* Day headers */}
        <div className="nf-atn-cal-row nf-atn-cal-grid" style={{ display: 'grid', gridTemplateColumns: `repeat(7, ${CAL_CELL_PX}px)`, gap: CAL_GAP, marginBottom: CAL_GAP, width: gridWidth }}>
          {CAL_DAY_HEADERS.map(d => (
            <div key={d} style={{ textAlign: 'center', fontSize: 9, color: 'var(--txt-dim)', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.07em' }}>
              {d}
            </div>
          ))}
        </div>

        {/* Month grid */}
        {loading ? (
          <div className="nf-atn-cal-row nf-atn-cal-skeleton" style={{ width: gridWidth, height: CAL_CELL_PX * 6, background: 'var(--raised2)', borderRadius: 8, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        ) : (
          <div className="nf-atn-cal-row nf-atn-cal-grid" style={{ display: 'grid', gridTemplateColumns: `repeat(7, ${CAL_CELL_PX}px)`, gap: CAL_GAP, width: gridWidth }}>
            {Array.from({ length: leadingEmpties }).map((_, i) => (
              <div key={`pad-${i}`} className="nf-atn-cal-cell" style={{ width: CAL_CELL_PX, height: CAL_CELL_PX }} />
            ))}
            {days.map(day => {
              const isToday = day.date === todayStr;
              const dayNum  = new Date(day.date + 'T12:00:00').getDate();
              return (
                <div
                  key={day.date}
                  className="nf-atn-cal-cell"
                  title={calCellTooltip(day)}
                  style={{
                    width: CAL_CELL_PX, height: CAL_CELL_PX, borderRadius: 7,
                    background: calCellTint(day),
                    border: isToday ? '2px solid var(--txt)' : '2px solid transparent',
                    boxSizing: 'border-box',
                    display: 'flex', flexDirection: 'column',
                    alignItems: 'center', justifyContent: 'center',
                    gap: 3, cursor: 'default', transition: 'filter 0.1s',
                    boxShadow: isToday ? '0 0 0 2px color-mix(in srgb, var(--txt) 15%, transparent)' : undefined,
                  }}
                  onMouseEnter={e => { (e.currentTarget as HTMLDivElement).style.filter = 'brightness(1.18)'; }}
                  onMouseLeave={e => { (e.currentTarget as HTMLDivElement).style.filter = ''; }}
                >
                  <span style={{ fontSize: 12, fontWeight: 600, lineHeight: 1, color: calCellTextColor(day), fontVariantNumeric: 'tabular-nums' }}>
                    {dayNum}
                  </span>
                  {!day.isWeekend && !day.isFuture && day.status != null && (
                    <span style={{ width: 4, height: 4, borderRadius: '50%', background: 'rgba(255,255,255,0.5)', flexShrink: 0 }} />
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Legend */}
        <div className="nf-atn-cal-row" style={{ marginTop: 14, display: 'flex', gap: 10, flexWrap: 'wrap', fontSize: 9, color: 'var(--txt-dim)', width: gridWidth }}>
          {legend.map(({ bg, label, border }) => (
            <span key={label} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: bg, border: border ?? 'none', boxSizing: 'border-box', flexShrink: 0 }} />
              {label}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── Leave balance donut ──────────────────────────────────────────────────────────

// Available/Consumed, not one slice per leave type — same dark/light brand-red pair as
// LeavePage's LeaveBalanceDonut (see there for the color rationale), so this dashboard summary
// and that per-type detail view read as the same visual language. The dashboard used to split
// the ring one slice per leave type, which was misleading with a single Annual Leave type (a
// "pie" of 100% one color) and doesn't scale to what actually matters here — how many days are
// left overall, not which type they came from. Aggregating every configured type's
// remaining/used into one Available-vs-Consumed ring shows that number directly, and still
// generalizes cleanly if more leave types get added later.
const LEAVE_DONUT_COLORS = { available: '#7A0C10', consumed: '#E8B4B6' };

function LeaveBalancePanel({ balances }: { balances: LeaveBalance[] }) {
  const configured = useMemo(() => balances.filter(b => b.totalDays > 0), [balances]);

  const totalRemaining = configured.reduce((s, b) => s + Math.max(0, Number(b.remainingDays)), 0);
  const totalQuota = configured.reduce((s, b) => s + Number(b.totalDays), 0);
  const totalConsumed = Math.max(0, totalQuota - totalRemaining);
  const data = [
    { name: 'Available', value: totalRemaining },
    { name: 'Consumed/Reserved', value: totalConsumed },
  ];
  const isEmptyQuota = totalQuota <= 0;
  const donutRef = useRef<HTMLDivElement>(null);

  return (
    <div className="nf-leave-panel" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
        Leave Balance
      </div>

      {isEmptyQuota ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0', textAlign: 'center' }}>No leave balances configured.</div>
      ) : (
        <>
          <div style={{ display: 'flex', justifyContent: 'center' }}>
            <div className="nf-leave-donut-wrap" style={{ position: 'relative', width: 160, height: 160 }} ref={donutRef}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={data}
                    cx="50%"
                    cy="50%"
                    innerRadius="62%"
                    outerRadius="87%"
                    dataKey="value"
                    startAngle={90}
                    endAngle={-270}
                    strokeWidth={0}
                  >
                    <Cell fill={LEAVE_DONUT_COLORS.available} />
                    <Cell fill={LEAVE_DONUT_COLORS.consumed} />
                  </Pie>
                  <Tooltip
                    /* Custom content (not contentStyle/formatter) so the box can flip to the left
                       of the hovered slice when that slice is on the left half of the ring —
                       Recharts' own positioning always offsets to the right. allowEscapeViewBox
                       still lets it render past this small (160px) chart's own bounds. */
                    content={props => (
                      <PieHoverTooltip
                        {...props}
                        containerRef={donutRef}
                        formatter={(val, name) => [`${val}d`, name]}
                      />
                    )}
                    allowEscapeViewBox={{ x: true, y: true }}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div style={{
                position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center', pointerEvents: 'none',
              }}>
                <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
                  {totalRemaining}
                </span>
                <span style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 2 }}>days left</span>
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: LEAVE_DONUT_COLORS.available, flexShrink: 0 }} />
              <span style={{ flex: 1, fontSize: 12, color: 'var(--txt-mut)' }}>Available</span>
              <span style={{ fontSize: 12, fontFamily: 'Inter, sans-serif', fontVariantNumeric: 'tabular-nums', color: 'var(--txt)', fontWeight: 600, minWidth: 40, textAlign: 'right' }}>
                {totalRemaining}d
              </span>
              <span style={{ fontSize: 10, color: 'var(--txt-dim)', minWidth: 48, textAlign: 'right' }}>
                / {totalQuota}d
              </span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: LEAVE_DONUT_COLORS.consumed, flexShrink: 0 }} />
              <span style={{ flex: 1, fontSize: 12, color: 'var(--txt-mut)' }}>Consumed/Reserved</span>
              <span style={{ fontSize: 12, fontFamily: 'Inter, sans-serif', fontVariantNumeric: 'tabular-nums', color: 'var(--txt)', fontWeight: 600, minWidth: 40, textAlign: 'right' }}>
                {totalConsumed}d
              </span>
              <span style={{ fontSize: 10, color: 'var(--txt-dim)', minWidth: 48, textAlign: 'right' }}>
                / {totalQuota}d
              </span>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// ── Action Needed ────────────────────────────────────────────────────────────────

function ActionNeeded({ requests }: { requests: MyRequestItem[] }) {
  const navigate = useNavigate();

  const actionItems = useMemo(
    () => requests.filter(r => r.status === 'REJECTED').slice(0, 5),
    [requests]
  );

  const REQ_LABEL: Record<string, string> = {
    LEAVE:         'Leave request',
    REGULARIZATION:'Regularization',
    WFH:           'WFH request',
    PARTIAL_DAY:   'Partial day',
    OVERTIME:      'Overtime',
  };

  if (actionItems.length === 0) {
    return (
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', display: 'flex', flexDirection: 'column', height: '100%', boxSizing: 'border-box' }}>
        <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 14 }}>
          Action Needed
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, padding: '16px 0' }}>
          <CheckCircle2 size={22} style={{ color: 'var(--ok)' }} />
          <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Nothing needs your attention</span>
        </div>
      </div>
    );
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', display: 'flex', flexDirection: 'column', height: '100%', boxSizing: 'border-box' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <AlertTriangle size={14} style={{ color: 'var(--risk)', flexShrink: 0 }} />
        <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          Action Needed
        </span>
        <span style={{ marginLeft: 'auto', fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 10, background: 'color-mix(in srgb, var(--risk) 12%, transparent)', color: 'var(--risk)' }}>
          {actionItems.length}
        </span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {actionItems.map(item => (
          <div key={item.id} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '10px 12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 3 }}>
              <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>
                {REQ_LABEL[item.requestType] ?? item.requestType}
              </span>
              <span style={{ fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 8, background: 'color-mix(in srgb, var(--risk) 12%, transparent)', color: 'var(--risk)', whiteSpace: 'nowrap' }}>
                Rejected
              </span>
            </div>
            {item.decisionReason && (
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginBottom: 4 }}>{item.decisionReason}</div>
            )}
            <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
              {item.attendanceDate
                ? new Date(item.attendanceDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
                : item.leaveStartDate
                  ? new Date(item.leaveStartDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
                  : new Date(item.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
            </div>
          </div>
        ))}
      </div>
      <button
        onClick={() => navigate('/requests')}
        style={{ marginTop: 'auto', padding: 0, paddingTop: 12, fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', cursor: 'pointer' }}
      >
        View all requests →
      </button>
    </div>
  );
}

// ── Recent Requests ──────────────────────────────────────────────────────────────

function RecentRequests({ requests }: { requests: MyRequestItem[] }) {
  const navigate = useNavigate();

  const recent = useMemo(
    () => [...requests].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5),
    [requests]
  );

  const REQ_LABEL: Record<string, string> = {
    LEAVE:         'Leave',
    REGULARIZATION:'Regularization',
    WFH:           'WFH',
    PARTIAL_DAY:   'Partial Day',
    OVERTIME:      'Overtime',
  };

  const statusStyle = (s: string): React.CSSProperties => {
    const map: Record<string, { bg: string; color: string }> = {
      PENDING:            { bg: 'color-mix(in srgb, var(--warn) 12%, transparent)',  color: 'var(--warn)' },
      APPROVED:           { bg: 'color-mix(in srgb, var(--ok) 12%, transparent)',    color: 'var(--ok)' },
      REJECTED:           { bg: 'color-mix(in srgb, var(--risk) 12%, transparent)',  color: 'var(--risk)' },
      PARTIALLY_APPROVED: { bg: 'color-mix(in srgb, var(--info) 12%, transparent)', color: 'var(--info)' },
    };
    const { bg, color } = map[s] ?? { bg: 'var(--raised2)', color: 'var(--txt-dim)' };
    return {
      display: 'inline-block', fontSize: 10, fontWeight: 700,
      padding: '2px 7px', borderRadius: 8,
      background: bg, color,
      textTransform: 'uppercase' as const, letterSpacing: '.04em',
    };
  };

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', display: 'flex', flexDirection: 'column', height: '100%', boxSizing: 'border-box' }}>
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 14 }}>
        My Recent Requests
      </div>
      {recent.length === 0 ? (
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', fontSize: 12.5, color: 'var(--txt-mut)', padding: '12px 0' }}>No requests submitted yet.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
          {recent.map((item, i) => (
            <div
              key={item.id}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '10px 0',
                borderTop: i === 0 ? 'none' : '1px solid var(--line)',
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 2 }}>
                  <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>
                    {REQ_LABEL[item.requestType] ?? item.requestType}
                  </span>
                  {item.leaveTypeName && (
                    <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>· {item.leaveTypeName}</span>
                  )}
                </div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                  {item.leaveStartDate
                    ? new Date(item.leaveStartDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
                    : item.attendanceDate
                      ? new Date(item.attendanceDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
                      : new Date(item.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                </div>
              </div>
              <span style={statusStyle(item.status)}>{item.status.replace(/_/g, ' ')}</span>
            </div>
          ))}
        </div>
      )}
      <button
        onClick={() => navigate('/requests')}
        style={{ marginTop: 'auto', padding: 0, paddingTop: 10, fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', cursor: 'pointer' }}
      >
        View all →
      </button>
    </div>
  );
}

// ── Upcoming Holidays (3-card horizontal row) ────────────────────────────────────

function UpcomingHolidays({ holidays }: { holidays: HolidayRow[] }) {
  const todayStr = todayIsoDate();

  const upcoming = useMemo(
    () => holidays
      .filter(h => h.active && h.holidayDate >= todayStr)
      .sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))
      .slice(0, 3),
    [holidays, todayStr]
  );

  const daysUntil = (dateStr: string) => {
    const d = new Date(dateStr + 'T12:00:00');
    const now = new Date();
    return Math.round((d.getTime() - now.getTime()) / 86400000);
  };

  return (
    <div className="nf-holiday-panel" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 14 }}>
        Upcoming Holidays
      </div>
      {upcoming.length === 0 ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '8px 0' }}>No upcoming holidays in your location.</div>
      ) : (
        <div className="nf-holiday-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
          {upcoming.map(h => {
            const d = new Date(h.holidayDate + 'T12:00:00');
            const diff = daysUntil(h.holidayDate);
            const soon = diff >= 0 && diff <= 7;
            const diffLabel = diff === 0 ? 'Today' : diff === 1 ? 'Tomorrow' : `In ${diff} days`;
            return (
              <div
                key={h.id}
                className="nf-holiday-card"
                style={{
                  background: soon ? 'color-mix(in srgb, var(--brand) 6%, var(--raised2))' : 'var(--raised2)',
                  border: `1px solid ${soon ? 'color-mix(in srgb, var(--brand) 20%, var(--line))' : 'var(--line)'}`,
                  borderRadius: 10, padding: '16px 16px', display: 'flex', flexDirection: 'column', gap: 10,
                }}
              >
                <div className="nf-holiday-top-row" style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
                  <div className="nf-holiday-icon" style={{
                    width: 44, height: 44, borderRadius: 8, flexShrink: 0,
                    background: soon ? 'color-mix(in srgb, var(--brand) 14%, var(--raised2))' : 'var(--panel)',
                    border: `1px solid ${soon ? 'color-mix(in srgb, var(--brand) 22%, var(--line))' : 'var(--line)'}`,
                    display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                  }}>
                    <span className="nf-holiday-month" style={{ fontSize: 8, fontWeight: 700, color: soon ? 'var(--brand)' : 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', lineHeight: 1 }}>
                      {d.toLocaleDateString('en-US', { month: 'short' })}
                    </span>
                    <span className="nf-holiday-day" style={{ fontSize: 18, fontWeight: 700, color: soon ? 'var(--brand)' : 'var(--txt)', lineHeight: 1.1, fontFamily: 'Inter, sans-serif' }}>
                      {d.getDate()}
                    </span>
                  </div>
                  {soon && (
                    <span className="nf-holiday-badge" style={{
                      fontSize: 9.5, fontWeight: 700, padding: '3px 7px', borderRadius: 10,
                      background: 'color-mix(in srgb, var(--brand) 12%, transparent)',
                      color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.05em', whiteSpace: 'nowrap',
                    }}>
                      {diffLabel}
                    </span>
                  )}
                </div>
                <div>
                  <div className="nf-holiday-name" style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {h.holidayName}
                  </div>
                  <div className="nf-holiday-date" style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                    {d.toLocaleDateString('en-US', { weekday: 'long' })}
                    {!soon && diff < 30 && <span style={{ marginLeft: 4 }}>· {diffLabel}</span>}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ── Employee Dashboard View ──────────────────────────────────────────────────────

function EmployeeDashboardView() {
  const token  = useAuthStore(s => s.token) ?? '';
  const user   = useAuthStore(s => s.user);

  const firstName = user?.fullName ?? user?.email?.split('@')[0] ?? 'there';

  const [stats, setStats]             = useState<AttendanceStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(true);
  const [balances, setBalances]       = useState<LeaveBalance[]>([]);
  const [requests, setRequests]       = useState<MyRequestItem[]>([]);
  const [holidays, setHolidays]       = useState<HolidayRow[]>([]);
  const [config, setConfig]           = useState<AttendanceConfig | null>(null);

  useEffect(() => {
    const today = todayIsoDate();
    const from  = `${today.slice(0, 8)}01`;
    attendanceApi.stats(from, today, token)
      .then(setStats)
      .catch(() => {})
      .finally(() => setStatsLoading(false));
    leaveApi.listBalances(token).then(setBalances).catch(() => {});
    myRequestsApi.list(token).then(setRequests).catch(() => {});
    holidaysApi.listForMyLocation(token).then(setHolidays).catch(() => {});
    attendanceApi.config(token).then(setConfig).catch(() => {});
  }, [token]);

  const today = new Date();
  const dateLabel = today.toLocaleDateString('en-US', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Header */}
      <div>
        <h1 style={{ margin: 0, marginBottom: 3, fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          {greetingPrefix()}, {firstName}.
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-dim)' }}>{dateLabel}</p>
      </div>

      {/* Hero Banner — self-contained, reads own attendance state */}
      <AttendanceHeroBanner />

      {/* Quick Actions */}
      <QuickActions actions={EMPLOYEE_QUICK_ACTIONS} />

      {/* Stat Tiles */}
      <EmployeeStatTiles
        stats={stats}
        balances={balances}
        statsLoading={statsLoading}
      />

      {/* Charts row: calendar (60%) + leave donut & upcoming holidays stacked (40%, combined height = calendar) */}
      <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: '3fr 2fr', gap: 16 }}>
        <AttendanceCalendar token={token} config={config} />
        <div className="nf-dash-right-col" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <LeaveBalancePanel balances={balances} />
          <UpcomingHolidays holidays={holidays} />
        </div>
      </div>

      {/* Bottom row: Action Needed | Recent Requests — both capped to 5 records and
          stretched (default grid alignment) to the same height as each other */}
      <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <ActionNeeded requests={requests} />
        <RecentRequests requests={requests} />
      </div>
    </div>
  );
}

// ── Super Admin Dashboard ────────────────────────────────────────────────────────

const ROLE_COLORS: Record<string, string> = {
  EMPLOYEE: '#2FB67C', MANAGER: '#4E9EE8', HR_ADMIN: '#E0A93B', SUPER_ADMIN: '#E4373D',
};
const ROLE_LABELS: Record<string, string> = {
  EMPLOYEE: 'Employee', MANAGER: 'Manager', HR_ADMIN: 'HR Admin', SUPER_ADMIN: 'Super Admin',
};

const APPROVAL_TYPE_COLORS: Record<string, string> = {
  LEAVE: '#4E9EE8', REGULARIZATION: '#2FB67C',
  EXPENSE: '#8B5CF6', ASSET_REQUEST: '#F97316', WFH: '#EC4899',
  PARTIAL_DAY: '#14B8A6', OVERTIME: '#6366F1', HELP_CONTENT: '#6B7280',
};
const APPROVAL_TYPE_LABELS: Record<string, string> = {
  LEAVE: 'Leave', REGULARIZATION: 'Regularization',
  EXPENSE: 'Expense', ASSET_REQUEST: 'Asset Request', WFH: 'WFH',
  PARTIAL_DAY: 'Partial Day', OVERTIME: 'Overtime', HELP_CONTENT: 'Document Review',
};
const AUDIT_GROUP_COLOR: Record<string, string> = {
  EMPLOYEE: '#4E9EE8', ATTENDANCE: '#2FB67C', LEAVE: '#E0A93B',
  EXPENSE: '#8B5CF6', ASSET: '#F97316', ACCESS: '#B11116', OTHER: '#6B7280',
};

function SuperAdminDashboardView() {
  const token = useAuthStore(s => s.token) ?? '';
  const user  = useAuthStore(s => s.user);
  const navigate = useNavigate();
  const firstName = user?.fullName ?? user?.email?.split('@')[0] ?? 'there';

  const [allUsers,           setAllUsers]           = useState<EmployeeRecord[]>([]);
  // attendanceApi.day already returns full AttendanceRecord rows (same call TeamDashboardView's
  // useTeamAttendanceToday makes for scope 'hr') — widened from the checkInAt-only shape this
  // used to be typed as, so the Present Today tile can open the same PresentTodayModal.
  const [todayRecords,       setTodayRecords]       = useState<AttendanceRecord[]>([]);
  const [showPresentModal,   setShowPresentModal]   = useState(false);
  const [pendingItems,       setPendingItems]       = useState<ApprovalItem[]>([]);
  const [auditStats,         setAuditStats]         = useState<AuditLogStats | null>(null);
  const [recentAudit,        setRecentAudit]        = useState<AuditLogEntry[]>([]);
  const [docKpis,            setDocKpis]            = useState<DocumentAdminKpi | null>(null);
  const [totalAssets,        setTotalAssets]        = useState<number | null>(null);
  const [roleChangesMonth,   setRoleChangesMonth]   = useState<number | null>(null);
  const [passwordResetsMonth,setPasswordResetsMonth]= useState<number | null>(null);
  const [loading,            setLoading]            = useState(true);

  // Wrapper refs for the two donuts below — PieHoverTooltip needs each chart's own pixel center
  // (mid-width of the div its Pie's cx="50%" is relative to) to decide which side to flip to.
  const pendingDonutRef = useRef<HTMLDivElement>(null);
  const roleDonutRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const today = todayIsoDate();
    const now = new Date();
    const monthStartStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;

    Promise.all([
      usersApi.list(token).catch(() => [] as EmployeeRecord[]),
      attendanceApi.day(today, token).catch(() => []),
      approvalCenterApi.listPending(token).catch(() => [] as ApprovalItem[]),
      auditApi.stats({}, token).catch(() => null),
      auditApi.list({}, 0, 8, token).catch(() => ({ content: [] as AuditLogEntry[] })),
      getAdminKpis(token).catch(() => null),
      assetsApi.count(token).catch(() => ({ count: 0 })),
      auditApi.list({ action: 'PASSWORD_RESET', from: monthStartStr }, 0, 1, token).catch(() => ({ totalElements: 0 })),
      auditApi.exportAll({ action: 'USER_UPDATED', from: monthStartStr }, token).catch(() => [] as AuditLogEntry[]),
    ]).then(([users, attn, pending, stats, auditPage, kpis, assets, pwResetPage, userUpdated]) => {
      setAllUsers(users as EmployeeRecord[]);
      setTodayRecords(attn as AttendanceRecord[]);
      setPendingItems(pending as ApprovalItem[]);
      setAuditStats(stats as AuditLogStats | null);
      setRecentAudit((auditPage as { content: AuditLogEntry[] }).content);
      setDocKpis(kpis as DocumentAdminKpi | null);
      setTotalAssets((assets as { count: number }).count);
      setPasswordResetsMonth((pwResetPage as { totalElements: number }).totalElements);
      const roleChanges = (userUpdated as AuditLogEntry[]).filter(e => {
        try {
          const b = JSON.parse(e.beforeState ?? 'null');
          const a = JSON.parse(e.afterState ?? 'null');
          return b && a && b.role !== a.role;
        } catch { return false; }
      }).length;
      setRoleChangesMonth(roleChanges);
    }).finally(() => setLoading(false));
  }, [token]);

  const activeUsers   = allUsers.filter(u => u.active).length;
  const inactiveUsers = allUsers.filter(u => !u.active).length;
  const presentCount  = todayRecords.filter(r => r.checkInAt).length;
  const pendingCount  = pendingItems.length;

  const monthStart = useMemo(() => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }, []);

  const pendingByType = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const item of pendingItems) {
      counts[item.requestType] = (counts[item.requestType] ?? 0) + 1;
    }
    return Object.entries(counts)
      .filter(([, v]) => v > 0)
      .map(([type, value]) => ({
        type,
        name: APPROVAL_TYPE_LABELS[type] ?? type,
        value,
        color: APPROVAL_TYPE_COLORS[type] ?? '#6B7280',
      }));
  }, [pendingItems]);

  const joinedThisMonth = useMemo(
    () => allUsers.filter(u => u.joiningDate >= monthStart).length,
    [allUsers, monthStart]
  );

  const noManagerCount = useMemo(
    () => allUsers.filter(u => u.active && !u.currentManager && u.role === 'EMPLOYEE').length,
    [allUsers]
  );

  const roleData = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const u of allUsers) {
      counts[u.role] = (counts[u.role] ?? 0) + 1;
    }
    return Object.entries(counts).map(([role, value]) => ({
      name: ROLE_LABELS[role] ?? role,
      value,
      color: ROLE_COLORS[role] ?? '#6B7280',
    }));
  }, [allUsers]);

  const statTiles = [
    {
      icon: <Users size={16} />,
      label: 'Total Users',
      value: loading ? '—' : String(allUsers.length),
      sub: loading ? '' : `${activeUsers} active · ${inactiveUsers} inactive · view directory →`,
      accent: '#4E9EE8',
      onClick: () => navigate('/directory'),
    },
    {
      icon: <UserCheck size={16} />,
      label: 'Present Today',
      value: loading ? '—' : `${presentCount}/${todayRecords.length}`,
      sub: 'org-wide check-ins · view list →',
      accent: '#2FB67C',
      onClick: () => setShowPresentModal(true),
    },
    {
      icon: <Package size={16} />,
      label: 'Total Assets',
      value: loading ? '—' : totalAssets === null ? '—' : String(totalAssets),
      sub: 'company-wide inventory · view inventory →',
      accent: '#F97316',
      onClick: () => navigate('/assets'),
    },
    {
      icon: <ShieldCheck size={16} />,
      label: 'Audit Events Today',
      value: loading ? '—' : auditStats ? String(auditStats.todayCount) : '—',
      sub: 'security events logged · view log →',
      accent: '#B11116',
      onClick: () => {
        const today = todayIsoDate();
        navigate(`/audit?from=${today}&to=${today}`);
      },
    },
  ];

  const cardStyle: React.CSSProperties = {
    background: 'var(--panel)', border: '1px solid var(--line)',
    borderRadius: 10, padding: '20px 22px',
    display: 'flex', flexDirection: 'column',
  };
  const cardTitle = (text: string) => (
    <div style={{ marginBottom: 14 }}>
      <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
        {text}
      </span>
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 3, fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          {greetingPrefix()}, {firstName}.
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-dim)' }}>Super Admin Dashboard</p>
      </div>

      <AttendanceHeroBanner />

      <QuickActions actions={SUPER_ADMIN_QUICK_ACTIONS} />

      {/* Row 2 — Stat tiles, each clickable through to its detail view */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        {statTiles.map(({ icon, label, value, sub, accent, onClick }) => (
          <button
            key={label}
            onClick={onClick}
            style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', textAlign: 'left', cursor: 'pointer', font: 'inherit', width: '100%' }}
          >
            <div style={{
              width: 34, height: 34, borderRadius: 8, marginBottom: 12,
              background: `color-mix(in srgb, ${accent} 12%, var(--raised2))`,
              display: 'flex', alignItems: 'center', justifyContent: 'center', color: accent,
            }}>
              {icon}
            </div>
            <div style={{
              fontSize: 28, fontWeight: 700, color: accent, lineHeight: 1, marginBottom: 6,
              fontFamily: 'Inter, sans-serif', fontVariantNumeric: 'tabular-nums',
            }}>
              {value}
            </div>
            <div style={{ fontSize: 12, color: 'var(--txt)', fontWeight: 500, marginBottom: 2 }}>{label}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{sub}</div>
          </button>
        ))}
      </div>

      {/* Row 3 — Pending Approvals by Type | Account Health | Users by Role */}
      <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 16, alignItems: 'stretch' }}>

        {/* Pending Approvals by Type — donut, clickable segments */}
        <div style={cardStyle}>
          {cardTitle('Pending Approvals by Type')}
          {loading ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '12px 0' }}>Loading…</div>
          ) : pendingByType.length === 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '16px 0' }}>
              <CheckCircle2 size={20} style={{ color: 'var(--ok)' }} />
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>No pending approvals</span>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
              <div style={{ display: 'flex', justifyContent: 'center' }}>
                <div style={{ position: 'relative', width: 140, height: 140 }} ref={pendingDonutRef}>
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={pendingByType}
                        cx="50%" cy="50%"
                        innerRadius="60%" outerRadius="85%"
                        dataKey="value"
                        startAngle={90} endAngle={-270}
                        strokeWidth={0}
                        onClick={(d) => navigate(`/approvals?type=${(d as unknown as { type: string }).type}`)}
                        style={{ cursor: 'pointer' }}
                      >
                        {pendingByType.map((entry, i) => (
                          <Cell key={i} fill={entry.color} />
                        ))}
                      </Pie>
                      <Tooltip
                        /* See LeaveBalancePanel's donut — same left/right-aware custom content,
                           needed because Recharts' own positioning always offsets to the right. */
                        content={props => (
                          <PieHoverTooltip
                            {...props}
                            containerRef={pendingDonutRef}
                            formatter={(val, name) => [`${val} pending`, name]}
                          />
                        )}
                        allowEscapeViewBox={{ x: true, y: true }}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
                    <span style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{pendingCount}</span>
                    <span style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 2 }}>pending</span>
                  </div>
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 12, flex: 1 }}>
                {pendingByType.map((item) => (
                  <button
                    key={item.type}
                    onClick={() => navigate(`/approvals?type=${item.type}`)}
                    style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'none', border: 'none', padding: '3px 0', cursor: 'pointer', textAlign: 'left', width: '100%' }}
                  >
                    <span style={{ width: 8, height: 8, borderRadius: 2, background: item.color, flexShrink: 0 }} />
                    <span style={{ flex: 1, fontSize: 12, color: 'var(--txt-mut)', textAlign: 'left' }}>{item.name}</span>
                    <span style={{ fontSize: 12, fontWeight: 600, color: item.color, fontVariantNumeric: 'tabular-nums' }}>{item.value}</span>
                  </button>
                ))}
              </div>
              <p style={{ margin: '10px 0 0', fontSize: 11, color: 'var(--txt-dim)' }}>Click segment or row to filter Approval Center</p>
            </div>
          )}
        </div>

        {/* Account Health — 5 metrics */}
        <div style={cardStyle}>
          {cardTitle('Account Health')}
          {loading ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '12px 0' }}>Loading…</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', flex: 1, justifyContent: 'space-between' }}>
              {[
                {
                  label: 'Joined This Month',
                  value: joinedThisMonth,
                  sub: 'new accounts by joining date',
                  color: '#2FB67C',
                },
                {
                  label: 'Inactive Accounts',
                  value: inactiveUsers,
                  sub: 'deactivated in system',
                  color: '#6B7280',
                },
                {
                  label: 'Docs Pending Verification',
                  value: docKpis?.pendingVerification ?? '—',
                  sub: docKpis ? `${docKpis.employeesWithPending} employees affected` : 'unavailable',
                  color: '#E0A93B',
                },
                {
                  label: 'Role Changes This Month',
                  value: roleChangesMonth ?? '—',
                  sub: 'users with role updated',
                  color: '#8B5CF6',
                },
                {
                  label: 'Password Resets This Month',
                  value: passwordResetsMonth ?? '—',
                  sub: 'admin-initiated resets',
                  color: '#B11116',
                },
                {
                  label: 'Employees Without Manager',
                  value: noManagerCount,
                  sub: 'active employees with no reporting line',
                  color: '#F97316',
                },
              ].map((row, i) => (
                <div key={row.label} style={{
                  display: 'flex', alignItems: 'center', gap: 12,
                  padding: '7px 0',
                  borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                }}>
                  <div style={{
                    fontSize: 22, fontWeight: 700, fontFamily: 'Inter, sans-serif',
                    fontVariantNumeric: 'tabular-nums', color: row.color,
                    minWidth: 32, lineHeight: 1,
                  }}>
                    {row.value}
                  </div>
                  <div>
                    <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)', marginBottom: 1 }}>{row.label}</div>
                    <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{row.sub}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Users by Role — UNCHANGED */}
        <div className="nf-leave-panel" style={{ ...cardStyle, gap: 16 }}>
          <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            Users by Role
          </div>
          {loading || roleData.length === 0 ? (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0', textAlign: 'center' }}>
              {loading ? 'Loading…' : 'No users found.'}
            </div>
          ) : (
            <>
              <div style={{ display: 'flex', justifyContent: 'center' }}>
                <div className="nf-leave-donut-wrap" style={{ position: 'relative', width: 160, height: 160 }} ref={roleDonutRef}>
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={roleData}
                        cx="50%"
                        cy="50%"
                        innerRadius="62%"
                        outerRadius="87%"
                        dataKey="value"
                        startAngle={90}
                        endAngle={-270}
                        strokeWidth={0}
                      >
                        {roleData.map((entry, i) => (
                          <Cell key={i} fill={entry.color} />
                        ))}
                      </Pie>
                      <Tooltip
                        /* See LeaveBalancePanel's donut — same left/right-aware custom content,
                           needed because Recharts' own positioning always offsets to the right. */
                        content={props => (
                          <PieHoverTooltip
                            {...props}
                            containerRef={roleDonutRef}
                            formatter={(val, name) => [`${val} users`, name]}
                          />
                        )}
                        allowEscapeViewBox={{ x: true, y: true }}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  <div style={{
                    position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
                    alignItems: 'center', justifyContent: 'center', pointerEvents: 'none',
                  }}>
                    <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
                      {allUsers.length}
                    </span>
                    <span style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 2 }}>total</span>
                  </div>
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {roleData.map((item) => (
                  <div key={item.name} style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
                    <span style={{ width: 8, height: 8, borderRadius: 2, background: item.color, flexShrink: 0 }} />
                    <span style={{ flex: 1, fontSize: 12, color: 'var(--txt-mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {item.name}
                    </span>
                    <span style={{ fontSize: 12, fontFamily: 'Inter, sans-serif', fontVariantNumeric: 'tabular-nums', color: 'var(--txt)', fontWeight: 600, minWidth: 24, textAlign: 'right' }}>
                      {item.value}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Recent Audit Events — UNCHANGED */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
        <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 14 }}>
          Recent Audit Events
        </div>
        {loading ? (
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '12px 0' }}>Loading…</div>
        ) : recentAudit.length === 0 ? (
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '12px 0' }}>No audit events recorded yet.</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
            {recentAudit.map((entry, i) => {
              const groupColor = AUDIT_GROUP_COLOR[entry.actionGroup] ?? '#6B7280';
              return (
                <div
                  key={entry.id}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 12,
                    padding: '10px 0',
                    borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                  }}
                >
                  <span style={{
                    fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 8,
                    background: `color-mix(in srgb, ${groupColor} 12%, transparent)`,
                    color: groupColor, textTransform: 'uppercase' as const, letterSpacing: '.04em',
                    whiteSpace: 'nowrap', flexShrink: 0,
                  }}>
                    {entry.actionGroup}
                  </span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {entry.action.replace(/_/g, ' ')}
                      {entry.targetLabel ? <span style={{ fontWeight: 400, color: 'var(--txt-mut)' }}> · {entry.targetLabel}</span> : null}
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                      {entry.actorName ?? entry.actorEmail ?? 'System'}
                    </div>
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', whiteSpace: 'nowrap', flexShrink: 0 }}>
                    {new Date(entry.occurredAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}{' '}
                    {new Date(entry.occurredAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        <button
          onClick={() => navigate('/audit')}
          style={{ marginTop: 12, fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View all in Audit & Security →
        </button>
      </div>

      {showPresentModal && (
        <PresentTodayModal
          records={todayRecords}
          loading={loading}
          scopeLabel="Organization"
          onClose={() => setShowPresentModal(false)}
        />
      )}
    </div>
  );
}

// ── Entry point ──────────────────────────────────────────────────────────────────

export default function DashboardPage() {
  const role = useAuthStore(s => s.user?.role) ?? '';
  if (role === 'MANAGER')     return <TeamDashboardView scope="manager" />;
  if (role === 'HR_ADMIN')    return <TeamDashboardView scope="hr" />;
  if (role === 'EMPLOYEE')    return <EmployeeDashboardView />;
  if (role === 'SUPER_ADMIN') return <SuperAdminDashboardView />;
  return <SuperAdminDashboardView />;
}
