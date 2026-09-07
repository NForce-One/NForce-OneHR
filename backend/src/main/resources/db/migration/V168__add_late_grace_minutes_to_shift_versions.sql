-- Confirmed business requirement (Phase 3 grace pass): every Shift has its own grace period,
-- applying to every workday that Shift Version governs. Previously there was a single global
-- app.attendance.late-grace-minutes value (YAML, default 10) — the sole runtime source read by
-- AttendanceInterpretationService (the one shared choke point behind Check-In, Web Clock-In, and
-- Regularization's lateness computation, after this codebase's earlier Shift/Attendance
-- decoupling work). This migration moves that value onto shift_versions, consistent with every
-- other timing field (start_time/end_time/break_minutes) already effective-dated there rather
-- than on shifts itself.
--
-- Backfilled with the exact current YAML default (10) for every EXISTING version, so no existing
-- Shift's LATE/PRESENT classification changes on deploy — Admin can then set a different grace
-- per Shift going forward via the Shifts tab, exactly like start/end time.
ALTER TABLE shift_versions
    ADD COLUMN late_grace_minutes INTEGER;

UPDATE shift_versions SET late_grace_minutes = 10 WHERE late_grace_minutes IS NULL;

ALTER TABLE shift_versions
    ALTER COLUMN late_grace_minutes SET NOT NULL;
