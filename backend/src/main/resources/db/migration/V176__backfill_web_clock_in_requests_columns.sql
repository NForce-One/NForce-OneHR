-- NForce OneHR — Flyway Migration V176
-- Repairs schema drift on this shared dev database: web_clock_in_requests is missing several
-- columns its very first migration (V46__create_web_clock_in_requests.sql) already declares —
-- assigned_approver_id, status, reviewed_by, reviewed_at, review_comment — which was breaking
-- every /api/approvals request with "column ... does not exist" (WebClockInRequest entity maps
-- all of these). Numbered above this environment's already-applied schema version (175, ahead
-- of this repo's committed migration files — see application.yml's flyway comment on this being
-- a shared dev DB other branches migrate against directly) so Flyway is guaranteed to actually
-- run it rather than treat it as already-applied. All additive and IF-NOT-EXISTS-guarded so it's
-- safe to run against an environment that's only partially missing these.

ALTER TABLE web_clock_in_requests
    ADD COLUMN IF NOT EXISTS assigned_approver_id UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS reviewed_by    UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS reviewed_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS review_comment TEXT;

-- Existing rows predate the status column and all land on the DEFAULT ('PENDING') above. A row
-- that already has a checked_out_at could only have gotten there via an approved clock-in (see
-- V46's header comment: check-out needs no separate approval, but a clock-in does), so backfill
-- those to APPROVED rather than leaving every historical completed request misclassified as
-- still pending. Rows without checked_out_at stay PENDING — genuinely ambiguous otherwise.
UPDATE web_clock_in_requests SET status = 'APPROVED'
    WHERE checked_out_at IS NOT NULL AND status = 'PENDING';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'web_clock_in_requests_status_check'
    ) THEN
        ALTER TABLE web_clock_in_requests
            ADD CONSTRAINT web_clock_in_requests_status_check
            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_web_clock_in_status ON web_clock_in_requests(status);

-- NOTE: deliberately NOT (re)creating V46's idx_web_clock_in_one_pending_per_date unique index
-- here. This environment's pre-existing rows predate the status column entirely, so several
-- share an (employee_user_id, work_date) pair that would violate that uniqueness once backfilled
-- to PENDING/APPROVED above — safely deduplicating that history is a separate, larger cleanup
-- outside the scope of this drift-repair migration, and nothing in the current bug fixes depends
-- on this constraint existing.
