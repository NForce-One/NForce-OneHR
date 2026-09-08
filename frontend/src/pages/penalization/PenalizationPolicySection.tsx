import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, History, Plus, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { leaveApi, type LeaveType } from '../../api/leave';
import {
  getCurrentPolicy, getPolicyVersion, getPolicyVersions, savePolicy,
  type PenalizationPolicy, type PenalizationPolicyRequest, type PenalizationPolicyVersionSummary,
  type WorkHoursTier, type LateHoursTier,
} from '../../api/penalizationPolicy';

type SectionKey = 'basicInfo' | 'noAttendance' | 'lateArrival' | 'workHoursShortage' | 'missingLogs';

const SECTION_LABELS: Record<SectionKey, string> = {
  basicInfo: 'Basic Information',
  noAttendance: 'No Attendance',
  lateArrival: 'Late Arrival',
  workHoursShortage: 'Work Hours Shortage',
  missingLogs: 'Missing Logs',
};

const SECTIONS: SectionKey[] = ['basicInfo', 'noAttendance', 'lateArrival', 'workHoursShortage', 'missingLogs'];
// Sections with their own Enabled/Disabled toggle — Basic Information applies to the whole
// document (deduction method, buffer period, notice period, effective date), so it has no
// enabled flag of its own and is never part of the wizard's method-selection step.
const TOGGLEABLE_SECTIONS: SectionKey[] = ['noAttendance', 'lateArrival', 'workHoursShortage', 'missingLogs'];

const METHOD_HELP: Record<SectionKey, string> = {
  basicInfo: 'Applies to every penalty this policy produces: whether a penalty deducts Loss of Pay or a configured paid-leave type, the buffer period before a penalty is finalized, and (optionally) a future effective date for this save.',
  noAttendance: 'Penalizes a working day with no recorded attendance at all. Can also treat a day with unusually low worked hours as a no-show, and can extend the penalty to an adjoining holiday or weekly-off day when no-attendance days sandwich it.',
  lateArrival: 'Penalizes arriving after the shift start, beyond a configurable grace period. Choose between counting incidents (with a configurable exempt count per week/month) or accumulated total late hours against tiered rules.',
  workHoursShortage: 'Penalizes working fewer hours than the assigned shift, using tiered "less than X% of shift hours" rules. Evaluated on Effective (worked, breaks excluded) or Gross (first check-in to last check-out) hours, daily or aggregated weekly/monthly. Can be configured to avoid double-penalizing a day that already triggered a Late Arrival penalty (or vice versa).',
  missingLogs: 'Penalizes missing check-in/check-out punches, with a configurable number of exempt occurrences per week/month and a deduction rate (per shift, or a single flat amount for the whole period).',
};

// ── Shared style tokens — matching OrgSetupPage's inline-style convention ──
const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '7px 9px', fontSize: 13, color: 'var(--txt)',
  outline: 'none', width: '100%', boxSizing: 'border-box',
};
const labelText: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
const checkboxRow: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)', cursor: 'pointer' };
const helpTextStyle: React.CSSProperties = { fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55, background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '10px 12px' };

function EnabledBadge({ enabled }: { enabled: boolean }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      background: enabled ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
      color: enabled ? 'var(--ok)' : 'var(--txt-dim)',
    }}>
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  );
}

type SectionStatus = 'draft' | 'saved' | 'unsaved' | 'error';

function SectionStatusBadge({ status }: { status: SectionStatus }) {
  const config: Record<SectionStatus, { label: string; bg: string; fg: string }> = {
    draft: { label: 'Not configured', bg: 'rgba(107,114,128,.15)', fg: 'var(--txt-dim)' },
    saved: { label: 'Saved', bg: 'rgba(47,182,124,.15)', fg: 'var(--ok)' },
    unsaved: { label: 'Unsaved', bg: 'rgba(245,166,35,.18)', fg: 'var(--warn, #f5a623)' },
    error: { label: 'Error', bg: 'rgba(228,55,61,.15)', fg: 'var(--risk)' },
  };
  const c = config[status];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', padding: '1.5px 7px', borderRadius: 20, fontSize: 10, fontWeight: 700, background: c.bg, color: c.fg, textTransform: 'uppercase', letterSpacing: '.03em' }}>
      {c.label}
    </span>
  );
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

/** Local calendar date (not UTC) as YYYY-MM-DD — same convention LeavePage uses for its date inputs. */
function todayIsoDate(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

// ── Validation — mirrors PenalizationPolicyService's backend validation exactly, including its
// deliberate choice NOT to enforce monotonically increasing deduction amounts across tiers (an
// already-shipped example intentionally configures them non-monotonically; see
// PenalizationPolicyServiceTest#save_validDistinctTiers_accepted_savedInOrder on the backend). ──

function basicInfoErrors(basicInfo: PenalizationPolicyRequest['basicInfo'], currentEffectiveFrom: string | null): string[] {
  const errors: string[] = [];
  if (basicInfo.deductionMethod === 'PAID_LEAVE' && basicInfo.leavePriorityOrder.length === 0) {
    errors.push('At least one leave type must be configured in priority order when deduction method is Paid Leave.');
  }
  const requested = basicInfo.requestedEffectiveFrom;
  if (requested) {
    const today = todayIsoDate();
    if (requested <= today) {
      errors.push('Effective date must be in the future.');
    } else if (currentEffectiveFrom && requested <= currentEffectiveFrom.slice(0, 10)) {
      errors.push(`Effective date must be after the currently scheduled version's effective date (${fmtDate(currentEffectiveFrom)}).`);
    }
  }
  return errors;
}

function workHoursTierErrors(tiers: WorkHoursTier[], enabled: boolean): string[] {
  const errors: string[] = [];
  if (enabled && tiers.length === 0) {
    errors.push('At least one tier must be configured while this section is enabled.');
  }
  const seen = new Set<number>();
  tiers.forEach((t, i) => {
    const n = i + 1;
    if (t.thresholdPercent < 0) errors.push(`Tier ${n}: threshold percent cannot be negative.`);
    if (t.thresholdPercent > 100) errors.push(`Tier ${n}: threshold percent cannot exceed 100%.`);
    if (seen.has(t.thresholdPercent)) errors.push(`Tier ${n}: threshold ${t.thresholdPercent}% duplicates another tier — thresholds must be distinct.`);
    seen.add(t.thresholdPercent);
    if (t.deductionDays < 0) errors.push(`Tier ${n}: deduction cannot be negative.`);
  });
  return errors;
}

function lateHoursTierErrors(tiers: LateHoursTier[], enabled: boolean, basis: string): string[] {
  const errors: string[] = [];
  if (enabled && basis === 'TOTAL_HOURS' && tiers.length === 0) {
    errors.push('At least one Total Late Hours tier must be configured while basis is Total Hours.');
  }
  const seen = new Set<number>();
  tiers.forEach((t, i) => {
    const n = i + 1;
    if (t.thresholdHours < 0) errors.push(`Tier ${n}: threshold hours cannot be negative.`);
    if (seen.has(t.thresholdHours)) errors.push(`Tier ${n}: threshold ${t.thresholdHours}h duplicates another tier — thresholds must be distinct.`);
    seen.add(t.thresholdHours);
    if (t.deductionDays < 0) errors.push(`Tier ${n}: deduction cannot be negative.`);
  });
  return errors;
}

function sectionErrors(key: SectionKey, form: PenalizationPolicyRequest, currentEffectiveFrom: string | null): string[] {
  if (key === 'basicInfo') return basicInfoErrors(form.basicInfo, currentEffectiveFrom);
  if (key === 'workHoursShortage') return workHoursTierErrors(form.workHoursShortage.tiers, form.workHoursShortage.enabled);
  if (key === 'lateArrival') return lateHoursTierErrors(form.lateArrival.lateHoursTiers, form.lateArrival.enabled, form.lateArrival.basis);
  return [];
}

// ── Modal chrome — same fixed-overlay pattern as OrgSetupPage's ConfirmModal/AddEditModal ──
function ModalShell({ title, onClose, width = 480, children }: { title: string; onClose: () => void; width?: number; children: React.ReactNode }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={title} style={{
      position: 'fixed', inset: 0, zIndex: 220,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12,
        padding: '22px 26px', width, maxWidth: '95vw', maxHeight: '85vh', overflowY: 'auto',
        boxShadow: '0 24px 48px rgba(0,0,0,.4)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}>
            <X size={16} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '7px 0', borderBottom: '1px solid var(--line)', fontSize: 13 }}>
      <span style={{ color: 'var(--txt-mut)' }}>{label}</span>
      <span style={{ color: 'var(--txt)', fontWeight: 500, textAlign: 'right' }}>{value}</span>
    </div>
  );
}

