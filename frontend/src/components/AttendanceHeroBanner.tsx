import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { LogIn, LogOut } from 'lucide-react';
import { BrandMark } from './BrandMark';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { attendanceApi, type TodayAttendance, type AttendanceConfig } from '../api/attendance';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';
import { WebClockInRequestModal } from './WebClockInRequestModal';

// ── Private helpers ──────────────────────────────────────────────────────────────

// Backend LocalDateTime strings are naive wall-clock digits already in the record's own
// resolved zone (browser-reported at Check-In/Web Clock-In, see AttendanceService.resolveZone)
// — there is nothing left to convert. Parsing with 'Z' and formatting with timeZone: 'UTC'
// reads those digits back out verbatim, regardless of the *viewer's* own browser timezone.
// Previously this appended '+05:30' (assumed IST) and let toLocaleTimeString re-project into
// the viewer's local zone — for any employee whose resolved zone isn't IST, or any viewer whose
// browser isn't IST either, that mislabeled the digits and then shifted them again, showing a
// check-in/check-out time that didn't match when the action actually happened.
function formatClockTime(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}

function formatWorkedMinutes(mins: number | null): string {
  if (mins == null) return '—';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

function shiftStartMinutes(shiftStart: string): number {
  const [h, m] = shiftStart.split(':').map(Number);
  return h * 60 + m;
}

/**
 * Parses a zone-less server timestamp (business/Location zone digits, verbatim) into epoch ms
 * using a fixed UTC-labeled reference frame — mirrors AttendancePage.tsx's wallClockMs. Never
 * use the browser's own clock/zone for anything shift- or attendance-timing-related: an
 * employee's device zone can differ from their assigned Location's zone.
 */
function wallClockMs(iso: string): number {
  const [datePart, timePart = '00:00:00'] = iso.split('T');
  const [y, mo, d] = datePart.split('-').map(Number);
  const [h, mi, s] = timePart.split(':').map((v) => Math.floor(Number(v)));
  return Date.UTC(y, mo - 1, d, h, mi, s || 0);
}

/** Minutes-since-midnight of a business/Location-zone wall clock produced by wallClockMs. */
function minutesOfDay(ms: number): number {
  const d = new Date(ms);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
}

// ── Gradient background — matches the auth layout's left-panel dark treatment. ──

const HERO_BG = [
  'radial-gradient(120% 100% at 80% 10%, rgba(177,17,22,.34) 0%, transparent 55%)',
  'linear-gradient(160deg, #0a0b0e 0%, #12141a 100%)',
].join(', ');

// ── Shell components ─────────────────────────────────────────────────────────────

function HeroCard({ children }: { children: React.ReactNode }) {
  return (
    <div
      data-theme="dark"
      style={{
        position: 'relative',
        background: HERO_BG,
        borderRadius: 12,
        padding: '28px 32px',
        overflow: 'hidden',
        display: 'flex',
        alignItems: 'center',
        gap: 24,
        minHeight: 140,
      }}
    >
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
        {children}
      </div>
      <div style={{ flexShrink: 0, alignSelf: 'flex-start' }}>
        <BrandMark size="lg" />
      </div>
    </div>
  );
}

function HeroPill({ dot, label, pulse = false }: { dot: string; label: string; pulse?: boolean }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 11px', borderRadius: 20,
      background: `color-mix(in srgb, ${dot} 15%, rgba(255,255,255,0.05))`,
      border: `1px solid color-mix(in srgb, ${dot} 28%, rgba(255,255,255,0.08))`,
      fontSize: 11.5, fontWeight: 600, color: '#E8EAED', whiteSpace: 'nowrap',
    }}>
      <span style={{
        width: 6, height: 6, borderRadius: '50%', background: dot, flexShrink: 0,
        ...(pulse ? { animation: 'nf-hero-pulse 2s ease-in-out infinite', boxShadow: `0 0 0 3px color-mix(in srgb, ${dot} 22%, transparent)` } : {}),
      }} />
      {label}
    </span>
  );
}

