package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shifts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shift {

    // The organization's default shift — every newly-created employee is assigned this shift
    // unless the admin explicitly picks a different one (see UserManagementService.createUser /
    // EmployeeService.createEmployee), and ShiftSeedCorrector backfills it onto any employee left
    // without a shift. Looked up by this stable name — never by a hardcoded id, since ids differ
    // per environment/seed run.
    //
    // Originally seeded (V95) and referred to throughout the codebase as "Regular Shift"; renamed
    // to the canonical "Default Shift" here in V161, which also renames the existing row in place
    // (same id, so every employee's shift_id assignment is untouched) on any environment that
    // still has the old name. See V161's own comment for the exact rename rules.
    public static final String DEFAULT_SHIFT_NAME = "Default Shift";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(unique = true, length = 30)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Timing (start/end/break) is NOT here — it lives on ShiftVersion, effective-dated, resolved
    // via ShiftVersionResolver. This entity is purely the logical identity: the thing an Employee
    // is assigned to, the row that appears once in the Shifts tab, the thing name/code/active
    // validation applies to — see ShiftVersion's own Javadoc for the full rationale (a mutable
    // timing field here would let editing an in-use Shift silently reinterpret already-recorded
    // Attendance/Exceptions/Penalties for historical dates).
    @Column(nullable = false)
    @Builder.Default
    private boolean flexible = false;

    // Comma-separated java.time.DayOfWeek names, e.g. "MONDAY,TUESDAY" — same convention as
    // WeeklyOffPolicy.offDays. Null when this shift doesn't specify working days itself (the
    // employee's assigned WeeklyOffPolicy is the source of truth in that case).
    @Column(name = "working_days", length = 60)
    private String workingDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