// ── View (read-only) — used for viewing a specific historical/future version from Version History.
// Has its own section tab bar so one modal can show all five sections for that version. ──
function ViewModal({ policy, initialSection, onClose }: { policy: PenalizationPolicy; initialSection: SectionKey; onClose: () => void }) {
  const [section, setSection] = useState<SectionKey>(initialSection);
  return (
    <ModalShell title={`Version ${policy.version} — ${fmtDate(policy.effectiveFrom)}`} onClose={onClose} width={560}>
      <div style={{ display: 'flex', gap: 6, marginBottom: 14, borderBottom: '1px solid var(--line)', paddingBottom: 8, flexWrap: 'wrap' }}>
        {SECTIONS.map(s => (
          <button key={s} onClick={() => setSection(s)} style={{
            padding: '5px 10px', borderRadius: 6, fontSize: 12, fontWeight: section === s ? 600 : 400,
            background: section === s ? 'var(--raised)' : 'transparent',
            color: section === s ? 'var(--brand-bright)' : 'var(--txt-mut)',
            border: '1px solid ' + (section === s ? 'var(--line2)' : 'transparent'), cursor: 'pointer',
          }}>
            {SECTION_LABELS[s]}
          </button>
        ))}
      </div>
      {TOGGLEABLE_SECTIONS.includes(section) && (
        <div style={{ marginBottom: 12 }}>
          <EnabledBadge enabled={
            section === 'noAttendance' ? policy.noAttendance.enabled :
            section === 'lateArrival' ? policy.lateArrival.enabled :
            section === 'workHoursShortage' ? policy.workHoursShortage.enabled :
            policy.missingLogs.enabled
          } />
        </div>
      )}
      <DetailRow label="Policy version" value={`V${policy.version}`} />
      <DetailRow label="Effective from" value={fmtDate(policy.effectiveFrom)} />
      <DetailRow label="Effective to" value={policy.effectiveTo ? fmtDate(policy.effectiveTo) : 'Current'} />

      {section === 'basicInfo' && (
        <>
          <DetailRow label="Deduction method" value={policy.basicInfo.deductionMethod === 'PAID_LEAVE' ? 'Paid Leave' : 'Loss of Pay'} />
          {policy.basicInfo.deductionMethod === 'PAID_LEAVE' && (
            <DetailRow label="Leave priority order" value={policy.basicInfo.leavePriorityOrder.join(' → ') || 'Not configured'} />
          )}
          <DetailRow label="Buffer period" value={policy.basicInfo.bufferPeriodDays != null ? `${policy.basicInfo.bufferPeriodDays} day(s)` : 'None'} />
          <DetailRow label="Notice period forces LoP" value={policy.basicInfo.noticePeriodForcesLopEnabled ? 'Yes' : 'No'} />
        </>
      )}
      {section === 'noAttendance' && (
        <>
          <DetailRow label="Deduction" value={policy.noAttendance.deductionDays != null ? `${policy.noAttendance.deductionDays} day(s) per no-attendance day` : 'Not configured'} />
          <DetailRow label="No-show rule" value={policy.noAttendance.noShowEnabled ? `Below ${policy.noAttendance.noShowThresholdHours ?? '—'} effective hours` : 'Disabled'} />
          <DetailRow label="Adjoining holiday" value={policy.noAttendance.adjoiningHolidayEnabled
            ? `${policy.noAttendance.adjoiningHolidayCondition ?? 'ANY'} · threshold ${policy.noAttendance.adjoiningHolidayCalendarDayThreshold ?? 1} day(s) · ${policy.noAttendance.adjoiningHolidayIgnoreHalfDayLeave ? 'ignores' : 'counts'} half-day leave`
            : 'Disabled'} />
          <DetailRow label="Adjoining week-off" value={policy.noAttendance.adjoiningWeekoffEnabled
            ? `${policy.noAttendance.adjoiningWeekoffCondition ?? 'ANY'} · threshold ${policy.noAttendance.adjoiningWeekoffCalendarDayThreshold ?? 1} day(s) · ${policy.noAttendance.adjoiningWeekoffIgnoreHalfDayLeave ? 'ignores' : 'counts'} half-day leave`
            : 'Disabled'} />
        </>
      )}
      {section === 'lateArrival' && (
        <>
          <DetailRow label="Basis" value={policy.lateArrival.basis === 'TOTAL_HOURS' ? 'Total hours' : 'Number of incidents'} />
          {policy.lateArrival.basis === 'TOTAL_HOURS' ? (
            <>
              {/* Only this basis still consults the policy's own grace period server-side — the
                  Number of Incidents basis is governed entirely by the employee's assigned Shift's
                  own allowed-late privilege (one allowed-late grace, not a second one here). */}
              <DetailRow label="Grace period" value={policy.lateArrival.gracePeriodMinutes != null ? `${policy.lateArrival.gracePeriodMinutes} min(s) every shift` : 'Not configured'} />
              <DetailRow label="Allowed hours" value={policy.lateArrival.allowedHours != null ? `${policy.lateArrival.allowedHours}h / ${policy.lateArrival.exemptPeriod.toLowerCase()}` : 'Not configured'} />
              {policy.lateArrival.lateHoursTiers.length === 0 ? (
                <DetailRow label="Tiers" value="Not configured" />
              ) : policy.lateArrival.lateHoursTiers.map((t, i) => (
                <DetailRow key={i} label={`Greater than ${t.thresholdHours}h`} value={`${t.deductionDays} day(s)`} />
              ))}
            </>
          ) : (
            <>
              <DetailRow label="Exempt" value={policy.lateArrival.exemptCount != null ? `${policy.lateArrival.exemptCount} late arrival(s) / ${policy.lateArrival.exemptPeriod.toLowerCase()}` : 'Not configured'} />
              <DetailRow label="Deduction" value={policy.lateArrival.deductionDays != null ? `${policy.lateArrival.deductionDays} day(s) per ${policy.lateArrival.deductionPerShifts ?? 1} shift(s)` : 'Not configured'} />
              {policy.lateArrival.lateHoursTiers.length > 0 && (
                <DetailRow label="Also: total late hours tiers" value={policy.lateArrival.lateHoursTiers.map(t => `>${t.thresholdHours}h → ${t.deductionDays}d`).join(', ')} />
              )}
              {policy.lateArrival.lateHoursTiers.length > 0 && (
                <DetailRow label="When both incident and total-hours thresholds are exceeded" value={policy.lateArrival.combinedRuleBehavior === 'BOTH' ? 'Apply both' : 'Total hours only'} />
              )}
            </>
          )}
          <DetailRow label="Ignore if effective hours met" value={policy.lateArrival.ignoreWhenEffectiveHoursMetEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Penalise if caused by missing log" value={policy.lateArrival.penaliseWhenCausedByMissingLogEnabled ? 'Yes' : 'No (default)'} />
        </>
      )}
      {section === 'workHoursShortage' && (
        <>
          <DetailRow label="Deduction basis" value={policy.workHoursShortage.deductionBasis === 'GROSS_HOURS' ? 'Gross Hours' : 'Effective Hours'} />
          <DetailRow label="Frequency" value={
            policy.workHoursShortage.deductionPeriod === 'WEEK' ? 'Weekly' :
            policy.workHoursShortage.deductionPeriod === 'MONTH' ? 'Monthly' : 'Daily'
          } />
          {policy.workHoursShortage.tiers.length === 0 ? (
            <DetailRow label="Tiers" value="Not configured" />
          ) : policy.workHoursShortage.tiers.map((t, i) => (
            <DetailRow key={i} label={`Less than ${t.thresholdPercent}% of shift hours`} value={`${t.deductionDays} day(s)`} />
          ))}
          <DetailRow label="Apply penalty for shortage (same-day overlap)" value={policy.workHoursShortage.applyPenaltyForShortageEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Also apply late-arrival penalty (same-day overlap)" value={policy.workHoursShortage.applyPenaltyForLateArrivalEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Exclude hours outside shift timing" value={policy.workHoursShortage.excludeHoursOutsideShiftEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Penalize shortage caused by missing logs" value={policy.workHoursShortage.penalizeShortageCausedByMissingLogsEnabled ? 'Yes' : 'No'} />
        </>
      )}
      {section === 'missingLogs' && (
        <>
          <DetailRow label="Exempt" value={policy.missingLogs.exemptDays != null ? `${policy.missingLogs.exemptDays} day(s) / ${policy.missingLogs.exemptPeriod.toLowerCase()}` : 'Not configured'} />
          <DetailRow label="Deduction mode" value={policy.missingLogs.deductionMode === 'PER_SHIFT' ? 'Per shift of missing logs' : 'Irrespective of missed logs'} />
          <DetailRow label="Deduction" value={policy.missingLogs.deductionDays != null ? `${policy.missingLogs.deductionDays} day(s)${policy.missingLogs.deductionMode === 'PER_SHIFT' ? ` per ${policy.missingLogs.deductionPerShifts ?? 1} shift(s)` : ''}` : 'Not configured'} />
          <DetailRow label="Ignore rule" value={policy.missingLogs.ignoreRuleEnabled ? `Above ${policy.missingLogs.ignoreRuleThresholdPercent ?? '—'}% effective hours` : 'Disabled'} />
        </>
      )}
    </ModalShell>
  );
}

// ── Version History ──
function VersionsModal({ token, policyId, onClose }: { token: string; policyId?: string; onClose: () => void }) {
  const [versions, setVersions] = useState<PenalizationPolicyVersionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [viewing, setViewing] = useState<PenalizationPolicy | null>(null);
  const [viewError, setViewError] = useState('');

  useEffect(() => {
    let cancelled = false;
    getPolicyVersions(token, policyId)
      .then(v => { if (!cancelled) setVersions(v.sort((a, b) => b.version - a.version)); })
      .catch(e => { if (!cancelled) setLoadError(e instanceof Error ? e.message : 'Failed to load versions'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, policyId]);

  const now = new Date().toISOString();
  function statusOf(v: PenalizationPolicyVersionSummary): { label: string; bg: string; fg: string } {
    if (v.effectiveTo == null && v.effectiveFrom <= now) return { label: 'Current', bg: 'rgba(47,182,124,.15)', fg: 'var(--ok)' };
    if (v.effectiveFrom > now) return { label: 'Future', bg: 'rgba(99,102,241,.18)', fg: 'var(--brand-bright)' };
    return { label: 'Historical', bg: 'rgba(107,114,128,.15)', fg: 'var(--txt-dim)' };
  }

  async function view(id: string) {
    setViewError('');
    try {
      setViewing(await getPolicyVersion(token, id));
    } catch (e) {
      setViewError(e instanceof Error ? e.message : 'Failed to load version');
    }
  }

  return (
    <ModalShell title="Version History" onClose={onClose} width={560}>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13, padding: '12px 0' }}>Loading…</div>
      ) : loadError ? (
        <div role="alert" style={{ color: 'var(--risk)', fontSize: 13 }}>{loadError}</div>
      ) : versions.length === 0 ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13, padding: '12px 0' }}>No versions saved yet.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {viewError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5 }}>{viewError}</div>}
          {versions.map(v => {
            const s = statusOf(v);
            return (
              <div key={v.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 12px', border: '1px solid var(--line2)', borderRadius: 8, background: 'var(--raised)' }}>
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', width: 44 }}>V{v.version}</span>
                <span style={{ flex: 1, fontSize: 12.5, color: 'var(--txt-mut)' }}>
                  Effective {fmtDate(v.effectiveFrom)}{v.effectiveTo ? ` → ${fmtDate(v.effectiveTo)}` : ''}
                </span>
                <span style={{ display: 'inline-flex', padding: '2px 8px', borderRadius: 20, fontSize: 10.5, fontWeight: 700, background: s.bg, color: s.fg }}>{s.label}</span>
                <button type="button" onClick={() => view(v.id)} style={{ padding: '5px 10px', background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
                  View
                </button>
              </div>
            );
          })}
        </div>
      )}
      {viewing && <ViewModal policy={viewing} initialSection="basicInfo" onClose={() => setViewing(null)} />}
    </ModalShell>
  );
}

