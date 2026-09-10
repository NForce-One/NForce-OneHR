package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests, following the same isolation pattern as
 * LeaveServiceTest — no @SpringBootTest/H2 (the H2 test profile can't create
 * schema for citext-typed entities).
 */
@ExtendWith(MockitoExtension.class)
class ExceptionServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private AttendancePenaltyEvaluationService attendancePenaltyEvaluationService;
    @Mock private EmailService emailService;
    @Mock private WorkingDayService workingDayService;
    @Mock private HolidayRepository holidayRepository;
    @Mock private PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;
    @Mock private WorkHoursShortageCalculationService workHoursShortageCalculationService;
    @Mock private AttendancePolicyEngine attendancePolicyEngine;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private ShiftRepository shiftRepository;

    private ExceptionService exceptionService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String hrEmail = "hr@test.com";

    // Zone-matched to AttendanceProperties.getZone() ("Asia/Kolkata", stubbed below) — the service
    // computes "today" against that zone, not the JVM's default zone; using a plain LocalDate.now()
    // here made this test's "today" and the service's "today" disagree (and openToday/missingPunch
    // assertions flip) whenever the machine's local date and Kolkata's calendar date differ, e.g.
    // late evening in an zone west of Kolkata.
    private final LocalDate to = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
    private final LocalDate from = to.minusDays(6);

    @BeforeEach
    void setUp() {
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        exceptionService = new ExceptionService(userRepository, employeeRepository, historyRepository,
                attendanceExceptionRepository, attendanceRepository, leaveRequestRepository,
                regularizationRequestRepository, attendanceProperties, emailService, attendancePenaltyEvaluationService,
                workingDayService, holidayRepository, penalizationPolicyResolutionService, expectedWorkHoursService,
                workHoursShortageCalculationService, attendancePolicyEngine, attendancePenaltyRepository,
                attendancePenaltyService, shiftVersionResolver, shiftRepository);
        // Default: employeeId is the only account holding the EMPLOYEE role — matches
        // EmployeeService.listEmployees()'s own definition of who counts as an employee.
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId));
        // Default: the LATE_ARRIVAL/WORK_HOURS_SHORTAGE display paths resolve the record's own
        // snapshotted shiftId (resolveSnapshotShift) — stubWorkingDays() below gives that
        // employee a real Shift with the same id, so this just needs to resolve to *some*
        // ShiftVersion rather than throw. The exact start/end values don't matter to any
        // assertion here.
        lenient().when(shiftRepository.findById(any())).thenAnswer(inv -> Optional.of(Shift.builder().id(inv.getArgument(0)).build()));
        lenient().when(shiftVersionResolver.resolve(any(), any()))
                .thenReturn(ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
        lenient().when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
    }

    /**
     * Section 09/Gap-007: LATE_ARRIVAL and MISSING_PUNCH are now gated by
     * {@code WorkingDayService}'s working-day set, same as NO_ATTENDANCE/WORK_HOURS_SHORTAGE
     * already were. Call this from a test that needs {@code employeeId} to count as scheduled to
     * work on {@code workDates} — scoped per-test (not a class-wide default) since
     * {@code findAllByIdWithScheduleDetails} returning a non-empty list also feeds
     * {@code detectNoAttendanceAndShortage}'s own, unrelated NO_ATTENDANCE detection.
     */
    private void stubWorkingDays(LocalDate... workDates) {
        Shift shift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(employeeRepository.findAllByIdWithScheduleDetails(any()))
                .thenReturn(List.of(Employee.builder().userId(employeeId).shift(shift).build()));
        when(workingDayService.computeExpectedWorkingDaysBulk(anyList(), any(), any()))
                .thenReturn(java.util.Map.of(employeeId, com.nforce.onehr.dto.attendance.WorkingDaySchedule.builder()
                        .employeeUserId(employeeId).workingDates(Set.of(workDates)).build()));
    }

    private User userWithRole(String email, String roleCode, UUID id) {
        Role role = Role.builder().code(roleCode).build();
        return User.builder().id(id).email(email).roles(Set.of(role)).build();
    }

    @Test
    void hrAdmin_scopeIsExactlyEmployeeRoleAccounts() {
        UUID otherEmployeeId = UUID.randomUUID();
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));
        when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId, otherEmployeeId));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(anyList(), eq(from), eq(to)))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(
                argThat(ids -> ids.containsAll(List.of(employeeId, otherEmployeeId)) && ids.size() == 2),
                eq(from), eq(to));
    }

    @Test
    void manager_isScopedToDirectReports() {
        User manager = userWithRole(managerEmail, "MANAGER", managerId);
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(manager));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId))
                .thenReturn(List.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(managerEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);
    }

    @Test
    void manager_excludesDirectReportWhoIsNotAnEmployeeRoleAccount() {
        UUID managerDirectReportId = UUID.randomUUID(); // e.g. a matrixed sub-manager
        User manager = userWithRole(managerEmail, "MANAGER", managerId);
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(manager));
        // employeeRoleIds (from setUp) contains only employeeId, not managerDirectReportId
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId)).thenReturn(List.of(
                EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build(),
                EmployeeManagerHistory.builder().employeeUserId(managerDirectReportId).managerUserId(managerId).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(managerEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);
        verify(attendanceRepository, never()).findByEmployeeUserIdInAndWorkDateBetween(
                argThat(ids -> ids.contains(managerDirectReportId)), any(), any());
    }

    @Test
    void employeeWithNoRole_isDenied() {
        User plain = userWithRole(employeeEmail, "EMPLOYEE", employeeId);
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(plain));

        assertThrows(AccessDeniedException.class,
                () -> exceptionService.getExceptionsForCaller(employeeEmail, from, to));
    }

    @Test
    void lateArrival_isDetectedFromRealAttendanceData() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance lateRecord = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(10).withMinute(0))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .status("LATE").lateByMinutes(15)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        stubWorkingDays(lateRecord.getWorkDate());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LATE_ARRIVAL)
                        && exc.getMinutesLate().equals(15)));
    }

    @Test
    void lateArrival_suppressedOnAHolidayOrWeekOff_gap007() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance lateRecordOnWeekOff = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(10).withMinute(0))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(15)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(lateRecordOnWeekOff));
        // Deliberately NOT included in the stubbed working-day set — this employee wasn't
        // scheduled to work this date at all (weekly off/holiday), so a punch that happens to
        // read as "late" against normal shift-start must not become a penalty candidate.
        stubWorkingDays();

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, never()).save(argThat(exc -> exc.getExceptionType().equals(ExceptionType.LATE_ARRIVAL)));
    }

    /** Section 3: MISSING_PUNCH shares the exact same isWorkingDay gate LATE_ARRIVAL does — a
     * forgotten checkout on a day nobody was expected to work (holiday or weekly off) must never
     * become a penalty candidate either. */
    @Test
    void missingPunch_suppressedOnAHolidayOrWeekOff() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance missingPunchOnWeekOff = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(to.minusDays(1))
                .checkInAt(to.minusDays(1).atTime(9, 30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(missingPunchOnWeekOff));
        // Deliberately NOT included in the stubbed working-day set — same gap007 reasoning as
        // lateArrival_suppressedOnAHolidayOrWeekOff_gap007 above.
        stubWorkingDays();

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, never()).save(argThat(exc -> exc.getExceptionType().equals(ExceptionType.MISSING_PUNCH)));
    }

    @Test
    void missingPunch_isDetectedForPastDayWithNoCheckout_butNotForToday() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance missingPunchYesterday = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(to.minusDays(1))
                .checkInAt(to.minusDays(1).atTime(9, 30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        Attendance openToday = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(to)
                .checkInAt(to.atTime(9, 30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(missingPunchYesterday, openToday));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, missingPunchYesterday.getWorkDate(), ExceptionType.MISSING_PUNCH)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        stubWorkingDays(missingPunchYesterday.getWorkDate());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(1)).save(any(AttendanceException.class));
        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.MISSING_PUNCH)
                        && exc.getExceptionDate().equals(missingPunchYesterday.getWorkDate())));
    }

    @Test
    void detection_upsertsExistingExceptionInstead_ofDuplicating() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance lateRecord = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(10).withMinute(0))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .status("LATE").lateByMinutes(30)
                .build();
        AttendanceException existing = AttendanceException.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .exceptionDate(lateRecord.getWorkDate())
                .exceptionType(ExceptionType.LATE_ARRIVAL)
                .minutesLate(10)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.of(existing));
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        stubWorkingDays(lateRecord.getWorkDate());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(1)).save(argThat(exc ->
                exc.getId().equals(existing.getId()) && exc.getMinutesLate().equals(30)));
    }

    @Test
    void leaveAttendanceConflict_isDetectedWhenApprovedLeaveOverlapsAnAttendanceRecord() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate conflictDate = LocalDate.now().minusDays(1);
        Attendance recordOnLeaveDay = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(conflictDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(conflictDate)
                .endDate(conflictDate)
                .status("APPROVED")
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOnLeaveDay));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, conflictDate, ExceptionType.LEAVE_ATTENDANCE_CONFLICT)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)
                        && exc.getExceptionDate().equals(conflictDate)));
    }

    @Test
    void leaveAttendanceConflict_notRaised_whenLeaveDoesNotCoverTheCheckInDay() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate checkInDate = LocalDate.now().minusDays(1);
        LocalDate leaveDate = LocalDate.now().minusDays(3); // does not overlap checkInDate
        Attendance recordOutsideLeave = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(checkInDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(leaveDate)
                .endDate(leaveDate)
                .status("APPROVED")
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOutsideLeave));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, never()).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)));
    }

    @Test
    void leaveAttendanceConflict_reDetection_emailsOnlyOnce() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate conflictDate = LocalDate.now().minusDays(1);
        Attendance recordOnLeaveDay = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(conflictDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(conflictDate)
                .endDate(conflictDate)
                .status("APPROVED")
                .build();
        AttendanceException existing = AttendanceException.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .exceptionDate(conflictDate)
                .exceptionType(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOnLeaveDay));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, conflictDate, ExceptionType.LEAVE_ATTENDANCE_CONFLICT)).thenReturn(Optional.of(existing));
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);
        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(2)).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)));
    }

    // ── Gap-033: reevaluateAndReverseIfInvalid must reverse ONLY the invalidated type ──────────

    @Test
    void reevaluateAndReverseIfInvalid_correctionFixesLateness_shortagePenaltyOfDifferentTypePreserved() {
        LocalDate date = to;
        UUID actorId = UUID.randomUUID();
        AttendancePenalty latePenalty = AttendancePenalty.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).incidentDate(date).discrepancyType(ExceptionType.LATE_ARRIVAL)
                .status(AttendancePenaltyStatus.PENDING_REVIEW).build();
        AttendancePenalty shortagePenalty = AttendancePenalty.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).incidentDate(date).discrepancyType(ExceptionType.WORK_HOURS_SHORTAGE)
                .status(AttendancePenaltyStatus.PENDING_REVIEW).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDate(employeeId, date))
                .thenReturn(List.of(latePenalty, shortagePenalty));
        Employee employee = Employee.builder().userId(employeeId).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(workingDayService.computeExpectedWorkingDays(employee, date, date))
                .thenReturn(com.nforce.onehr.dto.attendance.WorkingDaySchedule.builder()
                        .employeeUserId(employeeId).workingDates(Set.of(date)).build());
        // Corrected by the regularization: no longer late, but still short on total hours.
        Attendance corrected = Attendance.builder().employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(14, 0))
                .workedMinutes(270).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.of(corrected));
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder().id(UUID.randomUUID()).build();
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, date)).thenReturn(version);
        when(expectedWorkHoursService.adjustedExpectedMinutes(employee, date)).thenReturn(480L); // 270 < 480 — still short
        when(attendancePolicyEngine.evaluate(any())).thenReturn(
                com.nforce.onehr.dto.attendance.PolicyDecision.builder()
                        .type(com.nforce.onehr.dto.attendance.PolicyDecisionType.APPLY_PENALTY).build());

        exceptionService.reevaluateAndReverseIfInvalid(employeeId, date, ExceptionService.REGULARIZATION_REEVALUATION_TYPES,
                actorId, "Attendance corrected", "ATTENDANCE_PENALTY_REVERSED");

        verify(attendancePenaltyService).reverseIfActive(latePenalty.getId(), actorId, "Attendance corrected", "ATTENDANCE_PENALTY_REVERSED");
        verify(attendancePenaltyService, never()).reverseIfActive(eq(shortagePenalty.getId()), any(), any(), any());
    }

    @Test
    void reevaluateAndReverseIfInvalid_dateNoLongerAWorkingDay_reversesEveryCandidateType() {
        // Simulates a full/half-day leave approved AFTER a penalty already exists: the date drops
        // out of the working-day schedule entirely, so every candidate type must be reversed
        // regardless of its own per-type fact check.
        LocalDate date = to;
        UUID actorId = UUID.randomUUID();
        AttendancePenalty shortagePenalty = AttendancePenalty.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).incidentDate(date).discrepancyType(ExceptionType.WORK_HOURS_SHORTAGE)
                .status(AttendancePenaltyStatus.PENDING_REVIEW).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDate(employeeId, date))
                .thenReturn(List.of(shortagePenalty));
        Employee employee = Employee.builder().userId(employeeId).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        // Empty working-dates set — the newly-approved leave removed this date entirely.
        when(workingDayService.computeExpectedWorkingDays(employee, date, date))
                .thenReturn(com.nforce.onehr.dto.attendance.WorkingDaySchedule.builder()
                        .employeeUserId(employeeId).workingDates(Set.of()).build());

        exceptionService.reevaluateAndReverseIfInvalid(employeeId, date, ExceptionService.LEAVE_REEVALUATION_TYPES,
                actorId, "Leave approved", "ATTENDANCE_PENALTY_REVERSED");

        verify(attendancePenaltyService).reverseIfActive(shortagePenalty.getId(), actorId, "Leave approved", "ATTENDANCE_PENALTY_REVERSED");
        // The day being non-working is decisive on its own — the engine/per-type fact check must
        // never even need to run.
        verifyNoInteractions(attendancePolicyEngine);
    }
}
