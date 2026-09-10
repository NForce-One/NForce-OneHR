package com.nforce.onehr.service;

import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Punch History must group by WORKDAY (per {@link ShiftDayPolicy}), never calendar date — see the
 * Attendance UI correction spec's own worked example: a 10:00-19:00 shift, 18h max workday
 * duration, workday Sep 8 04:00 -> Sep 9 04:00. Punches at 9:25 AM Sep 8, 3:43 PM Sep 8,
 * 12:21 AM Sep 9, and 3:45 AM Sep 9 must all surface as ONE workday's punch history, in
 * chronological order, keyed off the single {@code workDate} (Sep 8) the check-in was originally
 * attributed to by {@code ShiftDayPolicy#shiftDayOf} — never re-derived from each punch's own
 * calendar date.
 *
 * <p>{@link AttendanceService#getPunches} already sources its grouping key from the caller-
 * supplied {@code workDate} (via {@code attendanceRepository.findByEmployeeUserIdAndWorkDate} and
 * {@code AttendancePunchRepository#findByAttendanceRecordIdOrderByCheckInAtAsc}, both scoped to
 * one {@code attendanceRecordId}/{@code workDate}), so these tests are a regression guard on that
 * existing correct behavior, not a fix — see the audit that preceded this change.
 */
@ExtendWith(MockitoExtension.class)
class AttendancePunchHistoryWorkdayTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private WebClockInRequestRepository webClockInRequestRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private LatePenaltyService latePenaltyService;
    @Mock private WorkingDayService workingDayService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;
    @Mock private com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;
    @Mock private com.nforce.onehr.repository.AttendanceRulesRepository attendanceRulesRepository;
    @Mock private com.nforce.onehr.repository.ShiftRepository shiftRepository;

    private AttendanceService service;
    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final List<ShiftVersion> shiftVersions = new ArrayList<>();
    private Shift shift;

    @BeforeEach
    void setUp() {
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(BigDecimal.valueOf(18)).build()));
        ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
            @Override
            public Optional<ShiftVersion> resolveIfPresent(Shift s, LocalDate workDate) {
                return shiftVersions.stream()
                        .filter(v -> v.getShift().getId().equals(s.getId()))
                        .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                        .max(Comparator.comparing(ShiftVersion::getEffectiveFrom));
            }
            @Override
            public ShiftVersion resolve(Shift s, LocalDate workDate) {
                return resolveIfPresent(s, workDate)
                        .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
            }
        };
        EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver = new EmployeeShiftAssignmentResolver(null) {
            @Override
            public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
                return employeeUserId.equals(employeeId) && shift != null
                        ? Optional.of(EmployeeShiftAssignment.builder()
                                .employeeUserId(employeeUserId).shift(shift).effectiveFrom(LocalDate.MIN).build())
                        : Optional.empty();
            }
            @Override
            public EmployeeShiftAssignment resolve(UUID employeeUserId, LocalDate workDate) {
                return resolveIfPresent(employeeUserId, workDate)
                        .orElseThrow(() -> new IllegalStateException("no assignment effective on or before " + workDate));
            }
        };
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver, employeeShiftAssignmentResolver);
        lenient().when(attendanceRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.AttendanceRules.builder()
                        .halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));
        AttendanceRulesService attendanceRulesService = new AttendanceRulesService(attendanceRulesRepository);
        lenient().when(shiftRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return shiftVersions.stream().map(ShiftVersion::getShift)
                    .filter(s -> s.getId().equals(id)).findFirst();
        });
        AttendanceInterpretationService attendanceInterpretationService =
                new AttendanceInterpretationService(shiftDayPolicy, shiftRepository, employeeShiftAssignmentResolver);
        service = new AttendanceService(attendanceRepository, attendancePunchRepository, webClockInRequestRepository,
                attendanceExceptionRepository, employeeRepository, managerHistoryRepository,
                auditService, auditSnapshot, latePenaltyService, workingDayService, expectedWorkHoursService,
                shiftDayPolicy, attendanceRulesService, attendanceInterpretationService, employeeShiftAssignmentResolver);

        // The spec's own worked example: 10:00-19:00 shift, 18h max -> workday Sep 8 04:00 to
        // Sep 9 04:00.
        shift = Shift.builder().id(UUID.randomUUID()).name("Regular").build();
        shiftVersions.add(ShiftVersion.builder().shift(shift).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(19, 0))
                .lateGraceMinutes(10).effectiveFrom(LocalDate.MIN).build());
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(shift).user(User.builder().id(employeeId).active(true).build()).build();
        lenient().when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        lenient().when(webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(any(), any()))
                .thenReturn(List.of());
    }

    /**
     * The exact scenario from the spec: 9:25 AM Sep 8, 3:43 PM Sep 8, 12:21 AM Sep 9, 3:45 AM
     * Sep 9 — all four punches belong to the ONE workday that started before midnight (Sep 8),
     * and must come back together, in that exact chronological order, from a single
     * {@code getPunches(employeeEmail, sep8)} call.
     */
    @Test
    void getPunches_postMidnightPunches_stayAttachedToTheOriginatingWorkday_inChronologicalOrder() {
        LocalDate sep8 = LocalDate.of(2026, 9, 8);
        LocalDate sep9 = sep8.plusDays(1);
        UUID attendanceRecordId = UUID.randomUUID();

        Attendance record = Attendance.builder().id(attendanceRecordId).employeeUserId(employeeId)
                .workDate(sep8).shiftId(shift.getId())
                .checkInAt(LocalDateTime.of(sep8, LocalTime.of(9, 25)))
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, sep8))
                .thenReturn(Optional.of(record));

        // Registered on the Attendance row (attendanceRecordId), exactly as check-in/resume
        // punches actually are — never keyed by their OWN calendar date.
        List<AttendancePunch> punches = List.of(
                punch(attendanceRecordId, LocalDateTime.of(sep8, LocalTime.of(15, 43)), LocalDateTime.of(sep8, LocalTime.of(15, 43))),
                punch(attendanceRecordId, LocalDateTime.of(sep8, LocalTime.of(9, 25)), LocalDateTime.of(sep8, LocalTime.of(12, 0))),
                punch(attendanceRecordId, LocalDateTime.of(sep9, LocalTime.of(0, 21)), LocalDateTime.of(sep9, LocalTime.of(3, 45)))
        );
        when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId))
                .thenReturn(punches);

        List<PunchResponse> result = service.getPunches(employeeEmail, sep8);

        assertEquals(3, result.size(), "every punch on this workday, including the post-midnight ones, must come back together");
        // Chronological order, by real check-in instant — including the post-midnight punches.
        assertTrue(result.get(0).getCheckInAt().isBefore(result.get(1).getCheckInAt()));
        assertTrue(result.get(1).getCheckInAt().isBefore(result.get(2).getCheckInAt()));
        assertEquals(LocalDateTime.of(sep9, LocalTime.of(0, 21)), result.get(2).getCheckInAt(),
                "the post-midnight punch must still be present, ordered last (chronologically), not dropped or misplaced");
    }

    /**
     * The mirror image: querying the NEXT calendar date (Sep 9) — which the post-midnight punches
     * above land on, by raw calendar date — must NOT resurface them a second time under a
     * calendar-date grouping. There is no separate Attendance row for Sep 9 in this scenario, so
     * the workday-scoped query correctly returns nothing.
     */
    @Test
    void getPunches_nextCalendarDateWithNoOwnWorkday_doesNotDoubleCountThePreviousWorkdaysPunches() {
        LocalDate sep8 = LocalDate.of(2026, 9, 8);
        LocalDate sep9 = sep8.plusDays(1);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, sep9)).thenReturn(Optional.empty());

        List<PunchResponse> result = service.getPunches(employeeEmail, sep9);

        assertTrue(result.isEmpty(), "post-midnight punches belong entirely to Sep 8's workday — Sep 9 has no attendance of its own here");
    }

    private AttendancePunch punch(UUID attendanceRecordId, LocalDateTime checkInAt, LocalDateTime checkOutAt) {
        return AttendancePunch.builder().id(UUID.randomUUID()).attendanceRecordId(attendanceRecordId)
                .checkInAt(checkInAt).checkOutAt(checkOutAt).build();
    }
}
