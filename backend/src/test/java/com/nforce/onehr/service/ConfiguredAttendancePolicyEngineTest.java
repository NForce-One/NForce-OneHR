package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The real {@link AttendancePolicyEngine} implementation. The
 * {@link #gracePeriodChange_changesEvaluationWithoutAnyCodeChange} test is the mandated critical
 * acceptance test: it proves Organization Masters configuration — not Java code — decides the
 * outcome for the identical attendance fact.
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredAttendancePolicyEngineTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;

    private ConfiguredAttendancePolicyEngine engine;

    private final UUID policyId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 15);

    private ConfiguredAttendancePolicyEngine newEngine() {
        return new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
    }

    private PenalizationPolicyVersion.PenalizationPolicyVersionBuilder baseVersion(int version) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(version)
                .effectiveFrom(LocalDateTime.of(2026, 8, 1, 0, 0));
    }

    private PolicyEvaluationContext.PolicyEvaluationContextBuilder baseContext(String discrepancyType) {
        return PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(date).discrepancyType(discrepancyType);
    }

    // ── 1. No policy ──
    @Test
    void noConfiguredPolicy_returnsNoMatch() {
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
        assertNull(decision.getPolicyId());
        assertNull(decision.getPolicyVersion());
    }

    // ── 2. Disabled section ──
    @Test
    void disabledSection_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).lateArrivalEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(30).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 3. Outside effective period ──
    @Test
    void outsideEffectivePeriod_returnsNoMatch() {
        // The repository query itself is what enforces "outside effective period" — simulate the
        // no-row-matches outcome a real query would give for a date before any version started.
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(30).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 4 & 5 (revised — unified grace model). There is exactly one allowed-late privilege,
    // ShiftVersion.lateGraceMinutes, already folded into Attendance.status/lateMinutes upstream
    // (ExceptionService only ever reaches this engine for a genuinely-LATE occurrence) —
    // laGracePeriodMinutes is no longer consulted here at all, so setting it can no longer change
    // the outcome for the same lateMinutes fact. ──
    @Test
    void genuineLateArrival_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laDeductionDays(new BigDecimal("0.5")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(policyId, decision.getPolicyId());
        assertEquals(1, decision.getPolicyVersion());
        assertEquals(new BigDecimal("0.5"), decision.getDeductionDays());
    }

    @Test
    void laGracePeriodMinutes_widerThanLateMinutes_noLongerExemptsTheOccurrence() {
        // Before the unified-grace fix, a policy-level grace WIDER than the raw late minutes
        // (15 > 12) used to exempt this occurrence outright — a second, independently-configured
        // grace overriding the shift's own. That field is no longer consulted for eligibility at
        // all: this occurrence only ever reaches the engine because ExceptionService already
        // determined it's genuinely LATE against the assigned shift's own grace, so it must apply.
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(15).laDeductionDays(new BigDecimal("0.5")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── MANDATORY ACCEPTANCE TEST (revised): a policy grace differing from the shift's own grace
    // must NOT alter Late Arrival eligibility — the shift's ShiftVersion.lateGraceMinutes is the
    // sole allowed-late privilege; laGracePeriodMinutes is no longer a second, competing grace. ──
    @Test
    void policyGraceChange_neverAltersLateArrivalEligibility_sameLateMinutesFact() {
        int lateMinutes = 12;
        PolicyEvaluationContext sameFactEveryTime = baseContext(ExceptionType.LATE_ARRIVAL).lateMinutes(lateMinutes).build();
        engine = newEngine();

        // V1: policy grace = 10 minutes.
        PenalizationPolicyVersion v1 = baseVersion(1).lateArrivalEnabled(true).laGracePeriodMinutes(10).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(v1));
        PolicyDecision decisionUnderV1 = engine.evaluate(sameFactEveryTime);
        assertEquals(PolicyDecisionType.APPLY_PENALTY, decisionUnderV1.getType());
        assertEquals(1, decisionUnderV1.getPolicyVersion());

        // HR widens the POLICY's own grace to 15 minutes — still no engine/attendance code change,
        // and (per the unified model) no effect on this occurrence's eligibility either: only the
        // assigned Shift's own grace can ever decide that, and it already did, upstream.
        PenalizationPolicyVersion v2 = baseVersion(2).lateArrivalEnabled(true).laGracePeriodMinutes(15).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(v2));

        PolicyDecision decisionUnderV2 = engine.evaluate(sameFactEveryTime);
        assertEquals(PolicyDecisionType.APPLY_PENALTY, decisionUnderV2.getType());
        assertEquals(2, decisionUnderV2.getPolicyVersion());

        // V1's own decision object is untouched by V2 existing — proves no shared mutable state.
        assertEquals(PolicyDecisionType.APPLY_PENALTY, decisionUnderV1.getType());
        assertEquals(1, decisionUnderV1.getPolicyVersion());
    }

    // ── 6 (restated). Non-matching rule: exempt count absorbs the occurrence ──
    @Test
    void withinExemptCount_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(2).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    @Test
    void beyondExemptCount_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(3).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── 6b. Late Arrival deduction rate (laDeductionPerShifts) ──
    @Test
    void lateArrival_noExplicitRate_deductsEveryOccurrencePastExempt() {
        // No laDeductionPerShifts set — must behave exactly like every pre-existing policy saved
        // before this field was consumed (backward compatibility).
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        assertEquals(PolicyDecisionType.APPLY_PENALTY, engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(3).build()).getType());
        assertEquals(PolicyDecisionType.APPLY_PENALTY, engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(4).build()).getType());
    }

    @Test
    void lateArrival_rateOfTwo_onlyDeductsOnEveryOtherOccurrencePastExempt() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(2).laDeductionPerShifts(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        // Occurrence #3 overall = 1st past exempt -> not a multiple of 2 -> no match.
        assertEquals(PolicyDecisionType.NO_MATCH, engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(3).build()).getType());
        // Occurrence #4 overall = 2nd past exempt -> multiple of 2 -> applies.
        assertEquals(PolicyDecisionType.APPLY_PENALTY, engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(4).build()).getType());
        // Occurrence #5 overall = 3rd past exempt -> not a multiple of 2 -> no match again.
        assertEquals(PolicyDecisionType.NO_MATCH, engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(5).build()).getType());
    }

    @Test
    void lateArrival_ratedBatching_stillDefersToATotalHoursTierMatchIndependently() {
        // laDeductionPerShifts gates only the plain incident-count case — a total-late-hours tier
        // match (a wholly separate mechanism) must still apply even on an occurrence the rate
        // would otherwise suppress.
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(0).laDeductionPerShifts(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId()))
                .thenReturn(List.of(com.nforce.onehr.entity.PenalizationPolicyLateHoursTier.builder()
                        .thresholdHours(new java.math.BigDecimal("1")).deductionDays(new java.math.BigDecimal("1")).build()));
        engine = newEngine();

        // Occurrence #1 overall = 1st past exempt -> not a multiple of 2 by rate alone, but 90
        // late minutes (1.5h) exceeds the 1-hour tier, so the tier match still applies.
        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(1).lateMinutesTotalInPeriod(90).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── 7. Work-hours shortage threshold affects evaluation ──
    @Test
    void effectiveHoursBelowTier_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build(),
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("50")).deductionDays(BigDecimal.ONE).sortOrder(1).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .workHoursShortagePercent(82.0).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        // 82% falls below the "less than 90%" tier only, not "less than 50%" — its own deduction, 0.5, applies.
        assertEquals(new BigDecimal("0.5"), decision.getDeductionDays());
    }

    @Test
    void effectiveHoursBelowBothTiers_usesMostSevereTiersDeduction() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build(),
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("50")).deductionDays(BigDecimal.ONE).sortOrder(1).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .workHoursShortagePercent(30.0).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        // 30% falls below both tiers — the stricter "less than 50%" tier's own deduction (1 day) governs.
        assertEquals(BigDecimal.ONE, decision.getDeductionDays());
    }

    @Test
    void effectiveHoursAboveEveryTier_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new BigDecimal("90")).deductionDays(new BigDecimal("0.5")).sortOrder(0).build()
        ));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.WORK_HOURS_SHORTAGE)
                .workHoursShortagePercent(95.0).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 8. Missing-log configuration affects evaluation ──
    @Test
    void missingLogsBeyondExemptDays_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(5).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH)
                .missingLogCountInPeriod(6).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    @Test
    void missingLogsWithinExemptDays_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(5).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH)
                .missingLogCountInPeriod(3).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── 8b. Missing-log deduction rate (mlDeductionMode/mlDeductionPerShifts) ──
    @Test
    void missingLogs_defaultPerShiftModeWithNoExplicitRate_deductsEveryOccurrencePastExempt() {
        // No mlDeductionMode/mlDeductionPerShifts set — must behave exactly like every
        // pre-existing policy saved before this distinction existed (backward compatibility).
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        assertEquals(PolicyDecisionType.APPLY_PENALTY,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(3).build()).getType());
        assertEquals(PolicyDecisionType.APPLY_PENALTY,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(4).build()).getType());
    }

    @Test
    void missingLogs_perShiftModeWithRateOfTwo_onlyDeductsOnEveryOtherOccurrencePastExempt() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(2)
                .mlDeductionMode("PER_SHIFT").mlDeductionPerShifts(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        // Occurrence #3 overall = 1st past exempt -> not a multiple of 2 -> no match.
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(3).build()).getType());
        // Occurrence #4 overall = 2nd past exempt -> multiple of 2 -> applies.
        assertEquals(PolicyDecisionType.APPLY_PENALTY,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(4).build()).getType());
        // Occurrence #5 overall = 3rd past exempt -> not a multiple of 2 -> no match again.
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(5).build()).getType());
    }

    @Test
    void missingLogs_irrespectiveMode_deductsOnlyOnceForTheWholePeriod() {
        PenalizationPolicyVersion version = baseVersion(1).missingLogsEnabled(true).mlExemptDays(2)
                .mlDeductionMode("IRRESPECTIVE").build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        // First occurrence past exempt -> the one and only deduction for the period.
        assertEquals(PolicyDecisionType.APPLY_PENALTY,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(3).build()).getType());
        // Every subsequent occurrence in the same period -> no further deduction.
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(4).build()).getType());
        assertEquals(PolicyDecisionType.NO_MATCH,
                engine.evaluate(baseContext(ExceptionType.MISSING_PUNCH).missingLogCountInPeriod(7).build()).getType());
    }

    // ── 9. No-attendance configuration affects evaluation ──
    @Test
    void noAttendanceEnabled_appliesPenaltyPerOccurrence() {
        PenalizationPolicyVersion version = baseVersion(1).noAttendanceEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── Supported exemption: approved regularization -> EXEMPT ──
    @Test
    void approvedRegularization_returnsExempt() {
        PenalizationPolicyVersion version = baseVersion(1).noAttendanceEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .hasApprovedRegularization(true).build());

        assertEquals(PolicyDecisionType.EXEMPT, decision.getType());
        assertEquals(1, decision.getPolicyVersion());
    }

    // ── Same-day interaction: Work Hours Shortage suppresses Late Arrival penalty when configured ──
    @Test
    void sameDayShortage_suppressesLateArrivalPenaltyWhenConfigured() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10)
                .workHoursShortageEnabled(true).whsApplyPenaltyForLateArrivalEnabled(false)
                .build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).workHoursShortageAlsoOccurredSameDay(true).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── Multi-policy scoping (Section 36): an employee's assigned policy scopes the lookup ──
    @Test
    void assignedPolicyId_scopesLookupToThatSpecificPolicy() {
        UUID otherPolicyId = UUID.randomUUID();
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(otherPolicyId).version(1)
                .effectiveFrom(LocalDateTime.of(2026, 8, 1, 0, 0))
                .lateArrivalEnabled(true).laGracePeriodMinutes(5).build();
        when(versionRepository.findVersionsEffectiveAtForPolicy(otherPolicyId, date.atStartOfDay()))
                .thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL)
                .assignedPolicyId(otherPolicyId).lateMinutes(12).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(otherPolicyId, decision.getPolicyId());
        // The global (unscoped) lookup must never even be consulted once a policy is assigned.
        org.mockito.Mockito.verify(versionRepository, org.mockito.Mockito.never())
                .findVersionsEffectiveAt(org.mockito.ArgumentMatchers.any());
    }

    // ── Buffer period (Section 8) ──
    @Test
    void bufferPeriodNotYetElapsed_returnsBufferPending_notPersisted() {
        PenalizationPolicyVersion version = baseVersion(1)
                .noAttendanceEnabled(true).naDeductionDays(BigDecimal.ONE).bufferPeriodDays(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .evaluationDate(date.plusDays(1)).build());

        assertEquals(PolicyDecisionType.BUFFER_PENDING, decision.getType());
    }

    @Test
    void bufferPeriodElapsed_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .noAttendanceEnabled(true).naDeductionDays(BigDecimal.ONE).bufferPeriodDays(2).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .evaluationDate(date.plusDays(2)).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── Notice-period override (Section 9) ──
    @Test
    void noticePeriodOverride_forcesLossOfPay_regardlessOfConfiguredMethod() {
        PenalizationPolicyVersion version = baseVersion(1)
                .noAttendanceEnabled(true).naDeductionDays(BigDecimal.ONE)
                .deductionMethod("PAID_LEAVE").noticePeriodForcesLopEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .evaluationDate(date).underNoticePeriod(true).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals("LOSS_OF_PAY", decision.getDeductionMethod());
    }

    @Test
    void notUnderNotice_usesConfiguredDeductionMethod() {
        PenalizationPolicyVersion version = baseVersion(1)
                .noAttendanceEnabled(true).naDeductionDays(BigDecimal.ONE)
                .deductionMethod("PAID_LEAVE").noticePeriodForcesLopEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.NO_ATTENDANCE)
                .evaluationDate(date).underNoticePeriod(false).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals("PAID_LEAVE", decision.getDeductionMethod());
    }

    // ── Late Arrival: Total Hours basis (Section 25/29) ──
    @Test
    void totalHoursBasis_exceedsAllowedHours_matchesTier_appliesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laBasis("TOTAL_HOURS").laAllowedHours(new BigDecimal("2")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                com.nforce.onehr.entity.PenalizationPolicyLateHoursTier.builder()
                        .thresholdHours(new BigDecimal("2")).deductionDays(new BigDecimal("1")).sortOrder(0).build()));
        engine = newEngine();

        // 2h15m total, allowed 2h -> exceeds; matches the "greater than 2h" tier.
        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(60).lateMinutesTotalInPeriod(135).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(new BigDecimal("1"), decision.getDeductionDays());
    }

    @Test
    void totalHoursBasis_withinAllowedHours_returnsNoMatch() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laBasis("TOTAL_HOURS").laAllowedHours(new BigDecimal("2")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(30).lateMinutesTotalInPeriod(90).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    // ── Late arrival caused by missing logs (Section 33) ──
    @Test
    void causedByMissingLog_defaultDisabled_suppressesPenalty() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laDeductionDays(BigDecimal.ONE).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCausedByMissingLog(true).build());

        assertEquals(PolicyDecisionType.NO_MATCH, decision.getType());
    }

    @Test
    void causedByMissingLog_explicitlyEnabled_stillApplies() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laDeductionDays(BigDecimal.ONE)
                .laPenaliseWhenCausedByMissingLogEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCausedByMissingLog(true).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
    }

    // ── Combined rule behavior (Section 32) ──
    @Test
    void combinedRule_bothThresholdsExceeded_totalHoursOnlyByDefault() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(0)
                .laDeductionDays(new BigDecimal("0.5")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                com.nforce.onehr.entity.PenalizationPolicyLateHoursTier.builder()
                        .thresholdHours(new BigDecimal("1")).deductionDays(new BigDecimal("2")).sortOrder(0).build()));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(1).lateMinutesTotalInPeriod(90).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(new BigDecimal("2"), decision.getDeductionDays(), "total-hours tier governs, not the incident amount");
    }

    @Test
    void combinedRule_bothThresholdsExceeded_bothConfigured_sumsAmounts() {
        PenalizationPolicyVersion version = baseVersion(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10).laExemptCount(0)
                .laDeductionDays(new BigDecimal("0.5")).laCombinedRuleBehavior("BOTH").build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                com.nforce.onehr.entity.PenalizationPolicyLateHoursTier.builder()
                        .thresholdHours(new BigDecimal("1")).deductionDays(new BigDecimal("2")).sortOrder(0).build()));
        engine = newEngine();

        PolicyDecision decision = engine.evaluate(baseContext(ExceptionType.LATE_ARRIVAL)
                .lateMinutes(20).lateArrivalCountInPeriod(1).lateMinutesTotalInPeriod(90).build());

        assertEquals(PolicyDecisionType.APPLY_PENALTY, decision.getType());
        assertEquals(new BigDecimal("2.5"), decision.getDeductionDays());
    }
}
