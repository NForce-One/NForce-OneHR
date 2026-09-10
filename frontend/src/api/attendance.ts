import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

/**
 * The browser/device's own IANA timezone (e.g. "Australia/Adelaide") — sent with Check-In/
 * Check-Out and Web Clock-In/Out so the server computes that action's timestamp in the user's
 * actual current timezone rather than their configured Location.timezone. `Intl.DateTimeFormat`
 * is supported by every browser this app targets; if it's ever unavailable, undefined is sent
 * and the server falls back to the employee's configured Location.timezone (then the global
 * business zone) — see AttendanceService.resolveZone. Never a client-supplied TIME, only which
 * zone the server's own clock reads in.
 */
export function browserTimezone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export type AttendanceStatus = 'PRESENT' | 'LATE' | 'HALF_DAY' | 'ABSENT' | 'MISSING_CHECKOUT';
export type AttendanceSource = 'SYSTEM' | 'REGULARIZATION' | 'WEB_REMOTE';

export interface AttendanceRecord {
  id: string | null;
  employeeUserId: string;
  employeeCode: string;
  fullName: string;
  workDate: string;
  /** The day's original check-in — stays fixed across lunch-break resumes. For "when did the
   * current/latest session start," use sessionStartedAt instead. */
  checkInAt: string | null;
  checkOutAt: string | null;
  /** When the most recent session started — updates on every check-in, including a resume. */
  sessionStartedAt: string | null;
  workedMinutes: number | null;
  status: AttendanceStatus | null;
  lateByMinutes: number | null;
  source: AttendanceSource | null;
  /** The employee's configured work mode (ONSITE/REMOTE/HYBRID) at query time. */
  workMode: string | null;
  /** IANA zone id the browser reported at Check-In/Web Clock-In (e.g. "Australia/Adelaide") —
   * null for records predating this field, or where none was supplied. */
  timezone: string | null;
  /** Scheduled shift start for this row's own workDate, resolved server-side against THIS ROW's
   * own snapshotted Shift (never the employee's current one) — see
   * AttendanceInterpretationService#resolveScheduledWindow. Same zone-less wall-clock basis as
   * checkInAt/checkOutAt. Null for a legacy record predating the shiftId snapshot. Powers the
   * Attendance Log's shift-boundary markers only (see AttendanceTimeline) — never used for any
   * lateness/cutoff computation, which has its own dedicated backend resolution path. */
  shiftStartAt: string | null;
  /** Scheduled shift end for this row's own workDate — see {@link shiftStartAt}. Rolls onto the
   * next calendar day for an overnight shift. Null for a legacy record. */
  shiftEndAt: string | null;
  /** Start of this row's logical WORKDAY (never calendar midnight) — see
   * ShiftDayPolicy#workdayStartAt on the backend. The Attendance timeline's whole track spans
   * workdayStartAt..workdayEndAt, not 00:00-24:00, so a punch after midnight still positions
   * correctly relative to the shift it actually belongs to. Null for a legacy record predating
   * the shiftId snapshot, same as shiftStartAt/shiftEndAt. */
  workdayStartAt: string | null;
  /** End of this row's logical WORKDAY — see {@link workdayStartAt} / ShiftDayPolicy#workdayEndAt.
   * Null for a legacy record. */
  workdayEndAt: string | null;
}

export interface Punch {
  id: string;
  checkInAt: string;
  checkOutAt: string | null;
  /** "SYSTEM" (normal Check-In/Check-Out) or "WEB_REMOTE" (Web Check-In/Check-Out). */
  source: 'SYSTEM' | 'WEB_REMOTE';
  /** The Web Clock-In note, if one was provided — always null for a "SYSTEM" source punch. */
  note: string | null;
}

export interface TodayAttendance {
  workDate: string;
  /** Current time in the server's business timezone — the clock elapsed time is measured against. */
  serverNow: string;
  canCheckIn: boolean;
  canCheckOut: boolean;
  /** Null until the employee has punched in today. */
  record: AttendanceRecord | null;
  /** Minutes spent on breaks so far today. Null until the employee has punched in. */
  breakUsedMinutes: number | null;
}

/** One side of the Me-vs-My-Team comparison. Null averages mean presentDays === 0. */
export interface AttendanceStatBucket {
  presentDays: number;
  avgHoursPerDay: number | null;
  onTimeArrivalPercent: number | null;
  /**
   * Average expected work hours per working day — the assigned shift's duration, reduced by any
   * approved hourly/quarter-day leave (see ExpectedWorkHoursService on the backend), the same
   * calculation the Penalization Policy engine uses. Null when there were no working days in
   * range or no shift was assigned to compute it from. Never recalculated on the frontend.
   */
  expectedHoursPerDay: number | null;
}

