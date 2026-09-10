package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Shift an employee has ever been assigned to, effective from a given calendar date — the
 * same effective-dated model {@link ShiftVersion} already applies to a Shift's own timing, applied
 * one layer up to WHICH Shift governs an employee at all. Immutable once created — reassigning an
 * employee always creates a NEW row rather than mutating an existing one; replacing a still-pending
 * (not-yet-effective) assignment is implemented as deleting it and inserting a replacement (see
 * {@code EmployeeAssignmentService#bulkUpdateShift}), never an in-place field update. This is what
 * lets {@link com.nforce.onehr.service.EmployeeShiftAssignmentResolver} — the one centralized place
 * every attendance-relevant consumer goes through — resolve "which Shift governed this employee on
 * date D" correctly and permanently, independent of whichever Shift the employee is on today.
 *
 * <p>{@code Employee.shiftId} remains in the schema as a best-effort, non-authoritative display
 * cache (rosters, dropdowns, bulk filters) — it is never the source of truth for an attendance
 * calculation once this table exists; every correctness-critical read resolves through here (or
 * through {@code Attendance.shiftId}'s own immutable snapshot for an already-captured row) instead.
 *
 * <p>{@code effectiveFrom} means "the first {@code Attendance.workDate} this assignment governs" —
 * a plain date, matching {@link ShiftVersion#getEffectiveFrom()}'s identical convention, deliberately
 * NOT a timestamp, for the same reason.
 */
@Entity
@Table(name = "employee_shift_assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeShiftAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Who made this assignment (a Manager/HR Admin/Super Admin acting via
    // EmployeeAssignmentService, or the system for the initial backfill/create-time row) — audit
    // trail only, never read for resolution.
    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
