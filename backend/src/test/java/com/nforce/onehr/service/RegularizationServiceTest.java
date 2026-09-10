package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationApprovalRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests, mirroring LeaveServiceTest's isolation approach (this repo's H2
 * test profile can't create schema for the citext-typed entities, so tests avoid booting a
 * real ApplicationContext).
 */
@ExtendWith(MockitoExtension.class)
class RegularizationServiceTest {

    @Mock private RegularizationRequestRepository regularizationRepository;
    @Mock private RegularizationApprovalRepository regularizationApprovalRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private ExceptionService exceptionService;
    @Mock private com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;
    @Mock private com.nforce.onehr.repository.AttendanceRulesRepository attendanceRulesRepository;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private com.nforce.onehr.repository.ShiftRepository shiftRepository;

    private RegularizationService regularizationService;
    // Hoisted out of setUp() (rather than kept as local variables there) purely so
    // useClock(Clock) below can rebuild regularizationService with a different Clock — every
    // other collaborator is a genuine @Mock field already; these two are the only real
    // (non-mocked) collaborators the constructor needs.
    private AttendanceInterpretationService attendanceInterpretationService;
    private AttendanceRulesService attendanceRulesService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID hrId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String hrEmail = "hr@test.com";
    private final String strangerEmail = "stranger@test.com";
    private final String superAdminEmail = "superadmin@test.com";

    private User employeeUser;
    private User managerUser;
    private User hrUser;
    private User strangerUser;
    private User superAdminUser;
    // employeeId's default assigned Shift (set up below) — a field so individual @Test methods
    // can reference its id, e.g. to assert a fresh regularization-created row snapshots it.
    private Shift defaultShift;
    // Backs the fake EmployeeShiftAssignmentResolver below — every "brand-new record" test in
    // this file resolves against defaultShift for both employeeId and superAdminId; no test here
    // exercises a genuinely different assignment as of the correction date (the tests that swap
    // in a different Shift for employeeId all stub an EXISTING Attendance record, which resolves
    // via that record's own snapshotted shiftId, never this resolver — see e.g.
    // submit_correctingAnExistingRecord_validatesAgainstItsOwnSnapshottedShift_notTheEmployeesCurrentOne).
    private final Map<UUID, Shift> currentShiftByEmployee = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Role managerRole = Role.builder().id(1).code("MANAGER").displayName("Manager").build();
        Role hrRole = Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build();
        Role employeeRole = Role.builder().id(3).code("EMPLOYEE").displayName("Employee").build();
        Role superAdminRole = Role.builder().id(4).code("SUPER_ADMIN").displayName("Super Admin").build();

        employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        managerUser = User.builder().id(managerId).email(managerEmail).roles(Set.of(managerRole)).build();
        hrUser = User.builder().id(hrId).email(hrEmail).roles(Set.of(hrRole)).build();
        strangerUser = User.builder().id(strangerId).email(strangerEmail).roles(Set.of(employeeRole)).build();
        // Per this org's setup, Super Admin accounts also hold EMPLOYEE, so they can submit
        // their own regularization requests (the submit endpoint is gated on hasRole('EMPLOYEE')).
        superAdminUser = User.builder().id(superAdminId).email(superAdminEmail)
                .roles(Set.of(superAdminRole, employeeRole)).build();

