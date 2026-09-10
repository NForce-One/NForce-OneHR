package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "web_clock_in_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebClockInRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "requested_check_in", nullable = false)
    private LocalDateTime requestedCheckIn;

    // Mandatory on the first Web Clock-In cycle of an employee's resolved work day, optional on
    // every later cycle that same day — enforced in WebClockInService#submit, not a column
    // constraint (see its own Javadoc). Purely an informational attendance note now, no approval
    // attached to it.
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
