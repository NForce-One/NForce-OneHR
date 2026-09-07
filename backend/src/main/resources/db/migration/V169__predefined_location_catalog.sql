-- Finalized Location/Timezone model: Locations become a controlled, predefined catalog (see
-- com.nforce.onehr.service.LocationCatalog) — each supported Location has exactly one valid IANA
-- timezone, and HR can no longer create an arbitrary Name + Timezone combination.
--
-- This migration is based on an audit of the live data (2026-09-08) before any constraint
-- changed. Findings and the fix each one gets:
--
--   Name              Employees   Timezone (before)     Problem                  Fix
--   ----------------  ---------   --------------------  -----------------------  --------------------------------
--   Bangalore         132         Asia/Kolkata           none                     kept as-is
--   Hyderbad           24         NULL                   typo'd name, no tz       rename -> Hyderabad, tz set
--   Mumbai             16         NULL                   no tz (silently fell     tz set explicitly
--                                                         back to org default,
--                                                         which happened to also
--                                                         be Asia/Kolkata)
--   New York            7         NULL                   same silent-fallback     tz set to America/New_York (this
--                                                         bug — org default is                                  IS a behavior change for these 7
--                                                         Asia/Kolkata, wrong                                    employees' FUTURE attendance only
--                                                         for a US location                                     — see Attendance.timezone)
--   Texas              32         America/New_York       wrong zone entirely      renamed -> Chicago, tz ->
--                                                         (Texas is not Eastern)                                 America/Chicago (confirmed with
--                                                                                                                 product — real data bug fix, future
--                                                                                                                 attendance only)
--   Remote - Global    22         Asia/Kolkata           functionally identical   employees reassigned to
--                                                         to "Remote - India"                                    "Remote - India", then deactivated
--                                                         (one-location-one-tz                                   (kept, not deleted, so its holiday/
--                                                         model no longer allows                                 audit history stays intact)
--                                                         a "global" catch-all)
--   Remote - India      32         Asia/Kolkata           none                     kept as-is (absorbs the 22
--                                                                                                                 reassigned above -> 54 total)
--   Vishakhapatnam      26         Asia/Kolkata           none                     kept as-is
--
-- Chennai and Los Angeles are added with zero current employees — required by the product
-- decision to support the India/US catalog in full (see LocationCatalog) even though no
-- employee is on them yet.
--
-- 75 employees have location_id NULL entirely (predate location_id being required — see V97) —
-- left untouched; they keep falling back to the org-wide default (attendance_rules
-- .default_timezone), same as before this migration. Assigning them a real Location is an HR
-- data-entry task, not something this migration can safely guess.

-- Hyderbad -> Hyderabad, timezone Asia/Kolkata (was NULL; the org default already happened to be
-- Asia/Kolkata too, so this is a no-op for these employees' resolved timezone — just now explicit
-- and locked in rather than an accidental match).
UPDATE locations SET name = 'Hyderabad', timezone = 'Asia/Kolkata' WHERE name = 'Hyderbad';

-- Mumbai: timezone was NULL, silently matching the org default by coincidence. Made explicit.
UPDATE locations SET timezone = 'Asia/Kolkata' WHERE name = 'Mumbai';

-- New York: timezone was NULL, so these 7 employees were actually resolving to the org default
-- (Asia/Kolkata) — clearly wrong for a US location. This DOES change their future attendance
-- interpretation (to America/New_York); no existing Attendance row is touched (each already
-- snapshots its own resolved timezone at check-in — see Attendance.timezone).
UPDATE locations SET timezone = 'America/New_York' WHERE name = 'New York';

-- Texas -> Chicago, timezone corrected from the wrong America/New_York to America/Chicago.
-- Confirmed with product: real data-quality bug (the exact "arbitrary Location + Timezone
-- combination" this feature exists to prevent going forward), fixed by renaming to a specific,
-- catalog-conformant city rather than leaving a vague state name. Future attendance only.
UPDATE locations SET name = 'Chicago', timezone = 'America/Chicago' WHERE name = 'Texas';

-- Remote - Global's employees move to Remote - India (functionally identical: both Asia/Kolkata,
-- and one-location-one-timezone no longer allows a separate "global" catch-all). Remote - Global
-- itself is deactivated below, not deleted — see this file's header and OrgService.deleteLocation
-- (holidays.location_id is NOT NULL, so a hard delete would cascade-delete its holiday calendar
-- history; deactivating preserves it while making the location unselectable going forward).
UPDATE employees SET location_id = (SELECT id FROM locations WHERE name = 'Remote - India')
WHERE location_id = (SELECT id FROM locations WHERE name = 'Remote - Global');

UPDATE locations SET is_active = FALSE WHERE name = 'Remote - Global';

-- New catalog entries with no current employees — see LocationCatalog's own Javadoc for why these
-- specific nine are the initial supported set.
INSERT INTO locations (id, name, city, state, country, timezone)
SELECT gen_random_uuid(), 'Chennai', 'Chennai', 'Tamil Nadu', 'India', 'Asia/Kolkata'
WHERE NOT EXISTS (SELECT 1 FROM locations WHERE name = 'Chennai');

INSERT INTO locations (id, name, city, state, country, timezone)
SELECT gen_random_uuid(), 'Los Angeles', 'Los Angeles', 'California', 'USA', 'America/Los_Angeles'
WHERE NOT EXISTS (SELECT 1 FROM locations WHERE name = 'Los Angeles');

-- Every Location row (active or not) now has a real, non-null timezone — safe to enforce NOT
-- NULL, plus a CHECK restricting it to the fixed set of currently-supported IANA zones (defense
-- in depth alongside LocationCatalog's application-level whitelist: even a direct DB write can no
-- longer leave a Location with a missing or unsupported timezone). Extending the supported set
-- later (a new LocationCatalog entry with a new IANA zone) requires a follow-up migration to widen
-- this CHECK — the same governance level as adding the entry itself.
ALTER TABLE locations ALTER COLUMN timezone SET NOT NULL;

ALTER TABLE locations ADD CONSTRAINT locations_timezone_supported_check
    CHECK (timezone IN ('Asia/Kolkata', 'America/New_York', 'America/Chicago', 'America/Los_Angeles'));
