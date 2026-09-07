package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceStatsResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Section 39/45 — the "me vs my team" Attendance Summary must show the same adjusted expected hours the Penalization Policy engine uses, not a second independently-derived figure. */
@ExtendWith(MockitoExtension.class)
class AttendanceStatsServiceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private WorkingDayService workingDayService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;

    private AttendanceStatsService service;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final LocalDate day1 = LocalDate.of(2026, 8, 3);
    private final LocalDate day2 = LocalDate.of(2026, 8, 4);
    // Timing is irrelevant here — AttendanceStatsService never reads it, only whether a shift is
    // assigned at all (expectedWorkHoursService, mocked below, supplies the actual figures).
    private final Shift nineHourShift = Shift.builder().id(UUID.randomUUID()).name("Regular").build();

    @BeforeEach
    void setUp() {
        service = new AttendanceStatsService(attendanceRepository, managerHistoryRepository, employeeRepository,
                workingDayService, expectedWorkHoursService);
    }

    private Employee employeeWithShift() {
        return Employee.builder().userId(employeeId).fullName("Test Employee").shift(nineHourShift).build();
    }

    @Test
    void getStats_expectedHoursPerDay_reflectsRealShiftDuration_forMeBucket() {
        Employee employee = employeeWithShift();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        lenient().when(managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), day1, day2))
                .thenReturn(List.of(Attendance.builder().employeeUserId(employeeId).workDate(day1)
                        .checkInAt(day1.atTime(9, 0)).checkOutAt(day1.atTime(18, 0)).workedMinutes(540).lateByMinutes(0).build()));
        when(employeeRepository.findAllByIdWithScheduleDetails(List.of(employeeId))).thenReturn(List.of(employee));
        when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                employeeId, WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(day1, day2)).build()));
        when(expectedWorkHoursService.adjustedExpectedMinutes(any(), any(), any())).thenReturn(540L);

        AttendanceStatsResponse response = service.getStats(employeeEmail, day1, day2);

        // 2 working days * 9h shift = 9h/day average — not any flat/hardcoded constant.
        assertEquals(9.0, response.getMe().getExpectedHoursPerDay(), 0.01);
        assertEquals(0, response.getTeamSize());
        assertNull(response.getTeam().getExpectedHoursPerDay(), "no peers -> nothing to average");
    }

    @Test
    void getStats_expectedHoursPerDay_isNull_whenNoWorkingDaysInRange() {
        Employee employee = employeeWithShift();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        lenient().when(managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), day1, day2)).thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(List.of(employeeId))).thenReturn(List.of(employee));
        when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                employeeId, WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of()).build()));

        AttendanceStatsResponse response = service.getStats(employeeEmail, day1, day2);

        assertNull(response.getMe().getExpectedHoursPerDay());
    }

    @Test
    void getStats_expectedHoursPerDay_includesPeers_forTeamBucket() {
        UUID peerId = UUID.randomUUID();
        Employee employee = employeeWithShift();
        Employee peer = Employee.builder().userId(peerId).fullName("Peer").shift(nineHourShift).build();
        UUID managerId = UUID.randomUUID();

        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        when(managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(employeeId, peerId));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), day1, day2)).thenReturn(List.of());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(peerId), day1, day2)).thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(List.of(employeeId, peerId))).thenReturn(List.of(employee, peer));
        when(workingDayService.computeExpectedWorkingDaysBulk(any(), any(), any())).thenReturn(Map.of(
                employeeId, WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(day1)).build(),
                peerId, WorkingDaySchedule.builder().employeeUserId(peerId).workingDates(Set.of(day1)).build()));
        when(expectedWorkHoursService.adjustedExpectedMinutes(any(), any(), any())).thenReturn(540L);

        AttendanceStatsResponse response = service.getStats(employeeEmail, day1, day2);

        assertEquals(1, response.getTeamSize());
        assertEquals(9.0, response.getTeam().getExpectedHoursPerDay(), 0.01);
    }
}