export interface AttendanceStats {
  me: AttendanceStatBucket;
  team: AttendanceStatBucket;
  teamSize: number;
}

/**
 * Read-only shift/break config, resolved per-caller: shiftStart/shiftEnd/weeklyOffDays reflect
 * the caller's assigned Shift/WeeklyOffPolicy (ONEHR-108) when one exists, else fall back to
 * global defaults (shiftEnd null, weeklyOffDays Sat/Sun) — see AttendanceService.getConfig.
 */
export interface AttendanceConfig {
  /** Null only if the caller has no Shift assigned. */
  shiftName: string | null;
  shiftStart: string; // "HH:mm:ss"
  /** Null only if the caller has no Shift assigned — every employee is seeded with one, so this is normally always set. */
  shiftEnd: string | null;
  lateGraceMinutes: number;
  /** Sourced from the persisted, Admin-editable Attendance Rules setting — see orgApi.getAttendanceRules. */
  halfDayMaxHours: number;
  /** java.time.DayOfWeek names, e.g. ["SATURDAY", "SUNDAY"]. */
  weeklyOffDays: string[];
}

/** Always empty today — see backend AttendanceExceptionResponse's Javadoc. */
export interface AttendanceExceptionRecord {
  id: string;
  exceptionDate: string;
  exceptionType: string;
  status: string;
  minutesLate: number | null;
}

// PARTIALLY_APPROVED: the Manager stage has approved, awaiting HR/Super Admin final approval.
export type RegularizationStatus = 'PENDING' | 'PARTIALLY_APPROVED' | 'APPROVED' | 'REJECTED';

export interface ApprovalHistoryEntry {
  actionType: 'APPROVED' | 'REJECTED';
  actorName: string;
  /** Authority actually exercised for this action — not necessarily every role the actor holds. */
  actorRole: 'MANAGER' | 'HR_ADMIN' | 'SUPER_ADMIN' | null;
  comments: string | null;
  actionDate: string;
}

export interface RegularizationRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  departmentName: string | null;
  attendanceDate: string;
  requestedCheckIn: string | null;
  requestedCheckOut: string | null;
  reason: string;
  status: RegularizationStatus;
  assignedApproverId: string | null;
  assignedApproverName: string | null;
  totalMinutes: number | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  // Stage 1 (manager approval) — null until a MANAGER approves, and null on a Super Admin
  // bypass straight from PENDING to APPROVED.
  approvedByName: string | null;
  approvedAt: string | null;
  // Stage 2 (final approval) — set once the request reaches the terminal APPROVED status.
  finalApprovedByName: string | null;
  finalApprovedAt: string | null;
  createdAt: string;
  approvalHistory: ApprovalHistoryEntry[];
}

export interface BulkActionResult {
  succeededIds: string[];
  failed: { id: string; reason: string }[];
}

/** One row of the Avg. Work Hours Leaderboard (ONEHR-106). */
export interface TeamEffortEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  avgHoursPerDay: number;
  hoursWorked: number;
  expectedHours: number;
  activeDays: number;
}

export interface TeamLateArrivalEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  lateDays: number;
  activeDays: number;
  latePct: number;
}

export interface TeamDailyCount {
  date: string;
  count: number;
}

export interface TeamLeastHoursEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  avgHoursPerDay: number;
  hoursWorked: number;
}

export interface TeamHoursBucket {
  label: string;
  count: number;
  pct: number;
}

export interface TeamFrequentBreaksEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  totalBreakHours: number;
  totalBreakCount: number;
  avgBreaksPerDay: number;
}

export interface TeamDailyAverage {
  date: string;
  avgBreaks: number;
}

/** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks (ONEHR-107). */
export interface TeamNegligenceResponse {
  lateArrivals: TeamLateArrivalEntry[];
  dailyLateCounts: TeamDailyCount[];
  leastHoursWorked: TeamLeastHoursEntry[];
  hoursHistogram: TeamHoursBucket[];
  frequentBreaks: TeamFrequentBreaksEntry[];
  breaksTrend: TeamDailyAverage[];
}

/** One row of the On-Time Leaderboard — "on time" means attendance.status == PRESENT. */
export interface PunctualityLeaderboardEntry {
  employeeUserId: string;
  fullName: string;
  designationName: string | null;
  onTimeDays: number;
  expectedWorkingDays: number;
  percentage: number;
}

export interface DailyPunctuality {
  date: string;
  employeesOnTime: number;
}

/** Computed from applicable daily values only (dates that were a working day for someone). */
export interface PunctualitySummary {
  averageEmployeesOnTime: number;
  minimumEmployeesOnTime: number;
  maximumEmployeesOnTime: number;
}

