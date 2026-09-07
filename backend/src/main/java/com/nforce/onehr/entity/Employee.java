package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "employees")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "employee_code", nullable = false, unique = true)
    private String employeeCode;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    private Designation designation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    // Shift/weekly-off/penalisation assignments (ONEHR-108) — separate from workMode above,
    // which is a self-service ONSITE/REMOTE/HYBRID profile attribute, not a policy assignment.
    // Every employee is expected to always have a Shift (product invariant; see
    // ShiftDayPolicy's class Javadoc) — nullable=false mirrors the DB-level NOT NULL added by
    // V160, so a stray direct save with no shift set fails at flush time instead of silently
    // persisting a shift-less employee.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "weekly_off_policy_id")
    private WeeklyOffPolicy weeklyOffPolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "penalisation_policy_id")
    private PenalisationPolicy penalisationPolicy;

    @Column(name = "employment_type", nullable = false)
    @Builder.Default
    private String employmentType = "FULL_TIME";

    @Column(name = "work_mode", nullable = false)
    @Builder.Default
    private String workMode = "ONSITE";

    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;

    // Notice period (Section 9) — null lastWorkingDay means "not under notice". No richer
    // offboarding workflow exists in this codebase; these are the minimal facts the Penalization
    // Policy engine needs to decide "is this employee under notice on this attendance date"
    // (noticePeriodStartDate <= date <= lastWorkingDay). See ExceptionService.isUnderNoticePeriod.
    @Column(name = "notice_period_start_date")
    private LocalDate noticePeriodStartDate;

    @Column(name = "last_working_day")
    private LocalDate lastWorkingDay;

    // Self-service profile fields — employee updates these themselves
    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "personal_email", length = 255)
    private String personalEmail;

    @Column(name = "address")
    private String address;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 30)
    private String emergencyContactPhone;

    // Admin-controlled ONLY — never editable through the self-service profile API
    // (ProfileController/ProfileService/UpdateProfileRequest deliberately have no timezone field
    // at all). IANA zone id (e.g. "America/New_York", "Asia/Kolkata"), or null to fall back to
    // the employee's Location.timezone, and beyond that to AttendanceRules.defaultTimezone — see
    // AttendanceService.resolveZone. Authoritative for attendance work-date/lateness/shift-day
    // math once set: the employee's own browser-reported zone is never consulted (see
    // resolveZone's own doc comment), and changing this value never reinterprets any existing
    // Attendance row (each already snapshots its own timezone at check-in — see
    // Attendance.timezone).
    @Column(name = "timezone", length = 50)
    private String timezone;

    @Column(name = "profile_photo", columnDefinition = "BYTEA")
    private byte[] profilePhoto;

    @Column(name = "created_by")
    private UUID createdBy;

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
