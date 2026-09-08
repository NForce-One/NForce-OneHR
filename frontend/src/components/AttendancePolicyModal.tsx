import { useEffect, useState } from 'react';
import { Info, X } from 'lucide-react';
import { getMyCurrentPolicy, type PenalizationPolicy } from '../api/penalizationPolicy';

/**
 * Section 25: the Penalisation Policy tab is now driven by the employee's own real resolved
 * policy (GET /api/penalization-policy/my-current — PenalizationPolicyResolutionService's normal
 * allocation → legacy → org-default priority, the same one the attendance engine itself uses),
 * not hardcoded copy that could silently contradict whatever HR actually configured.
 *
 * The Time Tracking tab (WFH/regularization/partial-day limits) remains static intentionally —
 * there is no backend policy document for those yet (no WFH/regularization policy entity exists
 * in this codebase), so nothing to point it at. Converting it would mean inventing a new backend
 * model, out of scope for this pass; see the constant below for the exact same caveat this file
 * already carried before this change.
 *
 * Layout is a full-page takeover (not a small centered dialog) matching Keka's reference —
 * see nf-attpolicy-* rules in index.css for the mobile breakpoint.
 */

type PolicyBlock =
  | { type: 'heading'; text: string }
  | { type: 'text'; text: string }
  | { type: 'callout'; text: string }
  | { type: 'bullets'; items: string[] };

const DEDUCTION_PERIOD_LABEL: Record<string, string> = { DAY: 'day', WEEK: 'week', MONTH: 'month' };

function leaveOrderBullets(policy: PenalizationPolicy): PolicyBlock[] {
  if (policy.basicInfo.deductionMethod !== 'PAID_LEAVE') {
    return [{ type: 'text', text: 'Penalties under this policy are deducted as Loss of Pay.' }];
  }
  return [
    { type: 'text', text: 'Penalties are deducted from your leave balance in this priority order:' },
    { type: 'bullets', items: policy.basicInfo.leavePriorityOrder.length > 0 ? policy.basicInfo.leavePriorityOrder : ['Not configured'] },
    { type: 'text', text: 'Once every configured leave type is exhausted, the remaining amount is deducted as Loss of Pay.' },
  ];
}

function noAttendanceBlocks(policy: PenalizationPolicy): PolicyBlock[] {
  const na = policy.noAttendance;
  if (!na.enabled) {
    return [{ type: 'heading', text: 'No Attendance' }, { type: 'text', text: 'Not currently enabled under your policy.' }];
  }
  const blocks: PolicyBlock[] = [
    { type: 'heading', text: 'No Attendance' },
    { type: 'text', text: `You will be penalized ${na.deductionDays ?? '—'} day(s) for every day with no recorded attendance.` },
  ];
  if (policy.basicInfo.bufferPeriodDays) {
    blocks.push({ type: 'callout', text: `You have a buffer period of ${policy.basicInfo.bufferPeriodDays} day(s) to regularize your attendance before the penalization applies.` });
  }
  return blocks;
}

