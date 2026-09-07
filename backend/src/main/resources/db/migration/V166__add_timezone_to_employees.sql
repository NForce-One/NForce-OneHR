-- Confirmed business requirement (Phase 2 timezone pass): the employee's own configured
-- timezone must be authoritative for attendance calculations, taking precedence over both the
-- browser's reported zone (already ignored — see AttendanceService.resolveZone) and the
-- employee's Location.timezone. No such per-employee field existed before this migration —
-- timezone was previously only configurable at Location granularity.
--
-- Nullable, no backfill: an employee with no timezone set here simply falls through to
-- Location.timezone, then to the org-wide default (see V167's attendance_rules.default_timezone)
-- — exactly the same fallback chain that already existed before this column, so this is
-- deployment-safe in both directions (OLD CODE never reads this column; NEW CODE reading a row
-- where it's still null behaves identically to before this migration).
ALTER TABLE employees
    ADD COLUMN timezone VARCHAR(50);
