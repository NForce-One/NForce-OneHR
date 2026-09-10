package com.nforce.onehr.service;

import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExpenseClaim;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ExpenseClaimRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the "Affected User" for an audit-log row — the employee/user whose record, request,
 * account, attendance, leave, expense, or asset was actually affected by the action, which is
 * NOT necessarily the actor. {@code audit_log.target_id} has no FK and points at a different
 * entity type depending on the action, so resolution dispatches by action-prefix.
 *
 * <p>For {@code LEAVE_REQUEST_*}/{@code EXPENSE_*}/{@code ATTENDANCE_*}, {@code target_id} is a
 * domain-record id (LeaveRequest/ExpenseClaim/Attendance) that must be looked up to find the
 * affected employee's own id. For every other tracked action ({@code EMPLOYEE_*}, {@code USER_*},
 * {@code PASSWORD_RESET}, {@code ASSET_*} approve/assign/reassign/return/fulfill,
 * {@code REGULARIZATION_APPROVED}/{@code REJECTED}), {@code target_id} already IS the affected
 * employee's own id directly — those call
 * sites pass it straight through rather than a domain-record id (see AssetService: Asset/
 * AssetAssignment/AssetRequest use Long primary keys that can't fit AuditService.log's UUID
 * target slot, so those services pass the affected employee's UUID instead of a record id, the
 * same convention EmployeeService/UserManagementService already use). Only {@code ASSET_CREATED}/
 * {@code ASSET_RETIRED} have no distinct affected employee at all and correctly fall back to the
 * actor's own id.
 *
 * <p>A plain prefix dispatch is preferred over a pluggable strategy registry — the action set is
 * small and unlikely to grow fast enough to need more abstraction. The resolved label is always
 * just the affected employee's name/email — no domain prefix (e.g. "Leave: ") — matching how
 * ASSET_* rows have always displayed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditTargetResolver {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ExpenseClaimRepository expenseClaimRepository;
    private final AttendanceRepository attendanceRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;

    /** Best-effort human label for the affected user, given the action that produced the row. Never throws. */
    public String resolve(String action, UUID targetId) {
        if (targetId == null) return "—";
        try {
            UUID affectedUserId = resolveAffectedUserId(action, targetId);
            return employeeNameOrEmail(affectedUserId).orElseGet(() -> shortId(affectedUserId));
        } catch (Exception e) {
            log.warn("Failed to resolve audit target label for action={} targetId={}", action, targetId, e);
            return shortId(targetId);
        }
    }

    /**
     * The employee code (business id, e.g. "NF-00001") of the affected user — for the Excel
     * export, which must never show a raw UUID. Empty string (never a UUID) when unresolvable.
     */
    public String resolveEmployeeCode(String action, UUID targetId) {
        if (targetId == null) return "";
        try {
            UUID affectedUserId = resolveAffectedUserId(action, targetId);
            return employeeRepository.findById(affectedUserId).map(Employee::getEmployeeCode).orElse("");
        } catch (Exception e) {
            log.warn("Failed to resolve audit target employee code for action={} targetId={}", action, targetId, e);
            return "";
        }
    }

    /**
     * The UUID of the employee/user actually affected by this action — the shared resolution
     * step behind both {@link #resolve} (display label) and {@link #resolveEmployeeCode} (Excel
     * export), so both stay consistent with a single dispatch instead of duplicating it.
     */
    private UUID resolveAffectedUserId(String action, UUID targetId) {
        if (action == null) return targetId;
        if (action.startsWith("LEAVE_REQUEST_")) {
            return leaveRequestRepository.findById(targetId)
                    .map(LeaveRequest::getEmployeeUserId).orElse(targetId);
        }
        if (action.startsWith("EXPENSE_")) {
            return expenseClaimRepository.findById(targetId)
                    .map(ExpenseClaim::getEmployeeUserId).orElse(targetId);
        }
        if (action.startsWith("ATTENDANCE_")) {
            return attendanceRepository.findById(targetId)
                    .map(Attendance::getEmployeeUserId).orElse(targetId);
        }
        // EMPLOYEE_*, USER_*, PASSWORD_RESET, ASSET_* (approve/assign/reassign/return/fulfill —
        // ASSET_CREATED/ASSET_RETIRED correctly fall back to the actor's own id here too, since
        // there's no distinct affected employee for those), and REGULARIZATION_APPROVED/REJECTED:
        // target_id already IS the affected employee's own id — no domain-record lookup needed or
        // possible.
        return targetId;
    }

    /**
     * Resolves a free-text search fragment (name/email) to the set of target ids it could match,
     * for the "search by target" filter. Since most targets ultimately trace back to an employee,
     * this unions the direct employee/user id match with every domain-record id owned by a
     * matching employee.
     */
    public Set<UUID> resolveTargetIdsMatching(String query) {
        Set<UUID> matchingUsers = userRepository.findUserIdsByEmailOrFullNameContaining(query);
        if (matchingUsers.isEmpty()) return Set.of();

        Set<UUID> targetIds = new HashSet<>(matchingUsers);
        targetIds.addAll(leaveRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(expenseClaimRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(attendanceRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(webClockInRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        targetIds.addAll(regularizationRequestRepository.findIdsByEmployeeUserIdIn(matchingUsers));
        return targetIds;
    }

    private Optional<String> employeeNameOrEmail(UUID userId) {
        if (userId == null) return Optional.empty();
        return employeeRepository.findById(userId)
                .map(e -> e.getFullName())
                .or(() -> userRepository.findById(userId).map(u -> u.getEmail()));
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8) + "…";
    }
}
