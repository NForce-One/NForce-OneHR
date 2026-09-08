package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bridges one discrepancy evaluation to a persisted {@link AttendancePenalty} row: calls the
 * configured {@link AttendancePolicyEngine}, and persists a row only when the decision is
 * {@code APPLY_PENALTY} — {@code NO_MATCH}, {@code EXEMPT}, and {@code CONFIGURATION_REQUIRED}
 * all produce nothing. The persisted row snapshots the policy id/version/evaluation time so a
 * later policy change never rewrites history.
 *
 * <p>Production caller: {@code ExceptionService.upsertException} invokes this the moment a
 * discrepancy is first detected (same "first detection only" gating already used for employee
 * notification emails) — the existing Exception Dashboard load is the trigger, not a scheduler.
 * The {@link #existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType} guard below is a second,
 * defensive line against duplicate rows: even if a future caller re-evaluates the same
 * employee/date/discrepancy (e.g. a caller that doesn't gate on first-detection), at most one
 * {@link AttendancePenalty} row is ever created for it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendancePenaltyEvaluationService {

    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final AttendancePolicyEngine policyEngine;
    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final PenaltyDeductionService penaltyDeductionService;
    private final NotificationService notificationService;
    private final EmployeeService employeeService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public Optional<AttendancePenalty> evaluate(PolicyEvaluationContext context) {
        PolicyDecision decision = policyEngine.evaluate(context);
        if (decision.getType() != PolicyDecisionType.APPLY_PENALTY) {
            return Optional.empty();
        }
        if (attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                context.getEmployeeUserId(), context.getAttendanceDate(), context.getDiscrepancyType())) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        AttendancePenalty penalty = AttendancePenalty.builder()
                .employeeUserId(context.getEmployeeUserId())
                .incidentDate(context.getAttendanceDate())
                .discrepancyType(context.getDiscrepancyType())
                .status(AttendancePenaltyStatus.PENDING_REVIEW)
                .policyId(decision.getPolicyId())
                .policyVersion(decision.getPolicyVersion())
                .deductionDays(decision.getDeductionDays())
                .evaluatedAt(now)
                .penalizedOn(now)
                .build();
        // Resolves the configured deduction-days amount into an actual leave-balance debit
        // and/or Loss-of-Pay amount, mutating `penalty` in place before its one save below.
        penaltyDeductionService.apply(penalty, decision.getDeductionMethod(), decision.getLeavePriorityOrder());
        AttendancePenalty saved;
        try {
            // saveAndFlush (not save) + catch, mirroring AttendanceService.checkIn's identical
            // duplicate-row race handling: the existsBy... check above is only a defensive first
            // line (see this class's own Javadoc) — the real guarantee is the DB unique index
            // (V156, on employee_user_id/incident_date/discrepancy_type). Without this catch, the
            // losing side of two concurrent evaluations of the same discrepancy (two dashboard
            // loads, or a dashboard load racing the nightly scheduler) surfaced as an unhandled
            // 500 instead of a graceful no-op — the DB prevented the duplicate row, but not the
            // error.
            saved = attendancePenaltyRepository.saveAndFlush(penalty);
        } catch (DataIntegrityViolationException e) {
            log.info("AttendancePenaltyEvaluationService: lost a concurrent evaluation race for employee {} / {} / {} "
                            + "— a penalty was already created by another request",
                    context.getEmployeeUserId(), context.getAttendanceDate(), context.getDiscrepancyType());
            return Optional.empty();
        }
        // Guarded by the duplicate-evaluation check above — fires exactly once per genuinely new
        // penalty row, never once per re-evaluation of an already-recorded discrepancy.
        notifyPenaltyApplied(saved);
        auditPenaltyCreated(saved);
        return Optional.of(saved);
    }

    /**
     * Gap-038: penalty creation — the single most financially consequential mutation in this
     * subsystem — previously had zero audit-log entry, even though every reversal already did.
     * No actorId: this is the system's own policy-engine decision, not a human action — a null
     * actor here is an honest "nobody clicked anything," not a missing field.
     */
    private void auditPenaltyCreated(AttendancePenalty penalty) {
        // Same convention as every other AuditSnapshotSerializer caller (AssetService,
        // RegularizationService, ...): UUID/LocalDate values are stringified before going into the
        // map, rather than depending on the caller's ObjectMapper having a date/time module
        // registered — the JSON shape is stable regardless of how toJson's ObjectMapper is configured.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employeeUserId", penalty.getEmployeeUserId() != null ? penalty.getEmployeeUserId().toString() : null);
        snapshot.put("policyId", penalty.getPolicyId() != null ? penalty.getPolicyId().toString() : null);
        snapshot.put("policyVersion", penalty.getPolicyVersion());
        snapshot.put("discrepancyType", penalty.getDiscrepancyType());
        snapshot.put("incidentDate", penalty.getIncidentDate() != null ? penalty.getIncidentDate().toString() : null);
        snapshot.put("status", penalty.getStatus());
        if (penalty.getDeductionDays() != null) snapshot.put("deductionDays", penalty.getDeductionDays());
        if (penalty.getLopDays() != null) snapshot.put("lopDays", penalty.getLopDays());
        if (penalty.getLeaveDeductionDays() != null) snapshot.put("leaveDeductionDays", penalty.getLeaveDeductionDays());
        if (penalty.getLeaveBreakdown() != null) snapshot.put("leaveBreakdown", penalty.getLeaveBreakdown());
        auditService.log(null, "ATTENDANCE_PENALTY_CREATED", penalty.getId(), null, auditSnapshot.toJson(snapshot));
    }

    /** Section 19: tells the employee (and their current manager, if resolvable) what was applied and why. */
    private void notifyPenaltyApplied(AttendancePenalty penalty) {
        StringBuilder message = new StringBuilder("A ")
                .append(penalty.getDiscrepancyType() != null ? penalty.getDiscrepancyType().replace('_', ' ').toLowerCase() : "policy")
                .append(" penalty of ").append(penalty.getDeductionDays() != null ? penalty.getDeductionDays() : BigDecimal.ZERO)
                .append(" day(s) has been applied for ").append(penalty.getIncidentDate().format(NOTIFICATION_DATE_FMT)).append('.');
        if (penalty.getLopDays() != null && penalty.getLopDays().signum() > 0) {
            message.append(' ').append(penalty.getLopDays()).append(" day(s) as Loss of Pay.");
        }
        if (penalty.getLeaveDeductionDays() != null && penalty.getLeaveDeductionDays().signum() > 0) {
            message.append(' ').append(penalty.getLeaveDeductionDays()).append(" day(s) deducted from your leave balance.");
        }
        message.append(" If you believe this is incorrect, you can submit a regularization request for this date.");
        notificationService.send(penalty.getEmployeeUserId(), "ATTENDANCE_PENALTY_APPLIED",
                "Attendance Penalty Applied", message.toString(), "/attendance");

        EmployeeResponse.ManagerRef manager = employeeService.findCurrentManagersBulk(List.of(penalty.getEmployeeUserId()))
                .get(penalty.getEmployeeUserId());
        if (manager != null) {
            notificationService.send(UUID.fromString(manager.getUserId()), "ATTENDANCE_PENALTY_APPLIED",
                    "Attendance Penalty Applied",
                    "A team member's attendance incurred a penalty for " + penalty.getIncidentDate().format(NOTIFICATION_DATE_FMT) + ".",
                    "/my-team");
        }
    }
}
