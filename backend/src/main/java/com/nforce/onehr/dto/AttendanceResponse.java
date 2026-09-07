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
}
