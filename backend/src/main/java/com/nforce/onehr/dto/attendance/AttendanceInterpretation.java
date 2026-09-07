package com.nforce.onehr.dto.attendance;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The output of {@link com.nforce.onehr.service.AttendanceInterpretationService} — everything a
 * caller (AttendanceService/WebClockInService/RegularizationService) needs to finalize a punch or
 * decide staleness, without any of them re-deriving Shift-relative facts themselves. Which fields
 * are populated depends on which of the service's two entry points produced it (mirrors
 * {@link PolicyEvaluationContext}'s own established convention of a single fact-carrying type with
 * fields that only apply to some callers):
 * <ul>
 *   <li>{@code interpretFreshAction} populates {@code workDate}/{@code isLate}/
 *       {@code lateByMinutes}/{@code shiftId} — {@code checkoutCutoff}/{@code maximumBoundary}
 *       are null (there is no existing session yet to bound).</li>
 *   <li>{@code interpretExistingSession} populates {@code workDate} (the shift-day {@code now}
 *       resolves to, for the caller's own staleness comparison against the record's stored
 *       {@code workDate} — this class never makes that comparison itself), {@code checkoutCutoff},
 *       {@code maximumBoundary}, and {@code shiftId} (the record's own snapshot, echoed back) —
 *       {@code isLate}/{@code lateByMinutes} are null (lateness for an existing record was already
 *       fixed at its own creation and is never recomputed here).</li>
 * </ul>
 * {@code outcome} is {@link InterpretationOutcome#LEGACY_UNRESOLVED} only for
 * {@code interpretExistingSession} on a row with no {@code shiftId} snapshot — every other field
 * is then null, and the caller MUST degrade explicitly rather than substitute anything.
 */
@Value
@Builder
public class AttendanceInterpretation {
    InterpretationOutcome outcome;
    LocalDate workDate;
    Boolean isLate;
    Integer lateByMinutes;
    LocalDateTime checkoutCutoff;
    LocalDateTime maximumBoundary;
    UUID shiftId;

    public static AttendanceInterpretation legacyUnresolved() {
        return AttendanceInterpretation.builder().outcome(InterpretationOutcome.LEGACY_UNRESOLVED).build();
    }

    public boolean isLegacyUnresolved() {
        return outcome == InterpretationOutcome.LEGACY_UNRESOLVED;
    }
}