// ── Method-selection wizard step (Section 1/2 of the Keka flow) ──
function MethodSelectionStep({ onContinue }: { onContinue: (selected: SectionKey[]) => void }) {
  const [selected, setSelected] = useState<Set<SectionKey>>(new Set());
  const [focused, setFocused] = useState<SectionKey>('noAttendance');

  function toggle(key: SectionKey) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
    setFocused(key);
  }

  return (
    <div>
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        Select the penalization methods this policy should enforce. You can add more later from the configuration screen.
      </div>
      <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 240px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {TOGGLEABLE_SECTIONS.map(key => (
            <label key={key} onMouseEnter={() => setFocused(key)} style={{
              display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
              border: '1px solid ' + (selected.has(key) ? 'var(--brand)' : 'var(--line2)'),
              background: selected.has(key) ? 'rgba(99,102,241,.08)' : 'var(--raised)',
            }}>
              <input type="checkbox" checked={selected.has(key)} onChange={() => toggle(key)} />
              <span style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{SECTION_LABELS[key]}</span>
            </label>
          ))}
        </div>
        <div style={{ flex: '1 1 240px', ...helpTextStyle }}>
          {METHOD_HELP[focused]}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 18 }}>
        <button type="button" disabled={selected.size === 0} onClick={() => onContinue([...selected])} style={{
          padding: '8px 20px', background: selected.size === 0 ? 'var(--raised)' : 'var(--brand)',
          border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 600,
          color: selected.size === 0 ? 'var(--txt-dim)' : '#fff', cursor: selected.size === 0 ? 'not-allowed' : 'pointer',
        }}>
          Continue
        </button>
      </div>
    </div>
  );
}

