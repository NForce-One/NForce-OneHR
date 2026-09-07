-- Phase 2 timezone pass: app.attendance.zone (application.yml) is the org-wide fallback IANA
-- zone consulted whenever neither an employee's own timezone nor their Location's is set. This
-- is a genuine HR/business default (same classification reasoning as V162's half_day_max_hours),
-- not deploy-time technical configuration — every environment ultimately runs the same business,
-- so it belongs in the same Admin-configurable singleton rather than in AttendanceProperties.
--
-- Seeded with the exact current YAML default (app.attendance.zone: Asia/Kolkata) so no existing
-- org's attendance calculations change on deploy. NOT NULL with this default is deployment-safe:
-- every existing (single) row gets the same value the YAML default already provided at runtime.
ALTER TABLE attendance_rules
    ADD COLUMN default_timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata';
