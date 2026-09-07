package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Value
public class ShiftResponse {
    UUID id;
    String name;
    String code;
    String description;
    // The version currently in effect (as of today) — same shape the P1 surface has always shown,
    // now resolved through Shift Versioning rather than read directly off the Shift row (which no
    // longer carries timing at all — see ShiftVersion's own Javadoc).
    LocalTime startTime;
    LocalTime endTime;
    Integer breakMinutes;
    // Every Shift Version has its own grace (see ShiftVersion.lateGraceMinutes/V168) — no single
    // global value applies across shifts anymore.
    Integer lateGraceMinutes;
    // Null when no future version is scheduled. Present only for a version whose effectiveFrom is
    // strictly after today — "at most one pending version per Shift" is enforced in OrgService.
    LocalDate pendingEffectiveFrom;
    LocalTime pendingStartTime;
    LocalTime pendingEndTime;
    Integer pendingBreakMinutes;
    Integer pendingLateGraceMinutes;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;

    // flexible/workingDays intentionally excluded from the P1 surface — see
    // CreateShiftRequest's own comment. The Shift entity/DB columns still exist (unused).
    public static ShiftResponse from(Shift s, ShiftVersion currentVersion, ShiftVersion pendingVersionOrNull, long employeeCount) {
        return new ShiftResponse(s.getId(), s.getName(), s.getCode(), s.getDescription(),
                currentVersion.getStartTime(), currentVersion.getEndTime(), currentVersion.getBreakMinutes(),
                currentVersion.getLateGraceMinutes(),
                pendingVersionOrNull != null ? pendingVersionOrNull.getEffectiveFrom() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getStartTime() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getEndTime() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getBreakMinutes() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getLateGraceMinutes() : null,
                s.isActive(), employeeCount, s.getCreatedAt());
    }
}
