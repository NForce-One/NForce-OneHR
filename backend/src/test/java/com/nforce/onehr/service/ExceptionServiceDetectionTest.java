package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gap-fix coverage for Section 10/18 (NO_ATTENDANCE and WORK_HOURS_SHORTAGE were "reserved" —
 * never detected in production — until this change) and Section 34 (weekly vs monthly exempt
 * cycles). Same "real objects for every layer, only repositories/EmailService mocked" convention
 * as {@link PenalizationPolicyProductionFlowTest}.
 */
@ExtendWith(MockitoExtension.class)
class ExceptionServiceDetectionTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private EmailService emailService;
    @Mock private HolidayRepository holidayRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private EmployeeService employeeService;
    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;

    private ExceptionService exceptionService;

    // A minimal, real (not mocked) Shift Version resolver shared by every real (non-mocked)
    // service this test constructs (ExpectedWorkHoursService, WorkHoursShortageCalculationService,
    // ShiftDayPolicy, ExceptionService itself) — single-version-per-shift, in-memory "latest
    // effectiveFrom <= day" lookup, mirroring ShiftVersionRepository's own query semantics. See
    // shift() below.
    private final List<ShiftVersion> shiftVersions = new java.util.ArrayList<>();
    private final ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
        @Override
        public ShiftVersion resolve(Shift s, LocalDate workDate) {
            return shiftVersions.stream()
                    .filter(v -> v.getShift().getId().equals(s.getId()))
                    .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(ShiftVersion::getEffectiveFrom))
                    .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
        }
    };

    /** Builds a Shift (with a real id) and registers a single version effective from the dawn of time — none of these tests exercise Shift Versioning itself. */
    private Shift shift(String name, LocalTime start, LocalTime end) {
        Shift s = Shift.builder().id(UUID.randomUUID()).name(name).build();
        shiftVersions.add(ShiftVersion.builder().shift(s).startTime(start).endTime(end).effectiveFrom(LocalDate.MIN).build());
        return s;
    }

    private final UUID employeeId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");
    /** A weekday strictly before "today", so it's never excluded by weekly-off or the
     * never-evaluate-today guard. */
    private final LocalDate targetDate = priorWeekday();

    private static LocalDate priorWeekday() {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(6);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    @BeforeEach
    void setUp() {
        AttendancePolicyEngine policyEngine = new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
        AuditSnapshotSerializer snapshotSerializer = new AuditSnapshotSerializer(new ObjectMapper());
        PenaltyDeductionService penaltyDeductionService = new PenaltyDeductionService(leaveTypeRepository, leaveBalanceRepository, snapshotSerializer);
        AttendancePenaltyEvaluationService penaltyEvaluationService =
                new AttendancePenaltyEvaluationService(policyEngine, attendancePenaltyRepository, penaltyDeductionService,
                        notificationService, employeeService, auditService, snapshotSerializer);
        WorkingDayService workingDayService = new WorkingDayService(holidayRepository, leaveRequestRepository);
        PenalizationPolicyService penalizationPolicyService = new PenalizationPolicyService(versionRepository, tierRepository,
                lateHoursTierRepository, penalisationPolicyRepository, userRepository, auditService, snapshotSerializer,
                attendanceProperties, employeeRepository, notificationService);
        lenient().when(allocationRepository.findEffectiveAt(any(), any())).thenReturn(List.of());
        PenalizationPolicyResolutionService policyResolutionService =
                new PenalizationPolicyResolutionService(versionRepository, allocationRepository, penalizationPolicyService, employeeRepository, attendanceProperties);
        ExpectedWorkHoursService expectedWorkHoursService = new ExpectedWorkHoursService(leaveRequestRepository, shiftVersionResolver);
        WorkHoursShortageCalculationService workHoursShortageCalculationService =
                new WorkHoursShortageCalculationService(attendanceRepository, expectedWorkHoursService, workingDayService, shiftVersionResolver);
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(java.math.BigDecimal.valueOf(18)).build()));
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver);
        exceptionService = new ExceptionService(userRepository, employeeRepository, historyRepository,
                attendanceExceptionRepository, attendanceRepository, leaveRequestRepository,
                regularizationRequestRepository, attendanceProperties, emailService, penaltyEvaluationService,
                workingDayService, holidayRepository, policyResolutionService, expectedWorkHoursService,
                workHoursShortageCalculationService, policyEngine, attendancePenaltyRepository, attendancePenaltyService,
                shiftDayPolicy, shiftVersionResolver);

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId));
        lenient().when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser()));
        lenient().when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(false);
        lenient().when(attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(attendancePenaltyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(null)));
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    private Employee employee(Shift shift) {
        User user = User.builder().id(employeeId).email("employee@test.com").build();
        return Employee.builder().userId(employeeId).user(user).employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(targetDate.minusYears(1)).shift(shift).build();
    }

    private PenalizationPolicyVersion noAttendanceVersion() {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1")).build();
    }

    // ── NO_ATTENDANCE: previously "reserved"/undetectable — no Attendance row at all ──
    @Test
    void expectedWorkingDayWithNoAttendanceRow_detectsNoAttendance_appliesPenalty() {
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(null)));
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(noAttendanceVersion()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository).save(captor.capture());
        assertEquals(ExceptionType.NO_ATTENDANCE, captor.getValue().getDiscrepancyType());
        assertEquals(employeeId, captor.getValue().getEmployeeUserId());
        assertEquals(targetDate, captor.getValue().getIncidentDate());
    }

    @Test
    void noAttendanceSectionDisabled_noPenaltyForMissingDay() {
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(null)));
        PenalizationPolicyVersion disabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay()).noAttendanceEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(disabled));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    // ── WORK_HOURS_SHORTAGE: previously "reserved"/undetectable — a completed day short of the
    // assigned shift's duration ──
    @Test
    void completedDayShortOfShiftDuration_detectsWorkHoursShortage_appliesPenalty() {
        Shift shift = shift("Regular", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Attendance shortDay = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(targetDate.atTime(13, 0))
                .workedMinutes(240).lateByMinutes(0).build(); // 4h worked against a 9h shift
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(shortDay));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, targetDate)).thenReturn(Optional.of(shortDay));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(shift)));
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("0.5")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository).save(captor.capture());
        assertEquals(ExceptionType.WORK_HOURS_SHORTAGE, captor.getValue().getDiscrepancyType());
        assertEquals(new java.math.BigDecimal("0.5"), captor.getValue().getDeductionDays());
    }

    @Test
    void completedDayMeetingFullShiftDuration_noShortageDetected() {
        Shift shift = shift("Regular", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Attendance fullDay = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(targetDate.atTime(18, 0))
                .workedMinutes(540).lateByMinutes(0).build(); // exactly 9h — no shortfall
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(fullDay));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    // ── Weekly cycle (Section 34): exempt-count window follows the configured cycle, not always
    // the calendar month ──
    @Test
    void missingLogsWeeklyCycle_countsWithinMondayToSundayWindow_notCalendarMonth() {
        Attendance missingOut = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 30)).checkOutAt(null).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(missingOut));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(null)));
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .missingLogsEnabled(true).mlExemptDays(5).mlExemptPeriod("WEEK").build();
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(attendanceExceptionRepository).countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                org.mockito.ArgumentMatchers.eq(employeeId), org.mockito.ArgumentMatchers.eq(ExceptionType.MISSING_PUNCH),
                fromCaptor.capture(), toCaptor.capture());

        LocalDate expectedMonday = targetDate.minusDays(targetDate.getDayOfWeek().getValue() - 1L);
        assertEquals(expectedMonday, fromCaptor.getValue());
        assertEquals(expectedMonday.plusDays(6), toCaptor.getValue());
        // Never the calendar-month window this policy would have used before WEEK support existed.
        assertNotEquals(targetDate.withDayOfMonth(1), fromCaptor.getValue());
    }

    // ── Adjoining-holiday sandwich rule (Section 12) — previously not implemented anywhere ──
    @Test
    void holidaySandwichedBetweenNoAttendanceDays_holidayItselfBecomesNoAttendance() {
        LocalDate holidayDate = priorMidWeekday();
        LocalDate before = holidayDate.minusDays(1);
        LocalDate after = holidayDate.plusDays(1);
        Location location = Location.builder().id(UUID.randomUUID()).name("HQ").build();
        Employee employeeAtLocation = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(holidayDate.minusYears(1)).location(location).build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), before, after))
                .thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeAtLocation));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeAtLocation));
        when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of(
                Holiday.builder().id(UUID.randomUUID()).holidayName("Test Holiday")
                        .holidayDate(holidayDate).location(location).active(true).build()));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(holidayDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningHolidayEnabled(true).naAdjoiningHolidayCondition("SANDWICHED")
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, before, after);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(p -> p.getIncidentDate().equals(holidayDate) && p.getDiscrepancyType().equals(ExceptionType.NO_ATTENDANCE)),
                "the holiday date itself must also be penalised as NO_ATTENDANCE");
    }

    @Test
    void holidayNotSandwiched_adjoiningRuleDoesNotFire() {
        LocalDate holidayDate = priorMidWeekday();
        LocalDate before = holidayDate.minusDays(1);
        LocalDate after = holidayDate.plusDays(1);
        Location location = Location.builder().id(UUID.randomUUID()).name("HQ").build();
        Employee employeeAtLocation = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(holidayDate.minusYears(1)).location(location).build();

        // Attended normally the day after the holiday — only "before" is a no-attendance day.
        Attendance attendedAfter = Attendance.builder().employeeUserId(employeeId).workDate(after)
                .checkInAt(after.atTime(9, 30)).checkOutAt(after.atTime(18, 0)).workedMinutes(510).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), before, after))
                .thenReturn(List.of(attendedAfter));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeAtLocation));
        when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of(
                Holiday.builder().id(UUID.randomUUID()).holidayName("Test Holiday")
                        .holidayDate(holidayDate).location(location).active(true).build()));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(holidayDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningHolidayEnabled(true).naAdjoiningHolidayCondition("SANDWICHED")
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, before, after);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals(before, captor.getValue().getIncidentDate(), "only the unattended 'before' day is penalised");
        assertNotEquals(holidayDate, captor.getValue().getIncidentDate());
    }

    // ── Condition must genuinely branch, not just happen to match SANDWICHED ──
    @Test
    void holidayBeforeConditionOnly_triggersEvenWithoutAnAfterNoAttendanceDay() {
        LocalDate holidayDate = priorMidWeekday();
        LocalDate before = holidayDate.minusDays(1);
        LocalDate after = holidayDate.plusDays(1);
        Location location = Location.builder().id(UUID.randomUUID()).name("HQ").build();
        Employee employeeAtLocation = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(holidayDate.minusYears(1)).location(location).build();

        // Same fixture as holidayNotSandwiched_adjoiningRuleDoesNotFire (attended the day after) —
        // but with condition=BEFORE this time it must still trigger, proving the condition switch
        // actually branches rather than the SANDWICHED test coincidentally passing.
        Attendance attendedAfter = Attendance.builder().employeeUserId(employeeId).workDate(after)
                .checkInAt(after.atTime(9, 30)).checkOutAt(after.atTime(18, 0)).workedMinutes(510).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), before, after))
                .thenReturn(List.of(attendedAfter));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeAtLocation));
        when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of(
                Holiday.builder().id(UUID.randomUUID()).holidayName("Test Holiday")
                        .holidayDate(holidayDate).location(location).active(true).build()));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(holidayDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningHolidayEnabled(true).naAdjoiningHolidayCondition("BEFORE")
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, before, after);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(holidayDate)),
                "BEFORE condition penalises the holiday even though the 'after' day was attended");
    }

    // ── Week-off must use the employee's actual assigned WeeklyOffPolicy, never assume Sat/Sun ──
    @Test
    void nonStandardWeekOff_beforeCondition_usesAssignedPolicy_notSaturdaySunday() {
        // Friday/Saturday week-off (not the default Saturday/Sunday) — pick a Friday that's also
        // strictly before "today" so detection isn't skipped, and whose Thursday is a real workday.
        LocalDate friday = priorMidWeekday();
        while (friday.getDayOfWeek() != DayOfWeek.FRIDAY) friday = friday.minusDays(1);
        LocalDate thursday = friday.minusDays(1);

        WeeklyOffPolicy fridaySaturdayOff = WeeklyOffPolicy.builder().id(UUID.randomUUID())
                .name("Fri-Sat Off").offDays("FRIDAY,SATURDAY").build();
        Employee employeeWithCustomWeekOff = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(friday.minusYears(1)).weeklyOffPolicy(fridaySaturdayOff).build();

        // No attendance Thursday (the day before the Friday week-off); no attendance rows at all
        // in range — a completely unattended stretch.
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, friday))
                .thenReturn(List.of());
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeWithCustomWeekOff));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(friday.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningWeekoffEnabled(true).naAdjoiningWeekoffCondition("BEFORE")
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, friday);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        LocalDate fridayFinal = friday;
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(fridayFinal)),
                "Friday (the employee's own configured week-off, not Saturday/Sunday) must be penalised too");
    }

    // ── Week-off AFTER / ANY conditions (Section 13) — previously only BEFORE/SANDWICHED were
    // directly exercised for week-offs. Fixed, non-"now"-dependent dates: a single FRIDAY-only
    // off day isolates AFTER's semantics cleanly (the day right after the week-off is an
    // ordinary working day, not itself another day off), unlike the two-consecutive-day
    // Fri+Sat fixture used above for BEFORE. ──
    private static final LocalDate WEEKOFF_FRIDAY = fixedWeekday(LocalDate.of(2024, 3, 1), DayOfWeek.FRIDAY);

    private static LocalDate fixedWeekday(LocalDate anchor, DayOfWeek target) {
        LocalDate d = anchor;
        while (d.getDayOfWeek() != target) {
            d = d.plusDays(1);
        }
        return d;
    }

    private Employee employeeWithFridayOff(LocalDate friday) {
        WeeklyOffPolicy fridayOff = WeeklyOffPolicy.builder().id(UUID.randomUUID())
                .name("Friday Off").offDays("FRIDAY").build();
        return Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(friday.minusYears(1)).weeklyOffPolicy(fridayOff).build();
    }

    private PenalizationPolicyVersion weekoffVersion(LocalDate friday, String condition) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(friday.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningWeekoffEnabled(true).naAdjoiningWeekoffCondition(condition)
                .build();
    }

    private Attendance attendedDay(LocalDate date) {
        return Attendance.builder().employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(18, 0))
                .workedMinutes(510).lateByMinutes(0).build();
    }

    @Test
    void weekOffAfterCondition_noAttendanceImmediatelyAfter_triggersPenalty() {
        LocalDate friday = WEEKOFF_FRIDAY;
        LocalDate thursday = friday.minusDays(1); // attended — must not matter for AFTER
        LocalDate saturday = friday.plusDays(1);  // no attendance — the day AFTER the week-off
        Employee employee = employeeWithFridayOff(friday);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, saturday))
                .thenReturn(List.of(attendedDay(thursday)));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(weekoffVersion(friday, "AFTER")));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, saturday);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(friday)),
                "AFTER condition must penalise the week-off itself when the day right after it is unattended");
    }

    @Test
    void weekOffAfterCondition_dayAfterIsAttended_doesNotTrigger() {
        LocalDate friday = WEEKOFF_FRIDAY;
        LocalDate thursday = friday.minusDays(1);
        LocalDate saturday = friday.plusDays(1); // attended — AFTER must not fire
        Employee employee = employeeWithFridayOff(friday);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, saturday))
                .thenReturn(List.of(attendedDay(thursday), attendedDay(saturday)));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(weekoffVersion(friday, "AFTER")));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, saturday);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void weekOffAnyCondition_noAttendanceBeforeOnly_triggers() {
        LocalDate friday = WEEKOFF_FRIDAY;
        LocalDate thursday = friday.minusDays(1); // no attendance — "before" only
        LocalDate saturday = friday.plusDays(1);  // attended
        Employee employee = employeeWithFridayOff(friday);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, saturday))
                .thenReturn(List.of(attendedDay(saturday)));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(weekoffVersion(friday, "ANY")));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, saturday);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(friday)),
                "ANY must trigger when only the day before the week-off is unattended");
    }

    @Test
    void weekOffAnyCondition_noAttendanceAfterOnly_triggers() {
        LocalDate friday = WEEKOFF_FRIDAY;
        LocalDate thursday = friday.minusDays(1); // attended
        LocalDate saturday = friday.plusDays(1);  // no attendance — "after" only
        Employee employee = employeeWithFridayOff(friday);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, saturday))
                .thenReturn(List.of(attendedDay(thursday)));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(weekoffVersion(friday, "ANY")));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, saturday);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(friday)),
                "ANY must trigger when only the day after the week-off is unattended");
    }

    @Test
    void weekOffAnyCondition_neitherSideAdjacent_doesNotTrigger() {
        LocalDate friday = WEEKOFF_FRIDAY;
        LocalDate thursday = friday.minusDays(1); // attended
        LocalDate saturday = friday.plusDays(1);  // attended
        Employee employee = employeeWithFridayOff(friday);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), thursday, saturday))
                .thenReturn(List.of(attendedDay(thursday), attendedDay(saturday)));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(weekoffVersion(friday, "ANY")));

        exceptionService.getExceptionsForCaller(hrEmail, thursday, saturday);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    // ── Half-day leave (Section 14): must be genuinely configurable, not hardcoded either way ──
    @Test
    void halfDayLeaveBeforeHoliday_ignoreHalfDay_true_breaksTheChain_holidayNotPenalised() {
        LocalDate holidayDate = priorMidWeekday();
        LocalDate before = holidayDate.minusDays(1);
        LocalDate after = holidayDate.plusDays(1);
        Location location = Location.builder().id(UUID.randomUUID()).name("HQ").build();
        Employee employeeAtLocation = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(holidayDate.minusYears(1)).location(location).build();

        // "Before" is a half-day leave, not a full no-attendance day; "after" is also unattended
        // (so the run would otherwise satisfy SANDWICHED if the half day were ignored as leave).
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), before, after))
                .thenReturn(List.of());
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of(
                LeaveRequest.builder().employeeUserId(employeeId).startDate(before).endDate(before)
                        .status("APPROVED").halfDay(true).build()));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeAtLocation));
        when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of(
                Holiday.builder().id(UUID.randomUUID()).holidayName("Test Holiday")
                        .holidayDate(holidayDate).location(location).active(true).build()));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(holidayDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningHolidayEnabled(true).naAdjoiningHolidayCondition("SANDWICHED")
                .naAdjoiningHolidayIgnoreHalfDayLeave(true)
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, before, after);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertFalse(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(holidayDate)),
                "a half-day leave, when configured to be ignored, breaks the sandwich — the holiday must NOT be penalised");
    }

    @Test
    void halfDayLeaveBeforeHoliday_ignoreHalfDay_false_countsAsNoAttendance_holidayPenalised() {
        LocalDate holidayDate = priorMidWeekday();
        LocalDate before = holidayDate.minusDays(1);
        LocalDate after = holidayDate.plusDays(1);
        Location location = Location.builder().id(UUID.randomUUID()).name("HQ").build();
        Employee employeeAtLocation = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee")
                .joiningDate(holidayDate.minusYears(1)).location(location).build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), before, after))
                .thenReturn(List.of());
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of(
                LeaveRequest.builder().employeeUserId(employeeId).startDate(before).endDate(before)
                        .status("APPROVED").halfDay(true).build()));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeAtLocation));
        when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of(
                Holiday.builder().id(UUID.randomUUID()).holidayName("Test Holiday")
                        .holidayDate(holidayDate).location(location).active(true).build()));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(holidayDate.minusMonths(1).atStartOfDay())
                .noAttendanceEnabled(true).naDeductionDays(new java.math.BigDecimal("1"))
                .naAdjoiningHolidayEnabled(true).naAdjoiningHolidayCondition("SANDWICHED")
                .naAdjoiningHolidayIgnoreHalfDayLeave(false)
                .build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, before, after);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getIncidentDate().equals(holidayDate)),
                "when half-day leave is configured to count, the sandwich is satisfied and the holiday IS penalised");
    }

    // ── Scheduler's date-range math (Section 19/45): never today, correct lookback window ──
    @Test
    void runScheduledPenaltyEvaluation_neverIncludesToday_usesConfiguredLookback() {
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(any(), any(), any())).thenReturn(List.of());

        exceptionService.runScheduledPenaltyEvaluation(7);

        LocalDate expectedToday = LocalDate.now(zone);
        LocalDate expectedTo = expectedToday.minusDays(1);
        LocalDate expectedFrom = expectedTo.minusDays(6); // 7-day window inclusive of "to"

        org.mockito.ArgumentCaptor<LocalDate> fromCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.ArgumentCaptor<LocalDate> toCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(any(), fromCaptor.capture(), toCaptor.capture());
        assertEquals(expectedFrom, fromCaptor.getValue());
        assertEquals(expectedTo, toCaptor.getValue());
        assertTrue(toCaptor.getValue().isBefore(expectedToday), "must never evaluate today, an in-progress day");
    }

    @Test
    void runScheduledPenaltyEvaluation_noEmployeeRoleAccounts_isANoOp() {
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of());

        exceptionService.runScheduledPenaltyEvaluation(7);

        verify(attendanceRepository, org.mockito.Mockito.never()).findByEmployeeUserIdInAndWorkDateBetween(any(), any(), any());
    }

    /**
     * Section 8: {@code Employee#lastWorkingDay} — the domain's one genuine, HR-authored
     * termination fact — blocks NEW penalty creation for any date after it. Reuses the exact
     * NO_ATTENDANCE scenario {@link #expectedWorkingDayWithNoAttendanceRow_detectsNoAttendance_appliesPenalty}
     * already proves fires a penalty — here the only difference is a lastWorkingDay before the
     * evaluated date, which must suppress it. A merely-deactivated employee (User.active = false,
     * no lastWorkingDay) is deliberately NOT an exclusion case — active alone must never gate this
     * (see this method's production-code javadoc); only lastWorkingDay does.
     */
    @Test
    void evaluatePolicy_dateAfterLastWorkingDay_neverCreatesANewPenalty() {
        Employee terminatedEmployee = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .joiningDate(targetDate.minusYears(1)).lastWorkingDay(targetDate.minusDays(1)).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(terminatedEmployee));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(terminatedEmployee));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of());
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(noAttendanceVersion()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        org.mockito.Mockito.verifyNoInteractions(attendancePenaltyRepository);
    }

    /**
     * Same scenario, but the last working day is ON the evaluated date itself — this is exactly
     * the boundary the gate must NOT suppress (an employee's actual final day is still a genuine
     * working day to evaluate).
     */
    @Test
    void evaluatePolicy_dateOnLastWorkingDay_stillEvaluatesNormally() {
        Employee employeeLeavingToday = Employee.builder().userId(employeeId)
                .user(User.builder().id(employeeId).email("employee@test.com").build())
                .joiningDate(targetDate.minusYears(1)).lastWorkingDay(targetDate).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeLeavingToday));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employeeLeavingToday));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of());
        when(versionRepository.findVersionsEffectiveAt(targetDate.atStartOfDay())).thenReturn(List.of(noAttendanceVersion()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository).save(captor.capture());
        assertEquals(ExceptionType.NO_ATTENDANCE, captor.getValue().getDiscrepancyType());
    }

    // ── Phase 3: Weekly/Monthly Work Hours Shortage frequency ──────────────────────────────────

    private Employee employeeWithShift(Shift shift) {
        return Employee.builder().userId(employeeId).user(User.builder().id(employeeId).email("employee@test.com").build())
                .employeeCode("NF-1").fullName("Test Employee").joiningDate(LocalDate.of(2020, 1, 1)).shift(shift).build();
    }

    private Attendance fullSpanAttendance(UUID empId, LocalDate date, java.time.LocalTime checkIn, java.time.LocalTime checkOut, int workedMinutes) {
        return Attendance.builder().employeeUserId(empId).workDate(date)
                .checkInAt(date.atTime(checkIn)).checkOutAt(date.atTime(checkOut)).workedMinutes(workedMinutes).lateByMinutes(0).build();
    }

    @Test
    void weeklyFrequency_evaluatesOnceOnTheCyclesLastDay_appliesOnePenaltyForTheWholeWeek() {
        // Fixed, far-in-the-past week so it's always safely before "today" — Monday 4 Mar 2024
        // through Sunday 10 Mar 2024.
        LocalDate monday = LocalDate.of(2024, 3, 4);
        LocalDate sunday = monday.plusDays(6);
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Employee employee = employeeWithShift(shift);
        List<Attendance> weekRecords = monday.datesUntil(sunday.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .map(d -> fullSpanAttendance(employeeId, d, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), 480))
                .toList();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), monday, sunday))
                .thenReturn(weekRecords);
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(monday.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK").build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("1")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, monday, sunday);

        // 5 days * 480 worked = 2400; 5 days * 540 expected = 2700 -> 88.9%, below the 90% tier.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals(ExceptionType.WORK_HOURS_SHORTAGE, captor.getValue().getDiscrepancyType());
        assertEquals(sunday, captor.getValue().getIncidentDate(), "the week's single penalty must be dated on the cycle's own last day");
        assertEquals(new java.math.BigDecimal("1"), captor.getValue().getDeductionDays());
    }

    @Test
    void weeklyFrequency_noShortfall_appliesNoPenalty() {
        LocalDate monday = LocalDate.of(2024, 3, 4);
        LocalDate sunday = monday.plusDays(6);
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Employee employee = employeeWithShift(shift);
        List<Attendance> weekRecords = monday.datesUntil(sunday.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .map(d -> fullSpanAttendance(employeeId, d, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 540))
                .toList();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), monday, sunday))
                .thenReturn(weekRecords);
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(monday.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK").build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        lenient().when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("1")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, monday, sunday);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void monthlyFrequency_evaluatesOnceOnTheLastCalendarDayOfTheMonth() {
        LocalDate monthStart = LocalDate.of(2024, 2, 1); // 2024 is a leap year -> Feb has 29 days
        LocalDate monthEnd = LocalDate.of(2024, 2, 29);
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Employee employee = employeeWithShift(shift);
        List<Attendance> monthRecords = monthStart.datesUntil(monthEnd.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .map(d -> fullSpanAttendance(employeeId, d, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), 480))
                .toList();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), monthStart, monthEnd))
                .thenReturn(monthRecords);
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(monthStart.minusMonths(2).atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionPeriod("MONTH").build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("1")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, monthStart, monthEnd);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals(monthEnd, captor.getValue().getIncidentDate(), "the month's single penalty must be dated on the calendar month's last day");
    }

    @Test
    void weeklyFrequency_midCyclePolicyChange_onlyThisVersionsOwnDatesAreAggregated() {
        // Monday-Tuesday governed by an OLDER version; Wednesday onward by a NEW version that
        // takes effect mid-week — the new version's weekly aggregate must not reach back into
        // Monday/Tuesday (Section 7: no retroactive application to a different version's dates).
        LocalDate monday = LocalDate.of(2024, 3, 4);
        LocalDate wednesday = monday.plusDays(2);
        LocalDate sunday = monday.plusDays(6);
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Employee employee = employeeWithShift(shift);
        // Only the new version's own window (Wed-Fri) has attendance worth aggregating; a full
        // 9h/day means no shortfall if (and only if) Monday/Tuesday are correctly excluded.
        List<Attendance> records = wednesday.datesUntil(sunday.plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .map(d -> fullSpanAttendance(employeeId, d, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 540))
                .toList();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), monday, sunday))
                .thenReturn(records);
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        PenalizationPolicyVersion newVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(2)
                .effectiveFrom(wednesday.atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK").build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(newVersion));
        lenient().when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(newVersion.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("1")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, monday, sunday);

        // Wed-Fri: 3 * 540 / 3 * 540 = 100% -> no shortfall, no penalty. Had Monday/Tuesday's
        // (missing, i.e. absent-from-the-map) contribution been wrongly excluded-as-zero rather
        // than genuinely excluded, or had the version's own start not been honored, this would
        // have come out below the tier instead.
        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    // ── Phase 3: Missing Logs -> Work Hours Shortage linkage ────────────────────────────────

    @Test
    void missingLogShortageLinkage_disabledByDefault_noPenaltyForMissingCheckoutDay() {
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Attendance missingCheckout = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(null).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(missingCheckout));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).whsPenalizeShortageCausedByMissingLogsEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void missingLogShortageLinkage_enabled_appliesShortagePenaltyForMissingCheckoutDay() {
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        Attendance missingCheckout = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(null).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(missingCheckout));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, targetDate)).thenReturn(Optional.of(missingCheckout));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(shift)));
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).whsPenalizeShortageCausedByMissingLogsEnabled(true).build();
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("0.5")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals(ExceptionType.WORK_HOURS_SHORTAGE, captor.getValue().getDiscrepancyType());
        assertEquals(new java.math.BigDecimal("0.5"), captor.getValue().getDeductionDays());
    }

    // ── Phase 3: Effective vs Gross basis, end-to-end ───────────────────────────────────────

    private PenalizationPolicyVersion basisVersion(String basis) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .effectiveFrom(targetDate.minusMonths(1).atStartOfDay())
                .workHoursShortageEnabled(true).whsDeductionBasis(basis).build();
    }

    @Test
    void effectiveHoursBasis_aLongBreakInsideAFullPunchSpan_stillTriggersShortage() {
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        // 9:00-19:00 punch span (600 min gross) but a long break means only 460 min were actually
        // effective — Effective basis (460/540 = 85.2%) falls below a 90% tier.
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(targetDate.atTime(19, 0))
                .workedMinutes(460).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(record));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, targetDate)).thenReturn(Optional.of(record));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(shift)));
        PenalizationPolicyVersion version = basisVersion("EFFECTIVE_HOURS");
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("0.5")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void grossHoursBasis_sameAttendance_thePunchSpanCoversTheWholeShift_noShortage() {
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        // Identical Attendance row to the EFFECTIVE-basis test above — GROSS_HOURS instead counts
        // the full 9:00-19:00 span (600/540 = 111%), well above any shortage tier, even though
        // Effective basis would have flagged this exact same day.
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(targetDate.atTime(19, 0))
                .workedMinutes(460).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(record));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, targetDate)).thenReturn(Optional.of(record));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(shift)));
        PenalizationPolicyVersion version = basisVersion("GROSS_HOURS");
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        lenient().when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("0.5")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        verify(attendancePenaltyRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * The historical-integrity invariant Shift Versioning exists to protect: a shift's timing
     * changing AFTER a date occurred must never retroactively change that date's WORK_HOURS_
     * SHORTAGE evaluation. Here the shift's ORIGINAL version (9:00-18:00, 540 expected minutes)
     * makes 480 worked minutes a genuine shortage (88.9% < the 90% tier); a NEW version (9:00-
     * 13:00, only 240 expected minutes), effective the day AFTER targetDate, would make that same
     * 480 minutes comfortably NOT a shortage if it were wrongly used instead. Detecting a penalty
     * at all here proves the OLD version was resolved for targetDate, not the new one.
     */
    @Test
    void workHoursShortage_historicalDate_resolvesTheShiftVersionEffectiveOnThatDate_notALaterChangedOne() {
        Shift shift = shift("Regular", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0));
        // A second version of the SAME shift, effective the day after targetDate — simulates an
        // admin changing this shift's timing sometime after targetDate's attendance already
        // happened. Registered directly against the shared in-memory version list (see shift()).
        shiftVersions.add(com.nforce.onehr.entity.ShiftVersion.builder().shift(shift)
                .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(13, 0))
                .effectiveFrom(targetDate.plusDays(1)).build());
        Attendance record = Attendance.builder().employeeUserId(employeeId).workDate(targetDate)
                .checkInAt(targetDate.atTime(9, 0)).checkOutAt(targetDate.atTime(17, 0))
                .workedMinutes(480).lateByMinutes(0).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), targetDate, targetDate))
                .thenReturn(List.of(record));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, targetDate)).thenReturn(Optional.of(record));
        when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee(shift)));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee(shift)));
        PenalizationPolicyVersion version = basisVersion("GROSS_HOURS");
        when(versionRepository.findVersionsEffectiveAt(any())).thenReturn(List.of(version));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(version.getId())).thenReturn(List.of(
                PenalizationPolicyWorkHoursTier.builder().thresholdPercent(new java.math.BigDecimal("90"))
                        .deductionDays(new java.math.BigDecimal("0.5")).sortOrder(0).build()));

        exceptionService.getExceptionsForCaller(hrEmail, targetDate, targetDate);

        ArgumentCaptor<AttendanceException> excCaptor = ArgumentCaptor.forClass(AttendanceException.class);
        verify(attendanceExceptionRepository).save(excCaptor.capture());
        assertEquals(ExceptionType.WORK_HOURS_SHORTAGE, excCaptor.getValue().getExceptionType());
        // The displayed "expected" time is also the OLD version's own end (18:00), not the new
        // version's 13:00 — see ExceptionService's own comment on this exact line.
        assertEquals(java.time.LocalTime.of(18, 0), excCaptor.getValue().getExpectedTime());
        verify(attendancePenaltyRepository, org.mockito.Mockito.times(1)).save(any());
    }

    private static LocalDate priorMidWeekday() {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(20);
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY && d.getDayOfWeek() != DayOfWeek.WEDNESDAY && d.getDayOfWeek() != DayOfWeek.THURSDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
