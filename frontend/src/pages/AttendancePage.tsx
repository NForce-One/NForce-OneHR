import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, forwardRef } from 'react';
import { createPortal } from 'react-dom';
import { Link, useSearchParams } from 'react-router-dom';
import * as XLSX from 'xlsx';
import { Clock, LogIn, LogOut, CheckCircle2, CalendarPlus, Pencil, ShieldCheck, X, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, Download, Eye, Turtle, Laptop, Home, Sun, FileText, Users, User, ArrowDownLeft, ArrowUpRight, Wifi, Info, AlertCircle, MoreVertical, XCircle } from 'lucide-react';
import {
  attendanceApi, regularizationApi,
  type AttendanceRecord,
  type AttendanceStatus,
  type TodayAttendance,
  type AttendanceConfig,
  type AttendanceStats,
  type RegularizationRecord,
  type SubmitRegularizationPayload,
  type RegularizationBalance,
  type Punch,
} from '../api/attendance';
import {
  attendanceRequestApi,
  type AttendanceRequestRecord,
  type AttendanceRequestType,
  type AttendanceRequestStatus,
  type PartialDayMode,
  type WfhDayMode,
  type WfhBalance,
  type SubmitAttendanceRequestPayload,
} from '../api/attendanceRequests';
import { overtimeRequestApi, type OvertimeRequestRecord } from '../api/overtimeRequests';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';
import { directoryApi, type DirectoryEntry } from '../api/directory';
import { AttendancePolicyModal } from '../components/AttendancePolicyModal';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
import { profileApi } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { toShellRole } from '../lib/nav.config';
import { TimeFormatProvider, useTimeFormat, formatLateBySeconds } from '../context/TimeFormatContext';
import { minutesSinceMidnight, secondsBetween, shiftMarkerPositions, segmentBarPosition, breakMarkerPosition, resolveWorkdayWindow, punchCalendarDateIfDiffers } from '../utils/shiftMarkers';

// ─── Formatting helpers ───────────────────────────────────────────────────────
// Server timestamps are wall-clock strings in the business timezone (no offset), so they are
// formatted by slicing rather than via `new Date()` — that would re-interpret them in the
// browser's zone and shift the displayed time.
// formatTime/formatDuration themselves now live in TimeFormatContext (12h/24h-aware) — every
// consumer on this page pulls them from useTimeFormat() instead of a plain module function.

/** Index-aligned with JS Date#getDay() (0=Sunday) → java.time.DayOfWeek name, for weeklyOffDays. */
const DOW_NAMES = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

// minutesSinceMidnight now lives in ../utils/shiftMarkers (shared with the shift-boundary-marker
// positioning it also backs) — re-exported here via the import above so every existing call site
// on this page is unaffected.

function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, {
    weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  });
}

/** Compact "14 Aug" form — for the "View Available Balance" table's Period column. */
function formatShortDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

function monthStartIso(isoDate: string): string {
  const [y, m] = isoDate.split('-').map(Number);
  return isoOf(y, m - 1, 1);
}

function monthEndIso(isoDate: string): string {
  const [y, m] = isoDate.split('-').map(Number);
  return isoOf(y, m - 1, daysInMonth(y, m - 1));
}

/** Parses a zone-less server timestamp into epoch ms using the same fixed reference frame. */
function wallClockMs(iso: string): number {
  const [datePart, timePart = '00:00:00'] = iso.split('T');
  const [y, mo, d] = datePart.split('-').map(Number);
  const [h, mi, s] = timePart.split(':').map((v) => Math.floor(Number(v)));
  return Date.UTC(y, mo - 1, d, h, mi, s || 0);
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The latest of any number of ISO timestamps (nulls/undefined ignored), or null if none are set
 * — used to combine a normal session's own checkOutAt with any Web Clock-Out(s) the same work
 * day, since Web Clock-Out never writes the shared Attendance record's own checkOutAt (see
 * WebClockInService's own class Javadoc) and the displayed Check Out must reflect whichever of
 * the two actually happened most recently, however many Normal/Web cycles occurred in between. */
function latestIso(...candidates: (string | null | undefined)[]): string | null {
  const valid = candidates.filter((v): v is string => !!v);
  if (valid.length === 0) return null;
  return valid.reduce((latest, cur) => (wallClockMs(cur) > wallClockMs(latest) ? cur : latest));
}

/**
 * The day's required effective minutes — the employee's assigned shift duration (wrapping past
 * midnight for overnight shifts). Same calc as ExceptionService.computeEffectiveHoursPercent on
 * the backend, kept in sync so "100% effective hours" means the same thing on both sides. Every
 * employee is expected to always have an assigned Shift (a hard DB invariant — see
 * AttendanceConfigResponse's own Javadoc), so config.shiftEnd is null only before the config
 * fetch resolves, never for a real employee thereafter — there is deliberately no other fallback
 * here (the old global full-day-min-hours target was removed as dead code once that invariant
 * shipped).
 */
function fullDayTargetMinutesFor(config: AttendanceConfig | null): number | null {
  if (!config?.shiftEnd) return null;
  const startMin = minutesSinceMidnight(`${todayIsoDate()}T${config.shiftStart}`) ?? 0;
  const endMin = minutesSinceMidnight(`${todayIsoDate()}T${config.shiftEnd}`) ?? 0;
  return endMin <= startMin ? endMin + 1440 - startMin : endMin - startMin;
}

/** Whether a day's worked minutes reached 100% of its required effective hours target. */
function hasMetFullEffectiveHours(workedMinutes: number | null | undefined, config: AttendanceConfig | null): boolean {
  const target = fullDayTargetMinutesFor(config);
  return target != null && workedMinutes != null && workedMinutes >= target;
}

// Requirement 1 (date-window restriction): Employee/Manager/HR may only pick today or one of
// the previous REGULARIZATION_LOOKBACK_DAYS-1 days (today counts as one of the allowed days —
// e.g. 7 with today=19th allows the 19th through the 13th, blocks the 12th onward). Super Admin
// is exempt (no restriction). This mirrors RegularizationService.validateLookbackWindow on the
// backend, which is the source of truth and enforces the same rule server-side — this is a UX
// convenience only, not the actual security boundary, since the API rejects out-of-window dates
// regardless.
const REGULARIZATION_LOOKBACK_DAYS = 7;

/** "13 Aug 2026" — matches RegularizationService's NOTIFICATION_DATE_FMT ("d MMM yyyy") so the
 * cutoff date in the lookback-window error reads identically on both sides. */
function formatCutoffDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** ISO date N days before today, in the browser's local calendar. */
function isoDaysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoOf(d.getFullYear(), d.getMonth(), d.getDate());
}

/** ISO date N days after a given ISO date. */
function isoDaysAfter(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d + n);
  return isoOf(date.getFullYear(), date.getMonth(), date.getDate());
}

// ─── Month-calendar helpers ────────────────────────────────────────────────────
function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function isoOf(year: number, month: number, day: number): string {
  return `${year}-${pad2(month + 1)}-${pad2(day)}`;
}

function daysInMonth(year: number, month: number): number {
  return new Date(year, month + 1, 0).getDate();
}

function calendarMonthLabel(year: number, month: number): string {
  return new Date(year, month, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
}

/** Sun-first grid of day numbers for a month, padded with nulls to full weeks. */
function buildCalendarCells(year: number, month: number): (number | null)[] {
  const firstDow = new Date(year, month, 1).getDay();
  const total = daysInMonth(year, month);
  const cells: (number | null)[] = Array(firstDow).fill(null);
  for (let d = 1; d <= total; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

/** Expands an inclusive date range into individual ISO date strings. */
function expandDateRange(startIso: string, endIso: string): string[] {
  const dates: string[] = [];
  let cur = new Date(`${startIso}T00:00:00`);
  const end = new Date(`${endIso}T00:00:00`);
  while (cur <= end) {
    dates.push(isoOf(cur.getFullYear(), cur.getMonth(), cur.getDate()));
    cur = new Date(cur.getFullYear(), cur.getMonth(), cur.getDate() + 1);
  }
  return dates;
}

/** Regularization timestamps come back with an offset, so a plain slice is safe here. */
function fmtDateTime(dt: string | null) {
  if (!dt) return '—';
  return dt.replace('T', ' ').slice(0, 16);
}

// ─── 12-hour time text input (Partial Day "Leave at" etc.) ────────────────────
// A single free-text field — not separate hour/minute/AM-PM dropdowns. Keystrokes are
// masked into "H:MM AM/PM" as the user types, and the result is validated against a
// strict 12-hour pattern before it's allowed to become a server timestamp.

type Period = 'AM' | 'PM';
interface TimeValue { hour: string; minute: string; period: Period | ''; }

/** True only when hour, minute, and AM/PM are all present. */
function isTimeValueComplete(t: TimeValue): boolean {
  return !!(t.hour && t.minute && t.period);
}

// "9:30 AM", "09:30am", "5:45 PM" — 1-12 hour, exactly 2-digit minute, AM/PM
// case-insensitive with or without a space before it.
const TIME_TEXT_PATTERN = /^(0?[1-9]|1[0-2]):([0-5][0-9])\s?([AaPp][Mm])$/;

/** Parses free-typed text into a TimeValue, or null if it isn't (yet) a complete valid time. */
function parseTimeText(text: string): TimeValue | null {
  const match = TIME_TEXT_PATTERN.exec(text.trim());
  if (!match) return null;
  return { hour: String(parseInt(match[1], 10)), minute: match[2], period: match[3].toUpperCase() as Period };
}

/** A complete TimeValue + an ISO date -> a server-shaped "YYYY-MM-DDTHH:mm:ss" datetime. */
function timeValueToIso(date: string, t: TimeValue): string {
  let hour24 = parseInt(t.hour, 10) % 12;
  if (t.period === 'PM') hour24 += 12;
  return `${date}T${pad2(hour24)}:${t.minute}:00`;
}

/** Inverse of timeValueToIso's clock-time formatting — for pre-filling a manual entry field
 * from a previously-submitted request's stored requestedCheckIn/Out. */
function isoToTimeText(iso: string | null | undefined): string {
  if (!iso) return '';
  const timePart = iso.slice(11, 16);
  if (timePart.length < 5) return '';
  const [hStr, minute] = timePart.split(':');
  let hour = Number(hStr);
  const period: Period = hour >= 12 ? 'PM' : 'AM';
  hour = hour % 12 || 12;
  return `${hour}:${minute} ${period}`;
}

/**
 * Masks raw keystrokes into "H:MM AM/PM" as the user types: auto-inserts the ':' once the
 * typed hour is unambiguous, and completes a typed "A"/"P" to "AM"/"PM" — but only while
 * characters are being added, so backspacing through the suffix still clears it.
 */
function maskTimeInput(raw: string, previous: string): string {
  const deleting = raw.length < previous.length;
  const upper = raw.toUpperCase();
  const digits = upper.replace(/[^0-9]/g, '').slice(0, 4);
  const letter = upper.match(/[AP]/)?.[0];

  let hourPart = digits;
  let minutePart = '';
  if (digits.length > 1) {
    const firstTwo = parseInt(digits.slice(0, 2), 10);
    if (firstTwo >= 1 && firstTwo <= 12) {
      hourPart = digits.slice(0, 2);
      minutePart = digits.slice(2, 4);
    } else {
      hourPart = digits.slice(0, 1);
      minutePart = digits.slice(1, 3);
    }
  }

  let out = hourPart;
  if (digits.length > hourPart.length) out += ':' + minutePart;
  if (letter) out += (out ? ' ' : '') + (deleting ? letter : letter + 'M');
  return out;
}

/** Single masked text field for a 12-hour time — replaces separate hour/minute/AM-PM dropdowns. */
function TimeTextInput({ label, value, touched, onChange, onBlur, requiredMessage }: {
  label: string; value: string; touched: boolean; onChange: (text: string) => void; onBlur: () => void;
  /** Overrides the empty-field message — e.g. Keka's exact "Time is required" wording. */
  requiredMessage?: string;
}) {
  const empty = value.trim() === '';
  const invalidFormat = !empty && !parseTimeText(value);
  const showError = touched && (empty || invalidFormat);
  return (
    <Field label={label}>
      <input
        type="text"
        inputMode="text"
        maxLength={8}
        placeholder="e.g. 09:30 AM"
        style={{ ...inputStyle, ...(showError ? { borderColor: 'var(--risk)' } : {}) }}
        value={value}
        onChange={e => onChange(maskTimeInput(e.target.value, value))}
        onBlur={onBlur}
      />
      {showError && (
        <div style={fieldErrorStyle}>
          {empty ? (requiredMessage ?? 'This field is required.') : 'Enter a valid 12-hour time, e.g. 09:30 AM or 5:45 PM.'}
        </div>
      )}
    </Field>
  );
}

/** Groups rows by attendance-date month, newest month first, rows within a month newest first. */
function groupByMonth<T extends { attendanceDate: string }>(rows: T[]): Array<[string, T[]]> {
  const map = new Map<string, T[]>();
  for (const r of rows) {
    const key = r.attendanceDate.slice(0, 7); // yyyy-MM
    (map.get(key) ?? map.set(key, []).get(key)!).push(r);
  }
  return [...map.entries()]
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([key, group]) => [key, [...group].sort((a, b) => b.attendanceDate.localeCompare(a.attendanceDate))]);
}

function monthLabel(key: string): string {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
}

// Plain month names for the My Requests month selector — filters by calendar month across
// all years, independent of the (year+month) grouping headings groupByMonth/monthLabel drive.
const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const STATUS_COLORS: Record<AttendanceStatus, string> = {
  PRESENT: '#2FB67C',
  LATE: '#E0A93B',
  HALF_DAY: '#4C8DD6',
  ABSENT: '#E4373D',
  MISSING_CHECKOUT: '#E4373D',
};

const STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  HALF_DAY: 'Half Day',
  ABSENT: 'Absent',
  MISSING_CHECKOUT: 'Missing Check-Out',
};

const REGULARIZATION_STATUS_COLOR: Record<string, string> = {
  PENDING: '#E0A93B', PARTIALLY_APPROVED: '#3B82C4', APPROVED: '#2FB67C', REJECTED: '#E4373D',
};

/**
 * A request still in flight AND actionable by the current viewer — the only combination
 * approve/reject/bulk actions can act on; APPROVED/REJECTED are always terminal.
 *
 * PENDING is the manager stage — actionable by whoever it's assigned to (or the HR/Super Admin
 * override). PARTIALLY_APPROVED is manager-stage-complete, awaiting HR/Super Admin final
 * approval — a Manager's Approve/Reject on it would be rejected server-side (see
 * RegularizationService.approve/reject, which only lets SUPER_ADMIN/HR_ADMIN act on
 * PARTIALLY_APPROVED), so those buttons are hidden for Manager rather than shown-then-failing
 * (Requirement 2.3). HR and Super Admin keep seeing them, unchanged.
 */
function isActionableRequest(r: RegularizationRecord, isManager: boolean) {
  if (r.status === 'PENDING') return true;
  if (r.status === 'PARTIALLY_APPROVED') return !isManager;
  return false;
}

const dash = <span style={{ color: 'var(--txt-dim)' }}>—</span>;
// A recorded day with no check-in/check-out punch (forgot to swipe, etc.) reads more clearly as
// "Missing" than a bare dash — used specifically for the Check In/Check Out fields on a day that
// does have an attendance record, as opposed to `dash`'s generic "nothing to show here" meaning.
const missingPunch = <span style={{ color: 'var(--txt-dim)' }}>Missing</span>;

const thStyle: React.CSSProperties = {
  padding: '9px 12px', textAlign: 'left', fontSize: 10, fontWeight: 700,
  color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em',
  borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
};
const tdStyle: React.CSSProperties = {
  padding: '10px 12px', fontSize: 12, color: 'var(--txt-mut)',
  borderBottom: '1px solid var(--line)', verticalAlign: 'middle',
};
const panelStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden',
};
const dateInputStyle: React.CSSProperties = {
  background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '6px 9px', fontSize: 12,
};

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 12.5, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const fieldErrorStyle: React.CSSProperties = { fontSize: 10.5, color: 'var(--risk)', marginTop: 4 };

// ─── Shared bits ──────────────────────────────────────────────────────────────

/**
 * Closes a popover on any click outside `ref`'s element — the "View Available Balance"/"View
 * Details" popovers below (Regularization, WFH, Partial Day) otherwise only close via their own
 * toggle button. `onOutside` is expected to be a stable state setter (e.g. `() =>
 * setShowBalance(false)`), so it's read once at mount rather than re-subscribing every render.
 */
function useCloseOnOutsideClick(ref: React.RefObject<HTMLElement | null>, onOutside: () => void) {
  useEffect(() => {
    function onMouseDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) onOutside();
    }
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onOutside is a stable setter for the component's lifetime
  }, [ref]);
}

function StatusPill({ status }: { status: AttendanceStatus | null }) {
  if (!status) return dash;
  const color = STATUS_COLORS[status] ?? 'var(--txt-mut)';
  return (
    <span style={{
      fontSize: 10, fontWeight: 600, color,
      background: 'var(--raised)', border: '1px solid var(--line)',
      borderRadius: 4, padding: '2px 6px', whiteSpace: 'nowrap',
    }}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

/**
 * Arrival-time lateness, shown independently of the day's overall status — a short day
 * overrides `status` to HALF_DAY (see AttendanceService.checkOut), which would otherwise hide
 * the fact that the person also arrived late. `lateByMinutes` is stored regardless of that
 * override, so this reads straight from it instead of gating on `status === 'LATE'`.
 */
/**
 * Late is grace-aware: `minutes` is stored raw (time past shift start, no grace forgiveness —
 * see AttendanceService.checkIn), so a check-in inside the grace window still has minutes > 0
 * and must NOT show as late here. `minutes > graceMinutes` is exactly the backend's own
 * `isLate` check (past shiftStart + graceMinutes) re-derived from data already on hand,
 * without needing `status` (which HALF_DAY can override — see AttendanceService.checkOut). This
 * gate is deliberately still minute-based (unchanged) — only the displayed TEXT below is
 * seconds-precise, computed directly from `checkInAt`/`shiftStartAt` (both already on
 * AttendanceResponse, resolved against the record's own snapshotted Shift — see
 * shiftMarkers.ts's secondsBetween) rather than the whole-minute `minutes` figure, so "Late by
 * 50m" (49m55s previously rounded up) reads as "Late by 49m 55s" instead. Falls back to the
 * coarser minute-only text when either timestamp is missing (a legacy record).
 */
function LateBadge({ minutes, checkInAt, shiftStartAt, graceMinutes, workedMinutes, config }: {
  minutes: number | null | undefined; checkInAt?: string | null; shiftStartAt?: string | null;
  graceMinutes: number | null | undefined;
  workedMinutes?: number | null; config?: AttendanceConfig | null;
}) {
  const { formatDuration } = useTimeFormat();
  const grace = graceMinutes ?? 10;
  if (!minutes || minutes <= grace) return null;
  const fullDay = hasMetFullEffectiveHours(workedMinutes, config ?? null);
  const seconds = checkInAt && shiftStartAt ? secondsBetween(shiftStartAt, checkInAt) : null;
  const text = (seconds != null ? formatLateBySeconds(seconds) : null) ?? formatDuration(minutes);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, color: '#E0A93B' }}>
      {!fullDay && <Turtle size={18} aria-label="Late" style={{ flexShrink: 0 }} />} Late by {text}
    </div>
  );
}

function RegularizationStatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 10, fontWeight: 600, color: REGULARIZATION_STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 6px' }}>
      {status}
    </span>
  );
}

/**
 * Every check-in/check-out session for a single day, grouped by source — normal Check-In/Out and
 * Web Check-In/Out each get their own labeled section (reuses PunchSourceGroup, same grouping
 * DayPunchIntervals already uses for past days), rather than one flat interleaved list that hides
 * which entries came from where. The combined worked-time total itself is unaffected — that still
 * comes from AttendanceService.recomputeCombinedWorkedMinutes and is shown elsewhere on this page
 * as a single figure; this only changes how the per-punch list is grouped for display.
 * `refreshKey` exists solely so the caller can force a re-fetch after a punch: `date`/`token`
 * alone never change when a new punch happens today, so without it this list would only ever
 * reflect whatever was on file when the panel first mounted, not the punch that just happened.
 */
function PunchHistoryList({ date, token, refreshKey }: { date: string; token: string; refreshKey?: unknown }) {
  const [punches, setPunches] = useState<Punch[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPunches(null);
    setError(null);
    attendanceApi.punches(date, token)
      .then((p) => { if (!cancelled) setPunches(p); })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load punch history'); });
    return () => { cancelled = true; };
  }, [date, token, refreshKey]);

  if (error) {
    return <div style={{ fontSize: 11, color: 'var(--risk)' }}>Punch history: {error}</div>;
  }
  if (punches === null) {
    return <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Loading punch history…</div>;
  }
  if (punches.length === 0) return null;

  // Oldest first, matching the Keka reference (earliest punch at top, latest at bottom) — the
  // API doesn't guarantee an order, so this is display-only, not a data change.
  const sortedPunches = [...punches].sort((a, b) => a.checkInAt.localeCompare(b.checkInAt));
  const officeSessions = sortedPunches.filter(p => p.source !== 'WEB_REMOTE').map(p => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt, note: p.note }));
  const webSessions = sortedPunches.filter(p => p.source === 'WEB_REMOTE').map(p => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt, note: p.note }));

  // A single NORMAL session adds nothing beyond the Check In/Check Out bookends shown above this
  // list — but that's only true for the normal session: Web Clock-Out deliberately never writes
  // the shared Attendance record's own checkOutAt (see WebClockInService's own class Javadoc), so
  // those bookends never reflect a Web session's check-out time at all, however many Web cycles
  // there are. Suppressing this whole list whenever total punches <= 1 (the old check) therefore
  // hid the ONLY place a lone Web Clock-In/Out cycle's check-out time is ever shown. Only skip
  // when there's truly nothing beyond the bookends: at most one normal session AND no Web session.
  if (officeSessions.length <= 1 && webSessions.length === 0) return null;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>
        <Clock size={10.5} /> Punch History
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <PunchSourceGroup label="Check-In / Check-Out" sessions={officeSessions} workDate={date} />
        <PunchSourceGroup label="Web Check-In / Check-Out" sessions={webSessions} workDate={date} />
      </div>
    </div>
  );
}

function SourceTag({ source }: { source: string | null }) {
  if (!source) return dash;
  const label = source === 'REGULARIZATION' ? 'Regularized' : source === 'WEB_REMOTE' ? 'Web Remote' : 'System';
  return <span>{label}</span>;
}

function SectionHeading({ title, hint }: { title: string; hint?: string }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <h2 style={{
        fontFamily: 'Inter, sans-serif', fontSize: 13, fontWeight: 700,
        color: 'var(--txt)', margin: 0,
      }}>{title}</h2>
      {hint && <p style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>{hint}</p>}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

/**
 * Keka's exact "Oh No!!" hard-stop error card — used for every submit-time validation failure
 * across the request modals (past date, over the per-request duration ceiling, missing
 * required fields), so there's one consistent error presentation instead of a plain text line.
 * Dismissible; never blocks the fields behind it from being edited.
 */
function OhNoError({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 9, padding: '11px 12px' }}>
      <div style={{ width: 20, height: 20, borderRadius: '50%', background: '#E4373D', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginTop: 1 }}>
        <X size={12} strokeWidth={3} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 700, color: '#E4373D', fontSize: 12.5, marginBottom: 2 }}>Oh No!!</div>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{message}</div>
      </div>
      <button onClick={onDismiss} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 2, display: 'flex', flexShrink: 0 }}>
        <X size={14} />
      </button>
    </div>
  );
}

// ─── Tooltip (hover-only, portal-rendered) ─────────────────────────────────────
// No tooltip component exists in this project and no UI library (MUI/Radix/Antd/etc.) is
// installed, so this is a minimal from-scratch implementation matching the page's existing
// inline-style conventions. Rendered through a portal so it always sits above any local
// overflow/z-index context (table scroll containers, modals) rather than being clipped by them.
function Tooltip({ content, children }: { content: React.ReactNode; children: React.ReactNode }) {
  const [coords, setCoords] = useState<{ top: number; left: number; placement: 'top' | 'bottom' } | null>(null);
  const anchorRef = useRef<HTMLSpanElement>(null);
  const TOOLTIP_MAX_WIDTH = 340;
  const GAP = 8;

  function show() {
    const el = anchorRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    // Prefer above; fall back to below only when there isn't reasonably enough headroom —
    // exact fit is re-checked after render isn't needed since the tooltip is capped/wrapped.
    const placement: 'top' | 'bottom' = rect.top >= 120 + GAP ? 'top' : 'bottom';
    const maxLeft = Math.max(GAP, window.innerWidth - GAP - TOOLTIP_MAX_WIDTH);
    const left = Math.min(Math.max(rect.left, GAP), maxLeft);
    setCoords({ top: placement === 'top' ? rect.top - GAP : rect.bottom + GAP, left, placement });
  }
  function hide() {
    setCoords(null);
  }

  return (
    <>
      <span
        ref={anchorRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        style={{ display: 'block', minWidth: 0, maxWidth: '100%' }}
      >
        {children}
      </span>
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed',
            top: coords.top,
            left: coords.left,
            transform: coords.placement === 'top' ? 'translateY(-100%)' : undefined,
            maxWidth: TOOLTIP_MAX_WIDTH,
            width: 'max-content',
            background: 'var(--raised2)',
            color: 'var(--txt)',
            border: '1px solid var(--line2)',
            borderRadius: 7,
            padding: '8px 11px',
            fontSize: 11.5,
            lineHeight: 1.5,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            overflowWrap: 'anywhere',
            boxShadow: '0 8px 24px rgba(0,0,0,.35)',
            zIndex: 1000,
            pointerEvents: 'none',
          }}
        >
          {content}
        </div>,
        document.body,
      )}
    </>
  );
}

/** Single-line, ellipsis-truncated text with a hover tooltip revealing the full content. */
function TruncatedText({ text, style }: { text: string | null | undefined; style?: React.CSSProperties }) {
  if (!text) return dash;
  return (
    <Tooltip content={text}>
      <span style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '100%', ...style }}>
        {text}
      </span>
    </Tooltip>
  );
}