const EMPTY_REQUEST: PenalizationPolicyRequest = {
  basicInfo: { deductionMethod: 'LOSS_OF_PAY', leavePriorityOrder: [], bufferPeriodDays: null, noticePeriodForcesLopEnabled: false, requestedEffectiveFrom: null },
  noAttendance: {
    enabled: false, deductionDays: null, noShowEnabled: false, noShowThresholdHours: null,
    adjoiningHolidayEnabled: false, adjoiningHolidayCondition: null, adjoiningHolidayCalendarDayThreshold: 1, adjoiningHolidayIgnoreHalfDayLeave: true,
    adjoiningWeekoffEnabled: false, adjoiningWeekoffCondition: null, adjoiningWeekoffCalendarDayThreshold: 1, adjoiningWeekoffIgnoreHalfDayLeave: true,
  },
  lateArrival: {
    enabled: false, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: null, exemptCount: null, exemptPeriod: 'MONTH',
    deductionDays: null, deductionPerShifts: null, ignoreWhenEffectiveHoursMetEnabled: false,
    allowedHours: null, lateHoursTiers: [], combinedRuleBehavior: 'TOTAL_HOURS_ONLY', penaliseWhenCausedByMissingLogEnabled: false,
  },
  workHoursShortage: {
    enabled: false, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'DAY', tiers: [],
    applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false,
    excludeHoursOutsideShiftEnabled: false, penalizeShortageCausedByMissingLogsEnabled: false,
  },
  missingLogs: { enabled: false, exemptDays: null, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: null, deductionPerShifts: null, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
};

function toRequest(p: PenalizationPolicy | null): PenalizationPolicyRequest {
  if (!p) return EMPTY_REQUEST;
  return {
    // requestedEffectiveFrom is a save-time directive, never echoed back by the backend (the
    // version's actual resolved date is p.effectiveFrom at the top level) — always starts blank.
    basicInfo: { ...p.basicInfo, leavePriorityOrder: [...p.basicInfo.leavePriorityOrder], requestedEffectiveFrom: null },
    noAttendance: { ...p.noAttendance },
    lateArrival: { ...p.lateArrival, lateHoursTiers: p.lateArrival.lateHoursTiers.map(t => ({ ...t })) },
    workHoursShortage: { ...p.workHoursShortage, tiers: p.workHoursShortage.tiers.map(t => ({ ...t })) },
    missingLogs: { ...p.missingLogs },
  };
}

function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

// ── Discard-with-unsaved-changes confirmation ──
export function ConfirmDiscardModal({ onKeepEditing, onDiscard }: { onKeepEditing: () => void; onDiscard: () => void }) {
  return (
    <div role="dialog" aria-modal="true" aria-label="Discard unsaved changes?" style={{
      position: 'fixed', inset: 0, zIndex: 230, display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
    }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 380, maxWidth: '95vw' }}>
        <h2 style={{ margin: '0 0 10px', fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>Discard unsaved changes?</h2>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: 'var(--txt-mut)' }}>You have unsaved edits in this policy configuration. Leaving now will discard them.</p>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button type="button" onClick={onKeepEditing} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Keep editing</button>
          <button type="button" onClick={onDiscard} style={{ padding: '7px 16px', background: 'var(--risk)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer' }}>Discard</button>
        </div>
      </div>
    </div>
  );
}

