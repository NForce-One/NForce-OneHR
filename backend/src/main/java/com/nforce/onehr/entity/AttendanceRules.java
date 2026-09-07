package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Organization-level attendance classification rules — a singleton row (see V162's own comment
 * for why, and how the singleton is DB-enforced). Currently holds exactly one setting:
 * {@code halfDayMaxHours} — the absolute-hours threshold below which a worked day is classified
 * HALF_DAY (see {@link com.nforce.onehr.service.AttendanceService}/{@link
 * com.nforce.onehr.service.WebClockInService}/{@link com.nforce.onehr.service.RegularizationService},
 * all three of which read this via {@link com.nforce.onehr.service.AttendanceRulesService}).
 * Deliberately NOT folded into {@link com.nforce.onehr.config.AttendanceProperties} (an
 * unpersisted, non-admin-editable application config class) — see this entity's migration for the
 * full reasoning, including why the other {@code app.attendance.*} YAML properties were removed
 * or left alone instead of joining this table.
 */
@Entity
@Table(name = "attendance_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRules {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Always true — see the class Javadoc. Never toggled by application code; its only purpose
    // is the DB-level UNIQUE constraint (V162) that makes a second row impossible.
    @Column(nullable = false)
    @Builder.Default
    private boolean singleton = true;

    @Column(name = "half_day_max_hours", nullable = false)
    private BigDecimal halfDayMaxHours;

    // The org-wide fallback IANA zone id, consulted only when neither the employee's own
    // Employee.timezone nor their Location.timezone is set — see
    // AttendanceService.resolveZone's precedence chain. Migrated off app.attendance.zone (V167)
    // for the same reason half_day_max_hours was migrated off YAML in V162: a genuine business
    // default, not deploy-time technical configuration, so it belongs here rather than in
    // AttendanceProperties. Changing this value never reinterprets any existing Attendance row
    // (each already snapshots its own resolved timezone at check-in).
    @Column(name = "default_timezone", nullable = false, length = 50)
    private String defaultTimezone;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