// ─── Request Regularization Modal (create or edit-while-pending) ──────────────
// A Selected Date / Shift Timings header, editable Corrected Check-in / Corrected Check-out
// fields (pre-filled from the day's on-file attendance record when one exists, blank otherwise
// — a date with no attendance record at all never blocks submission, only requires at least one
// of the two corrected times), a fixed "exempt this day from penalty" statement, a
// remaining-balance readout with View Details, and a single required Note. No "Assign To" field
// — the backend always routes to the employee's current reporting manager.
function RequestModal({ onClose, onSaved, token, editing, approvedDates, isSuperAdmin, initialDate }: {
  onClose: () => void;
  onSaved: (r: RegularizationRecord) => void;
  token: string;
  editing?: RegularizationRecord;
  /** Attendance dates that already have an APPROVED regularization — resubmission is blocked. */
  approvedDates: Set<string>;
  /** Super Admin is exempt from the date-window restriction below (Requirement 1). */
  isSuperAdmin: boolean;
  /** Pre-selects a date on a fresh (non-editing) request — e.g. opened from a specific day's Attendance Log entry. */
  initialDate?: string;
}) {
  const { showToast } = useToast();
  const { formatDuration } = useTimeFormat();
  const today = todayIsoDate();
  // Earliest attendance date the request window allows — not enforced via the date picker's
  // min/max (any date remains pickable there), only as a submit-time check below (and by the
  // backend, which is the actual source of truth). Super Admin has no lower bound at all — "any
  // number of previous days" per Requirement 1.
  const minDate = isSuperAdmin ? undefined : isoDaysAgo(REGULARIZATION_LOOKBACK_DAYS - 1);
  // No pre-join dates — applies to everyone, including Super Admin (unlike the lookback window
  // above): there's simply no attendance to correct before the employee existed. Mirrors
  // RegularizationService.assertNotBeforeJoiningDate, the actual boundary.
  const [joiningDate, setJoiningDate] = useState<string | null>(null);
  useEffect(() => {
    profileApi.get(token).then((p) => setJoiningDate(p.joiningDate)).catch(() => setJoiningDate(null));
  }, [token]);
  const [attendanceDate, setAttendanceDate] = useState(editing?.attendanceDate ?? initialDate ?? today);
  const [reason, setReason] = useState(editing?.reason ?? '');
  // No manual approver selection and no "Assign To" display — the backend always routes to
  // the employee's current reporting manager (EmployeeManagerHistory) when managerUserId is
  // omitted from the submit payload below.
  const [loadingPunch, setLoadingPunch] = useState(false);
  // Manual fallback for a date with no attendance record on file at all (e.g. a missed punch) —
  // pre-filled from the request being edited, if any, so re-opening an edit never loses a
  // manually-entered time.
  const [manualCheckInText, setManualCheckInText] = useState(isoToTimeText(editing?.requestedCheckIn));
  const [manualCheckOutText, setManualCheckOutText] = useState(isoToTimeText(editing?.requestedCheckOut));
  const [balance, setBalance] = useState<RegularizationBalance | null>(null);
  const [showBalanceDetails, setShowBalanceDetails] = useState(false);
  const balanceDetailsRef = useRef<HTMLDivElement>(null);
  useCloseOnOutsideClick(balanceDetailsRef, () => setShowBalanceDetails(false));
  const [submitting, setSubmitting] = useState(false);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.balance(token).then(setBalance).catch(() => setBalance(null));
  }, [token]);

  // Punch lookup: what's already on file for the chosen date, if anything — used only to seed
  // the editable Corrected Check-in/Check-out fields below with a starting value. Re-seeds
  // whenever the date changes: prefers this same request's own previously-submitted correction
  // (when editing a request already filed for this exact date) over the day's on-file punch, and
  // falls back to blank when neither exists — never blocks on a date with no punch at all.
  useEffect(() => {
    if (!attendanceDate) return;
    let cancelled = false;
    setLoadingPunch(true);
    const seedFromEditing = editing?.attendanceDate === attendanceDate;
    attendanceApi.punchForDate(attendanceDate, token)
      .then((punch) => {
        if (cancelled) return;
        setManualCheckInText(isoToTimeText(seedFromEditing ? editing?.requestedCheckIn : punch?.checkInAt));
        setManualCheckOutText(isoToTimeText(seedFromEditing ? editing?.requestedCheckOut : punch?.checkOutAt));
      })
      .catch(() => {
        if (cancelled) return;
        setManualCheckInText(isoToTimeText(seedFromEditing ? editing?.requestedCheckIn : null));
        setManualCheckOutText(isoToTimeText(seedFromEditing ? editing?.requestedCheckOut : null));
      })
      .finally(() => { if (!cancelled) setLoadingPunch(false); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `editing` is a stable prop for the modal's lifetime
  }, [attendanceDate, token]);

  const manualCheckInValue = parseTimeText(manualCheckInText);
  const manualCheckOutValue = parseTimeText(manualCheckOutText);
  const checkInInvalid = !!manualCheckInText.trim() && !manualCheckInValue;
  const checkOutInvalid = !!manualCheckOutText.trim() && !manualCheckOutValue;
  const hasManualEntry = !!manualCheckInValue || !!manualCheckOutValue;

  // Total Hours readout — only once both corrected times are entered. Checkout at or before
  // checkin's clock time on the same date means it rolls into the next calendar day (same
  // overnight-shift assumption the backend applies in RegularizationService.resolveTimes).
  const checkInMinutes = manualCheckInValue ? minutesSinceMidnight(timeValueToIso(attendanceDate, manualCheckInValue)) : null;
  const checkOutMinutes = manualCheckOutValue ? minutesSinceMidnight(timeValueToIso(attendanceDate, manualCheckOutValue)) : null;
  const totalMinutes = checkInMinutes != null && checkOutMinutes != null
    ? (checkOutMinutes <= checkInMinutes ? checkOutMinutes + 1440 - checkInMinutes : checkOutMinutes - checkInMinutes)
    : null;

  // A date that already has an APPROVED regularization can't be re-requested — editing that
  // same request (its own date, unchanged) is not a duplicate.
  const dateAlreadyApproved = !!attendanceDate
    && attendanceDate !== editing?.attendanceDate
    && approvedDates.has(attendanceDate);

  // Requirement 1: Employee/Manager/HR can only submit for a date within the last
  // REGULARIZATION_LOOKBACK_DAYS days (today counts as one) — checked only here, at submit
  // time, not via the date picker (which allows picking any date freely). Super Admin never
  // trips this check. The backend re-validates the same rule regardless — see
  // RegularizationService.validateLookbackWindow.
  const dateOutsideWindow = !isSuperAdmin && !!attendanceDate && !!minDate && attendanceDate < minDate;
  const beforeJoiningDate = !!attendanceDate && !!joiningDate && attendanceDate < joiningDate;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitAttempted(true);

    const dateMissing = !attendanceDate;
    const reasonMissing = !reason.trim();

    if (dateMissing || reasonMissing) {
      setError('Fill in every required field shown above.');
      return;
    }
    // These three are shown inline instead (see the submitAttempted-gated fields above) — just
    // block the network call here rather than duplicating the same text into the dismissible
    // banner too. Mirrors WFH's prior-notice check in AttendanceRequestModal.
    if (dateAlreadyApproved || beforeJoiningDate || dateOutsideWindow) return;
    if (checkInInvalid || checkOutInvalid) {
      setError('Enter a valid 12-hour time, e.g. 09:30 AM or 5:45 PM.');
      return;
    }
    if (!hasManualEntry) {
      setError('Enter a Corrected Check-in or Corrected Check-out time.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const payload: SubmitRegularizationPayload = {
        attendanceDate,
        requestedCheckIn: manualCheckInValue ? timeValueToIso(attendanceDate, manualCheckInValue) : undefined,
        requestedCheckOut: manualCheckOutValue ? timeValueToIso(attendanceDate, manualCheckOutValue) : undefined,
        reason: reason.trim(),
      };
      const saved = editing
        ? await regularizationApi.update(editing.id, payload, token)
        : await regularizationApi.submit(payload, token);
      onSaved(saved);
      showToast('success', editing ? 'Regularization request updated' : 'Regularization request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submission failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Regularization Request' : 'Request Attendance Regularization'} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 12.5 }}>{error}</div>}
          <div>
            <Field label="Selected Date *">
              <input type="date" style={inputStyle} value={attendanceDate}
                onChange={e => { setAttendanceDate(e.target.value); setSubmitAttempted(false); }} />
            </Field>
            {submitAttempted && !attendanceDate && <div style={fieldErrorStyle}>Attendance Date is required.</div>}
            {submitAttempted && dateAlreadyApproved && <div style={fieldErrorStyle}>Already raised regularization for this date.</div>}
            {submitAttempted && beforeJoiningDate && joiningDate && (
              <div style={fieldErrorStyle}>You joined on {formatCutoffDay(joiningDate)}. You cannot request regularization for a date before that.</div>
            )}
            {submitAttempted && !beforeJoiningDate && dateOutsideWindow && minDate && (
              <div style={fieldErrorStyle}>You are not allowed to apply regularization for this date after {formatCutoffDay(minDate)}.</div>
            )}
          </div>

          <div>
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 140 }}>
                <Field label="Corrected Check-in">
                  <input type="text" inputMode="text" maxLength={8} placeholder="e.g. 09:30 AM" disabled={loadingPunch}
                    style={{ ...inputStyle, ...(checkInInvalid ? { borderColor: 'var(--risk)' } : {}) }}
                    value={manualCheckInText}
                    onChange={e => setManualCheckInText(maskTimeInput(e.target.value, manualCheckInText))} />
                </Field>
                {checkInInvalid && <div style={fieldErrorStyle}>Enter a valid 12-hour time, e.g. 09:30 AM.</div>}
              </div>
              <div style={{ flex: 1, minWidth: 140 }}>
                <Field label="Corrected Check-out">
                  <input type="text" inputMode="text" maxLength={8} placeholder="e.g. 06:30 PM" disabled={loadingPunch}
                    style={{ ...inputStyle, ...(checkOutInvalid ? { borderColor: 'var(--risk)' } : {}) }}
                    value={manualCheckOutText}
                    onChange={e => setManualCheckOutText(maskTimeInput(e.target.value, manualCheckOutText))} />
                </Field>
                {checkOutInvalid && <div style={fieldErrorStyle}>Enter a valid 12-hour time, e.g. 06:30 PM.</div>}
              </div>
            </div>
            {totalMinutes != null && (
              <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 6 }}>
                Total Hours: <strong style={{ color: 'var(--txt)' }}>{formatDuration(totalMinutes)}</strong>
              </div>
            )}
            {submitAttempted && !hasManualEntry && !checkInInvalid && !checkOutInvalid && (
              <div style={fieldErrorStyle}>Enter a Corrected Check-in or Corrected Check-out time.</div>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <input type="radio" checked readOnly style={{ marginTop: 3 }} />
            <span style={{ fontSize: 12.5, color: 'var(--txt)' }}>
              Raise regularization request to exempt this day from penalization policy.
            </span>
          </div>

          <div ref={balanceDetailsRef} style={{ position: 'relative' }}>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 6 }}>
              <Info size={13} />
              {balance ? (
                balance.unlimited
                  ? <>Remaining balance: <strong style={{ color: 'var(--txt)' }}>Unlimited</strong></>
                  : <>Remaining balance: <strong style={{ color: 'var(--txt)' }}>{balance.remainingCount} request{balance.remainingCount === 1 ? '' : 's'}</strong></>
              ) : 'Remaining balance: —'}
              {balance && !balance.unlimited && (
                <button type="button" onClick={() => setShowBalanceDetails((s) => !s)}
                  style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                  View Details
                </button>
              )}
            </div>
            {showBalanceDetails && balance && !balance.unlimited && (
              <div style={{ position: 'absolute', top: '100%', left: 0, marginTop: 6, zIndex: 30, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 260, overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11.5 }}>
                  <thead>
                    <tr>
                      <th style={{ textAlign: 'left', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Period</th>
                      <th style={{ textAlign: 'right', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>
                        {formatShortDay(monthStartIso(attendanceDate))} - {formatShortDay(monthEndIso(attendanceDate))}
                      </td>
                      <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>
                        {balance.remainingCount}/{balance.limitCount} requests
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <Field label="Note *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="Enter note"
            />
            {submitAttempted && !reason.trim() && <div style={fieldErrorStyle}>Note is required.</div>}
          </Field>

          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            {/* Never disabled for dateAlreadyApproved/beforeJoiningDate/dateOutsideWindow — those
                are policy violations surfaced only after a Request click (see submitAttempted),
                same as WFH's prior-notice check; disabling here would block the very click that's
                supposed to reveal them. */}
            <button type="submit" disabled={submitting || loadingPunch}
              style={{
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600,
                cursor: (submitting || loadingPunch) ? 'not-allowed' : 'pointer',
                opacity: (submitting || loadingPunch) ? 0.7 : 1,
              }}>
              {submitting ? 'Saving…' : editing ? 'Save Changes' : 'Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Full, unclamped text block — no ellipsis, no hover-tooltip dependency. Long text wraps
 * naturally within the modal's width. Used for Reason/Comments in the details popup, where the
 * complete content must always be visible (unlike the table cells, which still use
 * TruncatedText — this component is deliberately separate from that one).
 */
function FullText({ text, style }: { text: string | null | undefined; style?: React.CSSProperties }) {
  if (!text) return <>{dash}</>;
  return (
    <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowWrap: 'anywhere', ...style }}>
      {text}
    </div>
  );
}

// ─── Request Details Modal (read-only — replaces the old Comments table column) ──────
function RequestDetailsModal({ request, onClose }: { request: RegularizationRecord; onClose: () => void }) {
  const { formatTime, formatDuration } = useTimeFormat();
  // Mirrors ReviewerCell's logic: who's responsible for the *next* action while still in
  // flight (assigned manager while PENDING, whichever HR/Super Admin queue while
  // PARTIALLY_APPROVED), or who made the final call once resolved.
  const approverLabel = request.status === 'PENDING' ? 'Current Approver'
    : request.status === 'PARTIALLY_APPROVED' ? 'Awaiting Final Approval From'
    : request.status === 'APPROVED' ? 'Approved By' : 'Rejected By';
  const approverName = request.status === 'PENDING' || request.status === 'PARTIALLY_APPROVED'
    ? request.assignedApproverName : request.reviewedByName;

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 460, overflowX: 'hidden' }}>
        <ModalHeader title="Regularization Request Details" onClose={onClose} />
        <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 16, maxWidth: '100%', overflowX: 'hidden' }}>
          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            <div>
              <div style={labelStyle}>Date</div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatDay(request.attendanceDate)}</div>
            </div>
            <div>
              <div style={labelStyle}>Status</div>
              <RegularizationStatusPill status={request.status} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            <div>
              <div style={labelStyle}>Requested In</div>
              <div style={{ fontSize: 13, color: 'var(--txt)' }}>{formatTime(request.requestedCheckIn) ?? dash}</div>
            </div>
            <div>
              <div style={labelStyle}>Requested Out</div>
              <div style={{ fontSize: 13, color: 'var(--txt)' }}>{formatTime(request.requestedCheckOut) ?? dash}</div>
            </div>
            <div>
              <div style={labelStyle}>Total Hours</div>
              <div style={{ fontSize: 13, color: 'var(--txt)' }}>{formatDuration(request.totalMinutes) ?? dash}</div>
            </div>
          </div>
          <div style={{ maxWidth: '100%', minWidth: 0 }}>
            <div style={labelStyle}>Reason</div>
            <FullText text={request.reason} style={{ fontSize: 12.5, color: 'var(--txt-mut)' }} />
          </div>
          <div>
            <div style={labelStyle}>{approverLabel}</div>
            <div style={{ fontSize: 13, color: 'var(--txt)' }}>{approverName ?? dash}</div>
          </div>
          {/* Two-stage audit trail — shown once each stage has actually happened, so a
              still-PENDING request shows neither and a fully-APPROVED one shows both. */}
          {request.approvedByName && (
            <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
              <div>
                <div style={labelStyle}>Approved By (Manager)</div>
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>{request.approvedByName}</div>
              </div>
              <div>
                <div style={labelStyle}>Approved At</div>
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>{fmtDateTime(request.approvedAt)}</div>
              </div>
            </div>
          )}
          {request.finalApprovedByName && (
            <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
              <div>
                <div style={labelStyle}>Final Approved By</div>
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>{request.finalApprovedByName}</div>
              </div>
              <div>
                <div style={labelStyle}>Final Approved At</div>
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>{fmtDateTime(request.finalApprovedAt)}</div>
              </div>
            </div>
          )}
          <div style={{ maxWidth: '100%', minWidth: 0 }}>
            <div style={labelStyle}>Comments</div>
            <FullText text={request.reviewComment} style={{ fontSize: 12.5, color: 'var(--txt-mut)' }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Approve Confirmation Modal ────────────────────────────────────────────────
function ConfirmApproveModal({ request, onClose, onApproved, token }: {
  request: RegularizationRecord; onClose: () => void; onApproved: (r: RegularizationRecord) => void; token: string;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleConfirm() {
    setSubmitting(true);
    try {
      const updated = await regularizationApi.approve(request.id, token, comment.trim() || undefined);
      onApproved(updated);
      showToast('success', 'Request approved and attendance record updated');
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Approve failed');
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Approve — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
            {formatDay(request.attendanceDate)} · {formatTime(request.requestedCheckIn) ?? dash} → {formatTime(request.requestedCheckOut) ?? dash}
          </div>
          <Field label="Comment (optional)">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Optional note for the employee"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleConfirm} disabled={submitting} style={{ background: '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Approving…' : 'Confirm Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Reject Modal ──────────────────────────────────────────────────────────────
function RejectModal({ request, onClose, onRejected, token }: { request: RegularizationRecord; onClose: () => void; onRejected: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReject() {
    if (!comment.trim()) { setError('A comment is required when rejecting a request.'); return; }
    setSubmitting(true); setError(null);
    try {
      const updated = await regularizationApi.reject(request.id, comment.trim(), token);
      onRejected(updated);
      showToast('success', 'Request rejected');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reject failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Reject — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 12.5 }}>{error}</div>}
          <Field label="Reason for rejection *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Explain why this request is being rejected"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleReject} disabled={submitting} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Rejecting…' : 'Reject Request'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Bulk Approve / Bulk Reject Modal ──────────────────────────────────────────
// Comment is optional for bulk-approve (mirrors ConfirmApproveModal) and required for
// bulk-reject (mirrors RejectModal) — same validation split as the single-item actions.
function BulkActionModal({ action, ids, token, onClose, onDone }: {
  action: 'APPROVE' | 'REJECT';
  ids: string[];
  token: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConfirm() {
    if (action === 'REJECT' && !comment.trim()) {
      setError('A comment is required when rejecting requests.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const result = action === 'APPROVE'
        ? await regularizationApi.bulkApprove(ids, token, comment.trim() || undefined)
        : await regularizationApi.bulkReject(ids, comment.trim(), token);
      const verb = action === 'APPROVE' ? 'approved' : 'rejected';
      if (result.succeededIds.length > 0) {
        showToast('success', `${result.succeededIds.length} request${result.succeededIds.length === 1 ? '' : 's'} ${verb}`);
      }
      if (result.failed.length > 0) {
        showToast('error', `${result.failed.length} failed — ${result.failed[0].reason}${result.failed.length > 1 ? ` (+${result.failed.length - 1} more)` : ''}`);
      }
      onDone();
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Bulk action failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  const isReject = action === 'REJECT';
  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`${isReject ? 'Reject' : 'Approve'} ${ids.length} Request${ids.length === 1 ? '' : 's'}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 12.5 }}>{error}</div>}
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
            This will {isReject ? 'reject' : 'approve'} {ids.length} selected request{ids.length === 1 ? '' : 's'}. Each is processed independently — one failure won't affect the others.
          </div>
          <Field label={isReject ? 'Reason for rejection *' : 'Comment (optional)'}>
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder={isReject ? 'Explain why these requests are being rejected' : 'Optional note applied to every selected request'}
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleConfirm}
              disabled={submitting}
              style={{ background: isReject ? '#C0392B' : '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
            >
              {submitting ? 'Processing…' : `Confirm Bulk ${isReject ? 'Reject' : 'Approve'}`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Day roster used by both the Manager and HR views. */
const ROSTER_PAGE_SIZE = 10;

function RosterTable({ rows, loading, emptyMessage }: {
  rows: AttendanceRecord[]; loading: boolean; emptyMessage: string;
}) {
  const { formatTime, formatDuration } = useTimeFormat();
  const [page, setPage] = useState(0);
  // Rows arrive fresh on every date change (see DayRoster's effect) — reset to page 1 whenever
  // the underlying list changes so a stale page index can't point past the new row count.
  useEffect(() => { setPage(0); }, [rows]);

  const totalPages = Math.ceil(rows.length / ROSTER_PAGE_SIZE);
  const paged = rows.slice(page * ROSTER_PAGE_SIZE, (page + 1) * ROSTER_PAGE_SIZE);

  return (
    <div style={panelStyle}>
      {loading ? (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
      ) : rows.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <div style={{ fontSize: 14, color: 'var(--txt-mut)', marginBottom: 8 }}>Nothing to show</div>
          <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>{emptyMessage}</div>
        </div>
      ) : (
        <>
          {/* overflowX only — the table's own vertical scroll (if any, from the page's normal
              flow) is untouched; pagination replaces unbounded row growth, not the scrollbar. */}
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Employee ID', 'Name', 'Check In', 'Check Out', 'Timezone', 'Hours', 'Status', 'Source'].map((h) => (
                    <th key={h} style={thStyle}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {paged.map((r) => (
                  <tr key={r.employeeUserId}>
                    <td style={{ ...tdStyle, fontFamily: 'Inter, sans-serif', fontSize: 11.5 }}>{r.employeeCode}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.fullName}</td>
                    {/* sessionStartedAt is the latest check-in of the day; checkInAt is fixed to
                        the day's first one and never updated on a same-day checkout+checkin
                        resume — see AttendanceService.checkIn. Same fallback as
                        AttendanceHeroBanner/the employee's own view. */}
                    <td style={tdStyle}>{formatTime(r.sessionStartedAt ?? r.checkInAt) ?? dash}</td>
                    <td style={tdStyle}>{formatTime(r.checkOutAt) ?? dash}</td>
                    {/* r.timezone is this employee's OWN effective timezone (locked in at their
                        check-in), not the viewer's — shown explicitly so an HR/Admin/Manager
                        viewing someone in a different timezone always knows which zone the Check
                        In/Out times above belong to. */}
                    <td style={{ ...tdStyle, fontSize: 11, color: 'var(--txt-dim)' }}>{r.timezone ?? dash}</td>
                    <td style={tdStyle}>{formatDuration(r.workedMinutes) ?? dash}</td>
                    <td style={tdStyle}><StatusPill status={r.status} /></td>
                    <td style={tdStyle}><SourceTag source={r.source} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
                Showing {page * ROSTER_PAGE_SIZE + 1}–{Math.min((page + 1) * ROSTER_PAGE_SIZE, rows.length)} of {rows.length}
              </span>
              <div style={{ display: 'flex', gap: 4 }}>
                <button
                  disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}
                  style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                >
                  <ChevronLeft size={13} />
                </button>
                {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                  const p = totalPages <= 7 ? i : page <= 3 ? i : page >= totalPages - 4 ? totalPages - 7 + i : page - 3 + i;
                  return (
                    <button
                      key={p}
                      onClick={() => setPage(p)}
                      style={{ padding: '5px 10px', minWidth: 32, background: page === p ? 'var(--brand)' : 'var(--raised)', border: `1px solid ${page === p ? 'var(--brand)' : 'var(--line2)'}`, borderRadius: 5, cursor: 'pointer', color: page === p ? '#fff' : 'var(--txt)', fontSize: 11.5, fontWeight: page === p ? 700 : 400 }}
                    >
                      {p + 1}
                    </button>
                  );
                })}
                <button
                  disabled={page === totalPages - 1}
                  onClick={() => setPage((p) => p + 1)}
                  style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page === totalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                >
                  <ChevronRight size={13} />
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ─── Month summary tile ────────────────────────────────────────────────────────
function MonthStatTile({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: '14px 16px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 7 }}>
        {label}
      </div>
      <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>
        {value}
      </div>
      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 5 }}>{hint}</div>
    </div>
  );
}

interface DayInfo {
  iso: string;
  day: number;
  isFuture: boolean;
  /** Before the employee's own joining date — never shown, same treatment as isFuture. */
  isBeforeJoining: boolean;
  isToday: boolean;
  isWeekend: boolean;
  holidayName?: string;
  leaveTypeName?: string;
  /** Only ever set when the request is APPROVED — pending/rejected requests get no calendar mark. */
  regularization?: RegularizationRecord;
  /** Only ever set when the WFH/Partial Day request is APPROVED. */
  attendanceRequest?: AttendanceRequestRecord;
  record?: AttendanceRecord;
}

const DAY_TAG_STYLE: React.CSSProperties = {
  display: 'inline-block', fontSize: 9.5, fontWeight: 700, padding: '1px 5px', borderRadius: 4,
  textTransform: 'uppercase', letterSpacing: '.03em', marginTop: 4, whiteSpace: 'nowrap',
};

/** Renders the small tag/status indicator inside a calendar day cell. */
/** The primary per-day badge — one of these, in precedence order. */
function primaryDayBadge(info: DayInfo): React.ReactNode {
  if (info.holidayName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>Holiday</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Leave</span>;
  }
  // Checked before the attendance-record pill: an approved regularization normally upserts
  // that same day's attendance record, so the pill would otherwise always shadow this tag.
  // info.regularization is only ever populated for APPROVED requests (see regularizationByDate),
  // so this tag is green — matching the APPROVED status color used elsewhere on the page.
  if (info.regularization) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Regularization</span>;
  }
  // Only ever set for an APPROVED WFH/Partial Day request — same reasoning as regularization above.
  if (info.attendanceRequest) {
    return info.attendanceRequest.requestType === 'WFH'
      ? <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>WFH</span>
      : <span style={{ ...DAY_TAG_STYLE, background: 'rgba(224,169,59,.18)', color: '#E0A93B' }}>Partial Day</span>;
  }
  if (info.record) {
    return <StatusPill status={info.record.status} />;
  }
  // Nothing else going on for a weekend day — Keka's reference UI shows this explicitly rather
  // than a blank cell/row.
  if (info.isWeekend) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(155,161,172,.15)', color: '#9BA1AC' }}>Weekly-off</span>;
  }
  return null;
}

function DayCellBadge({ info }: { info: DayInfo }) {
  return <>{primaryDayBadge(info)}</>;
}

function MonthCalendar({
  year, month, dayInfo, selectedDate, onSelect, onPrev, onNext,
}: {
  year: number; month: number;
  dayInfo: (day: number) => DayInfo;
  selectedDate: string | null;
  onSelect: (iso: string) => void;
  onPrev: () => void;
  onNext: () => void;
}) {
  const cells = useMemo(() => buildCalendarCells(year, month), [year, month]);

  return (
    <div style={panelStyle}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', borderBottom: '1px solid var(--line)' }}>
        <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 13, color: 'var(--txt)' }}>
          {calendarMonthLabel(year, month)}
        </span>
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={onPrev} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronLeft size={14} />
          </button>
          <button onClick={onNext} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronRight size={14} />
          </button>
        </div>
      </div>
      <div style={{ padding: 12 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 5, marginBottom: 5 }}>
          {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(d => (
            <div key={d} style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', textAlign: 'center', letterSpacing: '.05em' }}>
              {d}
            </div>
          ))}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 5 }}>
          {cells.map((day, i) => {
            if (day == null) return <div key={i} />;
            const info = dayInfo(day);
            const selected = selectedDate === info.iso;
            const disabled = info.isFuture || info.isBeforeJoining;
            return (
              <button
                key={i}
                onClick={() => !disabled && onSelect(info.iso)}
                disabled={disabled}
                style={{
                  minHeight: 58, borderRadius: 6, padding: '5px 5px', textAlign: 'left',
                  background: selected ? 'rgba(177,17,22,.10)' : 'var(--raised)',
                  border: info.isToday ? '1.5px solid var(--brand)' : selected ? '1px solid var(--brand)' : '1px solid var(--line)',
                  cursor: disabled ? 'default' : 'pointer',
                  opacity: disabled ? 0.45 : 1,
                  display: 'flex', flexDirection: 'column', gap: 2,
                }}
              >
                <span style={{ fontSize: 11, fontWeight: 600, color: info.isWeekend ? 'var(--txt-dim)' : 'var(--txt)' }}>
                  {day}
                </span>
                {!info.isBeforeJoining && <DayCellBadge info={info} />}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── My attendance (punch card + attendance calendar) ─────────────────────────

// Mirrors AttendanceRequestService.PARTIAL_DAY_MONTHLY_LIMIT_HOURS — the backend is the
// authoritative enforcement (across every non-rejected request in the month); this is only
// used for an early, single-request client-side check before hitting the network.
const PARTIAL_DAY_MONTHLY_LIMIT_HOURS = 2;

// Mirrors AttendanceRequestService.WFH_MONTHLY_LIMIT_DAYS — unlike Partial Day's advisory cap
// above, the backend enforces this one as a hard limit; this constant is only used for an early
// client-side check (and for capping the date-range width itself) before hitting the network.
const WFH_MONTHLY_LIMIT_DAYS = 2;

// Mirrors AttendanceRequestService.WFH_PRIOR_NOTICE_DAYS — matches the policy text ("requires 2
// day(s) of prior notice") and Keka's own reference behavior. The date picker itself is never
// disabled for these dates (see Keka's calendar UX) — this only drives the reactive inline
// notice and the submit-time block, exactly like the backend's own hard check.
const WFH_PRIOR_NOTICE_DAYS = 2;

const WFH_SINGLE_DAY_MODE_OPTIONS: { value: WfhDayMode; label: string }[] = [
  { value: 'FULL_DAY', label: 'Full Day' },
  { value: 'FIRST_HALF', label: 'First Half' },
  { value: 'SECOND_HALF', label: 'Second Half' },
];

/** Module-level (not a component-scoped closure) so WfhDetailDrawer can call it directly. */
function wfhDayModeLabel(mode: string | null): string {
  return WFH_SINGLE_DAY_MODE_OPTIONS.find((o) => o.value === mode)?.label ?? 'Full Day';
}
type WfhRangeMode = 'FULL_DAYS' | 'CUSTOM';
const WFH_RANGE_MODE_OPTIONS: { value: WfhRangeMode; label: string }[] = [
  { value: 'FULL_DAYS', label: 'Full Days' },
  { value: 'CUSTOM', label: 'Custom' },
];

/**
 * Keka's "Notify" field — search and pick a specific colleague to alert about this request.
 * Purely informational (distinct from the Assign To approver above it) — per product rule, only
 * shown to plain employees, not managers/HR/Super Admin.
 */
function NotifyEmployeeField({ token, value, onChange }: {
  token: string;
  value: DirectoryEntry | null;
  onChange: (entry: DirectoryEntry | null) => void;
}) {
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const [query, setQuery] = useState('');
  const [directory, setDirectory] = useState<DirectoryEntry[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (role !== 'Employee') return;
    directoryApi.list(token).then(setDirectory).catch(() => setDirectory([]));
  }, [token, role]);

  if (role !== 'Employee') return null;

  const matches = query.trim()
    ? directory
        .filter((d) => d.active !== false)
        .filter((d) => d.fullName.toLowerCase().includes(query.trim().toLowerCase()) || d.email.toLowerCase().includes(query.trim().toLowerCase()))
        .slice(0, 8)
    : [];

  return (
    <Field label="Notify (optional)">
      {value ? (
        <div style={{ ...inputStyle, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>{value.fullName}</span>
          <button onClick={() => onChange(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <X size={14} />
          </button>
        </div>
      ) : (
        <div style={{ position: 'relative' }}>
          <input
            value={query}
            onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
            onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder="Search employee…"
            style={inputStyle}
          />
          {open && matches.length > 0 && (
            <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 20, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, marginTop: 4, maxHeight: 180, overflowY: 'auto', boxShadow: '0 8px 24px rgba(0,0,0,.3)' }}>
              {matches.map((m) => (
                <div
                  key={m.userId}
                  onMouseDown={(e) => { e.preventDefault(); onChange(m); setQuery(''); setOpen(false); }}
                  style={{ padding: '8px 10px', fontSize: 12, cursor: 'pointer', color: 'var(--txt)' }}
                >
                  {m.fullName} <span style={{ color: 'var(--txt-dim)', fontSize: 10.5 }}>({m.email})</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </Field>
  );
}

/**
 * Adds/subtracts minutes from a "HH:mm[:ss]" time-of-day string, wrapping past midnight — same
 * crossing-midnight reasoning as TodaysTimingsPanel. Returns a fake ISO string so the existing
 * zone-less formatTime() can render it.
 */
function shiftClockTime(hhmmss: string, deltaMinutes: number): string {
  const base = minutesSinceMidnight(`2000-01-01T${hhmmss}`) ?? 0;
  const total = (((base + deltaMinutes) % 1440) + 1440) % 1440;
  const h = Math.floor(total / 60);
  const m = total % 60;
  return `2000-01-01T${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

const PARTIAL_DAY_MODE_OPTIONS: { value: PartialDayMode; label: string }[] = [
  { value: 'LATE_ARRIVE', label: 'Late Arrival' },
  { value: 'INTERVENING_TIMEOFF', label: 'Intervening Time-off' },
  { value: 'LEAVING_EARLY', label: 'Leaving Early' },
];

/** Keka's fixed duration presets for Intervening Time-off's "for" dropdown. */
const INTERVENING_DURATION_OPTIONS = [15, 30, 45, 60, 90, 120];

// ─── Attendance Request (WFH / Partial Day) submit modal ──────────────────────
// No manual approver selection — the backend always routes to the employee's current reporting
// manager (see resolveAssignedApprover in AttendanceRequestService) when managerUserId is
// omitted from the payload. Partial Day itself is one request type with three Keka-reference
// sub-modes (radio), not three separate request types — see PARTIAL_DAY_MODE_OPTIONS /
// AttendanceRequestService. WFH supports a day-type (Full Day/First Half/Second Half, or Custom
// per boundary day for a 2-day range) — see WFH_SINGLE_DAY_MODE_OPTIONS / WFH_RANGE_MODE_OPTIONS.
function AttendanceRequestModal({ presetType, onClose, onSaved, token, initialDate }: {
  presetType?: AttendanceRequestType;
  onClose: () => void;
  onSaved: (r: AttendanceRequestRecord) => void;
  token: string;
  /** Pre-selects a date — e.g. opened from a specific day's Attendance Log entry. */
  initialDate?: string;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const [requestType, setRequestType] = useState<AttendanceRequestType>(presetType ?? 'WFH');
  // WFH supports a Keka-style multi-day From/To range (one request submitted per day in the
  // range — the backend model is single-day only, same pattern as OvertimeRequestModal's
  // fromDate/toDate/expandDateRange). Partial Day is inherently single-day, so it only ever
  // reads `fromDate` and never shows the "To" field — see the Partial Day mode block below.
  const [fromDate, setFromDate] = useState(initialDate ?? todayIsoDate());
  const [toDate, setToDate] = useState(initialDate ?? todayIsoDate());
  // Partial Day's date can't be a range — keep `toDate` pinned to it so a later switch back to
  // WFH doesn't resurrect a stale range from before the switch.
  useEffect(() => {
    if (requestType === 'PARTIAL_DAY') setToDate(fromDate);
  }, [requestType, fromDate]);
  // WFH day-type: a single day picks Full Day/First Half/Second Half directly; a 2-day range
  // picks Full Days (every day counts as a full day) or Custom (the from-day and to-day are each
  // independently Full Day/First Half/Second Half — there's no "day in between" since the range
  // is capped at 2 days, see WFH_MONTHLY_LIMIT_DAYS).
  const [wfhSingleMode, setWfhSingleMode] = useState<WfhDayMode>('FULL_DAY');
  const [wfhRangeMode, setWfhRangeMode] = useState<WfhRangeMode>('FULL_DAYS');
  const [wfhCustomFromMode, setWfhCustomFromMode] = useState<WfhDayMode>('FIRST_HALF');
  const [wfhCustomToMode, setWfhCustomToMode] = useState<WfhDayMode>('SECOND_HALF');
  const [wfhBalance, setWfhBalance] = useState<WfhBalance | null>(null);
  const [showWfhBalance, setShowWfhBalance] = useState(false);
  const wfhBalanceRef = useRef<HTMLDivElement>(null);
  useCloseOnOutsideClick(wfhBalanceRef, () => setShowWfhBalance(false));
  const [partialDayMode, setPartialDayMode] = useState<PartialDayMode>('LATE_ARRIVE');
  const [partialDayMinutes, setPartialDayMinutes] = useState('60');
  // Intervening Time-off only — Keka anchors this mode to an explicit clock time ("Will leave
  // at") rather than a duration relative to the shift boundary, since the break can start
  // anywhere during the day.
  const [leaveAtText, setLeaveAtText] = useState('');
  const [leaveAtTouched, setLeaveAtTouched] = useState(false);
  const [reason, setReason] = useState('');
  // No manual approver selection — the backend always routes to the employee's current
  // reporting manager (EmployeeManagerHistory) when managerUserId is omitted from the payload.
  const [notifyEntry, setNotifyEntry] = useState<DirectoryEntry | null>(null);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);
  const [balance, setBalance] = useState<{ usedHours: number; limitHours: number; remainingHours: number } | null>(null);
  const [showBalance, setShowBalance] = useState(false);
  const partialBalanceRef = useRef<HTMLDivElement>(null);
  useCloseOnOutsideClick(partialBalanceRef, () => setShowBalance(false));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Gates the prior-notice banner below: stays hidden until the employee actually attempts to
  // submit, rather than firing the moment an invalid date is picked.
  const [submitAttempted, setSubmitAttempted] = useState(false);
  // Dates that already carry a non-rejected (PENDING/APPROVED) WFH request — one WFH request
  // per date is enforced, unlike Partial Day, which allows several same-day requests as long as
  // their combined minutes stay within the monthly cap (see the backend's mirrored check in
  // AttendanceRequestService.submit, the actual boundary — this is purely early UX feedback).
  const [existingWfhDates, setExistingWfhDates] = useState<Set<string>>(new Set());

  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  useEffect(() => {
    attendanceRequestApi.mine(token)
      .then(rows => setExistingWfhDates(new Set(
        rows.filter(r => r.requestType === 'WFH' && r.status !== 'REJECTED').map(r => r.requestDate),
      )))
      .catch(() => setExistingWfhDates(new Set()));
  }, [token]);

  useEffect(() => {
    if (requestType !== 'PARTIAL_DAY' || !fromDate) { setBalance(null); return; }
    attendanceRequestApi.partialDayBalance(fromDate, token).then(setBalance).catch(() => setBalance(null));
  }, [requestType, fromDate, token]);

  useEffect(() => {
    if (requestType !== 'WFH' || !fromDate) { setWfhBalance(null); return; }
    attendanceRequestApi.wfhBalance(fromDate, token).then(setWfhBalance).catch(() => setWfhBalance(null));
  }, [requestType, fromDate, token]);

  // Keka-style day count for the WFH From/To range — empty (not just zero) when To precedes
  // From, so the count badge and the submit validation below agree on what counts as invalid.
  const dateList = useMemo(
    () => requestType === 'WFH'
      ? (toDate >= fromDate ? expandDateRange(fromDate, toDate) : [])
      : (fromDate ? [fromDate] : []),
    [requestType, fromDate, toDate],
  );

  /** Which WFH day-type applies to the Nth date in dateList, per the single/range mode above. */
  const wfhModeForIndex = useCallback((index: number): WfhDayMode => {
    if (dateList.length <= 1) return wfhSingleMode;
    if (wfhRangeMode === 'FULL_DAYS') return 'FULL_DAY';
    return index === 0 ? wfhCustomFromMode : wfhCustomToMode;
  }, [dateList.length, wfhSingleMode, wfhRangeMode, wfhCustomFromMode, wfhCustomToMode]);

  const wfhDayFraction = (mode: WfhDayMode) => mode === 'FULL_DAY' ? 1 : 0.5;
  const totalWfhDays = requestType === 'WFH'
    ? dateList.reduce((sum, _d, i) => sum + wfhDayFraction(wfhModeForIndex(i)), 0)
    : 0;

  // wfhPriorNoticeViolation mirrors the backend's own hard check in
  // AttendanceRequestService.submit — it's independent of submitAttempted so handleSubmit can
  // block on it synchronously even on the very first click (setSubmitAttempted's update isn't
  // visible in this same closure until the next render). wfhPriorNoticeMessage is the UI-facing
  // gated version: the date field itself is never disabled, but an invalid pick isn't flagged
  // until Submit is actually clicked. After that first attempt, it does stay live: fixing the
  // date clears it, picking another invalid one re-shows it immediately.
  const wfhPriorNoticeFloor = isoDaysAfter(todayIsoDate(), WFH_PRIOR_NOTICE_DAYS);
  const wfhPriorNoticeViolation = requestType === 'WFH' && dateList.some(d => d < wfhPriorNoticeFloor);
  const wfhPriorNoticeMessage = submitAttempted && wfhPriorNoticeViolation
    ? `WFH request requires ${WFH_PRIOR_NOTICE_DAYS} day(s) of prior notice.`
    : null;

  // Reset mode-specific fields on switch so a stale value from one mode (e.g. a free-typed
  // number) never leaks into another mode's different widget (e.g. the fixed-option dropdown).
  useEffect(() => {
    setPartialDayMinutes('60');
    setLeaveAtText('');
    setLeaveAtTouched(false);
  }, [partialDayMode]);

  const partialDayHours = Number(partialDayMinutes) / 60;
  const remainingMinutes = balance ? Math.round(balance.remainingHours * 60) : null;
  const limitMinutes = balance ? Math.round(balance.limitHours * 60) : null;

  const leaveAtValue = parseTimeText(leaveAtText);
  const timeRequiredError = partialDayMode === 'INTERVENING_TIMEOFF' && leaveAtTouched
    && (!leaveAtValue || !isTimeValueComplete(leaveAtValue));

  // Keka's blue/green computed line — only meaningful for the two edge modes, since Intervening
  // Time-off happens mid-day and doesn't shift the reported arrival/departure clock time (it gets
  // its own balance table instead — see the "View Available Balance" popover below).
  const computedMessage = useMemo(() => {
    const minutes = Number(partialDayMinutes);
    if (!config?.shiftStart || !minutes || minutes <= 0) return null;
    if (partialDayMode === 'LATE_ARRIVE') {
      return `You will have to reach office by ${formatTime(shiftClockTime(config.shiftStart, minutes))}`;
    }
    if (partialDayMode === 'LEAVING_EARLY' && config.shiftEnd) {
      return `You can leave office at ${formatTime(shiftClockTime(config.shiftEnd, -minutes))}`;
    }
    return null;
  }, [config, partialDayMode, partialDayMinutes, formatTime]);

  /** The actual API call — invoked directly when within balance, or from the confirmation
   * dialog when the employee chooses to submit anyway despite exceeding it. */
  async function doSubmit() {
    setSubmitting(true);
    setError(null);
    try {
      // One request per day in the range — Partial Day's dateList is always exactly [fromDate].
      // Submitted sequentially (not Promise.all) so WFH's hard monthly-balance check on the
      // backend sees each prior day's row already committed before checking the next.
      const created: AttendanceRequestRecord[] = [];
      for (let i = 0; i < dateList.length; i++) {
        const date = dateList[i];
        const payload: SubmitAttendanceRequestPayload = {
          requestType,
          requestDate: date,
          reason: reason.trim(),
          partialDayHours: requestType === 'PARTIAL_DAY' ? partialDayHours : undefined,
          partialDayMode: requestType === 'PARTIAL_DAY' ? partialDayMode
            : requestType === 'WFH' ? wfhModeForIndex(i) : undefined,
          notifyUserId: notifyEntry?.userId || undefined,
        };
        created.push(await attendanceRequestApi.submit(payload, token));
      }
      const typeLabel = requestType === 'WFH' ? 'Work From Home' : 'Partial Day';
      showToast('success', created.length > 1 ? `${created.length} ${typeLabel} requests submitted for approval` : `${typeLabel} request submitted for approval`);
      created.forEach(onSaved);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to submit request';
      setError(msg);
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSubmit() {
    setSubmitAttempted(true);
    if (!reason.trim()) { setError('Reason is required'); return; }
    // Any date is selectable while typing (no min/max on the field) — validated only now, at
    // submit time.
    if (requestType === 'WFH' && toDate < fromDate) {
      setError('To date must be on or after From date');
      return;
    }
    if (fromDate < todayIsoDate()) {
      setError('Cannot request for past dates');
      return;
    }
    // Uses the submitAttempted-independent flag, not wfhPriorNoticeMessage — this runs in the
    // same click that just called setSubmitAttempted(true) above, whose update isn't visible in
    // this closure until the next render. The banner itself (see wfhPriorNoticeMessage) is what
    // surfaces this to the employee; block the network call here without duplicating the text
    // into the dismissible submit-error banner too.
    if (wfhPriorNoticeViolation) return;
    if (requestType === 'WFH' && dateList.length > WFH_MONTHLY_LIMIT_DAYS) {
      setError(`Work From Home requests can span at most ${WFH_MONTHLY_LIMIT_DAYS} days.`);
      return;
    }
    // One WFH request per date (full or half day alike) — mirrors the backend's hard check in
    // AttendanceRequestService.submit, the actual boundary.
    const duplicateWfhDate = requestType === 'WFH' ? dateList.find(d => existingWfhDates.has(d)) : undefined;
    if (duplicateWfhDate) {
      setError(`You already have a Work From Home request for ${formatShortDay(duplicateWfhDate)}.`);
      return;
    }
    if (requestType === 'WFH' && wfhBalance && totalWfhDays > wfhBalance.remainingDays) {
      setError(`This request exceeds your remaining Work From Home balance of ${wfhBalance.remainingDays} day(s) for this month.`);
      return;
    }
    if (requestType === 'PARTIAL_DAY' && (!partialDayMinutes || Number(partialDayMinutes) <= 0)) {
      setError('Duration must be greater than zero');
      return;
    }
    // Blocks once this request's own minutes would push the month's total past the cap — not
    // just once the allowance is already fully used, which let a single request larger than the
    // whole monthly cap (e.g. 200 minutes against a 120-minute allowance) through untouched.
    // Mirrors the hard check AttendanceRequestService.submit enforces server-side.
    const partialDayLimitMinutes = PARTIAL_DAY_MONTHLY_LIMIT_HOURS * 60;
    if (requestType === 'PARTIAL_DAY' && remainingMinutes != null && Number(partialDayMinutes) > remainingMinutes) {
      // Only claim the allowance is "used up" when it actually is (remainingMinutes <= 0) —
      // e.g. 0/120 used, requesting 200 minutes in one shot isn't "you've used your 120
      // minutes", it's simply asking for more than the cap allows in a single request.
      setError(remainingMinutes <= 0
        ? `You have used your ${partialDayLimitMinutes} minutes. You are not allowed to raise a request for more than ${partialDayLimitMinutes} minutes.`
        : `You are not allowed to raise a request for more than ${partialDayLimitMinutes} minutes.`);
      return;
    }
    if (partialDayMode === 'INTERVENING_TIMEOFF' && requestType === 'PARTIAL_DAY') {
      setLeaveAtTouched(true);
      if (!leaveAtValue || !isTimeValueComplete(leaveAtValue)) {
        setError('Time is required');
        return;
      }
    }
    setError(null);
    await doSubmit();
  }

  const partialDayModeLabel = partialDayMode === 'LATE_ARRIVE' ? 'Will come late by'
    : partialDayMode === 'LEAVING_EARLY' ? 'Will leave early by'
    : 'Will leave at';

  // Balance is intentionally not part of this — insufficient balance never disables Submit,
  // it's confirmed at click time instead (see handleSubmit).
  const canSubmit = !!reason.trim() && !submitting
    && (requestType !== 'PARTIAL_DAY' || (!timeRequiredError && Number(partialDayMinutes) > 0));

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title={presetType === 'WFH' ? 'Work From Home' : presetType === 'PARTIAL_DAY' ? 'Request Partial Day' : 'New Attendance Request'} onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <OhNoError message={error} onDismiss={() => setError(null)} />}
          {!presetType && (
            <Field label="Request Type">
              <select value={requestType} onChange={(e) => setRequestType(e.target.value as AttendanceRequestType)} style={inputStyle}>
                <option value="WFH">Work From Home</option>
                <option value="PARTIAL_DAY">Partial Day</option>
              </select>
            </Field>
          )}
          {requestType === 'WFH' ? (
            <>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}>
                <div style={{ flex: 1 }}>
                  <Field label="From">
                    <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={inputStyle} />
                  </Field>
                </div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', paddingBottom: 10, whiteSpace: 'nowrap' }}>
                  {dateList.length > 0 ? `${dateList.length} day${dateList.length > 1 ? 's' : ''}` : '—'}
                </div>
                <div style={{ flex: 1 }}>
                  <Field label="To">
                    {/* No min/max — same reasoning as From above: any date stays pickable, and
                        toDate < fromDate / range-length / prior-notice are all validated
                        reactively (see wfhPriorNoticeMessage and handleSubmit) instead of
                        disabling calendar dates. */}
                    <input type="date" value={toDate}
                      onChange={(e) => setToDate(e.target.value)} style={inputStyle} />
                  </Field>
                </div>
              </div>

              {/* Non-dismissible, but only shown after a Submit attempt (see submitAttempted) —
                  the date fields above are never disabled/greyed for a too-soon date; picking
                  one only surfaces this notice once the employee tries to submit. */}
              {wfhPriorNoticeMessage && (
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 7, padding: '9px 12px', fontSize: 12, color: 'var(--risk)', lineHeight: 1.5 }}>
                  <AlertCircle size={14} style={{ flexShrink: 0, marginTop: 1 }} />
                  <span>{wfhPriorNoticeMessage}</span>
                </div>
              )}

              {dateList.length === 1 && (
                <FilterTabs value={wfhSingleMode} onChange={setWfhSingleMode} options={WFH_SINGLE_DAY_MODE_OPTIONS} />
              )}
              {dateList.length > 1 && (
                <>
                  <FilterTabs value={wfhRangeMode} onChange={setWfhRangeMode} options={WFH_RANGE_MODE_OPTIONS} />
                  {wfhRangeMode === 'CUSTOM' && (
                    <div style={{ display: 'flex', gap: 12 }}>
                      <div style={{ flex: 1 }}>
                        <Field label={`From ${formatShortDay(dateList[0])}`}>
                          <select value={wfhCustomFromMode} onChange={(e) => setWfhCustomFromMode(e.target.value as WfhDayMode)} style={inputStyle}>
                            {WFH_SINGLE_DAY_MODE_OPTIONS.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                          </select>
                        </Field>
                      </div>
                      <div style={{ flex: 1 }}>
                        <Field label={`To ${formatShortDay(dateList[dateList.length - 1])}`}>
                          <select value={wfhCustomToMode} onChange={(e) => setWfhCustomToMode(e.target.value as WfhDayMode)} style={inputStyle}>
                            {WFH_SINGLE_DAY_MODE_OPTIONS.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                          </select>
                        </Field>
                      </div>
                    </div>
                  )}
                </>
              )}

              {dateList.length > 0 && (
                <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
                  You are requesting for <strong style={{ color: 'var(--txt)' }}>{totalWfhDays}</strong> day{totalWfhDays === 1 ? '' : 's'} of work from home
                </div>
              )}
              <div ref={wfhBalanceRef} style={{ position: 'relative' }}>
                <div style={{ fontSize: 12, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 6 }}>
                  <Info size={13} />
                  Remaining balance: <strong style={{ color: 'var(--txt)' }}>{wfhBalance ? wfhBalance.remainingDays : '—'}</strong> days
                  <button type="button" onClick={() => setShowWfhBalance((v) => !v)}
                    style={{ display: 'flex', alignItems: 'center', background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                    View Details
                  </button>
                </div>
                {showWfhBalance && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, marginTop: 6, zIndex: 30, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 260, overflow: 'hidden' }}>
                    {!wfhBalance ? (
                      <div style={{ padding: 12, fontSize: 11.5, color: 'var(--txt-mut)' }}>Loading balance…</div>
                    ) : (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11.5 }}>
                        <thead>
                          <tr>
                            <th style={{ textAlign: 'left', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Period</th>
                            <th style={{ textAlign: 'right', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Balance</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>
                              {formatShortDay(monthStartIso(fromDate))} - {formatShortDay(monthEndIso(fromDate))}
                            </td>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>
                              {wfhBalance.remainingDays}/{wfhBalance.limitDays} days
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </div>
              <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>
                Clock in is necessary on WFH days to avoid being marked absent.
              </div>
            </>
          ) : (
            <Field label="Select Date">
              <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={inputStyle} />
            </Field>
          )}
          {requestType === 'PARTIAL_DAY' && (
            <>
              {config?.shiftStart && (
                <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>
                  Shift timing: {formatTime(`${todayIsoDate()}T${config.shiftStart}`)}
                  {config.shiftEnd && <> – {formatTime(`${todayIsoDate()}T${config.shiftEnd}`)}</>}
                </div>
              )}
              <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
                {PARTIAL_DAY_MODE_OPTIONS.map((opt) => (
                  <label key={opt.value} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt)', cursor: 'pointer' }}>
                    <input type="radio" name="partialDayMode" checked={partialDayMode === opt.value} onChange={() => setPartialDayMode(opt.value)} />
                    {opt.label}
                  </label>
                ))}
              </div>
              {partialDayMode === 'INTERVENING_TIMEOFF' ? (
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                  <div style={{ flex: '1 1 140px' }}>
                    <TimeTextInput
                      label={partialDayModeLabel}
                      value={leaveAtText}
                      touched={leaveAtTouched}
                      onChange={setLeaveAtText}
                      onBlur={() => setLeaveAtTouched(true)}
                      requiredMessage="Time is required"
                    />
                  </div>
                  <Field label="For">
                    <select value={partialDayMinutes} onChange={(e) => setPartialDayMinutes(e.target.value)} style={{ ...inputStyle, width: 110 }}>
                      {INTERVENING_DURATION_OPTIONS.map((m) => (
                        <option key={m} value={m}>{m} minutes</option>
                      ))}
                    </select>
                  </Field>
                </div>
              ) : (
                <Field label={partialDayModeLabel}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input
                      type="number" min="1" step="1" inputMode="numeric" value={partialDayMinutes}
                      // Digits only — `type="number"` still lets the browser accept a typed "."
                      // (e.g. "120.333"), so decimals are stripped here rather than relying on
                      // step="1" alone, which only rounds spinner clicks, not free-typed input.
                      onChange={(e) => setPartialDayMinutes(e.target.value.replace(/[^0-9]/g, ''))}
                      onKeyDown={(e) => { if (e.key === '.' || e.key === ',') e.preventDefault(); }}
                      onPaste={(e) => {
                        const pasted = e.clipboardData.getData('text');
                        if (/[.,]/.test(pasted)) {
                          e.preventDefault();
                          setPartialDayMinutes((pasted.match(/[0-9]+/g) ?? []).join(''));
                        }
                      }}
                      style={{ ...inputStyle, width: 100 }}
                    />
                    <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>minutes</span>
                  </div>
                </Field>
              )}
              {computedMessage && (
                <div style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 10px', fontSize: 11.5, color: 'var(--txt)' }}>
                  {computedMessage}
                </div>
              )}
              <div ref={partialBalanceRef} style={{ position: 'relative' }}>
                <button
                  onClick={() => setShowBalance((v) => !v)}
                  style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', color: 'var(--brand)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}
                >
                  <Info size={13} /> View Available Balance
                </button>
                {showBalance && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, marginTop: 6, zIndex: 30, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 260, overflow: 'hidden' }}>
                    {!balance ? (
                      <div style={{ padding: 12, fontSize: 11.5, color: 'var(--txt-mut)' }}>Loading balance…</div>
                    ) : (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11.5 }}>
                        <thead>
                          <tr>
                            <th style={{ textAlign: 'left', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Period</th>
                            <th style={{ textAlign: 'right', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Balance</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>{formatShortDay(fromDate)}</td>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>{remainingMinutes}/{limitMinutes} minutes</td>
                          </tr>
                          <tr>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>{formatShortDay(monthStartIso(fromDate))} - {formatShortDay(monthEndIso(fromDate))}</td>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>{remainingMinutes}/{limitMinutes} minutes</td>
                          </tr>
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </div>
            </>
          )}
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why are you requesting this?"
              autoFocus
            />
          </Field>
          <NotifyEmployeeField token={token} value={notifyEntry} onChange={setNotifyEntry} />
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={!canSubmit}
              style={{ background: canSubmit ? 'var(--brand)' : 'var(--raised2)', color: canSubmit ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, fontWeight: 600, cursor: !canSubmit ? 'not-allowed' : 'pointer' }}
            >
              {submitting ? 'Submitting…' : 'Submit for Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Attendance Stats: Me vs My Team ───────────────────────────────────────────
type StatsRange = 'WEEK' | 'MONTH';

function AttendanceStatsPanel({ token }: { token: string }) {
  const [range, setRange] = useState<StatsRange>('WEEK');
  const [stats, setStats] = useState<AttendanceStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Guards against a slower, earlier request (e.g. a stale MONTH fetch from before the user
    // switched to WEEK) resolving after a newer one and silently overwriting it with the wrong
    // range's data — same cancellation pattern used by every other fetch effect on this page.
    let cancelled = false;
    setLoading(true);
    const to = todayIsoDate();
    const from = range === 'WEEK' ? isoDaysAgo(6) : isoDaysAgo(29);
    attendanceApi.stats(from, to, token)
      .then((s) => { if (!cancelled) setStats(s); })
      .catch(() => { if (!cancelled) setStats(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [range, token]);

  // Header and both data rows share this exact [flex:1 name][84px][100px] composition so the
  // Avg hrs/day and On-time % columns line up vertically — a data row using justify-content:
  // space-between here would distribute space differently than the header's flex:1 spacer and
  // throw the columns out of alignment.
  const rowStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0' };
  const nameStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, flex: 1, minWidth: 0, fontSize: 12, color: 'var(--txt)', fontWeight: 600 };

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 5 }}>
        <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)' }}>Attendance Stats</span>
        <select value={range} onChange={(e) => setRange(e.target.value as StatsRange)} style={{ ...inputStyle, width: 'auto', padding: '3px 7px', fontSize: 11 }}>
          <option value="WEEK">Last Week</option>
          <option value="MONTH">Last 30 Days</option>
        </select>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 12, padding: '10px 0' }}>Loading…</div>
      ) : !stats ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 12, padding: '10px 0' }}>Stats unavailable right now.</div>
      ) : (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
            <span style={{ flex: 1, minWidth: 0 }} />
            <span style={{ width: 84, textAlign: 'right' }}>Avg hrs/day</span>
            <span style={{ width: 100, textAlign: 'right' }}>On-time %</span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={nameStyle}>
              <User size={13} style={{ color: 'var(--brand)', flexShrink: 0 }} /> Me
            </span>
            <span style={{ width: 84, textAlign: 'right', fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.avgHoursPerDay != null ? `${stats.me.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 100, textAlign: 'right', fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.onTimeArrivalPercent != null ? `${stats.me.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={nameStyle}>
              <Users size={13} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} /> My Team
            </span>
            <span style={{ width: 84, textAlign: 'right', fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.avgHoursPerDay != null ? `${stats.team.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 100, textAlign: 'right', fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.onTimeArrivalPercent != null ? `${stats.team.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          {stats.teamSize === 0 && (
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 4 }}>No peers on record under your current manager yet.</div>
          )}
        </>
      )}
    </div>
  );
}

// ─── Today's Timings ────────────────────────────────────────────────────────────
// Every employee is expected to always have an assigned Shift (ONEHR-108 shipped as a hard DB
// invariant), so config.shiftEnd is effectively always populated here — see
// fullDayTargetMinutesFor's own comment. The break-used/break-budget progress bar this panel used
// to show was removed along with app.attendance.daily-break-budget-minutes (Workstream B): it was
// a display-only denominator with no enforcement and no other config to source it from (Shift's
// own per-shift breakMinutes is a separate, admin-metadata concept — not an org-wide live budget).
// Break used time itself (real, computed from actual punch gaps) is still shown, just without a
// target/percentage to compare it against.
function TodaysTimingsPanel({ today, config, workedMinutesToday }: {
  today: TodayAttendance | null;
  config: AttendanceConfig | null;
  workedMinutesToday: number | null;
}) {
  const { formatTime, formatDuration } = useTimeFormat();

  const fullDayTargetMinutes = fullDayTargetMinutesFor(config);
  const progressPct = fullDayTargetMinutes && workedMinutesToday != null
    ? Math.min(100, Math.round((workedMinutesToday / fullDayTargetMinutes) * 100))
    : 0;
  const breakUsed = today?.breakUsedMinutes;

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 9 }}>
      <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)' }}>Today's Timings</span>
      {config && (
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>
          {config.shiftEnd
            ? <>Shift {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} – {formatTime(`${todayIsoDate()}T${config.shiftEnd}`)} · grace {config.lateGraceMinutes}m</>
            : <>Shift starts {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} · grace {config.lateGraceMinutes}m</>}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--txt-dim)', marginBottom: 4 }}>
          <span>Progress toward shift end</span>
          <span>{formatDuration(workedMinutesToday) ?? dash}</span>
        </div>
        <div style={{ height: 7, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${progressPct}%`, background: 'var(--brand)', borderRadius: 4, transition: 'width .3s' }} />
        </div>
      </div>
      {breakUsed != null && (
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--txt-dim)' }}>
          <span>Break used</span>
          <span>{breakUsed} min</span>
        </div>
      )}
    </div>
  );
}

// ─── Quick Actions ──────────────────────────────────────────────────────────────
/**
 * Check-in / Check-out / Web Check-In — no manager approval needed. All three, plus the
 * calendar's own Today's-workday Check In/Check Out buttons and Today's Timings, are driven by
 * the exact same `today: TodayAttendance` (MyAttendance's state, refreshed via
 * `refreshTodayAndMonth`) so every entry point reflects one consistent attendance state — see
 * MyAttendance's `punch`/`refreshTodayAndMonth`. Checking in here calls the identical
 * attendanceApi.checkIn used by the calendar panel (so the late-arrival penalty applies the
 * same way regardless of which button was clicked); Web Check-In still goes through
 * webClockInApi.submit since it alone carries a reason, and the normal session's checkout is
 * always the one shared `onCheckOut` — nothing calls attendanceApi.checkOut from anywhere else
 * on this page. WebCheckInAction below DOES call webClockInApi.checkOut, for its own
 * independent Web session (deliberately separate from the normal session's own checkout — see
 * WebClockInService's class Javadoc); that was previously missing entirely (the button just sat
 * disabled forever once a Web session opened), fixed to mirror AttendanceHeroBanner's own
 * WebClockInRow.
 */
interface CheckInStateProps {
  actionStyle: React.CSSProperties;
  today: TodayAttendance | null;
  loading: boolean;
  submitting: boolean;
  onCheckIn: () => Promise<void>;
  onCheckOut: () => Promise<void>;
}

function CheckInAction({ actionStyle, today, loading, submitting, onCheckIn, onCheckOut }: CheckInStateProps) {
  const { formatTime } = useTimeFormat();
  const [confirmingCheckout, setConfirmingCheckout] = useState(false);

  function handlePrimaryClick() {
    // Checking out gets a confirmation popup since it closes out the day; checking in
    // still happens directly, no confirmation needed.
    if (today?.canCheckOut) {
      setConfirmingCheckout(true);
      return;
    }
    if (today?.canCheckIn) {
      onCheckIn();
    }
  }

  async function handleConfirmCheckout() {
    try {
      await onCheckOut();
    } finally {
      setConfirmingCheckout(false);
    }
  }

  const dayComplete = !!today && !today.canCheckIn && !today.canCheckOut;
  const disablePrimary = loading || submitting || dayComplete;
  const label = dayComplete
    ? `Checked out${today?.record?.checkOutAt ? ` at ${formatTime(today.record.checkOutAt)}` : ''}`
    : today?.canCheckOut ? 'Check-out' : 'Check-in';

  return (
    <>
      <button
        onClick={handlePrimaryClick}
        disabled={disablePrimary}
        style={{ ...actionStyle, opacity: disablePrimary ? 0.6 : 1, cursor: disablePrimary ? 'default' : 'pointer' }}
      >
        <Laptop size={14} style={{ color: 'var(--brand)' }} /> {loading ? 'Check-in' : label}
      </button>
      {confirmingCheckout && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 380 }}>
            <ModalHeader title="Check out?" onClose={() => setConfirmingCheckout(false)} />
            <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
              <p style={{ margin: 0, fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
                Are you sure you want to check out?
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setConfirmingCheckout(false)}
                  disabled={submitting}
                  style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 12.5, cursor: submitting ? 'not-allowed' : 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleConfirmCheckout}
                  disabled={submitting}
                  style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Checking out…' : 'OK'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/**
 * Distinct from the one-click Check-in above: for an ad-hoc remote/WFH day where the employee
 * wants to record why they're checking in off-site (mirrors Keka's Web Clock-In request, which
 * requires a reason). Web Clock-In is independent of the normal Check-In/Check-Out session (see
 * WebClockInService's own class Javadoc) — it stays enabled regardless of today.canCheckIn/
 * canCheckOut, and is only disabled while a Web session is already open (mirrors the backend's
 * own "already checked in" guard in WebClockInService.submit). Once submitted, refreshes the same
 * shared `today` — so either button immediately reflects in the other, and checkout (handled
 * solely by CheckInAction's toggle above) works no matter which one opened the day.
 */
function WebCheckInAction({ token, actionStyle, today, loading, onSubmitted }: {
  token: string;
  actionStyle: React.CSSProperties;
  today: TodayAttendance | null;
  loading: boolean;
  onSubmitted: () => Promise<unknown>;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  // Whether this employee has ANY Web Clock-In cycle on file today — a note is mandatory only
  // for the very first cycle of the resolved work day (enforced server-side); every later cycle
  // needs no note at all, so this click can skip the modal entirely. Mirrors
  // AttendanceHeroBanner's WebClockInRow.
  const [isFirstCycleToday, setIsFirstCycleToday] = useState(true);
  // The currently-open Web session (not yet checked out), if any — not just a boolean: this
  // button must offer Web Clock-Out once one is open (mirrors AttendanceHeroBanner's
  // WebClockInRow, which already does this on the Dashboard — this Actions-panel button used to
  // just sit disabled forever with no way to check out from here once open). No approval/review
  // status at all anymore (see WebClockInService's own class Javadoc).
  const [openWeb, setOpenWeb] = useState<WebClockInRecord | null>(null);
  // Synchronous re-entrancy guards — the `disabled`/`busy`/`checkingOut` state alone only blocks
  // a real click once React has committed the re-render, which isn't guaranteed before a second
  // click (rapid double-click, a slow/blocked main thread) reaches these handlers. A ref
  // mutation takes effect immediately, with no render/paint dependency.
  const busyRef = useRef(false);
  const checkingOutRef = useRef(false);

  // Returns the fetch's own promise (not fire-and-forget) — submitReason/handleWebCheckOut below
  // await this before releasing their re-entrancy guard, so the button never re-enables while
  // still showing stale openWeb/isFirstCycleToday state. A bare (unreturned) `.then(...)` call
  // here would make `await refreshMine()` resolve immediately without actually waiting for it.
  const refreshMine = useCallback(() => {
    // Filtered by the business/Location-zone work date (today.workDate) — never the browser's
    // own UTC calendar date, which can disagree with it near midnight or whenever the employee's
    // device zone differs from their assigned Location's zone.
    const businessTodayIso = today?.workDate;
    if (!businessTodayIso) { setIsFirstCycleToday(true); setOpenWeb(null); return Promise.resolve(); }
    return webClockInApi.mine(token).then((list: WebClockInRecord[]) => {
      const todays = list.filter(r => r.workDate === businessTodayIso);
      setIsFirstCycleToday(todays.length === 0);
      setOpenWeb(todays.find(r => !r.checkedOutAt) ?? null);
    }).catch(() => { setIsFirstCycleToday(true); setOpenWeb(null); });
  }, [token, today]);

  useEffect(() => { refreshMine(); }, [refreshMine]);

  const disabled = loading || !!openWeb;

  async function submitReason(trimmed: string | undefined) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const created = await webClockInApi.submit(trimmed, token);
      await onSubmitted();
      // Awaited (not fire-and-forget) — refreshMine is what sets openWeb, which is what flips
      // this button to Web Clock-Out. Releasing busyRef/busy in the finally below before this
      // resolves would re-enable the button while it still shows the pre-submit "Web Check-In"
      // label, letting a rapid second click fire a genuine duplicate request. Mirrors
      // CheckInAction's punch() in this same file, which awaits its own refresh the same way.
      await refreshMine();
      const at = formatTime(created.requestedCheckIn);
      showToast('success', `Checked in ${at ? `at ${at}` : 'successfully'}`);
      setOpen(false);
      setReason('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  function handleSubmit() {
    const trimmed = reason.trim();
    if (!trimmed) {
      showToast('error', 'Please enter a comment for the web clock-in request');
      return;
    }
    submitReason(trimmed);
  }

  async function handleWebCheckOut() {
    if (checkingOutRef.current) return;
    checkingOutRef.current = true;
    setCheckingOut(true);
    try {
      const resp = await webClockInApi.checkOut(token);
      await onSubmitted();
      // Same reasoning as submitReason above — awaited so checkingOutRef/checkingOut only
      // release once openWeb has actually cleared, not before.
      await refreshMine();
      const at = formatTime(resp.checkedOutAt);
      showToast('success', `Checked out ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Web clock-out failed');
    } finally {
      checkingOutRef.current = false;
      setCheckingOut(false);
    }
  }

  if (openWeb) {
    return (
      <button
        onClick={handleWebCheckOut}
        disabled={checkingOut}
        style={{ ...actionStyle, opacity: checkingOut ? 0.6 : 1, cursor: checkingOut ? 'default' : 'pointer' }}
      >
        <LogOut size={14} style={{ color: 'var(--brand)' }} />
        {checkingOut ? 'Web clocking out…' : `Web Clock-Out (since ${formatTime(openWeb.requestedCheckIn) ?? '—'})`}
      </button>
    );
  }

  return (
    <>
      <button
        onClick={() => (isFirstCycleToday ? setOpen(true) : submitReason(undefined))}
        disabled={disabled || busy}
        style={{ ...actionStyle, opacity: (disabled || busy) ? 0.6 : 1, cursor: (disabled || busy) ? 'default' : 'pointer' }}
      >
        <Wifi size={14} style={{ color: 'var(--brand)' }} /> {busy ? 'Checking in…' : 'Web Check-In'}
      </button>
      {open && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 480 }}>
            <ModalHeader title='Web Clock-In Request' onClose={() => !busy && setOpen(false)} />
            <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
              <p style={{ margin: 0, fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
                Adding a comment is required for a web clock-in request.
              </p>
              <div>
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value.slice(0, 1024))}
                  rows={4}
                  autoFocus
                  maxLength={1024}
                  style={{ width: '100%', resize: 'vertical', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 10px', fontSize: 12.5, background: 'var(--raised)', color: 'var(--txt)', fontFamily: 'inherit' }}
                />
                <div style={{ textAlign: 'right', fontSize: 10.5, color: 'var(--txt-mut)', marginTop: 4 }}>
                  {reason.length} / 1024
                </div>
              </div>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setOpen(false)}
                  disabled={busy}
                  style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 12.5, cursor: busy ? 'not-allowed' : 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={busy || !reason.trim()}
                  style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, fontWeight: 600, cursor: (busy || !reason.trim()) ? 'not-allowed' : 'pointer', opacity: (busy || !reason.trim()) ? 0.7 : 1 }}
                >
                  {busy ? 'Confirming…' : 'Confirm'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function QuickActionsPanel({ token, today, todayLoading, submitting, onCheckIn, onCheckOut, onWebCheckInSubmitted }: {
  token: string;
  today: TodayAttendance | null;
  todayLoading: boolean;
  submitting: boolean;
  onCheckIn: () => Promise<void>;
  onCheckOut: () => Promise<void>;
  onWebCheckInSubmitted: () => Promise<unknown>;
}) {
  const [modal, setModal] = useState<'WFH' | 'PARTIAL_DAY' | 'POLICY' | null>(null);

  const actionStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 8, background: 'var(--raised)', border: '1px solid var(--line2)',
    borderRadius: 7, padding: '8px 11px', fontSize: 12, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600, width: '100%',
    textAlign: 'left' as const,
  };

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 7 }}>
      <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Actions</span>
      <CheckInAction actionStyle={actionStyle} today={today} loading={todayLoading} submitting={submitting} onCheckIn={onCheckIn} onCheckOut={onCheckOut} />
      <WebCheckInAction token={token} actionStyle={actionStyle} today={today} loading={todayLoading} onSubmitted={onWebCheckInSubmitted} />
      <button style={actionStyle} onClick={() => setModal('WFH')}>
        <Home size={14} style={{ color: 'var(--brand)' }} /> Work From Home
      </button>
      <button style={actionStyle} onClick={() => setModal('PARTIAL_DAY')}>
        <Sun size={14} style={{ color: 'var(--brand)' }} /> Partial Day Request
      </button>
      <button style={actionStyle} onClick={() => setModal('POLICY')}>
        <FileText size={14} style={{ color: 'var(--brand)' }} /> Attendance Policy
      </button>
      {(modal === 'WFH' || modal === 'PARTIAL_DAY') && (
        <AttendanceRequestModal
          presetType={modal}
          token={token}
          onClose={() => setModal(null)}
          onSaved={() => { /* toast already shown by the modal itself */ }}
        />
      )}
      {modal === 'POLICY' && (
        <AttendancePolicyModal token={token} onClose={() => setModal(null)} />
      )}
    </div>
  );
}

// ─── Attendance Log: per-day visual timeline ───────────────────────────────────
// One 24-hour axis per day, with a clickable presence bar per punch session — supports
// multiple check-in/out blocks per day (e.g. a lunch break) when the real per-day punches
// have been fetched (see MyAttendance's punchesByDate cache); falls back to the day's single
// checkIn/checkOut pair otherwise. Never rendered for leave/holiday/weekly-off days — those
// show a plain text label instead, since a timeline would be misleading there.

/** Sum of gaps between consecutive closed punch sessions — mirrors AttendanceService's break calc. */
function computeBreakMinutesFromPunches(punches: Punch[]): number {
  let total = 0;
  for (let i = 0; i < punches.length - 1; i++) {
    const gapStart = punches[i].checkOutAt;
    const gapEnd = punches[i + 1].checkInAt;
    if (gapStart && gapEnd) {
      // Punches are sorted by checkInAt only (see the backend's PunchResponse ordering), but a
      // Web Clock-In/Out session can genuinely overlap a normal Check-In/Out session in real
      // time (they're independent — see WebClockInService's own class Javadoc), so an adjacent
      // pair here can have gapEnd before gapStart. That's a real overlap, not a negative-length
      // break — floor each interval at 0 rather than letting a negative gap corrupt the day's
      // total (and ultimately render as e.g. "-48m").
      total += Math.max(0, Math.round((wallClockMs(gapEnd) - wallClockMs(gapStart)) / 60000));
    }
  }
  return total;
}

interface RowMetrics {
  /** True only for today's row while still checked in — the only case worked minutes are still live. */
  openSession: boolean;
  effectiveMinutes: number | null;
  breakMinutes: number | null;
  grossMinutes: number | null;
}

/**
 * Effective/Break/Gross for one Attendance Log row. Gross = Effective + Break (elapsed incl.
 * breaks). businessTodayIso is TodayAttendanceResponse.workDate — the business/Location-zone
 * work date — never the browser's own UTC calendar date, which can disagree with it near
 * midnight or whenever the employee's device zone differs from their assigned Location's zone.
 */
function computeRowMetrics(info: DayInfo, punches: Punch[] | undefined, workedMinutesToday: number | null, businessTodayIso: string | undefined): RowMetrics {
  if (!info.record?.checkInAt) {
    return { openSession: false, effectiveMinutes: null, breakMinutes: null, grossMinutes: null };
  }
  const openSession = info.iso === businessTodayIso && !info.record.checkOutAt;
  const effectiveMinutes = openSession ? workedMinutesToday : (info.record.workedMinutes ?? null);
  const breakMinutes = punches ? computeBreakMinutesFromPunches(punches) : 0;
  const grossMinutes = effectiveMinutes != null ? effectiveMinutes + breakMinutes : null;
  return { openSession, effectiveMinutes, breakMinutes, grossMinutes };
}

/** One presence bar — purely presentational, no interactivity (the timeline is visual-only). */
/**
 * One presence bar, proportionally positioned/sized against the 24-hour track. Hovering shows a
 * small "Logged in HH:MM – HH:MM" tooltip centered directly above the bar — no click, no extra
 * day-details content here (that lives behind the row's View button instead).
 */
function TimelineBar({ checkInAt, checkOutAt, leftPct, widthPct }: {
  checkInAt: string; checkOutAt: string | null; leftPct: number; widthPct: number;
}) {
  const { formatTime } = useTimeFormat();
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);
  const barRef = useRef<HTMLDivElement>(null);

  function show() {
    const rect = barRef.current?.getBoundingClientRect();
    if (!rect) return;
    setCoords({ top: rect.top - 6, left: rect.left + rect.width / 2 });
  }
  function hide() {
    setCoords(null);
  }

  const label = `Logged in ${formatTime(checkInAt) ?? '—'} - ${checkOutAt ? formatTime(checkOutAt) : 'now'}`;

  return (
    <>
      <div
        ref={barRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        style={{
          position: 'absolute', left: `${leftPct}%`, width: `${widthPct}%`, minWidth: 3,
          top: 0, height: '100%',
          background: checkOutAt ? 'var(--brand)' : '#E0A93B',
          borderRadius: 3,
        }}
      />
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed', top: coords.top, left: coords.left, transform: 'translate(-50%, -100%)',
            background: 'var(--raised2)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6,
            padding: '5px 9px', fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
            boxShadow: '0 6px 18px rgba(0,0,0,.35)', zIndex: 1000, pointerEvents: 'none',
          }}
        >
          {label}
          <div style={{
            position: 'absolute', bottom: -4, left: '50%', transform: 'translateX(-50%)',
            width: 0, height: 0, borderLeft: '4px solid transparent', borderRight: '4px solid transparent',
            borderTop: '4px solid var(--raised2)',
          }} />
        </div>,
        document.body,
      )}
    </>
  );
}

/**
 * One break marker — the gap between two closed punch sessions, rendered as its own hoverable
 * segment (rather than empty space) so a break reads as calculated/intentional, not just an
 * absence of data. Mirrors TimelineBar's tooltip mechanics but with a distinct pale, flat fill
 * and "Break HH:MM – HH:MM" wording, matching Keka's own attendance-visual break callout.
 */
function BreakTimelineMarker({ breakStart, breakEnd, leftPct, widthPct }: {
  breakStart: string; breakEnd: string; leftPct: number; widthPct: number;
}) {
  const { formatTime } = useTimeFormat();
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);
  const barRef = useRef<HTMLDivElement>(null);

  function show() {
    const rect = barRef.current?.getBoundingClientRect();
    if (!rect) return;
    setCoords({ top: rect.top - 6, left: rect.left + rect.width / 2 });
  }
  function hide() {
    setCoords(null);
  }

  const label = `Break ${formatTime(breakStart) ?? '—'} - ${formatTime(breakEnd) ?? '—'}`;

  return (
    <>
      <div
        ref={barRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        style={{
          position: 'absolute', left: `${leftPct}%`, width: `${widthPct}%`, minWidth: 3,
          top: 0, height: '100%',
          background: 'var(--txt-dim)',
          opacity: 0.4, borderRadius: 3,
        }}
      />
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed', top: coords.top, left: coords.left, transform: 'translate(-50%, -100%)',
            background: 'var(--raised2)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6,
            padding: '5px 9px', fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
            boxShadow: '0 6px 18px rgba(0,0,0,.35)', zIndex: 1000, pointerEvents: 'none',
          }}
        >
          {label}
          <div style={{
            position: 'absolute', bottom: -4, left: '50%', transform: 'translateX(-50%)',
            width: 0, height: 0, borderLeft: '4px solid transparent', borderRight: '4px solid transparent',
            borderTop: '4px solid var(--raised2)',
          }} />
        </div>,
        document.body,
      )}
    </>
  );
}

/**
 * One scheduled-shift-boundary marker (start or end) — a small, subtle triangle sitting on top of
 * the same workday-window track the actual-attendance bar is drawn on (see AttendanceTimeline), so
 * an employee can compare "when I was scheduled" against "when I actually showed up" at a glance.
 * Purely an overlay: it never redraws, resizes, or replaces the actual-attendance TimelineBar
 * itself. `leftPct` comes from shiftMarkerPositions, the exact same workday-window basis
 * TimelineBar itself is positioned on (workdayStartAt -> workdayEndAt, never 00:00-24:00 — see
 * shiftMarkers.ts), so the two always line up correctly. Hover shows a small "Shift start/end
 * HH:MM" tooltip, mirroring TimelineBar's own tooltip mechanics.
 */
function ShiftBoundaryMarker({ label, leftPct }: { label: string; leftPct: number }) {
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);
  const markerRef = useRef<HTMLDivElement>(null);

  function show() {
    const rect = markerRef.current?.getBoundingClientRect();
    if (!rect) return;
    setCoords({ top: rect.top - 6, left: rect.left + rect.width / 2 });
  }
  function hide() {
    setCoords(null);
  }

  return (
    <>
      <div
        ref={markerRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        style={{
          position: 'absolute', left: `${leftPct}%`, top: -3, transform: 'translateX(-50%)',
          width: 0, height: 0,
          borderLeft: '3.5px solid transparent', borderRight: '3.5px solid transparent',
          borderTop: '5px solid var(--txt-dim)', opacity: 0.85,
        }}
      />
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed', top: coords.top, left: coords.left, transform: 'translate(-50%, -100%)',
            background: 'var(--raised2)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6,
            padding: '5px 9px', fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
            boxShadow: '0 6px 18px rgba(0,0,0,.35)', zIndex: 1000, pointerEvents: 'none',
          }}
        >
          {label}
          <div style={{
            position: 'absolute', bottom: -4, left: '50%', transform: 'translateX(-50%)',
            width: 0, height: 0, borderLeft: '4px solid transparent', borderRight: '4px solid transparent',
            borderTop: '4px solid var(--raised2)',
          }} />
        </div>,
        document.body,
      )}
    </>
  );
}

