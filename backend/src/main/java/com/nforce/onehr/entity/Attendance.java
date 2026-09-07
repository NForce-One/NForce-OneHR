package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "attendance_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Raw UUID rather than a @ManyToOne, matching EmployeeManagerHistory.employeeUserId.
    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    @Column(name = "worked_minutes")
    private Integer workedMinutes;

    // When the currently-open session began — only meaningful while checkOutAt is null.
    // Lets a later session in the same day (e.g. after a lunch break) compute its own
    // duration without disturbing checkInAt, which stays the day's first check-in.
    @Column(name = "session_started_at")
    private LocalDateTime sessionStartedAt;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PRESENT";

    @Column(name = "late_by_minutes", nullable = false)
    @Builder.Default
    private Integer lateByMinutes = 0;

    // SYSTEM for a normal punch, REGULARIZATION for a row created/edited via an approved
    // regularization request. See V18 migration and RegularizationService.
    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "SYSTEM";

    // IANA zone id (e.g. "Asia/Kolkata") resolved server-side at Check-In / Web Clock-In — via
    // the employee's own Employee.timezone, then their Location.timezone, then the org-wide
    // default (see AttendanceService.zoneIdFor's precedence chain) — locked in for the whole
    // session so Check-Out, worked-minutes, and shift-day/grace-window math all stay internally
    // consistent even if the employee's configured timezone later changes (e.g. a relocation).
    // The browser's reported zone is NEVER the source of this value. Null for records predating
    // this column.
    @Column(name = "timezone", length = 50)
    private String timezone;

    // Raw UUID, not a @ManyToOne (same convention as employeeUserId above) — the Shift that was
    // in effect when this row was FIRST created (normal Check-In, Web Clock-In, or a
    // Regularization-created row), set once and never updated afterward. Null for rows created
    // before this column existed ("legacy" rows — see AttendanceInterpretationService's
    // LEGACY_UNRESOLVED handling) or, in principle, for a row predating any Shift assignment;
    // never silently backfilled from the employee's current Shift. This is what lets an
    // already-open session or an already-created historical record keep its own original Shift
    // context even after the employee is reassigned to a different Shift — see V163's migration
    // comment and AttendanceInterpretationService.
    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Concurrent Check-Out (or a concurrent Regularization approval, or the stale-session
    // sweeper racing a live user action) can otherwise read-modify-write this same row with
    // nothing coordinating between them — the loser's save() would silently overwrite the
    // winner's checkOutAt/workedMinutes/status with stale values. @Version turns that into an
    // ObjectOptimisticLockingFailureException (translated to a clean 409 by
    // GlobalExceptionHandler) instead of a silently wrong attendance record. Mirrors
    // LeaveBalance's identical fix (V153) for the same class of read-modify-write race.
    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * The single "missing log" signal — a check-in was recorded but the day never got a matching
     * check-out. Shared by MISSING_PUNCH detection, Work Hours Shortage's missing-log linkage, and
     * Late Arrival's caused-by-missing-log check (see ExceptionService/WorkHoursShortageCalculationService)
     * so "missing log" means exactly one thing everywhere it's used.
     */
    public boolean isMissingCheckOut() {
        return checkInAt != null && checkOutAt == null;
    }
}
