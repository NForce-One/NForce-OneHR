// ─── Worked Today / Effective Hours: the single shared calculation ─────────────────────────────
// The backend's own source of truth for "Effective Hours" is
// AttendanceService.recomputeCombinedWorkedMinutes: it merges overlapping/back-to-back CLOSED
// punch intervals — across BOTH normal Check-In/Out and Web Clock-In/Out sessions, already
// combined into one list by the backend's own collectPunches — before summing, so an overlapping
// window (e.g. a concurrent Check-In and Web Clock-In) is counted once, never twice, and a
// genuine break (a gap between two non-overlapping intervals) is excluded automatically simply by
// falling outside every interval — there is no separate "subtract the break" step. That backend
// calculation only ever runs at an actual checkout, so it never sees a still-open punch.
//
// computeWorkedMinutesFromPunches below is that EXACT SAME merge-and-sum model, not a second,
// independently-invented "raw sum of punch intervals" calculation — the one addition is
// `nowMinuteEpoch`, which stands in for the missing checkOutAt of any punch still open (there can
// be more than one open at once), so a live view mid-day converges to the exact figure the
// backend itself will persist the instant that punch actually closes.
//
// Deliberately NOT shift-end-capped (unlike the backend's own capped overload, used only at an
// actual checkout to bound a forgotten-checkout day) — that cap only ever matters for a session
// left open well past its shift's natural end, a case already handled server-side (flagged
// Missing Check-Out / auto-closed — see AttendanceService/WebClockInService) before a genuinely
// live, still-actively-worked session would ever reach this module.

/** A single check-in/check-out interval — the minimal shape this module needs, kept independent
 * of the app's own Punch/PunchResponse types (which are structurally compatible and can be passed
 * straight through) so this module has no app-specific dependency. */
export interface WorkedInterval {
  checkInAt: string;
  checkOutAt: string | null;
}

/**
 * Parses a zone-less wall-clock ISO string ("2026-09-08T04:00:00" — none of these timestamps
 * carry a "Z"/offset) into epoch milliseconds via `Date.UTC` — NOT to interpret it as a real UTC
 * instant, but as an immune-to-DST, immune-to-browser-timezone arithmetic base, exactly like
 * shiftMarkers.ts's own parseWallClockMs/AttendancePage's own wallClockMs. Every timestamp this
 * module compares is parsed the exact same way, so differences come out correct regardless of the
 * viewer's own timezone.
 */
function wallClockMs(iso: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(iso);
  if (!m) return NaN;
  const [, y, mo, d, h, mi, s] = m;
  return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), s ? Number(s) : 0);
}

/** Per-timestamp whole-minute floor, matching
 * AttendanceService.recomputeCombinedWorkedMinutes's own toComparableMinute
 * (LocalDateTime.toEpochSecond(UTC) / 60, integer division) exactly — so a live figure computed
 * here converges to the exact same integer the backend persists the moment the punch it was
 * live-tracking actually closes. */
function toMinuteEpoch(iso: string): number {
  return Math.floor(wallClockMs(iso) / 60000);
}

/** Converts an already-computed epoch-ms instant (e.g. `Date.now()` plus a server-clock offset)
 * into the same minute-epoch space `toMinuteEpoch` produces for a parsed timestamp — exposed so
 * callers never need their own separate epoch-ms-to-minute conversion that could drift from this
 * module's own. */
export function msToMinuteEpoch(ms: number): number {
  return Math.floor(ms / 60000);
}

/**
 * The single source of truth for "Worked Today"/"Effective Hours" — see this module's own header
 * comment. `nowMinuteEpoch` substitutes for the missing checkOutAt of any interval that's still
 * open (there can be more than one — e.g. a Check-In and a Web Clock-In both open at once); pass
 * `null` to treat nothing as open (every open interval is then excluded, matching the backend's
 * own "only closed sessions count" rule for a fully-settled day). Returns `null` only when there
 * is nothing to sum at all (no punches, or every punch open with `nowMinuteEpoch` null).
 */
export function computeWorkedMinutesFromPunches(
  punches: WorkedInterval[],
  nowMinuteEpoch: number | null,
): number | null {
  const intervals = punches
    .map((p) => {
      const endMinute = p.checkOutAt != null ? toMinuteEpoch(p.checkOutAt) : nowMinuteEpoch;
      if (endMinute == null) return null;
      return [toMinuteEpoch(p.checkInAt), endMinute] as const;
    })
    .filter((iv): iv is readonly [number, number] => iv !== null)
    .sort((a, b) => a[0] - b[0]);

  if (intervals.length === 0) return null;

  let total = 0;
  let curStart = intervals[0][0];
  let curEnd = intervals[0][1];
  for (let i = 1; i < intervals.length; i++) {
    const [s, e] = intervals[i];
    if (s <= curEnd) {
      curEnd = Math.max(curEnd, e);
    } else {
      total += curEnd - curStart;
      curStart = s;
      curEnd = e;
    }
  }
  total += curEnd - curStart;
  return Math.max(0, total);
}
