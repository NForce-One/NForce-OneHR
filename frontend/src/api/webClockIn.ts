import { API_ORIGIN } from './config';
import { browserTimezone } from './attendance';
const BASE = `${API_ORIGIN}/api/attendance/web-clock-in`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface WebClockInRecord {
  id: string;
  employeeUserId: string;
  employeeName: string;
  employeeEmail: string;
  departmentName: string | null;
  workDate: string;
  requestedCheckIn: string;
  // Mandatory on the first Web Clock-In of a resolved work day, optional on every later cycle
  // the same day — enforced server-side, not by this client. No approval attached to it at all.
  reason: string | null;
  checkedOutAt: string | null;
  createdAt: string;
}

export const webClockInApi = {
  // timezone is the browser's own IANA zone (see attendance.ts's browserTimezone) — the server
  // still generates the actual timestamp itself, this only picks which zone it reads its clock
  // in, falling back to the employee's configured Location.timezone if omitted/invalid.
  submit: (reason: string | undefined, token: string) =>
    fetch(BASE, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason, timezone: browserTimezone() }),
    }).then(r => handle<WebClockInRecord>(r)),

  mine: (token: string) =>
    fetch(`${BASE}/mine`, { headers: authHeaders(token) }).then(r => handle<WebClockInRecord[]>(r)),

  checkOut: (token: string) =>
    fetch(`${BASE}/checkout`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ timezone: browserTimezone() }),
    }).then(r => handle<WebClockInRecord>(r)),

  /** Undoes today's still-open check-in (before check-out) — no approval needed. */
  cancel: (token: string) =>
    fetch(`${BASE}/cancel`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handle<void>(r)),
};
