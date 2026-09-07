-- Shift Versioning: makes a Shift's timing (start/end/break) effective-dated instead of a single
-- mutable value on the `shifts` row itself. See ShiftVersion's own Javadoc for the full design —
-- summary: every timing consumer resolves "the latest version whose effective_from <= workDate"
-- (ShiftVersionResolver), so a change to a Shift's timing can never silently reinterpret an
-- already-recorded Attendance/AttendanceException/AttendancePenalty/regularization for a date
-- before the new version takes effect.
--
-- `shifts` keeps only its identity fields (name/code/description/active/flexible/working_days,
-- the latter two already dead/P2 — unchanged by this migration) — start_time/end_time/
-- break_minutes move to this new child table, one row per timing configuration.
--
-- Existing shift rows become "Version 1" of themselves: effective_from is set to the shift's own
-- created_at date, the earliest date any real Attendance could possibly reference it (an employee
-- cannot have been assigned a shift before the shift row existed) — not invented, not a guess at
-- when the CURRENT values first became correct (this table has no way to know that; V117/
-- MigrationSeededShiftTimeFix already established that in-place corrections happened before this
-- migration existed, and there is no way to reconstruct exactly when each one took effect). This
-- is the same "we cannot reconstruct history we never captured" limitation any effective-dating
-- scheme faces for pre-migration data — strictly no worse than today's behavior, and now at least
-- auditably labeled as a version rather than indistinguishable from "current".
CREATE TABLE IF NOT EXISTS shift_versions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    shift_id        UUID         NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    start_time      TIME         NOT NULL,
    end_time        TIME         NOT NULL,
    break_minutes   INTEGER,
    effective_from  DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- At most one version of a given Shift may take effect on any single date — also what makes
    -- "replace the pending version" (OrgService) a safe delete-then-insert rather than needing to
    -- guess which of several same-dated rows is the intended one.
    CONSTRAINT uq_shift_versions_shift_effective_from UNIQUE (shift_id, effective_from)
);

INSERT INTO shift_versions (shift_id, start_time, end_time, break_minutes, effective_from, created_at)
SELECT id, start_time, end_time, break_minutes, created_at::date, created_at
FROM shifts;

ALTER TABLE shifts
    DROP COLUMN start_time,
    DROP COLUMN end_time,
    DROP COLUMN break_minutes;