        lenient().when(userRepository.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepository.findById(managerId)).thenReturn(Optional.of(managerUser));
        lenient().when(userRepository.findById(hrId)).thenReturn(Optional.of(hrUser));
        lenient().when(userRepository.findById(strangerId)).thenReturn(Optional.of(strangerUser));
        lenient().when(userRepository.findById(superAdminId)).thenReturn(Optional.of(superAdminUser));
        // Default: employeeId (the usual regularization subject in this file) resolves to an
        // Employee with a real assigned Shift, so recomputeDerivedFields' shift-relative timing
        // never hits the no-shift invariant guard by accident — tests that care about the exact
        // shift-start value stub shiftVersionResolver.resolve(...) themselves; everything else
        // just needs this not to throw. Any other employeeId still resolves to empty.
        defaultShift = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        // 15-minute grace matches this file's pre-existing expectations (previously the global
        // app.attendance.late-grace-minutes stub) — see individual tests' own lateness assertions.
        ShiftVersion defaultShiftVersion = ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .lateGraceMinutes(15).build();
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(defaultShiftVersion);
        // ONEHR-336 follow-up: ShiftDayPolicy's own "did the Shift already exist as of yesterday"
        // pre-check now calls resolveIfPresent (not resolve) directly — a plain @Mock leaves it
        // unstubbed, which Mockito answers with Optional.empty() regardless of whatever resolve()
        // was told to return, silently breaking Rule 2's overnight-rollover check everywhere in
        // this file. Delegates to whatever resolve() stub is active for that call (this default,
        // or any test-specific override registered later) instead of duplicating it, so every
        // existing resolve()-based fixture keeps behaving exactly as it did before that method
        // existed.
        lenient().when(shiftVersionResolver.resolveIfPresent(any(), any())).thenAnswer(inv -> {
            try {
                return Optional.of(shiftVersionResolver.resolve(inv.getArgument(0), inv.getArgument(1)));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        });
        lenient().when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(Employee.builder().userId(employeeId).shift(defaultShift).build()));
        // Super Admin also holds EMPLOYEE (see superAdminUser above) and is used as the
        // regularization subject in several literal-date scenarios below — resolves to an
        // Employee with the same defaultShift so the workday-validation lookup
        // (RegularizationService#resolveTimes -> AttendanceInterpretationService#belongsToWorkday)
        // has a real shift context to resolve against, exactly like a real Super Admin employee
        // record would.
        lenient().when(employeeRepository.findById(superAdminId))
                .thenReturn(Optional.of(Employee.builder().userId(superAdminId).shift(defaultShift).build()));
        lenient().when(employeeRepository.findById(argThat(id -> id != null && !id.equals(employeeId) && !id.equals(superAdminId))))
                .thenReturn(Optional.empty());
        lenient().when(regularizationApprovalRepository.findByRequestIdOrderByActionDateDesc(any()))
                .thenReturn(List.of());
        lenient().when(regularizationRepository.save(any(RegularizationRequest.class)))
                .thenAnswer(inv -> {
                    RegularizationRequest r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return r;
                });
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(java.math.BigDecimal.valueOf(18)).build()));
        currentShiftByEmployee.put(employeeId, defaultShift);
        currentShiftByEmployee.put(superAdminId, defaultShift);
        EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver = new EmployeeShiftAssignmentResolver(null) {
            @Override
            public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
                Shift s = currentShiftByEmployee.get(employeeUserId);
                return s == null ? Optional.empty()
                        : Optional.of(EmployeeShiftAssignment.builder()
                                .employeeUserId(employeeUserId).shift(s).effectiveFrom(LocalDate.MIN).build());
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
                        .halfDayMaxHours(java.math.BigDecimal.valueOf(4.0))
                        // Matches the system default zone so LocalDate.now(ZoneId.of(...)) in the
                        // service agrees with the plain LocalDate.now() used throughout these tests
                        // (previously attendanceProps.getZone()'s identical stub).
                        .defaultTimezone(java.time.ZoneId.systemDefault().getId())
                        .build()));
        attendanceRulesService = new AttendanceRulesService(attendanceRulesRepository);
        // Resolves any Shift referenced by an Attendance fixture's own .shiftId(...) — mirrors the
        // real ShiftRepository for AttendanceInterpretationService.interpretExistingRecordLateness.
        // Defaults to resolving defaultShift itself (declared just above), since every existing-
        // record approve() test in this file currently stubs an empty Attendance lookup (always
        // hitting the brand-new-record/interpretForKnownWorkDate path) rather than an existing one.
        lenient().when(shiftRepository.findById(defaultShift.getId())).thenReturn(Optional.of(defaultShift));
        attendanceInterpretationService = new AttendanceInterpretationService(shiftDayPolicy, shiftRepository, employeeShiftAssignmentResolver);

        // Default Clock for every test that doesn't care about the future-timestamp guard (see
        // RegularizationService#resolveTimes): a fixed instant a decade past whatever "now" is
        // when each test runs, so every one of this file's many `LocalDate.now()`-based fixtures
        // (a stand-in for "some arbitrary calendar date," not literally "this exact moment") reads
        // as safely historical regardless of the real wall-clock time-of-day the suite happens to
        // run at. The dedicated "future-timestamp guard" tests below call useClock(...) themselves
        // with a specific fixed reference instant instead.
        useClock(Clock.fixed(Instant.now().plus(Duration.ofDays(3650)), ZoneOffset.UTC));

        // Default for existing tests that don't care about the monthly cap — most submit()
        // tests use today's date and don't stub this, so let it resolve to a lenient 0.
        lenient().when(regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
    }

    /**
     * (Re)builds regularizationService against the given Clock, reusing every other
     * already-configured mock/collaborator — lets an individual test swap in a specific fixed
     * "now" for RegularizationService#resolveTimes's future-timestamp guard without disturbing
     * anything else setUp() already stubbed. Also re-applies the two @Value-injected fields,
     * since those live on the RegularizationService instance itself and are lost on rebuild.
     */
    private void useClock(Clock clock) throws Exception {
        regularizationService = new RegularizationService(regularizationRepository, regularizationApprovalRepository,
                attendanceRepository, historyRepository, userRepository, employeeRepository, auditService,
                auditSnapshot, notificationService, exceptionService, attendanceInterpretationService,
                attendanceRulesService, clock);

        // @Value-injected fields — never populated outside a Spring container.
        Field employeeLookback = RegularizationService.class.getDeclaredField("employeeLookbackDays");
        employeeLookback.setAccessible(true);
        employeeLookback.set(regularizationService, 3);

        Field monthlyLimit = RegularizationService.class.getDeclaredField("monthlyLimit");
        monthlyLimit.setAccessible(true);
        monthlyLimit.set(regularizationService, 3);
    }

    private CreateRegularizationRequest request(LocalDate date, LocalDateTime checkIn, LocalDateTime checkOut, String reason) {
        return CreateRegularizationRequest.builder()
                .attendanceDate(date).requestedCheckIn(checkIn).requestedCheckOut(checkOut).reason(reason).build();
    }

    @Test
    void submit_withNoManagerSelected_assignsCurrentManager() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        // assertNoDuplicateRequest checks APPROVED, then PARTIALLY_APPROVED, then PENDING —
        // all three need stubbing under strict-stub mode, not just the one this test cares about.
        lenient().when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, LocalDate.now(), "PENDING"))
                .thenReturn(false);

        LocalDate today = LocalDate.now();
        RegularizationResponse resp = regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(managerId, resp.getAssignedApproverId());
        verify(auditService).log(employeeId, "REGULARIZATION_REQUESTED", employeeId);
    }

    @Test
    void submit_withSelectedEligibleManager_assignsSelection() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(9, 0), today.atTime(18, 0), "Missed punch");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals(hrId, resp.getAssignedApproverId());
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void submit_withIneligibleSelectedUser_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(9, 0), today.atTime(18, 0), "Missed punch");
        req.setManagerUserId(strangerId); // plain EMPLOYEE role — not an eligible approver

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(req, employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_missingCheckOut_autoFillsCheckInFromExistingPunch() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        LocalDateTime existingCheckIn = today.atTime(9, 32);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(existingCheckIn).build()));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        // Client only supplies the missing check-out — check-in must be auto-filled server-side.
        CreateRegularizationRequest req = request(today, null, today.atTime(18, 30), "Forgot to punch out");
        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals(existingCheckIn, resp.getRequestedCheckIn());
        assertEquals(today.atTime(18, 30), resp.getRequestedCheckOut());
    }

    // ---------------------------------------------------------------- overnight check-in/check-out

    @Test
    void submit_overnightShift_330pmTo1230am_isAcceptedAndCheckoutRollsToNextDay() {
        // Scenario F: check-in's workday (today, since defaultShift's own 09:00 start has already
        // been reached) and the rolled-over check-out's workday (also today, since 00:30 falls
        // before today's 03:00 workday boundary — 09:00 + 18h max — so it's still part of today's
        // overnight tail) agree — both belong to attendanceDate=today — so this passes the
        // workday-consistency check. The actual stored check-out timestamp remains the real
        // next-calendar-day value; only its WORKDAY attribution rolls back, not the timestamp itself.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        // The frontend always sends both times on the same attendanceDate (RequestModal in
        // AttendancePage.tsx never does its own day-rollover) — 12:30 AM here is check-out's
        // clock time on that same date, exactly as the real request payload looks.
        CreateRegularizationRequest req = request(today, today.atTime(15, 30), today.atTime(0, 30), "Overnight shift");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(today.atTime(15, 30), resp.getRequestedCheckIn());
        assertEquals(today.plusDays(1).atTime(0, 30), resp.getRequestedCheckOut());
    }

    @Test
    void scenarioG_overnightCheckoutPast3AMWorkdayBoundary_isRejected() {
        // Same shape as scenario F, but past the ACTUAL shift-aware workday boundary this time:
        // defaultShift is 9:00-18:00 with an 18h max workday duration (see setUp), so the workday
        // this check-in (20:00) belongs to ends at 03:00 the next calendar day — never a fixed
        // 07:00 AM cutover. Check-out rolls over to next-day 06:59, which is PAST that 03:00
        // boundary, so it genuinely belongs to the NEXT workday and must be rejected.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(20, 0), today.atTime(6, 59), "Overnight shift, past the workday boundary");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void scenarioG2_overnightCheckoutJustBeforeTheWorkdayBoundary_isAccepted() {
        // The mirror case: a check-out just BEFORE the same 03:00 workday boundary (defaultShift
        // 9:00-18:00, 18h max) still belongs to the same workday as the 20:00 check-in.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(20, 0), today.atTime(2, 59), "Overnight shift, within the workday");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(today.atTime(20, 0), resp.getRequestedCheckIn());
        assertEquals(today.plusDays(1).atTime(2, 59), resp.getRequestedCheckOut());
    }

    @Test
    void scenarioH_checkoutOnlyAtSevenAM_businessDateIsItsOwnDay() {
        // Check-out submitted alone (no check-in in this request, none on file either) — at
        // exactly 07:00 it belongs to its OWN calendar date as the business date (rule 3), not
        // the previous one, so it must be validated against that same day's attendanceDate.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, null, today.atTime(7, 0), "Checkout-only correction");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertNull(resp.getRequestedCheckIn());
        assertEquals(today.atTime(7, 0), resp.getRequestedCheckOut());
    }

    @Test
    void scenario8_overnightShift_17AugTo18Aug_literalDates_attendanceDateIs17AugAndCheckoutStays18Aug() {
        // Literal Aug-17/18-2026 dates per the spec's exact example. Submitted as the Super Admin
        // fixture (also holds EMPLOYEE, exempt from the lookback window) purely so this test can
        // use fixed calendar dates instead of "today" — the business-date/rollover logic under
        // test doesn't care who the actor is.
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate aug17 = LocalDate.of(2026, 8, 17);
        CreateRegularizationRequest req = request(aug17, aug17.atTime(15, 30), aug17.atTime(0, 30), "Overnight shift");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(aug17, resp.getAttendanceDate());
        assertEquals(LocalDateTime.of(2026, 8, 17, 15, 30), resp.getRequestedCheckIn());
        // Checkout must remain the real next calendar day — never rewritten back to 17-Aug.
        assertEquals(LocalDateTime.of(2026, 8, 18, 0, 30), resp.getRequestedCheckOut());
    }

    @Test
    void scenario9_overnightCheckoutAt659am_18Aug_literalDates_isRejectedPastTheWorkdayBoundary() {
        // Literal-dated counterpart to scenarioG above: 6:59 AM 18-Aug is past the 03:00 workday
        // boundary a 9:00-18:00/18h-max shift produces, so it belongs to 18-Aug's own workday, not
        // 17-Aug's — a genuine mismatch with the requested attendanceDate.
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate aug17 = LocalDate.of(2026, 8, 17);
        CreateRegularizationRequest req = request(aug17, aug17.atTime(20, 0), aug17.atTime(6, 59), "Overnight shift, past the workday boundary");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, superAdminEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
    }

    // ── Workday-aware validation (ShiftDayPolicy), replacing the old fixed calendar-date/07:00
    //    boundary check — the exact spec worked example: a 10:00-19:00 shift with an 18h maximum
    //    workday duration spans workday 04:00 -> 04:00 the NEXT calendar day. ──────────────────

    /** Overrides defaultShift's 9:00-18:00 stub with the spec's own 10:00-19:00 shift for one test. */
    private void stubShift10to19() {
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(
                ShiftVersion.builder().startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(19, 0)).lateGraceMinutes(10).build());
    }

    @Test
    void submit_missingCheckout_correctedPostMidnightCheckout_isAccepted_theExactReportedBug() {
        // The exact bug report: an attendance with a missing checkout, corrected with a
        // post-midnight checkout that still belongs to the SAME workday (04:00 -> 04:00 next day
        // for a 10:00-19:00 shift) — must no longer be rejected as "not falling on the attendance
        // date".
        stubShift10to19();
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        LocalDateTime existingCheckIn = today.atTime(9, 25);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(existingCheckIn).shiftId(defaultShift.getId()).build()));

        CreateRegularizationRequest req = request(today, null, today.atTime(0, 21), "Forgot to punch out");
        req.setManagerUserId(hrId);
        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(today.plusDays(1).atTime(0, 21), resp.getRequestedCheckOut());
    }

    @Test
    void submit_missingCheckout_correctedCheckoutAt330amWithinTheWorkday_isAccepted() {
        stubShift10to19();
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(today.atTime(9, 25)).shiftId(defaultShift.getId()).build()));

        CreateRegularizationRequest req = request(today, null, today.atTime(3, 30), "Forgot to punch out");
        req.setManagerUserId(hrId);
        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals(today.plusDays(1).atTime(3, 30), resp.getRequestedCheckOut());
    }

    @Test
    void submit_correctedCheckoutExactlyAtTheWorkdayEnd_isRejected() {
        // 04:00 next day is exactly workdayEndAt — ShiftDayPolicy already attributes that instant
        // (and everything after) to the NEXT workday, never the originating one.
        stubShift10to19();
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(today.atTime(9, 25)).shiftId(defaultShift.getId()).build()));

        CreateRegularizationRequest req = request(today, null, today.atTime(4, 0), "Forgot to punch out");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
    }

    @Test
    void submit_correctedCheckoutAfterTheWorkdayEnd_isRejected() {
        stubShift10to19();
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(today.atTime(9, 25)).shiftId(defaultShift.getId()).build()));

        CreateRegularizationRequest req = request(today, null, today.atTime(5, 0), "Forgot to punch out");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
    }

    @Test
    void submit_correctedCheckIn_postMidnightWithinAnOvernightShiftsWorkday_isAccepted() {
        // No prior punch exists yet for this date — resolves against the EMPLOYEE's current shift
        // (interpretForKnownWorkDate's own rule), a genuinely overnight one: 22:00-07:00, 18h max
        // -> workday 16:00 -> 16:00 next day. A brand-new check-in correction at 05:00 the NEXT
        // calendar day is still within that window (before the 16:00 boundary), so it's genuinely
        // a post-midnight arrival that still belongs to TODAY's workday.
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(
                ShiftVersion.builder().startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(7, 0)).lateGraceMinutes(10).build());
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(today, today.plusDays(1).atTime(5, 0), null, "Night shift check-in, post-midnight");
        req.setManagerUserId(hrId);
        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(today.plusDays(1).atTime(5, 0), resp.getRequestedCheckIn());
    }

    @Test
    void submit_correctedCheckIn_beforeAnOvernightShiftsWorkdayStart_belongsToThePreviousWorkday_isRejected() {
        // Same 22:00-07:00/18h-max shift (workday 16:00 -> 16:00 next day) — a check-in correction
        // at 10:00 on the attendance date itself is BEFORE that date's own 16:00 workday start
        // (it's still the tail of the PREVIOUS workday, which doesn't end until 16:00), so it
        // mismatches the requested attendanceDate.
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(
                ShiftVersion.builder().startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(7, 0)).lateGraceMinutes(10).build());
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(today, today.atTime(10, 0), null, "Too early to belong to today's workday");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-in time must fall within the attendance workday ("));
    }

    /**
     * The corrected-checkout side of the same "record's own snapshotted shift, never the
     * employee's current one" guarantee {@link #approve_correctingAnExistingRecord_usesItsOwnSnapshottedShift_notTheEmployeesCurrentOne}
     * already proves at approval time — here proven at SUBMISSION/validation time instead.
     * existingPunch is snapshotted under defaultShift (9:00-18:00, workday boundary 03:00), but the
     * employee has SINCE been reassigned to a 10:00-19:00 shift (workday boundary 04:00) — a
     * 03:30 AM correction must be validated against the RECORD's own 03:00 boundary (rejected),
     * never the employee's new 04:00 one (which would have accepted it).
     */
    @Test
    void submit_correctingAnExistingRecord_validatesAgainstItsOwnSnapshottedShift_notTheEmployeesCurrentOne() {
        Shift newShift = Shift.builder().id(UUID.randomUUID()).name("New Shift").active(true).build();
        ShiftVersion oldVersion = ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).lateGraceMinutes(15).build();
        ShiftVersion newVersion = ShiftVersion.builder().startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(19, 0)).lateGraceMinutes(10).build();
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenAnswer(inv -> {
            Shift s = inv.getArgument(0);
            return s.getId().equals(newShift.getId()) ? newVersion : oldVersion;
        });
        // Employee reassigned since the record's own date — CURRENT shift is the new one.
        lenient().when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(Employee.builder().userId(employeeId).shift(newShift).build()));

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(today.atTime(9, 25)).shiftId(defaultShift.getId()).build()));

        CreateRegularizationRequest req = request(today, null, today.atTime(3, 30), "Forgot to punch out");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
    }

    @Test
    void submit_normalSameDayInterval_330pmTo1130pm_isUnaffected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(15, 30), today.atTime(23, 30), "Normal shift");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(today.atTime(15, 30), resp.getRequestedCheckIn());
        assertEquals(today.atTime(23, 30), resp.getRequestedCheckOut()); // same date — no rollover applied
    }

    @Test
    void submit_checkoutClockTimeWayPastTheWorkdayBoundary_isRejectedForWorkdayMismatch() {
        // Check-out's clock time (3:00 PM) is earlier than check-in's (3:30 PM), so the same-day
        // rollover fix (above) still shifts it to the next calendar day. But 3:00 PM the next day
        // is itself well past the workday boundary a 9:00-18:00/18h-max shift produces (03:00 the
        // next day), so it genuinely belongs to the NEXT workday, not check-in's — the two sides
        // disagree on which attendanceDate this request belongs to. A genuinely valid overnight
        // case never has this problem (its check-out always lands before the workday boundary);
        // this ~23.5h interval is the case that boundary is NOT meant to cover, and must still fail.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(15, 30), today.atTime(15, 0), "Long overnight shift");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertTrue(ex.getMessage().startsWith("Corrected check-out time must fall within the attendance workday ("));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_equalCheckInAndCheckOutTimes_isGenuinelyInvalid() {
        // Not "earlier than" check-in (equal, not before) — no rollover applies, so this stays a
        // same-day, zero-duration interval and is correctly rejected by the existing ordering rule.
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(15, 30), today.atTime(15, 30), "Same time");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, employeeEmail));
        assertEquals("Check-out time must be after check-in time", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_whenApprovedRequestAlreadyExistsForDate_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "APPROVED"))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail));

        assertEquals("Already raised regularization for this date.", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_whenPartiallyApprovedRequestAlreadyExistsForDate_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        // The APPROVED check runs first (and is unstubbed here — returns false by default);
        // only the PARTIALLY_APPROVED check this test targets needs a real stub.
        lenient().when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "APPROVED"))
                .thenReturn(false);
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "PARTIALLY_APPROVED"))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_byNonSuperAdmin_beyond3DayWindow_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate tooOld = LocalDate.now().minusDays(4); // employeeLookbackDays is 3 in setUp()

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(tooOld, tooOld.atTime(9, 0), tooOld.atTime(18, 0), "Old correction"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    /**
     * windowDays counts today itself as one of the allowed days: with employeeLookbackDays=3,
     * today/-1/-2 are allowed and -3 onward is blocked (Requirement 1's Case 1/2 date-window
     * examples — today=6th allows 6th/5th/4th, blocks 3rd onward).
     */
    @Test
    void submit_byNonSuperAdmin_atLookbackBoundary_isAllowed() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate boundary = LocalDate.now().minusDays(2); // last day still inside the 3-day window

        RegularizationResponse resp = regularizationService.submit(
                request(boundary, boundary.atTime(9, 0), boundary.atTime(18, 0), "Within window"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        verify(regularizationRepository).save(any());
    }

    @Test
    void submit_byNonSuperAdmin_justOutsideLookbackBoundary_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate justOutside = LocalDate.now().minusDays(3); // one day past the 3-day window

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(justOutside, justOutside.atTime(9, 0), justOutside.atTime(18, 0), "Too old"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_byManager_isBoundByLookbackWindow() {
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        LocalDate justOutside = LocalDate.now().minusDays(3);

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(justOutside, justOutside.atTime(9, 0), justOutside.atTime(18, 0), "Too old"), managerEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_byHrAdmin_isBoundByLookbackWindow() {
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        LocalDate justOutside = LocalDate.now().minusDays(3);

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(justOutside, justOutside.atTime(9, 0), justOutside.atTime(18, 0), "Too old"), hrEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_bySuperAdmin_bypassesLookbackWindow() {
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate tooOld = LocalDate.now().minusDays(10);

        RegularizationResponse resp = regularizationService.submit(
                request(tooOld, tooOld.atTime(9, 0), tooOld.atTime(18, 0), "Old correction"), superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submit_byNonSuperAdmin_atMonthlyLimit_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(eq(employeeId), any(), any()))
                .thenReturn(3L); // monthlyLimit is 3 in setUp()

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Another one"), employeeEmail));

        assertTrue(ex.getMessage().contains("maximum"));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_bySuperAdmin_exemptFromMonthlyLimit() {
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate today = LocalDate.now();

        RegularizationResponse resp = regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Yet another"), superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
        verify(regularizationRepository, never()).countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void update_doesNotConsumeMonthlyLimitSlot() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        regularizationService.update(pending.getId(),
                request(date, date.atTime(9, 15), date.atTime(18, 15), "Updated reason"), employeeEmail);

        verify(regularizationRepository, never()).countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void submit_bothTimesMissingWithNoExistingPunch_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        // resolveTimes() rejects a both-null request before ever consulting attendanceRepository,
        // so no stub is needed for it here.

        assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(request(today, null, null, "Nothing on file"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    // ── Future-timestamp guard (RegularizationService#resolveTimes): a corrected check-in/
    //    check-out must describe something that has already happened, read in the employee's own
    //    business zone — never later than the authoritative server clock. Uses the Super Admin
    //    fixture throughout (lookback-window-exempt, see submit_bySuperAdmin_bypassesLookbackWindow)
    //    so each test can pin a fully fixed, fictitious reference "now" via useClock(...) —
    //    deterministic regardless of the real wall-clock time-of-day `mvn test` actually runs at. ──

    /** A Clock permanently fixed at {@code at}, read in the same zone attendanceRulesService's
     * defaultTimezone stub resolves to (see setUp()) — so resolveTimes()'s own
     * {@code LocalDateTime.ofInstant(clock.instant(), zone)} reconstructs exactly {@code at}
     * again, regardless of the machine's own real timezone offset. */
    private Clock fixedClockAt(LocalDateTime at) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(at.atZone(zone).toInstant(), zone);
    }

    @Test
    void submit_futureGuard_pastSameDayTimes_areAccepted() throws Exception {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 15, 18, 0);
        useClock(fixedClockAt(fixedNow));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate attendanceDate = fixedNow.toLocalDate();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(attendanceDate, fixedNow.minusHours(9), fixedNow.minusHours(1), "Forgot badge");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(fixedNow.minusHours(9), resp.getRequestedCheckIn());
        assertEquals(fixedNow.minusHours(1), resp.getRequestedCheckOut());
    }

    @Test
    void submit_futureGuard_historicalDate_isAccepted() throws Exception {
        // A genuinely historical correction's own resolved instant is, by construction, already
        // in the past relative to "now" — never rejected by the future-timestamp guard, no matter
        // how far back (separately bounded by the lookback window, which Super Admin is exempt
        // from — see submit_bySuperAdmin_bypassesLookbackWindow).
        LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 15, 18, 0);
        useClock(fixedClockAt(fixedNow));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate attendanceDate = fixedNow.toLocalDate().minusDays(30);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(attendanceDate, attendanceDate.atTime(9, 0), attendanceDate.atTime(18, 0), "Old correction");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submit_futureGuard_sameDayCheckInLaterThanNow_isRejected() throws Exception {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 15, 18, 0);
        useClock(fixedClockAt(fixedNow));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate attendanceDate = fixedNow.toLocalDate();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(attendanceDate, fixedNow.plusHours(2), null, "Hasn't happened yet");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, superAdminEmail));
        assertEquals("Corrected check-in time cannot be later than the current time", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_futureGuard_sameDayCheckOutLaterThanNow_isRejected() throws Exception {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 15, 18, 0);
        useClock(fixedClockAt(fixedNow));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate attendanceDate = fixedNow.toLocalDate();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        // Check-in (9:00) already happened; the requested check-out (19:00) hasn't — proves the
        // two sides are validated independently, not as one all-or-nothing pair.
        CreateRegularizationRequest req = request(attendanceDate, attendanceDate.atTime(9, 0), fixedNow.plusHours(1), "Still clocked in");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, superAdminEmail));
        assertEquals("Corrected check-out time cannot be later than the current time", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    /** Shared 22:00-07:00 overnight shift setup for the two tests below — same shape as
     * submit_correctedCheckIn_postMidnightWithinAnOvernightShiftsWorkday_isAccepted above. */
    private void stubOvernightShift2200to0700() {
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenReturn(
                ShiftVersion.builder().startTime(LocalTime.of(22, 0)).endTime(LocalTime.of(7, 0)).lateGraceMinutes(10).build());
    }

    @Test
    void submit_futureGuard_overnightCheckoutAlreadyPast_isAccepted() throws Exception {
        stubOvernightShift2200to0700();
        LocalDate attendanceDate = LocalDate.of(2026, 6, 15);
        // "Now" is well after the overnight session's own end — the whole corrected interval,
        // check-in through the post-midnight checkout, already genuinely happened.
        useClock(fixedClockAt(attendanceDate.plusDays(1).atTime(10, 0)));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        // Frontend convention: both submitted on the same attendanceDate; check-out's earlier
        // clock time triggers the existing same-day rollover onto the next calendar day.
        CreateRegularizationRequest req = request(attendanceDate, attendanceDate.atTime(22, 0), attendanceDate.atTime(5, 0), "Overnight shift");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(attendanceDate.atTime(22, 0), resp.getRequestedCheckIn());
        assertEquals(attendanceDate.plusDays(1).atTime(5, 0), resp.getRequestedCheckOut());
    }

    @Test
    void submit_futureGuard_overnightCheckoutNotYetHappened_isRejected() throws Exception {
        stubOvernightShift2200to0700();
        LocalDate attendanceDate = LocalDate.of(2026, 6, 15);
        // "Now" is 2 AM the next day: the 22:00 check-in already happened, but the claimed 5 AM
        // checkout is still three hours away — must be rejected even though the check-in side,
        // checked independently, is perfectly valid on its own.
        useClock(fixedClockAt(attendanceDate.plusDays(1).atTime(2, 0)));
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(superAdminId, attendanceDate)).thenReturn(Optional.empty());

        CreateRegularizationRequest req = request(attendanceDate, attendanceDate.atTime(22, 0), attendanceDate.atTime(5, 0), "Overnight shift");
        req.setManagerUserId(hrId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(req, superAdminEmail));
        assertEquals("Corrected check-out time cannot be later than the current time", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void approve_byAssignedManager_transitionsDirectlyToApproved() {
        // A Manager's approval is now final on its own — no HR/Super Admin sign-off follows.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(pending.getId(), null, managerEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals(managerId, pending.getReviewedBy());
        assertNull(pending.getApprovedBy()); // no separate manager-stage marker — this is the final decision
        assertEquals(managerId, pending.getFinalApprovedBy());
        assertNotNull(pending.getFinalApprovedAt());
        verify(attendanceRepository).save(any(Attendance.class));
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getRequestId().equals(pending.getId()) && a.getActionType().equals("APPROVED")
                        && a.getActionBy().equals(managerId) && "MANAGER".equals(a.getActorRole())));
        verify(auditService).log(managerId, "REGULARIZATION_APPROVED", employeeId);
    }

    /**
     * A brand-new Attendance row (no prior punch existed for this date) must snapshot the
     * employee's CURRENT Shift — there is no prior context to preserve for a date that was never
     * punched. See AttendanceInterpretationService.interpretForKnownWorkDate.
     */
    @Test
    void approve_createsANewRecord_snapshotsTheEmployeesCurrentShift() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        verify(attendanceRepository).save(argThat(a -> defaultShift.getId().equals(a.getShiftId())));
    }

    /**
     * A brand-new Attendance row created via Regularization must also snapshot the employee's
     * effective (Location-derived) timezone, exactly like a normal Check-In/Web Clock-In does —
     * see AttendanceRulesService#resolveEmployeeZoneId and Attendance#getTimezone's own doc
     * comment. Before the finalized Location/Timezone consolidation this snapshot was silently
     * skipped entirely for a Regularization-created row (Attendance.timezone stayed null).
     */
    @Test
    void approve_createsANewRecord_snapshotsTheEmployeesEffectiveLocationTimezone() {
        LocalDate date = LocalDate.now();
        com.nforce.onehr.entity.Location office = com.nforce.onehr.entity.Location.builder()
                .name("Chicago").timezone("America/Chicago").build();
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(Employee.builder().userId(employeeId).shift(defaultShift).location(office).build()));
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        verify(attendanceRepository).save(argThat(a -> "America/Chicago".equals(a.getTimezone())));
    }

    /**
     * An EXISTING legacy row (predates the shiftId snapshot column, {@code shiftId == null})
     * cannot be safely recomputed for lateness — this must fail loudly and explicitly rather than
     * substitute the employee's current Shift or present a guessed number as reliable. See
     * AttendanceInterpretationService's own LEGACY_UNRESOLVED handling.
     */
    @Test
    void approve_existingLegacyRecordWithNoShiftSnapshot_throwsRatherThanGuessingLateness() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 5)).requestedCheckOut(date.atTime(18, 0))
                .reason("Wrong check-in time").status("PENDING").build();
        Attendance existingLegacyRecord = Attendance.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(18, 0))
                .shiftId(null) // predates the shiftId column
                .build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date))
                .thenReturn(Optional.of(existingLegacyRecord));

        assertThrows(IllegalStateException.class,
                () -> regularizationService.approve(pending.getId(), null, managerEmail));
        verify(attendanceRepository, never()).save(any(Attendance.class));
    }

    /**
     * Historical Attendance under Shift A, employee since reassigned to Shift B, then this record
     * is regularization-corrected: lateness must still be computed against Shift A (the record's
     * own snapshot) — never Shift B, even though {@code employee.getShift()} now returns B. See
     * AttendanceInterpretationService.interpretExistingRecordLateness.
     */
    @Test
    void approve_correctingAnExistingRecord_usesItsOwnSnapshottedShift_notTheEmployeesCurrentOne() {
        LocalDate date = LocalDate.now();
        Shift shiftB = Shift.builder().id(UUID.randomUUID()).name("Day Shift B").active(true).build();
        ShiftVersion shiftAVersion = ShiftVersion.builder().shift(defaultShift).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).lateGraceMinutes(15).build();
        ShiftVersion shiftBVersion = ShiftVersion.builder().shift(shiftB).startTime(LocalTime.of(6, 0)).endTime(LocalTime.of(14, 0)).lateGraceMinutes(15).build();
        // Overrides setUp()'s blanket any()/any() stub with shift-discriminating behavior — Shift
        // A and Shift B must resolve to DIFFERENT timings for this test to actually prove which
        // one governed, rather than passing regardless by coincidence.
        lenient().when(shiftVersionResolver.resolve(any(), any())).thenAnswer(inv -> {
            Shift s = inv.getArgument(0);
            return s.getId().equals(shiftB.getId()) ? shiftBVersion : shiftAVersion;
        });
        // Reassigned since this record's date: the CURRENT employee now resolves to Shift B.
        lenient().when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(Employee.builder().userId(employeeId).shift(shiftB).build()));
        // Shift A (this record's own snapshot, 9:00-18:00) must still be what's resolved — never Shift B.
        lenient().when(shiftRepository.findById(defaultShift.getId())).thenReturn(Optional.of(defaultShift));

        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 20)).requestedCheckOut(date.atTime(18, 0))
                .reason("Wrong check-in time").status("PENDING").build();
        Attendance existingRecord = Attendance.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(18, 0))
                .shiftId(defaultShift.getId())
                .build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date))
                .thenReturn(Optional.of(existingRecord));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        // 9:20 is BEFORE Shift A's 9:00+15min-grace deadline... use the actual configured grace
        // (15 in this test's setUp) — 9:20 is 20 minutes past 9:00, past the 15-minute grace, so
        // this should be LATE under Shift A. Under Shift B (6:00-14:00) it would be over 3 hours
        // late instead — the shiftId snapshot (defaultShift, 9:00 start) is what must govern.
        assertEquals(defaultShift.getId(), existingRecord.getShiftId(), "existing record's shiftId must never be replaced");
        assertEquals("LATE", existingRecord.getStatus());
    }

    // ── Phase 0.1: before/after Attendance audit snapshot on approve() ───────

    @Test
    @SuppressWarnings("unchecked")
    void approve_correctingAnExistingRecord_capturesTheCompleteBeforeAndAfterAttendanceState_includingSource() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 20)).requestedCheckOut(date.atTime(18, 0))
                .reason("Wrong check-in time").status("PENDING").build();
        // The record's ORIGINAL state before correction — source SYSTEM (a normal punch), status
        // PRESENT — is exactly what must be captured in the "before" snapshot, since approve()'s
        // own record.setSource(SOURCE_REGULARIZATION) is about to overwrite it.
        Attendance existingRecord = Attendance.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(18, 0))
                .status("PRESENT").lateByMinutes(15).workedMinutes(510).source("SYSTEM")
                .shiftId(defaultShift.getId())
                .build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date))
                .thenReturn(Optional.of(existingRecord));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditSnapshot, atLeastOnce()).toJson(snapshotCaptor.capture());
        List<Map<String, Object>> snapshots = snapshotCaptor.getAllValues();
        // The specific pair this test cares about: one snapshot with existed=true/source=SYSTEM
        // (the "before"), and one with source=REGULARIZATION (the "after") — captured somewhere
        // among all toJson() calls this approve() invocation makes (update()/reject() aren't
        // reached here, but other snapshot calls in this codepath are irrelevant noise).
        boolean hasBeforeWithOriginalSource = snapshots.stream().anyMatch(s ->
                Boolean.TRUE.equals(s.get("existed")) && "SYSTEM".equals(s.get("source"))
                        && "PRESENT".equals(s.get("status")));
        boolean hasAfterWithRegularizationSource = snapshots.stream().anyMatch(s ->
                "REGULARIZATION".equals(s.get("source")));
        assertTrue(hasBeforeWithOriginalSource, "before-snapshot must preserve the record's ORIGINAL source (SYSTEM), not the overwritten value");
        assertTrue(hasAfterWithRegularizationSource, "after-snapshot must reflect the corrected source (REGULARIZATION)");

        verify(auditService).log(eq(managerId), eq("ATTENDANCE_REGULARIZED"), eq(existingRecord.getId()), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void approve_forABrandNewRecord_capturesExistedFalseAsTheBeforeState() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditSnapshot, atLeastOnce()).toJson(snapshotCaptor.capture());
        boolean hasExistedFalseBefore = snapshotCaptor.getAllValues().stream()
                .anyMatch(s -> Boolean.FALSE.equals(s.get("existed")));
        assertTrue(hasExistedFalseBefore, "a brand-new row's before-state must be an explicit existed=false, not an empty/null map");
    }

    /**
     * The audit trail's whole point: TWO separate corrections to the same date must each leave
     * their own before/after pair — reconstructing the record's state after correction #1 must
     * remain possible even after correction #2 has since changed it again.
     */
    @Test
    @SuppressWarnings("unchecked")
    void approve_appliedTwiceToTheSameDate_retainsBothCorrectionsInAuditHistory_notJustTheLatest() {
        LocalDate date = LocalDate.now();
        Attendance record = Attendance.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30)).checkOutAt(date.atTime(18, 0))
                .status("PRESENT").lateByMinutes(15).workedMinutes(510).source("SYSTEM")
                .shiftId(defaultShift.getId())
                .build();
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.of(record));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationRequest firstCorrection = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 20)).requestedCheckOut(date.atTime(18, 0))
                .reason("First correction").status("PENDING").build();
        when(regularizationRepository.findById(firstCorrection.getId())).thenReturn(Optional.of(firstCorrection));
        regularizationService.approve(firstCorrection.getId(), null, managerEmail);

        RegularizationRequest secondCorrection = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 45)).requestedCheckOut(date.atTime(18, 0))
                .reason("Second correction").status("PENDING").build();
        when(regularizationRepository.findById(secondCorrection.getId())).thenReturn(Optional.of(secondCorrection));
        regularizationService.approve(secondCorrection.getId(), null, managerEmail);

        // Every audit call is a fresh INSERT (see AuditService.log — a new builder().build() is
        // always saved, never updated), so two separate calls are exactly what preserves both
        // corrections independently, rather than one being silently overwritten by the other.
        verify(auditService, times(2)).log(eq(managerId), eq("ATTENDANCE_REGULARIZED"), eq(record.getId()), any(), any());
    }

    @Test
    void approve_byManagerNotAssigned_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        // strangerUser holds only EMPLOYEE — approve()'s status-first branching denies it before
        // ever reaching assertCanReview's historyRepository fallback, so no stub needed there.

        assertThrows(AccessDeniedException.class, () -> regularizationService.approve(pending.getId(), null, strangerEmail));
        verify(regularizationRepository, never()).save(any());
        verify(regularizationApprovalRepository, never()).save(any());
    }

    @Test
    void approve_byHrAdmin_bypassesFromPending_directlyToApproved() {
        // ONEHR-140 follow-up: HR_ADMIN now has the same PENDING-stage bypass SUPER_ADMIN
        // already had — need not be the employee's manager, and may act before the manager does.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(pending.getId(), null, hrEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertNull(pending.getApprovedBy()); // bypass skips the manager stage entirely, same as SUPER_ADMIN
        assertEquals(hrId, pending.getFinalApprovedBy());
        verify(regularizationApprovalRepository).save(argThat(a -> "HR_ADMIN".equals(a.getActorRole())));
        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_APPROVED"), any(), any(), any());
    }

    @Test
    void approve_byHrAdmin_calledTwice_secondCallRejectedAndNoDuplicateNotification() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, hrEmail);
        // The request is now APPROVED (terminal) in-memory, so a second decision attempt falls
        // into the else-branch "only pending or partially-approved" guard.
        assertThrows(IllegalArgumentException.class, () -> regularizationService.approve(pending.getId(), null, hrEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_APPROVED"), any(), any(), any());
    }

    @Test
    void approve_byHrAdmin_onPartiallyApproved_transitionsToApproved() {
        LocalDate date = LocalDate.now();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED")
                .approvedBy(managerId).approvedAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(partiallyApproved.getId(), null, hrEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals(hrId, partiallyApproved.getFinalApprovedBy());
        assertNotNull(partiallyApproved.getFinalApprovedAt());
        // Stage 1 fields set earlier must survive the stage-2 transition untouched.
        assertEquals(managerId, partiallyApproved.getApprovedBy());
        verify(regularizationApprovalRepository).save(argThat(a -> "HR_ADMIN".equals(a.getActorRole())));
        verify(auditService).log(hrId, "REGULARIZATION_APPROVED", employeeId);
    }

    @Test
    void approve_bySuperAdmin_bypassesFromPending_directlyToApproved() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(pending.getId(), null, superAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertNull(pending.getApprovedBy()); // bypass skips the manager stage entirely
        assertEquals(superAdminId, pending.getFinalApprovedBy());
        verify(regularizationApprovalRepository).save(argThat(a -> "SUPER_ADMIN".equals(a.getActorRole())));
    }

    // ── Section 16: approving a regularization must trigger the corresponding penalty reversal ──

    @Test
    void approve_reachingTerminalApproved_triggersPenaltyReversalForThatEmployeeAndDate() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, superAdminEmail);

        verify(exceptionService).reevaluateAndReverseIfInvalid(eq(employeeId), eq(date),
                eq(ExceptionService.REGULARIZATION_REEVALUATION_TYPES), eq(superAdminId), anyString(), anyString());
    }

    @Test
    void approve_partialStage_doesNotYetTriggerPenaltyReversal() {
        LocalDate date = LocalDate.now();
        // A Manager approval is final on its own now (see the approve() javadoc) — a Manager has
        // no authority left once a legacy request is already PARTIALLY_APPROVED, so this exercises
        // the one remaining way finalStage can be false without also reaching the terminal state.
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED").approvedBy(managerId).build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));

        assertThrows(AccessDeniedException.class,
                () -> regularizationService.approve(partiallyApproved.getId(), null, managerEmail),
                "a Manager has no authority at the PARTIALLY_APPROVED stage");
        verifyNoInteractions(exceptionService);
    }

    @Test
    void reject_recordsCommentAndAuditRow() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        RegularizationResponse resp = regularizationService.reject(pending.getId(), "Not a valid correction", managerEmail);

        assertEquals("REJECTED", resp.getStatus());
        assertEquals("Not a valid correction", resp.getReviewComment());
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getActionType().equals("REJECTED") && "Not a valid correction".equals(a.getComments())
                        && "MANAGER".equals(a.getActorRole())));
    }

    @Test
    void reject_byHrAdmin_onPending_succeeds() {
        // ONEHR-140 follow-up: same bypass as approve() above — HR_ADMIN may reject a PENDING
        // request without being the employee's manager and before the manager has acted.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        RegularizationResponse resp = regularizationService.reject(pending.getId(), "No", hrEmail);

        assertEquals("REJECTED", resp.getStatus());
        assertEquals("No", resp.getReviewComment());
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getActionType().equals("REJECTED") && "HR_ADMIN".equals(a.getActorRole())));
        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_REJECTED"), any(), any(), any());
    }

    @Test
    void reject_bySuperAdmin_fromPending_succeeds() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        RegularizationResponse resp = regularizationService.reject(pending.getId(), "Not valid", superAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getActionType().equals("REJECTED") && "SUPER_ADMIN".equals(a.getActorRole())));
        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_REJECTED"), any(), any(), any());
    }

    @Test
    void reject_byUnauthorizedEmployee_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class, () -> regularizationService.reject(pending.getId(), "No", strangerEmail));
        verify(regularizationRepository, never()).save(any());
        verify(regularizationApprovalRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void reject_byHrAdmin_calledTwice_secondCallRejectedAndNoDuplicateNotification() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        regularizationService.reject(pending.getId(), "No", hrEmail);
        assertThrows(IllegalArgumentException.class, () -> regularizationService.reject(pending.getId(), "No", hrEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_REJECTED"), any(), any(), any());
    }

    @Test
    void reject_byHrAdmin_onPartiallyApproved_succeeds() {
        LocalDate date = LocalDate.now();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));

        RegularizationResponse resp = regularizationService.reject(partiallyApproved.getId(), "Insufficient evidence", hrEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(regularizationApprovalRepository).save(argThat(a -> "HR_ADMIN".equals(a.getActorRole())));
    }

    @Test
    void update_byOwnerWhilePending_reResolvesApprover() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        CreateRegularizationRequest edit = request(date, date.atTime(9, 15), date.atTime(18, 15), "Updated reason");
        edit.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.update(pending.getId(), edit, employeeEmail);

        assertEquals("Updated reason", resp.getReason());
        assertEquals(hrId, resp.getAssignedApproverId());
        assertEquals(date.atTime(9, 15), resp.getRequestedCheckIn());
    }

    @Test
    void update_toOvernightShift_appliesSameBusinessDateAndRolloverAsSubmit() {
        // update() calls the same resolveTimes() as submit() — confirms the overnight rollover
        // and 07:00 AM business-date consistency check behave identically on the edit path.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        CreateRegularizationRequest edit = request(date, date.atTime(15, 30), date.atTime(0, 30), "Switched to night shift");
        edit.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.update(pending.getId(), edit, employeeEmail);

        assertEquals(date.atTime(15, 30), resp.getRequestedCheckIn());
        assertEquals(date.plusDays(1).atTime(0, 30), resp.getRequestedCheckOut());
    }

    @Test
    void scenario12_update_overnightShift_17AugTo18Aug_literalDates_sameRuleAsSubmit() {
        // Literal-dated counterpart to scenario 8, via update() instead of submit() — same
        // resolveTimes() call, so the business-date/rollover outcome must be identical. Owned by
        // the Super Admin fixture (exempt from the lookback window) so the edit's new attendance
        // date can be a fixed 2026-08-17 rather than "today".
        LocalDate aug17 = LocalDate.of(2026, 8, 17);
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(superAdminId).assignedApproverId(managerId).attendanceDate(aug17)
                .requestedCheckIn(aug17.atTime(9, 0)).requestedCheckOut(aug17.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        CreateRegularizationRequest edit = request(aug17, aug17.atTime(15, 30), aug17.atTime(0, 30), "Switched to night shift");
        edit.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.update(pending.getId(), edit, superAdminEmail);

        assertEquals(aug17, resp.getAttendanceDate());
        assertEquals(LocalDateTime.of(2026, 8, 17, 15, 30), resp.getRequestedCheckIn());
        assertEquals(LocalDateTime.of(2026, 8, 18, 0, 30), resp.getRequestedCheckOut());
    }

    @Test
    void update_byNonOwner_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class, () -> regularizationService.update(
                pending.getId(), request(date, date.atTime(9, 15), date.atTime(18, 15), "Hijack"), strangerEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void update_whenMovedToDateWithApprovedRequest_isRejected() {
        LocalDate date = LocalDate.now();
        // Must stay within the 3-day lookback window and not be in the future — otherwise
        // validateLookbackWindow rejects it before the duplicate-date check this test targets.
        LocalDate approvedDate = date.minusDays(1);
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, approvedDate, "APPROVED"))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.update(
                pending.getId(), request(approvedDate, approvedDate.atTime(9, 15), approvedDate.atTime(18, 15), "Moved"), employeeEmail));

        assertEquals("Already raised regularization for this date.", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void update_afterAlreadyDecided_isRejected() {
        LocalDate date = LocalDate.now();
        RegularizationRequest decided = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("APPROVED").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(decided.getId())).thenReturn(Optional.of(decided));

        assertThrows(IllegalStateException.class, () -> regularizationService.update(
                decided.getId(), request(date, date.atTime(9, 15), date.atTime(18, 15), "Too late"), employeeEmail));
    }

    @Test
    void listPendingForApprover_managerSeesOnlyAssignedRequests() {
        RegularizationRequest assigned = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").build();
        RegularizationRequest notAssigned = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("y").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findByStatus("PENDING")).thenReturn(List.of(assigned, notAssigned));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(managerEmail);

        assertEquals(1, queue.size());
        assertEquals(assigned.getId(), queue.get(0).getId());
    }

    @Test
    void listPendingForApprover_hrAdminSeesOnlyPartiallyApproved() {
        // HR is a final-stage-only approver — their queue is PARTIALLY_APPROVED, not PENDING.
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PARTIALLY_APPROVED").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findByStatus("PARTIALLY_APPROVED")).thenReturn(List.of(partiallyApproved));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(hrEmail);

        assertEquals(1, queue.size());
        assertEquals(partiallyApproved.getId(), queue.get(0).getId());
        verify(regularizationRepository, never()).findByStatus("PENDING");
    }

    @Test
    void listPendingForApprover_dualRoleManagerAndHrAdmin_seesBothQueues() {
        RegularizationRequest assignedPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").build();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("y").status("PARTIALLY_APPROVED").build();
        User dualRoleUser = User.builder().id(managerId).email(managerEmail)
                .roles(Set.of(
                        Role.builder().id(1).code("MANAGER").displayName("Manager").build(),
                        Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build()))
                .build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(dualRoleUser));
        when(regularizationRepository.findByStatus("PENDING")).thenReturn(List.of(assignedPending));
        when(regularizationRepository.findByStatus("PARTIALLY_APPROVED")).thenReturn(List.of(partiallyApproved));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(managerEmail);

        assertEquals(2, queue.size());
        assertTrue(queue.stream().map(RegularizationResponse::getId)
                .toList().containsAll(List.of(assignedPending.getId(), partiallyApproved.getId())));
    }

    @Test
    void listForApprover_managerSeesOnlyAssignedRequestsAcrossAllStatuses() {
        RegularizationRequest assignedPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").createdAt(LocalDateTime.now()).build();
        RegularizationRequest assignedApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now().minusDays(1))
                .reason("y").status("APPROVED").createdAt(LocalDateTime.now().minusDays(1)).build();
        RegularizationRequest notAssignedRejected = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("z").status("REJECTED").createdAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findAll()).thenReturn(List.of(assignedPending, assignedApproved, notAssignedRejected));

        List<RegularizationResponse> all = regularizationService.listForApprover(managerEmail);

        assertEquals(2, all.size());
        assertTrue(all.stream().map(RegularizationResponse::getId)
                .toList().containsAll(List.of(assignedPending.getId(), assignedApproved.getId())));
    }

    @Test
    void listForApprover_hrAdminSeesEveryRequestRegardlessOfAssignee() {
        RegularizationRequest assignedToManagerPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").createdAt(LocalDateTime.now()).build();
        RegularizationRequest assignedToManagerApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("y").status("APPROVED").createdAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findAll())
                .thenReturn(List.of(assignedToManagerPending, assignedToManagerApproved));

        List<RegularizationResponse> all = regularizationService.listForApprover(hrEmail);

        assertEquals(2, all.size());
    }

    @Test
    void listApprovers_returnsEligibleRoleEmployeesOnly() {
        Employee managerEmployee = Employee.builder().userId(managerId).fullName("Manager One").user(managerUser).build();
        when(employeeRepository.findActiveByRoleCodes(Set.of("MANAGER", "HR_ADMIN")))
                .thenReturn(List.of(managerEmployee));

        List<?> approvers = regularizationService.listApprovers();

        assertEquals(1, approvers.size());
    }

    // ── 07:00 AM business-day boundary — pure, wall-clock-independent (see resolveBusinessDate) ──

    @Test
    void resolveBusinessDate_beforeSevenAM_belongsToPreviousDay() {
        LocalDateTime justBeforeBoundary = LocalDateTime.of(2026, 3, 11, 6, 59, 59);

        assertEquals(LocalDate.of(2026, 3, 10), RegularizationService.resolveBusinessDate(justBeforeBoundary));
    }

    @Test
    void resolveBusinessDate_atExactlySevenAM_startsNewDay() {
        LocalDateTime exactlyBoundary = LocalDateTime.of(2026, 3, 11, 7, 0, 0);

        assertEquals(LocalDate.of(2026, 3, 11), RegularizationService.resolveBusinessDate(exactlyBoundary));
    }

    @Test
    void resolveBusinessDate_earlyMorningWellBeforeBoundary_belongsToPreviousDay() {
        LocalDateTime fiveAM = LocalDateTime.of(2026, 3, 11, 5, 0, 0);

        assertEquals(LocalDate.of(2026, 3, 10), RegularizationService.resolveBusinessDate(fiveAM));
    }

    @Test
    void resolveBusinessDate_wellAfterBoundary_belongsToSameCalendarDay() {
        LocalDateTime afternoon = LocalDateTime.of(2026, 3, 11, 15, 30, 0);

        assertEquals(LocalDate.of(2026, 3, 11), RegularizationService.resolveBusinessDate(afternoon));
    }

    // ── Scenarios A-E from the spec, applied to both check-in- and check-out-shaped timestamps —
    //    resolveBusinessDate is one pure function used for both, so "consistently for both" holds
    //    by construction; these pin the exact letter-labeled boundary values against 17/18-Aug. ──

    @Test
    void scenarioA_checkIn_17Aug7AM_belongsTo17Aug() {
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 17, 7, 0, 0)));
    }

    @Test
    void scenarioB_checkIn_17Aug1159PM_belongsTo17Aug() {
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 17, 23, 59, 0)));
    }

    @Test
    void scenarioC_checkIn_18AugMidnight_belongsTo17Aug() {
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 0, 0, 0)));
    }

    @Test
    void scenarioD_checkIn_18Aug659AM_belongsTo17Aug() {
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 6, 59, 0)));
    }

    @Test
    void scenarioE_checkIn_18Aug7AM_belongsTo18Aug() {
        assertEquals(LocalDate.of(2026, 8, 18),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 7, 0, 0)));
    }

    @Test
    void scenarioD_checkOut_18Aug659AM_belongsTo17Aug_sameRuleAsCheckIn() {
        // Same function, same rule, applied to a check-out-shaped value — confirms consistency
        // between check-in and check-out per requirement 4, not just check-in in isolation.
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 6, 59, 0)));
    }

    @Test
    void scenarioH_checkOut_18Aug7AM_belongsTo18Aug_sameRuleAsCheckIn() {
        assertEquals(LocalDate.of(2026, 8, 18),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 7, 0, 0)));
    }

    // ── Exact numbered scenarios 1, 3, 10 from the cycle-boundary spec, pinned to their literal
    //    Aug 16/17/18 dates for direct traceability (1 and 3 aren't otherwise covered by an exact
    //    literal value; 10 mirrors scenario E/H's rule but is added explicitly for that item). ──

    @Test
    void scenario1_659am_Aug17_belongsToAug16Cycle() {
        assertEquals(LocalDate.of(2026, 8, 16),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 17, 6, 59, 0)));
    }

    @Test
    void scenario3_2pm_Aug17_belongsToAug17Cycle() {
        assertEquals(LocalDate.of(2026, 8, 17),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 17, 14, 0, 0)));
    }

    @Test
    void scenario10_700am_Aug18_belongsToAug18Cycle() {
        assertEquals(LocalDate.of(2026, 8, 18),
                RegularizationService.resolveBusinessDate(LocalDateTime.of(2026, 8, 18, 7, 0, 0)));
    }

    // ── Notifications: reuse the existing NotificationService, only for Request Regularization ──

    @Test
    void submit_notifiesTheAssignedApprover_onCreation() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        Employee employeeRecord = Employee.builder().userId(employeeId).fullName("Alex Employee").user(employeeUser).shift(defaultShift).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employeeRecord));

        LocalDate today = LocalDate.now();
        regularizationService.submit(request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail);

        verify(notificationService, times(1)).send(eq(managerId), eq("REGULARIZATION_SUBMITTED"),
                eq("Regularization Request Submitted"),
                argThat(msg -> msg.contains("Alex Employee") && msg.contains(today.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")))),
                eq("/approvals?type=REGULARIZATION"));
    }

    @Test
    void submit_withNoManagerOnFile_sendsNoNotification() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        LocalDate today = LocalDate.now();
        RegularizationResponse resp = regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail);

        assertNull(resp.getAssignedApproverId());
        verifyNoInteractions(notificationService);
    }

    @Test
    void approve_byManager_notifiesEmployeeExactlyOnce() {
        // A Manager's approval is final on its own now, so it fires the same "approved"
        // notification a HR/Super Admin approval would.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, managerEmail);

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_APPROVED"), any(), any(), any());
    }

    @Test
    void approve_byHrAdmin_finalStage_notifiesEmployeeExactlyOnce() {
        LocalDate date = LocalDate.now();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED")
                .approvedBy(managerId).approvedAt(LocalDateTime.now()).build();
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        Employee hrEmployee = Employee.builder().userId(hrId).fullName("Priya HR").user(hrUser).build();
        when(employeeRepository.findById(hrId)).thenReturn(Optional.of(hrEmployee));

        regularizationService.approve(partiallyApproved.getId(), "Looks good", hrEmail);

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_APPROVED"),
                eq("Regularization Request Approved"),
                argThat(msg -> msg.contains("Priya HR") && msg.contains("Looks good")
                        && msg.contains(date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")))),
                eq("/my-requests?type=REGULARIZATION"));
    }

    @Test
    void approve_bySuperAdmin_bypass_notifiesEmployee() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        regularizationService.approve(pending.getId(), null, superAdminEmail);

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_notifiesEmployee_withReasonIncluded_exactlyOnce() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        Employee managerEmployee = Employee.builder().userId(managerId).fullName("Sam Manager").user(managerUser).build();
        when(employeeRepository.findById(managerId)).thenReturn(Optional.of(managerEmployee));

        regularizationService.reject(pending.getId(), "Not a valid correction", managerEmail);

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_REJECTED"),
                eq("Regularization Request Rejected"),
                argThat(msg -> msg.contains("Sam Manager") && msg.contains("Not a valid correction")),
                eq("/my-requests?type=REGULARIZATION"));
    }

    @Test
    void reject_withoutComment_stillNotifiesEmployee_withoutReasonClause() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        regularizationService.reject(pending.getId(), null, managerEmail);

        verify(notificationService, times(1)).send(eq(employeeId), eq("REGULARIZATION_REJECTED"), any(), any(), any());
    }
}
