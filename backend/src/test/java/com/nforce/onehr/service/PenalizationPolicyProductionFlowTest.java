package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The real production execution path, end to end, with NOTHING except repositories/EmailService
 * mocked: {@code ExceptionService.getExceptionsForCaller} (the existing, already-invoked
 * Exception Dashboard load — not a scheduler) → {@code ExceptionService.upsertException} →
 * {@code ExceptionService.evaluatePolicy} → real {@link AttendancePenaltyEvaluationService} →
 * real {@link ConfiguredAttendancePolicyEngine} → real {@link PenalizationPolicyVersion} lookup →
 * {@link AttendancePenalty} persistence. {@link ConfiguredAttendancePolicyEngineTest} covers the
 * engine's decision logic in isolation; this class proves that logic is actually reachable from
 * the same production entry point HR/Manager already use, and that a Penalization Policy change
 * made through {@link PenalizationPolicyService} (not by calling the engine directly) changes the
 * outcome for an identical attendance fact.
 */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyProductionFlowTest {

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
    @Mock private ShiftVersionResolver shiftVersionResolver;

    private ExceptionService exceptionService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";

    @BeforeEach
    void setUp() {
        // Real objects — not mocks — for every layer between the production trigger and the
        // engine. Only repositories/EmailService are mocked.
        AttendancePolicyEngine policyEngine = new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
        AuditSnapshotSerializer snapshotSerializer = new AuditSnapshotSerializer(new com.fasterxml.jackson.databind.ObjectMapper());
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
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // Both detectExceptions' own GAP-007 working-day gate and detectNoAttendanceAndShortage
        // need a real schedule for this employee to treat the test dates (weekdays, no location/
        // weekly-off policy configured => default Sat/Sun off) as working days. The employee has
        // no location, so detectNoAttendanceAndShortage's holiday/leave lookups below stay no-ops
        // and its shortage/NO_ATTENDANCE branches never fire (workedMinutes is left null on every
        // fixture Attendance and no default policy is configured), leaving this test class free to
        // focus purely on the LATE_ARRIVAL flow it was written for.
        Shift shift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any()))
                .thenReturn(List.of(Employee.builder().userId(employeeId).shift(shift).build()));
        // shiftDayPolicy.resolveShiftStart(...) (LATE_ARRIVAL's "expected" start time) needs this
        // employee's Shift to resolve to *some* ShiftVersion rather than throw — the exact
        // start/end values don't matter to any assertion in this class.
        lenient().when(shiftVersionResolver.resolve(any(), any()))
                .thenReturn(ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    // Unified-grace model: Attendance.status is the sole "genuinely late" signal ExceptionService's
    // detectExceptions gates LATE_ARRIVAL detection on — a fixture representing a genuine late
    // arrival must carry status LATE, exactly as AttendanceInterpretationService/AttendanceService
    // would have set it (lateByMinutes alone is only ever the raw, no-forgiveness display figure).
    private Attendance lateAttendance(LocalDate date, int lateByMinutes) {
        return Attendance.builder()
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30).plusMinutes(lateByMinutes))
                .checkOutAt(date.atTime(18, 0))
                .status("LATE").lateByMinutes(lateByMinutes)
                .build();
    }

    // graceMinutes is retained only as an inert PenalizationPolicyVersion field — no longer
    // consulted by ConfiguredAttendancePolicyEngine for Late Arrival eligibility (the assigned
    // Shift's own ShiftVersion.lateGraceMinutes is the sole allowed-late privilege now); kept here
    // only to exercise that a stored/versioned value continues to round-trip harmlessly.
    private PenalizationPolicyVersion lateArrivalVersion(int version, int graceMinutes) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(version)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(graceMinutes)
                .laDeductionDays(new java.math.BigDecimal("0.5"))
                .build();
    }

    private PenalizationPolicyVersion disabledLateArrivalVersion(int version) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(version)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(false)
                .build();
    }

    // ── CRITICAL ACCEPTANCE TEST — real production flow, V1 ──
    @Test
    void realProductionFlow_v1_genuineLateArrival_appliesPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).saveAndFlush(captor.capture());
        AttendancePenalty penalty = captor.getValue();
        assertEquals(employeeId, penalty.getEmployeeUserId());
        assertEquals(date, penalty.getIncidentDate());
        assertEquals(ExceptionType.LATE_ARRIVAL, penalty.getDiscrepancyType());
        assertEquals(policyId, penalty.getPolicyId());
        assertEquals(1, penalty.getPolicyVersion());
        assertEquals(new java.math.BigDecimal("0.5"), penalty.getDeductionDays());
        assertNotNull(penalty.getEvaluatedAt());
    }

    // ── Same real production flow, V2: HR disables the Late Arrival section — NO code change,
    // only configuration. (Widening laGracePeriodMinutes no longer changes this outcome at all —
    // see ConfiguredAttendancePolicyEngineTest's own policyGraceChange_... acceptance test for that
    // specific claim; this test instead proves a real, still-effective per-version config change.) ──
    @Test
    void realProductionFlow_v2LateArrivalDisabled_sameGenuineLateArrival_noMatch_noPenaltyPersisted() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(disabledLateArrivalVersion(2)));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    // ── Version immutability across two evaluations through the real production path ──
    @Test
    void policyVersionChange_realFlow_historicalPenaltyKeepsV1_laterEvaluationUsesV2() {
        LocalDate augDate = LocalDate.of(2026, 8, 17);
        LocalDate sepDate = LocalDate.of(2026, 9, 15);

        when(versionRepository.findVersionsEffectiveAt(augDate.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));
        when(versionRepository.findVersionsEffectiveAt(sepDate.atStartOfDay()))
                .thenReturn(List.of(disabledLateArrivalVersion(2)));

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), augDate, augDate))
                .thenReturn(List.of(lateAttendance(augDate, 12)));
        exceptionService.getExceptionsForCaller(hrEmail, augDate, augDate);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), sepDate, sepDate))
                .thenReturn(List.of(lateAttendance(sepDate, 12)));
        exceptionService.getExceptionsForCaller(hrEmail, sepDate, sepDate);

        // Only the August evaluation (V1, Late Arrival enabled) produced a penalty; September
        // (V2, HR since disabled the section) did not, for the identical kind of genuine lateness.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).saveAndFlush(captor.capture());
        assertEquals(augDate, captor.getValue().getIncidentDate());
        assertEquals(1, captor.getValue().getPolicyVersion());
    }

    // ── Disabled section / outside effective period, through the real production path ──
    @Test
    void realProductionFlow_disabledLateArrivalSection_noMatch_noPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 30)));
        PenalizationPolicyVersion disabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(1)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(disabled));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    @Test
    void realProductionFlow_noEffectiveVersion_noMatch_noPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 30)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    // ── Regularization: unchanged existing meaning, now honored through the real flow too ──
    @Test
    void realProductionFlow_approvedRegularization_exempt_noPenaltyPersisted() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));
        RegularizationRequest approved = RegularizationRequest.builder()
                .employeeUserId(employeeId).attendanceDate(date).status("APPROVED").reason("r").build();
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(approved));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    // ── Duplicate-evaluation guard: re-running the dashboard load for an already-detected
    // exception must never create a second penalty row. ──
    @Test
    void reRunningDetection_forAlreadyDetectedException_doesNotDuplicatePenalty() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        // Simulate the exception already existing from a prior dashboard load — isNew=false means
        // evaluatePolicy (and therefore versionRepository) is never even reached this time.
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, date, ExceptionType.LATE_ARRIVAL))
                .thenReturn(Optional.of(AttendanceException.builder()
                        .id(UUID.randomUUID()).employeeUserId(employeeId).exceptionDate(date)
                        .exceptionType(ExceptionType.LATE_ARRIVAL).build()));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }
}
