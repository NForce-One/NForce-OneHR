-- NForce OneHR — Flyway Migration V177
-- Undoes a since-deleted local migration (V176__backfill_web_clock_in_requests_columns.sql) that
-- was written against a checkout that hadn't yet pulled V174 (remove_web_clock_in_approval) and
-- V175 (drop_stray_one_web_clock_in_per_date_index) from dev, which deliberately DROP these same
-- five columns as part of removing the Web Clock-In manager/HR approval workflow entirely (see
-- V174's header comment). V176 re-added them, unaware they were an intentional removal rather
-- than drift. Numbered above V176's own recorded version so Flyway is guaranteed to run it
-- (a version already in flyway_schema_history is never re-applied, even under a new file).
--
-- Mirrors V174's drops exactly.

ALTER TABLE web_clock_in_requests DROP COLUMN IF EXISTS status;
ALTER TABLE web_clock_in_requests DROP COLUMN IF EXISTS assigned_approver_id;
ALTER TABLE web_clock_in_requests DROP COLUMN IF EXISTS reviewed_by;
ALTER TABLE web_clock_in_requests DROP COLUMN IF EXISTS reviewed_at;
ALTER TABLE web_clock_in_requests DROP COLUMN IF EXISTS review_comment;

DROP INDEX IF EXISTS idx_web_clock_in_status;
