-- NForce OneHR — Flyway Migration V160
-- Enforces the mandatory Default Shift product invariant (ONEHR-108 follow-up) at the schema
-- level: every employee is expected to always have an assigned Shift — see ShiftDayPolicy's
-- class Javadoc and the create-time hard-fails in UserManagementService/EmployeeService. This was
-- previously only enforced in application code (plus ShiftSeedCorrector's best-effort startup
-- backfill, which silently skips if the default shift is inactive) — never at the database level,
-- so a direct SQL write or a bug in some other write path could still leave employees.shift_id
-- NULL without anything noticing. This backfills any remaining NULL rows to the seeded default
-- ('Regular Shift', by its stable name — same lookup OrgService/UserManagementService/
-- EmployeeService all use), then makes the column NOT NULL so the invariant can never again be
-- silently violated.
--
-- The default-shift lookup only happens if there's actually a NULL row to fix — an environment
-- that already has zero NULL shift_id rows (every employee already assigned, e.g. via the
-- create-time defaults and ShiftSeedCorrector already in place) doesn't need "Regular Shift" to
-- exist/be active at all; it goes straight to the NOT NULL constraint. Only an environment that
-- actually still has an unassigned employee needs it, and in that case this fails the migration
-- outright (does not guess, does not leave rows unassigned) if the default shift is missing or
-- inactive — same "fail clearly, not silently" rule applied everywhere else in this invariant's
-- enforcement, rather than picking an arbitrary other shift or leaving the column nullable.
DO $$
DECLARE
    null_count INTEGER;
    default_shift_id UUID;
BEGIN
    SELECT COUNT(*) INTO null_count FROM employees WHERE shift_id IS NULL;

    IF null_count > 0 THEN
        SELECT id INTO default_shift_id FROM shifts WHERE name = 'Regular Shift' AND active = TRUE;

        IF default_shift_id IS NULL THEN
            RAISE EXCEPTION 'Cannot enforce employees.shift_id NOT NULL: % employee row(s) have no shift, and the organization''s default shift (''Regular Shift'') is missing or inactive to backfill them. Restore/reactivate it (or manually reassign every NULL-shift employee) before re-running this migration.', null_count;
        END IF;

        UPDATE employees SET shift_id = default_shift_id WHERE shift_id IS NULL;

        -- Defense in depth: the UPDATE above should already account for every NULL row, but this
        -- gives a clear, specific failure instead of an opaque constraint-violation error below
        -- if something unexpected (e.g. a concurrent insert) left one behind.
        SELECT COUNT(*) INTO null_count FROM employees WHERE shift_id IS NULL;
        IF null_count > 0 THEN
            RAISE EXCEPTION 'Cannot enforce employees.shift_id NOT NULL: % employee row(s) still have a NULL shift_id after backfill.', null_count;
        END IF;
    END IF;
END $$;

ALTER TABLE employees ALTER COLUMN shift_id SET NOT NULL;
