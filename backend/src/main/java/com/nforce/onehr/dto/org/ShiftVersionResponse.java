package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.ShiftVersion;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** One row of a Shift's version history — backs the Shifts tab's version drill-down. */
@Value
public class ShiftVersionResponse {
    UUID id;
    LocalTime startTime;
    LocalTime endTime;
    Integer breakMinutes;
    Integer lateGraceMinutes;
    LocalDate effectiveFrom;

    public static ShiftVersionResponse from(ShiftVersion v) {
        return new ShiftVersionResponse(v.getId(), v.getStartTime(), v.getEndTime(), v.getBreakMinutes(),
                v.getLateGraceMinutes(), v.getEffectiveFrom());
    }
}