/** Compact, column-filling 24-hour presence timeline. Bars are proportional to actual login/logout duration; hovering a bar shows a small tooltip (see TimelineBar). No day-details content lives here — that's behind the row's View button. */
// Width pinned via ATTENDANCE_VISUAL_COL_WIDTH (the <th>/<td> below) so every row in the column
// — bars or placeholder text — occupies the same footprint and lines up under the "Attendance
// Visual" header, and the same font size as the rest of the table's cells (tdStyle) rather than
// a smaller one-off size.
const ATTENDANCE_VISUAL_COL_WIDTH = 200;
// Compact overall track height — keep every element below (placeholder text, the hour-tick
// axis, the track bar, and the session/break markers) sized off this one constant so they
// all stay vertically centered and in proportion if it ever changes again.
const ATTENDANCE_VISUAL_HEIGHT = 14;
const attendanceVisualPlaceholderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', height: ATTENDANCE_VISUAL_HEIGHT, width: '100%', fontSize: 12, color: 'var(--txt-dim)',
};

function AttendanceTimeline({ info, punches, punchesLoading }: {
  info: DayInfo; punches: Punch[] | undefined; punchesLoading: boolean;
}) {
  const { formatTime } = useTimeFormat();
  if (info.holidayName) {
    return (
      <div style={attendanceVisualPlaceholderStyle}>
        <TruncatedText text={`Company holiday — ${info.holidayName}`} />
      </div>
    );
  }
  if (info.leaveTypeName) {
    return (
      <div style={attendanceVisualPlaceholderStyle}>
        <TruncatedText text={`On leave — ${info.leaveTypeName}`} />
      </div>
    );
  }
  if (info.isWeekend && !info.record && !info.attendanceRequest) {
    return <div style={attendanceVisualPlaceholderStyle}>Full day Weekly-off</div>;
  }
  const record = info.record;
  if (!record?.checkInAt) {
    return <div style={attendanceVisualPlaceholderStyle}>—</div>;
  }
  if (punchesLoading) {
    return <div style={attendanceVisualPlaceholderStyle}>Loading…</div>;
  }

  // Real per-session punches (supports multiple blocks/day) when the fetch has resolved;
  // otherwise fall back to the day's single check-in/out pair rather than showing nothing.
  const segments: { key: string; checkInAt: string; checkOutAt: string | null }[] =
    punches && punches.length > 0
      ? punches.map((p) => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt }))
      : [{ key: info.iso, checkInAt: record.checkInAt, checkOutAt: record.checkOutAt }];

  // The track's own coordinate system: workdayStartAt -> workdayEndAt (per ShiftDayPolicy, via
  // AttendanceResponse.workdayStartAt/workdayEndAt) — NEVER a fixed 00:00-24:00 calendar day, so
  // a post-midnight punch still lands to the right of the shift-start marker instead of wrapping
  // back to the track's own left edge. See shiftMarkers.ts's own header comment.
  const workdayWindow = resolveWorkdayWindow(record.workDate, record.workdayStartAt, record.workdayEndAt);
  const trackHours = Math.ceil(workdayWindow.totalMinutes / 60);

  // Scheduled shift start/end markers — null for a legacy record predating the shift snapshot
  // (see AttendanceResponse.shiftStartAt's own doc comment), in which case none are drawn.
  const markers = shiftMarkerPositions(record.shiftStartAt, record.shiftEndAt, workdayWindow);

  return (
    <div style={{ position: 'relative', height: ATTENDANCE_VISUAL_HEIGHT, width: '100%' }}>
      {/* No workday-boundary tooltip here by design — the workday window is already represented
          visually by the track's own extent/background and the shift-boundary markers below;
          a separate "Workday HH:MM – HH:MM" hover on top of that was confusing and carried no
          attendance information of its own, so it's intentionally not reinstated (see the
          Attendance UI correction spec's "Remove confusing workday hover" requirement). Hover
          behavior for actual attendance (TimelineBar) and breaks (BreakTimelineMarker) is
          untouched. */}
      <div style={{ position: 'absolute', left: 0, right: 0, top: 5, height: 4, background: 'var(--raised2)', borderRadius: 2 }} />
      {Array.from({ length: trackHours + 1 }).map((_, i) => (
        <div key={i} style={{ position: 'absolute', left: `${Math.min(100, (i * 60 / workdayWindow.totalMinutes) * 100)}%`, top: 2, width: 1, height: 10, background: 'var(--line2)', opacity: i % 6 === 0 ? 0.8 : 0.35 }} />
      ))}
      {segments.map((seg, i) => {
        const position = segmentBarPosition(seg.checkInAt, seg.checkOutAt, workdayWindow);
        if (!position) return null;
        return (
          <TimelineBar key={seg.key ?? i} leftPct={position.leftPct} widthPct={position.widthPct} checkInAt={seg.checkInAt} checkOutAt={seg.checkOutAt} />
        );
      })}
      {/* Breaks — the gap between one session's checkOutAt and the next session's checkInAt,
          same pairing computeBreakMinutesFromPunches sums for the "Break Taken" column. Rendered
          as its own hoverable marker (not just empty space) so the break reads as calculated,
          matching Keka's "Break HH:MM – HH:MM" callout on its attendance visual. */}
      {segments.slice(0, -1).map((seg, i) => {
        const next = segments[i + 1];
        if (!seg.checkOutAt || !next?.checkInAt) return null;
        const position = breakMarkerPosition(seg.checkOutAt, next.checkInAt, workdayWindow);
        if (!position) return null;
        return (
          <BreakTimelineMarker
            key={`break-${seg.key}`}
            breakStart={seg.checkOutAt}
            breakEnd={next.checkInAt}
            leftPct={position.leftPct}
            widthPct={position.widthPct}
          />
        );
      })}
      {/* Scheduled shift start/end — small triangle markers overlaid on the same track, so the
          actual-attendance bar(s) above can be compared at a glance against when the shift was
          actually scheduled. Never redraws or replaces the bar itself. */}
      {markers && (
        <>
          <ShiftBoundaryMarker label={`Shift start ${formatTime(record.shiftStartAt!) ?? '—'}`} leftPct={markers.startPct} />
          <ShiftBoundaryMarker label={`Shift end ${formatTime(record.shiftEndAt!) ?? '—'}`} leftPct={markers.endPct} />
        </>
      )}
    </div>
  );
}

