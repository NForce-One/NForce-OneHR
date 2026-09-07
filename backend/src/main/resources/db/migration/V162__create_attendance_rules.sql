-- Workstream B (re-scoped): the ONLY attendance-classification threshold that genuinely needed a
-- persisted, admin-editable home. See scratch/workstream-b-settings-investigation.md and the
-- follow-up re-scope for the audit behind each of the other app.attendance.* properties:
--   - late-grace-minutes: NOT migrated here — left in application.yml/AttendanceProperties, and
--     confirmed still the sole runtime source for the org-wide LATE/PRESENT status gate in three
--     places (AttendanceService/WebClockInService/RegularizationService). Deliberately not folded
--     into the existing per-policy PenalizationPolicyVersion.laGracePeriodMinutes either: that
--     field never touches this gate — it's the Penalization/Tracking Policy's own, separately-
--     configured grace, consumed only downstream of an already-computed lateByMinutes, purely for
--     penalty *eligibility* (a per-incident exemption in ConfiguredAttendancePolicyEngine, and
--     again subtracted in ExceptionService's TOTAL_HOURS tally). Reusing it here would both change
--     lateByMinutes to vary per resolved policy and double-subtract grace in that penalty tally.
--     Left as a documented prerequisite for a future Tracking/Regularization Policy pass.
--   - daily-break-budget-minutes: removed outright (see the paired code change) — it was a
--     display-only progress-bar denominator with no enforcement, and is NOT the same concept as
--     the already-persisted, per-shift ShiftVersion.breakMinutes (static admin metadata, never
--     read by any attendance computation).
--   - full-day-min-hours: removed outright — proven dead (AttendanceResponse.fullDay has zero
--     readers anywhere; the frontend's own "full day" concept is independently derived from the
--     employee's actual assigned shift span, which Workstream A made a hard invariant).
--   - regularization.employee-lookback-days / regularization.monthly-limit: NOT migrated —
--     both stay exactly as they are today (YAML-sourced @Value fields on RegularizationService)
--     pending a future, dedicated Regularization Policy that will own lookback/monthly-limit
--     together with other regularization-specific configuration.
--
-- Singleton, DB-enforced exactly like shift_weekly_off_rules (V158): `singleton` is always TRUE
-- (CHECK) and UNIQUE, so a second row can never be inserted. `half_day_max_hours` keeps its
-- current absolute-hours semantics (workedMinutes < halfDayMaxHours*60 => HALF_DAY) — NOT
-- redesigned as a percentage of shift duration. Bounded (0, 24) by CHECK as defense-in-depth
-- alongside AttendanceRulesService's own validation: a half-day threshold must be a positive
-- fraction of a calendar day.
CREATE TABLE IF NOT EXISTS attendance_rules (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton            BOOLEAN      NOT NULL DEFAULT TRUE,
    half_day_max_hours   NUMERIC(4,1) NOT NULL DEFAULT 3.5,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_attendance_rules_singleton CHECK (singleton),
    CONSTRAINT chk_attendance_rules_half_day_max_hours_bounds
        CHECK (half_day_max_hours > 0 AND half_day_max_hours < 24),
    CONSTRAINT uq_attendance_rules_singleton UNIQUE (singleton)
);

-- Seeded with the exact current YAML default (app.attendance.half-day-max-hours: 3.5) so no
-- existing org's HALF_DAY classification changes on deploy.
INSERT INTO attendance_rules (half_day_max_hours)
VALUES (3.5)
ON CONFLICT (singleton) DO NOTHING;
