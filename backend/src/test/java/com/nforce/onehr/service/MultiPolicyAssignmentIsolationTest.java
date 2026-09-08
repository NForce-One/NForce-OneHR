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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Final-verification gap fix: confirms two employees, each assigned to a DIFFERENT
 * {@link PenalisationPolicy}, are evaluated against their own policy's configuration only — and
 * that the unscoped, cross-policy {@code findVersionsEffectiveAt} lookup is never consulted for
 * either of them. This is the exact scenario Section 36 ("Employee → Assigned Policy → Applicable
 * Version") requires and Policy List (Section 5) makes possible for the first time — before this
 * fix, an employee with no explicit assignment fell through to that unscoped query, which becomes
 * ambiguous (not just "unused") the moment a second policy exists, since it orders by version
 * number across every policy combined.
 */
@ExtendWith(MockitoExtension.class)
class MultiPolicyAssignmentIsolationTest {

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

    private final UUID employeeAId = UUID.randomUUID();
    private final UUID employeeBId = UUID.randomUUID();
    private final UUID policyAId = UUID.randomUUID();
    private final UUID policyBId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";
    private final LocalDate date = LocalDate.of(2026, 8, 10); // a Monday

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
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeAId, employeeBId));
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
        lenient().when(attendancePenaltyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of());
        // Every employee() built below carries this same Shift, so the LATE_ARRIVAL path's
        // shiftDayPolicy.resolveShiftStart(...) call never hits the no-shift invariant guard —
        // the exact start/end values don't matter here, only that resolution doesn't throw.
        lenient().when(shiftVersionResolver.resolve(any(), any()))
                .thenReturn(ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    private Employee employee(UUID id, PenalisationPolicy policy) {
        User user = User.builder().id(id).email(id + "@test.com").build();
        Shift shift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        return Employee.builder().userId(id).user(user).employeeCode("NF-" + id).fullName("Employee " + id)
                .joiningDate(date.minusYears(1)).penalisationPolicy(policy).shift(shift).build();
    }

    // Unified-grace model: Attendance.status is the sole "genuinely late" signal ExceptionService's
    // detectExceptions gates on (see its own comment) — lateByMinutes alone no longer creates a
    // LATE_ARRIVAL exception, so every fixture representing a genuine late arrival must also carry
    // status LATE, exactly as AttendanceInterpretationService/AttendanceService would have set it.
    private Attendance lateAttendance(UUID employeeId, int lateByMinutes) {
        return Attendance.builder().employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30).plusMinutes(lateByMinutes)).checkOutAt(date.atTime(18, 0))
                .status("LATE").lateByMinutes(lateByMinutes).build();
    }

    @Test
    void employeeA_evaluatedAgainstPolicyA_employeeB_evaluatedAgainstPolicyB_noGlobalFallback() {
        PenalisationPolicy policyA = PenalisationPolicy.builder().id(policyAId).name("Policy A").build();
        PenalisationPolicy policyB = PenalisationPolicy.builder().id(policyBId).name("Policy B").build();
        when(employeeRepository.findById(employeeAId)).thenReturn(Optional.of(employee(employeeAId, policyA)));
        when(employeeRepository.findById(employeeBId)).thenReturn(Optional.of(employee(employeeBId, policyB)));
        // Gap-001: the legacy-FK tier now gates through findActivePolicyIds() — both policies here
        // are ACTIVE (the entity's default status).
        when(penalisationPolicyRepository.findByStatus("ACTIVE")).thenReturn(List.of(policyA, policyB));
        // GAP-007's working-day gate (and detectNoAttendanceAndShortage's own resolution) need
        // this schedule lookup to return the SAME per-employee policy assignment `findById` does —
        // otherwise it would fall through to the unscoped lookup this test forbids below.
        when(employeeRepository.findAllByIdWithScheduleDetails(any()))
                .thenReturn(List.of(employee(employeeAId, policyA), employee(employeeBId, policyB)));

        // Both employees are 20 minutes late on the same day. The scope list's order isn't
        // guaranteed (it's built from a Set), so match on any list rather than a fixed order.
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(anyList(), eq(date), eq(date)))
                .thenReturn(List.of(lateAttendance(employeeAId, 20), lateAttendance(employeeBId, 20)));

        // Policy A: Late Arrival enabled, 1 day deduction. Policy B: Late Arrival section disabled
        // outright — both employees are equally, genuinely late (status LATE against their shared
        // shift grace; there is no separate policy-level grace left to differ by), so this
        // deliberately isolates the assertion to "each employee's OWN assigned policy governs" —
        // never grace arithmetic, which the unified-grace model no longer lets a policy override.
        PenalizationPolicyVersion versionA = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyAId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(true).laDeductionDays(BigDecimal.ONE).build();
        PenalizationPolicyVersion versionB = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyBId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyAId, date.atStartOfDay())).thenReturn(List.of(versionA));
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyBId, date.atStartOfDay())).thenReturn(List.of(versionB));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        // Only Employee A's policy has Late Arrival enabled; Employee B's identical lateness is
        // evaluated against THEIR OWN policy, which has the section disabled.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).saveAndFlush(captor.capture());
        AttendancePenalty penalty = captor.getValue();
        assertEquals(employeeAId, penalty.getEmployeeUserId());
        assertEquals(policyAId, penalty.getPolicyId());
        assertEquals(BigDecimal.ONE, penalty.getDeductionDays());

        // Critical: the unscoped, cross-policy lookup must never be consulted for either employee
        // once each has an explicit assignment — no accidental global-policy fallback.
        verify(versionRepository, never()).findVersionsEffectiveAt(any());
    }

    @Test
    void employeeWithNoAssignment_fallsBackToOrgDefaultPolicy_deterministically() {
        // No penalisationPolicy set — simulates a newly-created employee nobody has assigned yet.
        when(employeeRepository.findById(employeeAId)).thenReturn(Optional.of(employee(employeeAId, null)));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeAId), date, date))
                .thenReturn(List.of(lateAttendance(employeeAId, 20)));
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeAId));
        when(employeeRepository.findAllByIdWithScheduleDetails(any()))
                .thenReturn(List.of(employee(employeeAId, null)));

        PenalisationPolicy defaultPolicy = PenalisationPolicy.builder().id(policyAId).name("Default Tracking Policy")
                .createdAt(java.time.LocalDateTime.of(2020, 1, 1, 0, 0)).orgDefault(true).build();
        // Section 7: the org-default fallback now resolves through resolveActiveDefaultPolicyId(),
        // which reads the admin-chosen isOrgDefault flag directly, not "oldest ACTIVE by createdAt".
        when(penalisationPolicyRepository.findByOrgDefaultTrue()).thenReturn(Optional.of(defaultPolicy));
        PenalizationPolicyVersion defaultVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyAId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(5).laDeductionDays(BigDecimal.ONE).build();
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyAId, date.atStartOfDay())).thenReturn(List.of(defaultVersion));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).saveAndFlush(captor.capture());
        assertEquals(policyAId, captor.getValue().getPolicyId(), "unassigned employee resolves to the deterministic org default, not an arbitrary policy");
        // Still never falls through to the ambiguous unscoped lookup.
        verify(versionRepository, never()).findVersionsEffectiveAt(any());
    }
}
