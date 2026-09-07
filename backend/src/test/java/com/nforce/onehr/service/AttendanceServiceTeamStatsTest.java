package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Team Effort (ONEHR-106) and Team Negligence (ONEHR-107) aggregation — pure Mockito, same
 * isolation reasoning as LeaveServiceTest (no @SpringBootTest against the citext-incompatible
 * H2 test profile).
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceTeamStatsTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties props;
    @Mock private WorkingDayService workingDayService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;

    @InjectMocks private AttendanceService attendanceService;

    private final UUID managerId = UUID.randomUUID();
    private final UUID emp1Id = UUID.randomUUID();
    private final UUID emp2Id = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    private final LocalDate day1 = LocalDate.of(2026, 8, 3); // Monday
    private final LocalDate day2 = LocalDate.of(2026, 8, 4); // Tuesday

    private Employee manager;
    private Employee emp1;
    private Employee emp2;

    @BeforeEach
    void setUp() {
        manager = Employee.builder().userId(managerId).fullName("Manager One").build();
        emp1 = Employee.builder().userId(emp1Id).fullName("Employee One").employeeCode("NF-1").build();
        emp2 = Employee.builder().userId(emp2Id).fullName("Employee Two").employeeCode("NF-2").build();

        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(java.util.Optional.of(manager));
        // lenient: the "no direct reports" test overrides this to an empty list.
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id, emp2Id));
        lenient().when(employeeRepository.findAllById(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1, emp2));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1, emp2));
        // Both test employees have no location/weeklyOffPolicy/joiningDate set, so their
        // "working days" over day1..day2 is exactly those two weekdays — same result the old
        // flat countWeekdays(from, to) calculation gave, keeping avgHoursPerDay unaffected.
        lenient().when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                emp1Id, WorkingDaySchedule.builder().employeeUserId(emp1Id).workingDates(Set.of(day1, day2)).build(),
                emp2Id, WorkingDaySchedule.builder().employeeUserId(emp2Id).workingDates(Set.of(day1, day2)).build()));
        // AttendanceService's own "does a real shift exist" gate now reads employee.getShift()
        // directly rather than calling shiftMinutes (see its own comment) — emp1/emp2 above have
        // no shift assigned, so that gate alone already routes them to the flat per-day estimate;
        // no expectedWorkHoursService stub is needed for that decision anymore.
    }

    private Attendance record(UUID id, UUID employeeId, LocalDate date, int workedMinutes, String status) {
        return Attendance.builder()
                .id(id)
                .employeeUserId(employeeId)
                .workDate(date)
                .checkInAt(date.atTime(9, 0))
                .checkOutAt(date.atTime(17, 0))
                .workedMinutes(workedMinutes)
                .status(status)
                .build();
    }

    @Test
    void getTeamEffort_ranksDescByAvgHoursPerDay_scopedToDirectReports() {
        // emp1 averages 7.5 hrs/day; emp2 averages 8.33 hrs/day — emp2 should rank first.
        List<Attendance> records = List.of(
                record(UUID.randomUUID(), emp1Id, day1, 480, "LATE"),
                record(UUID.randomUUID(), emp1Id, day2, 420, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day1, 500, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day2, 500, "PRESENT"));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), day1, day2))
                .thenReturn(records);

        List<TeamEffortEntry> result = attendanceService.getTeamEffort(managerEmail, day1, day2);

        assertEquals(2, result.size());
        assertEquals(emp2Id, result.get(0).getEmployeeUserId());
        assertEquals(emp1Id, result.get(1).getEmployeeUserId());
        assertEquals(8.3, result.get(0).getAvgHoursPerDay(), 0.05);
        assertEquals(7.5, result.get(1).getAvgHoursPerDay(), 0.05);
    }

    @Test
    void getTeamEffort_expectedHours_comesFromWorkingDayServiceSchedule_notAFlatWeekdayCount() {
        // Override the default 2-day setUp() schedule with a 1-day schedule for emp1 only —
        // if getTeamEffort still used a flat countWeekdays(from, to), this would be ignored and
        // expectedHours would come out as 2*8=16 for both employees instead of employee-specific.
        when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                emp1Id, WorkingDaySchedule.builder().employeeUserId(emp1Id).workingDates(Set.of(day1)).build(),
                emp2Id, WorkingDaySchedule.builder().employeeUserId(emp2Id).workingDates(Set.of(day1, day2)).build()));
        List<Attendance> records = List.of(
                record(UUID.randomUUID(), emp1Id, day1, 480, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day1, 480, "PRESENT"));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), day1, day2))
                .thenReturn(records);

        List<TeamEffortEntry> result = attendanceService.getTeamEffort(managerEmail, day1, day2);

        TeamEffortEntry emp1Entry = result.stream().filter(e -> e.getEmployeeUserId().equals(emp1Id)).findFirst().orElseThrow();
        TeamEffortEntry emp2Entry = result.stream().filter(e -> e.getEmployeeUserId().equals(emp2Id)).findFirst().orElseThrow();
        assertEquals(8.0, emp1Entry.getExpectedHours(), 0.01); // 1 working day * 8h — same schedule Punctuality would use
        assertEquals(16.0, emp2Entry.getExpectedHours(), 0.01); // 2 working days * 8h
    }

    @Test
    void getTeamEffort_expectedHours_usesRealAssignedShift_whenEmployeeHasOne_insteadOfFlatConstant() {
        com.nforce.onehr.entity.Shift nineHourShift = com.nforce.onehr.entity.Shift.builder().id(UUID.randomUUID()).name("Regular").build();
        Employee emp1WithShift = Employee.builder().userId(emp1Id).fullName("Employee One").employeeCode("NF-1").shift(nineHourShift).build();
        when(employeeRepository.findAllByIdWithScheduleDetails(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1WithShift, emp2));
        when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                emp1Id, WorkingDaySchedule.builder().employeeUserId(emp1Id).workingDates(Set.of(day1, day2)).build(),
                emp2Id, WorkingDaySchedule.builder().employeeUserId(emp2Id).workingDates(Set.of(day1, day2)).build()));
        when(expectedWorkHoursService.adjustedExpectedMinutes(eq(emp1WithShift), any(), any())).thenReturn(540L);
        List<Attendance> records = List.of(
                record(UUID.randomUUID(), emp1Id, day1, 480, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day1, 480, "PRESENT"));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), day1, day2))
                .thenReturn(records);

        List<TeamEffortEntry> result = attendanceService.getTeamEffort(managerEmail, day1, day2);

        TeamEffortEntry emp1Entry = result.stream().filter(e -> e.getEmployeeUserId().equals(emp1Id)).findFirst().orElseThrow();
        TeamEffortEntry emp2Entry = result.stream().filter(e -> e.getEmployeeUserId().equals(emp2Id)).findFirst().orElseThrow();
        // 2 working days * 9h shift = 18h — NOT the flat 2*8=16h a hardcoded constant would give.
        assertEquals(18.0, emp1Entry.getExpectedHours(), 0.01);
        // emp2 still has no shift on file -> falls back to the flat 8h/day estimate.
        assertEquals(16.0, emp2Entry.getExpectedHours(), 0.01);
    }

    @Test
    void getTeamEffort_returnsEmpty_whenManagerHasNoDirectReports() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of());

        List<TeamEffortEntry> result = attendanceService.getTeamEffort(managerEmail, day1, day2);

        assertTrue(result.isEmpty());
    }

    @Test
    void getTeamNegligence_lateArrivals_ranksDescByLatePct() {
        // emp1 is late 1/2 days (50%); emp2 is never late (0%) — emp1 should rank first.
        List<Attendance> records = List.of(
                record(UUID.randomUUID(), emp1Id, day1, 480, "LATE"),
                record(UUID.randomUUID(), emp1Id, day2, 420, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day1, 500, "PRESENT"),
                record(UUID.randomUUID(), emp2Id, day2, 500, "PRESENT"));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), day1, day2))
                .thenReturn(records);
        when(attendancePunchRepository.findByAttendanceRecordIdInOrderByCheckInAtAsc(any())).thenReturn(List.of());

        TeamNegligenceResponse result = attendanceService.getTeamNegligence(managerEmail, day1, day2);

        assertEquals(2, result.getLateArrivals().size());
        assertEquals(emp1Id, result.getLateArrivals().get(0).getEmployeeUserId());
        assertEquals(50.0, result.getLateArrivals().get(0).getLatePct(), 0.01);
        assertEquals(0.0, result.getLateArrivals().get(1).getLatePct(), 0.01);
    }

    @Test
    void getTeamNegligence_frequentBreaks_excludesEmployeesWithNoBreaks() {
        UUID recordWithBreak = UUID.randomUUID();
        UUID recordWithoutBreak = UUID.randomUUID();
        List<Attendance> records = List.of(
                record(recordWithBreak, emp1Id, day1, 480, "PRESENT"),
                record(recordWithoutBreak, emp2Id, day1, 500, "PRESENT"));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), day1, day2))
                .thenReturn(records);

        // emp1: two sessions the same day (a lunch break); emp2: a single unbroken session.
        List<AttendancePunch> punches = List.of(
                AttendancePunch.builder().id(UUID.randomUUID()).attendanceRecordId(recordWithBreak)
                        .checkInAt(day1.atTime(9, 0)).checkOutAt(day1.atTime(12, 0)).build(),
                AttendancePunch.builder().id(UUID.randomUUID()).attendanceRecordId(recordWithBreak)
                        .checkInAt(day1.atTime(13, 0)).checkOutAt(day1.atTime(17, 0)).build(),
                AttendancePunch.builder().id(UUID.randomUUID()).attendanceRecordId(recordWithoutBreak)
                        .checkInAt(day1.atTime(9, 0)).checkOutAt(day1.atTime(17, 0)).build());
        when(attendancePunchRepository.findByAttendanceRecordIdInOrderByCheckInAtAsc(any())).thenReturn(punches);

        TeamNegligenceResponse result = attendanceService.getTeamNegligence(managerEmail, day1, day2);

        assertEquals(1, result.getFrequentBreaks().size());
        assertEquals(emp1Id, result.getFrequentBreaks().get(0).getEmployeeUserId());
        assertEquals(1, result.getFrequentBreaks().get(0).getTotalBreakCount());
        assertEquals(1.0, result.getFrequentBreaks().get(0).getTotalBreakHours(), 0.01);
    }
}
