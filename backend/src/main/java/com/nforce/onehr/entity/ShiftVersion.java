package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One timing configuration a {@link Shift} has ever had, effective from a given calendar date.
 * Immutable once created — a change to a Shift's timing always creates a NEW version rather than
 * mutating an existing one; "editing" a pending (not-yet-effective) version is implemented as
 * deleting it and inserting a replacement (see {@code OrgService#scheduleShiftVersion}), never as
 * an in-place field update. This is what lets {@link com.nforce.onehr.service.ShiftDayPolicy} and
 * every other timing consumer resolve "what was this Shift's configuration on date D" correctly
 * and permanently, independent of whatever the Shift is configured as today — see {@link
 * com.nforce.onehr.service.ShiftVersionResolver}, the one centralized place every consumer goes
 * through instead of reading {@code Shift.startTime}/{@code endTime}/{@code breakMinutes} (which
 * no longer exist on {@link Shift} at all — timing lives here exclusively).
 *
 * <p>{@code effectiveFrom} means "the first {@code Attendance.workDate} this version governs" —
 * a plain date, matching every other date-typed field this domain already keys historical facts
 * by (Attendance.workDate, AttendanceException.exceptionDate), deliberately NOT a timestamp (see
 * the design discussion this class implements: a raw instant would reintroduce exactly the
 * ambiguity workDate-keyed resolution exists to avoid for a session straddling midnight).
 */
@Entity
@Table(name = "shift_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShiftVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    // Same LocalTimeTextConverter as Shift's own former columns — see that converter's Javadoc for
    // why (Hibernate's UTC-calendar JDBC binding otherwise silently corrupts a plain LocalTime on
    // any non-UTC-default JVM).
    @Column(name = "start_time", nullable = false)
    @Convert(converter = LocalTimeTextConverter.class)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    @Convert(converter = LocalTimeTextConverter.class)
    private LocalTime endTime;

    @Column(name = "break_minutes")
    private Integer breakMinutes;

    // Confirmed business requirement (Phase 3 grace pass): every Shift has its own grace period,
    // applying to every workday that version governs — there is no single global authoritative
    // grace anymore (migrated off app.attendance.late-grace-minutes — see V168's migration
    // comment). Read exclusively via ShiftDayPolicy.resolveLateGraceMinutes, which resolves the
    // SAME effective-dated version every other timing field on this class already does — never
    // duplicated onto Attendance, since (shiftId, workDate) already reconstructs it exactly like
    // start/end time.
    @Column(name = "late_grace_minutes", nullable = false)
    private Integer lateGraceMinutes;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