export interface TeamPunctualityResponse {
  leaderboard: PunctualityLeaderboardEntry[];
  daily: DailyPunctuality[];
  summary: PunctualitySummary;
}

export type AttendancePenaltyStatus = 'PENDING_REVIEW' | 'APPLIED' | 'CANCELLED' | 'REVERSED';

/** One row of the Regularize & Cancel Penalties table. */
export interface PenaltyRow {
  id: string;
  employeeUserId: string;
  fullName: string;
  employeeCode: string;
  incidentDate: string;
  penalizedOn: string;
  status: AttendancePenaltyStatus;
  locationName: string | null;
  departmentName: string | null;
  discrepancyType: string;
  /** The matched Penalization Policy rule's configured deduction amount at evaluation time. */
  deductionDays: number | null;
  cancellable: boolean;
}

export interface PenaltyFilters {
  from: string;
  to: string;
  status?: AttendancePenaltyStatus;
  discrepancyType?: string;
  department?: string;
  location?: string;
  search?: string;
}

export interface PenaltyCancelResult {
  succeededIds: string[];
  failed: { id: string; reason: string }[];
}

export interface SubmitRegularizationPayload {
  attendanceDate: string;
  requestedCheckIn?: string;
  requestedCheckOut?: string;
  reason: string;
  managerUserId?: string;
}

export interface RegularizationBalance {
  usedCount: number;
  limitCount: number;
  remainingCount: number;
  unlimited: boolean;
}

export interface ApproverOption {
  userId: string;
  fullName: string;
  email: string;
  // Super Admin is excluded server-side — they have blanket review visibility already and
  // aren't offered as an explicit "Assign To" target.
  roleCode: 'MANAGER' | 'HR_ADMIN';
}

export interface RegularizationFilters {
  employeeUserId?: string;
  approverUserId?: string;
  departmentId?: string;
  month?: string; // 'yyyy-MM'
  status?: RegularizationStatus;
}