/** Shift name/timing for the selected day + Regularize/Apply Partial Day actions, shown in the
 * existing View/details side panel (reuses RequestModal/AttendanceRequestModal — see MyAttendance's
 * regularizeDate/partialDayDate state). */
function DayShiftAndActions({ info, config, onRegularize, onApplyPartialDay }: {
  info: DayInfo; config: AttendanceConfig | null; onRegularize: () => void; onApplyPartialDay: () => void;
}) {
  const { formatTime } = useTimeFormat();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
      {config?.shiftName && (
        <div>
          <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Shift</div>
          <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{config.shiftName}</div>
          {config.shiftStart && config.shiftEnd && (
            <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 2 }}>
              {formatTime(`${info.iso}T${config.shiftStart}`)} - {formatTime(`${info.iso}T${config.shiftEnd}`)}
            </div>
          )}
        </div>
      )}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <button
          onClick={onRegularize}
          style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', padding: 0, color: 'var(--brand)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer' }}
        >
          <Pencil size={11} /> Regularize
        </button>
        <button
          onClick={onApplyPartialDay}
          style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', padding: 0, color: 'var(--brand)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer' }}
        >
          <Pencil size={11} /> Apply Partial Day
        </button>
      </div>
    </div>
  );
}

/** " (9 Sep)" appended to a punch's formatted time when its own calendar date differs from the
 * attendance row's own {@code workDate} — see {@link punchCalendarDateIfDiffers}'s own doc
 * comment for why (a legitimate post-midnight punch must stay visually distinct from a same-day
 * one at the same clock time). Empty string when they match, so a same-day punch renders exactly
 * as it always has. Reuses the existing {@link formatShortDay} formatter (already used elsewhere
 * on this page for a compact day label) rather than introducing another one. */
