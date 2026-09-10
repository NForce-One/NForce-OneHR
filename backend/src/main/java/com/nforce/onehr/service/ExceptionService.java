package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.util.WorkHoursCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExceptionService {

    private static final Set<String> HR_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> PENDING_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED");
    // The one allowed-late privilege: ShiftVersion.lateGraceMinutes, already folded into this by
    // AttendanceInterpretationService/AttendanceService/RegularizationService when they compute
    // Attendance.status. Never re-derive "genuinely late" from the raw, no-forgiveness
    // lateByMinutes figure here — see detectExceptions' own comment on why.
    private static final String STATUS_LATE = "LATE";

    // Still detected and evaluated against the Penalization Policy exactly as before (see
    // detectExceptions/runScheduledPenaltyEvaluation) — these three just no longer surface as
    // rows on the Exception Dashboard itself, per explicit request. Removing an entry here only
    // changes what getExceptionsForCaller returns, never what gets detected or penalized.
    private static final Set<String> HIDDEN_FROM_EXCEPTION_DASHBOARD = Set.of(
            ExceptionType.NO_ATTENDANCE, ExceptionType.WORK_HOURS_SHORTAGE, ExceptionType.LEAVE_ATTENDANCE_CONFLICT);

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final AttendanceProperties attendanceProperties;
    private final EmailService emailService;
    private final AttendancePenaltyEvaluationService attendancePenaltyEvaluationService;
    private final WorkingDayService workingDayService;
    private final HolidayRepository holidayRepository;
    private final PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    private final ExpectedWorkHoursService expectedWorkHoursService;
    private final WorkHoursShortageCalculationService workHoursShortageCalculationService;
    private final AttendancePolicyEngine attendancePolicyEngine;
    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final AttendancePenaltyService attendancePenaltyService;
    // Resolves a Shift's timing for a SPECIFIC date (Shift Versioning) — see its own Javadoc.
    private final ShiftVersionResolver shiftVersionResolver;
    // Only for resolveSnapshotShift's historical Attendance.shiftId lookup below — never for
    // resolving an employee's current/live Shift.
    private final ShiftRepository shiftRepository;

    /**
     * Gap-033/034: every discrepancy type a corrected Attendance record could invalidate — a
     * regularization approval rewrites checkInAt/checkOutAt/lateByMinutes/workedMinutes/status for
     * the whole day, so any of these four could no longer hold. Deliberately excludes
     * LEAVE_ATTENDANCE_CONFLICT (a punch *during* approved leave is a fact regularization can never
     * retract — see detectExceptions' own javadoc on why that type is never gated the same way as
     * the other four).
     */
    static final Set<String> REGULARIZATION_REEVALUATION_TYPES = Set.of(
            ExceptionType.LATE_ARRIVAL, ExceptionType.MISSING_PUNCH,
            ExceptionType.WORK_HOURS_SHORTAGE, ExceptionType.NO_ATTENDANCE);

    /**
     * Gap-034: leave approval only ever changes what an employee was *expected* to work (via
     * {@link ExpectedWorkHoursService#adjustedExpectedMinutes}) or whether the day counts as a
     * working day at all — it never touches what was actually punched, so LATE_ARRIVAL/MISSING_PUNCH
     * are deliberately excluded here even though they're in {@link #REGULARIZATION_REEVALUATION_TYPES}.
     */
    static final Set<String> LEAVE_REEVALUATION_TYPES = Set.of(
            ExceptionType.WORK_HOURS_SHORTAGE, ExceptionType.NO_ATTENDANCE);

    /**
     * HR Admin + Super Admin see company-wide exceptions; Manager sees only current
     * direct reports (via EmployeeManagerHistory). Scope is resolved from the caller's
     * roles only — never client-supplied. HR/Super Admin takes precedence over Manager
     * for any user holding both roles.
     *
     * This dashboard is an individual-contributor view only: an exception subject must
     * hold the EMPLOYEE role and none of MANAGER/HR_ADMIN/SUPER_ADMIN (see
     * UserRepository.findEmployeeRoleUserIds()). Admin/HR/Manager accounts never appear
     * as exception subjects, company-wide or as a direct report, even if they were also
     * granted EMPLOYEE (e.g. to punch in/out themselves) or their own attendance would
     * otherwise qualify.
     */
    @Transactional
    public List<ExceptionResponse> getExceptionsForCaller(String actorEmail, LocalDate from, LocalDate to) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        Set<UUID> employeeRoleIds = userRepository.findEmployeeRoleUserIds();

        List<UUID> scopeIds;
        if (roleCodes.stream().anyMatch(HR_ROLES::contains)) {
            scopeIds = new java.util.ArrayList<>(employeeRoleIds);
        } else if (roleCodes.contains("MANAGER")) {
            scopeIds = historyRepository.findByManagerUserIdAndEffectiveToIsNull(actor.getId()).stream()
                    .map(EmployeeManagerHistory::getEmployeeUserId)
                    .filter(employeeRoleIds::contains)
                    .collect(Collectors.toList());
        } else {
            throw new AccessDeniedException("Not authorized to view exceptions");
        }

        detectExceptions(scopeIds, from, to);

        List<AttendanceException> exceptions = attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(scopeIds, from, to);

        List<ExceptionResponse> responses = exceptions.stream()
                .filter(e -> !HIDDEN_FROM_EXCEPTION_DASHBOARD.contains(e.getExceptionType()))
                .map(this::toResponse)
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        responses.addAll(pendingLeaveApprovals(scopeIds, from, to));
        responses.sort(Comparator.comparing(ExceptionResponse::getExceptionDate)
                .thenComparing(ExceptionResponse::getDetectedAt)
                .reversed());
        return responses;
    }

    /**
     * A leave request still awaiting approval, surfaced as a dashboard row alongside real
     * attendance exceptions — see ExceptionType.PENDING_LEAVE_APPROVAL's javadoc. Dated by when
     * it was requested (createdAt), not its leave start date, so the dashboard's From/To filter
     * means the same thing here as it does for every other row: "when did this need attention."
     */
    private List<ExceptionResponse> pendingLeaveApprovals(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        return leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(scopeIds, "PENDING").stream()
                .filter(r -> {
                    LocalDate requestedOn = r.getCreatedAt().toLocalDate();
                    return !requestedOn.isBefore(from) && !requestedOn.isAfter(to);
                })
                .map(r -> {
                    Optional<Employee> employee = employeeRepository.findById(r.getEmployeeUserId());
                    return ExceptionResponse.builder()
                            .id(r.getId())
                            .employeeUserId(r.getEmployeeUserId())
                            .employeeCode(employee.map(Employee::getEmployeeCode).orElse(null))
                            .employeeFullName(employee.map(Employee::getFullName).orElse(null))
                            .exceptionDate(r.getCreatedAt().toLocalDate())
                            .exceptionType(ExceptionType.PENDING_LEAVE_APPROVAL)
                            .status("OPEN")
                            .detectedAt(r.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Section 45's background-processing entry point: the same detection/evaluation pipeline
     * {@link #getExceptionsForCaller} already runs on every dashboard load, but company-wide and
     * on a schedule (see {@code PenaltyEvaluationScheduler}) — so a Penalization Policy match is
     * never missed just because nobody opened the Exceptions/Penalties dashboard that day. Looks
     * back {@code lookbackDays} calendar days from yesterday (never "today", an in-progress day),
     * which must comfortably cover the longest configured buffer period + exemption cycle for any
     * policy still evaluating pending occurrences.
     *
     * <p>Section 8: scope is the same {@link UserRepository#findEmployeeRoleUserIds()} population
     * {@link #getExceptionsForCaller} uses — deliberately NOT narrowed to {@code User.active},
     * which cannot reliably distinguish a genuine termination from any other reason an admin might
     * deactivate someone (see {@link #isPastEmploymentTermination}'s javadoc). "No new penalty
     * after a genuine termination" is enforced precisely, per-date, at {@link #evaluatePolicy}
     * instead — the correct place, since it's the one signal (Employee#lastWorkingDay) actually
     * meant to record employment end, and it applies uniformly to this scheduled path and the
     * dashboard-triggered one.
     */
    @Transactional
    public void runScheduledPenaltyEvaluation(int lookbackDays) {
        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(Math.max(lookbackDays, 1) - 1L);
        if (to.isBefore(from)) {
            return;
        }
        Set<UUID> scopeIds = userRepository.findEmployeeRoleUserIds();
        if (scopeIds.isEmpty()) {
            return;
        }
        detectExceptions(new java.util.ArrayList<>(scopeIds), from, to);
    }

    /**
     * Reads real attendance_records for the scope/date range and upserts all exception
     * types into attendance_exceptions:
     *  - LATE_ARRIVAL: the check-in was already flagged late by AttendanceService against
     *    the same shift-start/grace configuration — reused here rather than re-derived.
     *  - MISSING_PUNCH: a past day (never today, which may still legitimately be open) has
     *    a check-in but no check-out.
     *  - LEAVE_ATTENDANCE_CONFLICT: an approved leave request covers the same day a
     *    check-in was also recorded.
     *
     * <p>The moment a discrepancy is first detected, {@code upsertException} also evaluates it
     * against the configured Penalization Policy (see {@link #evaluatePolicy}) — this dashboard
     * load (HR/Super Admin viewing company-wide exceptions, or a Manager viewing their team's) is
     * the existing, already-invoked production trigger; no scheduler is introduced.
     *
     * <p>LATE_ARRIVAL and MISSING_PUNCH are both gated against {@link WorkingDayService}'s
     * "was this employee actually expected to work this date" set — the same non-working-day
     * exclusion (holiday, weekly off, approved full-day leave) {@link #detectNoAttendanceAndShortage}
     * already applies for its own two exception types. Without it, a punch on an optional/overtime
     * work day that happens to read as "late" against normal shift-start, or a forgotten checkout
     * on a day nobody was expected to work, would incorrectly become a penalty candidate — nothing
     * upstream in {@code AttendanceService}'s {@code lateByMinutes}/{@code isMissingCheckOut}
     * computation is itself aware of holidays/week-offs. LEAVE_ATTENDANCE_CONFLICT is a different
     * concern (a punch *during* approved leave) and is deliberately not gated the same way.
     */
    private void detectExceptions(Collection<UUID> scopeIds, LocalDate from, LocalDate to) {
        List<UUID> scopeIdList = new java.util.ArrayList<>(scopeIds);
        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(scopeIdList, from, to);

        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();

        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        scopeIdList, "APPROVED", to, from);

        Set<String> leaveCoveredDays = approvedLeave.stream()
                .flatMap(leave -> leave.getStartDate().datesUntil(leave.getEndDate().plusDays(1))
                        .map(date -> leave.getEmployeeUserId() + "|" + date))
                .collect(Collectors.toSet());

        List<Employee> employees = employeeRepository.findAllByIdWithScheduleDetails(scopeIdList);

        // Unclamped [from, to] — deliberately not reusing detectNoAttendanceAndShortage's own
        // yesterday-clamped range below, since a late arrival can legitimately be for *today*.
        Map<UUID, WorkingDaySchedule> workingDaySchedules = workingDayService.computeExpectedWorkingDaysBulk(employees, from, to);

        for (Attendance record : records) {
            WorkingDaySchedule schedule = workingDaySchedules.get(record.getEmployeeUserId());
            boolean isWorkingDay = schedule != null && schedule.getWorkingDates().contains(record.getWorkDate());

            // Gated on Attendance.status == LATE, NOT `lateByMinutes > 0`. There is exactly one
            // allowed-late privilege — ShiftVersion.lateGraceMinutes — and it is already folded
            // into `status` by AttendanceInterpretationService's isLate formula (shiftStart +
            // grace). `lateByMinutes` is deliberately left as the raw, no-forgiveness minutes
            // (an employee-facing display/audit figure only — see its own Javadoc); gating on it
            // directly would create a LATE_ARRIVAL incident, and count it toward the "every 3rd
            // late arrival" threshold, for an arrival still within the employee's allowed-late
            // privilege, contradicting that privilege's whole purpose.
            if (isWorkingDay && STATUS_LATE.equals(record.getStatus())) {
                // Resolved from THIS record's own snapshotted shiftId (never the employee's
                // current/live Shift) — an employee reassigned since this historical date must
                // never change what "expected start" this already-decided LATE_ARRIVAL displays.
                // Null (legacy row, no snapshot) means "cannot be evaluated" — the exception is
                // still raised (the LATE decision itself is independent and already final), just
                // without a resolvable expected-start figure, same as MISSING_PUNCH/
                // LEAVE_ATTENDANCE_CONFLICT below already pass null for fields they can't supply.
                LocalTime expectedShiftStart = resolveSnapshotShift(record)
                        .map(shift -> shiftVersionResolver.resolve(shift, record.getWorkDate()).getStartTime())
                        .orElse(null);
                upsertException(record, ExceptionType.LATE_ARRIVAL,
                        expectedShiftStart, record.getCheckInAt().toLocalTime(),
                        record.getLateByMinutes());
            }
            if (isWorkingDay && record.isMissingCheckOut() && record.getWorkDate().isBefore(today)) {
                upsertException(record, ExceptionType.MISSING_PUNCH,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
            if (record.getCheckInAt() != null && leaveCoveredDays.contains(record.getEmployeeUserId() + "|" + record.getWorkDate())) {
                upsertException(record, ExceptionType.LEAVE_ATTENDANCE_CONFLICT,
                        null, record.getCheckInAt().toLocalTime(), null);
            }
        }

        detectNoAttendanceAndShortage(scopeIdList, records, from, to, today);
    }

    /**
     * NO_ATTENDANCE (Section 10) and WORK_HOURS_SHORTAGE (Section 18) both require comparing an
     * expected working day against what actually happened — the loop above can never produce
     * either one since it only ever iterates {@code Attendance} rows that already exist (see
     * {@code ExceptionType}'s "reserved" javadoc). Reuses {@link WorkingDayService} — the existing
     * "how many days was this employee expected to work" source of truth already used by Team
     * Effort/Punctuality — instead of inventing a second expected-working-day calculation.
     *
     * <p>Never evaluated for "today" (an in-progress day isn't a discrepancy yet, matching the
     * existing MISSING_PUNCH convention).
     */
    private void detectNoAttendanceAndShortage(List<UUID> scopeIdList, List<Attendance> records,
                                                LocalDate from, LocalDate to, LocalDate today) {
        LocalDate rangeEnd = to.isBefore(today) ? to : today.minusDays(1);
        if (rangeEnd.isBefore(from)) {
            return;
        }
        List<Employee> employees = employeeRepository.findAllByIdWithScheduleDetails(scopeIdList);
        Map<UUID, WorkingDaySchedule> schedules = workingDayService.computeExpectedWorkingDaysBulk(employees, from, rangeEnd);
        Map<String, LeaveRequest> partialHourLeaveByEmployeeDate =
                expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(scopeIdList, from, rangeEnd);

        Map<String, Attendance> byEmployeeDate = records.stream()
                .collect(Collectors.toMap(r -> r.getEmployeeUserId() + "|" + r.getWorkDate(), r -> r, (a, b) -> a));

        for (Employee employee : employees) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null) {
                continue;
            }
            // Resolved once per employee (matching detectAdjoiningPenalties' own convention) —
            // decides which detection MODE runs for this whole pass; evaluatePolicy still resolves
            // the precise per-date version for the actual penalty math regardless.
            PenalizationPolicyVersion version = penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, rangeEnd);
            boolean cyclicFrequency = version != null
                    && ("WEEK".equals(version.getWhsDeductionPeriod()) || "MONTH".equals(version.getWhsDeductionPeriod()));
            boolean missingLogShortageEnabled = version != null && version.isWhsPenalizeShortageCausedByMissingLogsEnabled();

            for (LocalDate date : schedule.getWorkingDates()) {
                Attendance existing = byEmployeeDate.get(employee.getUserId() + "|" + date);
                if (existing == null) {
                    // No punch at all — build a transient (never persisted) Attendance carrying
                    // just enough for upsertException/evaluatePolicy to work from; workedMinutes
                    // is 0, matching "1h30m against a threshold" style facts elsewhere.
                    Attendance noAttendance = Attendance.builder()
                            .employeeUserId(employee.getUserId()).workDate(date).workedMinutes(0).build();
                    upsertException(noAttendance, ExceptionType.NO_ATTENDANCE, null, null, null);
                } else if (cyclicFrequency) {
                    // WEEK/MONTH frequency is evaluated once per cycle, not per day — see
                    // detectCyclicWorkHoursShortage below.
                } else if (existing.getCheckOutAt() != null && existing.getWorkedMinutes() != null) {
                    // Expected minutes are reduced (not removed — see WorkingDayService) by any
                    // approved hourly/quarter-day leave on this date, so a shortage is only flagged
                    // against what the employee was actually still expected to work.
                    Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(
                            employee, date, partialHourLeaveByEmployeeDate.get(employee.getUserId() + "|" + date));
                    if (expectedMinutes != null && existing.getWorkedMinutes() < expectedMinutes) {
                        // Resolved from THIS record's own snapshotted shiftId (never the
                        // employee's current/live Shift) — an employee reassigned since this
                        // historical date must never change what "expected end" an
                        // already-decided shortage displays. Null (legacy row) means "cannot be
                        // evaluated" for display only; the shortage itself was already decided
                        // above from expectedMinutes, which is independently date-correct (see
                        // ExpectedWorkHoursService#shiftMinutes).
                        LocalTime expectedShiftEnd = resolveSnapshotShift(existing)
                                .map(shift -> shiftVersionResolver.resolve(shift, date).getEndTime())
                                .orElse(null);
                        upsertException(existing, ExceptionType.WORK_HOURS_SHORTAGE,
                                expectedShiftEnd, existing.getCheckOutAt().toLocalTime(), null);
                    }
                } else if (missingLogShortageEnabled && existing.isMissingCheckOut()) {
                    // Section 10 (Phase 3): a missing check-out is a candidate shortage day only
                    // when explicitly opted in — evaluatePolicy treats it as 0 worked minutes.
                    Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(
                            employee, date, partialHourLeaveByEmployeeDate.get(employee.getUserId() + "|" + date));
                    if (expectedMinutes != null && expectedMinutes > 0) {
                        upsertException(existing, ExceptionType.WORK_HOURS_SHORTAGE, null, null, null);
                    }
                }
            }
        }

        detectCyclicWorkHoursShortage(employees, from, rangeEnd);
        detectAdjoiningPenalties(employees, schedules, byEmployeeDate, from, rangeEnd);
    }

    /**
     * Section 5 (Phase 3): WEEK/MONTH Work Hours Shortage frequency — evaluated once on the
     * cycle's own last calendar day (not necessarily a working day itself; the aggregate inside
     * {@link WorkHoursShortageCalculationService} only counts the cycle's actual working days),
     * so exactly one {@code AttendanceException} row (and one penalty evaluation) results per
     * cycle rather than one per day.
     */
    private void detectCyclicWorkHoursShortage(List<Employee> employees, LocalDate from, LocalDate to) {
        for (Employee employee : employees) {
            PenalizationPolicyVersion version = penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, to);
            if (version == null || !version.isWorkHoursShortageEnabled()) {
                continue;
            }
            String period = version.getWhsDeductionPeriod();
            if (!"WEEK".equals(period) && !"MONTH".equals(period)) {
                continue;
            }
            for (LocalDate date : from.datesUntil(to.plusDays(1)).toList()) {
                LocalDate[] cycle = cyclePeriod(date, period);
                if (!date.equals(cycle[1])) {
                    continue; // only the cycle's last calendar day triggers evaluation
                }
                Double percent = workHoursShortageCalculationService.computeShortagePercent(employee, date, version);
                // Cheap pre-filter mirroring the DAY-mode gate ("worked < expected") — avoids an
                // exception row (and a full policy evaluation) for a cycle with no shortfall at all.
                if (percent == null || percent >= 100.0) {
                    continue;
                }
                Attendance synthetic = Attendance.builder()
                        .employeeUserId(employee.getUserId()).workDate(date).build();
                upsertException(synthetic, ExceptionType.WORK_HOURS_SHORTAGE, null, null, null);
            }
        }
    }

    /**
     * Section 12/13: No Attendance's adjoining-holiday/adjoining-week-off "sandwich" rules —
     * explicitly called out as NOT implemented in Phase 1 (no multi-day look-around detection
     * existed anywhere in this codebase). When enabled and the configured condition is met, the
     * holiday/week-off date itself becomes an ADDITIONAL {@code NO_ATTENDANCE} occurrence — the
     * same evaluation path {@link #detectNoAttendanceAndShortage} already uses, not a second one.
     *
     * <p>Scoped to the requested {@code [from, to]} window only — a holiday/week-off whose
     * adjoining no-attendance day(s) fall outside that window is not evaluated this call (the
     * scheduled background job's wide rolling lookback window means this is not a practical gap
     * for anything but a boundary date).
     *
     * <p>Simplification, disclosed: a multi-day holiday block (consecutive {@code Holiday} rows)
     * is evaluated per individual date, not as one merged block.
     */
    private void detectAdjoiningPenalties(List<Employee> employees, Map<UUID, WorkingDaySchedule> schedules,
                                           Map<String, Attendance> byEmployeeDate, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return;
        }
        Map<UUID, Set<LocalDate>> holidaysByLocation = loadHolidayDatesByLocation(employees, from, to);
        Map<String, Boolean> halfDayByEmployeeDate = loadHalfDayLeaveByEmployeeDate(employees, from, to);

        for (Employee employee : employees) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null) {
                continue;
            }
            PenalizationPolicyVersion version = penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, to);
            if (version == null || (!version.isNaAdjoiningHolidayEnabled() && !version.isNaAdjoiningWeekoffEnabled())) {
                continue;
            }
            Set<LocalDate> holidayDates = employee.getLocation() != null
                    ? holidaysByLocation.getOrDefault(employee.getLocation().getId(), Set.of()) : Set.of();
            Set<DayOfWeek> offDays = weeklyOffDaysOf(employee);

            for (LocalDate date : from.datesUntil(to.plusDays(1)).toList()) {
                if (version.isNaAdjoiningHolidayEnabled() && holidayDates.contains(date)) {
                    evaluateAdjoiningDay(employee, version, date, version.getNaAdjoiningHolidayCondition(),
                            version.getNaAdjoiningHolidayCalendarDayThreshold(), version.isNaAdjoiningHolidayIgnoreHalfDayLeave(),
                            byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate);
                }
                if (version.isNaAdjoiningWeekoffEnabled() && offDays.contains(date.getDayOfWeek()) && !holidayDates.contains(date)) {
                    evaluateAdjoiningDay(employee, version, date, version.getNaAdjoiningWeekoffCondition(),
                            version.getNaAdjoiningWeekoffCalendarDayThreshold(), version.isNaAdjoiningWeekoffIgnoreHalfDayLeave(),
                            byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate);
                }
            }
        }
    }

    private void evaluateAdjoiningDay(Employee employee, PenalizationPolicyVersion version, LocalDate specialDate,
                                       String condition, Integer thresholdConfig, boolean ignoreHalfDayLeave,
                                       Map<String, Attendance> byEmployeeDate, Set<LocalDate> holidayDates,
                                       Set<DayOfWeek> offDays, Map<String, Boolean> halfDayByEmployeeDate) {
        int threshold = thresholdConfig != null && thresholdConfig > 0 ? thresholdConfig : 1;
        boolean beforeSatisfied = isConsecutiveNoAttendanceRun(employee, specialDate.minusDays(1), -1, threshold,
                byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate, ignoreHalfDayLeave);
        boolean afterSatisfied = isConsecutiveNoAttendanceRun(employee, specialDate.plusDays(1), 1, threshold,
                byEmployeeDate, holidayDates, offDays, halfDayByEmployeeDate, ignoreHalfDayLeave);

        boolean triggered = switch (condition == null ? "ANY" : condition) {
            case "SANDWICHED" -> beforeSatisfied && afterSatisfied;
            case "BEFORE" -> beforeSatisfied;
            case "AFTER" -> afterSatisfied;
            default -> beforeSatisfied || afterSatisfied;
        };
        if (!triggered) {
            return;
        }
        // The holiday/week-off date itself becomes an additional no-attendance occurrence —
        // "sandwiching" a holiday/week-off between absences now also costs the holiday/week-off.
        Attendance synthetic = Attendance.builder()
                .employeeUserId(employee.getUserId()).workDate(specialDate).workedMinutes(0).build();
        upsertException(synthetic, ExceptionType.NO_ATTENDANCE, null, null, null);
    }

    /** {@code count} consecutive calendar days starting at {@code start}, stepping by {@code direction}, that all qualify as "no attendance" for adjoining-rule purposes. */
    private boolean isConsecutiveNoAttendanceRun(Employee employee, LocalDate start, int direction, int count,
                                                  Map<String, Attendance> byEmployeeDate, Set<LocalDate> holidayDates,
                                                  Set<DayOfWeek> offDays, Map<String, Boolean> halfDayByEmployeeDate,
                                                  boolean ignoreHalfDayLeave) {
        LocalDate date = start;
        for (int i = 0; i < count; i++) {
            if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
                return false;
            }
            if (holidayDates.contains(date) || offDays.contains(date.getDayOfWeek())) {
                return false; // another holiday/week-off breaks the run, not a no-attendance day itself
            }
            String key = employee.getUserId() + "|" + date;
            Boolean halfDay = halfDayByEmployeeDate.get(key);
            if (halfDay != null) {
                if (Boolean.TRUE.equals(halfDay)) {
                    if (ignoreHalfDayLeave) {
                        return false; // a half-day leave breaks the run when configured to be ignored
                    }
                    // else: treated as a no-attendance day for this purpose — continue the run.
                } else {
                    return false; // a full-day leave is a legitimate absence, not a no-attendance day
                }
            } else if (byEmployeeDate.containsKey(key)) {
                return false; // attendance was recorded — not a no-attendance day
            }
            date = date.plusDays(direction);
        }
        return true;
    }

    private Map<UUID, Set<LocalDate>> loadHolidayDatesByLocation(List<Employee> employees, LocalDate from, LocalDate to) {
        Set<UUID> locationIds = employees.stream()
                .map(e -> e.getLocation() != null ? e.getLocation().getId() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        return holidayRepository.findByLocation_IdInAndActiveTrue(locationIds).stream()
                .filter(h -> !h.getHolidayDate().isBefore(from.minusDays(7)) && !h.getHolidayDate().isAfter(to.plusDays(7)))
                .collect(Collectors.groupingBy(h -> h.getLocation().getId(),
                        Collectors.mapping(com.nforce.onehr.entity.Holiday::getHolidayDate, Collectors.toSet())));
    }

    /** {@code true} = half-day leave, {@code false} = full-day leave, absent = no leave that date. */
    private Map<String, Boolean> loadHalfDayLeaveByEmployeeDate(List<Employee> employees, LocalDate from, LocalDate to) {
        List<UUID> employeeIds = employees.stream().map(Employee::getUserId).toList();
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        List<LeaveRequest> approvedLeave = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        employeeIds, "APPROVED", to.plusDays(7), from.minusDays(7));
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (LeaveRequest leave : approvedLeave) {
            LocalDate start = leave.getStartDate().isBefore(from.minusDays(7)) ? from.minusDays(7) : leave.getStartDate();
            LocalDate end = leave.getEndDate().isAfter(to.plusDays(7)) ? to.plusDays(7) : leave.getEndDate();
            if (start.isAfter(end)) {
                continue;
            }
            start.datesUntil(end.plusDays(1)).forEach(date ->
                    result.put(leave.getEmployeeUserId() + "|" + date, leave.isHalfDay()));
        }
        return result;
    }

    /** Comma-separated DayOfWeek names on the assigned policy, else the Saturday/Sunday fallback — same convention as WorkingDayService. */
    private Set<DayOfWeek> weeklyOffDaysOf(Employee employee) {
        com.nforce.onehr.entity.WeeklyOffPolicy policy = employee.getWeeklyOffPolicy();
        if (policy == null || policy.getOffDays() == null || policy.getOffDays().isBlank()) {
            return Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
        return java.util.Arrays.stream(policy.getOffDays().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf)
                .collect(Collectors.toSet());
    }

    /**
     * Resolves the Shift referenced by {@code record}'s own snapshotted {@code shiftId} — never
     * the employee's current/live assignment — for a historical display or calculation tied to
     * this specific record. Empty for a legacy row with no snapshot (see {@code Attendance
     * .shiftId}'s own Javadoc); callers must treat that as "cannot be evaluated," never fall back
     * to the employee's current Shift. Mirrors {@code AttendanceInterpretationService
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

    private void upsertException(Attendance record, String exceptionType,
                                  LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();
        Optional<AttendanceException> existing = attendanceExceptionRepository
                .findByEmployeeUserIdAndExceptionDateAndExceptionType(employeeUserId, exceptionDate, exceptionType);
        boolean isNew = existing.isEmpty();

        AttendanceException exception = existing.orElseGet(() -> AttendanceException.builder()
                .employeeUserId(employeeUserId)
                .exceptionDate(exceptionDate)
                .exceptionType(exceptionType)
                .build());
        exception.setExpectedTime(expectedTime);
        exception.setActualTime(actualTime);
        exception.setMinutesLate(minutesLate);
        attendanceExceptionRepository.save(exception);

        // Email once, and evaluate the configured Penalization Policy once, the moment an
        // exception is first detected — never on later re-detection of the same row (every
        // dashboard load re-runs detectExceptions). AttendancePenaltyEvaluationService has its
        // own defensive duplicate guard regardless (see its class javadoc).
        if (isNew) {
            evaluatePolicy(record, exceptionType);
            notifyEmployee(employeeUserId, exceptionType, exceptionDate, expectedTime, actualTime, minutesLate);
        }
    }

    private void notifyEmployee(UUID employeeUserId, String exceptionType, LocalDate exceptionDate,
                                 LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        employeeRepository.findById(employeeUserId).ifPresent(employee -> {
            String email = employee.getUser().getEmail();
            String name = employee.getFullName();
            String managerEmail = currentManagerEmail(employeeUserId);
            if (ExceptionType.LATE_ARRIVAL.equals(exceptionType)) {
                emailService.sendLateArrivalEmail(email, managerEmail, name, exceptionDate, expectedTime, actualTime, minutesLate);
            } else if (ExceptionType.MISSING_PUNCH.equals(exceptionType)) {
                emailService.sendMissingPunchEmail(email, managerEmail, name, exceptionDate, actualTime);
            } else if (ExceptionType.LEAVE_ATTENDANCE_CONFLICT.equals(exceptionType)) {
                emailService.sendLeaveAttendanceConflictEmail(email, managerEmail, name, exceptionDate, actualTime);
            }
        });
    }

    /**
     * The one production entry point into the Penalization Policy seam: builds a
     * {@link PolicyEvaluationContext} entirely from facts {@code AttendanceService} already
     * computed (never re-derived here) and hands it to
     * {@link AttendancePenaltyEvaluationService#evaluate}, which calls the configured
     * {@link AttendancePolicyEngine} and persists an {@link AttendancePenalty} only on
     * {@code APPLY_PENALTY}. Every discrepancy type reaches here uniformly — the engine itself,
     * not this method, decides which (if any) configured section applies.
     */
    private void evaluatePolicy(Attendance record, String exceptionType) {
        if (isPastEmploymentTermination(record.getEmployeeUserId(), record.getWorkDate())) {
            return;
        }
        attendancePenaltyEvaluationService.evaluate(buildContext(record, exceptionType));
    }

    /**
     * Section 8: {@link Employee#getLastWorkingDay()} — already the domain's one existing,
     * HR-authored "employment ended on X" fact (previously consumed only by
     * {@link #isUnderNoticePeriod} for notice-period exemption) — also means no NEW penalty
     * should ever be evaluated for a date after it. Deliberately NOT based on {@code User.active}:
     * that boolean is shared with unrelated deactivation reasons (see
     * {@code UserManagementService#assertNotSelfOrLastActiveSuperAdmin}'s shared guard for both
     * {@code setActiveStatus(false)} and {@code softDeleteUser}) and cannot reliably distinguish
     * "this person left the company" from "temporarily disabled for another reason" — unlike
     * {@code lastWorkingDay}, which nothing in this codebase sets except a deliberate HR action.
     *
     * <p>Only gates NEW penalty creation ({@link #evaluatePolicy}) — never
     * {@link #reevaluateAndReverseIfInvalid}'s reversal path, which must still be able to reverse
     * an already-applied penalty regardless of the employee's current employment status, and never
     * historical rows, allocations, or the exception/notification recording above this call, none
     * of which this method touches.
     */
    private boolean isPastEmploymentTermination(UUID employeeUserId, LocalDate date) {
        return employeeRepository.findById(employeeUserId)
                .map(Employee::getLastWorkingDay)
                .filter(date::isAfter)
                .isPresent();
    }

    /**
     * Extracted from {@link #evaluatePolicy} so {@link #reevaluateAndReverseIfInvalid} (Gap-033/034)
     * can build the exact same {@link PolicyEvaluationContext} against corrected attendance/leave
     * data and hand it straight to {@link AttendancePolicyEngine#evaluate} for a read-only decision
     * check, without persisting a new penalty the way {@link AttendancePenaltyEvaluationService}
     * would.
     */
    private PolicyEvaluationContext buildContext(Attendance record, String exceptionType) {
        UUID employeeUserId = record.getEmployeeUserId();
        LocalDate exceptionDate = record.getWorkDate();
        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();

        Employee employee = employeeRepository.findById(employeeUserId).orElse(null);
        UUID assignedPolicyId = penalizationPolicyResolutionService.resolveAssignedOrDefaultPolicyId(employee, exceptionDate);
        PenalizationPolicyVersion version = penalizationPolicyResolutionService.resolveEffectiveVersion(assignedPolicyId, exceptionDate);

        List<RegularizationRequest> regularizations = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(List.of(employeeUserId), exceptionDate, exceptionDate);
        boolean hasPending = regularizations.stream().anyMatch(r -> PENDING_REGULARIZATION_STATUSES.contains(r.getStatus()));
        boolean hasApproved = regularizations.stream().anyMatch(r -> "APPROVED".equals(r.getStatus()));

        // Section 27/34: exempt-count windows follow whichever cycle (WEEK/MONTH) the effective
        // policy configured for that section — never mixed, and never retroactively reinterpreted
        // for a cycle change (the window is derived from exceptionDate itself, not "now").
        LocalDate[] laPeriod = cyclePeriod(exceptionDate, version != null ? version.getLaExemptPeriod() : null);
        LocalDate[] mlPeriod = cyclePeriod(exceptionDate, version != null ? version.getMlExemptPeriod() : null);
        // Counted straight off Attendance.status == LATE (live), NOT off the AttendanceException
        // snapshot rows upsertException wrote — the same query LatePenaltyService has always used
        // for its own "every 3rd late this month" count (see AttendanceRepository's Javadoc on
        // this method). AttendanceException rows are an append-only, never-deleted detection
        // audit trail (see upsertException) — counting them directly would keep a date counted
        // forever even after a regularization corrects that day's Attendance back to PRESENT.
        // Attendance.status, by contrast, IS corrected in place by RegularizationService.approve
        // (see its own applyInterpretation), so counting it here means a regularized late arrival
        // stops contributing to this and every later occurrence's threshold automatically, with
        // no second, independently-maintained "is this incident still valid" flag to keep in sync.
        int lateArrivalCount = (int) attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                employeeUserId, laPeriod[0], laPeriod[1], STATUS_LATE);
        int missingLogCount = (int) attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                employeeUserId, ExceptionType.MISSING_PUNCH, mlPeriod[0], mlPeriod[1]);

        boolean lateArrivalSameDay = !ExceptionType.LATE_ARRIVAL.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.LATE_ARRIVAL);
        boolean workHoursShortageSameDay = !ExceptionType.WORK_HOURS_SHORTAGE.equals(exceptionType)
                && attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate, ExceptionType.WORK_HOURS_SHORTAGE);

        // Section 33: a late arrival that coincides with an unresolved missing log on the
        // immediately preceding working day is treated as "caused by" it — the cheapest fact
        // derivable from Attendance without inventing a richer causal signal that doesn't exist.
        boolean lateArrivalCausedByMissingLog = attendanceExceptionRepository
                .existsByEmployeeUserIdAndExceptionDateAndExceptionType(
                        employeeUserId, exceptionDate.minusDays(1), ExceptionType.MISSING_PUNCH);

        // NOT unified with the NUMBER_OF_INCIDENTS basis above: the "Total Late Hours in Shift"
        // basis (Section 25/29/31) is a distinct, separately-configured Penalization Policy mode —
        // an org running THIS basis instead is choosing to measure cumulative lateness minutes
        // against its own configured allowed-hours/tiers, not "every Nth incident" at all, so
        // laGracePeriodMinutes here is this mode's own threshold input, not a second copy of the
        // shift's allowed-late privilege. Left unchanged by this fix (which targets the
        // NUMBER_OF_INCIDENTS basis exercised by every acceptance scenario in this change).
        Integer lateMinutesTotalInPeriod = null;
        if (version != null && "TOTAL_HOURS".equals(version.getLaBasis())) {
            int grace = version.getLaGracePeriodMinutes() != null ? version.getLaGracePeriodMinutes() : 0;
            lateMinutesTotalInPeriod = attendanceExceptionRepository
                    .findByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                            employeeUserId, ExceptionType.LATE_ARRIVAL, laPeriod[0], laPeriod[1])
                    .stream()
                    .mapToInt(e -> Math.max(0, (e.getMinutesLate() != null ? e.getMinutesLate() : 0) - grace))
                    .sum();
        }

        return PolicyEvaluationContext.builder()
                .employeeUserId(employeeUserId)
                .attendanceDate(exceptionDate)
                .discrepancyType(exceptionType)
                .assignedPolicyId(assignedPolicyId)
                .evaluationDate(today)
                .underNoticePeriod(isUnderNoticePeriod(employee, exceptionDate))
                .hasPendingRegularization(hasPending)
                .hasApprovedRegularization(hasApproved)
                .lateMinutes(record.getLateByMinutes())
                .workedMinutes(record.getWorkedMinutes())
                .effectiveHoursPercent(computeEffectiveHoursPercent(record, employee))
                .workHoursShortagePercent(employee != null
                        ? workHoursShortageCalculationService.computeShortagePercent(employee, exceptionDate, version) : null)
                .lateArrivalCountInPeriod(lateArrivalCount)
                .missingLogCountInPeriod(missingLogCount)
                .lateArrivalAlsoOccurredSameDay(lateArrivalSameDay)
                .workHoursShortageAlsoOccurredSameDay(workHoursShortageSameDay)
                .lateArrivalCausedByMissingLog(lateArrivalCausedByMissingLog)
                .lateMinutesTotalInPeriod(lateMinutesTotalInPeriod)
                .build();
    }

    /**
     * Gap-033/034: the shared re-evaluation engine both regularization approval and leave approval
     * call once they've changed the facts an existing penalty was based on — {@code
     * RegularizationService#approve} after correcting the day's Attendance record, {@code
     * LeaveService#approve} after approving leave that changes what a day's expected work hours
     * are. Re-derives, for each still-active penalty of a CANDIDATE discrepancy type on this
     * employee/date, whether that discrepancy still holds against CURRENT data — reusing the exact
     * same working-day gate {@link #detectExceptions} applies, the same per-type detection
     * predicate {@link #detectNoAttendanceAndShortage} uses, and the same {@link AttendancePolicyEngine}
     * decision {@link #evaluatePolicy} uses — and reverses only the ones that no longer do. A
     * penalty whose type isn't in {@code candidateDiscrepancyTypes}, or that's already
     * CANCELLED/REVERSED, is left untouched. Never creates a new penalty and never mutates
     * {@code Attendance}/{@code AttendanceException} rows — this is read-then-reverse only.
     */
    @Transactional
    public void reevaluateAndReverseIfInvalid(UUID employeeUserId, LocalDate date, Set<String> candidateDiscrepancyTypes,
                                               UUID actorId, String reason, String auditAction) {
        List<AttendancePenalty> candidates = attendancePenaltyRepository
                .findByEmployeeUserIdAndIncidentDate(employeeUserId, date).stream()
                .filter(p -> candidateDiscrepancyTypes.contains(p.getDiscrepancyType()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        Employee employee = employeeRepository.findById(employeeUserId).orElse(null);
        if (employee == null) {
            return;
        }
        LocalDate today = LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
        WorkingDaySchedule schedule = workingDayService.computeExpectedWorkingDays(employee, date, date);
        boolean isWorkingDay = schedule != null && schedule.getWorkingDates().contains(date);
        Attendance record = attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeUserId, date).orElse(null);
        PenalizationPolicyVersion version = penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, date);

        for (AttendancePenalty penalty : candidates) {
            boolean stillValid = isWorkingDay
                    && isDiscrepancyStillActive(penalty.getDiscrepancyType(), employee, date, record, version, today);
            if (!stillValid) {
                attendancePenaltyService.reverseIfActive(penalty.getId(), actorId, reason, auditAction);
            }
        }
    }

    /**
     * Mirrors the exact upstream gate each discrepancy type already has to pass before it can even
     * reach {@link AttendancePolicyEngine#evaluate} in {@link #detectExceptions}/
     * {@link #detectNoAttendanceAndShortage} — a discrepancy whose own fact no longer holds is
     * immediately invalid without needing an engine call; one whose fact still holds still needs
     * the engine's own gate/tier decision (Scenario A: a regularization that only fixes lateness
     * must not silently invalidate a still-legitimate shortage penalty by fact-check alone).
     */
    private boolean isDiscrepancyStillActive(String discrepancyType, Employee employee, LocalDate date,
                                              Attendance record, PenalizationPolicyVersion version, LocalDate today) {
        return switch (discrepancyType) {
            // Same STATUS_LATE gate detectExceptions itself uses, not a bare lateByMinutes check —
            // a regularization that corrects this record's status away from LATE (see
            // RegularizationService#applyInterpretation) must make this discrepancy inactive here
            // too, for exactly the same reason it must never have been counted in the first place.
            case ExceptionType.LATE_ARRIVAL -> record != null && STATUS_LATE.equals(record.getStatus())
                    && engineStillAppliesPenalty(discrepancyType, record);
            case ExceptionType.MISSING_PUNCH -> record != null && record.isMissingCheckOut() && date.isBefore(today)
                    && engineStillAppliesPenalty(discrepancyType, record);
            case ExceptionType.NO_ATTENDANCE -> record == null
                    && engineStillAppliesPenalty(discrepancyType, syntheticNoAttendanceRecord(employee, date));
            case ExceptionType.WORK_HOURS_SHORTAGE -> stillHasShortageFact(employee, date, record, version)
                    && engineStillAppliesPenalty(discrepancyType,
                            record != null ? record : syntheticNoAttendanceRecord(employee, date));
            default -> true; // unknown/unhandled type — never touch what this method doesn't understand
        };
    }

    /** Same predicate {@link #detectNoAttendanceAndShortage}'s DAY-mode branch uses, minus the cyclic-frequency case. */
    private boolean stillHasShortageFact(Employee employee, LocalDate date, Attendance record, PenalizationPolicyVersion version) {
        if (record != null && record.getCheckOutAt() != null && record.getWorkedMinutes() != null) {
            Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(employee, date);
            return expectedMinutes != null && record.getWorkedMinutes() < expectedMinutes;
        }
        boolean missingLogShortageEnabled = version != null && version.isWhsPenalizeShortageCausedByMissingLogsEnabled();
        if (record != null && missingLogShortageEnabled && record.isMissingCheckOut()) {
            Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(employee, date);
            return expectedMinutes != null && expectedMinutes > 0;
        }
        return false;
    }

    /** Same transient-record pattern {@link #detectNoAttendanceAndShortage} builds for a day with no punch at all. */
    private Attendance syntheticNoAttendanceRecord(Employee employee, LocalDate date) {
        return Attendance.builder().employeeUserId(employee.getUserId()).workDate(date).workedMinutes(0).build();
    }

    /** Read-only: builds the same context {@link #evaluatePolicy} would, but only asks the engine — never persists. */
    private boolean engineStillAppliesPenalty(String discrepancyType, Attendance record) {
        PolicyEvaluationContext context = buildContext(record, discrepancyType);
        return attendancePolicyEngine.evaluate(context).getType() == PolicyDecisionType.APPLY_PENALTY;
    }

    /**
     * Monday-Sunday for {@code WEEK} (Section 34); calendar month for {@code MONTH} or unset. The
     * one definition of "the policy's cycle boundaries" — also reused by
     * {@link WorkHoursShortageCalculationService} for weekly/monthly Work Hours Shortage
     * aggregation, so a "week"/"month" never means something subtly different there.
     */
    static LocalDate[] cyclePeriod(LocalDate date, String periodUnit) {
        if ("WEEK".equals(periodUnit)) {
            LocalDate start = date.minusDays(date.getDayOfWeek().getValue() - 1L);
            return new LocalDate[]{start, start.plusDays(6)};
        }
        return new LocalDate[]{date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())};
    }

    /** True when {@code date} falls within the employee's notice period, per Employee's fields. */
    private boolean isUnderNoticePeriod(Employee employee, LocalDate date) {
        if (employee == null || employee.getLastWorkingDay() == null || date.isAfter(employee.getLastWorkingDay())) {
            return false;
        }
        return employee.getNoticePeriodStartDate() == null || !date.isBefore(employee.getNoticePeriodStartDate());
    }

    /**
     * {@code workedMinutes} as a percentage of the employee's expected minutes for that date —
     * the assigned shift duration, reduced by any approved hourly/quarter-day leave on that date
     * (see ExpectedWorkHoursService) — null (not 0%) when a required fact is unavailable, so the
     * engine can tell "no data" apart from "worked nothing".
     */
    private Double computeEffectiveHoursPercent(Attendance record, Employee employee) {
        if (record.getWorkedMinutes() == null || employee == null) {
            return null;
        }
        Long expectedMinutes = expectedWorkHoursService.adjustedExpectedMinutes(employee, record.getWorkDate());
        return WorkHoursCalculator.minutesToPercent(record.getWorkedMinutes(), expectedMinutes);
    }

    /** Null if the employee has no current manager on file — the email is simply sent without a cc. */
    private String currentManagerEmail(UUID employeeUserId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .flatMap(userRepository::findById)
                .map(User::getEmail)
                .orElse(null);
    }

    private ExceptionResponse toResponse(AttendanceException exception) {
        Optional<Employee> employee = employeeRepository.findById(exception.getEmployeeUserId());
        return ExceptionResponse.builder()
                .id(exception.getId())
                .employeeUserId(exception.getEmployeeUserId())
                .employeeCode(employee.map(Employee::getEmployeeCode).orElse(null))
                .employeeFullName(employee.map(Employee::getFullName).orElse(null))
                .exceptionDate(exception.getExceptionDate())
                .exceptionType(exception.getExceptionType())
                .expectedTime(exception.getExpectedTime())
                .actualTime(exception.getActualTime())
                .minutesLate(exception.getMinutesLate())
                .status(exception.getStatus())
                .detectedAt(exception.getDetectedAt())
                .build();
    }
}
