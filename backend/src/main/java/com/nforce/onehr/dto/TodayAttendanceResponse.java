package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Drives the punch button. The client renders whichever of canCheckIn/canCheckOut is true and
 * never infers the state itself, so the one-pair-per-day rule lives server-side only.
 */
@Data @Builder
public class TodayAttendanceResponse {
    private LocalDate workDate;
    /**
     * Current time in the business timezone. Lets the client compute elapsed-since-check-in
     * against the same clock the punch was recorded on, instead of the browser's timezone.
     */
    private LocalDateTime serverNow;
    private boolean canCheckIn;
    private boolean canCheckOut;
    /** Null when nothing has been recorded for today yet. */
    private AttendanceResponse record;

    /** Minutes spent on breaks so far today (gaps between closed punch sessions). Null if no record yet. */
    private Integer breakUsedMinutes;
}