function punchDateSuffix(iso: string, workDate: string): string {
  const differing = punchCalendarDateIfDiffers(iso, workDate);
  return differing ? ` (${formatShortDay(differing)})` : '';
}

/** One source's block of sessions within DayPunchIntervals — mirrors Keka's per-source grouping
 * (its own company-name section for normal punches, a separate "Web Clock In" section).
 * `workDate` is the attendance row's own authoritative workDate (never re-derived here — see
 * {@link punchDateSuffix}) so a session whose check-in/check-out lands on a different calendar
 * date (a post-midnight continuation of this same logical workday) renders with a date qualifier
 * instead of being indistinguishable from a same-day punch. */
function PunchSourceGroup({ label, sessions, workDate }: {
  label: string;
  sessions: { key: string; checkInAt: string; checkOutAt: string | null; note?: string | null }[];
  workDate: string;
}) {
  const { formatTime } = useTimeFormat();
  if (sessions.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</div>
      {sessions.map((s, i) => {
        const inTime = formatTime(s.checkInAt);
        const outTime = s.checkOutAt ? formatTime(s.checkOutAt) : null;
        return (
          <div key={s.key ?? i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 11.5 }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--ok)', fontWeight: 600 }}>
              <ArrowDownLeft size={12} /> {inTime ? `${inTime}${punchDateSuffix(s.checkInAt, workDate)}` : dash}
              {s.note && (
                <Tooltip content={s.note}>
                  <button
                    type="button"
                    aria-label="Web clock-in note"
                    style={{ display: 'inline-flex', background: 'none', border: 'none', padding: 0, margin: 0, cursor: 'pointer', color: 'var(--txt-dim)' }}
                  >
                    <Info size={12} />
                  </button>
                </Tooltip>
              )}
            </span>
            {s.checkOutAt ? (
              <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--txt)', fontWeight: 600 }}>
                <ArrowUpRight size={12} /> {outTime ? `${outTime}${punchDateSuffix(s.checkOutAt, workDate)}` : outTime}
              </span>
            ) : (
              <span style={{ fontSize: 10, fontWeight: 700, color: '#E4373D', letterSpacing: '.04em' }}>MISSING</span>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Complete real punch history for the day (supports multiple IN/OUT sessions across BOTH entry
 * points), MISSING shown for any session still missing its check-out. Grouped by source — normal
 * Check-In/Check-Out and Web Check-In/Check-Out each get their own labeled section, matching the
 * Keka reference's separate "Company Name" / "Web Clock In" blocks — rather than one flat list
 * that hides which entries came from where.
 */
function DayPunchIntervals({ info, punches }: { info: DayInfo; punches: Punch[] | undefined }) {
  const record = info.record;
  // The authoritative workDate every session's date qualifier is compared against (see
  // PunchSourceGroup/punchDateSuffix) — record.workDate when a record exists (always equal to
  // info.iso, since recordByDate is itself keyed by workDate), else info.iso as a same-value
  // fallback for the (structurally unreachable once sessions is non-empty) no-record case.
  const workDate = record?.workDate ?? info.iso;
  const sessions: { key: string; checkInAt: string; checkOutAt: string | null; source: Punch['source']; note: string | null }[] =
    punches && punches.length > 0
      ? punches.map((p) => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt, source: p.source, note: p.note }))
      : record?.checkInAt
        ? [{ key: info.iso, checkInAt: record.checkInAt, checkOutAt: record.checkOutAt, source: record.source === 'WEB_REMOTE' ? 'WEB_REMOTE' : 'SYSTEM', note: null }]
        : [];

  // Oldest first, matching the Keka reference (earliest punch at top, latest at bottom) — the
  // API doesn't guarantee an order, so this is display-only, not a data change.
  sessions.sort((a, b) => a.checkInAt.localeCompare(b.checkInAt));

  if (sessions.length === 0) {
    return <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>No punches recorded for this day.</div>;
  }

  const officeSessions = sessions.filter((s) => s.source !== 'WEB_REMOTE');
  const webSessions = sessions.filter((s) => s.source === 'WEB_REMOTE');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 260 }}>
      <PunchSourceGroup label="Check-In / Check-Out" sessions={officeSessions} workDate={workDate} />
      <PunchSourceGroup label="Web Check-In / Check-Out" sessions={webSessions} workDate={workDate} />
    </div>
  );
}

/**
 * Full day-details body — date header, then whichever of holiday/leave/regularization/punched-
 * record/weekend/nothing applies, including (for a punched record) DayShiftAndActions and
 * DayPunchIntervals. Shared by the calendar's side panel AND the View-button modal below, so
 * both present exactly the same information.
 */
function DayDetailsBody({ info, config, punches, workedMinutesToday, businessTodayIso, webSessions, onRegularize, onApplyPartialDay }: {
  info: DayInfo;
  config: AttendanceConfig | null;
  punches: Punch[] | undefined;
  // Today's live-aware worked-minutes figure and the business/Location-zone "today" ISO it
  // applies to — same two inputs computeRowMetrics already takes for the Attendance Log's
  // "Effective Hours" column, so this modal's own figure is computed identically instead of
  // reading info.record.workedMinutes directly (a second, independent source that only ever
  // reflects the last *settled* total and can disagree with Effective Hours). Both are optional:
  // this component only ever needs them for info.iso === businessTodayIso.
  workedMinutesToday: number | null;
  businessTodayIso: string | undefined;
  // This day's Web Clock-In records (if any) — needed so Check Out can reflect the LATEST of the
  // normal session's own checkOutAt and any Web Clock-Out(s) the same day, since Web Clock-Out
  // never writes the shared record's own checkOutAt (see WebClockInService's own class Javadoc).
  webSessions: WebClockInRecord[];
  onRegularize: () => void;
  onApplyPartialDay: () => void;
}) {
  const { formatTime, formatDuration } = useTimeFormat();
  const metrics = info.record ? computeRowMetrics(info, punches, workedMinutesToday, businessTodayIso) : null;
  // Check In is always the day's original checkInAt (frozen across every resume regardless of
  // source, see AttendanceService.checkIn's own doc comment) — never replaced by a later
  // Check-In/Web Clock-In cycle. Check Out is the latest close from either source.
  const checkOutDisplay = info.record
    ? latestIso(info.record.checkOutAt, ...webSessions.map((r) => r.checkedOutAt))
    : null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)' }}>{formatDay(info.iso)}</div>
      {info.holidayName ? (
        <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Company holiday — {info.holidayName}</div>
      ) : info.leaveTypeName ? (
        <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>On leave — {info.leaveTypeName}</div>
      ) : info.regularization ? (
        <>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <div>
              <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested In</div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.regularization.requestedCheckIn) ?? dash}</div>
            </div>
            <div>
              <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested Out</div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.regularization.requestedCheckOut) ?? dash}</div>
            </div>
          </div>
          <div>
            <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Total Hours</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(info.regularization.totalMinutes) ?? dash}</div>
          </div>
          <div>
            <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Status</div>
            <RegularizationStatusPill status={info.regularization.status} />
          </div>
          <div>
            <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Approved By</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{info.regularization.reviewedByName ?? dash}</div>
          </div>
          <div>
            <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Comments</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{info.regularization.reviewComment ?? dash}</div>
          </div>
        </>
      ) : info.record ? (
        <>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                <LogIn size={11} /> Check In
              </div>
              {/* The day's original checkInAt — locked for the entire resolved work day
                  regardless of source, never replaced by a later Check-In/Web Clock-In cycle
                  (see AttendanceService.checkIn's own doc comment). DayPunchIntervals below still
                  lists every individual session, including the latest. */}
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.record.checkInAt) ?? missingPunch}</div>
            </div>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                <LogOut size={11} /> Check Out
              </div>
              {/* Latest of the normal session's own checkOutAt and any Web Clock-Out(s) this day
                  — see checkOutDisplay's own comment above. */}
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(checkOutDisplay) ?? missingPunch}</div>
            </div>
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
              <Clock size={11} /> Worked Today
            </div>
            {/* Same calculation as the Attendance Log's "Effective Hours" column (both go through
                computeRowMetrics) — previously this read info.record.workedMinutes directly, a
                static total that never reflects today's still-open session live. */}
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(metrics?.effectiveMinutes ?? null) ?? dash}</div>
          </div>
          <StatusPill status={info.record.status} />
          <LateBadge minutes={info.record.lateByMinutes} checkInAt={info.record.checkInAt} shiftStartAt={info.record.shiftStartAt} graceMinutes={config?.lateGraceMinutes} workedMinutes={metrics?.effectiveMinutes ?? info.record.workedMinutes} config={config} />
          <DayShiftAndActions info={info} config={config} onRegularize={onRegularize} onApplyPartialDay={onApplyPartialDay} />
          <DayPunchIntervals info={info} punches={punches} />
        </>
      ) : info.isWeekend ? (
        <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Weekend — no attendance expected.</div>
      ) : (
        <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No attendance recorded for this day.</div>
      )}
    </div>
  );
}

/**
 * Opened directly by the Attendance Log's View button — a self-contained modal (not dependent
 * on the calendar's side panel, which can be scrolled out of view) showing the exact same
 * DayDetailsBody: shift timing, Regularize, Apply Partial Day, and the full punch history.
 */
function DayDetailsModal({ info, config, punches, workedMinutesToday, businessTodayIso, webSessions, onClose, onRegularize, onApplyPartialDay }: {
  info: DayInfo;
  config: AttendanceConfig | null;
  punches: Punch[] | undefined;
  workedMinutesToday: number | null;
  businessTodayIso: string | undefined;
  webSessions: WebClockInRecord[];
  onClose: () => void;
  onRegularize: () => void;
  onApplyPartialDay: () => void;
}) {
  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title="Attendance Details" onClose={onClose} />
        <div style={{ padding: 24 }}>
          <DayDetailsBody info={info} config={config} punches={punches} workedMinutesToday={workedMinutesToday} businessTodayIso={businessTodayIso} webSessions={webSessions} onRegularize={onRegularize} onApplyPartialDay={onApplyPartialDay} />
        </div>
      </div>
    </div>
  );
}

/** Subtle full-row tint for Weekly-off and Leave days, so the day's status reads at a glance
 * without opening the row — same predicates InlineDayBadge uses for its W-OFF/LEAVE badges
 * (leave takes priority over weekend, matching InlineDayBadge's own precedence), so the row
 * shading and the badge always agree. Colors echo each badge's own hue at low opacity: purple
 * for leave (InlineDayBadge's LEAVE badge), warm beige for weekly-off — deliberately a distinct
 * hue from the W-OFF badge's neutral gray so the two full-row states stay visually distinct at
 * a glance, per the Keka reference (cream weekend rows vs. lavender leave rows). */
function attendanceRowTint(info: DayInfo): string | undefined {
  if (info.leaveTypeName) return 'rgba(139,92,246,.08)';
  if (info.isWeekend && !info.record) return 'rgba(196,164,108,.10)';
  return undefined;
}

/** Special-day badge shown next to the date — plain PRESENT/LATE status is conveyed by the
 * Arrival/Effective Hours columns instead, so it's deliberately not repeated here. */
function InlineDayBadge({ info }: { info: DayInfo }) {
  if (info.holidayName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>Holiday</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(139,92,246,.18)', color: '#8B5CF6' }}>LEAVE</span>;
  }
  if (info.regularization) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Regularization</span>;
  }
  if (info.attendanceRequest) {
    return info.attendanceRequest.requestType === 'WFH'
      ? <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>WFH</span>
      : <span style={{ ...DAY_TAG_STYLE, background: 'rgba(224,169,59,.18)', color: '#E0A93B' }}>PARTIAL DAY</span>;
  }
  if (info.isWeekend && !info.record) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(155,161,172,.15)', color: '#9BA1AC' }}>W-OFF</span>;
  }
  return null;
}

function EffectiveHoursCell({ metrics }: { metrics: RowMetrics }) {
  const { formatDuration } = useTimeFormat();
  if (metrics.effectiveMinutes == null) return dash;
  const dotColor = metrics.openSession ? 'transparent' : 'var(--brand)';
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>
      <span style={{ width: 7, height: 7, borderRadius: '50%', background: dotColor, border: metrics.openSession ? '1.5px solid var(--brand)' : 'none', flexShrink: 0 }} />
      {formatDuration(metrics.effectiveMinutes)}{metrics.openSession ? ' +' : ''}
    </span>
  );
}

/** Same grace-aware rule as LateBadge — see its doc comment. */
function ArrivalCell({ record, graceMinutes, config }: {
  record: AttendanceRecord | undefined; graceMinutes: number | null | undefined; config?: AttendanceConfig | null;
}) {
  const { formatDuration } = useTimeFormat();
  if (!record?.checkInAt) return dash;
  const late = record.lateByMinutes ?? 0;
  const grace = graceMinutes ?? 10;
  if (late > grace) {
    const fullDay = hasMetFullEffectiveHours(record.workedMinutes, config ?? null);
    const seconds = record.shiftStartAt ? secondsBetween(record.shiftStartAt, record.checkInAt) : null;
    const text = (seconds != null ? formatLateBySeconds(seconds) : null) ?? formatDuration(late);
    return (
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#E0A93B', fontSize: 11.5, fontWeight: 600 }}>
        {!fullDay && <Turtle size={16} style={{ flexShrink: 0 }} />} {text} late
      </span>
    );
  }
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--ok)', fontSize: 11.5, fontWeight: 600 }}>
      <CheckCircle2 size={12} /> On Time
    </span>
  );
}

// ─── Logs & Requests tab bar ────────────────────────────────────────────────────
// One flat row of 4 tabs (Keka reference: nforceone.keka.com/#/me/attendance/logs) — the
// active tab gets a bordered "chip", inactive tabs stay plain text. Calendar/Attendance Log
// content lives here (inside MyAttendance, which already owns that state); Attendance
// Requests/Overtime Requests content is handed in as `otherTabContent` by the page since it
// lives in sibling components with their own state.
export type LogsTab = 'ATTENDANCE_LOG' | 'CALENDAR' | 'ATTENDANCE_REQUESTS' | 'OVERTIME';

const LOGS_TABS: { value: LogsTab; label: string }[] = [
  { value: 'ATTENDANCE_LOG', label: 'Attendance Log' },
  { value: 'CALENDAR', label: 'Calendar' },
  { value: 'ATTENDANCE_REQUESTS', label: 'Attendance Requests' },
  { value: 'OVERTIME', label: 'Overtime Requests' },
];

function LogsTabBar({ value, onChange }: { value: LogsTab; onChange: (v: LogsTab) => void }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
      {LOGS_TABS.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            onClick={() => onChange(t.value)}
            style={{
              background: active ? 'var(--raised)' : 'transparent',
              border: `1px solid ${active ? 'var(--line2)' : 'transparent'}`,
              borderRadius: 6, padding: '7px 14px', fontSize: 12, fontWeight: 600,
              color: active ? 'var(--txt)' : 'var(--txt-mut)', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {t.label}
          </button>
        );
      })}
    </div>
  );
}

/** Relocated from Quick Actions so it sits above/right of the Logs & Requests tab bar, matching the Keka reference. Same shared preference (useTimeFormat) — just one on/off switch instead of a 12h/24h pair. */
function TimeFormatToggle() {
  const { format, toggle } = useTimeFormat();
  const checked = format === '24h';
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 12, color: 'var(--txt-mut)', userSelect: 'none' }}>
      <span
        role="switch"
        aria-checked={checked}
        onClick={toggle}
        style={{
          width: 36, height: 20, borderRadius: 10, position: 'relative', flexShrink: 0,
          background: checked ? 'var(--brand)' : 'var(--line2)', transition: 'background .15s',
        }}
      >
        <span style={{
          position: 'absolute', top: 2, left: checked ? 18 : 2, width: 16, height: 16, borderRadius: '50%',
          background: '#fff', transition: 'left .15s', boxShadow: '0 1px 2px rgba(0,0,0,.25)',
        }} />
      </span>
      24 hour format
    </label>
  );
}

export interface MyAttendanceHandle {
  exportMonth: () => void;
}

