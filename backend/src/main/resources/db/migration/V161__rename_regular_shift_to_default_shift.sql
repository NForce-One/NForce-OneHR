-- NForce OneHR — Flyway Migration V161
-- Canonical default-shift rename: Shift.DEFAULT_SHIFT_NAME (and every lookup that depends on it)
-- changes from "Regular Shift" (the original V95 seed name) to "Default Shift" — the name
-- discovered, during V160 verification, to already be in place on every live environment checked
-- so far (renamed there through the ordinary Shift Management UI, before OrgService ever guarded
-- the default shift's own identity against renaming/deactivation/deletion).
--
-- This is a plain UPDATE ... SET name on the existing row, never a delete+recreate — the row's id
-- (and therefore every employee's existing shift_id FK) is completely untouched, so no employee
-- assignment can be affected by this migration.
--
-- Handles exactly the scenarios that matter, and refuses to guess at anything else:
--  - Only "Regular Shift" exists  -> renamed in place to "Default Shift".
--  - Only "Default Shift" exists  -> already canonical, left completely untouched (no-op).
--  - Both exist                   -> STOPS the migration; a human must decide which one is the
--                                     real one and reassign/merge manually — this never silently
--                                     deletes or merges either row.
--  - Neither exists                -> STOPS the migration; V95 always seeds "Regular Shift", so
--                                     reaching this state means the row was deleted entirely at
--                                     some point in this environment — also not something to
--                                     guess a fix for here.
DO $$
DECLARE
    regular_shift_id UUID;
    default_shift_id UUID;
BEGIN
    SELECT id INTO regular_shift_id FROM shifts WHERE name = 'Regular Shift';
    SELECT id INTO default_shift_id FROM shifts WHERE name = 'Default Shift';

    IF regular_shift_id IS NOT NULL AND default_shift_id IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot rename the default shift: both ''Regular Shift'' (id=%) and ''Default Shift'' (id=%) exist in this environment. Decide which one is canonical and reassign/merge the other'' employees manually before re-running this migration.', regular_shift_id, default_shift_id;
    ELSIF regular_shift_id IS NULL AND default_shift_id IS NULL THEN
        RAISE EXCEPTION 'Cannot rename the default shift: neither ''Regular Shift'' nor ''Default Shift'' exists in this environment. Investigate and restore the organization''s default shift before re-running this migration.';
    ELSIF regular_shift_id IS NOT NULL THEN
        UPDATE shifts SET name = 'Default Shift' WHERE id = regular_shift_id;
    END IF;
    -- else: default_shift_id IS NOT NULL and regular_shift_id IS NULL -> already canonical, no-op.
END $$;
