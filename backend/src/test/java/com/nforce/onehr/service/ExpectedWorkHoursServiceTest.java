package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Section 9/16 — adjusted expected work hours after approved hourly/quarter-day leave. */
@ExtendWith(MockitoExtension.class)
class ExpectedWorkHoursServiceTest {

    @Mock private LeaveRequestRepository leaveRequestRepository;

    private ExpectedWorkHoursService service;

    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 10);
    // A minimal, real (not mocked) Shift Version resolver — single-version-per-shift, in-memory
    // "latest effectiveFrom <= day" lookup, mirroring ShiftVersionRepository's own query semantics.
    private final List<ShiftVersion> shiftVersions = new ArrayList<>();
    private final ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
        @Override
        public ShiftVersion resolve(Shift shift, LocalDate workDate) {
            return shiftVersions.stream()
                    .filter(v -> v.getShift().getId().equals(shift.getId()))
                    .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(ShiftVersion::getEffectiveFrom))
                    .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
        }
    };

    /** Builds a Shift (with a real id) and registers a single version effective from the dawn of time. */
    private Shift shift(String name, LocalTime start, LocalTime end) {
        Shift s = Shift.builder().id(UUID.randomUUID()).name(name).build();
        shiftVersions.add(ShiftVersion.builder().shift(s).startTime(start).endTime(end).effectiveFrom(LocalDate.MIN).build());
        return s;
    }

    // 9:00-18:00 = 540 minutes (9 hours).
    private final Shift nineHourShift = shift("Regular", LocalTime.of(9, 0), LocalTime.of(18, 0));

    // A minimal, real (not mocked) assignment resolver — mirrors the ShiftVersionResolver fake
    // above one layer up: shiftMinutes now resolves the Shift from the employee's ASSIGNMENT
    // effective on the date in question (never employee.getShift()), so every test needs a
    // matching assignment registered — see employeeWithShift below.
    private final List<EmployeeShiftAssignment> assignments = new ArrayList<>();
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver = new EmployeeShiftAssignmentResolver(null) {
        @Override
        public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
            return assignments.stream()
                    .filter(a -> a.getEmployeeUserId().equals(employeeUserId))
                    .filter(a -> !a.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(EmployeeShiftAssignment::getEffectiveFrom));
        }

        @Override
        public EmployeeShiftAssignment resolve(UUID employeeUserId, LocalDate workDate) {
            return resolveIfPresent(employeeUserId, workDate)
                    .orElseThrow(() -> new IllegalStateException("no assignment effective on or before " + workDate));
        }
    };

    @BeforeEach
    void setUp() {
        service = new ExpectedWorkHoursService(leaveRequestRepository, shiftVersionResolver, employeeShiftAssignmentResolver);
    }

    /** Registers a Shift Assignment for the shared test employee (effective from the dawn of time, matching the shift() fixture's own convention) and builds the Employee — null clears any assignment (no-shift case). */
    private Employee employeeWithShift(Shift shift) {
        if (shift != null) {
            assignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeId).shift(shift).effectiveFrom(LocalDate.MIN).build());
        }
        return Employee.builder().userId(employeeId).fullName("Test Employee").shift(shift).build();
    }

    @Test
    void noShift_returnsNull() {
        Employee noShift = employeeWithShift(null);
        assertNull(service.shiftMinutes(noShift, date));
    }

    @Test
    void noPartialLeave_returnsFullShiftMinutes() {
        Employee employee = employeeWithShift(nineHourShift);
        assertEquals(540L, service.adjustedExpectedMinutes(employee, date, null));
    }

    @Test
    void quarterDayLeave_reducesExpectedMinutesByAQuarterOfTheShift() {
        Employee employee = employeeWithShift(nineHourShift);
        LeaveRequest quarterDay = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build();

        // 540 * 0.25 = 135 -> 540 - 135 = 405
        assertEquals(405L, service.adjustedExpectedMinutes(employee, date, quarterDay));
    }

    @Test
    void hourlyLeave_reducesExpectedMinutesByExactlyTheRequestedHours() {
        Employee employee = employeeWithShift(nineHourShift);
        LeaveRequest hourly = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                .leaveHours(new BigDecimal("2")).build();

        // 540 - (2 * 60) = 420
        assertEquals(420L, service.adjustedExpectedMinutes(employee, date, hourly));
    }

    @Test
    void adjustedExpectedMinutes_neverGoesNegative() {
        // Pathological: leaveHours somehow exceeds the shift — floor at 0, don't go negative.
        Shift oneHourShift = shift("Short", LocalTime.of(9, 0), LocalTime.of(10, 0));
        Employee employee = employeeWithShift(oneHourShift);
        LeaveRequest hourly = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                .leaveHours(new BigDecimal("5")).build();

        assertEquals(0L, service.adjustedExpectedMinutes(employee, date, hourly));
    }

    /**
     * Confirmed bug, now fixed: {@code Duration.between} on two bare {@link LocalTime} values has
     * no notion of "next day," so an overnight shift (end not after start) used to compute a
     * negative duration and get silently treated as "no shift" (null) — exactly like the org's
     * own default shift (15:30-00:30). Same elapsed span as the 9-hour day-shift case above.
     */
    @Test
    void overnightShift_returnsCorrectPositiveMinutes_insteadOfNullFromTheOldNegativeDurationBug() {
        Shift overnightShift = shift("Regular Shift", LocalTime.of(15, 30), LocalTime.of(0, 30));
        Employee employee = employeeWithShift(overnightShift);

        assertEquals(540L, service.shiftMinutes(employee, date), "15:30-00:30 is a 9-hour shift, not a negative/null one");
        assertEquals(540L, service.adjustedExpectedMinutes(employee, date, null));
    }

    /** Same overnight fix, exercised through the adjusted-minutes path leave reduction uses. */
    @Test
    void overnightShift_quarterDayLeave_reducesExpectedMinutesCorrectly() {
        Shift overnightShift = shift("Overnight for quarter-day test", LocalTime.of(15, 30), LocalTime.of(0, 30));
        Employee employee = employeeWithShift(overnightShift);
        LeaveRequest quarterDay = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build();

        // 540 * 0.25 = 135 -> 540 - 135 = 405, same as the non-overnight 9-hour case.
        assertEquals(405L, service.adjustedExpectedMinutes(employee, date, quarterDay));
    }

    @Test
    void noShift_returnsNullEvenWithApprovedPartialLeave() {
        Employee employee = employeeWithShift(null);
        LeaveRequest quarterDay = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build();

        assertNull(service.adjustedExpectedMinutes(employee, date, quarterDay));
    }

    @Test
    void loadPartialHourLeaveByEmployeeDate_onlyReturnsHourlyAndQuarterDayTypes() {
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder().employeeUserId(employeeId)
                        .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                        .leaveHours(new BigDecimal("1")).build()));

        Map<String, LeaveRequest> result = service.loadPartialHourLeaveByEmployeeDate(List.of(employeeId), date, date);

        assertEquals(1, result.size());
        assertEquals(LeaveDurationType.HOURLY, result.get(employeeId + "|" + date).getDurationType());
    }

    @Test
    void adjustedExpectedMinutes_singleArgOverload_loadsFromRepository() {
        Employee employee = employeeWithShift(nineHourShift);
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder().employeeUserId(employeeId)
                        .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build()));

        assertEquals(405L, service.adjustedExpectedMinutes(employee, date));
    }

    /**
     * A Shift Version resolves against the specific date passed in, not "now" — a historical date
     * (before a newer version's effectiveFrom) must still use the OLD version's duration, exactly
     * the invariant the whole Shift Versioning design exists to protect.
     */
    @Test
    void shiftMinutes_historicalDate_resolvesTheVersionEffectiveOnThatDate_notTheCurrentOne() {
        Shift shift = Shift.builder().id(UUID.randomUUID()).name("Versioned").build();
        shiftVersions.add(ShiftVersion.builder().shift(shift).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(LocalDate.MIN).build());
        shiftVersions.add(ShiftVersion.builder().shift(shift).startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(15, 0)).effectiveFrom(date.plusDays(5)).build());
        Employee employee = employeeWithShift(shift);

        assertEquals(540L, service.shiftMinutes(employee, date), "before the new version's effectiveFrom — must still resolve the OLD (9-18) duration");
        assertEquals(540L, service.shiftMinutes(employee, date.plusDays(10)), "on/after the new version's effectiveFrom — resolves the NEW (6-15) duration, same 9h span here");
    }

    /**
     * The historical-integrity invariant one layer up from Shift Versioning: a later EMPLOYEE
     * REASSIGNMENT (a different Shift entity entirely, not just a new timing version of the same
     * one) must never retroactively change an expected-hours figure already applicable to a past
     * date. The employee was on Shift A (9h) on {@code date}; a reassignment to Shift B (4h)
     * becomes effective the day after — {@code shiftMinutes} for {@code date} itself must still
     * resolve Shift A's own duration, proving it resolves the ASSIGNMENT effective on that
     * specific date, never the employee's current one.
     */
    @Test
    void shiftMinutes_employeeReassignedSinceThisDate_stillResolvesTheAssignmentEffectiveOnThatDate() {
        Shift shiftA = shift("Nine Hour", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Shift shiftB = shift("Four Hour", LocalTime.of(9, 0), LocalTime.of(13, 0));
        Employee employee = employeeWithShift(shiftA);
        assignments.add(EmployeeShiftAssignment.builder().employeeUserId(employeeId).shift(shiftB).effectiveFrom(date.plusDays(1)).build());

        assertEquals(540L, service.shiftMinutes(employee, date), "still Shift A's own 9h duration — the reassignment hasn't taken effect yet as of this date");
        assertEquals(240L, service.shiftMinutes(employee, date.plusDays(1)), "Shift B governs from its own effective date onward");
    }
}
