package com.nforce.onehr.service;

import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The one centralized place every attendance-relevant consumer resolves "which Shift governed this
 * employee on date D" — mirrors {@link ShiftVersionResolver}'s identical role for a Shift's own
 * timing, applied one layer up to WHICH Shift an employee is even on. Nothing outside this class
 * (and {@code EmployeeAssignmentService}'s own CRUD, which creates/replaces assignment rows but
 * never resolves them for a business decision) should read an {@link EmployeeShiftAssignment}
 * directly for an attendance computation — always go through {@link #resolve}, keyed by the
 * specific date in question (typically {@code Attendance.workDate} or a fresh check-in's own
 * work-date), never by "the employee's current {@code Employee.shift}" — that field is a
 * best-effort display cache only, not authoritative (see {@code Employee.shift}'s own Javadoc).
 */
@Service
@RequiredArgsConstructor
public class EmployeeShiftAssignmentResolver {

    private final EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;

    /**
     * The assignment governing {@code workDate}: the latest assignment whose {@code effectiveFrom}
     * is on or before it. Every employee is expected to have at least one assignment at all times
     * (created alongside the Employee itself — see {@code EmployeeService#createEmployee}/
     * {@code UserManagementService#createUser} — or backfilled for any pre-existing row), so this
     * only throws if that invariant has somehow been violated for a date it should actually cover.
     */
    public EmployeeShiftAssignment resolve(UUID employeeUserId, LocalDate workDate) {
        return resolveIfPresent(employeeUserId, workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Employee " + employeeUserId + " has no Shift assignment effective on or before "
                                + workDate + " — every employee is expected to have at least an initial "
                                + "assignment (see EmployeeService#createEmployee/UserManagementService#createUser "
                                + "and the initial backfill)."));
    }

    /**
     * Non-throwing counterpart to {@link #resolve} — empty when {@code workDate} predates the
     * employee's own earliest assignment (e.g. the day before they joined, or the day before a
     * brand-new assignment's own effective date), rather than treating that as the invariant
     * violation {@link #resolve} guards against. Exists for callers asking "did this employee
     * already have SOME assignment as of date D" (e.g. {@link ShiftDayPolicy}'s own
     * yesterday-boundary pre-check, mirroring the identical {@code ShiftVersionResolver
     * .resolveIfPresent} pattern) rather than "what governs date D."
     */
    public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
        return employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, workDate);
    }
}
