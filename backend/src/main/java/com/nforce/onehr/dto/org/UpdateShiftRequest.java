package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class UpdateShiftRequest {

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
    // global default. Like every other field here, this only takes effect on effectiveFrom —
    // never reinterprets an already-effective version.
    private Integer lateGraceMinutes;

    // Shift changes are always future-effective (never today, never in the past) — see
    // OrgService#updateShift's own validation and ShiftVersion's Javadoc for why: the current
    // configuration must remain in effect for every date up to and including today, exactly as it
    // already was, and only the new one is scheduled ahead of it. Required on every update — name/
    // code/description-only edits still resubmit the current timing values with a future date;
    // see updateShift's own comment for why this codebase doesn't try to special-case "unchanged".
    @NotNull(message = "Effective From is required")
    private LocalDate effectiveFrom;

    // flexible/workingDays intentionally removed from the P1 surface — see CreateShiftRequest's
    // own comment.
}