function lateArrivalBlocks(policy: PenalizationPolicy): PolicyBlock[] {
  const la = policy.lateArrival;
  if (!la.enabled) {
    return [{ type: 'heading', text: 'Late Arrival' }, { type: 'text', text: 'Not currently enabled under your policy.' }];
  }
  const blocks: PolicyBlock[] = [{ type: 'heading', text: 'Late Arrival' }];
  if (la.basis === 'TOTAL_HOURS') {
    // Only this basis still consults the policy's own grace period server-side — the
    // NUMBER_OF_INCIDENTS basis below is governed entirely by the employee's assigned Shift's own
    // allowed-late privilege (there is exactly one allowed-late grace now, not a second one here).
    if (la.gracePeriodMinutes != null) {
      blocks.push({ type: 'callout', text: `You have a grace period of ${la.gracePeriodMinutes} minute(s) beyond which your arrival is considered late.` });
    }
    blocks.push({ type: 'text', text: `Once your total late minutes in a ${la.exemptPeriod.toLowerCase()} exceed ${la.allowedHours ?? '—'} hour(s), a penalty applies based on the tier your total falls into:` });
    if (la.lateHoursTiers.length > 0) {
      blocks.push({ type: 'bullets', items: la.lateHoursTiers.map(t => `More than ${t.thresholdHours} total hour(s) late: ${t.deductionDays} day(s) penalty`) });
    }
  } else {
    const perShifts = la.deductionPerShifts && la.deductionPerShifts > 1 ? la.deductionPerShifts : 1;
    const rateText = perShifts > 1
      ? `you will be penalized ${la.deductionDays ?? '—'} day(s) for every ${perShifts} incident(s) beyond that`
      : `you will be penalized ${la.deductionDays ?? '—'} day(s) for every incident beyond that`;
    blocks.push({ type: 'text', text: `You can arrive late ${la.exemptCount ?? 0} time(s) per ${la.exemptPeriod.toLowerCase()} without penalty — ${rateText}.` });
  }
  if (la.ignoreWhenEffectiveHoursMetEnabled) {
    blocks.push({ type: 'callout', text: 'If your required effective hours are still met for the day, that day is not penalized for late arrival.' });
  }
  if (policy.basicInfo.bufferPeriodDays) {
    blocks.push({ type: 'callout', text: `You have a buffer period of ${policy.basicInfo.bufferPeriodDays} day(s) to regularize your attendance before the penalization applies.` });
  }
  return blocks;
}

function workHoursShortageBlocks(policy: PenalizationPolicy): PolicyBlock[] {
  const whs = policy.workHoursShortage;
  if (!whs.enabled) {
    return [{ type: 'heading', text: 'Work Hours' }, { type: 'text', text: 'Not currently enabled under your policy.' }];
  }
  const basisLabel = whs.deductionBasis === 'GROSS_HOURS' ? 'gross hours' : 'effective hours';
  const periodLabel = DEDUCTION_PERIOD_LABEL[whs.deductionPeriod] ?? whs.deductionPeriod.toLowerCase();
  const blocks: PolicyBlock[] = [
    { type: 'heading', text: 'Work Hours' },
    { type: 'text', text: `You will be penalized based on the shortage of ${basisLabel} against your shift, evaluated per ${periodLabel}:` },
  ];
  if (whs.tiers.length > 0) {
    blocks.push({ type: 'bullets', items: whs.tiers.map(t => `${t.deductionDays} day(s) penalty if ${basisLabel} are less than ${t.thresholdPercent}% of shift hours`) });
  }
  if (!whs.applyPenaltyForLateArrivalEnabled) {
    blocks.push({ type: 'text', text: 'If both Late Arrival and Work Hours Shortage occur the same day, only the Work Hours Shortage penalty applies.' });
  }
  if (policy.basicInfo.bufferPeriodDays) {
    blocks.push({ type: 'callout', text: `You have a buffer period of ${policy.basicInfo.bufferPeriodDays} day(s) to regularize your attendance before the penalization applies.` });
  }
  return blocks;
}

function missingLogsBlocks(policy: PenalizationPolicy): PolicyBlock[] {
  const ml = policy.missingLogs;
  if (!ml.enabled) {
    return [{ type: 'heading', text: 'Missing Swipes' }, { type: 'text', text: 'Not currently enabled under your policy.' }];
  }
  const perShifts = ml.deductionMode === 'IRRESPECTIVE' ? null : (ml.deductionPerShifts && ml.deductionPerShifts > 1 ? ml.deductionPerShifts : 1);
  const rateText = ml.deductionMode === 'IRRESPECTIVE'
    ? `you will be penalized ${ml.deductionDays ?? '—'} day(s) once for the period`
    : perShifts && perShifts > 1
      ? `you will be penalized ${ml.deductionDays ?? '—'} day(s) for every ${perShifts} occurrence(s) beyond that`
      : `you will be penalized ${ml.deductionDays ?? '—'} day(s) for every occurrence beyond that`;
  const blocks: PolicyBlock[] = [
    { type: 'heading', text: 'Missing Swipes' },
    { type: 'text', text: `You can have ${ml.exemptDays ?? 0} missing-swipe day(s) per ${ml.exemptPeriod.toLowerCase()} without penalty — ${rateText}.` },
  ];
  if (policy.basicInfo.bufferPeriodDays) {
    blocks.push({ type: 'callout', text: `You have a buffer period of ${policy.basicInfo.bufferPeriodDays} day(s) to regularize your attendance before the penalization applies.` });
  }
  return blocks;
}