// ── Configuration workspace — Keka-style left (sections + status) / right (active section) layout.
// Every Save persists the WHOLE policy document via the one backend PUT endpoint (there is no
// per-section save API) — a section's own Save button saves everything currently in `form`, which
// is exactly why switching sections never loses edits: they all live in one shared `form` object
// the whole time, and any pending edit anywhere is included the next time Save is pressed. ──
function ConfigurationWorkspace({ policy, token, leaveTypes, policyId, initialVisibleSections, onSaved, onOpenVersions, onDirtyChange }: {
  policy: PenalizationPolicy | null; token: string; leaveTypes: LeaveType[]; policyId?: string;
  initialVisibleSections: SectionKey[];
  onSaved: (p: PenalizationPolicy) => void;
  onOpenVersions: () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const { showToast } = useToast();
  const [savedSnapshot, setSavedSnapshot] = useState<PenalizationPolicyRequest>(() => toRequest(policy));
  const [form, setForm] = useState<PenalizationPolicyRequest>(() => {
    const base = toRequest(policy);
    // Sections chosen just now in the wizard start pre-enabled — selecting a method implies
    // wanting it active; the admin can still flip it off inside the section.
    for (const key of initialVisibleSections) {
      if (TOGGLEABLE_SECTIONS.includes(key)) (base as any)[key] = { ...(base as any)[key], enabled: true };
    }
    return base;
  });
  const [visibleSections, setVisibleSections] = useState<SectionKey[]>(['basicInfo', ...initialVisibleSections]);
  const [tab, setTab] = useState<SectionKey>(initialVisibleSections[0] ?? 'basicInfo');
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);
  const [addMenuOpen, setAddMenuOpen] = useState(false);

  const currentEffectiveFrom = policy?.effectiveFrom ?? null;
  const dirty = useMemo(() => !deepEqual(form, savedSnapshot), [form, savedSnapshot]);
  const errorsBySection = useMemo(() => {
    const map = {} as Record<SectionKey, string[]>;
    for (const key of SECTIONS) map[key] = sectionErrors(key, form, currentEffectiveFrom);
    return map;
  }, [form, currentEffectiveFrom]);
  const hasAnyError = SECTIONS.some(k => errorsBySection[k].length > 0);

  // Warn before an actual tab/browser close with unsaved edits — never a silent reset.
  useEffect(() => {
    function handler(e: BeforeUnloadEvent) {
      if (dirty) { e.preventDefault(); e.returnValue = ''; }
    }
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  // Reports dirty state to the enclosing modal (PolicyListSection's EditPolicyModal) so its own
  // close (X) button can confirm before discarding — this component has no close button of its own.
  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

  function patch<K extends SectionKey>(key: K, patchValue: Partial<PenalizationPolicyRequest[K]>) {
    setForm(f => ({ ...f, [key]: { ...f[key], ...patchValue } }));
  }

  function statusOf(key: SectionKey): SectionStatus {
    if (errorsBySection[key].length > 0) return 'error';
    if (!deepEqual(form[key], savedSnapshot[key])) return 'unsaved';
    if (!policy) return 'draft';
    return 'saved';
  }

  function addMethod(key: SectionKey) {
    setVisibleSections(v => v.includes(key) ? v : [...v, key]);
    setTab(key);
    setAddMenuOpen(false);
  }

  async function handleSave() {
    if (hasAnyError) {
      setSaveError('Fix the highlighted validation errors before saving.');
      return;
    }
    setSaveError('');
    setSaving(true);
    try {
      const saved = await savePolicy(token, form, policyId);
      onSaved(saved);
      const nextForm = toRequest(saved);
      setSavedSnapshot(nextForm);
      setForm(nextForm);
      showToast('success', `Saved — version ${saved.version}, effective ${fmtDate(saved.effectiveFrom)}`);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Failed to save policy');
    } finally {
      setSaving(false);
    }
  }

  const basicInfo = form.basicInfo, na = form.noAttendance, la = form.lateArrival, whs = form.workHoursShortage, ml = form.missingLogs;

  function setTier(i: number, field: keyof WorkHoursTier, value: number) {
    const tiers = whs.tiers.map((t, idx) => idx === i ? { ...t, [field]: value } : t);
    patch('workHoursShortage', { tiers });
  }
  function addTier() {
    patch('workHoursShortage', { tiers: [...whs.tiers, { thresholdPercent: 0, deductionDays: 0 }] });
  }
  function removeTier(i: number) {
    patch('workHoursShortage', { tiers: whs.tiers.filter((_, idx) => idx !== i) });
  }

  function setLateHoursTier(i: number, field: keyof LateHoursTier, value: number) {
    const tiers = la.lateHoursTiers.map((t, idx) => idx === i ? { ...t, [field]: value } : t);
    patch('lateArrival', { lateHoursTiers: tiers });
  }
  function addLateHoursTier() {
    patch('lateArrival', { lateHoursTiers: [...la.lateHoursTiers, { thresholdHours: 0, deductionDays: 0 }] });
  }
  function removeLateHoursTier(i: number) {
    patch('lateArrival', { lateHoursTiers: la.lateHoursTiers.filter((_, idx) => idx !== i) });
  }

  function moveLeaveType(i: number, direction: -1 | 1) {
    const order = [...basicInfo.leavePriorityOrder];
    const j = i + direction;
    if (j < 0 || j >= order.length) return;
    [order[i], order[j]] = [order[j], order[i]];
    patch('basicInfo', { leavePriorityOrder: order });
  }
  function removeLeaveType(code: string) {
    patch('basicInfo', { leavePriorityOrder: basicInfo.leavePriorityOrder.filter(c => c !== code) });
  }
  function addLeaveType(code: string) {
    if (!code || basicInfo.leavePriorityOrder.includes(code)) return;
    patch('basicInfo', { leavePriorityOrder: [...basicInfo.leavePriorityOrder, code] });
  }

  const hiddenMethods = TOGGLEABLE_SECTIONS.filter(k => !visibleSections.includes(k));
  const activeErrors = errorsBySection[tab];

  return (
    <div>
      {policy && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
            Version {policy.version} · Effective from {fmtDate(policy.effectiveFrom)}
            {policy.effectiveTo ? ` to ${fmtDate(policy.effectiveTo)}` : ' (current)'}
          </div>
          <button type="button" onClick={onOpenVersions} style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            <History size={13} /> Version History
          </button>
        </div>
      )}

      <div style={{ display: 'flex', gap: 18, alignItems: 'flex-start' }}>
        {/* Left panel */}
        <div style={{ width: 200, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {visibleSections.map(key => (
            <button key={key} type="button" onClick={() => setTab(key)} style={{
              display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3,
              padding: '9px 10px', borderRadius: 8, textAlign: 'left', cursor: 'pointer',
              background: tab === key ? 'var(--raised)' : 'transparent',
              border: '1px solid ' + (tab === key ? 'var(--line2)' : 'transparent'),
            }}>
              <span style={{ fontSize: 12.5, fontWeight: tab === key ? 700 : 500, color: tab === key ? 'var(--brand-bright)' : 'var(--txt)' }}>
                {SECTION_LABELS[key]}
              </span>
              <SectionStatusBadge status={statusOf(key)} />
            </button>
          ))}

          {hiddenMethods.length > 0 && (
            <div style={{ position: 'relative', marginTop: 6 }}>
              <button type="button" onClick={() => setAddMenuOpen(o => !o)} style={{
                display: 'flex', alignItems: 'center', gap: 5, width: '100%', padding: '7px 10px',
                background: 'transparent', border: '1px dashed var(--line2)', borderRadius: 8,
                fontSize: 12, color: 'var(--brand-bright)', cursor: 'pointer',
              }}>
                <Plus size={13} /> Add Penalization Method <ChevronDown size={12} style={{ marginLeft: 'auto' }} />
              </button>
              {addMenuOpen && (
                <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 8, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 10 }}>
                  {hiddenMethods.map(key => (
                    <button key={key} type="button" onClick={() => addMethod(key)} style={{
                      display: 'block', width: '100%', textAlign: 'left', padding: '8px 12px',
                      background: 'transparent', border: 'none', fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer',
                    }}>
                      {SECTION_LABELS[key]}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Right panel */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={helpTextStyle}>{METHOD_HELP[tab]}</div>

          {saveError && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', color: 'var(--risk)', fontSize: 12.5 }}>
              {saveError}
            </div>
          )}
          {activeErrors.length > 0 && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 6, padding: '8px 12px', color: 'var(--risk)', fontSize: 12 }}>
              {activeErrors.map((e, i) => <div key={i}>• {e}</div>)}
            </div>
          )}

          {TOGGLEABLE_SECTIONS.includes(tab) && (
            <label style={checkboxRow}>
              <input type="checkbox" checked={
                tab === 'noAttendance' ? na.enabled : tab === 'lateArrival' ? la.enabled :
                tab === 'workHoursShortage' ? whs.enabled : ml.enabled
              } onChange={e => patch(tab, { enabled: e.target.checked } as any)} />
              Enabled
            </label>
          )}

          {tab === 'basicInfo' && (
            <>
              <div>
                <span style={labelText}>Deduction method</span>
                <select style={inputStyle} value={basicInfo.deductionMethod}
                  onChange={e => patch('basicInfo', { deductionMethod: e.target.value as any })}>
                  <option value="LOSS_OF_PAY">Loss of Pay — every penalty is Loss of Pay</option>
                  <option value="PAID_LEAVE">Paid Leave — deduct from configured leave types</option>
                </select>
              </div>
              {basicInfo.deductionMethod === 'PAID_LEAVE' && (
                <div>
                  <span style={labelText}>Leave deduction order (top exhausted first, remainder becomes Loss of Pay)</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {basicInfo.leavePriorityOrder.map((code, i) => {
                      const lt = leaveTypes.find(t => t.code === code);
                      return (
                        <div key={code} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px' }}>
                          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', width: 18 }}>{i + 1}.</span>
                          <span style={{ flex: 1, fontSize: 13, color: 'var(--txt)' }}>{lt?.name ?? code}</span>
                          <button type="button" onClick={() => moveLeaveType(i, -1)} disabled={i === 0} style={{ background: 'transparent', border: 'none', cursor: i === 0 ? 'not-allowed' : 'pointer', color: 'var(--txt-mut)' }}>↑</button>
                          <button type="button" onClick={() => moveLeaveType(i, 1)} disabled={i === basicInfo.leavePriorityOrder.length - 1} style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)' }}>↓</button>
                          <button type="button" onClick={() => removeLeaveType(code)} style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--risk)' }}>Remove</button>
                        </div>
                      );
                    })}
                  </div>
                  {leaveTypes.filter(t => !basicInfo.leavePriorityOrder.includes(t.code)).length > 0 && (
                    <select style={{ ...inputStyle, marginTop: 8 }} value=""
                      onChange={e => addLeaveType(e.target.value)}>
                      <option value="">+ Add leave type…</option>
                      {leaveTypes.filter(t => !basicInfo.leavePriorityOrder.includes(t.code)).map(t => (
                        <option key={t.code} value={t.code}>{t.name}</option>
                      ))}
                    </select>
                  )}
                </div>
              )}
              <div>
                <span style={labelText}>Buffer period (days an employee has to self-correct before a penalty is finalized)</span>
                <input type="number" min={0} style={inputStyle} value={basicInfo.bufferPeriodDays ?? ''}
                  onChange={e => patch('basicInfo', { bufferPeriodDays: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={basicInfo.noticePeriodForcesLopEnabled}
                  onChange={e => patch('basicInfo', { noticePeriodForcesLopEnabled: e.target.checked })} />
                If employee is under notice period, consider all penalties as Loss of Pay
              </label>
              <div>
                <span style={labelText}>Effective date (optional — leave blank for the 1st of next month)</span>
                <input type="date" min={todayIsoDate()} style={inputStyle}
                  value={basicInfo.requestedEffectiveFrom ?? ''}
                  onChange={e => patch('basicInfo', { requestedEffectiveFrom: e.target.value || null })} />
                <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
                  {basicInfo.requestedEffectiveFrom
                    ? `This save becomes effective on ${fmtDate(basicInfo.requestedEffectiveFrom)}. Attendance before that date keeps using the current configuration.`
                    : 'Defaults to the 1st of next calendar month if left blank.'}
                </div>
              </div>
            </>
          )}

          {tab === 'noAttendance' && (
            <>
              <div>
                <span style={labelText}>Deduct (day(s) leave for every day of no attendance)</span>
                <input type="number" min={0} step={0.25} style={inputStyle} value={na.deductionDays ?? ''}
                  onChange={e => patch('noAttendance', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={na.noShowEnabled} onChange={e => patch('noAttendance', { noShowEnabled: e.target.checked })} />
                Employee working less than X effective hours is considered a no-show
              </label>
              {na.noShowEnabled && (
                <div>
                  <span style={labelText}>No-show threshold (effective hours)</span>
                  <input type="number" min={0} step={0.5} style={inputStyle} value={na.noShowThresholdHours ?? ''}
                    onChange={e => patch('noAttendance', { noShowThresholdHours: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
              )}

              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Adjoining Holiday Penalty</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={na.adjoiningHolidayEnabled}
                  onChange={e => patch('noAttendance', { adjoiningHolidayEnabled: e.target.checked })} />
                Also penalise the holiday itself when no-attendance days adjoin it
              </label>
              {na.adjoiningHolidayEnabled && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <span style={labelText}>Condition</span>
                    <select style={inputStyle} value={na.adjoiningHolidayCondition ?? 'ANY'}
                      onChange={e => patch('noAttendance', { adjoiningHolidayCondition: e.target.value as any })}>
                      <option value="SANDWICHED">Sandwiched (before AND after)</option>
                      <option value="BEFORE">No attendance immediately before</option>
                      <option value="AFTER">No attendance immediately after</option>
                      <option value="ANY">Before, after, or in-between (any)</option>
                    </select>
                  </div>
                  <div>
                    <span style={labelText}>No-attendance calendar-day threshold</span>
                    <input type="number" min={1} style={inputStyle} value={na.adjoiningHolidayCalendarDayThreshold ?? 1}
                      onChange={e => patch('noAttendance', { adjoiningHolidayCalendarDayThreshold: Number(e.target.value) })} />
                  </div>
                  <label style={{ ...checkboxRow, gridColumn: '1 / -1' }}>
                    <input type="checkbox" checked={na.adjoiningHolidayIgnoreHalfDayLeave}
                      onChange={e => patch('noAttendance', { adjoiningHolidayIgnoreHalfDayLeave: e.target.checked })} />
                    Ignore half-day leave (a half-day leave does not count as a no-attendance day)
                  </label>
                </div>
              )}

              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Adjoining Week-Off Penalty</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={na.adjoiningWeekoffEnabled}
                  onChange={e => patch('noAttendance', { adjoiningWeekoffEnabled: e.target.checked })} />
                Also penalise the week-off itself when no-attendance days adjoin it
              </label>
              {na.adjoiningWeekoffEnabled && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <span style={labelText}>Condition</span>
                    <select style={inputStyle} value={na.adjoiningWeekoffCondition ?? 'ANY'}
                      onChange={e => patch('noAttendance', { adjoiningWeekoffCondition: e.target.value as any })}>
                      <option value="SANDWICHED">Sandwiched (before AND after)</option>
                      <option value="BEFORE">No attendance immediately before</option>
                      <option value="AFTER">No attendance immediately after</option>
                      <option value="ANY">Before, after, or in-between (any)</option>
                    </select>
                  </div>
                  <div>
                    <span style={labelText}>No-attendance calendar-day threshold</span>
                    <input type="number" min={1} style={inputStyle} value={na.adjoiningWeekoffCalendarDayThreshold ?? 1}
                      onChange={e => patch('noAttendance', { adjoiningWeekoffCalendarDayThreshold: Number(e.target.value) })} />
                  </div>
                  <label style={{ ...checkboxRow, gridColumn: '1 / -1' }}>
                    <input type="checkbox" checked={na.adjoiningWeekoffIgnoreHalfDayLeave}
                      onChange={e => patch('noAttendance', { adjoiningWeekoffIgnoreHalfDayLeave: e.target.checked })} />
                    Ignore half-day leave (a half-day leave does not count as a no-attendance day)
                  </label>
                </div>
              )}
            </>
          )}

          {tab === 'lateArrival' && (
            <>
              <div>
                <span style={labelText}>Basis</span>
                <select style={inputStyle} value={la.basis} onChange={e => patch('lateArrival', { basis: e.target.value as any })}>
                  <option value="NUMBER_OF_INCIDENTS">Number of Incidents</option>
                  <option value="TOTAL_HOURS">Total Hours</option>
                </select>
              </div>
              {la.basis === 'TOTAL_HOURS' && (
                // Only this basis still consults this grace period server-side. Under Number of
                // Incidents, the employee's assigned Shift's own allowed-late privilege is the
                // sole grace — there is no separate policy-level one for this admin to configure.
                <div>
                  <span style={labelText}>Grace period (minutes every shift)</span>
                  <input type="number" min={0} style={inputStyle} value={la.gracePeriodMinutes ?? ''}
                    onChange={e => patch('lateArrival', { gracePeriodMinutes: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
              )}

              {la.basis === 'NUMBER_OF_INCIDENTS' && (
                <>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                    <div>
                      <span style={labelText}>Exempt late arrival(s)</span>
                      <input type="number" min={0} style={inputStyle} value={la.exemptCount ?? ''}
                        onChange={e => patch('lateArrival', { exemptCount: e.target.value === '' ? null : Number(e.target.value) })} />
                    </div>
                    <div>
                      <span style={labelText}>Per cycle</span>
                      <select style={inputStyle} value={la.exemptPeriod} onChange={e => patch('lateArrival', { exemptPeriod: e.target.value as any })}>
                        <option value="WEEK">Week</option>
                        <option value="MONTH">Month</option>
                      </select>
                    </div>
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                    <div>
                      <span style={labelText}>Post exemption, deduct (day(s))</span>
                      <input type="number" min={0} step={0.25} style={inputStyle} value={la.deductionDays ?? ''}
                        onChange={e => patch('lateArrival', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
                    </div>
                    <div>
                      <span style={labelText}>Per shift(s) of late arrival</span>
                      <input type="number" min={1} style={inputStyle} value={la.deductionPerShifts ?? ''}
                        onChange={e => patch('lateArrival', { deductionPerShifts: e.target.value === '' ? null : Number(e.target.value) })} />
                    </div>
                  </div>
                </>
              )}

              {la.basis === 'TOTAL_HOURS' && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <span style={labelText}>Allowed hours</span>
                    <input type="number" min={0} step={0.25} style={inputStyle} value={la.allowedHours ?? ''}
                      onChange={e => patch('lateArrival', { allowedHours: e.target.value === '' ? null : Number(e.target.value) })} />
                  </div>
                  <div>
                    <span style={labelText}>Per cycle</span>
                    <select style={inputStyle} value={la.exemptPeriod} onChange={e => patch('lateArrival', { exemptPeriod: e.target.value as any })}>
                      <option value="WEEK">Week</option>
                      <option value="MONTH">Month</option>
                    </select>
                  </div>
                </div>
              )}

              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Total Late Hours in Shift (progressive tiers)</div>
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
                Rule table evaluated against total late hours for the cycle — used directly when basis is Total Hours, and as an additional check alongside Number of Incidents. Deduction amounts do not need to increase with the threshold — each tier's amount is independent.
              </div>
              {la.lateHoursTiers.map((t, i) => (
                <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 10, alignItems: 'end' }}>
                  <div>
                    <span style={labelText}>Greater than X hours</span>
                    <input type="number" min={0} step={0.25} style={inputStyle} value={t.thresholdHours}
                      onChange={e => setLateHoursTier(i, 'thresholdHours', Number(e.target.value))} />
                  </div>
                  <div>
                    <span style={labelText}>Deduct (day(s))</span>
                    <input type="number" min={0} step={0.25} style={inputStyle} value={t.deductionDays}
                      onChange={e => setLateHoursTier(i, 'deductionDays', Number(e.target.value))} />
                  </div>
                  <button type="button" onClick={() => removeLateHoursTier(i)} style={{ padding: '7px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--risk)', cursor: 'pointer' }}>
                    Remove
                  </button>
                </div>
              ))}
              <button type="button" onClick={addLateHoursTier} style={{ alignSelf: 'flex-start', padding: '6px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--brand-bright)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
                + Add Tier
              </button>

              {la.basis === 'NUMBER_OF_INCIDENTS' && la.lateHoursTiers.length > 0 && (
                <div>
                  <span style={labelText}>When both incident-count and total-hours thresholds are exceeded</span>
                  <select style={inputStyle} value={la.combinedRuleBehavior} onChange={e => patch('lateArrival', { combinedRuleBehavior: e.target.value as any })}>
                    <option value="TOTAL_HOURS_ONLY">Apply penalty for total hours only</option>
                    <option value="BOTH">Apply penalty for both</option>
                  </select>
                </div>
              )}

              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Advanced Settings</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={la.ignoreWhenEffectiveHoursMetEnabled}
                  onChange={e => patch('lateArrival', { ignoreWhenEffectiveHoursMetEnabled: e.target.checked })} />
                Ignore late arrival penalty when employee completes desired effective hours in a shift
              </label>
              <label style={checkboxRow}>
                <input type="checkbox" checked={la.penaliseWhenCausedByMissingLogEnabled}
                  onChange={e => patch('lateArrival', { penaliseWhenCausedByMissingLogEnabled: e.target.checked })} />
                Penalise any late arrival caused by missing logs (off by default — avoids double-penalising)
              </label>
            </>
          )}

          {tab === 'workHoursShortage' && (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Deduction basis</span>
                  <select style={inputStyle} value={whs.deductionBasis} onChange={e => patch('workHoursShortage', { deductionBasis: e.target.value as any })}>
                    <option value="EFFECTIVE_HOURS">Effective Hours (worked time, breaks excluded)</option>
                    <option value="GROSS_HOURS">Gross Hours (first check-in to last check-out)</option>
                  </select>
                </div>
                <div>
                  <span style={labelText}>Frequency</span>
                  <select style={inputStyle} value={whs.deductionPeriod} onChange={e => patch('workHoursShortage', { deductionPeriod: e.target.value as any })}>
                    <option value="DAY">Daily</option>
                    <option value="WEEK">Weekly</option>
                    <option value="MONTH">Monthly</option>
                  </select>
                </div>
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
                {whs.deductionPeriod === 'DAY'
                  ? 'Deduct leave for the shortage on a single day — add as many threshold rules as needed.'
                  : `Deduct leave for the shortage aggregated over the whole ${whs.deductionPeriod === 'WEEK' ? 'week (Monday-Sunday)' : 'calendar month'} — evaluated once at the end of each cycle, against the same tiers below.`}
              </div>
              {whs.tiers.map((t, i) => (
                <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 10, alignItems: 'end' }}>
                  <div>
                    <span style={labelText}>Less than X% of shift hours</span>
                    <input type="number" min={0} max={100} style={inputStyle} value={t.thresholdPercent}
                      onChange={e => setTier(i, 'thresholdPercent', Number(e.target.value))} />
                  </div>
                  <div>
                    <span style={labelText}>Deduct (day(s))</span>
                    <input type="number" min={0} step={0.25} style={inputStyle} value={t.deductionDays}
                      onChange={e => setTier(i, 'deductionDays', Number(e.target.value))} />
                  </div>
                  <button type="button" onClick={() => removeTier(i)} style={{ padding: '7px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--risk)', cursor: 'pointer' }}>
                    Remove
                  </button>
                </div>
              ))}
              <button type="button" onClick={addTier} style={{ alignSelf: 'flex-start', padding: '6px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--brand-bright)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
                + Add Tier
              </button>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>When shortage and late arrival both occur the same day</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={whs.applyPenaltyForShortageEnabled}
                  onChange={e => patch('workHoursShortage', { applyPenaltyForShortageEnabled: e.target.checked })} />
                Apply penalty for effective hours shortage
              </label>
              <label style={checkboxRow}>
                <input type="checkbox" checked={whs.applyPenaltyForLateArrivalEnabled}
                  onChange={e => patch('workHoursShortage', { applyPenaltyForLateArrivalEnabled: e.target.checked })} />
                Apply penalty for late arrival
              </label>

              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Additional Settings</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={whs.excludeHoursOutsideShiftEnabled}
                  onChange={e => patch('workHoursShortage', { excludeHoursOutsideShiftEnabled: e.target.checked })} />
                Exclude hours worked outside the assigned shift timing (e.g. an early arrival or late departure)
              </label>
              <label style={checkboxRow}>
                <input type="checkbox" checked={whs.penalizeShortageCausedByMissingLogsEnabled}
                  onChange={e => patch('workHoursShortage', { penalizeShortageCausedByMissingLogsEnabled: e.target.checked })} />
                Penalize shortage caused by missing logs (a day with no check-out is otherwise never evaluated)
              </label>
            </>
          )}

          {tab === 'missingLogs' && (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Exempt day(s) of missing logs</span>
                  <input type="number" min={0} style={inputStyle} value={ml.exemptDays ?? ''}
                    onChange={e => patch('missingLogs', { exemptDays: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
                <div>
                  <span style={labelText}>Per cycle</span>
                  <select style={inputStyle} value={ml.exemptPeriod} onChange={e => patch('missingLogs', { exemptPeriod: e.target.value as any })}>
                    <option value="WEEK">Week</option>
                    <option value="MONTH">Month</option>
                  </select>
                </div>
              </div>
              <div>
                <span style={labelText}>Deduction mode</span>
                <select style={inputStyle} value={ml.deductionMode} onChange={e => patch('missingLogs', { deductionMode: e.target.value as any })}>
                  <option value="PER_SHIFT">Deduct per shift of missing logs</option>
                  <option value="IRRESPECTIVE">Deduct once, irrespective of missed-log count</option>
                </select>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Deduct (day(s))</span>
                  <input type="number" min={0} step={0.25} style={inputStyle} value={ml.deductionDays ?? ''}
                    onChange={e => patch('missingLogs', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
                {ml.deductionMode === 'PER_SHIFT' && (
                  <div>
                    <span style={labelText}>Per shift(s) of missing logs</span>
                    <input type="number" min={1} style={inputStyle} value={ml.deductionPerShifts ?? ''}
                      onChange={e => patch('missingLogs', { deductionPerShifts: e.target.value === '' ? null : Number(e.target.value) })} />
                  </div>
                )}
              </div>
              <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Additional Settings</div>
              <label style={checkboxRow}>
                <input type="checkbox" checked={ml.ignoreRuleEnabled} onChange={e => patch('missingLogs', { ignoreRuleEnabled: e.target.checked })} />
                Ignore missing logs rule when effective hours exceed X% of shift hours
              </label>
              {ml.ignoreRuleEnabled && (
                <div>
                  <span style={labelText}>Threshold (%)</span>
                  <input type="number" min={0} max={100} style={inputStyle} value={ml.ignoreRuleThresholdPercent ?? ''}
                    onChange={e => patch('missingLogs', { ignoreRuleThresholdPercent: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
              )}
              <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>
                Excluding hours worked outside shift timing, and linking missing-log detection to work-hour
                shortage, are not yet supported by the backend.
              </div>
            </>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'flex-end', marginTop: 8, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            {dirty && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginRight: 'auto' }}>Unsaved changes — Save persists the whole policy document.</span>}
            <button type="button" onClick={handleSave} disabled={saving || !dirty} style={{
              padding: '7px 18px', background: (saving || !dirty) ? 'var(--raised)' : 'var(--brand)', border: 'none', borderRadius: 6,
              fontSize: 12.5, fontWeight: 600, color: (saving || !dirty) ? 'var(--txt-dim)' : '#fff',
              cursor: (saving || !dirty) ? 'not-allowed' : 'pointer',
            }}>
              {saving ? 'Saving…' : `Save ${SECTION_LABELS[tab]}`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function PenalizationPolicySection({ token, policyId, onDirtyChange }: { token: string; policyId?: string; onDirtyChange?: (dirty: boolean) => void }) {
  const [policy, setPolicy] = useState<PenalizationPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [wizardSelection, setWizardSelection] = useState<SectionKey[] | null>(null);
  const [showVersions, setShowVersions] = useState(false);

  async function load() {
    setLoadError('');
    setLoading(true);
    try {
      const current = await getCurrentPolicy(token, policyId);
      setPolicy(current);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load Penalization Policy');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (token) load(); }, [token, policyId]);
  useEffect(() => { if (token) leaveApi.listTypes(token).then(setLeaveTypes).catch(() => {}); }, [token]);

  if (loading) {
    return <div style={{ padding: 24, color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {loadError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          {loadError}
        </div>
      )}

      {!policy && !wizardSelection ? (
        <MethodSelectionStep onContinue={setWizardSelection} />
      ) : (
        <ConfigurationWorkspace
          key={policy?.policyId ?? 'new'}
          policy={policy}
          token={token}
          leaveTypes={leaveTypes}
          policyId={policyId}
          initialVisibleSections={policy
            ? TOGGLEABLE_SECTIONS.filter(k => (policy as any)[k]?.enabled)
            : (wizardSelection ?? [])}
          onSaved={setPolicy}
          onOpenVersions={() => setShowVersions(true)}
          onDirtyChange={onDirtyChange}
        />
      )}

      {showVersions && (
        <VersionsModal token={token} policyId={policyId} onClose={() => setShowVersions(false)} />
      )}
    </div>
  );
}
