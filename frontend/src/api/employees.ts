import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface ManagerRef { userId: string; fullName: string; email: string; }

export interface EmployeeRecord {
  userId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  role: string;
  businessUnitId: string | null;
  businessUnitName: string | null;
  departmentId: string | null;
  departmentName: string | null;
  designationId: string | null;
  designationName: string | null;
  locationId: string | null;
  locationName: string | null;
  shiftId: string | null;
  shiftName: string | null;
  employmentType: string;
  workMode: string;
  joiningDate: string;
  /** Admin-set IANA zone id (e.g. "Asia/Kolkata"), or null to fall back to Location then the org default. */
  timezone: string | null;
  active: boolean;
  currentManager: ManagerRef | null;
  tempPassword?: string;
}

export interface CreateEmployeePayload {
  fullName: string;
  email: string;
  employeeCode?: string;
  businessUnitId?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  shiftId?: string;
  employmentType?: string;
  workMode?: string;
  joiningDate: string;
  managerId?: string;
  /** Admin-only. IANA zone id, e.g. "Asia/Kolkata"; omit/blank to leave unset (falls back to Location, then the org default). */
  timezone?: string;
}

export interface UpdateEmployeePayload {
  fullName?: string;
  businessUnitId?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  shiftId?: string;
  employmentType?: string;
  workMode?: string;
  /** Required (true) to change department/designation/employmentType on a deactivated employee. */
  confirmInactiveEdit?: boolean;
  /** Admin-only. null/omit = leave unchanged; "" = clear it; else = set it. */
  timezone?: string;
}

export interface CreateUserPayload extends CreateEmployeePayload {
  role: string;
}

export interface UpdateUserPayload {
  fullName?: string;
  role?: string;
  businessUnitId?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  shiftId?: string;
  employmentType?: string;
  workMode?: string;
  managerId?: string;
  /** Required (true) to change role/manager/department/designation/employmentType on a deactivated user. */
  confirmInactiveEdit?: boolean;
  /** Admin-only (this endpoint is Super-Admin-only). null/omit = leave unchanged; "" = clear it; else = set it. */
  timezone?: string;
}

export interface UpdateJoiningDatePayload {
  newJoiningDate: string;
  note?: string;
}

export interface ResetPasswordResult { tempPassword: string; message: string; }

export interface EmployeeCodePreview { employeeCode: string; }

export const employeesApi = {
  list: (token: string) =>
    fetch(`${BASE}/employees`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),

  create: (payload: CreateEmployeePayload, token: string) =>
    fetch(`${BASE}/employees`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  update: (userId: string, payload: UpdateEmployeePayload, token: string) =>
    fetch(`${BASE}/employees/${userId}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  potentialManagers: (token: string) =>
    fetch(`${BASE}/employees/potential-managers`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),

  // Read-only preview of the Employee ID the Add Employee/User form should display — does NOT
  // reserve or consume the ID. See EmployeeCodeGenerator#preview on the backend.
  previewNextCode: (token: string) =>
    fetch(`${BASE}/employees/next-code`, { headers: authHeaders(token) }).then(handle<EmployeeCodePreview>),
};

export const usersApi = {
  list: (token: string) =>
    fetch(`${BASE}/users`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),

  create: (payload: CreateUserPayload, token: string) =>
    fetch(`${BASE}/users`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  update: (userId: string, payload: UpdateUserPayload, token: string) =>
    fetch(`${BASE}/users/${userId}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  updateJoiningDate: (userId: string, payload: UpdateJoiningDatePayload, token: string) =>
    fetch(`${BASE}/users/${userId}/joining-date`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  resetPassword: (userId: string, token: string) =>
    fetch(`${BASE}/users/${userId}/reset-password`, { method: 'POST', headers: authHeaders(token) }).then(handle<ResetPasswordResult>),

  setStatus: (userId: string, active: boolean, token: string) =>
    fetch(`${BASE}/users/${userId}/status`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ active }) }).then(handle<EmployeeRecord>),

  softDelete: (userId: string, token: string) =>
    fetch(`${BASE}/users/${userId}`, { method: 'DELETE', headers: authHeaders(token) }).then(async (res) => {
      if (!res.ok) {
        let body: { message?: string } = {};
        try { body = await res.json(); } catch { /* non-json */ }
        throw new Error((body as any).message ?? `Request failed (${res.status})`);
      }
    }),
};