function buildPenalisationBlocks(policy: PenalizationPolicy): PolicyBlock[] {
  return [
    { type: 'callout', text: `This policy is effective from ${new Date(policy.effectiveFrom).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}.` },
    ...leaveOrderBullets(policy),
    ...noAttendanceBlocks(policy),
    ...lateArrivalBlocks(policy),
    ...workHoursShortageBlocks(policy),
    ...missingLogsBlocks(policy),
  ];
}

const TIME_TRACKING_POLICY_BLOCKS: PolicyBlock[] = [
  { type: 'text', text: 'Below are the details of the time tracking policy assigned to you' },

  { type: 'heading', text: 'Check-in' },
  { type: 'text', text: 'Your attendance is tracked via check-in/check-out on this page, or via Check-in when working remotely (subject to approval).' },

  { type: 'heading', text: 'Work from Home (WFH)' },
  { type: 'text', text: 'You are allowed to take 2 day(s) of WFH in a Month.' },
  { type: 'text', text: 'You can request for full day, half-day' },
  { type: 'text', text: 'You are required to clock-in/out when doing WFH. In case of late clock-in, no clock-in, or less effective/gross hours clocked, the system will penalise based on the penalisation policy assigned to you.' },
  { type: 'text', text: 'WFH request requires 2 day(s) of prior notice, containing at least 0 working day(s)' },
  { type: 'callout', text: 'Approval is required for all WFH requests.' },

  { type: 'heading', text: 'Regularization & Partial Day' },
  { type: 'text', text: 'In case of penalisation due to attendance discrepancy, you are allowed to request regularisation, 3 time(s) in a Month.' },
  { type: 'text', text: 'You are allowed to raise a regularisation request for the past 5 day(s).' },
  { type: 'text', text: 'You are allowed to apply for below partial work day request(s), based on number of cumulative (total) hours in a period' },
  { type: 'callout', text: 'Partial day is allowed for a cumulative hours of 120 minutes in a Month.' },
  { type: 'bullets', items: [
    'Late Arrival: You are allowed to request for maximum 120 minutes (each instance) of late arrival (after shift start time)',
    'Early Leaving: You are allowed to request for maximum 120 minutes (each instance) of early leaving (before shift end time)',
    'Anytime during the shift: You are allowed to request for maximum 120 minutes (each instance) of partial day leave (during the shift)',
  ] },
  { type: 'text', text: 'Partial day request cannot be made sooner than 1 day(s).' },
  { type: 'callout', text: 'Approval is required for all Regularization / Partial Day requests.' },
];

function PolicyBlockView({ block }: { block: PolicyBlock }) {
  if (block.type === 'heading') {
    return (
      <h3
        className="nf-attpolicy-heading"
        style={{
          fontFamily: 'Inter, sans-serif', fontSize: 17, fontWeight: 700, color: 'var(--txt)',
          margin: '32px 0 12px', paddingBottom: 10, borderBottom: '1px solid var(--line)',
        }}
      >
        {block.text}
      </h3>
    );
  }
  if (block.type === 'callout') {
    return (
      <div
        className="nf-attpolicy-callout"
        style={{
          display: 'flex', alignItems: 'flex-start', gap: 10,
          background: 'rgba(76,141,214,.10)', border: '1px solid rgba(76,141,214,.28)',
          borderLeft: '3px solid #4C8DD6', borderRadius: 8, padding: '12px 16px',
          fontSize: 13.5, color: 'var(--txt)', lineHeight: 1.6, margin: '12px 0',
        }}
      >
        <Info size={15} style={{ color: '#4C8DD6', flexShrink: 0, marginTop: 2 }} />
        <span>{block.text}</span>
      </div>
    );
  }
  if (block.type === 'bullets') {
    return (
      <ul style={{ listStyle: 'none', margin: '8px 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {block.items.map((item, i) => (
          <li key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 13.5, color: 'var(--txt-mut)', lineHeight: 1.65 }}>
            <span style={{ flexShrink: 0, marginTop: 8, width: 6, height: 6, borderRadius: '50%', border: '1.5px solid var(--txt-dim)' }} />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    );
  }
  return <p style={{ fontSize: 13.5, color: 'var(--txt-mut)', lineHeight: 1.7, margin: '10px 0' }}>{block.text}</p>;
}

