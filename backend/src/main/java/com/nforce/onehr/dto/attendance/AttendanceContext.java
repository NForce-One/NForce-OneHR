package com.nforce.onehr.dto.attendance;

import lombok.Value;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The raw, always-available facts of an attendance action — employee identity, the server clock
 * reading, and the already-resolved zone it was read in (Location → global fallback; see
 * AttendanceService/WebClockInService's own {@code resolveZone} — unchanged, and never Shift,
 * never the browser-reported zone). Deliberately contains nothing Shift-derived: this is exactly
 * what a punch has before any Shift interpretation happens.
 */
@Value
public class AttendanceContext {
    UUID employeeId;
    LocalDateTime now;
    ZoneId zone;
}
