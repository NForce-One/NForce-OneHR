-- Concurrent Check-Out (or a concurrent Regularization approval, or the hourly stale-session
-- sweeper racing a live user action) can read-modify-write the same attendance_records row with
-- nothing else coordinating between them today — the loser's UPDATE silently overwrites the
-- winner's check_out_at/worked_minutes/status with stale values (a classic lost update). This
-- mirrors V153's identical fix for leave_balances: a version column turns that silent race into
-- an ObjectOptimisticLockingFailureException the caller sees as a clean, retryable conflict (see
-- GlobalExceptionHandler's existing handler for it) instead of quietly wrong attendance data.
--
-- DEFAULT 0 + NOT NULL is deployment-safe in both directions: OLD CODE (not yet aware of this
-- column) simply never selects or sets it, so its INSERT/UPDATE statements are unaffected and the
-- column keeps incrementing only when NEW CODE (Hibernate's @Version) writes the row; NEW CODE
-- reading a row written by OLD CODE just sees version 0, which is a valid starting version. No
-- backfill is required — every existing row already satisfies NOT NULL via the DEFAULT.
ALTER TABLE attendance_records
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
