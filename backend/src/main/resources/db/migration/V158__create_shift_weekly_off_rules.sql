-- Shift + Weekly Off P1: organization-level "Shifts & Weekly Off Rules" area.
--
-- Holds exactly one setting for P1: maximum_shift_day_duration_hours (default 18) — the elapsed
-- window, from an employee's assigned shift's own start time, during which attendance activity
-- still belongs to that shift's logical workday (see ShiftDayPolicy). This is deliberately NOT a
-- Shift column (it is an org-wide rule, not a per-shift override) and deliberately NOT folded
-- into AttendanceProperties (an unpersisted, non-admin-editable application config class) — this
-- table is the smallest structure that represents "Shifts & Weekly Off Rules" as a real,
-- persisted, admin-owned concept, matching the product decision that this setting lives there.
--
-- Singleton, enforced at the database level (not just by application convention): `singleton` is
-- always TRUE (see the CHECK) and UNIQUE, so a second row can never be inserted regardless of its
-- id — any INSERT attempting a second row violates the UNIQUE constraint on `singleton`.
-- `maximum_shift_day_duration_hours` is bounded (0, 24] by CHECK as defense-in-depth alongside
-- the application-level validation in ShiftWeeklyOffRulesService — see that service's own
-- comment for why 24h (not some other number) is the chosen ceiling: no existing domain rule
-- establishes this bound, so it is fixed at the largest value for which ShiftDayPolicy's single-
-- candidate-day algorithm stays unambiguous (a value greater than 24h would let more than one
-- calendar day's shift-start simultaneously fall within the elapsed window, which the product has
-- not specified how to resolve).
CREATE TABLE IF NOT EXISTS shift_weekly_off_rules (
    id                                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton                          BOOLEAN      NOT NULL DEFAULT TRUE,
    maximum_shift_day_duration_hours   NUMERIC(4,1) NOT NULL DEFAULT 18.0,
    updated_at                         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_shift_weekly_off_rules_singleton CHECK (singleton),
    CONSTRAINT chk_shift_weekly_off_rules_duration_bounds
        CHECK (maximum_shift_day_duration_hours > 0 AND maximum_shift_day_duration_hours <= 24),
    CONSTRAINT uq_shift_weekly_off_rules_singleton UNIQUE (singleton)
);

INSERT INTO shift_weekly_off_rules (maximum_shift_day_duration_hours)
VALUES (18.0)
ON CONFLICT (singleton) DO NOTHING;
