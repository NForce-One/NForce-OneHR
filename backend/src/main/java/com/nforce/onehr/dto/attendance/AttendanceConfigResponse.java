package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalTime;
import java.util.List;

/**
 * Read-only shift/break config for the frontend's Today's Timings panel, resolved per-caller:
 * shiftStart/shiftEnd come from the caller's assigned Shift (ONEHR-108) and its effective Shift
 * Version — every employee is expected to always have one (product invariant; see
 * ShiftDayPolicy's class Javadoc), so AttendanceService.getConfig() fails loudly rather than
 * falling back to any global default if that's ever not the case. weeklyOffDays similarly comes
 * from the caller's assigned WeeklyOffPolicy, else defaults to Saturday/Sunday. See
 * AttendanceService.getConfig().
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceConfigResponse {

    /** Never actually null in practice — getConfig() throws before building this response if the caller has no Shift assigned. */
    private String shiftName;
    private LocalTime shiftStart;
    /** Never actually null in practice — getConfig() throws before building this response if the caller has no Shift assigned. */
    private LocalTime shiftEnd;
    private int lateGraceMinutes;
    // Fractional hours (3.5 = 3h30m). Sourced from the persisted AttendanceRules singleton (see
    // AttendanceRulesService), not AttendanceProperties — see that service's own Javadoc.
    private double halfDayMaxHours;
    /** java.time.DayOfWeek names, e.g. ["SATURDAY", "SUNDAY"]. */
    private List<String> weeklyOffDays;
}