export const attendanceApi = {
  today: (token: string) => {
    const tz = browserTimezone();
    return fetch(`${BASE}/attendance/today${tz ? `?timezone=${encodeURIComponent(tz)}` : ''}`, { headers: authHeaders(token) })
      .then(handle<TodayAttendance>);
  },

  // The server still generates the actual timestamp itself — this only tells it which zone
  // (the browser's own) to read its clock in. See browserTimezone's own doc comment.
  checkIn: (token: string) =>
    fetch(`${BASE}/attendance/check-in`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ timezone: browserTimezone() }),
    }).then(handle<AttendanceRecord>),

  checkOut: (token: string) =>
    fetch(`${BASE}/attendance/check-out`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ timezone: browserTimezone() }),
    }).then(handle<AttendanceRecord>),

  myHistory: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/me?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  day: (date: string, token: string) =>
    fetch(`${BASE}/attendance/day?date=${date}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  team: (date: string, token: string) =>
    fetch(`${BASE}/attendance/team?date=${date}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  /** Attendance rows for the manager's direct reports across a date range — backs the My Team calendar. */
  teamMonth: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-month?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  /** Day roster for the caller's current peers — My Team: Peers view (ONEHR-73). Any employee. */
  peers: (date: string, token: string) =>
    fetch(`${BASE}/attendance/peers?date=${date}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  /** Peer attendance across a date range — backs the Peers view calendar. */
  peersMonth: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/peers-month?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceRecord[]>),

  /** Own punch for a single date, or null if the employee never punched that day. */
  punchForDate: (date: string, token: string): Promise<AttendanceRecord | null> =>
    fetch(`${BASE}/attendance/punch/${date}`, { headers: authHeaders(token) })
      .then((res) => (res.status === 204 ? null : handle<AttendanceRecord>(res))),

  /** Every check-in/check-out session for a single day, e.g. to show a lunch-break gap. */
  punches: (date: string, token: string) =>
    fetch(`${BASE}/attendance/punches/${date}`, { headers: authHeaders(token) })
      .then(handle<Punch[]>),

  /** "Me vs My Team" (peers under the employee's own current manager) for the selected range. */
  stats: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/stats?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceStats>),

  config: (token: string) =>
    fetch(`${BASE}/attendance/config`, { headers: authHeaders(token) }).then(handle<AttendanceConfig>),

  /** Always resolves to an empty array today — see AttendanceExceptionRecord's doc comment. */
  exceptions: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/exceptions?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<AttendanceExceptionRecord[]>),

  /** Avg. Work Hours Leaderboard for the manager's direct reports over a date range (ONEHR-106). */
  teamEffort: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-effort?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<TeamEffortEntry[]>),

  /** Late Arrivals, Least Hours Worked, and Frequent Breaks for direct reports (ONEHR-107). */
  teamNegligence: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-negligence?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<TeamNegligenceResponse>),

  /** On-Time Leaderboard for the manager's direct reports over a date range. */
  teamPunctuality: (from: string, to: string, token: string) =>
    fetch(`${BASE}/attendance/team-punctuality?from=${from}&to=${to}`, { headers: authHeaders(token) })
      .then(handle<TeamPunctualityResponse>),
};

export const penaltiesApi = {
  // Because there is no active attendance penalty policy today, an empty list is expected.
  list: (filters: PenaltyFilters, token: string) => {
    const params = new URLSearchParams({ from: filters.from, to: filters.to });
    if (filters.status) params.set('status', filters.status);
    if (filters.discrepancyType) params.set('discrepancyType', filters.discrepancyType);
    if (filters.department) params.set('department', filters.department);
    if (filters.location) params.set('location', filters.location);
    if (filters.search) params.set('search', filters.search);
    return fetch(`${BASE}/attendance/penalties?${params.toString()}`, { headers: authHeaders(token) })
      .then(handle<PenaltyRow[]>);
  },

  // Each id is independently re-validated server-side — one failure doesn't affect the rest.
  cancel: (penaltyIds: string[], reason: string, token: string) =>
    fetch(`${BASE}/attendance/penalties/cancel`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ penaltyIds, reason }),
    }).then(r => handle<PenaltyCancelResult>(r)),

  // Read-only — every regularization request ever filed for one employee/date, newest first.
  regularizationHistory: (employeeUserId: string, attendanceDate: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/history?employeeUserId=${employeeUserId}&attendanceDate=${attendanceDate}`,
      { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),
};

export const regularizationApi = {
  submit: (payload: SubmitRegularizationPayload, token: string) =>
    fetch(`${BASE}/attendance/regularization`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<RegularizationRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/attendance/regularization/mine`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  pending: (token: string) =>
    fetch(`${BASE}/attendance/regularization/pending`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  // Same reviewer scoping as `pending`, but every status — backs the Pending Approvals
  // screen's All/Pending/Approved/Rejected status tabs (Manager/HR/Super Admin only).
  forApprover: (token: string) =>
    fetch(`${BASE}/attendance/regularization/for-approver`, { headers: authHeaders(token) }).then(r => handle<RegularizationRecord[]>(r)),

  approve: (id: string, token: string, comment?: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/approve`, {
      method: 'PATCH', headers: authHeaders(token),
      body: JSON.stringify({ comment: comment || undefined }),
    }).then(r => handle<RegularizationRecord>(r)),

  reject: (id: string, comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}/reject`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ comment }),
    }).then(r => handle<RegularizationRecord>(r)),

  // Each id is processed independently server-side — one failure doesn't affect the rest.
  bulkApprove: (ids: string[], token: string, comment?: string) =>
    fetch(`${BASE}/attendance/regularization/bulk-approve`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ ids, comment: comment || undefined }),
    }).then(r => handle<BulkActionResult>(r)),

  bulkReject: (ids: string[], comment: string, token: string) =>
    fetch(`${BASE}/attendance/regularization/bulk-reject`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ ids, comment }),
    }).then(r => handle<BulkActionResult>(r)),

  // Only while the request is still PENDING and belongs to the caller (enforced server-side).
  update: (id: string, payload: SubmitRegularizationPayload, token: string) =>
    fetch(`${BASE}/attendance/regularization/${id}`, {
      method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload),
    }).then(r => handle<RegularizationRecord>(r)),

  approvers: (token: string) =>
    fetch(`${BASE}/attendance/regularization/approvers`, { headers: authHeaders(token) })
      .then(r => handle<ApproverOption[]>(r)),

  /** "Remaining balance: N requests" on the Request Regularization modal. */
  balance: (token: string) =>
    fetch(`${BASE}/attendance/regularization/balance`, { headers: authHeaders(token) })
      .then(r => handle<RegularizationBalance>(r)),

  // Super Admin only — full history org-wide, with optional filters.
  all: (filters: RegularizationFilters, token: string) => {
    const params = new URLSearchParams();
    if (filters.employeeUserId) params.set('employeeUserId', filters.employeeUserId);
    if (filters.approverUserId) params.set('approverUserId', filters.approverUserId);
    if (filters.departmentId) params.set('departmentId', filters.departmentId);
    if (filters.month) params.set('month', filters.month);
    if (filters.status) params.set('status', filters.status);
    const qs = params.toString();
    return fetch(`${BASE}/attendance/regularization/all${qs ? `?${qs}` : ''}`, { headers: authHeaders(token) })
      .then(r => handle<RegularizationRecord[]>(r));
  },
};