const MyAttendance = forwardRef<MyAttendanceHandle, {
  isSuperAdmin: boolean;
  logsTab: LogsTab;
  onLogsTabChange: (tab: LogsTab) => void;
  /** Attendance Requests / Overtime Requests content — owned by sibling components in the page, rendered in this same box when their tab is active. */
  otherTabContent: React.ReactNode;
}>(function MyAttendance({ isSuperAdmin, logsTab, onLogsTabChange, otherTabContent }, ref) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();

  const [today, setToday] = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  // The Attendance Log's View button opens this modal directly — it doesn't rely on the
  // calendar's side panel, which can be scrolled out of view for rows further down the table.
  const [viewDetailsIso, setViewDetailsIso] = useState<string | null>(null);
  // Regularize/Apply Partial Day, opened from either the side panel or the View modal (see
  // DayShiftAndActions) — reuses the existing RequestModal/AttendanceRequestModal flows
  // directly rather than routing through the Regularization/WFH&Partial-Day tabs.
  const [regularizeDate, setRegularizeDate] = useState<string | null>(null);
  const [partialDayDate, setPartialDayDate] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Synchronous re-entrancy guard for punch() — the `disabled` attribute (driven by `submitting`)
  // only blocks a real click once React has committed the re-render, which isn't guaranteed
  // before a second click (rapid double-click, a slow/blocked main thread) reaches punch()
  // itself. A ref mutation takes effect immediately, with no render/paint dependency.
  const punchInFlightRef = useRef(false);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);

  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  // Bumped on every successful punch — PunchHistoryList's own fetch is keyed on [date, token],
  // neither of which changes on a punch, so without this it never re-fetches until something
  // else forces the whole panel to remount (e.g. a page reload).
  const [punchVersion, setPunchVersion] = useState(0);

  // Offset between the browser clock and the server's business-timezone clock, captured on
  // load, so the live elapsed counter is correct in any browser timezone.
  const serverOffsetMs = useRef(0);
  const [tick, setTick] = useState(0);

  // ── Calendar state ──
  const now = useMemo(() => new Date(), []);
  const [viewYear, setViewYear] = useState(now.getFullYear());
  const [viewMonth, setViewMonth] = useState(now.getMonth());
  const [monthRecords, setMonthRecords] = useState<AttendanceRecord[]>([]);
  const [monthLoading, setMonthLoading] = useState(true);
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [leaves, setLeaves] = useState<LeaveRequestRecord[]>([]);
  const [regularizations, setRegularizations] = useState<RegularizationRecord[]>([]);
  const [attendanceRequests, setAttendanceRequests] = useState<AttendanceRequestRecord[]>([]);
  const [selectedDate, setSelectedDate] = useState<string | null>(todayIsoDate());
  // Nothing before this date is ever shown (Attendance Log rows, Calendar cells) — a day
  // before the employee joined was never expected to have attendance of any kind.
  const [joiningDate, setJoiningDate] = useState<string | null>(null);

  // ── Attendance Log tab: date picker + pagination (replaces the old month-shortcut pills) ──
  // The underlying data is still fetched one calendar month at a time (see refreshMonth below),
  // so picking a date outside the currently-loaded month triggers goToMonth to fetch that
  // month, then this jumps straight to the page containing the picked date once its row shows
  // up in logRows.
  const LOG_PAGE_SIZE = 10;
  const [logPage, setLogPage] = useState(0);
  const [logPickedIso, setLogPickedIso] = useState(todayIsoDate());
  const [logJumpIso, setLogJumpIso] = useState<string | null>(null);

  // Holidays / leaves / regularizations / attendance requests are fetched once — the calendar
  // filters them per month.
  useEffect(() => {
    Promise.all([
      holidaysApi.listForMyLocation(token).catch(() => []),
      leaveApi.listMine(token).catch(() => []),
      regularizationApi.mine(token).catch(() => []),
      attendanceRequestApi.mine(token).catch(() => []),
    ]).then(([h, l, r, ar]) => {
      setHolidays(h); setLeaves(l); setRegularizations(r); setAttendanceRequests(ar);
    });
    profileApi.get(token).then((p) => setJoiningDate(p.joiningDate)).catch(() => setJoiningDate(null));
  }, [token]);

  const refreshMonth = useCallback(() => {
    setMonthLoading(true);
    const from = isoOf(viewYear, viewMonth, 1);
    const to = isoOf(viewYear, viewMonth, daysInMonth(viewYear, viewMonth));
    return attendanceApi.myHistory(from, to, token)
      .then((r) => setMonthRecords(r))
      .catch(() => setMonthRecords([]))
      .finally(() => setMonthLoading(false));
  }, [viewYear, viewMonth, token]);

  // Exposed to the page header's "Export selected month" button — same XLSX pattern
  // already used by DirectoryPage.tsx, reusing the month's already-fetched records.
  useImperativeHandle(ref, () => ({
    exportMonth: () => {
      const rows = monthRecords.map((r) => ({
        Date: r.workDate,
        'Check In': formatTime(r.checkInAt) ?? '',
        'Check Out': formatTime(r.checkOutAt) ?? '',
        Worked: formatDuration(r.workedMinutes) ?? '',
        Mode: r.source === 'WEB_REMOTE' ? 'Remote' : 'Office',
        Status: r.status ?? '',
      }));
      const ws = XLSX.utils.json_to_sheet(rows);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Attendance');
      XLSX.writeFile(wb, `attendance-${viewYear}-${pad2(viewMonth + 1)}.xlsx`);
    },
  }), [monthRecords, viewYear, viewMonth]);

  useEffect(() => {
    let cancelled = false;
    setMonthLoading(true);
    const from = isoOf(viewYear, viewMonth, 1);
    const to = isoOf(viewYear, viewMonth, daysInMonth(viewYear, viewMonth));
    attendanceApi.myHistory(from, to, token)
      .then((r) => { if (!cancelled) setMonthRecords(r); })
      .catch(() => { if (!cancelled) setMonthRecords([]); })
      .finally(() => { if (!cancelled) setMonthLoading(false); });
    return () => { cancelled = true; };
  }, [viewYear, viewMonth, token]);

  const recordByDate = useMemo(() => {
    const map = new Map<string, AttendanceRecord>();
    monthRecords.forEach((r) => map.set(r.workDate, r));
    return map;
  }, [monthRecords]);

  const holidayByDate = useMemo(() => {
    const map = new Map<string, string>();
    holidays.forEach((h) => { if (h.active) map.set(h.holidayDate, h.holidayName); });
    return map;
  }, [holidays]);

  const leaveByDate = useMemo(() => {
    const map = new Map<string, string>();
    leaves.filter((l) => l.status === 'APPROVED').forEach((l) => {
      expandDateRange(l.startDate, l.endDate).forEach((iso) => map.set(iso, l.leaveTypeName));
    });
    return map;
  }, [leaves]);

  // Calendar marks/details only ever reflect APPROVED requests — pending/rejected requests
  // are not shown on the calendar at all.
  const regularizationByDate = useMemo(() => {
    const map = new Map<string, RegularizationRecord>();
    regularizations.filter((r) => r.status === 'APPROVED').forEach((r) => map.set(r.attendanceDate, r));
    return map;
  }, [regularizations]);

  const attendanceRequestByDate = useMemo(() => {
    const map = new Map<string, AttendanceRequestRecord>();
    attendanceRequests.filter((r) => r.status === 'APPROVED').forEach((r) => map.set(r.requestDate, r));
    return map;
  }, [attendanceRequests]);


  const monthPrefix = `${viewYear}-${pad2(viewMonth + 1)}`;
  const presentDaysCount = monthRecords.filter((r) => r.checkInAt).length;
  const workedMinutesTotal = monthRecords.reduce((sum, r) => sum + (r.workedMinutes ?? 0), 0);
  const leaveHolidayCount = useMemo(() => {
    let count = 0;
    for (const iso of holidayByDate.keys()) if (iso.startsWith(monthPrefix)) count++;
    for (const iso of leaveByDate.keys()) if (iso.startsWith(monthPrefix)) count++;
    return count;
  }, [holidayByDate, leaveByDate, monthPrefix]);

  const getDayInfo = useCallback((day: number): DayInfo => {
    const iso = isoOf(viewYear, viewMonth, day);
    const dow = new Date(viewYear, viewMonth, day).getDay();
    // ONEHR-108: respects the employee's assigned WeeklyOffPolicy when loaded, else Sat/Sun.
    const isWeekend = config
      ? config.weeklyOffDays.includes(DOW_NAMES[dow])
      : dow === 0 || dow === 6;
    // Compared against the business/Location-zone work date (today?.workDate) — never the
    // browser's own UTC calendar date, which can disagree with it near midnight or whenever the
    // employee's device zone differs from their assigned Location's zone. Falls back to the
    // browser date only before the first /today fetch resolves.
    const businessTodayIso = today?.workDate ?? todayIsoDate();
    return {
      iso,
      day,
      isFuture: iso > businessTodayIso,
      isBeforeJoining: !!joiningDate && iso < joiningDate,
      isToday: iso === businessTodayIso,
      isWeekend,
      holidayName: holidayByDate.get(iso),
      leaveTypeName: leaveByDate.get(iso),
      regularization: regularizationByDate.get(iso),
      attendanceRequest: attendanceRequestByDate.get(iso),
      record: recordByDate.get(iso),
    };
  }, [viewYear, viewMonth, config, joiningDate, holidayByDate, leaveByDate, regularizationByDate, attendanceRequestByDate, recordByDate, today?.workDate]);

  function goToPrevMonth() {
    setSelectedDate(null);
    if (viewMonth === 0) { setViewYear((y) => y - 1); setViewMonth(11); } else { setViewMonth((m) => m - 1); }
  }
  function goToNextMonth() {
    setSelectedDate(null);
    if (viewMonth === 11) { setViewYear((y) => y + 1); setViewMonth(0); } else { setViewMonth((m) => m + 1); }
  }
  /** Month-shortcut buttons — jumps directly to a given month/year, same reset behavior as prev/next. */
  function goToMonth(year: number, month: number) {
    setSelectedDate(null);
    setViewYear(year);
    setViewMonth(month);
  }

  const approvedRegularizationDates = useMemo(
    () => new Set(regularizationByDate.keys()),
    [regularizationByDate],
  );

  // Every non-future day of the month, newest first — reuses getDayInfo exactly as
  // MonthCalendar does, so weekends/leaves/holidays appear as rows even with no punch.
  const logRows = useMemo(() => {
    const total = daysInMonth(viewYear, viewMonth);
    const rows: DayInfo[] = [];
    for (let d = 1; d <= total; d++) {
      const info = getDayInfo(d);
      if (!info.isFuture && !info.isBeforeJoining) rows.push(info);
    }
    return rows.reverse();
  }, [viewYear, viewMonth, getDayInfo]);

  // Reset to page 1 whenever the month changes (new row set) — mirrors RosterTable's own
  // page-reset-on-new-rows behavior.
  useEffect(() => { setLogPage(0); }, [viewYear, viewMonth]);

  // Once the picked date's month has loaded (or was already loaded), land on the page that
  // actually contains that day instead of leaving the user on page 1.
  useEffect(() => {
    if (!logJumpIso) return;
    const idx = logRows.findIndex((r) => r.iso === logJumpIso);
    if (idx >= 0) setLogPage(Math.floor(idx / LOG_PAGE_SIZE));
    setLogJumpIso(null);
  }, [logRows, logJumpIso]);

  /** Attendance Log's date picker — jumps months only when the picked date actually falls
   * outside the one currently loaded, then pages straight to that day's row. */
  function handleLogDatePick(iso: string) {
    setLogPickedIso(iso);
    const [y, m] = iso.split('-').map(Number);
    if (y !== viewYear || m - 1 !== viewMonth) goToMonth(y, m - 1);
    setLogJumpIso(iso);
  }

  const logTotalPages = Math.ceil(logRows.length / LOG_PAGE_SIZE);
  const logPaged = logRows.slice(logPage * LOG_PAGE_SIZE, (logPage + 1) * LOG_PAGE_SIZE);

  // Real per-day punches (for the Attendance Log's multi-segment timeline + break calc, and for
  // DayPunchIntervals in the View/details side panel), fetched once per punched day and cached
  // across month switches via the existing per-date /attendance/punches/{date} endpoint.
  const [punchesByDate, setPunchesByDate] = useState<Map<string, Punch[]>>(new Map());

  // This employee's full Web Clock-In history, refreshed alongside `today` — needed so the Check
  // In/Check Out summary can combine Web Clock-In/Out with the normal session (Web Clock-Out
  // never writes the shared Attendance record's own checkOutAt, see WebClockInService's own class
  // Javadoc). Deliberately separate from punchesByDate/Punch History's own fetch — this state is
  // only ever read by the summary fields below, never by PunchHistoryList/DayPunchIntervals.
  const [webRecords, setWebRecords] = useState<WebClockInRecord[]>([]);
  const webForIso = useCallback(
    (iso: string) => webRecords.filter((r) => r.workDate === iso),
    [webRecords],
  );
  useEffect(() => {
    const datesNeeded = logRows
      .filter((info) => info.record?.checkInAt && !punchesByDate.has(info.iso))
      .map((info) => info.iso);
    if (datesNeeded.length === 0) return;
    let cancelled = false;
    Promise.all(datesNeeded.map((iso) =>
      attendanceApi.punches(iso, token)
        .then((p) => [iso, p] as const)
        .catch(() => [iso, [] as Punch[]] as const),
    )).then((results) => {
      if (cancelled) return;
      setPunchesByDate((prev) => {
        const next = new Map(prev);
        results.forEach(([iso, p]) => next.set(iso, p));
        return next;
      });
    });
    return () => { cancelled = true; };
    // punchesByDate is read only to skip already-cached dates — omitted from deps deliberately,
    // since including it would make this effect re-run every time it updates its own state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [logRows, token]);

  const selectedInfo = useMemo(() => {
    if (!selectedDate) return null;
    const [y, m, d] = selectedDate.split('-').map(Number);
    if (y !== viewYear || m - 1 !== viewMonth) return null;
    return getDayInfo(d);
  }, [selectedDate, viewYear, viewMonth, getDayInfo]);

  useEffect(() => {
    let cancelled = false;
    attendanceApi.today(token)
      .then((t) => {
        if (cancelled) return;
        serverOffsetMs.current = wallClockMs(t.serverNow) - Date.now();
        setToday(t);
      })
      .catch((err) => {
        if (!cancelled) showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    webClockInApi.mine(token).then((list) => { if (!cancelled) setWebRecords(list); }).catch(() => {});
    return () => { cancelled = true; };
  }, [token, showToast]);

  // sessionStartedAt (not checkInAt) — the currently-open session's own start, so a resumed
  // session's live worked-time (workedMinutesToday below) counts from its own resume, not the
  // day's original check-in (which would wrongly include the break). This is purely an input to
  // that live calculation — it must never be read for a DISPLAYED "Check In" time; the day's
  // original checkInAt (frozen across every resume, see AttendanceService.checkIn's own doc
  // comment) is what "Check In" itself always shows, see the render below.
  const openSince = today?.canCheckOut ? today.record?.sessionStartedAt ?? today.record?.checkInAt ?? null : null;

  useEffect(() => {
    if (!openSince) return;
    const id = setInterval(() => setTick((n) => n + 1), 60000);
    return () => clearInterval(id);
  }, [openSince]);

  // Raw minutes-worked-so-far-today, for the Today's Timings progress bar / break panel, the
  // Attendance Log's "Effective Hours" column, and the Calendar's "Worked Today" figure — the
  // ONE shared live-aware calculation all three read, rather than each computing (or a UI
  // element separately re-deriving) its own "elapsed" figure that could drift from this one.
  const workedMinutesToday = useMemo(() => {
    if (openSince) {
      void tick;
      const minutes = Math.floor((Date.now() + serverOffsetMs.current - wallClockMs(openSince)) / 60000);
      return minutes >= 0 ? minutes : null;
    }
    return today?.record?.workedMinutes ?? null;
  }, [openSince, tick, today]);

  // Check In/Check Out summary for today: Check In is always the day's original checkInAt
  // (frozen across every resume regardless of source, see AttendanceService.checkIn's own doc
  // comment) — locked for the entire resolved work day, never the latest In. Check Out is the
  // LATEST of the normal session's own checkOutAt and any Web Clock-Out(s) the same work day
  // (Web Clock-Out never writes the shared record's own checkOutAt, see WebClockInService's own
  // class Javadoc) — so it keeps updating to whichever source most recently closed a session.
  const todayCheckOutDisplay = useMemo(() => {
    if (!today) return null;
    return latestIso(today.record?.checkOutAt, ...webForIso(today.workDate).map((r) => r.checkedOutAt));
  }, [today, webForIso]);

  // Re-read /today so canCheckIn/canCheckOut always come from the server, never inferred — the
  // ONE shared refresh every check-in/check-out/web-check-in entry point calls afterward, so
  // every consumer of `today` (Today's Timings, the calendar's Today's-workday panel, and the
  // Actions panel) ends up looking at the exact same state instead of each keeping its own.
  const refreshTodayAndMonth = useCallback(async () => {
    const [refreshed] = await Promise.all([
      attendanceApi.today(token),
      refreshMonth(),
    ]);
    serverOffsetMs.current = wallClockMs(refreshed.serverNow) - Date.now();
    setToday(refreshed);
    setPunchVersion((v) => v + 1);
    // The Attendance Details modal (View button + calendar side panel) reads
    // punchesByDate.get(iso) — that map's own effect only ever fetches a date once (see its
    // comment), so without this a fresh Check-In/Check-Out/Web Check-In here wouldn't show up
    // there until something else forced logRows to change or the page was reloaded. Not
    // awaited — this is a followup refresh, no reason to hold up the toast/UI on it.
    attendanceApi.punches(refreshed.workDate, token)
      .then((p) => setPunchesByDate((prev) => new Map(prev).set(refreshed.workDate, p)))
      .catch(() => {});
    // Same followup-refresh treatment for the Check In/Check Out summary's own Web Clock-In
    // history — see webRecords' own comment.
    webClockInApi.mine(token).then(setWebRecords).catch(() => {});
    return refreshed;
  }, [token, refreshMonth]);

  async function punch(kind: 'in' | 'out') {
    if (punchInFlightRef.current) return;
    punchInFlightRef.current = true;
    setSubmitting(true);
    try {
      const record = kind === 'in'
        ? await attendanceApi.checkIn(token)
        : await attendanceApi.checkOut(token);
      await refreshTodayAndMonth();

      // record.checkInAt is the day's *original* check-in (deliberately never updated on a
      // lunch-break resume, see AttendanceService.checkIn) — not what just happened on a
      // repeat check-in, so use sessionStartedAt (updates on every check-in, including a
      // resume) instead. checkOutAt is always the latest checkout, so it's fine as-is.
      // Note: this used to read refreshed.serverNow, but that comes from a second, later
      // /today call and picks up whatever latency that round trip has, making the toast read
      // later than the moment the employee actually punched in.
      const at = formatTime(kind === 'in' ? (record.sessionStartedAt ?? record.checkInAt) : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      punchInFlightRef.current = false;
      setSubmitting(false);
    }
  }

  const primaryButtonStyle = (disabled: boolean): React.CSSProperties => ({
    display: 'flex', alignItems: 'center', gap: 7,
    background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7,
    padding: '10px 20px', fontSize: 12.5, fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {/* Attendance Stats / Today's Timings / Quick Actions */}
      {/* minmax floor is 260px, not 280px: at a common "zoomed in a bit" width (~1164px
          effective, e.g. a 1280px display at 110% browser zoom) the grid needed 868px to hold
          Attendance Stats/Today's Timings/Actions in one row at 280px each but only had ~861px
          — a ~7px shortfall that silently dropped Actions to its own row and read as a
          role-specific layout bug. Same knife's-edge pattern as the header button fix above;
          verified this reflow happens identically for every role at that width, since all roles
          render this exact same grid. 260px still comfortably fits each card's content. */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14 }}>
        <AttendanceStatsPanel token={token} />
        <TodaysTimingsPanel today={today} config={config} workedMinutesToday={workedMinutesToday} />
        <QuickActionsPanel
          token={token}
          today={today}
          todayLoading={loading}
          submitting={submitting}
          onCheckIn={() => punch('in')}
          onCheckOut={() => punch('out')}
          onWebCheckInSubmitted={refreshTodayAndMonth}
        />
      </div>

      {/* Logs & Requests — Keka-style 4-tab bar (nforceone.keka.com/#/me/attendance/logs).
          Calendar/Attendance Log render inline below since they share this component's state;
          Attendance Requests/Overtime Requests are handed in as otherTabContent — they live in
          sibling components with their own state. */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12, marginBottom: 12 }}>
          <h2 style={{ fontFamily: 'Inter, sans-serif', fontSize: 13, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Logs & Requests</h2>
          <TimeFormatToggle />
        </div>
        <LogsTabBar value={logsTab} onChange={onLogsTabChange} />

        {logsTab === 'CALENDAR' && (
        <div style={{ marginTop: 14 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 9, marginBottom: 14 }}>
          <MonthStatTile label="Present Days" value={monthLoading ? '—' : String(presentDaysCount)} hint="Selected month" />
          <MonthStatTile label="Worked Hours" value={monthLoading ? '—' : (formatDuration(workedMinutesTotal) ?? '0m')} hint="Selected month" />
          <MonthStatTile label="Leave / Holidays" value={String(leaveHolidayCount)} hint="Selected month" />
        </div>

        <div className="nf-grid-proportional-collapse" style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, alignItems: 'start' }}>
          <MonthCalendar
            year={viewYear}
            month={viewMonth}
            dayInfo={getDayInfo}
            selectedDate={selectedDate}
            onSelect={setSelectedDate}
            onPrev={goToPrevMonth}
            onNext={goToNextMonth}
          />

          <div style={{ ...panelStyle, padding: '16px 18px' }}>
            {!selectedInfo ? (
              <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Pick a day on the calendar to see its details.</div>
            ) : selectedInfo.isToday ? (
              // Today's workday — merged from the old standalone punch card, now living
              // in the calendar's side panel with the exact same today/punch() state.
              <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)' }}>Today's workday</div>
                {loading ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Loading…</div>
                ) : !today ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Attendance unavailable right now.</div>
                ) : (
                  <>
                    <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogIn size={11} /> Check In
                        </div>
                        {/* The day's original checkInAt — locked for the entire resolved work
                            day regardless of source (see AttendanceService.checkIn's own doc
                            comment: never touched on a resume), so a later Check-In/Web Clock-In
                            cycle never replaces what's shown here. */}
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>
                          {formatTime(today.record?.checkInAt ?? null) ?? dash}
                        </div>
                      </div>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogOut size={11} /> Check Out
                        </div>
                        {/* Latest of the normal session's own checkOutAt and any Web Clock-Out(s)
                            today — see todayCheckOutDisplay's own comment. */}
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(todayCheckOutDisplay) ?? dash}</div>
                      </div>
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 9.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                        <Clock size={11} /> Worked Today
                      </div>
                      {/* Same underlying figure as the Attendance Log's "Effective Hours" column
                          for today's row (both read workedMinutesToday) — previously this read
                          today.record?.workedMinutes directly instead, a second, independent
                          source that could disagree with Effective Hours rather than matching it. */}
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>
                        {formatDuration(workedMinutesToday) ?? dash}
                      </div>
                    </div>
                    {today.record?.status && <StatusPill status={today.record.status} />}
                    <LateBadge minutes={today.record?.lateByMinutes} checkInAt={today.record?.checkInAt} shiftStartAt={today.record?.shiftStartAt} graceMinutes={config?.lateGraceMinutes} workedMinutes={workedMinutesToday} config={config} />
                    {/* The button is driven only by the server's canCheckIn / canCheckOut flags. */}
                    <div>
                      {today.canCheckIn && (
                        <button onClick={() => punch('in')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogIn size={14} /> {submitting ? 'Checking in…' : 'Check In'}
                        </button>
                      )}
                      {today.canCheckOut && (
                        <button onClick={() => punch('out')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogOut size={14} /> {submitting ? 'Checking out…' : 'Check Out'}
                        </button>
                      )}
                      {!today.canCheckIn && !today.canCheckOut && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 7, color: 'var(--ok)', fontSize: 12, fontWeight: 600 }}>
                          <CheckCircle2 size={15} /> Day complete
                        </div>
                      )}
                    </div>
                    <DayShiftAndActions
                      info={selectedInfo}
                      config={config}
                      onRegularize={() => setRegularizeDate(selectedInfo.iso)}
                      onApplyPartialDay={() => setPartialDayDate(selectedInfo.iso)}
                    />
                    {/* PunchHistoryList (not DayPunchIntervals) here specifically — it self-fetches
                        with a refreshKey, so today's list updates immediately after a punch instead
                        of showing whatever punchesByDate cached before the punch happened. */}
                    <PunchHistoryList date={selectedInfo.iso} token={token} refreshKey={punchVersion} />
                  </>
                )}
              </div>
            ) : (
              <DayDetailsBody
                info={selectedInfo}
                config={config}
                punches={punchesByDate.get(selectedInfo.iso)}
                workedMinutesToday={workedMinutesToday}
                businessTodayIso={today?.workDate}
                webSessions={webForIso(selectedInfo.iso)}
                onRegularize={() => setRegularizeDate(selectedInfo.iso)}
                onApplyPartialDay={() => setPartialDayDate(selectedInfo.iso)}
              />
            )}
          </div>
        </div>
        </div>
        )}

        {logsTab === 'ATTENDANCE_LOG' && (
        <div style={{ marginTop: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>{calendarMonthLabel(viewYear, viewMonth)}</div>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, color: 'var(--txt-mut)' }}>
              Date
              <input
                type="date"
                value={logPickedIso}
                min={joiningDate ?? undefined}
                max={today?.workDate ?? todayIsoDate()}
                onChange={(e) => e.target.value && handleLogDatePick(e.target.value)}
                style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 9px', color: 'var(--txt)', fontSize: 12 }}
              />
            </label>
          </div>
          <div style={panelStyle}>
            {monthLoading ? (
              <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Loading…</div>
            ) : logRows.length === 0 ? (
              <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>No days to show for this month.</div>
            ) : (
              <>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>{['Date', 'Attendance Visual', 'Effective Hours', 'Break Taken', 'Gross Hours', 'Arrival', 'Log'].map((h) => (
                      <th key={h} style={h === 'Attendance Visual' ? { ...thStyle, width: ATTENDANCE_VISUAL_COL_WIDTH, minWidth: ATTENDANCE_VISUAL_COL_WIDTH } : thStyle}>{h}</th>
                    ))}</tr>
                  </thead>
                  <tbody>
                    {logPaged.map((info) => {
                      const punches = punchesByDate.get(info.iso);
                      const punchesLoading = !!info.record?.checkInAt && !punches;
                      const metrics = computeRowMetrics(info, punches, workedMinutesToday, today?.workDate);
                      return (
                        <tr key={info.iso} style={{ background: attendanceRowTint(info) }}>
                          <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>
                            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{formatDay(info.iso)}</span>
                              <InlineDayBadge info={info} />
                            </span>
                          </td>
                          <td style={tdStyle}>
                            <AttendanceTimeline info={info} punches={punches} punchesLoading={punchesLoading} />
                          </td>
                          <td style={tdStyle}><EffectiveHoursCell metrics={metrics} /></td>
                          <td style={tdStyle}>{formatDuration(metrics.breakMinutes) ?? dash}</td>
                          <td style={tdStyle}>{formatDuration(metrics.grossMinutes) ?? dash}{metrics.grossMinutes != null && metrics.openSession ? ' +' : ''}</td>
                          <td style={tdStyle}><ArrivalCell record={info.record} graceMinutes={config?.lateGraceMinutes} config={config} /></td>
                          <td style={tdStyle}>
                            <button
                              onClick={() => setViewDetailsIso(info.iso)}
                              style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '3px 9px', fontSize: 10, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600 }}
                            >
                              <Eye size={10.5} /> View
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {logTotalPages > 1 && (
                <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between', flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
                    Showing {logPage * LOG_PAGE_SIZE + 1}–{Math.min((logPage + 1) * LOG_PAGE_SIZE, logRows.length)} of {logRows.length}
                  </span>
                  <div style={{ display: 'flex', gap: 4 }}>
                    <button
                      disabled={logPage === 0}
                      onClick={() => setLogPage((p) => p - 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: logPage === 0 ? 'not-allowed' : 'pointer', opacity: logPage === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronLeft size={13} />
                    </button>
                    {Array.from({ length: Math.min(logTotalPages, 7) }, (_, i) => {
                      const p = logTotalPages <= 7 ? i : logPage <= 3 ? i : logPage >= logTotalPages - 4 ? logTotalPages - 7 + i : logPage - 3 + i;
                      return (
                        <button
                          key={p}
                          onClick={() => setLogPage(p)}
                          style={{ padding: '5px 10px', minWidth: 32, background: logPage === p ? 'var(--brand)' : 'var(--raised)', border: `1px solid ${logPage === p ? 'var(--brand)' : 'var(--line2)'}`, borderRadius: 5, cursor: 'pointer', color: logPage === p ? '#fff' : 'var(--txt)', fontSize: 11.5, fontWeight: logPage === p ? 700 : 400 }}
                        >
                          {p + 1}
                        </button>
                      );
                    })}
                    <button
                      disabled={logPage === logTotalPages - 1}
                      onClick={() => setLogPage((p) => p + 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: logPage === logTotalPages - 1 ? 'not-allowed' : 'pointer', opacity: logPage === logTotalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronRight size={13} />
                    </button>
                  </div>
                </div>
              )}
              </>
            )}
          </div>
        </div>
        )}

        {logsTab !== 'CALENDAR' && logsTab !== 'ATTENDANCE_LOG' && (
          <div style={{ marginTop: 14 }}>{otherTabContent}</div>
        )}
      </div>

      {viewDetailsIso && (() => {
        const viewInfo = logRows.find((r) => r.iso === viewDetailsIso);
        if (!viewInfo) return null;
        return (
          <DayDetailsModal
            info={viewInfo}
            config={config}
            punches={punchesByDate.get(viewDetailsIso)}
            workedMinutesToday={workedMinutesToday}
            businessTodayIso={today?.workDate}
            webSessions={webForIso(viewDetailsIso)}
            onClose={() => setViewDetailsIso(null)}
            onRegularize={() => { setViewDetailsIso(null); setRegularizeDate(viewDetailsIso); }}
            onApplyPartialDay={() => { setViewDetailsIso(null); setPartialDayDate(viewDetailsIso); }}
          />
        );
      })()}

      {regularizeDate && (
        <RequestModal
          token={token}
          initialDate={regularizeDate}
          approvedDates={approvedRegularizationDates}
          isSuperAdmin={isSuperAdmin}
          onClose={() => setRegularizeDate(null)}
          onSaved={(r) => { setRegularizations((prev) => [r, ...prev]); setRegularizeDate(null); }}
        />
      )}

      {partialDayDate && (
        <AttendanceRequestModal
          presetType="PARTIAL_DAY"
          token={token}
          initialDate={partialDayDate}
          onClose={() => setPartialDayDate(null)}
          onSaved={(r) => { setAttendanceRequests((prev) => [r, ...prev]); setPartialDayDate(null); }}
        />
      )}
    </div>
  );
});

// ─── Regularization (request + my requests + pending approvals) ───────────────

/** Reviewer column: current approver while pending, who decided it once resolved. */
function ReviewerCell({ r }: { r: RegularizationRecord }) {
  if (r.status === 'PENDING') {
    return (
      <>
        <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>Current Approver</div>
        {r.assignedApproverName ?? dash}
      </>
    );
  }
  if (r.status === 'PARTIALLY_APPROVED') {
    return (
      <>
        <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>Manager Approved — Awaiting HR/Super Admin</div>
        {r.approvedByName ?? dash}
      </>
    );
  }
  return (
    <>
      <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>{r.status === 'APPROVED' ? 'Approved By' : 'Rejected By'}</div>
      {r.reviewedByName ?? dash}
    </>
  );
}

function MonthGroupHeading({ monthKey }: { monthKey: string }) {
  return (
    <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', margin: '12px 0 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>
      {monthLabel(monthKey)}
    </div>
  );
}

// ─── Status tabs (reusable) ─────────────────────────────────────────────────────
// Generic single-select tab strip. Used by Pending Approvals for its All/Pending/Approved/
// Rejected status filter — deliberately generic (not hardcoded to status) so any future
// single-select category filter elsewhere on this page can reuse it as-is.
type StatusFilterValue = 'ALL' | 'PENDING' | 'PARTIALLY_APPROVED' | 'APPROVED' | 'REJECTED';

const STATUS_FILTER_TABS: { value: StatusFilterValue; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'PARTIALLY_APPROVED', label: 'Partially Approved' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
];

function FilterTabs<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string }[]; onChange: (next: T) => void;
}) {
  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              background: active ? 'var(--brand)' : 'var(--raised)',
              color: active ? '#fff' : 'var(--txt-mut)',
              border: `1px solid ${active ? 'var(--brand)' : 'var(--line2)'}`,
              borderRadius: 6, padding: '6px 12px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

// ─── My Requests month filter ──────────────────────────────────────────────────
// The only filter on My Requests (Employee, Manager, HR alike) — defaults to "All Months"
// so every request is visible until the user narrows it down.
const ALL_MONTHS_VALUE = 'ALL';

function MonthFilter({ month, onChange }: { month: string; onChange: (v: string) => void }) {
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11, color: 'var(--txt-dim)' }}>
      Month
      <select value={month} onChange={(e) => onChange(e.target.value)} style={dateInputStyle}>
        <option value={ALL_MONTHS_VALUE}>All Months</option>
        {MONTH_NAMES.map((name, i) => (
          <option key={name} value={String(i + 1).padStart(2, '0')}>{name}</option>
        ))}
      </select>
    </label>
  );
}

export interface RegularizationSectionHandle {
  openNewRequest: () => void;
}

