package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder
public class AttendanceResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private LocalDate workDate;
    /** The day's original check-in — stays fixed across lunch-break resumes (late calculations
     * anchor to it). For "what time did the current/most recent session start," use
     * sessionStartedAt instead. */
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    /** When the most recent session started — updates on every check-in, including a resume
     * after a break, unlike checkInAt. Null only for records predating this field. */
    private LocalDateTime sessionStartedAt;
    private Integer workedMinutes;
    private String status;
    private Integer lateByMinutes;
    /** SYSTEM for a normal punch, REGULARIZATION if this row came from an approved correction. */
    private String source;
    /** The employee's configured work mode (ONSITE/REMOTE/HYBRID) at the time of the query. */
    private String workMode;
    /** IANA zone id the browser/device reported at Check-In — see Attendance.timezone. Null for
     * records predating this field or where none was supplied (Location.timezone was used). */
    private String timezone;
    /** Scheduled shift start for this row's own workDate, resolved against THAT ROW's own
     * snapshotted Shift (never the employee's current one) — see
     * AttendanceInterpretationService#resolveScheduledWindow. Same wall-clock basis as
     * checkInAt/checkOutAt. Null for a legacy row predating the shiftId snapshot. Powers the
     * Attendance Log's shift-boundary markers only — never used for lateness/cutoff math, which
     * has its own dedicated resolution path. */
    private LocalDateTime shiftStartAt;
    /** Scheduled shift end for this row's own workDate — see {@link #shiftStartAt}. Rolls to the
     * next calendar day for an overnight shift (end not after start). Null for a legacy row. */
    private LocalDateTime shiftEndAt;
    /** Start of this row's logical WORKDAY (never calendar midnight) — see
     * AttendanceInterpretationService#resolveScheduledWindow / ShiftDayPolicy#workdayStartAt. The
     * Attendance timeline's track spans workdayStartAt..workdayEndAt, not 00:00-24:00, so a
     * post-midnight punch still positions correctly relative to the shift it belongs to. Null for
     * a legacy row predating the shiftId snapshot, same as shiftStartAt/shiftEndAt. */
    private LocalDateTime workdayStartAt;
    /** End of this row's logical WORKDAY — see {@link #workdayStartAt} /
     * ShiftDayPolicy#workdayEndAt (the same instant as ShiftDayPolicy#maximumAttendanceBoundary
     * for this workDate). Null for a legacy row. */
    private LocalDateTime workdayEndAt;
}
