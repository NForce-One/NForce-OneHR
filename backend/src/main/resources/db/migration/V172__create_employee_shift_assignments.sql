-- NForce OneHR — Flyway Migration V172
-- Employee Shift Assignment history: makes WHICH Shift an employee is assigned to effective-dated,
-- the same model V159 already applied to a Shift's own TIMING (ShiftVersion). See
-- EmployeeShiftAssignment's own Javadoc for the full design — summary: every attendance-relevant
-- consumer resolves "the latest assignment whose effective_from <= workDate"
-- (EmployeeShiftAssignmentResolver), so a future-dated reassignment can never retroactively affect
-- an already-recorded Attendance/AttendanceException/WorkHoursShortage/penalty evaluation for a
-- date before the new assignment takes effect.
--
-- employees.shift_id is UNCHANGED by this migration and remains in the schema — it becomes a
-- best-effort, non-authoritative display/roster/filter cache going forward (see application-code
-- changes alongside this migration); it is not read here for anything but the one-time backfill
-- below.
CREATE TABLE IF NOT EXISTS employee_shift_assignments (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Mirrors V136 (penalization_policy_allocations)'s identical per-employee-assignment-history
    -- shape — employees(user_id), no cascade (employees are deactivated, never hard-deleted; see
    -- User.deletedAt's own "deactivate, never delete" convention).
    employee_user_id  UUID         NOT NULL REFERENCES employees(user_id),
    shift_id          UUID         NOT NULL REFERENCES shifts(id),
    effective_from    DATE         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    -- At most one assignment of a given employee may take effect on any single date — mirrors
    -- shift_versions' own uq_shift_versions_shift_effective_from, and is what makes "replace the
    -- pending assignment" (EmployeeAssignmentService) a safe delete-then-insert.
    CONSTRAINT uq_employee_shift_assignments_employee_effective_from UNIQUE (employee_user_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_employee_shift_assignments_employee_effective_from
    ON employee_shift_assignments (employee_user_id, effective_from);

-- Read-only anomaly check (see the design gate's own "backfill anchor" section): a real Attendance
-- row dated before its employee's own joining_date would mean the backfilled assignment below
-- (effective from joining_date) doesn't cover it — any historical calculation for that date would
-- then correctly report "cannot be evaluated" per the resolver's own invariant, rather than a
-- silently wrong Shift. That's the correct, safe behavior, not a migration blocker — but a
-- data-quality anomaly of that shape is worth surfacing loudly rather than passing through
-- silently, the same "fail clearly, don't guess" rule V159/V160 already apply to this exact class
-- of pre-existing-data question. This only WARNS (via RAISE NOTICE) — it does not fail the
-- migration — since the anomaly, if any, is pre-existing and unrelated to this feature, and the
-- resolver already degrades safely for it.
DO $$
DECLARE
    anomaly_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO anomaly_count
    FROM attendance_records a
    JOIN employees e ON e.user_id = a.employee_user_id
    WHERE a.work_date < e.joining_date;

    IF anomaly_count > 0 THEN
        RAISE NOTICE 'employee_shift_assignments backfill: % attendance_records row(s) have a work_date before their employee''s own joining_date — the backfilled assignment (effective from joining_date) will not cover them; any historical Shift-dependent calculation for those specific dates will correctly report "cannot be evaluated" rather than guess. This is a pre-existing data anomaly, not something this migration can safely correct.', anomaly_count;
    END IF;
END $$;

-- Backfill: one initial assignment per existing employee, anchored to their own joining_date — the
-- domain-meaningful "employment start" fact already used the same way by WorkingDayService's own
-- clampToJoiningDate ("a day before the employee joined was never expected"), not the technical
-- created_at bookkeeping timestamp (see the design gate's own reasoning for why created_at would be
-- wrong for a system carrying bulk-seeded historical employees). This is the same
-- "we cannot reconstruct history we never captured" limitation V159's own backfill (anchored to
-- shifts.created_at, for the exact same reason — no better field existed there) already accepts.
INSERT INTO employee_shift_assignments (employee_user_id, shift_id, effective_from, created_at)
SELECT user_id, shift_id, joining_date, created_at
FROM employees;
