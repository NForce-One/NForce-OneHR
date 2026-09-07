package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
public class CreateShiftRequest {

    @NotBlank(message = "Shift name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 30)
    private String code;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private Integer breakMinutes;

    // Minutes past startTime forgiven before a punch counts as LATE — every Shift has its own
    // (see ShiftVersion.lateGraceMinutes/V168); null defaults to 10, matching the pre-migration
    // global default so an admin who doesn't touch this field gets identical behavior to before.
    private Integer lateGraceMinutes;

    // flexible/workingDays intentionally removed from the P1 surface — flexible is P2 (no shift
    // in P1 has anything but a fixed start/end); workingDays was never consumed by any weekly-off
    // decision (WeeklyOffPolicy is the sole source of truth) and conflicted with it. The DB
    // columns remain for now (see Shift entity) but are no longer settable through this API.
}
