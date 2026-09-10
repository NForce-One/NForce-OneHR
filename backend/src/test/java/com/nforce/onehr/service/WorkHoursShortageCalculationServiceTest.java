package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Phase 3 Sections 2/4/5/6/7/8/10 — Work Hours Shortage's basis, frequency, shift-exclusion, and missing-log linkage. */
@ExtendWith(MockitoExtension.class)
class WorkHoursShortageCalculationServiceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;
    @Mock private WorkingDayService workingDayService;
    @Mock private ShiftRepository shiftRepository;

    private WorkHoursShortageCalculationService service;

    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate monday = LocalDate.of(2026, 8, 3);
    private final LocalDate tuesday = monday.plusDays(1);
    // A minimal, real (not mocked) Shift Version resolver — single-version-per-shift, in-memory
    // "latest effectiveFrom <= day" lookup, mirroring ShiftVersionRepository's own query semantics.
    private final List<ShiftVersion> shiftVersions = new java.util.ArrayList<>();
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
        allShifts.add(s);
        return s;
    }

    private final List<Shift> allShifts = new java.util.ArrayList<>();
    private final Shift nineToSix = shift("Regular", LocalTime.of(9, 0), LocalTime.of(18, 0));

    @BeforeEach
    void setUp() {
        service = new WorkHoursShortageCalculationService(attendanceRepository, expectedWorkHoursService, workingDayService, shiftVersionResolver, shiftRepository);
        // shiftBoundedGrossMinutes now resolves the Shift from the Attendance record's own
        // snapshotted shiftId (never the employee's current/live Shift) — see attendance()'s own
        // comment on why every fixture that exercises shift-bounding sets it.
        lenient().when(shiftRepository.findById(any())).thenAnswer(inv -> shiftById(inv.getArgument(0)));
    }

    private Optional<Shift> shiftById(UUID id) {
        return allShifts.stream().filter(s -> s.getId().equals(id)).findFirst();
    }

    private Employee employeeWithShift(Shift shift) {
        return Employee.builder().userId(employeeId).fullName("Test Employee").shift(shift).build();
    }

    private PenalizationPolicyVersion.PenalizationPolicyVersionBuilder version() {
        return PenalizationPolicyVersion.builder().id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(monday.minusMonths(1).atStartOfDay()).workHoursShortageEnabled(true);
    }

    /** Snapshots {@code nineToSix} by default — every test using this helper puts its employee on that same shift; see the explicit-shift overload for the one test that needs a different (or no) snapshot. */
    private Attendance attendance(LocalDate date, LocalTime checkIn, LocalTime checkOut, int workedMinutes) {
        return attendance(date, checkIn, checkOut, workedMinutes, nineToSix);
    }

    /**
     * shiftBoundedGrossMinutes now resolves the Shift from THIS record's own snapshotted
     * shiftId (never the employee's current/live Shift) — {@code shift} here must be null,
     * exactly like a real legacy pre-{@code shiftId} row, only when the test deliberately means
     * to exercise the "cannot be evaluated" fallback.
     */
    private Attendance attendance(LocalDate date, LocalTime checkIn, LocalTime checkOut, int workedMinutes, Shift shift) {
        return Attendance.builder().employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(checkIn)).checkOutAt(date.atTime(checkOut)).workedMinutes(workedMinutes)
                .shiftId(shift != null ? shift.getId() : null).build();
    }

    // ── DAY frequency (default) ──

    @Test
    void dayFrequency_effectiveBasis_usesWorkedMinutesAgainstAdjustedExpected() {
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = attendance(monday, LocalTime.of(9, 0), LocalTime.of(17, 0), 400);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);
        PenalizationPolicyVersion version = version().whsDeductionBasis("EFFECTIVE_HOURS").build();

        Double percent = service.computeShortagePercent(employee, monday, version);

        assertEquals(400 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void dayFrequency_grossBasis_sameAttendance_differentResultThanEffective() {
        Employee employee = employeeWithShift(nineToSix);
        // 8h punch span (9:00-17:00 = 480 min gross) but only 400 min actually effective (a break in between).
        Attendance record = attendance(monday, LocalTime.of(9, 0), LocalTime.of(17, 0), 400);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        PenalizationPolicyVersion effectiveVersion = version().whsDeductionBasis("EFFECTIVE_HOURS").build();
        PenalizationPolicyVersion grossVersion = version().whsDeductionBasis("GROSS_HOURS").build();

        Double effectivePercent = service.computeShortagePercent(employee, monday, effectiveVersion);
        Double grossPercent = service.computeShortagePercent(employee, monday, grossVersion);

        assertEquals(400 * 100.0 / 540, effectivePercent, 0.001);
        assertEquals(480 * 100.0 / 540, grossPercent, 0.001);
        assertTrue(grossPercent > effectivePercent, "gross span (includes the break) must be >= effective worked minutes");
    }

    @Test
    void dayFrequency_bothBases_useTheSameAdjustedExpectedMinutes() {
        // Proves neither basis computes its own expected-hours figure — both delegate to
        // ExpectedWorkHoursService for the exact same date.
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = attendance(monday, LocalTime.of(9, 0), LocalTime.of(17, 0), 400);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(300L); // leave-adjusted, not the full 540

        Double percent = service.computeShortagePercent(employee, monday, version().whsDeductionBasis("EFFECTIVE_HOURS").build());

        assertEquals(400 * 100.0 / 300, percent, 0.001);
    }

    @Test
    void dayFrequency_missingCheckOut_defaultToggleOff_returnsNull() {
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(monday)
                .checkInAt(monday.atTime(9, 0)).checkOutAt(null).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));

        Double percent = service.computeShortagePercent(employee, monday, version().build());

        assertNull(percent, "a missing log must never be evaluated for shortage unless explicitly opted in");
    }

    @Test
    void dayFrequency_missingCheckOut_toggleOn_treatsAsZeroWorkedMinutes() {
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(monday)
                .checkInAt(monday.atTime(9, 0)).checkOutAt(null).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsPenalizeShortageCausedByMissingLogsEnabled(true).build());

        assertEquals(0.0, percent, 0.001);
    }

    @Test
    void dayFrequency_noAttendanceAtAll_returnsNull() {
        Employee employee = employeeWithShift(nineToSix);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.empty());

        assertNull(service.computeShortagePercent(employee, monday, version().build()));
    }

    // ── Exclude hours outside shift timing ──

    @Test
    void excludeOutsideShift_earlyArrival_isTrimmed() {
        Employee employee = employeeWithShift(nineToSix);
        // Shift 09:00-18:00; punched in at 08:00 (1h early) through 18:00 (on time).
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(18, 0), 600);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        // Trimmed to the shift window: 09:00-18:00 = 540 min, not the full 600.
        assertEquals(540 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_lateDeparture_isTrimmed() {
        Employee employee = employeeWithShift(nineToSix);
        // Shift 09:00-18:00; punched 09:00 through 19:00 (1h late departure).
        Attendance record = attendance(monday, LocalTime.of(9, 0), LocalTime.of(19, 0), 600);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        assertEquals(540 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_bothEarlyAndLate_isTrimmedToExactlyTheShiftWindow() {
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(19, 0), 660);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        assertEquals(100.0, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_disabled_includesAllPunchedHours() {
        Employee employee = employeeWithShift(nineToSix);
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(18, 0), 600);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(false).build());

        // Full 10h (08:00-18:00) counted — not trimmed.
        assertEquals(600 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_effectiveBasis_isCappedByTheShiftBoundedSpan() {
        Employee employee = employeeWithShift(nineToSix);
        // Effective (workedMinutes) is 620 (implausibly close to the full punch span with barely
        // any break) but the punch span itself extends outside the shift window on both sides.
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(19, 0), 620);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("EFFECTIVE_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        // Capped at the shift-bounded gross span (540 min), not the raw 620.
        assertEquals(100.0, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_overnightShift_windowCrossesMidnightCorrectly() {
        Shift overnight = shift("Night", LocalTime.of(22, 0), LocalTime.of(6, 0));
        Employee employee = employeeWithShift(overnight);
        // Punched in at 21:00 (1h early) through 07:00 next day (1h late) — spans midnight.
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(monday)
                .checkInAt(monday.atTime(21, 0)).checkOutAt(monday.plusDays(1).atTime(7, 0)).workedMinutes(600)
                .shiftId(overnight.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(480L); // 22:00-06:00 = 8h

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        // Trimmed to 22:00 -> 06:00 (next day) = 480 min, not the full 10h span.
        assertEquals(100.0, percent, 0.001);
    }

    /**
     * The historical-integrity invariant Attendance.shiftId's own snapshot exists to protect —
     * one layer up from Shift Versioning: a later EMPLOYEE REASSIGNMENT (a different Shift
     * entity entirely, not just a new timing version of the same one) must never retroactively
     * change a shortage figure already computed for a past date. The record was snapshotted
     * under nineToSix (09:00-18:00); the employee object handed to computeShortagePercent now
     * carries a completely different current shift — proving the bounded figure still comes
     * from the record's own snapshot, not employee.getShift().
     */
    @Test
    void excludeOutsideShift_employeeReassignedSinceThisRecord_stillUsesTheRecordsOwnSnapshottedShift() {
        Shift reassignedTo = shift("Night", LocalTime.of(22, 0), LocalTime.of(6, 0));
        Employee employeeNow = employeeWithShift(reassignedTo);
        // Snapshotted under nineToSix (09:00-18:00) — the shift actually in effect when this
        // record was created, before the reassignment above.
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(19, 0), 600, nineToSix);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employeeNow, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employeeNow, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        // Trimmed to nineToSix's own 09:00-18:00 = 540 min, not the new shift's 22:00-06:00
        // window (which wouldn't even overlap this record's 08:00-19:00 punch at all).
        assertEquals(540 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void excludeOutsideShift_noAssignedShift_fallsBackToUnboundedFigure() {
        Employee employee = employeeWithShift(null);
        // No shiftId snapshot either — a real legacy/no-shift row, not just a live-employee gap.
        Attendance record = attendance(monday, LocalTime.of(8, 0), LocalTime.of(18, 0), 600, null);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, monday)).thenReturn(Optional.of(record));
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday,
                version().whsDeductionBasis("GROSS_HOURS").whsExcludeHoursOutsideShiftEnabled(true).build());

        assertEquals(600 * 100.0 / 540, percent, 0.001);
    }

    // ── Weekly / monthly frequency ──

    @Test
    void weeklyFrequency_aggregatesWorkedAndExpectedAcrossTheCycle_usingPerDateAdjustedExpected() {
        Employee employee = employeeWithShift(nineToSix);
        PenalizationPolicyVersion version = version().whsDeductionPeriod("WEEK").build();
        LocalDate sunday = monday.minusDays(1);
        when(workingDayService.computeExpectedWorkingDays(eq(employee), any(), any()))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(monday, tuesday)).build());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(employeeId)), any(), any()))
                .thenReturn(List.of(attendance(monday, LocalTime.of(9, 0), LocalTime.of(15, 0), 360), // 6h
                        attendance(tuesday, LocalTime.of(9, 0), LocalTime.of(18, 0), 540))); // 9h
        when(expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(eq(List.of(employeeId)), any(), any()))
                .thenReturn(Map.of());
        // Monday: 2h hourly leave -> adjusted expected 420; Tuesday: full 540.
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday, null)).thenReturn(420L);
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, tuesday, null)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, sunday.plusDays(4), version);

        // (360 + 540) / (420 + 540) * 100
        assertEquals((360.0 + 540) / (420 + 540) * 100, percent, 0.001);
    }

    @Test
    void weeklyFrequency_dayWithNoAttendanceAtAll_isExcludedFromBothTotals_notCountedAsZero() {
        Employee employee = employeeWithShift(nineToSix);
        PenalizationPolicyVersion version = version().whsDeductionPeriod("WEEK").build();
        when(workingDayService.computeExpectedWorkingDays(eq(employee), any(), any()))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(monday, tuesday)).build());
        // Only Monday has an attendance row — Tuesday is a true absence (No Attendance's concern).
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(employeeId)), any(), any()))
                .thenReturn(List.of(attendance(monday, LocalTime.of(9, 0), LocalTime.of(18, 0), 540)));
        when(expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(eq(List.of(employeeId)), any(), any()))
                .thenReturn(Map.of());
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, monday, null)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, monday, version);

        // Only Monday counted: 540/540 = 100%, Tuesday's absence contributes nothing to either side.
        assertEquals(100.0, percent, 0.001);
    }

    @Test
    void monthlyFrequency_aggregatesAcrossTheWholeCalendarMonth() {
        Employee employee = employeeWithShift(nineToSix);
        PenalizationPolicyVersion version = version().whsDeductionPeriod("MONTH").build();
        LocalDate day1 = LocalDate.of(2026, 8, 3);
        LocalDate day2 = LocalDate.of(2026, 8, 4);
        when(workingDayService.computeExpectedWorkingDays(eq(employee), any(), any()))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(day1, day2)).build());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(employeeId)), any(), any()))
                .thenReturn(List.of(attendance(day1, LocalTime.of(9, 0), LocalTime.of(13, 0), 240),
                        attendance(day2, LocalTime.of(9, 0), LocalTime.of(13, 0), 240)));
        when(expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(eq(List.of(employeeId)), any(), any()))
                .thenReturn(Map.of());
        when(expectedWorkHoursService.adjustedExpectedMinutes(eq(employee), any(), eq(null))).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, day1, version);

        assertEquals((240.0 + 240) / (540 + 540) * 100, percent, 0.001);
    }

    @Test
    void weeklyFrequency_versionEffectiveFromMidCycle_clampsAggregateToOnlyThisVersionsDates() {
        Employee employee = employeeWithShift(nineToSix);
        // Version only takes effect Tuesday — Monday belonged to a different (older) version and
        // must NOT be pulled into this version's weekly aggregate.
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(2)
                .effectiveFrom(tuesday.atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK").build();
        when(workingDayService.computeExpectedWorkingDays(eq(employee), eq(tuesday), any()))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(tuesday)).build());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(employeeId)), eq(tuesday), any()))
                .thenReturn(List.of(attendance(tuesday, LocalTime.of(9, 0), LocalTime.of(15, 0), 360)));
        when(expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(eq(List.of(employeeId)), eq(tuesday), any()))
                .thenReturn(Map.of());
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, tuesday, null)).thenReturn(540L);

        Double percent = service.computeShortagePercent(employee, tuesday, version);

        // Only Tuesday's 360/540 — Monday is never queried (verified via the eq(tuesday) matcher
        // on computeExpectedWorkingDays above; a wider range would fail that stub and NPE instead).
        assertEquals(360 * 100.0 / 540, percent, 0.001);
    }

    @Test
    void weeklyFrequency_versionEffectiveToBeforeCycleEnd_clampsUpperBoundToo() {
        Employee employee = employeeWithShift(nineToSix);
        LocalDate wednesday = tuesday.plusDays(1);
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(monday.minusMonths(1).atStartOfDay())
                .effectiveTo(tuesday.atTime(23, 59, 59)) // superseded starting Wednesday
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK").build();
        when(workingDayService.computeExpectedWorkingDays(eq(employee), eq(monday), eq(tuesday)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(monday, tuesday)).build());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(employeeId)), eq(monday), eq(tuesday)))
                .thenReturn(List.of(attendance(monday, LocalTime.of(9, 0), LocalTime.of(15, 0), 360),
                        attendance(tuesday, LocalTime.of(9, 0), LocalTime.of(15, 0), 360)));
        when(expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(eq(List.of(employeeId)), eq(monday), eq(tuesday)))
                .thenReturn(Map.of());
        when(expectedWorkHoursService.adjustedExpectedMinutes(eq(employee), any(), eq(null))).thenReturn(540L);
        // Wednesday (the actual evaluation-triggering date, e.g. the cycle's own last day per the
        // caller) is deliberately never stubbed on computeExpectedWorkingDays with that upper
        // bound — proving the aggregate never reaches past this version's own effectiveTo.
        lenient().when(workingDayService.computeExpectedWorkingDays(eq(employee), eq(monday), eq(wednesday)))
                .thenThrow(new AssertionError("must not query past this version's effectiveTo"));

        Double percent = service.computeShortagePercent(employee, wednesday, version);

        assertEquals((360.0 + 360) / (540 + 540) * 100, percent, 0.001);
    }

    @Test
    void computeShortagePercent_nullVersion_returnsNull() {
        assertNull(service.computeShortagePercent(employeeWithShift(nineToSix), monday, null));
    }

    @Test
    void computeShortagePercent_nullEmployee_returnsNull() {
        assertNull(service.computeShortagePercent(null, monday, version().build()));
    }
}
