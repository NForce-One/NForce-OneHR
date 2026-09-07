-- "At most one open (check_out_at IS NULL) punch per attendance_record_id" has only ever been
-- enforced in Java (AttendanceService#openPunch's own defensive close-stragglers logic) — a
-- confirmed race exists where two concurrent resume-Check-In requests for the same employee/day
-- can both pass that Java-level "no open punch" scan before either commits its INSERT, leaving
-- two simultaneously-open punches under one attendance_records row.
--
-- Step 1 — self-heal any duplicate open punches that may already exist in this environment before
-- the unique index below can be created. This does NOT delete any row (every historical punch is
-- preserved) — it only closes every open punch except the most-recently-opened one per record, by
-- setting its check_out_at to the check-in time of the NEXT open punch in the same group. This is
-- exactly the same closing convention AttendanceService#openPunch already applies at runtime when
-- it defensively closes a straggler, so a repaired row reads the same way a normal same-day
-- session boundary would. On a fresh database (no attendance_punches rows yet), every WHERE
-- clause below matches nothing and this is a no-op.
WITH ranked_open_punches AS (
    SELECT
        id,
        attendance_record_id,
        check_in_at,
        COUNT(*) OVER (PARTITION BY attendance_record_id) AS open_count,
        LEAD(check_in_at) OVER (PARTITION BY attendance_record_id ORDER BY check_in_at ASC) AS next_check_in_at
    FROM attendance_punches
    WHERE check_out_at IS NULL
)
UPDATE attendance_punches p
SET check_out_at = r.next_check_in_at
FROM ranked_open_punches r
WHERE p.id = r.id
  AND r.open_count > 1
  AND r.next_check_in_at IS NOT NULL;

-- Step 2 — enforce the invariant at the database level going forward. Additive: a fresh-database
-- replay hits an empty table; an environment with pre-existing data was just repaired by step 1
-- above, so this cannot fail on data it didn't already fix.
CREATE UNIQUE INDEX idx_attendance_punches_one_open_per_record
    ON attendance_punches (attendance_record_id)
    WHERE check_out_at IS NULL;
