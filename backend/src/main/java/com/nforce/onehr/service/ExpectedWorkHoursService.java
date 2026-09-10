package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Single source of truth for "how many minutes was this employee expected to work on this date,"
 * after approved hourly/quarter-day leave (see LeaveDurationType) reduces the day's assigned-shift
 * duration. Both the Penalization Policy engine's fact-gathering (ExceptionService) and Attendance
 * Summary must call this rather than each computing their own adjusted figure — the whole point of
 * "adjusted expected hours" is that every consumer sees the same number.
 *
 * <p>Full-day and half-day leave are NOT handled here — those still remove the entire date via
 * {@link WorkingDayService} exactly as before this change; this service only ever reduces (never
 * zeroes) a day that remains a working day.
 */
@Service
@RequiredArgsConstructor
public class ExpectedWorkHoursService {

    private static final String LEAVE_STATUS_APPROVED = "APPROVED";
    private static final List<String> PARTIAL_HOUR_DURATION_TYPES =
            List.of(LeaveDurationType.HOURLY, LeaveDurationType.QUARTER_DAY);

    private final LeaveRequestRepository leaveRequestRepository;
    private final ShiftVersionResolver shiftVersionResolver;
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;

    /**
     * The employee's assigned shift duration in minutes on {@code date} — resolved from the Shift
     * Assignment effective on THAT SPECIFIC DATE (never {@code employee.getShift()}, a best-effort
     * display cache that may not reflect what governed a historical or future date — see that
     * field's own Javadoc), and the Shift Version effective on that same date, so neither a later
     * reassignment nor a later timing change can retroactively change a historical expected-hours
     * figure. Null if no assignment covers {@code date} (a legacy/no-shift state — "cannot be
     * evaluated," never guessed by falling back to the employee's current assignment).
     * Overnight-aware: {@code Duration.between} on two bare {@link java.time.LocalTime} values has
     * no notion of "next day" — for an overnight shift (end not after start, e.g. the org's own
     * default 15:30-00:30) it returns a negative value, which used to be silently treated as "no
     * shift" (returning null here, same as an employee with no shift at all). That masked
     * expected-hours/Work-Hours-Shortage evaluation for every overnight-shift employee. Corrected
     * using the exact same rollover rule already established elsewhere for this purpose (see
     * {@link ShiftDayPolicy#shiftEndAt}): when the end is not after the start, it falls on the
     * next calendar day, so the elapsed span wraps forward by 24h instead of going negative.
     */
    public Long shiftMinutes(Employee employee, LocalDate date) {
        if (employee == null) {
            return null;
        }
        Optional<EmployeeShiftAssignment> assignment = employeeShiftAssignmentResolver.resolveIfPresent(employee.getUserId(), date);
        if (assignment.isEmpty()) {
            return null;
        }
        Shift shift = assignment.get().getShift();
        ShiftVersion version = shiftVersionResolver.resolve(shift, date);
        LocalTime start = version.getStartTime();
        LocalTime end = version.getEndTime();
        long minutes = Duration.between(start, end).toMinutes();
        if (!end.isAfter(start)) {
            minutes += 24 * 60;
        }
        return minutes > 0 ? minutes : null;
    }

    /** Convenience single employee/date form of {@link #loadPartialHourLeaveByEmployeeDate} — issues its own single-row query, so prefer the bulk form when evaluating a range of employees/dates. */
    public Long adjustedExpectedMinutes(Employee employee, LocalDate date) {
        if (employee == null) {
            return null;
        }
        Map<String, LeaveRequest> byEmployeeDate =
                loadPartialHourLeaveByEmployeeDate(List.of(employee.getUserId()), date, date);
        return adjustedExpectedMinutes(employee, date, byEmployeeDate.get(key(employee.getUserId(), date)));
    }

    /** Same calculation as {@link #adjustedExpectedMinutes(Employee, LocalDate)}, given an already-resolved partial-hour leave (or null) — for bulk callers that pre-load the map once per batch instead of once per employee/date. */
    public Long adjustedExpectedMinutes(Employee employee, LocalDate date, LeaveRequest partialHourLeave) {
        Long shiftMinutes = shiftMinutes(employee, date);
        if (shiftMinutes == null) {
            return null;
        }
        if (partialHourLeave == null) {
            return shiftMinutes;
        }
        long reduction = switch (partialHourLeave.getDurationType()) {
            case LeaveDurationType.HOURLY -> partialHourLeave.getLeaveHours() != null
                    ? partialHourLeave.getLeaveHours().multiply(BigDecimal.valueOf(60)).longValue()
                    : 0L;
            case LeaveDurationType.QUARTER_DAY -> Math.round(shiftMinutes * 0.25);
            default -> 0L; // shouldn't reach here — loadPartialHourLeaveByEmployeeDate only returns HOURLY/QUARTER_DAY rows
        };
        return Math.max(0L, shiftMinutes - reduction);
    }

    /** Approved hourly/quarter-day leave overlapping [from, to] for the given employees, keyed by "employeeId|date" (both types are always single-day, so one leave request maps to exactly one key). */
    public Map<String, LeaveRequest> loadPartialHourLeaveByEmployeeDate(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        List<LeaveRequest> leaves = leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        employeeIds, LEAVE_STATUS_APPROVED, PARTIAL_HOUR_DURATION_TYPES, to, from);
        Map<String, LeaveRequest> result = new HashMap<>();
        for (LeaveRequest leave : leaves) {
            result.put(key(leave.getEmployeeUserId(), leave.getStartDate()), leave);
        }
        return result;
    }

    private static String key(UUID employeeUserId, LocalDate date) {
        return employeeUserId + "|" + date;
    }
}
