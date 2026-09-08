package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceContext;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.InterpretationOutcome;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The single, narrow, shared owner of Shift-relative punch interpretation — replacing what used
 * to be three independently-duplicated implementations across {@link AttendanceService}
 * ({@code checkIn}/{@code checkOut}/{@code flagMissingCheckoutIfStale}), {@link WebClockInService}
 * (its own private {@code recomputeDerivedFields}), and {@link RegularizationService} (its own
 * private {@code recomputeDerivedFields}/{@code resolveShiftStart}).
 *
 * <p><b>Exactly three responsibilities, nothing more</b> — see the class-level design discussion
 * this was built from:
 * <ol>
 *   <li>Work-date attribution — delegates entirely to {@link ShiftDayPolicy#shiftDayOf}.</li>
 *   <li>Lateness — resolves the Shift Version via {@link ShiftDayPolicy#resolveShiftStart}/
 *       {@link ShiftDayPolicy#resolveLateGraceMinutes} (both backed by {@link ShiftVersionResolver},
 *       so grace is per-Shift-Version, not a single global value — see V168), and returns the
 *       same two facts {@link AttendanceService#checkIn} always has: a grace-aware {@code isLate}
 *       and a raw, no-forgiveness {@code lateByMinutes}.</li>
 *   <li>Checkout/staleness boundary — {@link ShiftDayPolicy#shiftEndAt} and
 *       {@link ShiftDayPolicy#maximumAttendanceBoundary}.</li>
 * </ol>
 * It does NOT compute worked duration, actual break, expected hours, or anything Tracking-Policy-
 * related — those already have their own, unduplicated owners ({@link AttendanceService}'s own
 * worked-minutes/break arithmetic, {@link ExpectedWorkHoursService}, {@link ExceptionService}/
 * {@link ConfiguredAttendancePolicyEngine}) and are not touched by this class.
 *
 * <h2>Four entry points, never confused about which Shift they resolve against</h2>
 * <ul>
 *   <li>{@link #interpretFreshAction} — a brand-new Check-In/Web Clock-In click: no prior
 *       Attendance context exists for the date {@code context.getNow()} resolves to. Derives the
 *       work-date from that timestamp AND resolves lateness, both against the employee's CURRENT
 *       Shift — correct, since there is nothing to preserve yet.</li>
 *   <li>{@link #interpretForKnownWorkDate} — a Regularization-created row for a date with no
 *       prior punch: the work-date is already known (the employee-picked correction date), so
 *       there is nothing to derive — only lateness is resolved, against the employee's CURRENT
 *       Shift (again correct: no prior context exists for that date either).</li>
 *   <li>{@link #interpretExistingSession} — an open session being checked out or swept for
 *       staleness: resolves work-day/cutoff/staleness-boundary against THAT ROW's own snapshotted
 *       {@code shiftId}, never the employee's current Shift.</li>
 *   <li>{@link #interpretExistingRecordLateness} — a Regularization correction to an ALREADY
 *       EXISTING row (its check-in time is being changed): resolves lateness against THAT ROW's
 *       own snapshotted {@code shiftId}, never the employee's current Shift — the direct fix for
 *       "employee reassigned since this historical record's date" silently changing a correction's
 *       computed lateness.</li>
 * </ul>
 * The dividing line is never "which method is convenient" — it's always "does an Attendance row
 * already exist for this work-date." If yes, its own {@code shiftId} governs, full stop.
 *
 * <h2>Legacy rows — explicit, never guessed</h2>
 * A row with {@code shiftId == null} predates this column entirely (see V163's migration comment)
 * — both {@link #interpretExistingSession} and {@link #interpretExistingRecordLateness} return
 * {@link InterpretationOutcome#LEGACY_UNRESOLVED} for it, with every other field null. It is NEVER
 * resolved by falling back to the employee's current Shift (that would silently reintroduce the
 * exact historical-corruption problem this whole design exists to prevent) and NEVER guessed via
 * a plain calendar-date rollover (a genuinely overnight Shift makes that guess provably wrong).
 * Callers decide what "unresolved" means for their own operation — e.g. an open legacy session is
 * left alone by the existing MISSING_CHECKOUT mechanism rather than assigned a fabricated cutoff;
 * a historical correction surfaces the outcome rather than presenting a computed lateness figure
 * as reliable.
 *
 * <h2>A genuine invariant violation — loud, never silently degraded</h2>
 * {@link #interpretFreshAction}/{@link #interpretForKnownWorkDate} resolve against
 * {@code employee.getShift()}, which the mandatory-Shift invariant (DB {@code NOT NULL},
 * create-time fail-loud, reassignment rejection) guarantees is never null for a real employee. If
 * {@link ShiftDayPolicy} ever throws here regardless, this class does NOT catch it and does NOT
 * substitute a plain calendar-date attribution (a 22:00→07:00 overnight employee would be
 * silently misattributed to the wrong logical workday) — the exception propagates as-is, exactly
 * the loud, typed, alertable failure it already is today, just from one shared call site instead
 * of three duplicated ones.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceInterpretationService {

    private final ShiftDayPolicy shiftDayPolicy;
    private final ShiftRepository shiftRepository;

    /**
     * A brand-new Check-In/Web Clock-In click — derives the work-date from {@code context.getNow()}
     * (against the employee's CURRENT Shift) and delegates to {@link #interpretForKnownWorkDate}
     * for lateness against that same date/instant/Shift.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretFreshAction(Employee employee, AttendanceContext context) {
        LocalDate workDate = shiftDayPolicy.shiftDayOf(employee, context.getNow());
        return interpretForKnownWorkDate(employee, workDate, context.getNow());
    }

    /**
     * Lateness for an employee/work-date/check-in-instant that's already known — used for (a)
     * {@link #interpretFreshAction}'s own delegation above, and (b) a Regularization-created row
     * for a date with no prior punch, where the work-date is the employee-picked correction date,
     * not something to derive from a timestamp. Resolves against the employee's CURRENT Shift —
     * correct in both cases, since neither has any prior Attendance context to preserve. Mirrors
     * the exact formula {@link AttendanceService#checkIn} has always used: {@code isLate} is
     * grace-aware (deadline = shiftStart + grace), {@code lateByMinutes} is the raw,
     * no-forgiveness minutes past shiftStart itself (an employee-facing display value, never
     * grace-forgiven) — both anchored to full date-aware instants, never a bare {@link LocalTime},
     * so an overnight shift's post-midnight arrival is measured correctly.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretForKnownWorkDate(Employee employee, LocalDate workDate, LocalDateTime checkInAt) {
        LocalTime shiftStart = shiftDayPolicy.resolveShiftStart(employee, workDate);
        LocalDateTime shiftStartAt = LocalDateTime.of(workDate, shiftStart);
        int graceMinutes = shiftDayPolicy.resolveLateGraceMinutes(employee, workDate);
        LocalDateTime deadlineAt = shiftStartAt.plusMinutes(graceMinutes);
        boolean isLate = checkInAt.isAfter(deadlineAt);
        int lateByMinutes = checkInAt.isAfter(shiftStartAt)
                ? (int) Math.ceil(Duration.between(shiftStartAt, checkInAt).getSeconds() / 60.0)
                : 0;
        return AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .workDate(workDate)
                .isLate(isLate)
                .lateByMinutes(lateByMinutes)
                .shiftId(employee.getShift().getId())
                .build();
    }

    /**
     * For an already-created Attendance row (an open session being checked out or swept for
     * staleness) — resolves work-day/checkout-cutoff/staleness-boundary against THAT ROW's own
     * snapshotted {@code shiftId}, never the employee's current Shift. {@code now} is the caller's
     * already-resolved "current instant" (from the record's own locked-in timezone — see
     * {@code resolveZone} — unrelated to this method).
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretExistingSession(Attendance record, LocalDateTime now) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return AttendanceInterpretation.legacyUnresolved();
        }
        LocalDate workDateOfNow = shiftDayPolicy.shiftDayOf(shiftContext, now);
        LocalDateTime cutoff = shiftDayPolicy.shiftEndAt(shiftContext, record.getWorkDate());
        LocalDateTime maximumBoundary = shiftDayPolicy.maximumAttendanceBoundary(shiftContext, record.getWorkDate());
        return AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .workDate(workDateOfNow)
                .checkoutCutoff(cutoff)
                .maximumBoundary(maximumBoundary)
                .shiftId(shiftContext.getShift().getId())
                .build();
    }

    /**
     * Lateness for a Regularization correction to an ALREADY EXISTING Attendance row (its
     * check-in time is being changed) — resolves against THAT ROW's own snapshotted
     * {@code shiftId} and its own {@code workDate}, never the employee's current Shift. This is
     * the direct fix for "employee reassigned to a different Shift since this historical record's
     * date" silently changing a regularization correction's computed lateness.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretExistingRecordLateness(Attendance record, LocalDateTime checkInAt) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return AttendanceInterpretation.legacyUnresolved();
        }
        return interpretForKnownWorkDate(shiftContext, record.getWorkDate(), checkInAt);
    }

    /**
     * The scheduled shift start/end for an ALREADY EXISTING Attendance row — resolved against
     * THAT ROW's own snapshotted {@code shiftId} and its own {@code workDate}, never the
     * employee's current Shift (same rule as {@link #interpretExistingSession}/
     * {@link #interpretExistingRecordLateness}). Powers the Attendance Log's shift-boundary
     * markers so they keep comparing an old record against the shift it was ACTUALLY worked
     * under, even after the employee is later reassigned or that Shift's timing changes for the
     * future — see {@link ShiftDayPolicy}'s own "Shift Versions" Javadoc section.
     *
     * <p>Reuses {@link ShiftDayPolicy#shiftStartAt}/{@link #shiftEndAt}, so it inherits the same
     * overnight handling (end rolls to {@code workDate + 1} when the effective version's end time
     * is not after its start) — both returned as plain {@link LocalDateTime}s in the record's own
     * historical wall-clock basis, i.e. the same basis {@code checkInAt}/{@code checkOutAt} are
     * already in (see {@code Attendance.timezone}), so a caller can compare them directly without
     * any zone conversion of its own.
     *
     * <p>Returns {@link ScheduledShiftWindow#EMPTY} for a legacy row ({@code shiftId == null}) —
     * never guessed, exactly like {@link #interpretExistingSession}'s own
     * {@link InterpretationOutcome#LEGACY_UNRESOLVED} handling.
     */
    @Transactional(readOnly = true)
    public ScheduledShiftWindow resolveScheduledWindow(Attendance record) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return ScheduledShiftWindow.EMPTY;
        }
        LocalDateTime start = shiftDayPolicy.shiftStartAt(shiftContext, record.getWorkDate());
        LocalDateTime end = shiftDayPolicy.shiftEndAt(shiftContext, record.getWorkDate());
        return new ScheduledShiftWindow(start, end);
    }

    /** Scheduled shift start/end resolved for one Attendance row — see {@link #resolveScheduledWindow}. */
    public record ScheduledShiftWindow(LocalDateTime start, LocalDateTime end) {
        public static final ScheduledShiftWindow EMPTY = new ScheduledShiftWindow(null, null);
    }

    /**
     * Resolves {@code record.getShiftId()} into a minimal stand-in {@link Employee} carrying ONLY
     * that snapshotted {@link Shift} — {@link ShiftDayPolicy}'s methods read nothing else off
     * {@code Employee} (see its own class Javadoc: {@code shiftOf(employee)} is its only
     * touchpoint), so this is a safe, non-invasive way to make them resolve against the record's
     * own historical Shift instead of the real employee's current one, without modifying
     * {@link ShiftDayPolicy} itself at all. Returns {@code null} for a legacy row
     * ({@code shiftId == null}) — callers must return {@link InterpretationOutcome#LEGACY_UNRESOLVED}
     * rather than substitute the employee's current Shift.
     *
     * <p>If {@code record.getShiftId()} is set but no longer resolves to a real {@link Shift} row,
     * this throws loudly rather than silently degrading — {@code OrgService#deleteShift} rejects
     * deleting any Shift a real Attendance row still references (see V163's FK), so this should be
     * structurally unreachable; a violation here signals data corruption elsewhere, not a case to
     * paper over.
     */
    private Employee resolveShiftContextOrNull(Attendance record) {
        if (record.getShiftId() == null) {
            log.warn("Attendance {} (employee {}, workDate {}) has no Shift snapshot — treating as "
                            + "LEGACY_UNRESOLVED rather than substituting the employee's current Shift",
                    record.getId(), record.getEmployeeUserId(), record.getWorkDate());
            return null;
        }
        Shift snapshotShift = shiftRepository.findById(record.getShiftId())
                .orElseThrow(() -> new IllegalStateException(
                        "Attendance " + record.getId() + " references shift " + record.getShiftId()
                                + " which no longer exists — Shift deletion should be blocked once any "
                                + "Attendance references it (see OrgService#deleteShift); this indicates "
                                + "data corruption, not a case to fall back from."));
        return Employee.builder()
                .userId(record.getEmployeeUserId())
                .shift(snapshotShift)
                .build();
    }
}
