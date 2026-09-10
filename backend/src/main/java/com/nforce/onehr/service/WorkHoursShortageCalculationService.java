package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.util.WorkHoursCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Work Hours Shortage's WORKED side: basis (Effective/Gross), shift-boundary filtering, and
 * daily/weekly/monthly aggregation. The EXPECTED side always comes from
 * {@link ExpectedWorkHoursService} unchanged — this class never computes its own expected-hours
 * figure, only the worked-minutes figure compared against it (see
 * {@link com.nforce.onehr.service.ConfiguredAttendancePolicyEngine}'s class javadoc for the full
 * pipeline).
 *
 * <p>Deliberate simplification, disclosed: shift-boundary filtering clips the day's single
 * {@code checkInAt}/{@code checkOutAt} span (the "gross" span already tracked on
 * {@link Attendance} — see its class javadoc) to the shift window, then caps Effective-basis
 * worked minutes at that clipped span's length. For the overwhelmingly common single-session day
 * this is exact; for a day with multiple check-in/check-out sessions crossing the shift boundary
 * on either side, it's a documented approximation rather than re-deriving from individual punches
 * (which would require a bulk multi-employee punch fetch this nightly-batch code path doesn't
 * otherwise need — see {@code AttendanceService#collectPunches}, which is scoped to one
 * employee's live session at a time).
 */
@Service
@RequiredArgsConstructor
public class WorkHoursShortageCalculationService {

    private static final String BASIS_GROSS = "GROSS_HOURS";
    private static final String PERIOD_WEEK = "WEEK";
    private static final String PERIOD_MONTH = "MONTH";

    private final AttendanceRepository attendanceRepository;
    private final ExpectedWorkHoursService expectedWorkHoursService;
    private final WorkingDayService workingDayService;
    private final ShiftVersionResolver shiftVersionResolver;
    // Only for shiftBoundedGrossMinutes' historical Attendance.shiftId lookup below — never for
    // resolving an employee's current/live Shift.
    private final ShiftRepository shiftRepository;

    /**
     * The Work Hours Shortage percent fact for {@code date}, honoring the version's configured
     * basis/frequency/shift-exclusion/missing-log settings — null when there's nothing to compare
     * (no attendance at all that date, or a missing log this policy doesn't opt in to
     * penalizing). {@code DAY} frequency (the default, and the only one earlier versions ever
     * stored) evaluates {@code date} alone, reproducing the pre-Phase-3 calculation exactly.
     * {@code WEEK}/{@code MONTH} aggregates the policy's cycle containing {@code date}, clamped to
     * this version's own effective window (never retroactively reaching into a different
     * version's dates).
     */
    public Double computeShortagePercent(Employee employee, LocalDate date, PenalizationPolicyVersion version) {
        if (employee == null || version == null) {
            return null;
        }
        String period = version.getWhsDeductionPeriod();
        if (PERIOD_WEEK.equals(period) || PERIOD_MONTH.equals(period)) {
            return computeCyclePercent(employee, date, version, period);
        }
        return computeDayPercent(employee, date, version);
    }

    private Double computeDayPercent(Employee employee, LocalDate date, PenalizationPolicyVersion version) {
        Attendance record = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date).orElse(null);
        Long workedMinutes = workedMinutesFor(record, employee, version);
        if (workedMinutes == null) {
            return null;
        }
        Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(employee, date);
        return WorkHoursCalculator.minutesToPercent(workedMinutes, expectedMinutes);
    }

    private Double computeCyclePercent(Employee employee, LocalDate date, PenalizationPolicyVersion version, String period) {
        LocalDate[] cycle = ExceptionService.cyclePeriod(date, period);
        LocalDate cycleStart = cycle[0];
        LocalDate cycleEnd = cycle[1];
        // Section 7: never let this version's aggregate reach into dates a DIFFERENT version
        // governed — clamp to this version's own effective window.
        LocalDate versionStart = version.getEffectiveFrom().toLocalDate();
        if (versionStart.isAfter(cycleStart)) {
            cycleStart = versionStart;
        }
        if (version.getEffectiveTo() != null) {
            LocalDate versionEnd = version.getEffectiveTo().toLocalDate();
            if (versionEnd.isBefore(cycleEnd)) {
                cycleEnd = versionEnd;
            }
        }
        if (cycleEnd.isBefore(cycleStart)) {
            return null;
        }

        WorkingDaySchedule schedule = workingDayService.computeExpectedWorkingDays(employee, cycleStart, cycleEnd);
        if (schedule.getWorkingDates().isEmpty()) {
            return null;
        }
        List<Attendance> records = attendanceRepository
                .findByEmployeeUserIdInAndWorkDateBetween(List.of(employee.getUserId()), cycleStart, cycleEnd);
        Map<LocalDate, Attendance> byDate = records.stream()
                .collect(Collectors.toMap(Attendance::getWorkDate, r -> r, (a, b) -> a));
        Map<String, LeaveRequest> partialHourLeave = expectedWorkHoursService
                .loadPartialHourLeaveByEmployeeDate(List.of(employee.getUserId()), cycleStart, cycleEnd);

        long totalWorked = 0;
        long totalExpected = 0;
        boolean anyIncluded = false;
        for (LocalDate day : schedule.getWorkingDates()) {
            Attendance record = byDate.get(day);
            if (record == null) {
                continue; // a true absence is No Attendance's concern, not Work Hours Shortage's
            }
            Long workedMinutes = workedMinutesFor(record, employee, version);
            if (workedMinutes == null) {
                continue; // a missing log this policy doesn't penalize — excluded from both totals
            }
            Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(
                    employee, day, partialHourLeave.get(employee.getUserId() + "|" + day));
            if (expectedMinutes == null) {
                continue;
            }
            totalWorked += workedMinutes;
            totalExpected += expectedMinutes;
            anyIncluded = true;
        }
        if (!anyIncluded) {
            return null;
        }
        return WorkHoursCalculator.minutesToPercent(totalWorked, totalExpected);
    }

    /**
     * Worked minutes for one day, per the version's configured basis — null when there's nothing
     * to evaluate (no attendance row) or a missing log the version doesn't opt in to penalizing.
     * A missing log that IS opted in contributes exactly 0 (a full shortfall for that date),
     * never a guessed partial figure.
     */
    private Long workedMinutesFor(Attendance record, Employee employee, PenalizationPolicyVersion version) {
        if (record == null) {
            return null;
        }
        if (record.isMissingCheckOut()) {
            return version.isWhsPenalizeShortageCausedByMissingLogsEnabled() ? 0L : null;
        }
        if (record.getWorkedMinutes() == null) {
            return null;
        }
        boolean gross = BASIS_GROSS.equals(version.getWhsDeductionBasis());
        if (!version.isWhsExcludeHoursOutsideShiftEnabled()) {
            return gross ? grossMinutes(record) : (long) record.getWorkedMinutes();
        }
        Long shiftBoundedGross = shiftBoundedGrossMinutes(record, employee);
        if (shiftBoundedGross == null) {
            // No assigned shift to bound against — fall back to the unbounded figure rather than
            // silently zeroing out a real day's attendance.
            return gross ? grossMinutes(record) : (long) record.getWorkedMinutes();
        }
        return gross ? shiftBoundedGross : Math.min((long) record.getWorkedMinutes(), shiftBoundedGross);
    }

    /** checkInAt to checkOutAt — the day's full punch span, unbounded by shift timing. Null if either timestamp is missing. */
    private Long grossMinutes(Attendance record) {
        if (record.getCheckInAt() == null || record.getCheckOutAt() == null) {
            return null;
        }
        return Duration.between(record.getCheckInAt(), record.getCheckOutAt()).toMinutes();
    }

    /**
     * {@link #grossMinutes} clipped to the shift window this record was ACTUALLY snapshotted
     * under (its own {@code shiftId} — never the employee's current/live assignment, so a later
     * reassignment can't retroactively change a historical shortage figure) — overnight-shift-aware
     * (an end time earlier than the start rolls into the next calendar day), same convention as
     * {@code AttendanceService#shiftEndCutoff}. Null when the record has no shift snapshot (a
     * legacy pre-{@code shiftId} row — "cannot be evaluated," never guessed via the employee's
     * current Shift).
     */
    private Long shiftBoundedGrossMinutes(Attendance record, Employee employee) {
        Shift shift = resolveSnapshotShift(record).orElse(null);
        if (shift == null || record.getCheckInAt() == null || record.getCheckOutAt() == null) {
            return null;
        }
        // The version effective on THIS record's own workDate — never the employee's current/live
        // shift, so a later timing change can't retroactively change a historical shortage figure.
        com.nforce.onehr.entity.ShiftVersion version = shiftVersionResolver.resolve(shift, record.getWorkDate());
        LocalTime shiftStart = version.getStartTime();
        LocalTime shiftEnd = version.getEndTime();
        LocalDate endDate = !shiftEnd.isAfter(shiftStart) ? record.getWorkDate().plusDays(1) : record.getWorkDate();
        LocalDateTime windowStart = LocalDateTime.of(record.getWorkDate(), shiftStart);
        LocalDateTime windowEnd = LocalDateTime.of(endDate, shiftEnd);

        LocalDateTime clippedStart = record.getCheckInAt().isBefore(windowStart) ? windowStart : record.getCheckInAt();
        LocalDateTime clippedEnd = record.getCheckOutAt().isAfter(windowEnd) ? windowEnd : record.getCheckOutAt();
        if (!clippedEnd.isAfter(clippedStart)) {
            return 0L;
        }
        return Duration.between(clippedStart, clippedEnd).toMinutes();
    }

    /**
     * Resolves the Shift referenced by {@code record}'s own snapshotted {@code shiftId} — never
     * the employee's current/live assignment. Empty for a legacy row with no snapshot (see
     * {@code Attendance.shiftId}'s own Javadoc); callers must treat that as "cannot be bounded,"
     * never fall back to the employee's current Shift. Mirrors {@code AttendanceInterpretationService
     * .resolveShiftContextOrNull}'s identical reasoning/error message for the same class of
     * "references a shift that no longer exists" data-corruption signal.
     */
    private Optional<Shift> resolveSnapshotShift(Attendance record) {
        if (record.getShiftId() == null) {
            return Optional.empty();
        }
        return Optional.of(shiftRepository.findById(record.getShiftId())
                .orElseThrow(() -> new IllegalStateException(
                        "Attendance " + record.getId() + " references shift " + record.getShiftId()
                                + " which no longer exists — Shift deletion should be blocked once any "
                                + "Attendance references it (see OrgService#deleteShift); this indicates "
                                + "data corruption, not a case to fall back from.")));
    }
}
