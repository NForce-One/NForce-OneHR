package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
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
 * The clarified business rule (2026-09-08): there is exactly ONE allowed-late privilege — the
 * assigned Shift's own {@link ShiftVersion#getLateGraceMinutes()} — not a second, independently
 * configured {@link PenalizationPolicyVersion#getLaGracePeriodMinutes()}. An arrival inside that
 * privilege is not late at all: no {@code LATE_ARRIVAL} exception, no incident, no contribution
 * toward "every Nth late arrival," no penalty. Only an arrival past it is a genuine late incident.
 *
 * <p>Every {@link Attendance} fixture here is built through the REAL
 * {@link AttendanceInterpretationService#interpretForKnownWorkDate} — the exact formula
 * check-in itself uses — rather than hand-set {@code status}/{@code lateByMinutes} values, so
 * these tests prove the rule against the real implementation, not a re-typed copy of it.
 *
 * <p>Same real-production-flow wiring as {@link PenalizationPolicyProductionFlowTest} (only
 * repositories/EmailService mocked) — this class exists alongside it to keep that file focused on
 * multi-version/multi-policy resolution, and this one focused purely on the grace-unification rule.
 */
@ExtendWith(MockitoExtension.class)
class LateArrivalGraceUnificationTest {

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
    @Mock private ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private ShiftRepository shiftRepository;

    private ExceptionService exceptionService;
    private AttendanceInterpretationService interpretationService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";
    private final LocalDate monthStart = LocalDate.of(2026, 8, 1);
    private final LocalDate monthEnd = LocalDate.of(2026, 8, 31);

    private Shift shift;
    private Employee employee;

    /** Shift starts 09:00, 10-minute allowed-late privilege — the example from the business rule. */
    private static final int SHIFT_GRACE_MINUTES = 10;

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
                ShiftWeeklyOffRules.builder().maximumShiftDayDurationHours(BigDecimal.valueOf(18)).build()));
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver);
        interpretationService = new AttendanceInterpretationService(shiftDayPolicy, shiftRepository);
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

        shift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        User employeeUser = User.builder().id(employeeId).email("employee@test.com").build();
        employee = Employee.builder().userId(employeeId).user(employeeUser).fullName("Test Employee")
                .employeeCode("NF-TEST").shift(shift).build();
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        // Every ShiftVersion resolution in this class shares one 09:00 start / 10-minute grace —
        // the exact example from the clarified business rule.
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(
                ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                        .lateGraceMinutes(SHIFT_GRACE_MINUTES).build());
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    /**
     * Built through the real {@link AttendanceInterpretationService} — status/lateByMinutes are
     * whatever check-in itself would have computed, never hand-set — so these tests exercise the
     * real formula, not a re-typed copy of it.
     */
    private Attendance attendanceAt(LocalDate date, LocalTime checkInTime) {
        LocalDateTime checkInAt = date.atTime(checkInTime);
        AttendanceInterpretation interpretation = interpretationService.interpretForKnownWorkDate(employee, date, checkInAt);
        return Attendance.builder()
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(checkInAt).checkOutAt(date.atTime(18, 0))
                .status(interpretation.getIsLate() ? "LATE" : "PRESENT")
                .lateByMinutes(interpretation.getLateByMinutes())
                .shiftId(shift.getId())
                .build();
    }

    private PenalizationPolicyVersion incidentBasisVersion() {
        // laExemptCount(0) + laDeductionPerShifts(3): "every 3rd genuine late arrival" — the exact
        // business example ("every 3rd late arrival = 0.5 Casual Leave"). laGracePeriodMinutes is
        // deliberately NOT set here — the shift's own grace is the sole privilege now.
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(1)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(true).laExemptCount(0).laDeductionPerShifts(3)
                .laDeductionDays(new BigDecimal("0.5"))
                .build();
    }

    private void stubDay(LocalDate date, Attendance record) {
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(record));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(incidentBasisVersion()));
    }

    // ── 1-3: within the 10-minute privilege — no LATE_ARRIVAL exception, no incident, no penalty ──

    @Test
    void checkInAtShiftStart_0900_noLateArrivalException() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        stubDay(date, attendanceAt(date, LocalTime.of(9, 0)));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendanceExceptionRepository, never()).save(any());
        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkInAt0905_withinGrace_noLateArrivalException() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        Attendance record = attendanceAt(date, LocalTime.of(9, 5));
        assertEquals("PRESENT", record.getStatus(), "sanity: 5 minutes is within the 10-minute privilege");
        stubDay(date, record);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendanceExceptionRepository, never()).save(any());
        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkInAt0910_exactlyAtGraceBoundary_noLateArrivalException() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        Attendance record = attendanceAt(date, LocalTime.of(9, 10));
        assertEquals("PRESENT", record.getStatus(), "sanity: exactly the grace boundary is still accepted");
        stubDay(date, record);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendanceExceptionRepository, never()).save(any());
        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    // ── 4-5: past the privilege — exactly one genuine incident, regardless of raw lateByMinutes ──

    @Test
    void checkInAt0911_pastGrace_createsExactlyOneLateArrivalException() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        Attendance record = attendanceAt(date, LocalTime.of(9, 11));
        assertEquals("LATE", record.getStatus(), "sanity: 11 minutes is past the 10-minute privilege");
        stubDay(date, record);
        // Below the exempt/batch threshold (count=1 of 3) — genuinely late, but no deduction yet.
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendanceExceptionRepository, times(1)).save(any());
        verify(attendancePenaltyRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkInAt0959_deeplyLate_stillExactlyOneGenuineIncident_regardlessOfRawLateByMinutes() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        Attendance record = attendanceAt(date, LocalTime.of(9, 59));
        assertEquals("LATE", record.getStatus());
        assertEquals(59, record.getLateByMinutes(), "raw lateByMinutes stays the true magnitude, for display/audit only");
        stubDay(date, record);
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        // Exactly one exception row for the date (upsertException is keyed by employee/date/type) —
        // 59 minutes late does not create more than one incident.
        verify(attendanceExceptionRepository, times(1)).save(any());
    }

    // ── 6-8: every 3rd GENUINE late incident deducts 0.5 day; no double/early/missed deduction ──

    @Test
    void every3rdGenuineLateArrival_deductsExactlyHalfDay_others_deductNothing() {
        LocalDate[] dates = {
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 10),
        };
        // Each day's own genuine late arrival (9:15 — comfortably past the 10-minute privilege).
        for (LocalDate date : dates) {
            stubDay(date, attendanceAt(date, LocalTime.of(9, 15)));
        }
        // The cumulative genuine-late count as of each successive evaluation — 1..6.
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        for (LocalDate date : dates) {
            exceptionService.getExceptionsForCaller(hrEmail, date, date);
        }

        // Deductions only on the 3rd and 6th occurrence — never on 1st/2nd/4th/5th, never twice for
        // the same occurrence.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(2)).saveAndFlush(captor.capture());
        List<AttendancePenalty> penalties = captor.getAllValues();
        assertEquals(LocalDate.of(2026, 8, 5), penalties.get(0).getIncidentDate(), "3rd genuine late arrival");
        assertEquals(LocalDate.of(2026, 8, 10), penalties.get(1).getIncidentDate(), "6th genuine late arrival");
        assertEquals(new BigDecimal("0.5"), penalties.get(0).getDeductionDays());
        assertEquals(new BigDecimal("0.5"), penalties.get(1).getDeductionDays());
    }

    // ── 9: a within-grace arrival mixed into the sequence never counts toward the threshold ──

    @Test
    void withinGraceArrival_interspersedAmongGenuineLates_neverCountsTowardThreshold() {
        LocalDate day1 = LocalDate.of(2026, 8, 3);
        LocalDate day2WithinGrace = LocalDate.of(2026, 8, 4);
        LocalDate day3 = LocalDate.of(2026, 8, 5);
        LocalDate day4 = LocalDate.of(2026, 8, 6);

        stubDay(day1, attendanceAt(day1, LocalTime.of(9, 15)));       // genuine late #1
        stubDay(day2WithinGrace, attendanceAt(day2WithinGrace, LocalTime.of(9, 8))); // within grace
        stubDay(day3, attendanceAt(day3, LocalTime.of(9, 20)));       // genuine late #2
        stubDay(day4, attendanceAt(day4, LocalTime.of(9, 25)));       // genuine late #3 -> deduction

        // Only genuine lates ever reach the count query — day2 never triggers detectExceptions'
        // LATE_ARRIVAL branch at all, so this stub is only ever consulted 3 times, not 4.
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L, 2L, 3L);

        exceptionService.getExceptionsForCaller(hrEmail, day1, day1);
        exceptionService.getExceptionsForCaller(hrEmail, day2WithinGrace, day2WithinGrace);
        exceptionService.getExceptionsForCaller(hrEmail, day3, day3);
        exceptionService.getExceptionsForCaller(hrEmail, day4, day4);

        verify(attendanceExceptionRepository, times(3)).save(any()); // day2 never persisted
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).saveAndFlush(captor.capture());
        assertEquals(day4, captor.getValue().getIncidentDate(), "the 3rd GENUINE late arrival is day4, not day3");
    }

    // ── 16: a policy grace differing from the shift's own grace cannot alter eligibility ──

    @Test
    void policyGraceWiderThanShiftGrace_stillPenalizesAGenuinelyLateArrival() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        // 9:15 is 5 minutes past the shift's own 10-minute privilege — genuinely late.
        Attendance record = attendanceAt(date, LocalTime.of(9, 15));
        assertEquals("LATE", record.getStatus());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(record));
        // A policy grace of 30 minutes — far wider than the shift's own 10 — used to exempt this
        // occurrence outright before the unification fix. It must have zero effect now.
        PenalizationPolicyVersion version = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(1)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(30).laExemptCount(0).laDeductionPerShifts(1)
                .laDeductionDays(new BigDecimal("0.5")).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(version));
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, times(1)).saveAndFlush(any());
    }

    // ── Regression guard: the incident count is sourced from live Attendance.status, never from
    // the (append-only, never-deleted) AttendanceException snapshot rows — see ExceptionService's
    // own comment on why counting the snapshot would keep a regularized day counted forever. ──

    @Test
    void incidentCount_sourcedFromLiveAttendanceStatus_neverFromTheExceptionSnapshotTable() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        stubDay(date, attendanceAt(date, LocalTime.of(9, 15)));
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE"))
                .thenReturn(1L);

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendanceRepository, times(1))
                .countByEmployeeUserIdAndWorkDateBetweenAndStatus(employeeId, monthStart, monthEnd, "LATE");
        // Missing Logs' own count is untouched by this fix and legitimately still uses the
        // exception-snapshot table — only LATE_ARRIVAL must never be counted from it any more.
        verify(attendanceExceptionRepository, never()).countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(
                any(), eq(ExceptionType.LATE_ARRIVAL), any(), any());
    }

    // ── Regularization / stale-incident correctness: once a previously-genuine late arrival's
    // Attendance row is corrected away from LATE (exactly what RegularizationService#approve
    // already does via applyInterpretation), isDiscrepancyStillActive must no longer treat it as
    // active — proven directly against ExceptionService's own re-evaluation entry point, the same
    // one RegularizationService#approve calls after correcting the record. ──

    @Test
    void reevaluateAndReverseIfInvalid_regularizedLateArrival_noLongerActive_penaltyReversed() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        AttendancePenalty existingPenalty = AttendancePenalty.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).incidentDate(date)
                .discrepancyType(ExceptionType.LATE_ARRIVAL).policyId(policyId).policyVersion(1)
                .deductionDays(new BigDecimal("0.5")).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDate(employeeId, date))
                .thenReturn(List.of(existingPenalty));
        // The record as RegularizationService#approve would have already corrected it: status back
        // to PRESENT, lateByMinutes back to 0 — the fact this discrepancy was based on no longer holds.
        Attendance corrected = Attendance.builder()
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 5)).checkOutAt(date.atTime(18, 0))
                .status("PRESENT").lateByMinutes(0).shiftId(shift.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.of(corrected));

        exceptionService.reevaluateAndReverseIfInvalid(employeeId, date,
                ExceptionService.REGULARIZATION_REEVALUATION_TYPES, UUID.randomUUID(),
                "Attendance corrected via approved regularization", "ATTENDANCE_PENALTY_REVERSED");

        verify(attendancePenaltyService, times(1)).reverseIfActive(
                eq(existingPenalty.getId()), any(), anyString(), eq("ATTENDANCE_PENALTY_REVERSED"));
    }
}
