-- NForce OneHR — Flyway Migration V173
-- Future-effective Location timezone changes (ONEHR-336 follow-up): a queued future timezone
-- change, resolved by date on demand — see Location.pendingTimezone/pendingTimezoneEffectiveFrom's
-- own Javadoc and AttendanceRulesService#resolveEmployeeZoneId for the resolution itself.
--
-- A dedicated LocationVersion/EmployeeLocationAssignment model was deliberately NOT introduced —
-- unlike Shift timing (which genuinely needs "what governed an arbitrary historical work-date,"
-- hence ShiftVersion), nothing in this codebase ever asks "what was this Location's timezone on
-- some past date" — every existing Attendance row already immutably snapshots its own resolved
-- timezone at capture time (Attendance.timezone), independent of how the CURRENT/pending value is
-- stored. A simple pending pair on the Location row itself is the safest, minimal representation
-- for the one question this actually needs to answer: "is there a future value, and has its date
-- arrived yet."
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS pending_timezone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pending_timezone_effective_from DATE;

-- Same fixed-set guard as the live timezone column (see V169/V170) — a queued future value must
-- also be one of the organization's actually-supported zones, never an arbitrary one.
ALTER TABLE locations ADD CONSTRAINT locations_pending_timezone_supported_check
    CHECK (pending_timezone IS NULL OR pending_timezone IN ('Asia/Kolkata', 'America/New_York', 'America/Chicago', 'America/Los_Angeles'));