// Web Clock section — always rendered by every state below (pre-check-in, active session, on
// break, checked out), not just pre-check-in, and always actionable regardless of the normal
// Check-In/Check-Out status. Its own current action/status is driven entirely by `openWeb` (this
// employee's own currently-open Web Clock-In, if any) — never gated on the normal session's
// canCheckIn/canCheckOut. Multiple Web Clock-In → Web Clock-Out cycles in one day are allowed
// (see WebClockInService.submit) — this row simply reflects whatever the current cycle's state is.
function WebClockInRow({ webToday, onSubmitted }: {
  // Today's Web Clock-In records (already filtered to the business/Location-zone workDate by
  // the parent) — passed down rather than fetched again here: the parent (AttendanceHeroBanner)
  // already fetches this exact list on mount/poll/every action, so a second independent fetch in
  // this child was pure duplicate network traffic on every render and every action.
  webToday: WebClockInRecord[];
  onSubmitted: () => Promise<void>;
}) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [showModal, setShowModal] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  // Web Clock Out / Web Clock In are mutually exclusive (see the two branches below) — never
  // more than one of these text-links is mounted at once — so one shared hover flag is enough.
  // Base color is high-contrast white (matches HeroCard's other high-emphasis text, e.g. the
  // headline) since the previous var(--brand) red-on-near-black had poor contrast here
  // (WCAG-failing); red is kept only as the hover accent, not the resting state.
  const [linkHovered, setLinkHovered] = useState(false);
  const actionLinkStyle: React.CSSProperties = {
    fontSize: 12, fontWeight: 600, color: linkHovered ? 'var(--brand)' : '#E8EAED',
    background: 'none', border: 'none', padding: 0, cursor: 'pointer',
    transition: 'color 120ms ease',
  };

  // No approval/review status at all anymore (see WebClockInService's own class Javadoc) — the
  // only thing that matters is whether the most recent cycle today is still open.
  const openWeb = useMemo(
    () => webToday.find(r => !r.checkedOutAt) ?? null,
    [webToday]);
  // Whether this employee has ANY Web Clock-In cycle on file today — the note is mandatory only
  // for the very first cycle of the resolved work day (enforced server-side); every later cycle
  // needs no note at all, so this click can skip the modal entirely and clock in immediately.
  const isFirstCycleToday = webToday.length === 0;

  // Synchronous re-entrancy guards, checked/set BEFORE any state update — the `disabled`
  // attribute alone only blocks a real click once React has committed it, which isn't
  // guaranteed to happen before a second click (rapid double-click, a slow/blocked main thread)
  // reaches the handler. A ref mutation is visible to the very next invocation immediately, with
  // no render/paint dependency, unlike the setState-driven `disabled` prop.
  const checkingOutRef = useRef(false);
  const submittingRef = useRef(false);

  async function handleCheckOut() {
    if (checkingOutRef.current) return;
    checkingOutRef.current = true;
    setCheckingOut(true);
    try {
      const resp = await webClockInApi.checkOut(token);
      const at = formatClockTime(resp.checkedOutAt);
      // Awaited BEFORE the guard is released below (see CheckInAction's identical punch()
      // ordering in AttendancePage.tsx) — onSubmitted re-fetches webToday, which is what flips
      // this row from "Web Clock Out" back to "Web Clock In". Releasing the guard/re-enabling
      // the button before that fetch lands would leave a real window where the button is
      // clickable again but still showing the stale (already-checked-out) state — exactly the
      // gap a rapid double-click needs to fire a second real request.
      await onSubmitted();
      showToast('success', `Checked out ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Web clock-out failed');
    } finally {
      checkingOutRef.current = false;
      setCheckingOut(false);
    }
  }

  // Not the first cycle of the day — no note required at all, so skip the modal entirely and
  // clock in immediately.
  async function handleQuickWebClockIn() {
    if (submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      const resp = await webClockInApi.submit(undefined, token);
      const at = formatClockTime(resp.requestedCheckIn);
      // Same ordering as handleCheckOut above — await the refetch before the finally block
      // re-enables the button, so it never shows the pre-submit label/state while clickable.
      await onSubmitted();
      showToast('success', `Checked in ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Web clock-in failed');
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  return (
    <div style={{ borderTop: '1px solid var(--line)', paddingTop: 10, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      {openWeb && (
        <>
          <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
            Web clocked in since {formatClockTime(openWeb.requestedCheckIn) ?? '—'}
          </span>
          <button
            onClick={handleCheckOut}
            disabled={checkingOut}
            onMouseEnter={() => setLinkHovered(true)}
            onMouseLeave={() => setLinkHovered(false)}
            style={{ ...actionLinkStyle, cursor: checkingOut ? 'not-allowed' : 'pointer', opacity: checkingOut ? 0.7 : 1 }}
          >
            {checkingOut ? 'Web clocking out…' : 'Web Clock Out →'}
          </button>
        </>
      )}
      {!openWeb && (
        <button
          onClick={() => (isFirstCycleToday ? setShowModal(true) : handleQuickWebClockIn())}
          disabled={submitting}
          onMouseEnter={() => setLinkHovered(true)}
          onMouseLeave={() => setLinkHovered(false)}
          style={{ ...actionLinkStyle, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
        >
          {submitting ? 'Checking in…' : 'Working remotely? Web Clock In →'}
        </button>
      )}
      {showModal && (
        <WebClockInRequestModal onClose={() => setShowModal(false)} onSubmitted={() => onSubmitted()} />
      )}
    </div>
  );
}

// ── Main export ─────────────────────────────────────────────────────────────────
// Self-contained: reads token from auth store, manages its own today/config state.
// Drop into any dashboard view with zero props.

export function AttendanceHeroBanner() {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [today, setToday]   = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  const [config, setConfig]   = useState<AttendanceConfig | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Synchronous re-entrancy guard for handlePunch — see WebClockInRow's identical pattern above
  // for why this can't just be the `submitting` state/disabled attribute alone.
  const punchInFlightRef = useRef(false);
  const [now, setNow] = useState(() => new Date());
  // Anchors the shift countdown to the business/Location-zone clock the backend reported
  // (today.serverNow), not the viewer's own browser clock/zone — captured alongside the browser
  // epoch ms it arrived at, so the countdown can keep ticking between refreshes by adding real
  // elapsed time (epoch ms is zone-agnostic) rather than re-reading the browser's wall clock.
  const [serverNowBase, setServerNowBase] = useState<{ ms: number; fetchedAtMs: number } | null>(null);
  useEffect(() => {
    if (today?.serverNow) {
      setServerNowBase({ ms: wallClockMs(today.serverNow), fetchedAtMs: Date.now() });
    }
  }, [today?.serverNow]);
  // Today's Web Clock-In records — fetched alongside the normal `today` so the "Checked out at"
  // summary below can reflect whichever of Check-Out / Web Clock-Out actually happened most
  // recently. Web Clock-Out never writes to the normal record's own checkOutAt (the two sessions
  // are deliberately independent — see WebClockInService's own class Javadoc), so without this
  // the summary would silently ignore a Web Clock-Out entirely.
  const [webToday, setWebToday] = useState<WebClockInRecord[]>([]);

  // webToday is filtered by the business/Location-zone workDate the `today` fetch just resolved
  // (never the browser's own UTC calendar date), so it's fetched only once that's known.
  const refresh = useCallback(async () => {
    const refreshed = await attendanceApi.today(token).catch(() => null);
    if (refreshed) setToday(refreshed);
    const workDate = refreshed?.workDate;
    if (!workDate) { setWebToday([]); return; }
    await webClockInApi.mine(token)
      .then(list => setWebToday(list.filter(r => r.workDate === workDate)))
      .catch(() => setWebToday([]));
  }, [token]);

  useEffect(() => {
    refresh().finally(() => setLoading(false));
    attendanceApi.config(token).then(setConfig).catch(() => {});
    const pollId = setInterval(refresh, 60000);
    const tickId = setInterval(() => setNow(new Date()), 30000);
    return () => { clearInterval(pollId); clearInterval(tickId); };
  }, [refresh, token]);

  async function handlePunch(kind: 'in' | 'out') {
    if (punchInFlightRef.current) return;
    punchInFlightRef.current = true;
    setSubmitting(true);
    try {
      const record = kind === 'in' ? await attendanceApi.checkIn(token) : await attendanceApi.checkOut(token);
      const refreshed = await attendanceApi.today(token);
      setToday(refreshed);
      // Use the check-in/check-out record's own timestamp, not `refreshed.serverNow` — that comes
      // from a second, later `/today` call and picks up whatever latency that round trip has,
      // making the toast read later than the moment the employee actually punched in.
      const at = formatClockTime(kind === 'in' ? (record.sessionStartedAt ?? record.checkInAt) : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      punchInFlightRef.current = false;
      setSubmitting(false);
    }
  }

  const record     = today?.record ?? null;
  // checkInAt (not sessionStartedAt) — the day's original check-in, deliberately frozen across
  // every resume regardless of source (see AttendanceService.checkIn's own doc comment), is
  // exactly what "locked for the entire resolved work day" requires: Check In must always show
  // the day's FIRST presence and must never be replaced by a later Check-In/Web Clock-In cycle.
  const checkInAt  = record?.checkInAt ?? null;
  const checkOutAt = record?.checkOutAt ?? null;

  // The later of the normal Check-Out and the most recent closed Web Clock-Out today, whichever
  // actually happened last — so the summary line reflects reality regardless of which of the two
  // independent sessions the employee used most recently.
  const latestCheckOutAt = useMemo(() => {
    const candidates = [checkOutAt, ...webToday.map(r => r.checkedOutAt)].filter((v): v is string => !!v);
    if (candidates.length === 0) return null;
    return candidates.reduce((latest, cur) =>
      new Date(cur + 'Z').getTime() > new Date(latest + 'Z').getTime() ? cur : latest);
  }, [checkOutAt, webToday]);

  const shiftInfo = useMemo(() => {
    if (!config || !serverNowBase) return null;
    const currentMs = serverNowBase.ms + (now.getTime() - serverNowBase.fetchedAtMs);
    const diffMin = shiftStartMinutes(config.shiftStart) - minutesOfDay(currentMs);
    const pad2 = (n: number) => String(n).padStart(2, '0');
    const fmt12 = (hhmm: string) => {
      const [h, m] = hhmm.split(':').map(Number);
      return `${h % 12 === 0 ? 12 : h % 12}:${pad2(m)} ${h < 12 ? 'AM' : 'PM'}`;
    };
    return {
      shiftStartLabel: fmt12(config.shiftStart),
      shiftEndLabel: config.shiftEnd ? fmt12(config.shiftEnd) : null,
      shiftName: config.shiftName,
      diffMin,
    };
  }, [config, now, serverNowBase]);

  // ── Loading skeleton ──────────────────────────────────────────────────────────
  if (loading) {
    return (
      <HeroCard>
        <div style={{ height: 28, width: '100%', maxWidth: 240, background: 'rgba(255,255,255,0.08)', borderRadius: 6, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        <div style={{ height: 14, width: '100%', maxWidth: 360, background: 'rgba(255,255,255,0.05)', borderRadius: 4, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        <div style={{ display: 'flex', gap: 7 }}>
          <div style={{ height: 26, width: 90, background: 'rgba(255,255,255,0.06)', borderRadius: 20, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
          <div style={{ height: 26, width: 70, background: 'rgba(255,255,255,0.06)', borderRadius: 20, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        </div>
      </HeroCard>
    );
  }

  // ── Status pill helpers ───────────────────────────────────────────────────────
  const statusPill = (() => {
    if (!record?.status) return null;
    const map: Record<string, { dot: string; label: string }> = {
      PRESENT:          { dot: '#2FB67C', label: 'On time' },
      // formatWorkedMinutes gives "1h 30m"/"1h"/"45m" — matches how worked/elapsed time is
      // already formatted on this same banner, instead of a raw, unconverted minute count.
      LATE:             { dot: '#E0A93B', label: `Late${record.lateByMinutes ? ` by ${formatWorkedMinutes(record.lateByMinutes)}` : ''}` },
      ABSENT:           { dot: '#B11116', label: 'Absent' },
      MISSING_CHECKOUT: { dot: '#F97316', label: 'Missing checkout' },
      // Matches AttendancePage's STATUS_COLORS/STATUS_LABELS (#4C8DD6 / "Half Day") — without
      // this entry a genuinely HALF_DAY record silently showed no status pill at all here.
      HALF_DAY:         { dot: '#4C8DD6', label: 'Half Day' },
    };
    const def = map[record.status];
    return def ? <HeroPill key="status" dot={def.dot} label={def.label} /> : null;
  })();

  // ── State 3: Checked out ──────────────────────────────────────────────────────
  if (!today?.canCheckIn && !today?.canCheckOut) {
    const isComplete = !!(checkInAt && checkOutAt);
    const headline = isComplete
      ? `You completed today's workday. Total worked time: ${formatWorkedMinutes(record?.workedMinutes ?? null)}.`
      : 'No attendance recorded today.';
    const subtitle = isComplete
      ? `Checked in at ${formatClockTime(checkInAt)} · Checked out at ${formatClockTime(latestCheckOutAt)}`
      : undefined;

    return (
      <HeroCard>
        <div style={{ fontFamily: 'Inter, sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          {headline}
        </div>
        {subtitle && <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{subtitle}</p>}
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#2FB67C" label="Completed" />
          {statusPill}
        </div>
        <button
          onClick={() => navigate('/attendance?tab=calendar')}
          style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View full record →
        </button>
        <WebClockInRow webToday={webToday} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 2: Active session ───────────────────────────────────────────────────
  if (today?.canCheckOut) {
    // No "Working now." headline / "…elapsed… prior" narrative here by design — removed per
    // explicit request. The factual check-in time is still shown (just the timestamp, no
    // elapsed/worked-minutes narrative) so the employee's status is legible at a glance.
    return (
      <HeroCard>
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#4E9EE8" label="Working" pulse />
          {statusPill}
        </div>
        {checkInAt && (
          <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>
            Checked in at {formatClockTime(checkInAt)}
          </p>
        )}
        <button
          onClick={() => handlePunch('out')}
          disabled={submitting}
          style={{
            alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 7,
            padding: '8px 18px', background: '#B11116', color: '#fff',
            border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
            cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
          }}
        >
          <LogOut size={14} />
          {submitting ? 'Checking out…' : 'Check Out'}
        </button>
        <button
          onClick={() => navigate('/attendance?tab=calendar')}
          style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View full record →
        </button>
        <WebClockInRow webToday={webToday} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 1b: Checked out for a break, can check in again ─────────────────────
  if (today?.canCheckIn && record) {
    // No "On a break." headline / "You've worked… check in again to resume." narrative here by
    // design — removed per explicit request, same as State 2's "Working now." removal above.
    // Check-In/Check-Out functionality itself, and the worked-minutes total it's based on, are
    // unaffected. The factual check-in/check-out timestamps of the last completed session are
    // still shown (no elapsed/worked-minutes narrative) so status stays legible at a glance.
    return (
      <HeroCard>
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          {statusPill}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <button
            onClick={() => handlePunch('in')}
            disabled={submitting}
            style={{
              display: 'flex', alignItems: 'center', gap: 7,
              padding: '8px 18px', background: '#B11116', color: '#fff',
              border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
              cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
            }}
          >
            <LogIn size={14} />
            {submitting ? 'Checking in…' : 'Check In Again'}
          </button>
          <button
            onClick={() => navigate('/attendance?tab=calendar')}
            style={{ fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            View full record →
          </button>
        </div>
        {checkInAt && (
          <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>
            Checked in at {formatClockTime(checkInAt)}{latestCheckOutAt ? ` · Checked out at ${formatClockTime(latestCheckOutAt)}` : ''}
          </p>
        )}
        <WebClockInRow webToday={webToday} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 1: Pre-check-in ─────────────────────────────────────────────────────
  const shiftLine = shiftInfo
    ? shiftInfo.diffMin > 0
      ? `Shift starts at ${shiftInfo.shiftStartLabel} — ${shiftInfo.diffMin < 60 ? `${shiftInfo.diffMin}m from now` : `in ${Math.floor(shiftInfo.diffMin / 60)}h ${shiftInfo.diffMin % 60}m`}.`
      : shiftInfo.diffMin < 0
        ? `Shift started ${Math.abs(shiftInfo.diffMin) < 60 ? `${Math.abs(shiftInfo.diffMin)}m ago` : `${Math.floor(Math.abs(shiftInfo.diffMin) / 60)}h ${Math.abs(shiftInfo.diffMin) % 60}m ago`}.`
        : 'Your shift starts now.'
    : '';
  const subtitle = shiftLine || 'You haven\'t checked in yet today.';

  return (
    <HeroCard>
      <div style={{ fontFamily: 'Inter, sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
        Not checked in yet.
      </div>
      <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{subtitle}</p>
      <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
        <HeroPill dot="#6B7280" label="Shift not started" />
        {shiftInfo?.shiftName && <HeroPill dot="#6B7280" label={shiftInfo.shiftName} />}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <button
          onClick={() => handlePunch('in')}
          disabled={submitting}
          style={{
            display: 'flex', alignItems: 'center', gap: 7,
            padding: '8px 18px', background: '#B11116', color: '#fff',
            border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
            cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
          }}
        >
          <LogIn size={14} />
          {submitting ? 'Checking in…' : 'Check In'}
        </button>
        <button
          onClick={() => navigate('/attendance?tab=calendar')}
          style={{ fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View full record →
        </button>
      </div>
      <WebClockInRow webToday={webToday} onSubmitted={refresh} />
    </HeroCard>
  );
}
