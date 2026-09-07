package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Single source of truth for "what shift-relative day/time does this instant belong to" —
 * extracted from what were previously independently-duplicated private methods on
 * {@link AttendanceService} (and copies in {@link WebClockInService},
 * {@link RegularizationService}, {@link ExceptionService}). Pure and side-effect-free: every
 * method here only computes and returns a value — none of them read or write an
 * {@link com.nforce.onehr.entity.Attendance} row, flag anything, or close a session, or query
 * one — that remains entirely the job of {@link AttendanceService}/{@link StaleAttendanceSweeper}
 * exactly as before; this class only ever answers "what day/time," never "what should happen."
 *
 * <h2>The five concepts, kept deliberately distinct</h2>
 * <ol>
 * <li><b>Scheduled shift end</b> — {@link #shiftEndAt}. What the shift is actually scheduled to
 * end at, per the version effective on the day in question. Unrelated to the boundary below.
 * <li><b>Logical workday reset</b> — {@link #maximumAttendanceBoundary} / {@link #shiftDayOf}.
 * The ONLY thing the organization's {@code maximumShiftDayDurationHours} setting controls: which
 * calendar day a punch is attributed to. It does not close, flag, or create anything.
 * <li><b>Overtime</b> — not modeled here (no dedicated overtime computation exists anywhere in
 * this codebase); working past the scheduled end simply stays part of the same logical workday
 * until the reset.
 * <li><b>Stale/missing-checkout handling</b> — owned entirely by
 * {@link AttendanceService#flagMissingCheckoutIfStale}/{@link WebClockInService}'s own analogous
 * check and {@link StaleAttendanceSweeper}. Those callers now source their "has this record's own
 * day moved on" input from {@link #shiftDayOf} instead of a fixed global cutover — the decision to
 * act on that input (flag/reject) is unchanged and stays entirely with them.
 * <li><b>Auto-checkout</b> — does not exist anywhere in this codebase and is not introduced here.
 * </ol>
 *
 * <h2>Shift Versions — every timing lookup is keyed by a date, never "now"</h2>
 * A {@link Shift}'s timing is effective-dated ({@link ShiftVersion}, resolved via
 * {@link ShiftVersionResolver}) rather than a single mutable value on the Shift itself — every
 * method on this class that needs an actual start/end time resolves the version effective on the
 * SPECIFIC day it was asked about (typically an {@code Attendance.workDate}, fixed once at
 * check-in and never recomputed), never "whichever version happens to be current when this code
 * executes." This is what lets an admin change a Shift's future timing without silently
 * reinterpreting an in-progress or already-recorded Attendance for a date the new version doesn't
 * yet (or no longer) govern — see {@link ShiftVersion}'s own Javadoc for the full design.
 *
 * <h2>Logical-day algorithm</h2>
 * For an employee (every employee has an assigned {@link Shift} — see this class's own "No-shift
 * employees" section below), the day for a given {@code timestamp} is computed relative to that
 * shift's own start (for the day in question), not a fixed clock time. Two rules apply, in order:
 * <ol>
 * <li><b>Today's own start, first.</b> If {@code timestamp} has already reached the version
 * effective on {@code timestamp}'s own calendar date's start time, it unambiguously belongs to
 * that calendar date — regardless of what YESTERDAY's boundary would otherwise suggest. Without
 * this check, a Shift Version whose new start time is meaningfully earlier than the previous
 * version's own 18-hour reset would incorrectly swallow a legitimate fresh, on-time arrival under
 * the new version into the previous logical workday — see the worked example below.
 * <li><b>Otherwise, yesterday's boundary.</b> A timestamp still belongs to YESTERDAY's logical
 * workday if it falls before yesterday's own {@link #maximumAttendanceBoundary}; otherwise it
 * belongs to today's (even though today's own shift hasn't nominally started yet — an early punch
 * is an ordinary early-arrival concern, not a shift-day one).
 * </ol>
 * Verified against every worked example in the design discussion, including:
 * <ul>
 * <li>09:00-18:00, 18h max: boundary = 03:00 the next day. A 01:00 punch (technically "tomorrow")
 * still belongs to TODAY's logical workday (overtime continuation); a 05:00 punch (after the
 * boundary, before tomorrow's own 09:00 start) already belongs to TOMORROW's.
 * <li>15:30-00:30 (overnight), 18h max: boundary = 09:30 the next day. Punches at 00:20, 00:30,
 * 01:00, 05:00, and 07:00 all still belong to the PREVIOUS logical workday; 09:30 and 10:00
 * already belong to the new one.
 * <li><b>Version-boundary case</b>: Shift changes from 15:30-00:30 to a NEW version effective
 * today, 06:00-15:00. A fresh check-in today at 06:00 (no open session) correctly resolves to
 * TODAY — not yesterday, even though yesterday's own (old-version) 18h boundary would otherwise
 * extend to 09:30 today — because today's own (new-version) start (06:00) has already been
 * reached. Reversed (06:00-15:00 -> 15:30-00:30, fresh check-in today at 15:30) is unaffected
 * either way, since the OLD version's short same-day boundary never reached that far regardless.
 * </ul>
 *
 * <h2>No-shift employees — there is no fallback, anywhere in this class</h2>
 * It is a product/domain invariant that every employee always has an assigned {@link Shift}: a
 * newly-created employee is server-side defaulted to the organization's default shift
 * ({@link Shift#DEFAULT_SHIFT_NAME}) unconditionally — {@code UserManagementService#createUser}/
 * {@code EmployeeService#createEmployee} now fail loudly rather than silently proceed if that
 * default can't be resolved — and {@code OrgService} refuses to rename/deactivate/delete the
 * default shift itself, so the "no assigned Shift" state is treated as an invariant violation, not
 * a normal or tolerated one. Consequently every method on this class — {@link #resolveShiftStart}/
 * {@link #shiftStartAt}/{@link #shiftEndAt} included, not just {@link #shiftDayOf}/
 * {@link #maximumAttendanceBoundary} — throws {@link IllegalStateException} for a null-shift
 * employee rather than silently substituting any fixed clock time (the previous
 * {@code AttendanceProperties.shiftStart}/{@code shiftDayCutover}-based fallbacks have both been
 * removed entirely, from this class and from {@code AttendanceProperties}). This is intentional: a
 * null-shift employee reaching any attendance computation is an anomaly that should fail clearly,
 * never be quietly worked around.
 */
@Component
@RequiredArgsConstructor
public class ShiftDayPolicy {

    private final ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    private final ShiftVersionResolver shiftVersionResolver;

    /**
     * The employee's actually-assigned Shift start on {@code day}, per the version effective on
     * that date. Requires an assigned shift — throws for a null-shift employee, exactly like
     * every other method here (see this class's own "No-shift employees" Javadoc section) — there
     * is no historical/corrective-flow fallback anymore; a legacy record belonging to a null-shift
     * employee is exactly the anomaly that invariant exists to prevent, not a case to route around.
     */
    public LocalTime resolveShiftStart(Employee employee, LocalDate day) {
        requireShift(employee);
        return shiftVersionResolver.resolve(shiftOf(employee), day).getStartTime();
    }

    /** {@link #resolveShiftStart} anchored to a specific calendar date. */
    public LocalDateTime shiftStartAt(Employee employee, LocalDate day) {
        return LocalDateTime.of(day, resolveShiftStart(employee, day));
    }

    /**
     * Minutes past {@link #resolveShiftStart} forgiven before a punch counts as LATE, per the
     * version effective on {@code day} — every Shift has its own grace, not a single global one
     * (migrated off {@code app.attendance.late-grace-minutes} — see V168's migration comment).
     * Read exclusively by {@link AttendanceInterpretationService}. Requires an assigned shift —
     * throws otherwise, exactly like every other method here.
     */
    public int resolveLateGraceMinutes(Employee employee, LocalDate day) {
        requireShift(employee);
        return shiftVersionResolver.resolve(shiftOf(employee), day).getLateGraceMinutes();
    }

    /**
     * Scheduled end of the shift covering {@code workDate} (per the version effective on that
     * date) — concept #1 (scheduled shift end), never replaced by the maximum-boundary concept
     * below. Overnight-aware: rolls into the next calendar day when that version's end time is
     * not after its start (e.g. 3:30 PM - 12:30 AM). Requires an assigned shift — throws
     * otherwise; the flat-24h no-shift fallback this method previously had was removed along with
     * every other no-shift fallback in this class (see the class Javadoc).
     */
    public LocalDateTime shiftEndAt(Employee employee, LocalDate workDate) {
        requireShift(employee);
        LocalTime start = resolveShiftStart(employee, workDate);
        ShiftVersion version = shiftVersionResolver.resolve(shiftOf(employee), workDate);
        LocalTime end = version.getEndTime();
        LocalDate endDate = !end.isAfter(start) ? workDate.plusDays(1) : workDate;
        return LocalDateTime.of(endDate, end);
    }

    /** {@code true} when the version effective on {@code day} has an end not after its start, i.e. it crosses midnight. */
    public boolean isOvernight(Shift shift, LocalDate day) {
        ShiftVersion version = shiftVersionResolver.resolve(shift, day);
        return !version.getEndTime().isAfter(version.getStartTime());
    }

    /**
     * Concept #2 (logical workday reset) ONLY — the instant at which activity anchored to
     * {@code day}'s shift start (per the version effective on {@code day}) stops belonging to
     * that logical workday. Computed as {@code shiftStartAt(day) +
     * organization.maximumShiftDayDurationHours}. Requires an assigned shift — throws for a
     * null-shift employee rather than falling back to any fixed clock time (see this class's own
     * "No-shift employees" Javadoc section for why).
     *
     * <p>This is purely a query: it returns an instant, nothing more. Crossing it does not, by
     * itself, close, flag, or create any {@code Attendance} record — see this class's own Javadoc.
     */
    public LocalDateTime maximumAttendanceBoundary(Employee employee, LocalDate day) {
        requireShift(employee);
        double hours = shiftWeeklyOffRulesService.getMaximumShiftDayDurationHours();
        long minutes = Math.round(hours * 60);
        return shiftStartAt(employee, day).plusMinutes(minutes);
    }

    /**
     * The logical workday {@code timestamp} belongs to — see this class's own Javadoc for the
     * full algorithm and worked examples (including the Shift-Version-boundary case). Only
     * decides date ATTRIBUTION; it is not consulted for, and does not perform, staleness/closure/
     * exception decisions (those remain entirely the caller's own responsibility — see
     * {@link AttendanceService#flagMissingCheckoutIfStale}). Requires an assigned shift — throws
     * immediately for a null-shift employee. No fixed-clock-time fallback of any kind exists in
     * this method, and it never queries {@code Attendance} — an existing open session is already
     * handled entirely by the caller (see {@code AttendanceService#checkIn}) BEFORE this method is
     * ever invoked for a fresh check-in's classification.
     */
    public LocalDate shiftDayOf(Employee employee, LocalDateTime timestamp) {
        requireShift(employee);
        LocalDate candidate = timestamp.toLocalDate();

        // Rule 1: today's own start, first — a legitimate arrival for TODAY's own (possibly just-
        // changed) Shift Version must never be reinterpreted as yesterday's leftover merely
        // because yesterday's 18h boundary happens to extend into today (see this class's Javadoc
        // "version-boundary case" worked example).
        LocalDateTime todaysOwnStart = shiftStartAt(employee, candidate);
        if (!timestamp.isBefore(todaysOwnStart)) {
            return candidate;
        }

        // Rule 2: otherwise, is this still yesterday's overnight tail?
        LocalDateTime previousDayBoundary = maximumAttendanceBoundary(employee, candidate.minusDays(1));
        return timestamp.isBefore(previousDayBoundary) ? candidate.minusDays(1) : candidate;
    }

    private void requireShift(Employee employee) {
        if (shiftOf(employee) == null) {
            throw new IllegalStateException(
                    "Cannot compute shift-relative timing for an employee with no assigned Shift "
                            + "(employeeUserId=" + (employee != null ? employee.getUserId() : null) + "). Every "
                            + "employee is expected to have one — see Shift.DEFAULT_SHIFT_NAME's server-side "
                            + "default on creation and ShiftSeedCorrector's startup backfill. There is no fixed-"
                            + "clock-time fallback anywhere in this class, by design.");
        }
    }

    private Shift shiftOf(Employee employee) {
        return employee != null ? employee.getShift() : null;
    }
}