type PolicyTab = 'PENALISATION' | 'TIME_TRACKING';

export function AttendancePolicyModal({ token, onClose }: { token: string; onClose: () => void }) {
  const [tab, setTab] = useState<PolicyTab>('PENALISATION');
  const [policy, setPolicy] = useState<PenalizationPolicy | null>(null);
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>('loading');

  useEffect(() => {
    let cancelled = false;
    setLoadState('loading');
    getMyCurrentPolicy(token)
      .then((p) => { if (!cancelled) { setPolicy(p); setLoadState('ready'); } })
      .catch(() => { if (!cancelled) setLoadState('error'); });
    return () => { cancelled = true; };
  }, [token]);

  let penalisationBlocks: PolicyBlock[];
  if (loadState === 'loading') {
    penalisationBlocks = [{ type: 'text', text: 'Loading your attendance policy…' }];
  } else if (loadState === 'error') {
    penalisationBlocks = [{ type: 'text', text: 'Could not load your attendance policy right now. Please try again later.' }];
  } else if (!policy) {
    penalisationBlocks = [{ type: 'text', text: 'No attendance penalization policy has been configured for your organization yet.' }];
  } else {
    penalisationBlocks = buildPenalisationBlocks(policy);
  }
  const blocks = tab === 'PENALISATION' ? penalisationBlocks : TIME_TRACKING_POLICY_BLOCKS;

  // This is a full-page takeover that covers the viewport, but the Attendance page behind it
  // stays in the DOM and keeps document.body scrollable — without this, the body's native
  // scrollbar and this modal's own .nf-attpolicy-content scrollbar both render at the same
  // right edge, showing as two scrollbars. Restores whatever overflow value was there before
  // (rather than assuming ''), so it can't clobber a lock some other component already set.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  return (
    <div className="nf-attpolicy-overlay" style={{ position: 'fixed', inset: 0, background: 'var(--shell)', zIndex: 500, display: 'flex', flexDirection: 'column' }}>
      {/* Header + tabs stay pinned while the long policy text scrolls beneath them. */}
      <div className="nf-attpolicy-sticky" style={{ position: 'sticky', top: 0, background: 'var(--shell)', borderBottom: '1px solid var(--line)', zIndex: 1 }}>
        <div className="nf-attpolicy-header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '20px 32px' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 20, color: 'var(--txt)' }}>Attendance Policy</span>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 8, borderRadius: 7, display: 'flex' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; e.currentTarget.style.color = 'var(--txt)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--txt-dim)'; }}
          >
            <X size={18} />
          </button>
        </div>
        <div className="nf-attpolicy-tabs" style={{ display: 'flex', gap: 8, padding: '0 32px 16px' }}>
          {([
            { value: 'PENALISATION', label: 'Penalisation Policy' },
            { value: 'TIME_TRACKING', label: 'Time Tracking Policy' },
          ] as const).map((t) => {
            const active = t.value === tab;
            return (
              <button
                key={t.value}
                onClick={() => setTab(t.value)}
                style={{
                  background: active ? 'rgba(47,182,124,.12)' : 'var(--raised)',
                  color: active ? '#2FB67C' : 'var(--txt-mut)',
                  border: `1px solid ${active ? 'rgba(47,182,124,.35)' : 'var(--line2)'}`,
                  borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer',
                }}
              >
                {t.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Scrollable body — full-width panel, but text itself stays capped for readability. */}
      <div className="nf-attpolicy-content" style={{ flex: 1, overflowY: 'auto', padding: '28px 32px 56px' }}>
        <div style={{ maxWidth: 860 }}>
          {blocks.map((block, i) => <PolicyBlockView key={i} block={block} />)}
        </div>
      </div>
    </div>
  );
}
