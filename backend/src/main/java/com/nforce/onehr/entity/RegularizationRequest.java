package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "regularization_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RegularizationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    // Resolved once at submission time: the employee-selected manager, else their current
    // manager via EmployeeManagerHistory, else NULL (HR/Super Admin have blanket override
    // visibility regardless — see RegularizationService.listPendingForApprover).
    @Column(name = "assigned_approver_id")
    private UUID assignedApproverId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "requested_check_in")
    private LocalDateTime requestedCheckIn;

    @Column(name = "requested_check_out")
    private LocalDateTime requestedCheckOut;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    // Stage 1 (manager approval) — set only when a MANAGER approves a PENDING request,
    // transitioning it to PARTIALLY_APPROVED. Never set by a Super Admin bypass approval.
    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // Stage 2 (final approval) — set when HR_ADMIN or SUPER_ADMIN approves, transitioning the
    // request to the terminal APPROVED status, whether from PARTIALLY_APPROVED or (Super Admin
    // bypass) directly from PENDING.
    @Column(name = "final_approved_by")
    private UUID finalApprovedBy;

    @Column(name = "final_approved_at")
    private LocalDateTime finalApprovedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Concurrent approve() and reject() calls on the same still-PENDING/PARTIALLY_APPROVED
    // request otherwise race with nothing coordinating between them: both read-modify-write this
    // same row (status, reviewedBy/At, approvedBy/At or finalApprovedBy/At) with only an in-memory
    // status check guarding either — the loser's save() would silently overwrite the winner's
    // decision, leaving the Attendance mutation one branch already applied (approve()) alongside a
    // REJECTED status and notification from the other, with no rollback of either. @Version turns
    // that into an ObjectOptimisticLockingFailureException (translated to a clean 409 by
    // GlobalExceptionHandler) instead — see V171's migration comment. Mirrors Attendance's
    // identical fix (V164) for the same class of read-modify-write race.
    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
