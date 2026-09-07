package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Organization-level "Shifts & Weekly Off Rules" — a singleton row (see V158's own comment for
 * why, and how the singleton is DB-enforced). P1 holds exactly one setting:
 * {@code maximumShiftDayDurationHours} — see {@link com.nforce.onehr.service.ShiftDayPolicy} for
 * how it's consumed. Deliberately not a {@link Shift} field and not part of
 * {@link com.nforce.onehr.config.AttendanceProperties} — see this entity's migration for the full
 * reasoning.
 */
@Entity
@Table(name = "shift_weekly_off_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShiftWeeklyOffRules {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Always true — see the class Javadoc. Never toggled by application code; its only purpose
    // is the DB-level UNIQUE constraint (V158) that makes a second row impossible.
    @Column(nullable = false)
    @Builder.Default
    private boolean singleton = true;

    @Column(name = "maximum_shift_day_duration_hours", nullable = false)
    private BigDecimal maximumShiftDayDurationHours;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
