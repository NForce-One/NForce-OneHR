import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/org`;

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

async function handleEmpty(res: Response): Promise<void> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  }
}

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export interface BusinessUnitRow {
  id: string; name: string; active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface DepartmentRow {
  id: string; name: string; active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface DesignationRow {
  id: string; title: string; grade: string | null; level: string | null;
  active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface LocationRow {
  id: string; name: string; city: string | null; state: string | null;
  country: string | null; holidayRegion: string | null; timezone: string | null;
  active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface ShiftRow {
  id: string; name: string; code: string | null; description: string | null;
  // The version currently in effect (as of today) — same shape this surface has always shown.
  startTime: string; endTime: string; breakMinutes: number | null;
  // Every Shift Version has its own grace period — no single global value applies anymore.
  lateGraceMinutes: number | null;
  // Set only when a future version is already scheduled (at most one at a time — editing again
  // before it takes effect replaces it, never stacks a second one).
  pendingEffectiveFrom: string | null;
  pendingStartTime: string | null;
  pendingEndTime: string | null;
  pendingBreakMinutes: number | null;
  pendingLateGraceMinutes: number | null;
  active: boolean; employeeCount: number; createdAt: string;
}
export interface CreateShiftPayload {
  name: string; code?: string; description?: string;
  startTime: string; endTime: string; breakMinutes?: number; lateGraceMinutes?: number;
}
export interface UpdateShiftPayload extends CreateShiftPayload {
  // Required, and must be strictly after today — the backend rejects today/past regardless of
  // what's sent here. A brand-new Shift (createShift) has no such field: its first version is
  // effective immediately, since nothing is assigned to it yet to protect.
  effectiveFrom: string;
}
export interface ShiftVersionRow {
  id: string; startTime: string; endTime: string; breakMinutes: number | null; lateGraceMinutes: number | null; effectiveFrom: string;
}
export interface ShiftEmployeeRow {
  userId: string; employeeCode: string; fullName: string; email: string; departmentName: string | null;
  active: boolean;
}
export interface WeeklyOffPolicyRow {
  id: string; name: string; offDays: string[]; employeeCount: number; createdAt: string;
}
export interface WeeklyOffPolicyPayload {
  name: string; offDays: string[];
}
export interface ShiftWeeklyOffRules {
  id: string; maximumShiftDayDurationHours: number; updatedAt: string;
}
export interface AttendanceRules {
  id: string; halfDayMaxHours: number; defaultTimezone: string; updatedAt: string;
}

export const orgApi = {
  // Business Units
  listBusinessUnits: (token: string) =>
    fetch(`${BASE}/business-units`, { headers: authHeaders(token) }).then(r => handle<BusinessUnitRow[]>(r)),

  createBusinessUnit: (token: string, name: string) =>
    fetch(`${BASE}/business-units`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<BusinessUnitRow>(r)),

  updateBusinessUnit: (token: string, id: string, name: string) =>
    fetch(`${BASE}/business-units/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<BusinessUnitRow>(r)),

  toggleBusinessUnitActive: (token: string, id: string) =>
    fetch(`${BASE}/business-units/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<BusinessUnitRow>(r)),

  deleteBusinessUnit: (token: string, id: string) =>
    fetch(`${BASE}/business-units/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Departments
  listDepartments: (token: string) =>
    fetch(`${BASE}/departments`, { headers: authHeaders(token) }).then(r => handle<DepartmentRow[]>(r)),

  createDepartment: (token: string, name: string) =>
    fetch(`${BASE}/departments`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<DepartmentRow>(r)),

  updateDepartment: (token: string, id: string, name: string) =>
    fetch(`${BASE}/departments/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<DepartmentRow>(r)),

  toggleDepartmentActive: (token: string, id: string) =>
    fetch(`${BASE}/departments/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<DepartmentRow>(r)),

  deleteDepartment: (token: string, id: string) =>
    fetch(`${BASE}/departments/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Designations
  listDesignations: (token: string) =>
    fetch(`${BASE}/designations`, { headers: authHeaders(token) }).then(r => handle<DesignationRow[]>(r)),

  createDesignation: (token: string, title: string, grade?: string, level?: string) =>
    fetch(`${BASE}/designations`, {
      method: 'POST', headers: authHeaders(token),
      body: JSON.stringify({ title, grade: grade || undefined, level: level || undefined }),
    }).then(r => handle<DesignationRow>(r)),

  updateDesignation: (token: string, id: string, payload: { title: string; grade?: string; level?: string }) =>
    fetch(`${BASE}/designations/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<DesignationRow>(r)),

  toggleDesignationActive: (token: string, id: string) =>
    fetch(`${BASE}/designations/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<DesignationRow>(r)),

  deleteDesignation: (token: string, id: string) =>
    fetch(`${BASE}/designations/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Locations
  listLocations: (token: string) =>
    fetch(`${BASE}/locations`, { headers: authHeaders(token) }).then(r => handle<LocationRow[]>(r)),

  createLocation: (token: string, payload: { name: string; city?: string; state?: string; country?: string; holidayRegion?: string; timezone?: string }) =>
    fetch(`${BASE}/locations`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<LocationRow>(r)),

  updateLocation: (token: string, id: string, payload: { name: string; city?: string; state?: string; country?: string; holidayRegion?: string; timezone?: string }) =>
    fetch(`${BASE}/locations/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<LocationRow>(r)),

  toggleLocationActive: (token: string, id: string) =>
    fetch(`${BASE}/locations/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<LocationRow>(r)),

  deleteLocation: (token: string, id: string) =>
    fetch(`${BASE}/locations/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Shifts — create/edit/delete are Super Admin only, enforced by the backend regardless of
  // who can reach this UI (see OrgService). Listing itself is open to any authenticated caller.
  listShifts: (token: string) =>
    fetch(`${BASE}/shifts`, { headers: authHeaders(token) }).then(r => handle<ShiftRow[]>(r)),

  listShiftEmployees: (token: string, shiftId: string) =>
    fetch(`${BASE}/shifts/${shiftId}/employees`, { headers: authHeaders(token) }).then(r => handle<ShiftEmployeeRow[]>(r)),

  listShiftVersions: (token: string, shiftId: string) =>
    fetch(`${BASE}/shifts/${shiftId}/versions`, { headers: authHeaders(token) }).then(r => handle<ShiftVersionRow[]>(r)),

  createShift: (token: string, payload: CreateShiftPayload) =>
    fetch(`${BASE}/shifts`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<ShiftRow>(r)),

  // Editing a Shift never mutates its currently-effective timing — it schedules a new version,
  // effective from payload.effectiveFrom (validated server-side to be strictly after today).
  updateShift: (token: string, id: string, payload: UpdateShiftPayload) =>
    fetch(`${BASE}/shifts/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<ShiftRow>(r)),

  toggleShiftActive: (token: string, id: string) =>
    fetch(`${BASE}/shifts/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<ShiftRow>(r)),

  deleteShift: (token: string, id: string) =>
    fetch(`${BASE}/shifts/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Weekly Off Policies — WeeklyOffPolicy is the sole source of truth for weekly-off (Shift
  // carries no working-days concept). Same Super-Admin-only create/edit/delete pattern as Shifts.
  listWeeklyOffPolicies: (token: string) =>
    fetch(`${BASE}/weekly-off-policies`, { headers: authHeaders(token) }).then(r => handle<WeeklyOffPolicyRow[]>(r)),

  createWeeklyOffPolicy: (token: string, payload: WeeklyOffPolicyPayload) =>
    fetch(`${BASE}/weekly-off-policies`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<WeeklyOffPolicyRow>(r)),

  updateWeeklyOffPolicy: (token: string, id: string, payload: WeeklyOffPolicyPayload) =>
    fetch(`${BASE}/weekly-off-policies/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<WeeklyOffPolicyRow>(r)),

  deleteWeeklyOffPolicy: (token: string, id: string) =>
    fetch(`${BASE}/weekly-off-policies/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Shifts & Weekly Off Rules — org-level singleton. P1 holds exactly one setting, Maximum Shift
  // Day Duration (default 18h). Read is open; update is Super-Admin-only (enforced server-side).
  getShiftWeeklyOffRules: (token: string) =>
    fetch(`${BASE}/shift-weekly-off-rules`, { headers: authHeaders(token) }).then(r => handle<ShiftWeeklyOffRules>(r)),

  updateShiftWeeklyOffRules: (token: string, maximumShiftDayDurationHours: number) =>
    fetch(`${BASE}/shift-weekly-off-rules`, {
      method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ maximumShiftDayDurationHours }),
    }).then(r => handle<ShiftWeeklyOffRules>(r)),

  // Attendance Rules — org-level singleton. Currently holds exactly one setting, Half Day Max
  // Hours (the absolute-hours HALF_DAY classification threshold, default 3.5h). Read is open;
  // update is Super-Admin-only (enforced server-side).
  getAttendanceRules: (token: string) =>
    fetch(`${BASE}/attendance-rules`, { headers: authHeaders(token) }).then(r => handle<AttendanceRules>(r)),

  updateAttendanceRules: (token: string, halfDayMaxHours: number) =>
    fetch(`${BASE}/attendance-rules`, {
      method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ halfDayMaxHours }),
    }).then(r => handle<AttendanceRules>(r)),

  // Org-wide fallback timezone — consulted only when neither an employee's own timezone nor
  // their Location's is set. Separate endpoint from the one above; update is Super-Admin-only.
  updateDefaultTimezone: (token: string, defaultTimezone: string) =>
    fetch(`${BASE}/attendance-rules/default-timezone`, {
      method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ defaultTimezone }),
    }).then(r => handle<AttendanceRules>(r)),
};