const RegularizationSection = forwardRef<RegularizationSectionHandle, { token: string; canApprove: boolean; isSuperAdmin: boolean; isManager: boolean }>(
  function RegularizationSection({ token, canApprove, isSuperAdmin, isManager }, ref) {
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();
  const [myRequests, setMyRequests] = useState<RegularizationRecord[]>([]);
  const [pending, setPending] = useState<RegularizationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [editing, setEditing] = useState<RegularizationRecord | null>(null);
  const [rejecting, setRejecting] = useState<RegularizationRecord | null>(null);
  const [approving, setApproving] = useState<RegularizationRecord | null>(null);
  const [viewing, setViewing] = useState<RegularizationRecord | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkConfirm, setBulkConfirm] = useState<'APPROVE' | 'REJECT' | null>(null);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [regularizationApi.mine(token).then(setMyRequests)];
    // Every status the reviewer can see (not just PENDING) — the All/Pending/Approved/Rejected
    // tabs below filter this same list client-side, so Approved/Rejected history is visible too.
    if (canApprove) calls.push(regularizationApi.forApprover(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load regularization requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // My Requests: the only filter is Month, defaulting to "All Months" (every request visible
  // until narrowed). No status tabs here — those live on Pending Approvals instead.
  const [selectedMonth, setSelectedMonth] = useState(ALL_MONTHS_VALUE);
  const filteredMyRequests = useMemo(
    () => selectedMonth === ALL_MONTHS_VALUE
      ? myRequests
      : myRequests.filter((r) => r.attendanceDate.slice(5, 7) === selectedMonth),
    [myRequests, selectedMonth],
  );
  const myRequestMonths = useMemo(() => groupByMonth(filteredMyRequests), [filteredMyRequests]);

  // Pending Approvals: status tabs (All/Pending/Approved/Rejected), defaulting to Pending to
  // match the screen's pre-existing default view. Sourced from /for-approver (every status the
  // reviewer can see), not /pending (PENDING-only) — see loadAll below.
  const [approvalStatusFilter, setApprovalStatusFilter] = useState<StatusFilterValue>('PENDING');
  const filteredPending = useMemo(
    () => approvalStatusFilter === 'ALL' ? pending : pending.filter((r) => r.status === approvalStatusFilter),
    [pending, approvalStatusFilter],
  );
  // Flat, newest-first (not grouped into one table per month — see date picker/pagination
  // below instead) — same sort groupByMonth used internally, just without the month buckets.
  const sortedPending = useMemo(
    () => [...filteredPending].sort((a, b) => b.attendanceDate.localeCompare(a.attendanceDate)),
    [filteredPending],
  );
  // Selections don't carry across an unrelated filter change — avoids acting on a row the
  // user can no longer see.
  useEffect(() => { setSelectedIds(new Set()); }, [approvalStatusFilter]);
  const approvedDates = useMemo(
    () => new Set(myRequests.filter((r) => r.status === 'APPROVED').map((r) => r.attendanceDate)),
    [myRequests],
  );

  // Pending Approvals: date picker + pagination (replaces the old "one table per month,
  // stacked forever" layout). Data stays a flat sortedPending list — the picker just jumps to
  // whichever page contains the nearest request on or before the chosen date.
  const APPROVALS_PAGE_SIZE = 10;
  const [approvalsPage, setApprovalsPage] = useState(0);
  const [approvalsPickedIso, setApprovalsPickedIso] = useState(todayIsoDate());
  useEffect(() => { setApprovalsPage(0); }, [sortedPending]);
  function handleApprovalsDatePick(iso: string) {
    setApprovalsPickedIso(iso);
    const idx = sortedPending.findIndex((r) => r.attendanceDate <= iso);
    setApprovalsPage(idx >= 0 ? Math.floor(idx / APPROVALS_PAGE_SIZE) : 0);
  }
  const approvalsTotalPages = Math.ceil(sortedPending.length / APPROVALS_PAGE_SIZE);
  const approvalsPaged = sortedPending.slice(approvalsPage * APPROVALS_PAGE_SIZE, (approvalsPage + 1) * APPROVALS_PAGE_SIZE);

  // Exposed to the page header's "Request Regularization" button — opens the exact same
  // create-mode modal the section's own flow uses.
  useImperativeHandle(ref, () => ({
    openNewRequest: () => { setEditing(null); setShowRequest(true); },
  }), []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
        <SectionHeading title="Attendance Regularization" hint="Request corrections for missed or incorrect punches." />
        {isSuperAdmin && (
          <Link to="/attendance/regularization/all" style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 14px', fontSize: 12, fontWeight: 600, textDecoration: 'none' }}>
            <ShieldCheck size={13} /> View All & Audit Trail
          </Link>
        )}
      </div>

      {/* My Regularization Requests — month filter only, grouped by month within that filter */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <h3 style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>My Requests</h3>
          <MonthFilter month={selectedMonth} onChange={setSelectedMonth} />
        </div>
        {loading ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Loading…</div>
        ) : myRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>No requests submitted yet.</div>
        ) : filteredMyRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>
            No requests found for {MONTH_NAMES[parseInt(selectedMonth, 10) - 1]}.
          </div>
        ) : (
          myRequestMonths.map(([monthKey, rows]) => (
            <div key={monthKey}>
              <MonthGroupHeading monthKey={monthKey} />
              <div style={panelStyle}>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr>{['Date', 'Requested In', 'Requested Out', 'Total Hours', 'Reason', 'Status', 'Approver / Reviewer', 'Comments', 'Action'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                    <tbody>
                      {rows.map(r => (
                        <tr key={r.id} onClick={() => setViewing(r)} style={{ cursor: 'pointer' }}>
                          <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.attendanceDate}</td>
                          <td style={tdStyle}>{formatTime(r.requestedCheckIn) ?? dash}</td>
                          <td style={tdStyle}>{formatTime(r.requestedCheckOut) ?? dash}</td>
                          <td style={tdStyle}>{formatDuration(r.totalMinutes) ?? dash}</td>
                          <td style={{ ...tdStyle, maxWidth: 200 }}><TruncatedText text={r.reason} /></td>
                          <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                          <td style={tdStyle}><ReviewerCell r={r} /></td>
                          <td style={{ ...tdStyle, maxWidth: 180 }}><TruncatedText text={r.reviewComment} /></td>
                          <td style={tdStyle}>
                            {r.status === 'PENDING' && (
                              <button
                                onClick={(e) => { e.stopPropagation(); setEditing(r); }}
                                title="Edit"
                                style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '4px 8px', fontSize: 11, color: 'var(--txt-mut)', cursor: 'pointer' }}
                              >
                                <Pencil size={11} /> Edit
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
          ))
        )}
      </div>

      {/* Pending Approvals — Manager / HR Admin / Super Admin only. Flat, newest-first list with
          a date picker (jumps to whichever page holds the nearest request on/before that date)
          and pagination, instead of one table stacked per month. Status tabs filter across
          every status the reviewer can see (not just PENDING). */}
      {canApprove && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <h3 style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>Pending Approvals</h3>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, color: 'var(--txt-mut)' }}>
                Date
                <input
                  type="date"
                  value={approvalsPickedIso}
                  onChange={(e) => e.target.value && handleApprovalsDatePick(e.target.value)}
                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 9px', color: 'var(--txt)', fontSize: 12 }}
                />
              </label>
              <FilterTabs value={approvalStatusFilter} options={STATUS_FILTER_TABS} onChange={setApprovalStatusFilter} />
            </div>
          </div>
          {selectedIds.size > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 10, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 13px' }}>
              <span style={{ fontSize: 12, color: 'var(--txt-mut)', fontWeight: 600 }}>{selectedIds.size} selected</span>
              <button onClick={() => setBulkConfirm('APPROVE')} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '5px 11px', fontSize: 11, fontWeight: 600, color: '#2FB67C', cursor: 'pointer' }}>Bulk Approve</button>
              <button onClick={() => setBulkConfirm('REJECT')} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '5px 11px', fontSize: 11, fontWeight: 600, color: '#E4373D', cursor: 'pointer' }}>Bulk Reject</button>
              <button onClick={() => setSelectedIds(new Set())} style={{ background: 'none', border: 'none', color: 'var(--txt-dim)', fontSize: 11, cursor: 'pointer', marginLeft: 'auto' }}>Clear selection</button>
            </div>
          )}
          {pending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>No requests to review yet.</div>
          ) : filteredPending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>
              No {STATUS_FILTER_TABS.find((t) => t.value === approvalStatusFilter)?.label.toLowerCase()} requests.
            </div>
          ) : (() => {
            // Select-all is scoped to the current page, same as any other paginated table's
            // header checkbox — not every filtered row across every page.
            const selectableIds = approvalsPaged.filter(r => isActionableRequest(r, isManager)).map(r => r.id);
            const allSelected = selectableIds.length > 0 && selectableIds.every(id => selectedIds.has(id));
            const someSelected = !allSelected && selectableIds.some(id => selectedIds.has(id));
            return (
            <div style={panelStyle}>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th style={{ ...thStyle, width: 34 }}>
                        {selectableIds.length > 0 && (
                          <input
                            type="checkbox"
                            checked={allSelected}
                            ref={el => { if (el) el.indeterminate = someSelected; }}
                            onChange={() => setSelectedIds(prev => {
                              const next = new Set(prev);
                              if (allSelected) selectableIds.forEach(id => next.delete(id));
                              else selectableIds.forEach(id => next.add(id));
                              return next;
                            })}
                          />
                        )}
                      </th>
                      {['Employee', 'Date', 'Requested In', 'Requested Out', 'Total Hours', 'Reason', 'Status', 'Reviewer', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {approvalsPaged.map(r => (
                      <tr key={r.id} onClick={() => setViewing(r)} style={{ cursor: 'pointer' }}>
                        <td style={tdStyle} onClick={e => e.stopPropagation()}>
                          {isActionableRequest(r, isManager) && (
                            <input
                              type="checkbox"
                              checked={selectedIds.has(r.id)}
                              onChange={() => setSelectedIds(prev => {
                                const next = new Set(prev);
                                if (next.has(r.id)) next.delete(r.id); else next.add(r.id);
                                return next;
                              })}
                            />
                          )}
                        </td>
                        <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                          {r.employeeName}
                          <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
                        </td>
                        <td style={tdStyle}>{r.attendanceDate}</td>
                        <td style={tdStyle}>{formatTime(r.requestedCheckIn) ?? dash}</td>
                        <td style={tdStyle}>{formatTime(r.requestedCheckOut) ?? dash}</td>
                        <td style={tdStyle}>{formatDuration(r.totalMinutes) ?? dash}</td>
                        <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                        <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                        <td style={tdStyle}><ReviewerCell r={r} /></td>
                        <td style={tdStyle}>
                          {isActionableRequest(r, isManager) ? (
                            <div style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                              <button onClick={() => setApproving(r)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 10.5, color: '#2FB67C', cursor: 'pointer' }}>Approve</button>
                              <button onClick={() => setRejecting(r)} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 10.5, color: '#E4373D', cursor: 'pointer' }}>Reject</button>
                            </div>
                          ) : dash}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {approvalsTotalPages > 1 && (
                <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between', flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
                    Showing {approvalsPage * APPROVALS_PAGE_SIZE + 1}–{Math.min((approvalsPage + 1) * APPROVALS_PAGE_SIZE, sortedPending.length)} of {sortedPending.length}
                  </span>
                  <div style={{ display: 'flex', gap: 4 }}>
                    <button
                      disabled={approvalsPage === 0}
                      onClick={() => setApprovalsPage((p) => p - 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: approvalsPage === 0 ? 'not-allowed' : 'pointer', opacity: approvalsPage === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronLeft size={13} />
                    </button>
                    {Array.from({ length: Math.min(approvalsTotalPages, 7) }, (_, i) => {
                      const p = approvalsTotalPages <= 7 ? i : approvalsPage <= 3 ? i : approvalsPage >= approvalsTotalPages - 4 ? approvalsTotalPages - 7 + i : approvalsPage - 3 + i;
                      return (
                        <button
                          key={p}
                          onClick={() => setApprovalsPage(p)}
                          style={{ padding: '5px 10px', minWidth: 32, background: approvalsPage === p ? 'var(--brand)' : 'var(--raised)', border: `1px solid ${approvalsPage === p ? 'var(--brand)' : 'var(--line2)'}`, borderRadius: 5, cursor: 'pointer', color: approvalsPage === p ? '#fff' : 'var(--txt)', fontSize: 11.5, fontWeight: approvalsPage === p ? 700 : 400 }}
                        >
                          {p + 1}
                        </button>
                      );
                    })}
                    <button
                      disabled={approvalsPage === approvalsTotalPages - 1}
                      onClick={() => setApprovalsPage((p) => p + 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: approvalsPage === approvalsTotalPages - 1 ? 'not-allowed' : 'pointer', opacity: approvalsPage === approvalsTotalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronRight size={13} />
                    </button>
                  </div>
                </div>
              )}
            </div>
            );
          })()}
        </div>
      )}
      {bulkConfirm && (
        <BulkActionModal
          action={bulkConfirm}
          ids={[...selectedIds]}
          token={token}
          onClose={() => setBulkConfirm(null)}
          onDone={() => { setSelectedIds(new Set()); loadAll(); }}
        />
      )}

      {showRequest && (
        <RequestModal token={token} approvedDates={approvedDates} isSuperAdmin={isSuperAdmin} onClose={() => setShowRequest(false)} onSaved={r => setMyRequests(prev => [r, ...prev])} />
      )}
      {editing && (
        <RequestModal
          token={token}
          editing={editing}
          approvedDates={approvedDates}
          isSuperAdmin={isSuperAdmin}
          onClose={() => setEditing(null)}
          onSaved={updated => setMyRequests(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {approving && (
        <ConfirmApproveModal
          request={approving}
          token={token}
          onClose={() => setApproving(null)}
          onApproved={updated => setPending(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {rejecting && (
        <RejectModal
          request={rejecting}
          token={token}
          onClose={() => setRejecting(null)}
          onRejected={updated => setPending(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {viewing && (
        <RequestDetailsModal request={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
});

// ─── WFH / Partial Day requests — single-stage approval (no PARTIALLY_APPROVED stage, no
// bulk actions), mirroring WebClockInService's simpler flow rather than Regularization's
// two-stage one. See AttendanceRequestService for why. ──────────────────────────
function AttendanceRequestsSection({ token, canApprove }: { token: string; canApprove: boolean }) {
  const { showToast } = useToast();
  const { formatDuration } = useTimeFormat();
  const [myRequests, setMyRequests] = useState<AttendanceRequestRecord[]>([]);
  const [pending, setPending] = useState<AttendanceRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [month, setMonth] = useState(ALL_MONTHS_VALUE);
  const [acting, setActing] = useState<{ request: AttendanceRequestRecord; action: 'APPROVE' | 'REJECT' } | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // WFH's own "View Request" drawer (Keka-style) — separate from the plain `acting` confirm
  // modal Partial Day still uses. A "group" is a groupWfhRequests() batch: one decision here
  // applies to every date in that batch, since the drawer displays the whole range as one row.
  const [wfhDrawer, setWfhDrawer] = useState<{ group: AttendanceRequestRecord[]; mode: 'VIEW' | 'APPROVE' | 'REJECT' } | null>(null);
  const [wfhComment, setWfhComment] = useState('');
  const [wfhSubmitting, setWfhSubmitting] = useState(false);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [attendanceRequestApi.mine(token).then(setMyRequests)];
    if (canApprove) calls.push(attendanceRequestApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load attendance requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  const filteredMyRequests = useMemo(
    () => month === ALL_MONTHS_VALUE ? myRequests : myRequests.filter((r) => r.requestDate.slice(5, 7) === month),
    [myRequests, month],
  );

  async function handleAct() {
    if (!acting) return;
    if (acting.action === 'REJECT' && !comment.trim()) { showToast('error', 'A comment is required when rejecting'); return; }
    setSubmitting(true);
    try {
      const updated = acting.action === 'APPROVE'
        ? await attendanceRequestApi.approve(acting.request.id, token, comment.trim() || undefined)
        : await attendanceRequestApi.reject(acting.request.id, comment.trim(), token);
      setPending((prev) => prev.filter((r) => r.id !== updated.id));
      showToast('success', acting.action === 'APPROVE' ? 'Request approved' : 'Request rejected');
      setActing(null);
      setComment('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setSubmitting(false);
    }
  }

  function closeWfhDrawer() { setWfhDrawer(null); setWfhComment(''); }

  /** Approves/rejects every date in the group sequentially — there's no single backend id for a
   * multi-day WFH batch (see groupWfhRequests), so one drawer decision fans out to one API call
   * per date it covers. */
  async function handleActWfh() {
    if (!wfhDrawer || wfhDrawer.mode === 'VIEW') return;
    if (wfhDrawer.mode === 'REJECT' && !wfhComment.trim()) { showToast('error', 'A comment is required when rejecting'); return; }
    setWfhSubmitting(true);
    try {
      const ids = wfhDrawer.group.map((r) => r.id);
      for (const id of ids) {
        if (wfhDrawer.mode === 'APPROVE') await attendanceRequestApi.approve(id, token, wfhComment.trim() || undefined);
        else await attendanceRequestApi.reject(id, wfhComment.trim(), token);
      }
      setPending((prev) => prev.filter((r) => !ids.includes(r.id)));
      showToast('success', wfhDrawer.mode === 'APPROVE' ? 'Request approved' : 'Request rejected');
      closeWfhDrawer();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setWfhSubmitting(false);
    }
  }

  function typeLabel(r: AttendanceRequestRecord) {
    if (r.requestType === 'WFH') {
      const wfhModeLabel = WFH_SINGLE_DAY_MODE_OPTIONS.find((o) => o.value === r.partialDayMode)?.label;
      return wfhModeLabel && wfhModeLabel !== 'Full Day' ? `Work From Home (${wfhModeLabel})` : 'Work From Home';
    }
    const modeLabel = PARTIAL_DAY_MODE_OPTIONS.find((o) => o.value === r.partialDayMode)?.label;
    return modeLabel ? `Partial Day (${modeLabel})` : 'Partial Day';
  }

  function partialDayModeLabel(r: AttendanceRequestRecord) {
    return PARTIAL_DAY_MODE_OPTIONS.find((o) => o.value === r.partialDayMode)?.label ?? dash;
  }

  /**
   * A multi-day WFH request has no shared id on the backend — it's submitted as one independent
   * AttendanceRequestRecord per date (see AttendanceRequestModal.doSubmit), each fully its own
   * approvable unit. This reconstructs the original submission for display only (so "2 days" can
   * be shown, and a reviewer can see a date belongs to a range) — it never merges actionability;
   * every date keeps its own Approve/Reject. A "batch" is same employee + same reason + calendar-
   * consecutive dates + createdAt within 10 minutes of each other, which is safe here because WFH
   * requests are hard-capped at 2 days (WFH_MONTHLY_LIMIT_DAYS), so no batch can ever exceed 2.
   */
  function groupWfhRequests(rows: AttendanceRequestRecord[]): AttendanceRequestRecord[][] {
    const sorted = [...rows].sort((a, b) => {
      if (a.employeeUserId !== b.employeeUserId) return a.employeeUserId < b.employeeUserId ? -1 : 1;
      return a.requestDate < b.requestDate ? -1 : a.requestDate > b.requestDate ? 1 : 0;
    });
    const groups: AttendanceRequestRecord[][] = [];
    for (const r of sorted) {
      const prevGroup = groups[groups.length - 1];
      const prev = prevGroup?.[prevGroup.length - 1];
      const sameBatch = !!prev
        && prev.employeeUserId === r.employeeUserId
        && prev.reason === r.reason
        && isoDaysAfter(prev.requestDate, 1) === r.requestDate
        && Math.abs(new Date(prev.createdAt).getTime() - new Date(r.createdAt).getTime()) <= 10 * 60 * 1000;
      if (sameBatch && prevGroup) prevGroup.push(r);
      else groups.push([r]);
    }
    return groups;
  }

  /** A group is APPROVED/REJECTED only once every date in it agrees — any date still PENDING
   * means the whole visual row still needs action, matching how Next Approver is shown below. */
  function wfhGroupStatus(group: AttendanceRequestRecord[]): AttendanceRequestStatus {
    if (group.some((r) => r.status === 'PENDING')) return 'PENDING';
    if (group.every((r) => r.status === 'APPROVED')) return 'APPROVED';
    return 'REJECTED';
  }

  function wfhGroupLastAction(group: AttendanceRequestRecord[]): AttendanceRequestRecord | null {
    const decided = group.filter((r) => r.reviewedByName && r.reviewedAt);
    if (!decided.length) return null;
    return decided.reduce((a, b) => (a.reviewedAt! > b.reviewedAt! ? a : b));
  }

  /** "25 Jun – 26 Jun • 2 Days" for a batch, "28 Aug • 1 Day" for a single date. */
  function formatWfhRange(group: AttendanceRequestRecord[]): string {
    const days = group.length;
    const span = days > 1 ? `${formatShortDay(group[0].requestDate)} – ${formatShortDay(group[days - 1].requestDate)}` : formatShortDay(group[0].requestDate);
    return `${span} • ${days} Day${days > 1 ? 's' : ''}`;
  }

  function renderWfhTable(rows: AttendanceRequestRecord[], showActions: boolean) {
    const groups = groupWfhRequests(rows);
    return (
      <div style={panelStyle}>
        {groups.length === 0 ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                {/* Employee only shown for Pending Approvals (showActions) — see renderPartialDayTable. */}
                <tr>{[...(showActions ? ['Employee'] : []), 'Date', 'Request Type', 'Requested On', 'Reason', 'Status', 'Last Action By', 'Next Approver', 'Actions'].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {groups.map((group) => {
                  const first = group[0];
                  const status = wfhGroupStatus(group);
                  const lastAction = wfhGroupLastAction(group);
                  // Only worth showing if there's still someone left to act, and it isn't just
                  // repeating the name already shown in Last Action By (a batch can be PENDING
                  // overall while some of its dates are already decided by that same approver).
                  const nextApprover = status === 'PENDING' && first.assignedApproverName !== lastAction?.reviewedByName
                    ? (first.assignedApproverName ?? dash)
                    : dash;
                  return (
                    <tr key={first.id}>
                      {showActions && <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{first.employeeName}</td>}
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Home size={13} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                          <span>{formatWfhRange(group)}</span>
                        </div>
                      </td>
                      <td style={tdStyle}>Work From Home</td>
                      <td style={tdStyle}>
                        <div>{formatDay(first.createdAt.slice(0, 10))}</div>
                        <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>by {first.employeeName}</div>
                      </td>
                      <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={first.reason} /></td>
                      <td style={tdStyle}><RegularizationStatusPill status={status} /></td>
                      <td style={tdStyle}>
                        {lastAction ? (
                          <>
                            {lastAction.reviewedByName}
                            <div style={{ fontSize: 10, color: 'var(--txt-dim)' }}>on {formatShortDay(lastAction.reviewedAt!.slice(0, 10))}</div>
                          </>
                        ) : dash}
                      </td>
                      <td style={tdStyle}>{nextApprover}</td>
                      <td style={tdStyle}>
                        <WfhActionMenu
                          group={group}
                          canApprove={showActions && canApprove}
                          onView={() => setWfhDrawer({ group, mode: 'VIEW' })}
                          onApprove={() => setWfhDrawer({ group, mode: 'APPROVE' })}
                          onReject={() => setWfhDrawer({ group, mode: 'REJECT' })}
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  function renderPartialDayTable(rows: AttendanceRequestRecord[], showActions: boolean) {
    return (
      <div style={panelStyle}>
        {rows.length === 0 ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                {/* Employee only shown for Pending Approvals (showActions) — in "My Requests"
                    every row is the viewer's own, so the name would be redundant. Mirrors
                    RegularizationSection's own Employee column, shown only in its reviewer-facing
                    Pending Approvals table for the same reason. */}
                <tr>{[...(showActions ? ['Employee'] : []), 'Date', 'Mode', 'Hours', 'Reason', 'Approver', 'Status', ...(showActions ? ['Actions'] : [])].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    {showActions && <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</td>}
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.requestDate)}</td>
                    <td style={tdStyle}>{partialDayModeLabel(r)}</td>
                    {/* partialDayHours is a decimal (e.g. 3.33 for 3h 20m) — round-trip through
                        minutes for a precise "3h 20m" instead of that raw fraction. */}
                    <td style={tdStyle}>{r.partialDayHours != null ? formatDuration(Math.round(r.partialDayHours * 60)) ?? dash : dash}</td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                    <td style={tdStyle}>{r.assignedApproverName ?? dash}</td>
                    <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                    {showActions && (
                      <td style={tdStyle}>
                        {r.status === 'PENDING' ? (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button onClick={() => setActing({ request: r, action: 'APPROVE' })} style={{ background: 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 5, padding: '4px 9px', fontSize: 10.5, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}>Approve</button>
                            <button onClick={() => setActing({ request: r, action: 'REJECT' })} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 10.5, color: '#E4373D', cursor: 'pointer', fontWeight: 600 }}>Reject</button>
                          </div>
                        ) : dash}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  const wfhPending = useMemo(() => pending.filter((r) => r.requestType === 'WFH'), [pending]);
  const partialDayPending = useMemo(() => pending.filter((r) => r.requestType === 'PARTIAL_DAY'), [pending]);
  const wfhMyRequests = useMemo(() => filteredMyRequests.filter((r) => r.requestType === 'WFH'), [filteredMyRequests]);
  const partialDayMyRequests = useMemo(() => filteredMyRequests.filter((r) => r.requestType === 'PARTIAL_DAY'), [filteredMyRequests]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', flexWrap: 'wrap', gap: 10 }}>
        <MonthFilter month={month} onChange={setMonth} />
        <button
          onClick={() => setShowRequest(true)}
          style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 13px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer' }}
        >
          <CalendarPlus size={12} /> New Request
        </button>
      </div>

      {/* Work From Home — its own dedicated table, separate from Partial Day. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {canApprove && (
          <div>
            <SectionHeading title="Pending Approvals — Work From Home" />
            {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderWfhTable(wfhPending, true)}
          </div>
        )}
        <div>
          <SectionHeading title="My Work From Home Requests" />
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderWfhTable(wfhMyRequests, false)}
        </div>
      </div>

      {/* Partial Day — its own dedicated table, separate from WFH. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {canApprove && (
          <div>
            <SectionHeading title="Pending Approvals — Partial Day" />
            {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderPartialDayTable(partialDayPending, true)}
          </div>
        )}
        <div>
          <SectionHeading title="My Partial Day Requests" />
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderPartialDayTable(partialDayMyRequests, false)}
        </div>
      </div>

      {showRequest && (
        <AttendanceRequestModal
          token={token}
          onClose={() => setShowRequest(false)}
          onSaved={(r) => setMyRequests((prev) => [r, ...prev])}
        />
      )}
      {acting && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 420 }}>
            <ModalHeader title={`${acting.action === 'APPROVE' ? 'Approve' : 'Reject'} — ${acting.request.employeeName}`} onClose={() => setActing(null)} />
            <div style={{ padding: 24 }}>
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
                {typeLabel(acting.request)} · {formatDay(acting.request.requestDate)}
              </div>
              <Field label={acting.action === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}>
                <textarea style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
                <button onClick={() => setActing(null)} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
                <button
                  onClick={handleAct}
                  disabled={submitting}
                  style={{ background: acting.action === 'APPROVE' ? '#2FB67C' : '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Submitting…' : acting.action === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {wfhDrawer && (
        <WfhDetailDrawer
          group={wfhDrawer.group}
          mode={wfhDrawer.mode}
          comment={wfhComment}
          setComment={setWfhComment}
          submitting={wfhSubmitting}
          onClose={closeWfhDrawer}
          onConfirmAction={handleActWfh}
        />
      )}
    </div>
  );
}

/** Next Approver column: who still needs to act — blank once the request is already decided
 * (there's no "next" step left), matching the Keka reference this was modeled after, where that
 * column is empty for a resolved row. Separate from "Last Action By" (OvertimeLastActionCell)
 * below, which is the complementary half Keka splits into its own column instead of collapsing
 * both into one cell. */
function OvertimeNextApproverCell({ r }: { r: OvertimeRequestRecord }) {
  if (r.status !== 'PENDING') return <>{dash}</>;
  return <>{r.assignedApproverName ?? dash}</>;
}

/** Last Action By: who actually approved/rejected, plus the CAPACITY they acted in — HR Admin or
 * Super Admin can decide a manager-stage request too (see OvertimeRequestService's approver
 * override), so "who approved" alone doesn't say whether it was the employee's actual manager or
 * an HR/SA override step in; reviewedByRole (backend-resolved at response time) makes that
 * explicit, e.g. "Rohit Shivramwar (Manager)". Blank while still pending — nobody has acted yet. */
function OvertimeLastActionCell({ r }: { r: OvertimeRequestRecord }) {
  if (!r.reviewedByName) return <>{dash}</>;
  const roleLabel = r.reviewedByRole ? toShellRole(r.reviewedByRole) : null;
  return (
    <>
      {r.reviewedByName}
      {roleLabel && <div style={{ fontSize: 10, color: 'var(--txt-dim)', marginTop: 1 }}>{roleLabel}</div>}
    </>
  );
}

const dropdownMenuItemStyle: React.CSSProperties = {
  display: 'block', width: '100%', textAlign: 'left', padding: '9px 14px', fontSize: 12,
  background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt)',
};

/** Row-level "•••" menu — replaces the old inline Approve/Reject buttons. Every row gets "View
 * Request" (opens the read-only detail drawer); a pending row also gets Approve/Reject shortcuts
 * when the viewer is an approver, opening the same drawer pre-armed for that action. */
function OvertimeActionMenu({ request, canApprove, onView, onApprove, onReject }: {
  request: OvertimeRequestRecord; canApprove: boolean;
  onView: () => void; onApprove: () => void; onReject: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  return (
    <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label="Actions"
        style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
      >
        <MoreVertical size={14} />
      </button>
      {open && (
        <div style={{ position: 'absolute', top: '100%', right: 0, marginTop: 4, zIndex: 40, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 150, overflow: 'hidden' }}>
          <button
            onClick={() => { setOpen(false); onView(); }}
            style={dropdownMenuItemStyle}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            View Request
          </button>
          {canApprove && request.status === 'PENDING' && (
            <>
              <button
                onClick={() => { setOpen(false); onApprove(); }}
                style={{ ...dropdownMenuItemStyle, color: '#2FB67C' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
              >
                Approve
              </button>
              <button
                onClick={() => { setOpen(false); onReject(); }}
                style={{ ...dropdownMenuItemStyle, color: '#E4373D' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
              >
                Reject
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** Same "•••" row menu as OvertimeActionMenu, but keyed to a whole WFH batch (group) instead of
 * a single record — Approve/Reject only offered while any date in the group is still PENDING. */
function WfhActionMenu({ group, canApprove, onView, onApprove, onReject }: {
  group: AttendanceRequestRecord[]; canApprove: boolean;
  onView: () => void; onApprove: () => void; onReject: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const pending = group.some((r) => r.status === 'PENDING');

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  return (
    <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label="Actions"
        style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
      >
        <MoreVertical size={14} />
      </button>
      {open && (
        <div style={{ position: 'absolute', top: '100%', right: 0, marginTop: 4, zIndex: 40, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 150, overflow: 'hidden' }}>
          <button
            onClick={() => { setOpen(false); onView(); }}
            style={dropdownMenuItemStyle}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            View Request
          </button>
          {canApprove && pending && (
            <>
              <button
                onClick={() => { setOpen(false); onApprove(); }}
                style={{ ...dropdownMenuItemStyle, color: '#2FB67C' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
              >
                Approve
              </button>
              <button
                onClick={() => { setOpen(false); onReject(); }}
                style={{ ...dropdownMenuItemStyle, color: '#E4373D' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
              >
                Reject
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** Day-number-over-weekday chip used in the WFH detail drawer's date range header (Keka's "25 THU"
 * boxes) — zone-less, same parsing convention as formatDay/formatShortDay above. */
function DateBox({ isoDate }: { isoDate: string }) {
  const [y, m, d] = isoDate.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  return (
    <div style={{ textAlign: 'center', minWidth: 40 }}>
      <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--txt)', lineHeight: 1.15 }}>
        {date.toLocaleDateString(undefined, { day: 'numeric' })}
      </div>
      <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--txt-dim)', letterSpacing: '.05em', textTransform: 'uppercase' }}>
        {date.toLocaleDateString(undefined, { weekday: 'short' })}
      </div>
    </div>
  );
}

/**
 * Right-side slide-out detail drawer for a WFH batch, modeled on the Keka reference: requestor
 * summary, the date-range header ("2 Days of Work From Home Request" + per-day half-day mode),
 * who it was routed to, the free-text note, and the approval/rejection event. As in
 * OvertimeDetailDrawer, there's no persisted multi-comment thread in this data model (just one
 * reviewComment per record, not a comments table) — so the comment box only appears while it's
 * wired to a real Approve/Reject action, never as a decorative field that would submit nothing.
 */
function WfhDetailDrawer({ group, mode, comment, setComment, submitting, onClose, onConfirmAction }: {
  group: AttendanceRequestRecord[];
  mode: 'VIEW' | 'APPROVE' | 'REJECT';
  comment: string;
  setComment: (v: string) => void;
  submitting: boolean;
  onClose: () => void;
  onConfirmAction: () => void;
}) {
  const first = group[0];
  const last = group[group.length - 1];
  const status: AttendanceRequestStatus = group.some((r) => r.status === 'PENDING')
    ? 'PENDING'
    : group.every((r) => r.status === 'APPROVED') ? 'APPROVED' : 'REJECTED';
  const decided = group.filter((r) => r.reviewedByName && r.reviewedAt);
  const lastAction = decided.length ? decided.reduce((a, b) => (a.reviewedAt! > b.reviewedAt! ? a : b)) : null;
  const initials = first.employeeName.split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');

  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', zIndex: 600 }} />
      <div style={{ position: 'fixed', top: 0, right: 0, bottom: 0, width: 'min(440px, 100vw)', background: 'var(--panel)', borderLeft: '1px solid var(--line)', boxShadow: '-12px 0 32px rgba(0,0,0,.4)', zIndex: 601, display: 'flex', flexDirection: 'column', overflowY: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)', flexShrink: 0 }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>Work From Home Request Details</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--raised2)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12.5, fontWeight: 700, color: 'var(--txt-mut)', flexShrink: 0 }}>
              {initials || <User size={16} />}
            </div>
            <div>
              <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{first.employeeName}</div>
              <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Requested on {formatDay(first.createdAt.slice(0, 10))}</div>
            </div>
          </div>

          <div style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '12px 14px', display: 'flex', alignItems: 'center', gap: 14 }}>
            <DateBox isoDate={first.requestDate} />
            {group.length > 1 && (<><span style={{ color: 'var(--txt-dim)' }}>–</span><DateBox isoDate={last.requestDate} /></>)}
            <div>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{group.length} Day{group.length > 1 ? 's' : ''} of Work From Home Request</div>
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>
                {group.map((r) => `${formatShortDay(r.requestDate)} (${wfhDayModeLabel(r.partialDayMode)})`).join(' – ')}
              </div>
            </div>
          </div>

          {first.assignedApproverName && (
            <div>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 6 }}>Notified To</div>
              <div style={{ fontSize: 12.5, color: 'var(--txt)' }}>{first.assignedApproverName}</div>
              {first.notifyUserName && first.notifyUserName !== first.assignedApproverName && <div style={{ fontSize: 12.5, color: 'var(--txt)', marginTop: 2 }}>{first.notifyUserName}</div>}
            </div>
          )}

          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 6 }}>Note</div>
            <div style={{ display: 'flex', gap: 8 }}>
              <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--raised2)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', flexShrink: 0 }}>
                {initials || <User size={13} />}
              </div>
              <div>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>
                  {first.employeeName} <span style={{ fontWeight: 400, color: 'var(--txt-dim)', fontSize: 10.5 }}>{formatDay(first.createdAt.slice(0, 10))}</span>
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 2 }}>{first.reason}</div>
              </div>
            </div>
          </div>

          {lastAction && (
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, background: status === 'REJECTED' ? 'rgba(228,55,61,.08)' : 'rgba(47,182,124,.08)', border: `1px solid ${status === 'REJECTED' ? 'rgba(228,55,61,.25)' : 'rgba(47,182,124,.25)'}`, borderRadius: 8, padding: '10px 12px' }}>
              {status === 'REJECTED'
                ? <XCircle size={15} style={{ color: '#E4373D', flexShrink: 0, marginTop: 1 }} />
                : <CheckCircle2 size={15} style={{ color: '#2FB67C', flexShrink: 0, marginTop: 1 }} />}
              <div>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>
                  {status === 'REJECTED' ? 'Rejected' : 'Approved'} by {lastAction.reviewedByName}
                  {lastAction.reviewedAt && <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}> · {formatDay(lastAction.reviewedAt.slice(0, 10))}</span>}
                </div>
                {lastAction.reviewComment && <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>{lastAction.reviewComment}</div>}
              </div>
            </div>
          )}

          {mode !== 'VIEW' && (
            <div>
              <Field label={mode === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}>
                <textarea style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 12 }}>
                <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
                <button
                  onClick={onConfirmAction}
                  disabled={submitting}
                  style={{ background: mode === 'APPROVE' ? '#2FB67C' : '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Submitting…' : mode === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/**
 * Right-side slide-out detail drawer (replaces the old center modal), modeled on the Keka
 * reference: employee summary, a "View breakdown by day" section, applied-vs-approved hours,
 * the approval/rejection event, and — only in APPROVE/REJECT mode — the same comment field the
 * old modal used to submit that decision. There is no persisted multi-comment thread in this
 * data model (OvertimeRequest has a single reviewComment, not a comments table), so unlike the
 * Keka reference the comment box is shown only while it's wired to a real action — never as a
 * decorative always-present field that would silently do nothing when submitted.
 * "Breakdown by day" is a single row today because a request always covers exactly one workDate
 * (there's no multi-day grouped-request concept in this schema); the section still exists so the
 * UI already matches the reference shape if that ever changes.
 */
function OvertimeDetailDrawer({ request, mode, comment, setComment, submitting, onClose, onConfirmAction }: {
  request: OvertimeRequestRecord;
  mode: 'VIEW' | 'APPROVE' | 'REJECT';
  comment: string;
  setComment: (v: string) => void;
  submitting: boolean;
  onClose: () => void;
  onConfirmAction: () => void;
}) {
  const { formatDuration } = useTimeFormat();
  const [breakdownOpen, setBreakdownOpen] = useState(true);
  const approvedMinutes = request.status === 'APPROVED' ? request.requestedMinutes : null;
  const initials = request.employeeName.split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');

  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', zIndex: 600 }} />
      <div style={{ position: 'fixed', top: 0, right: 0, bottom: 0, width: 'min(440px, 100vw)', background: 'var(--panel)', borderLeft: '1px solid var(--line)', boxShadow: '-12px 0 32px rgba(0,0,0,.4)', zIndex: 601, display: 'flex', flexDirection: 'column', overflowY: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)', flexShrink: 0 }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>Overtime Request Details</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--raised2)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12.5, fontWeight: 700, color: 'var(--txt-mut)', flexShrink: 0 }}>
              {initials || <User size={16} />}
            </div>
            <div>
              <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{request.employeeName}</div>
              <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Requested on {formatDay(request.createdAt.slice(0, 10))}</div>
            </div>
          </div>

          <div>
            <button
              onClick={() => setBreakdownOpen((o) => !o)}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--brand)', fontSize: 12, fontWeight: 600, padding: 0 }}
            >
              View breakdown by day {breakdownOpen ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            </button>
            {breakdownOpen && (
              <div style={{ marginTop: 10, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '10px 14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 12, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(request.workDate)}</span>
                <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{formatDuration(request.requestedMinutes) ?? dash}</span>
              </div>
            )}
          </div>

          <div style={{ display: 'flex', gap: 28 }}>
            <div>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 4 }}>Total Applied Hours</div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{formatDuration(request.requestedMinutes) ?? dash}</div>
            </div>
            <div>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 4 }}>Total Approved Hours</div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{approvedMinutes != null ? formatDuration(approvedMinutes) : dash}</div>
            </div>
          </div>

          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 6 }}>Reason</div>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{request.reason}</div>
          </div>

          {request.reviewedByName && (
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, background: request.status === 'REJECTED' ? 'rgba(228,55,61,.08)' : 'rgba(47,182,124,.08)', border: `1px solid ${request.status === 'REJECTED' ? 'rgba(228,55,61,.25)' : 'rgba(47,182,124,.25)'}`, borderRadius: 8, padding: '10px 12px' }}>
              {request.status === 'REJECTED'
                ? <XCircle size={15} style={{ color: '#E4373D', flexShrink: 0, marginTop: 1 }} />
                : <CheckCircle2 size={15} style={{ color: '#2FB67C', flexShrink: 0, marginTop: 1 }} />}
              <div>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>
                  {request.status === 'REJECTED' ? 'Rejected' : 'Approved'} by {request.reviewedByName}
                  {request.reviewedAt && <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}> · {formatDay(request.reviewedAt.slice(0, 10))}</span>}
                </div>
                {request.reviewComment && <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>{request.reviewComment}</div>}
              </div>
            </div>
          )}

          {mode !== 'VIEW' && (
            <div>
              <Field label={mode === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}>
                <textarea style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 12 }}>
                <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
                <button
                  onClick={onConfirmAction}
                  disabled={submitting}
                  style={{ background: mode === 'APPROVE' ? '#2FB67C' : '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 12.5, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Submitting…' : mode === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

// ─── Overtime requests — same single-stage shape as AttendanceRequestsSection. ──
function OvertimeRequestsSection({ token, canApprove }: { token: string; canApprove: boolean }) {
  const { showToast } = useToast();
  const { formatDuration } = useTimeFormat();
  const [myRequests, setMyRequests] = useState<OvertimeRequestRecord[]>([]);
  const [pending, setPending] = useState<OvertimeRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [month, setMonth] = useState(ALL_MONTHS_VALUE);
  const [drawer, setDrawer] = useState<{ request: OvertimeRequestRecord; mode: 'VIEW' | 'APPROVE' | 'REJECT' } | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [overtimeRequestApi.mine(token).then(setMyRequests)];
    if (canApprove) calls.push(overtimeRequestApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load overtime requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  const filteredMyRequests = useMemo(
    () => month === ALL_MONTHS_VALUE ? myRequests : myRequests.filter((r) => r.workDate.slice(5, 7) === month),
    [myRequests, month],
  );

  function closeDrawer() {
    setDrawer(null);
    setComment('');
  }

  async function handleAct() {
    if (!drawer || drawer.mode === 'VIEW') return;
    if (drawer.mode === 'REJECT' && !comment.trim()) { showToast('error', 'A comment is required when rejecting'); return; }
    setSubmitting(true);
    try {
      const updated = drawer.mode === 'APPROVE'
        ? await overtimeRequestApi.approve(drawer.request.id, token, comment.trim() || undefined)
        : await overtimeRequestApi.reject(drawer.request.id, comment.trim(), token);
      setPending((prev) => prev.filter((r) => r.id !== updated.id));
      showToast('success', drawer.mode === 'APPROVE' ? 'Request approved' : 'Request rejected');
      closeDrawer();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setSubmitting(false);
    }
  }

  function renderTable(rows: OvertimeRequestRecord[], showApprovalActions: boolean) {
    return (
      <div style={panelStyle}>
        {rows.length === 0 ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                {/* Employee only shown for Pending Approvals (showApprovalActions) — see the same
                    reasoning in AttendanceRequestsSection.renderTable. The "..." Actions column is
                    always present (every row can at least be viewed), unlike the old Approve/
                    Reject buttons that only appeared in the approvals table. */}
                <tr>{[...(showApprovalActions ? ['Employee'] : []), 'Date', 'Overtime Hours', 'Reason', 'Status', 'Last Action By', 'Next Approver', 'Actions'].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    {showApprovalActions && <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</td>}
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.workDate)}</td>
                    <td style={tdStyle}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        {formatDuration(r.requestedMinutes) ?? dash}
                        {/* Opens the same detail drawer's Applied/Approved hours breakdown —
                            matches the Keka reference's info icon next to the hours value,
                            reusing the drawer instead of a second tooltip implementation. */}
                        <button
                          onClick={() => setDrawer({ request: r, mode: 'VIEW' })}
                          aria-label="Hours detail"
                          style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}
                        >
                          <Info size={12} />
                        </button>
                      </span>
                    </td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                    <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                    <td style={tdStyle}><OvertimeLastActionCell r={r} /></td>
                    <td style={tdStyle}><OvertimeNextApproverCell r={r} /></td>
                    <td style={tdStyle}>
                      <OvertimeActionMenu
                        request={r}
                        canApprove={showApprovalActions && canApprove}
                        onView={() => setDrawer({ request: r, mode: 'VIEW' })}
                        onApprove={() => setDrawer({ request: r, mode: 'APPROVE' })}
                        onReject={() => setDrawer({ request: r, mode: 'REJECT' })}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {canApprove && (
        <div>
          <SectionHeading title="Pending Approvals — Overtime" />
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderTable(pending, true)}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <SectionHeading title="My Requests" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <MonthFilter month={month} onChange={setMonth} />
            <button
              onClick={() => setShowRequest(true)}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 13px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer' }}
            >
              <CalendarPlus size={12} /> New Request
            </button>
          </div>
        </div>
        {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12 }}>Loading…</div> : renderTable(filteredMyRequests, false)}
      </div>
      {showRequest && (
        <OvertimeRequestModal
          token={token}
          existingRequests={myRequests}
          onClose={() => setShowRequest(false)}
          onSaved={(r) => setMyRequests((prev) => [r, ...prev])}
        />
      )}
      {drawer && (
        <OvertimeDetailDrawer
          request={drawer.request}
          mode={drawer.mode}
          comment={comment}
          setComment={setComment}
          submitting={submitting}
          onClose={closeDrawer}
          onConfirmAction={handleAct}
        />
      )}
    </div>
  );
}

type OvertimeHoursMode = 'FIXED' | 'CUSTOM';

/**
 * Plain "H:mm" or "HH:mm" duration, no AM/PM — e.g. "2:15" -> 135 minutes. This is the only
 * "valid hours" check this form applies (well-formed, positive, <= 24h); per policy an employee
 * may request OT regardless of what attendance logs show as already "earned," so this never
 * checks that figure.
 */
function parseDurationHM(text: string): number | null {
  const m = text.trim().match(/^([0-9]{1,3}):([0-5][0-9])$/);
  if (!m) return null;
  const minutes = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
  return minutes > 0 && minutes <= 24 * 60 ? minutes : null;
}

function formatDurationHM(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

// ─── Overtime submit modal — Keka-style multi-day range. Overtime detected from attendance logs
// per day is only a starting suggestion (Fixed hours: one value applied to every day; Custom
// hours: an editable hh:mm per day) — never a gate. Submission is blocked only on malformed
// entered hours, never on the detected/existing balance being zero.
function OvertimeRequestModal({ onClose, onSaved, token, existingRequests }: {
  onClose: () => void;
  onSaved: (r: OvertimeRequestRecord) => void;
  token: string;
  existingRequests: OvertimeRequestRecord[];
}) {
  const { showToast } = useToast();
  const { formatDuration } = useTimeFormat();
  const [fromDate, setFromDate] = useState(todayIsoDate());
  const [toDate, setToDate] = useState(todayIsoDate());
  const [mode, setMode] = useState<OvertimeHoursMode>('FIXED');
  const [fixedHoursText, setFixedHoursText] = useState('');
  const [perDayHoursText, setPerDayHoursText] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [notifyEntry, setNotifyEntry] = useState<DirectoryEntry | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Past dates are allowed (an employee claiming overtime worked yesterday shouldn't need same-day
  // submission), but only back to when they actually joined — mirrors RegularizationRequestModal's
  // joiningDate fetch and OvertimeRequestService.assertNotBeforeJoiningDate, the actual boundary.
  const [joiningDate, setJoiningDate] = useState<string | null>(null);
  useEffect(() => {
    profileApi.get(token).then((p) => setJoiningDate(p.joiningDate)).catch(() => setJoiningDate(null));
  }, [token]);

  const [config, setConfig] = useState<AttendanceConfig | null>(null);
  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  const dateList = useMemo(
    () => (toDate >= fromDate ? expandDateRange(fromDate, toDate) : []),
    [fromDate, toDate],
  );

  // Dates already covered by a PENDING or APPROVED request of this employee's own — queried
  // client-side from the list OvertimeRequestsSection already loaded (no extra fetch needed).
  // Only a REJECTED request never blocks a new one for the same date.
  const pendingDatesByWorkDate = useMemo(() => {
    const map = new Map<string, OvertimeRequestRecord>();
    existingRequests.forEach((r) => { if (r.status === 'PENDING' || r.status === 'APPROVED') map.set(r.workDate, r); });
    return map;
  }, [existingRequests]);
  const conflictingDates = useMemo(
    () => dateList.filter((d) => pendingDatesByWorkDate.has(d)),
    [dateList, pendingDatesByWorkDate],
  );
  const hasPendingConflict = conflictingDates.length > 0;

  // Fetched purely to seed a starting suggestion and for the "fetched from attendance logs"
  // readout — never used to block submission (a day with no attendance record is fine; it just
  // suggests 0 and the employee can still enter hours).
  const [recordsByDate, setRecordsByDate] = useState<Record<string, AttendanceRecord | null>>({});
  const [loadingDays, setLoadingDays] = useState(false);
  useEffect(() => {
    if (dateList.length === 0) { setRecordsByDate({}); return; }
    let cancelled = false;
    setLoadingDays(true);
    Promise.all(dateList.map((d) =>
      attendanceApi.punchForDate(d, token).then((r) => [d, r] as const).catch(() => [d, null] as const),
    ))
      .then((results) => { if (!cancelled) setRecordsByDate(Object.fromEntries(results)); })
      .finally(() => { if (!cancelled) setLoadingDays(false); });
    return () => { cancelled = true; };
  }, [dateList, token]);

  const fullDayTargetMinutes = fullDayTargetMinutesFor(config);
  const detectedMinutesFor = useCallback((date: string) => {
    const worked = recordsByDate[date]?.workedMinutes;
    return fullDayTargetMinutes != null && worked != null ? Math.max(0, worked - fullDayTargetMinutes) : 0;
  }, [recordsByDate, fullDayTargetMinutes]);

  const totalDetectedMinutes = useMemo(
    () => dateList.reduce((sum, d) => sum + detectedMinutesFor(d), 0),
    [dateList, detectedMinutesFor],
  );

  // Custom-mode fields default to that day's detected overtime the first time it's seen, but
  // never overwrite a value already typed for that date (including one the employee cleared).
  useEffect(() => {
    setPerDayHoursText((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const d of dateList) {
        if (next[d] === undefined) {
          const detected = detectedMinutesFor(d);
          next[d] = detected > 0 ? formatDurationHM(detected) : '';
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [dateList, detectedMinutesFor]);

  function entryFor(date: string): string {
    return mode === 'FIXED' ? fixedHoursText : (perDayHoursText[date] ?? '');
  }

  async function handleSubmit() {
    if (toDate < fromDate) { setError('To date must be on or after From date'); return; }
    // Past dates are allowed — only the joining-date boundary blocks a request, not "today".
    if (joiningDate && fromDate < joiningDate) {
      setError('Overtime requests cannot be made prior to your joining date.');
      return;
    }
    if (hasPendingConflict) {
      setError('Previous Overtime request is in pending / approved for the selected dates.');
      return;
    }
    if (!reason.trim()) { setError('Reason is required'); return; }

    const entries: { date: string; minutes: number }[] = [];
    for (const d of dateList) {
      const text = entryFor(d);
      if (!text.trim()) continue;
      const minutes = parseDurationHM(text);
      if (minutes == null) { setError(`Enter a valid hh:mm value for ${formatDay(d)}`); return; }
      entries.push({ date: d, minutes });
    }
    if (entries.length === 0) { setError('Enter overtime hours for at least one day'); return; }

    setSubmitting(true);
    setError(null);
    try {
      const created = await Promise.all(entries.map(({ date, minutes }) => {
        // requestedStart/requestedEnd still back the stored request (the schema requires both
        // and checks requestedEnd > requestedStart) — a plain midnight-anchored span sized to
        // the entered minutes, since these are now claimed hours, not real clock times.
        const requestedStart = `${date}T00:00:00`;
        const endDate = new Date(requestedStart);
        endDate.setMinutes(endDate.getMinutes() + minutes);
        const requestedEnd = `${isoOf(endDate.getFullYear(), endDate.getMonth(), endDate.getDate())}T`
          + `${pad2(endDate.getHours())}:${pad2(endDate.getMinutes())}:00`;
        return overtimeRequestApi.submit({
          workDate: date, requestedStart, requestedEnd, reason: reason.trim(),
          // No manual approver selection — the backend always routes to the employee's current
          // reporting manager (EmployeeManagerHistory) when managerUserId is omitted.
          notifyUserId: notifyEntry?.userId || undefined,
        }, token);
      }));
      created.forEach(onSaved);
      showToast('success', created.length > 1
        ? `${created.length} overtime requests submitted for approval`
        : 'Overtime request submitted for approval');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to submit overtime request';
      setError(msg);
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 460 }}>
        <ModalHeader title="Request Overtime (OT)" onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <OhNoError message={error} onDismiss={() => setError(null)} />}
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <Field label="From">
                <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={inputStyle} />
              </Field>
            </div>
            <div style={{ fontSize: 11, color: 'var(--txt-dim)', paddingBottom: 10, whiteSpace: 'nowrap' }}>
              {dateList.length > 0 ? `${dateList.length} day${dateList.length > 1 ? 's' : ''}` : '—'}
            </div>
            <div style={{ flex: 1 }}>
              <Field label="To">
                <input type="date" value={toDate} min={fromDate} onChange={(e) => setToDate(e.target.value)} style={inputStyle} />
              </Field>
            </div>
          </div>
          <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
            {loadingDays ? 'Checking attendance…' : (
              <>You have <strong style={{ color: 'var(--txt)' }}>{formatDuration(totalDetectedMinutes) ?? '0m'}</strong> of overtime for selected day{dateList.length > 1 ? 's' : ''} (fetched from attendance logs)</>
            )}
          </div>
          {hasPendingConflict && (
            <div style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 7, padding: '8px 10px', fontSize: 11.5, color: 'var(--risk)', fontWeight: 600 }}>
              Previous Overtime request is in pending / approved for the selected dates.
              {conflictingDates.length > 0 && ` (${conflictingDates.map((d) => formatDay(d)).join(', ')})`}
            </div>
          )}
          <FilterTabs
            value={mode}
            onChange={setMode}
            options={[{ value: 'FIXED', label: 'Fixed hours' }, { value: 'CUSTOM', label: 'Custom hours' }]}
          />
          {mode === 'FIXED' ? (
            <Field label="Overtime hours">
              <input value={fixedHoursText} onChange={(e) => setFixedHoursText(e.target.value)} placeholder="hh:mm" style={inputStyle} />
            </Field>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 220, overflowY: 'auto' }}>
              {dateList.length === 0 ? (
                <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Pick a valid date range above.</div>
              ) : dateList.map((d) => {
                const conflict = pendingDatesByWorkDate.get(d);
                return (
                  <div key={d} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                    <span style={{ fontSize: 12, color: conflict ? 'var(--risk)' : 'var(--txt-mut)' }}>
                      {formatDay(d)}{conflict && ` · ${conflict.status === 'APPROVED' ? 'Approved' : 'Pending'}`}
                    </span>
                    <input
                      value={perDayHoursText[d] ?? ''}
                      onChange={(e) => setPerDayHoursText((prev) => ({ ...prev, [d]: e.target.value }))}
                      placeholder="hh:mm"
                      disabled={!!conflict}
                      style={{ ...inputStyle, width: 110, opacity: conflict ? 0.5 : 1 }}
                    />
                  </div>
                );
              })}
            </div>
          )}
          <div style={{ background: 'rgba(224,169,59,.12)', border: '1px solid rgba(224,169,59,.35)', borderRadius: 7, padding: '8px 10px', fontSize: 11.5, color: 'var(--txt-mut)' }}>
            Overtime compensation requires an approved request with valid hours.
          </div>
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is overtime needed?"
            />
          </Field>
          <NotifyEmployeeField token={token} value={notifyEntry} onChange={setNotifyEntry} />
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 12.5, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={submitting || hasPendingConflict}
              style={{
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 12.5, fontWeight: 600,
                cursor: (submitting || hasPendingConflict) ? 'not-allowed' : 'pointer',
                opacity: (submitting || hasPendingConflict) ? 0.6 : 1,
              }}
            >
              {submitting ? 'Submitting…' : 'Submit for Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Roster view (Manager team / HR org-wide) ─────────────────────────────────

function DayRoster({ scope }: { scope: 'team' | 'all' }) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();

  const [date, setDate] = useState(todayIsoDate());
  const [rows, setRows] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const fetcher = scope === 'team' ? attendanceApi.team : attendanceApi.day;
    fetcher(date, token)
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((err) => {
        if (cancelled) return;
        setRows([]);
        showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [scope, date, token, showToast]);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <SectionHeading
          title={scope === 'team' ? 'Team attendance' : 'Organization attendance'}
          hint={scope === 'team'
            ? 'Your current direct reports for the selected day.'
            : 'All active employees for the selected day.'}
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 12 }}>
          Date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={dateInputStyle} />
        </label>
      </div>
      <RosterTable
        rows={rows}
        loading={loading}
        emptyMessage={scope === 'team'
          ? 'No direct reports are assigned to you yet.'
          : 'No employee records for this date.'}
      />
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

// Sub-tab inside the "Attendance Requests" main tab — Regularization and WFH & Partial Day
// used to be two of three siblings in a flat FilterTabs; they now nest one level deeper so the
// outer bar can match Keka's exact 4-tab list (Overtime Requests moved out to its own main tab).
type AttendanceRequestsSubTab = 'REGULARIZATION' | 'WFH_PARTIAL_DAY';

function AttendancePageInner() {
  // The router has no role guard, so — like Shell — the page resolves the role itself.
  const token = useAuthStore((s) => s.token)!;
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const canApprove = role === 'Manager' || role === 'HR Admin' || role === 'Super Admin';

  const subtitle = role === 'Manager'
    ? 'Review daily attendance for the selected month, and your team’s attendance for any day.'
    : role === 'HR Admin' || role === 'Super Admin'
      ? 'Review daily attendance for the selected month, and attendance across the organization.'
      : 'Review daily attendance for the selected month.';

  const myAttendanceRef = useRef<MyAttendanceHandle>(null);
  const regularizationRef = useRef<RegularizationSectionHandle>(null);
  // "View full record" (AttendanceHeroBanner, Home dashboard) links here with ?tab=calendar so
  // it lands on the Calendar tab instead of the default Attendance Log — read once on mount,
  // same as any other deep-link query param.
  const [searchParams] = useSearchParams();
  const [logsTab, setLogsTab] = useState<LogsTab>(() =>
    searchParams.get('tab') === 'calendar' ? 'CALENDAR' : 'ATTENDANCE_LOG');
  const [requestsSubTab, setRequestsSubTab] = useState<AttendanceRequestsSubTab>('REGULARIZATION');
  const pendingOpenRequest = useRef(false);

  // The header's "Request Regularization" button may be clicked while a different tab is
  // active (RegularizationSection unmounted, ref not yet attached) — switch tabs first, then
  // open the modal once the ref attaches (refs commit before this effect runs).
  useEffect(() => {
    if (logsTab === 'ATTENDANCE_REQUESTS' && requestsSubTab === 'REGULARIZATION' && pendingOpenRequest.current) {
      pendingOpenRequest.current = false;
      regularizationRef.current?.openNewRequest();
    }
  }, [logsTab, requestsSubTab]);

  return (
    <div>
      <div className="nf-attendance-header" style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap', marginBottom: 22 }}>
        {/* flex-basis is an explicit 300px, not left as content-driven "auto": the subtitle text
            length differs by role (Employee's is one short sentence, Manager/HR Admin/Super
            Admin's are longer), and an auto-width flex item claims only as much row space as its
            own text needs — so Employee's shorter subtitle left more room for the header's action
            buttons than the other roles' longer ones did, making the header wrap to a second row
            (buttons below the title) at a different viewport width per role. A fixed basis makes
            every role claim the identical footprint regardless of its own subtitle's length, so
            the header wraps at the same breakpoint for all four roles.
            Grow is 0, not 1: a growing title would expand to fill any spare header width (up to
            the maxWidth cap) *at the actions column's expense*, since actions itself has no grow
            of its own and only gets whatever width is left over — that starved the two buttons of
            the room their own internal flex-basis needed even on an ordinary 1440px desktop
            width, wrapping them onto separate lines where they used to sit side by side. Without
            grow, title only ever claims its basis (or less, via shrink, on very narrow screens),
            leaving all spare width for the buttons, matching the original layout's intent. */}
        <div style={{ flex: '0 1 300px', maxWidth: 560 }}>
          <h1 style={{
            fontFamily: 'Inter, sans-serif', fontSize: 18, fontWeight: 700,
            color: 'var(--txt)', margin: 0,
          }}>My Attendance</h1>
          {/* minHeight reserves 2 lines regardless of role: the subtitle text length varies by
              role (Employee's is one line, Manager/HR Admin/Super Admin's wrap to two), and
              without a reserved height the header block's total height — and therefore the
              vertical start of the stats row below it — shifted per role, reading as
              inconsistent alignment across roles even though it's the same shared component. */}
          <p style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--txt-mut)', marginTop: 4, minHeight: 'calc(13px * 1.5 * 2)' }}>{subtitle}</p>
        </div>
        {/* width is computed explicitly (2x the button clamp formula from index.css, + the 10px
            gap) rather than left as "auto": a flex container's own auto/shrink-to-fit width, when
            its children are flex-grow items, does not reliably compute to those children's full
            unshrunk (flex-basis) size in every browser — measured here at ~375px vs. the ~410px
            the two buttons actually want at their basis — so leaving it implicit silently starved
            both buttons and either wrapped their labels or stacked them despite the header having
            hundreds of spare pixels right next to them (justify-content: space-between spends
            that slack as the GAP between title and actions, never by growing actions itself, so
            there was never any implicit fallback that would have caught this). */}
        <div className="nf-attendance-actions" style={{ display: 'flex', gap: 10, flexWrap: 'nowrap', width: 'calc(2 * clamp(90px, 76.5px + 8.59vw, 200px) + 10px)' }}>
          <button
            className="nf-attendance-action-btn"
            onClick={() => myAttendanceRef.current?.exportMonth()}
            style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 16px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}
          >
            <Download className="nf-attendance-action-icon" size={14} />
            <span className="nf-attendance-action-label">Export selected month</span>
          </button>
          <button
            className="nf-attendance-action-btn"
            onClick={() => {
              if (logsTab === 'ATTENDANCE_REQUESTS' && requestsSubTab === 'REGULARIZATION') {
                regularizationRef.current?.openNewRequest();
              } else {
                pendingOpenRequest.current = true;
                setLogsTab('ATTENDANCE_REQUESTS');
                setRequestsSubTab('REGULARIZATION');
              }
            }}
            style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}
          >
            <CalendarPlus className="nf-attendance-action-icon" size={14} />
            <span className="nf-attendance-action-label">Request Regularization</span>
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 30 }}>
        <MyAttendance
          ref={myAttendanceRef}
          isSuperAdmin={role === 'Super Admin'}
          logsTab={logsTab}
          onLogsTabChange={setLogsTab}
          otherTabContent={
            logsTab === 'ATTENDANCE_REQUESTS' ? (
              <div>
                <div style={{ marginBottom: 16 }}>
                  <FilterTabs
                    value={requestsSubTab}
                    onChange={setRequestsSubTab}
                    options={[
                      { value: 'REGULARIZATION', label: 'Regularization' },
                      { value: 'WFH_PARTIAL_DAY', label: 'WFH & Partial Day' },
                    ]}
                  />
                </div>
                {requestsSubTab === 'REGULARIZATION' && (
                  <RegularizationSection ref={regularizationRef} token={token} canApprove={canApprove} isSuperAdmin={role === 'Super Admin'} isManager={role === 'Manager'} />
                )}
                {requestsSubTab === 'WFH_PARTIAL_DAY' && (
                  <AttendanceRequestsSection token={token} canApprove={canApprove} />
                )}
              </div>
            ) : logsTab === 'OVERTIME' ? (
              <OvertimeRequestsSection token={token} canApprove={canApprove} />
            ) : null
          }
        />

        {role === 'Manager' && <DayRoster scope="team" />}
        {(role === 'HR Admin' || role === 'Super Admin') && <DayRoster scope="all" />}
      </div>
    </div>
  );
}

export default function AttendancePage() {
  return (
    <TimeFormatProvider>
      <AttendancePageInner />
    </TimeFormatProvider>
  );
}
