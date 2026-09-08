package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the seam with a mock {@link AttendancePolicyEngine} — {@link ConfiguredAttendancePolicyEngineTest}
 * covers the real engine's decision logic; this test only covers what
 * {@link AttendancePenaltyEvaluationService} itself does with whatever decision it receives.
 */
@ExtendWith(MockitoExtension.class)
class AttendancePenaltyEvaluationServiceTest {

    @Mock private AttendancePolicyEngine policyEngine;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private PenaltyDeductionService penaltyDeductionService;
    @Mock private NotificationService notificationService;
    @Mock private EmployeeService employeeService;
    @Mock private AuditService auditService;
    @Spy private AuditSnapshotSerializer auditSnapshot = new AuditSnapshotSerializer(new ObjectMapper());

    @InjectMocks private AttendancePenaltyEvaluationService service;

    @Test
    void noMatch_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.NO_MATCH).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verifyNoInteractions(attendancePenaltyRepository);
    }

    @Test
    void exempt_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.EXEMPT).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verifyNoInteractions(attendancePenaltyRepository);
    }

    @Test
    void configurationRequired_persistsNothing() {
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder().type(PolicyDecisionType.CONFIGURATION_REQUIRED).build());

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(UUID.randomUUID()).attendanceDate(LocalDate.now())
                .discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
    }

    @Test
    void applyPenalty_persistsWithPolicySnapshot() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        UUID policyId = UUID.randomUUID();
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(policyId).policyVersion(2).build());
        when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isPresent());
        assertEquals(employeeId, result.get().getEmployeeUserId());
        assertEquals(date, result.get().getIncidentDate());
        assertEquals(policyId, result.get().getPolicyId());
        assertEquals(2, result.get().getPolicyVersion());
        assertNotNull(result.get().getEvaluatedAt());
        verify(attendancePenaltyRepository).saveAndFlush(any());
    }

    /** Gap-038: penalty creation must be traceable from the audit log, not just the row itself. */
    @Test
    void applyPenalty_auditsCreation_withNullActor_sinceNoHumanInitiatedIt() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        UUID policyId = UUID.randomUUID();
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(policyId).policyVersion(2).build());
        when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        verify(auditService).log(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("ATTENDANCE_PENALTY_CREATED"),
                org.mockito.ArgumentMatchers.eq(result.get().getId()), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void applyPenalty_penaltyAlreadyExistsForThisIncident_doesNotSaveDuplicate() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1).build());
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(true);

        Optional<AttendancePenalty> result = service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        assertTrue(result.isEmpty());
        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        // The duplicate guard short-circuits BEFORE deduction is even attempted — an already-
        // recorded penalty must never trigger a second leave-balance debit.
        verifyNoInteractions(penaltyDeductionService);
    }

    /** Idempotency (Section 18): evaluating the identical occurrence twice in a row must debit at most once. */
    @Test
    void evaluatingSameOccurrenceTwiceInARow_secondCallIsANoOp_noDoubleDeduction() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        PolicyEvaluationContext context = PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build();
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1)
                .deductionDays(new java.math.BigDecimal("1")).deductionMethod("PAID_LEAVE").build());
        when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // First call: nothing recorded yet. Second call: the repository now reflects the row the
        // first call persisted — simulating two sequential runs against the same underlying state
        // (e.g. a dashboard reload, or the scheduler running twice for the same date).
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(false, true);

        Optional<AttendancePenalty> first = service.evaluate(context);
        Optional<AttendancePenalty> second = service.evaluate(context);

        assertTrue(first.isPresent(), "first evaluation creates the penalty");
        assertTrue(second.isEmpty(), "second evaluation of the identical occurrence is a no-op");
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
        verify(penaltyDeductionService, org.mockito.Mockito.times(1)).apply(any(), any(), any());
    }

    /**
     * The existsBy... guard is only a defensive first line (see this class's own Javadoc) — the
     * real guarantee against a duplicate row is the DB unique index (V156). Two concurrent
     * evaluations of the identical occurrence can both pass that check before either commits; the
     * loser's insert must fail with a DB constraint violation, which this must translate into a
     * graceful no-op — never an unhandled exception — and must never send a notification or audit
     * a penalty that was never actually persisted.
     */
    @Test
    void concurrentEvaluationRace_loserGetsAGracefulNoOp_notAnUnhandledException() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1).build());
        // Both concurrent calls pass the existsBy... pre-check (neither has committed yet)...
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(false);
        // ...but the DB unique index (V156) rejects the losing insert.
        when(attendancePenaltyRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        Optional<AttendancePenalty> result = assertDoesNotThrow(() -> service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build()));

        assertTrue(result.isEmpty(), "the losing side of the race must be a no-op, not a thrown exception");
        verifyNoInteractions(notificationService);
        verify(auditService, org.mockito.Mockito.never()).log(any(), any(), any(), any(), any());
    }

    // ── Section 19: penalty-applied notification ─────────────────────────────────────────────

    @Test
    void applyPenalty_notifiesTheEmployee() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1).build());
        when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        verify(notificationService).send(org.mockito.ArgumentMatchers.eq(employeeId),
                org.mockito.ArgumentMatchers.eq("ATTENDANCE_PENALTY_APPLIED"), any(), any(), any());
    }

    @Test
    void duplicateEvaluation_neverSendsASecondNotification() {
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 3);
        when(policyEngine.evaluate(any())).thenReturn(PolicyDecision.builder()
                .type(PolicyDecisionType.APPLY_PENALTY).policyId(UUID.randomUUID()).policyVersion(1).build());
        when(attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                employeeId, date, ExceptionType.LATE_ARRIVAL)).thenReturn(true);

        service.evaluate(PolicyEvaluationContext.builder()
                .employeeUserId(employeeId).attendanceDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL).build());

        verifyNoInteractions(notificationService);
    }
}
