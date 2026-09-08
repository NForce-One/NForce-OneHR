package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyLateHoursTier;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one production {@link AttendancePolicyEngine} implementation. Reads the version effective
 * for {@code context.getAttendanceDate()} straight from {@link PenalizationPolicyVersionRepository}
 * on every call — no caching, no synchronization mechanism — so a Penalization Policy save in
 * Organization Masters is visible to the very next evaluation without a restart or deployment.
 *
 * <p>Every field below is either a <b>gate</b> (read here, decides NO_MATCH/EXEMPT/APPLY_PENALTY)
 * or an <b>amount</b> field (deduction days) — every {@code APPLY_PENALTY} decision carries the
 * matched rule's configured {@code deductionDays} on {@link PolicyDecision#getDeductionDays()},
 * which {@link AttendancePenaltyEvaluationService} copies onto
 * {@link com.nforce.onehr.entity.AttendancePenalty#getDeductionDays()} — a real, persisted
 * execution path, not stored-but-unused configuration. {@code mlDeductionMode}/
 * {@code mlDeductionPerShifts} ({@link #isMissingLogDeductionDueThisOccurrence}) and
 * {@code laDeductionPerShifts} ({@link #isLateArrivalDeductionDueThisOccurrence}) are both real
 * occurrence-batching gates, not cosmetic rate descriptions — with {@code deductionPerShifts}
 * defaulting to 1 (every pre-existing policy's implicit value), batching is a no-op and every
 * occurrence past the exempt count is penalized exactly as before. No field is stored and
 * silently ignored by this engine.
 *
 * <p><b>Phase 2 note on "Total Late Hours in Shift" (Section 31) vs "Total Hours" basis (Section
 * 25/29):</b> both are modeled as ONE mechanism here —
 * {@link com.nforce.onehr.entity.PenalizationPolicyLateHoursTier} is evaluated against
 * {@code lateMinutesTotalInPeriod} (the cycle-cumulative total), not a separate per-single-shift
 * figure. This is a deliberate simplification, not a partial build: modeling both as genuinely
 * distinct facts would need a second cumulative-vs-single-day tier table with no way to
 * distinguish their approved-screenshot examples. "Combined Late Arrival Rules" (Section 32)
 * "BOTH" behavior sums both matched amounts into the one persisted
 * {@link com.nforce.onehr.entity.AttendancePenalty} row — {@code AttendancePenaltyEvaluationService}'s
 * duplicate guard is keyed on (employee, date, discrepancy type) only, so two separate rows for
 * the same Late Arrival occurrence isn't a change this phase makes.</p>
 */
@Service
@RequiredArgsConstructor
public class ConfiguredAttendancePolicyEngine implements AttendancePolicyEngine {

    private final PenalizationPolicyVersionRepository versionRepository;
    private final PenalizationPolicyWorkHoursTierRepository tierRepository;
    private final PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext context) {
        List<PenalizationPolicyVersion> candidates = context.getAssignedPolicyId() != null
                ? versionRepository.findVersionsEffectiveAtForPolicy(
                        context.getAssignedPolicyId(), context.getAttendanceDate().atStartOfDay())
                : versionRepository.findVersionsEffectiveAt(context.getAttendanceDate().atStartOfDay());
        Optional<PenalizationPolicyVersion> effective = candidates.stream().findFirst();
        if (effective.isEmpty()) {
            return noMatch(null, null, "No Penalization Policy version is effective for this date.");
        }
        PenalizationPolicyVersion version = effective.get();

        if (context.getDiscrepancyType() == null) {
            return noMatch(version.getPolicyId(), version.getVersion(), "No discrepancy type given.");
        }
        return switch (context.getDiscrepancyType()) {
            case ExceptionType.NO_ATTENDANCE -> evaluateNoAttendance(version, context);
            case ExceptionType.LATE_ARRIVAL -> evaluateLateArrival(version, context);
            case ExceptionType.WORK_HOURS_SHORTAGE -> evaluateWorkHoursShortage(version, context);
            case ExceptionType.MISSING_PUNCH -> evaluateMissingLogs(version, context);
            default -> noMatch(version.getPolicyId(), version.getVersion(),
                    "No configured Penalization Policy section covers this discrepancy type.");
        };
    }

    private PolicyDecision evaluateNoAttendance(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isNoAttendanceEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "No Attendance section is disabled.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return decideApplyPenalty(v, ctx, v.getNaDeductionDays(), "No attendance was recorded for this working day.");
    }

    private PolicyDecision evaluateLateArrival(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isLateArrivalEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Late Arrival section is disabled.");
        }
        if (ctx.getLateMinutes() == null) {
            return configurationRequired(v, "lateMinutes fact is required to evaluate Late Arrival.");
        }
        // Deliberately NOT re-applying v.getLaGracePeriodMinutes() as a second, independently-
        // configured grace here. There is exactly one allowed-late privilege — the assigned Shift's
        // own ShiftVersion.lateGraceMinutes — and it is already the sole reason this evaluation is
        // even reached: ExceptionService only ever calls into this engine for a LATE_ARRIVAL
        // discrepancy once Attendance.status is genuinely LATE against that shift grace (see
        // ExceptionService#detectExceptions). Re-checking a second, separately configured grace
        // here would let a Penalization Policy silently override (widen OR narrow) the shift's own
        // privilege for the exact same occurrence — the two-grace duplication this engine used to
        // have, now unified onto the shift as the single source of truth.
        if (v.isLaIgnoreWhenEffectiveHoursMetEnabled()
                && ctx.getEffectiveHoursPercent() != null && ctx.getEffectiveHoursPercent() >= 100.0) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Employee completed full effective hours despite late arrival.");
        }
        if (ctx.isWorkHoursShortageAlsoOccurredSameDay() && v.isWorkHoursShortageEnabled()
                && !v.isWhsApplyPenaltyForLateArrivalEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Work Hours Shortage also occurred the same day and Late Arrival penalty is suppressed by configuration.");
        }
        // Section 33: a late arrival that's a byproduct of an unresolved missing log is exempt
        // from its own Late Arrival penalty unless the org explicitly opts in to penalising it too
        // (avoids double-penalising the same missing-punch incident under two sections).
        if (ctx.isLateArrivalCausedByMissingLog() && !v.isLaPenaliseWhenCausedByMissingLogEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Late arrival is caused by an unresolved missing log and this policy does not penalise that case.");
        }

        boolean incidentBasis = "NUMBER_OF_INCIDENTS".equals(v.getLaBasis()) || v.getLaBasis() == null;
        if (incidentBasis) {
            return evaluateLateArrivalByIncidents(v, ctx);
        }
        return evaluateLateArrivalByTotalHours(v, ctx);
    }

    private PolicyDecision evaluateLateArrivalByIncidents(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        // "Exempt N late arrival(s) in a Month" — lateArrivalCountInPeriod is this occurrence's
        // running count for the period, inclusive of itself (see PolicyEvaluationContext javadoc);
        // "Post N late arrivals, deduct..." means the (N+1)th occurrence is the first one penalized,
        // batched every laDeductionPerShifts occurrences past that point — see
        // isLateArrivalDeductionDueThisOccurrence.
        boolean incidentDeductionDue = isLateArrivalDeductionDueThisOccurrence(v, ctx);
        Optional<PenalizationPolicyLateHoursTier> matchedTotalHoursTier = matchTotalHoursTier(v, ctx);
        boolean totalHoursExceeded = matchedTotalHoursTier.isPresent();

        if (!incidentDeductionDue && !totalHoursExceeded) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Late arrival occurrence is within the exempt count for this period.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }

        // Section 32: both the incident-count and total-hours thresholds are exceeded for the
        // same occurrence — resolve per the configured combined-rule behavior. Only one
        // AttendancePenalty row is ever recorded per (employee, date, discrepancy type)
        // (AttendancePenaltyEvaluationService's duplicate guard), so "BOTH" combines both
        // configured amounts into that one row rather than attempting two separate rows.
        if (incidentDeductionDue && totalHoursExceeded) {
            BigDecimal totalHoursAmount = matchedTotalHoursTier.get().getDeductionDays();
            if ("BOTH".equals(v.getLaCombinedRuleBehavior())) {
                BigDecimal combined = (v.getLaDeductionDays() == null ? BigDecimal.ZERO : v.getLaDeductionDays())
                        .add(totalHoursAmount);
                return decideApplyPenalty(v, ctx, combined,
                        "Both the incident-count and total-late-hours thresholds are exceeded — combined per configuration.");
            }
            return decideApplyPenalty(v, ctx, totalHoursAmount,
                    "Both thresholds exceeded — total-late-hours tier governs per configuration.");
        }
        if (totalHoursExceeded) {
            return decideApplyPenalty(v, ctx, matchedTotalHoursTier.get().getDeductionDays(),
                    "Total late hours in the period exceed a configured tier.");
        }
        return decideApplyPenalty(v, ctx, v.getLaDeductionDays(), "Late minutes exceed the configured grace period.");
    }

    /**
     * Section 21: consumes {@code laDeductionPerShifts} — previously stored and versioned but
     * never read by this engine (unlike its Missing Logs sibling, {@code mlDeductionPerShifts},
     * already consumed by {@link #isMissingLogDeductionDueThisOccurrence}). Late Arrival has no
     * {@code laDeductionMode} toggle, so this always applies PER_SHIFT-style batching: with
     * {@code deductionPerShifts} defaulting to 1, this is identical to every pre-existing policy's
     * behavior (a deduction on every incident-basis occurrence past the exempt count), preserving
     * backward compatibility for policies saved before this field was consumed. Only gates the
     * plain incident-count case above — the total-late-hours tier match is its own independent
     * mechanism, not occurrence-counted.
     */
    private boolean isLateArrivalDeductionDueThisOccurrence(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        // Preserves the pre-existing incidentExceeded short-circuit exactly: no configured exempt
        // count (or no count fact available) always applies, regardless of the occurrence count's
        // actual value — unlike Missing Logs, Late Arrival's original gate never treated a null
        // exempt count as "0 exempt" (see the equivalent boolean this replaces, previously
        // `laExemptCount == null || count == null || count > laExemptCount`). Only once an exempt
        // count is actually configured does "occurrences past it" become a meaningful basis to
        // batch by deductionPerShifts.
        if (v.getLaExemptCount() == null || ctx.getLateArrivalCountInPeriod() == null) {
            return true;
        }
        int occurrencesPastExempt = ctx.getLateArrivalCountInPeriod() - v.getLaExemptCount();
        if (occurrencesPastExempt <= 0) {
            return false;
        }
        int perShifts = v.getLaDeductionPerShifts() != null && v.getLaDeductionPerShifts() > 0 ? v.getLaDeductionPerShifts() : 1;
        return occurrencesPastExempt % perShifts == 0;
    }

    private PolicyDecision evaluateLateArrivalByTotalHours(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (ctx.getLateMinutesTotalInPeriod() == null) {
            return configurationRequired(v, "lateMinutesTotalInPeriod fact is required for the Total Hours basis.");
        }
        if (v.getLaAllowedHours() == null) {
            return configurationRequired(v, "Allowed hours must be configured for the Total Hours basis.");
        }
        if (ctx.getLateMinutesTotalInPeriod() <= v.getLaAllowedHours().doubleValue() * 60) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Total late minutes for the period are within the allowed hours.");
        }
        Optional<PenalizationPolicyLateHoursTier> matched = matchTotalHoursTier(v, ctx);
        if (matched.isEmpty()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Total late hours do not fall within any configured tier.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return decideApplyPenalty(v, ctx, matched.get().getDeductionDays(), "Total late hours for the period exceed a configured tier.");
    }

    /** Most severe matching "greater than X hours" tier (highest threshold that still matches). */
    private Optional<PenalizationPolicyLateHoursTier> matchTotalHoursTier(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (ctx.getLateMinutesTotalInPeriod() == null) {
            return Optional.empty();
        }
        double totalHours = ctx.getLateMinutesTotalInPeriod() / 60.0;
        return lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId()).stream()
                .filter(t -> totalHours > t.getThresholdHours().doubleValue())
                .max(Comparator.comparing(PenalizationPolicyLateHoursTier::getThresholdHours));
    }

    private PolicyDecision evaluateWorkHoursShortage(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        // No Attendance's no-show-by-hours rule reclassifies a sufficiently-low-hours day as a
        // no-attendance occurrence rather than a shortage tier — evaluated first since it changes
        // which section's gate applies, not just which reason string is returned.
        if (v.isNoAttendanceEnabled() && v.isNaNoShowEnabled()
                && ctx.getWorkedMinutes() != null && v.getNaNoShowThresholdHours() != null
                && ctx.getWorkedMinutes() < v.getNaNoShowThresholdHours().doubleValue() * 60) {
            if (isRegularized(ctx)) {
                return exempt(v, "A pending or approved regularization covers this date.");
            }
            return decideApplyPenalty(v, ctx, v.getNaDeductionDays(), "Worked hours are below the configured no-show threshold — treated as no attendance.");
        }

        if (!v.isWorkHoursShortageEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Work Hours Shortage section is disabled.");
        }
        // Phase 3: distinct from effectiveHoursPercent above (which the no-show check just used,
        // deliberately unchanged) — this fact already honors the version's configured basis
        // (Effective/Gross), shift-exclusion, and daily/weekly/monthly frequency (see
        // WorkHoursShortageCalculationService). The engine itself still never derives it.
        if (ctx.getWorkHoursShortagePercent() == null) {
            return configurationRequired(v, "workHoursShortagePercent fact is required to evaluate Work Hours Shortage.");
        }
        if (ctx.isLateArrivalAlsoOccurredSameDay() && !v.isWhsApplyPenaltyForShortageEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Late Arrival also occurred the same day and Work Hours Shortage penalty is suppressed by configuration.");
        }

        List<PenalizationPolicyWorkHoursTier> tiers = tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(v.getId());
        Optional<PenalizationPolicyWorkHoursTier> matched = tiers.stream()
                .filter(t -> ctx.getWorkHoursShortagePercent() < t.getThresholdPercent().doubleValue())
                // Most severe matching tier (lowest threshold) — an employee below 50% also
                // qualifies for the "less than 90%" tier but the stricter one governs.
                .min(Comparator.comparing(PenalizationPolicyWorkHoursTier::getThresholdPercent));
        if (matched.isEmpty()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Work hours shortage percent does not fall below any configured tier.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        // The matched tier's own deduction, not a version-level field — a "less than 50%" match
        // deducts that tier's amount, not the "less than 90%" tier's.
        return decideApplyPenalty(v, ctx, matched.get().getDeductionDays(), "Work hours shortage percent is below a configured shortage tier.");
    }

    private PolicyDecision evaluateMissingLogs(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (!v.isMissingLogsEnabled()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Missing Logs section is disabled.");
        }
        if (v.isMlIgnoreRuleEnabled() && ctx.getEffectiveHoursPercent() != null && v.getMlIgnoreRuleThresholdPercent() != null
                && ctx.getEffectiveHoursPercent() > v.getMlIgnoreRuleThresholdPercent().doubleValue()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Effective hours percent exceeds the configured ignore-rule threshold.");
        }
        if (v.getMlExemptDays() != null && ctx.getMissingLogCountInPeriod() != null
                && ctx.getMissingLogCountInPeriod() <= v.getMlExemptDays()) {
            return noMatch(v.getPolicyId(), v.getVersion(), "Missing-log occurrence is within the exempt days for this period.");
        }
        if (!isMissingLogDeductionDueThisOccurrence(v, ctx)) {
            return noMatch(v.getPolicyId(), v.getVersion(),
                    "Missing-log occurrence does not fall on a configured deduction interval.");
        }
        if (isRegularized(ctx)) {
            return exempt(v, "A pending or approved regularization covers this date.");
        }
        return decideApplyPenalty(v, ctx, v.getMlDeductionDays(), "Missing-log occurrences exceed the configured exempt days for this period.");
    }

    /**
     * Consumes {@code mlDeductionMode}/{@code mlDeductionPerShifts} — previously stored and
     * versioned but never read by this engine. {@code IRRESPECTIVE} applies the configured
     * deduction exactly once per period, on the first occurrence past the exempt count;
     * {@code PER_SHIFT} (default, {@code deductionPerShifts} defaulting to 1) batches the
     * deduction every N occurrences past the exempt count — with N=1 this is identical to every
     * pre-existing policy's behavior (a deduction on every occurrence past the exempt count),
     * preserving backward compatibility for policies saved before this distinction existed.
     */
    private boolean isMissingLogDeductionDueThisOccurrence(PenalizationPolicyVersion v, PolicyEvaluationContext ctx) {
        if (ctx.getMissingLogCountInPeriod() == null) {
            return true; // no count fact available — fall back to the pre-existing "every occurrence" behavior
        }
        int exempt = v.getMlExemptDays() != null ? v.getMlExemptDays() : 0;
        int occurrencesPastExempt = ctx.getMissingLogCountInPeriod() - exempt;
        if (occurrencesPastExempt <= 0) {
            return false;
        }
        if ("IRRESPECTIVE".equals(v.getMlDeductionMode())) {
            return occurrencesPastExempt == 1;
        }
        int perShifts = v.getMlDeductionPerShifts() != null && v.getMlDeductionPerShifts() > 0 ? v.getMlDeductionPerShifts() : 1;
        return occurrencesPastExempt % perShifts == 0;
    }

    private boolean isRegularized(PolicyEvaluationContext ctx) {
        return ctx.isHasPendingRegularization() || ctx.isHasApprovedRegularization();
    }

    private PolicyDecision noMatch(java.util.UUID policyId, Integer version, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.NO_MATCH)
                .policyId(policyId).policyVersion(version).reason(reason).build();
    }

    private PolicyDecision exempt(PenalizationPolicyVersion v, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.EXEMPT)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).reason(reason).build();
    }

    /**
     * The final step before a match becomes a persisted penalty: gate on the configured buffer
     * period (Section 8), then resolve which deduction method actually applies — the
     * notice-period override (Section 9) always wins over the configured method.
     */
    private PolicyDecision decideApplyPenalty(PenalizationPolicyVersion v, PolicyEvaluationContext ctx,
                                               java.math.BigDecimal deductionDays, String reason) {
        if (v.getBufferPeriodDays() != null && v.getBufferPeriodDays() > 0
                && ctx.getEvaluationDate() != null
                && ctx.getEvaluationDate().isBefore(ctx.getAttendanceDate().plusDays(v.getBufferPeriodDays()))) {
            return bufferPending(v, "Buffer period of " + v.getBufferPeriodDays() + " day(s) has not elapsed yet.");
        }
        String deductionMethod = ctx.isUnderNoticePeriod() && v.isNoticePeriodForcesLopEnabled()
                ? "LOSS_OF_PAY" : v.getDeductionMethod();
        return PolicyDecision.builder().type(PolicyDecisionType.APPLY_PENALTY)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).deductionDays(deductionDays)
                .deductionMethod(deductionMethod).leavePriorityOrder(v.getLeavePriorityOrder())
                .reason(reason).build();
    }

    private PolicyDecision bufferPending(PenalizationPolicyVersion v, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.BUFFER_PENDING)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).reason(reason).build();
    }

    private PolicyDecision configurationRequired(PenalizationPolicyVersion v, String reason) {
        return PolicyDecision.builder().type(PolicyDecisionType.CONFIGURATION_REQUIRED)
                .policyId(v.getPolicyId()).policyVersion(v.getVersion()).reason(reason).build();
    }
}
