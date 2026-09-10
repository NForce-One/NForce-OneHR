-- NForce OneHR — Flyway Migration V174
-- Removes the manager/HR approval workflow from Web Clock-In (V46). A Web Clock-In's attendance
-- effect has always been applied immediately on submit, independent of review status (see
-- WebClockInService's own class Javadoc) — the review itself was pure friction with no real gate,
-- so it's dropped outright rather than reworked. In its place: the employee's Reporting Manager
-- gets a purely informational notification on the first Web Clock-In of each resolved work day
-- (WebClockInService#submit), with no approve/reject decision attached to it at all.

ALTER TABLE web_clock_in_requests DROP COLUMN status;
ALTER TABLE web_clock_in_requests DROP COLUMN assigned_approver_id;
ALTER TABLE web_clock_in_requests DROP COLUMN reviewed_by;
ALTER TABLE web_clock_in_requests DROP COLUMN reviewed_at;
ALTER TABLE web_clock_in_requests DROP COLUMN review_comment;

DROP INDEX IF EXISTS idx_web_clock_in_status;

-- The reason is now a plain attendance note: mandatory only on the first Web Clock-In of an
-- employee's resolved work day, optional on every later cycle that same day — enforced in
-- WebClockInService#submit (it depends on that day's prior rows, not something a column
-- constraint can express), so the column itself must allow NULL.
ALTER TABLE web_clock_in_requests ALTER COLUMN reason DROP NOT NULL;
