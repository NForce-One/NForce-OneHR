package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Web Clock-In has no approval step at all: every submission's attendance effect is immediate,
 * and there is no PENDING/APPROVED/REJECTED review (see the service's own class Javadoc). The
 * FIRST Web Clock-In of an employee's resolved work day requires a note and notifies the
 * employee's manager (informational only); every later cycle the same day needs no note and
 * never re-notifies.
 */
@ExtendWith(MockitoExtension.class)
class WebClockInServiceTest {

    @Mock private WebClockInRequestRepository webClockInRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private LatePenaltyService latePenaltyService;
    @Mock private NotificationService notificationService;
    @Mock private AttendanceService attendanceService;
    @Mock private com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;
    @Mock private com.nforce.onehr.repository.AttendanceRulesRepository attendanceRulesRepository;
    @Mock private com.nforce.onehr.repository.ShiftRepository shiftRepository;

    private WebClockInService service;

    // The default employee's assigned Shift (set up below) — a field so individual @Test methods
    // can reference its id for Attendance fixtures' .shiftId(...), exactly as
    // AttendanceInterpretationService.interpretExistingSession now requires for any "existing
    // record" test scenario to resolve as RESOLVED rather than LEGACY_UNRESOLVED.
    private com.nforce.onehr.entity.Shift defaultShift;
    // Backs the fake EmployeeShiftAssignmentResolver below — the shared test employee's "as of
    // right now" assignment for submit()'s interpretFreshAction call. A test that swaps in a
    // different Shift for a fresh submit() must update this alongside the Employee fixture's own
    // .shift(...).
    private com.nforce.onehr.entity.Shift currentEmployeeShift;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    // A minimal, real (not mocked) Shift Version resolver — single-version-per-shift, in-memory
    // "latest effectiveFrom <= day" lookup, mirroring ShiftVersionRepository's own query semantics.
    private final List<com.nforce.onehr.entity.ShiftVersion> shiftVersions = new java.util.ArrayList<>();
    private final ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
        @Override
        public Optional<com.nforce.onehr.entity.ShiftVersion> resolveIfPresent(com.nforce.onehr.entity.Shift s, LocalDate workDate) {
            return shiftVersions.stream()
                    .filter(v -> v.getShift().getId().equals(s.getId()))
                    .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(com.nforce.onehr.entity.ShiftVersion::getEffectiveFrom));
        }
        @Override
        public com.nforce.onehr.entity.ShiftVersion resolve(com.nforce.onehr.entity.Shift s, LocalDate workDate) {
            return resolveIfPresent(s, workDate)
                    .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
        }
    };

    // Matches the pre-migration global app.attendance.late-grace-minutes default (10).
    private static final int DEFAULT_TEST_GRACE_MINUTES = 10;

    /** Builds a Shift (with a real id) and registers a single version effective from the dawn of time. */
    private com.nforce.onehr.entity.Shift shift(String name, LocalTime start, LocalTime end) {
        com.nforce.onehr.entity.Shift s = com.nforce.onehr.entity.Shift.builder().id(UUID.randomUUID()).name(name).active(true).build();
        shiftVersions.add(com.nforce.onehr.entity.ShiftVersion.builder().shift(s).startTime(start).endTime(end)
                .lateGraceMinutes(DEFAULT_TEST_GRACE_MINUTES).effectiveFrom(LocalDate.MIN).build());
        return s;
    }

    @BeforeEach
    void setUp() {
        lenient().when(webClockInRepository.save(any(WebClockInRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // No prior cycles today unless a test stubs otherwise — i.e. every submit() defaults to
        // "first cycle of the day" (mandatory note, notifies the manager).
        lenient().when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(any(), any()))
                .thenReturn(List.of());
        // Default: a real employee with a real (overnight, 15:30-00:30 — matching the org's own
        // Default Shift) assigned shift, since every employee is now expected to have
        // one (ShiftDayPolicy has no fallback for a null shift, and requireEmployee() below fails
        // loudly for a genuinely missing Employee profile — see WebClockInService's own Javadoc on
        // both). Individual tests below override this with their own Employee where the specific
        // shift/timing matters; tests that don't care about shift specifics get this sane default
        // rather than an anomalous "no employee" state that no longer reflects normal operation.
        defaultShift = shift("Regular Shift", LocalTime.of(15, 30), LocalTime.of(0, 30));
        com.nforce.onehr.entity.Employee defaultEmployee = com.nforce.onehr.entity.Employee.builder()
                .userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(defaultShift).build();
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.of(defaultEmployee));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(java.math.BigDecimal.valueOf(18)).build()));
        EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver = new EmployeeShiftAssignmentResolver(null) {
            @Override
            public Optional<EmployeeShiftAssignment> resolveIfPresent(UUID employeeUserId, LocalDate workDate) {
                return currentEmployeeShift == null ? Optional.empty()
                        : Optional.of(EmployeeShiftAssignment.builder()
                                .employeeUserId(employeeUserId).shift(currentEmployeeShift).effectiveFrom(LocalDate.MIN).build());
            }
            @Override
            public EmployeeShiftAssignment resolve(UUID employeeUserId, LocalDate workDate) {
                return resolveIfPresent(employeeUserId, workDate)
                        .orElseThrow(() -> new IllegalStateException("no assignment effective on or before " + workDate));
            }
        };
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver, employeeShiftAssignmentResolver);
        // Matches app.attendance.half-day-max-hours' old YAML default (3.5) — see
        // AttendanceRulesService's own Javadoc for why this moved off AttendanceProperties.
        lenient().when(attendanceRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.AttendanceRules.builder()
                        .halfDayMaxHours(java.math.BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));
        AttendanceRulesService attendanceRulesService = new AttendanceRulesService(attendanceRulesRepository);
        // Resolves any Shift created via this test file's own shift() helper above — so a fixture
        // that sets Attendance.shiftId to one of those shifts' ids resolves correctly through
        // AttendanceInterpretationService.interpretExistingSession, exactly like the real
        // ShiftRepository would.
        lenient().when(shiftRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return shiftVersions.stream().map(com.nforce.onehr.entity.ShiftVersion::getShift)
                    .filter(s -> s.getId().equals(id)).findFirst();
        });
        AttendanceInterpretationService attendanceInterpretationService =
                new AttendanceInterpretationService(shiftDayPolicy, shiftRepository, employeeShiftAssignmentResolver);
        service = new WebClockInService(webClockInRepository, attendanceRepository, attendancePunchRepository,
                historyRepository, userRepository, employeeRepository, auditService, auditSnapshot,
                latePenaltyService, notificationService, attendanceService, attendanceRulesService,
                attendanceInterpretationService);
        currentEmployeeShift = defaultShift;
        lenient().when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendanceRepository.saveAndFlush(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        // submit() -> resolveAssignedApprover() needs a non-null Optional regardless of whether
        // the test cares about manager assignment.
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        // cancel()'s safety check — defaults to "nothing else has touched this row" unless a test
        // overrides it.
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(any())).thenReturn(List.of());
    }

    private User employeeUser(String email) {
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        return User.builder().id(employeeId).email(email).roles(Set.of(empRole)).build();
    }

    private void stubEmployeeUser(String employeeEmail) {
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
    }

    private void stubManagerAssigned() {
        com.nforce.onehr.entity.EmployeeManagerHistory history = com.nforce.onehr.entity.EmployeeManagerHistory.builder()
                .managerUserId(managerId).build();
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(history));
    }

    @Test
    void checkOut_rejectsStaleClick_pastItsOwnGraceWindow_leavingTheSharedRecordUntouched() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        LocalDate workDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(2);
        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(req));

        Attendance record = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .sessionStartedAt(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, workDate))
                .thenReturn(Optional.of(record));

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

        assertEquals("PRESENT", record.getStatus());
        assertNull(record.getCheckOutAt());
        assertNull(record.getWorkedMinutes());
        verify(attendanceRepository, never()).save(any(Attendance.class));
    }

    // ── Phase 0.2: active/deleted employee punch gate ────────────────────────

    @Test
    void submit_deactivatedEmployee_isRejected() {
        String employeeEmail = "employee@test.com";
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User deactivated = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(empRole)).active(false).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(deactivated));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
        assertTrue(ex.getMessage().toLowerCase().contains("inactive"));
        verify(webClockInRepository, never()).save(any(WebClockInRequest.class));
    }

    @Test
    void submit_deletedEmployee_isRejected() {
        String employeeEmail = "employee@test.com";
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User deleted = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(empRole))
                .active(true).deletedAt(java.time.Instant.now()).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(deleted));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
        verify(webClockInRepository, never()).save(any(WebClockInRequest.class));
    }

    @Test
    void checkOut_deactivatedEmployee_isRejected() {
        String employeeEmail = "employee@test.com";
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User deactivated = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(empRole)).active(false).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(deactivated));

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));
    }

    @Test
    void submit_succeeds_whenNoSessionIsCurrentlyOpen() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("Working from home", resp.getReason());
    }

    /**
     * A fresh Web Clock-In (no prior Attendance context for the day) must snapshot the employee's
     * CURRENT Shift onto the Attendance row it creates — see
     * AttendanceInterpretationService.interpretFreshAction/applyCheckInToAttendance.
     */
    @Test
    void submit_freshWebClockIn_snapshotsTheEmployeesCurrentShiftOntoTheAttendanceRecord() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        service.submit(req, employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a ->
                defaultShift.getId().equals(a.getShiftId())));
    }

    /**
     * The FIRST Web Clock-In of an employee's resolved work day requires a note, and — when the
     * employee has a manager — sends that manager a purely informational notification.
     */
    @Test
    void submit_firstCycleOfTheDay_notifiesTheManager() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);
        stubManagerAssigned();

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        service.submit(req, employeeEmail);

        verify(notificationService, times(1)).send(eq(managerId), eq("WEB_CLOCK_IN_NOTICE"), any(), any(), any());
    }

    /** The first cycle of the day, with no manager assigned, sends no notification (nothing to fail on either). */
    @Test
    void submit_firstCycleOfTheDay_noManagerAssigned_sendsNoNotification() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        service.submit(req, employeeEmail);

        verifyNoInteractions(notificationService);
    }

    /** The mandatory-note gate: the first Web Clock-In of the day with a blank reason is rejected outright. */
    @Test
    void submit_firstCycleOfTheDay_blankReason_isRejected() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("   ").timezone("Asia/Kolkata").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
        assertTrue(ex.getMessage().toLowerCase().contains("note is required"));
        verify(webClockInRepository, never()).save(any(WebClockInRequest.class));
        verifyNoInteractions(notificationService);
    }

    /** Same as above, for a completely omitted (null) reason rather than a blank one. */
    @Test
    void submit_firstCycleOfTheDay_omittedReason_isRejected() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().timezone("Asia/Kolkata").build();

        assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
        verify(webClockInRepository, never()).save(any(WebClockInRequest.class));
    }

    /**
     * Requirement: normal Check-In/Check-Out and Web Clock-In are fully independent — an employee
     * who is currently checked in normally must still be able to submit a fresh Web Clock-In.
     * submit() never even looks at the normal session's open/closed state (only this class's own
     * WebClockInRequest.checkedOutAt), so there is nothing normal-session-related to stub here.
     */
    @Test
    void submit_isNotBlockedByAnOpenNormalCheckInSession() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Remote while also clocked in").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("Remote while also clocked in", resp.getReason());
        verifyNoInteractions(attendanceService);
    }

    /**
     * Only one WEB session may be open at a time, however it started — this is NOT the
     * once-per-day restriction (see the next tests for that), and is unrelated to whatever
     * the normal Check-In/Check-Out session's own state happens to be.
     */
    @Test
    void submit_rejectsASecondConcurrentWebClockIn_whileAWebSessionIsStillOpen() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        WebClockInRequest openWebReq = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(today)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(openWebReq));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Second try").timezone("Asia/Kolkata").build();

        assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
    }

    /**
     * Requirement: Web Clock-In/Out is NOT restricted to once per day — an employee can
     * Web Clock-In, Web Clock-Out, then Web Clock-In again later the same day. Since the day's
     * Attendance row already exists, a fresh submit must not touch its checkInAt/checkOutAt/
     * workedMinutes at all (those are only ever recomputed when a session actually closes — see
     * checkOut) — no resetting, no reopening, no double-counting. This second cycle needs no note
     * (blank reason accepted) and must not re-notify the manager.
     */
    @Test
    void submit_allowsASecondWebClockInCycle_sameDay_withoutTouchingTheExistingAttendanceRecord_andWithoutRenotifying() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);
        // Deliberately NOT stubbing a manager assignment: since this is not the first cycle of
        // the day, resolveAssignedApprover must never even be consulted (see verifyNoInteractions
        // below) — stubbing it would just be dead setup.

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Attendance closedRecord = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(today)
                .checkInAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(3))
                .checkOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(1))
                .workedMinutes(120)
                .status("PRESENT")
                .build();
        // Matched by employeeId + any() date, not eq(today): submit() resolves its own workDate
        // via ShiftDayPolicy.shiftDayOf(employee, now), which can legitimately land on the
        // previous calendar date when the test happens to run before the default shift's own
        // logical-workday-reset boundary (09:30 AM, for the default 15:30-00:30 shift used here)
        // — a plain LocalDate.now() here would then mismatch and flakily fail, exactly the
        // scenario this comment is guarding against.
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(closedRecord));
        // A prior cycle already exists today — this is NOT the first cycle.
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).workDate(today)
                .requestedCheckIn(closedRecord.getCheckInAt()).reason("First remote cycle")
                .checkedOutAt(closedRecord.getCheckOutAt()).build();
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(eq(employeeId), any()))
                .thenReturn(List.of(firstCycle));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        // Blank reason accepted without error — this is not the first cycle of the day.
        assertNotNull(resp);
        assertNotNull(closedRecord.getCheckOutAt());
        assertEquals(120, closedRecord.getWorkedMinutes());
        verify(attendanceRepository, never()).save(any(Attendance.class));
        verifyNoInteractions(notificationService);
    }

    /**
     * A WEB session left open past its own workday/grace window (a forgotten Web Clock-Out from
     * days ago) must not block a fresh Web Clock-In forever — it's auto-closed at its own natural
     * shift end (see autoCloseStaleWebSession) instead, then the fresh submit proceeds normally.
     */
    @Test
    void submit_autoClosesAStaleOpenWebSession_thenStillAllowsAFreshWebClockIn() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        LocalDate staleWorkDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(2);
        WebClockInRequest staleWebReq = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .requestedCheckIn(LocalDateTime.of(staleWorkDate, LocalTime.of(17, 35)))
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(staleWebReq));

        Attendance staleAttendance = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .checkInAt(LocalDateTime.of(staleWorkDate, LocalTime.of(17, 35)))
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        lenient().when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, staleWorkDate))
                .thenReturn(Optional.of(staleAttendance));
        when(attendanceService.recomputeCombinedWorkedMinutes(eq(employeeId), eq(staleAttendance.getId()), eq(staleWorkDate)))
                .thenReturn(475);

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Fresh remote day").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("Fresh remote day", resp.getReason());
        assertNotNull(staleWebReq.getCheckedOutAt(), "the stale Web session must be auto-closed, not left open forever");
        assertEquals(475, staleAttendance.getWorkedMinutes());
    }

    /**
     * A Web Clock-In session and a normal Check-In/Check-Out session can genuinely overlap in
     * real time on the same shared Attendance row. checkOut() must never write record.checkOutAt
     * (that field belongs exclusively to the normal session) and must always ask
     * AttendanceService for the combined, overlap-safe total rather than adding this session's
     * own minutes on top of whatever the normal side already counted. checkOut() must also never
     * notify anyone.
     */
    @Test
    void checkOut_recomputesCombinedWorkedMinutes_viaAttendanceServiceMerge_andNeverTouchesRecordCheckOutAt_andNeverNotifies() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(10, 0));
        LocalDateTime regularCheckOutAt = LocalDateTime.of(workDate, LocalTime.of(12, 0));

        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(checkInAt)
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(req));

        // A normal Check-In/Check-Out already ran concurrently — 120 minutes already counted once.
        Attendance record = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .checkOutAt(regularCheckOutAt)
                .workedMinutes(120)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, workDate))
                .thenReturn(Optional.of(record));
        when(attendanceService.recomputeCombinedWorkedMinutes(eq(employeeId), eq(record.getId()), eq(workDate), any()))
                .thenReturn(150);

        WebClockInResponse resp = service.checkOut(employeeEmail, null);

        assertNotNull(resp.getCheckedOutAt());
        // The merge result is what's stored — not a naive addition of this session's own minutes
        // on top of the existing 120.
        assertEquals(150, record.getWorkedMinutes());
        // Never touched — that field belongs exclusively to the normal session.
        assertEquals(regularCheckOutAt, record.getCheckOutAt());
        verifyNoInteractions(notificationService);
    }

    /**
     * Mirrors AttendanceServiceTest's identical fix/test — recomputeDerivedFields must compare
     * full date-aware instants, not bare LocalTime-of-day, or a Web Clock-In that's crossed
     * midnight relative to an overnight shift wrongly reads as on-time. Uses a browser-timezone
     * OFFSET chosen so "now" is always exactly 1:00 AM local, regardless of when this test
     * actually runs.
     */
    @Test
    void submit_computesLatenessCorrectly_forAFreshCheckInThatHasCrossedMidnightOnAnOvernightShift() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);

        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(1, 0).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        // The deterministic "now reads as 1:00 AM" trick now has to come from the employee's own
        // configured Location.timezone, not the request's clientTimezone — resolveZone no longer
        // consults the latter at all (Location is the ONLY source, per explicit requirement).
        // ZoneId.of accepts a bare numeric offset ("+05:30" etc.) just as well as a real IANA
        // region name, so this is otherwise the exact same technique as before.
        com.nforce.onehr.entity.Location location = com.nforce.onehr.entity.Location.builder()
                .name("Test Location").timezone(offset.getId()).build();
        com.nforce.onehr.entity.Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        com.nforce.onehr.entity.Employee employee = com.nforce.onehr.entity.Employee.builder()
                .userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        currentEmployeeShift = overnightShift;

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Late remote start").timezone(null).build();

        service.submit(req, employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a ->
                "LATE".equals(a.getStatus()) && a.getLateByMinutes() != null && a.getLateByMinutes() > 200));
    }

    // ── Unified-grace model (2026-09-08 audit): the shift's own allowed-late privilege is the
    // sole grace, for Web Clock-In exactly as it already was for the normal Check-In path.
    // Deterministic-"now" helper mirrors submit_computesLatenessCorrectly_...'s own technique. ──

    /** Builds the deterministic ZoneOffset so LocalDateTime.now() reads as exactly {@code time} local, regardless of when this test actually runs. */
    private ZoneOffset offsetForNow(LocalTime time) {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = time.toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        return ZoneOffset.ofTotalSeconds(offsetSeconds);
    }

    private void useDayShiftEmployee(String employeeEmail, ZoneOffset offset) {
        com.nforce.onehr.entity.Location location = com.nforce.onehr.entity.Location.builder()
                .name("Test Location").timezone(offset.getId()).build();
        com.nforce.onehr.entity.Shift dayShift = shift("Day Shift", LocalTime.of(9, 0), LocalTime.of(18, 0));
        com.nforce.onehr.entity.Employee employee = com.nforce.onehr.entity.Employee.builder()
                .userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(dayShift).location(location).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        currentEmployeeShift = dayShift;
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
    }

    @Test
    void submit_at0905_withinTenMinuteGrace_staysPresent() {
        String employeeEmail = "employee@test.com";
        useDayShiftEmployee(employeeEmail, offsetForNow(LocalTime.of(9, 5)));

        service.submit(CreateWebClockInRequest.builder().reason("Remote start").timezone(null).build(), employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a -> "PRESENT".equals(a.getStatus())));
    }

    /**
     * The grace boundary itself (10 minutes) is still accepted, not rejected. Targets 09:09:59
     * rather than exactly 09:10:00: the deadline itself
     * ({@code shiftStart(09:00:00) + 10min = 09:10:00.000000000} exactly) is a single nanosecond
     * instant a real wall-clock read can never reliably land ON (this offset trick only aligns to
     * whole seconds — the real nanosecond-of-second component is whatever it naturally is) — one
     * second of margin is the finest precision available without flaking, and still exercises
     * "right at the edge of the window," never the safely-inside-grace 09:05 case above.
     */
    @Test
    void submit_at0909_59_rightAtTheGraceBoundary_staysPresent() {
        String employeeEmail = "employee@test.com";
        useDayShiftEmployee(employeeEmail, offsetForNow(LocalTime.of(9, 9, 59)));

        service.submit(CreateWebClockInRequest.builder().reason("Remote start").timezone(null).build(), employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a -> "PRESENT".equals(a.getStatus())));
    }

    @Test
    void submit_at0911_pastGrace_isLate() {
        String employeeEmail = "employee@test.com";
        useDayShiftEmployee(employeeEmail, offsetForNow(LocalTime.of(9, 11)));

        service.submit(CreateWebClockInRequest.builder().reason("Remote start").timezone(null).build(), employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a -> "LATE".equals(a.getStatus())));
    }

    /**
     * The confirmed defect this fix addresses: checkOut()'s own status finalization used to
     * recompute LATE/PRESENT from the raw {@code lateByMinutes > 0} figure — ignoring the shift's
     * allowed-late privilege entirely — silently flipping an already-correctly-graced PRESENT
     * Web-only day to LATE the moment the shift naturally ended. A LATE flip here would then
     * (correctly, per ExceptionService's own status-gated detection) create a genuine
     * LATE_ARRIVAL incident on the next dashboard load for an arrival that was never actually
     * late — this test proves checkOut() itself can no longer produce that false incident's
     * root cause. Check-in at 09:05 against a 09:00 shift with a 10-minute privilege is
     * genuinely NOT late (lateByMinutes stays 5, the raw, no-forgiveness display figure) — a
     * full day worked past the shift's own end must still finalize as PRESENT, not LATE.
     */
    @Test
    void checkOut_afterShiftEnd_checkInWithinGrace_remainsPresent_neverFlipsToLateFromRawLateByMinutes() {
        String employeeEmail = "employee@test.com";
        ZoneOffset offset = offsetForNow(LocalTime.of(18, 5));
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        com.nforce.onehr.entity.Shift dayShift = shift("Day Shift", LocalTime.of(9, 0), LocalTime.of(18, 0));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(9, 5));
        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(checkInAt)
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(req));

        Attendance record = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(5)
                .status("PRESENT")
                .timezone(offset.getId())
                .shiftId(dayShift.getId())
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, workDate))
                .thenReturn(Optional.of(record));
        // Full day worked, comfortably above the half-day threshold — the branch under test
        // (workedMinutes >= half-day threshold) is the one that recomputes LATE/PRESENT.
        when(attendanceService.recomputeCombinedWorkedMinutes(eq(employeeId), eq(record.getId()), eq(workDate), any()))
                .thenReturn(530);

        service.checkOut(employeeEmail, null);

        assertEquals("PRESENT", record.getStatus(),
                "5 minutes late is within the shift's 10-minute allowed-late privilege — must stay "
                        + "PRESENT even once the shift naturally ends, never flip to LATE from raw lateByMinutes");
    }

    /**
     * Two Web Clock-In cycles that straddle midnight under an overnight shift still resolve to
     * the same shift-relative work date — so the post-midnight cycle is correctly recognized as
     * "not the first cycle of the day" (no note required, no re-notification), proving the
     * first-cycle/dedup key is the resolved work date, not the calendar date. Uses `any()` for
     * the date argument throughout (not a computed LocalDate.now()) for the same reason
     * submit_allowsASecondWebClockInCycle_sameDay_... does: submit() resolves its own workDate
     * via ShiftDayPolicy against the overnight defaultShift, which can legitimately land on
     * either side of midnight depending on exactly when this test runs — asserting an exact date
     * here would be flaky by construction, when the actual behavior under test is purely "a prior
     * row exists for whatever date gets resolved."
     */
    @Test
    void submit_secondCycleAfterMidnight_onAnOvernightShift_stillResolvesToTheSameWorkDate_soDoesNotReNotify() {
        String employeeEmail = "employee@test.com";
        stubEmployeeUser(employeeEmail);
        // Deliberately NOT stubbing a manager assignment — see the identical comment in
        // submit_allowsASecondWebClockInCycle_sameDay_....

        // defaultShift is overnight, 15:30-00:30 (see setUp) — its resolved work date is stable
        // across midnight, so a prior (pre-midnight) cycle on file for that same resolved work
        // date means a post-midnight submit is NOT the first of the day, regardless of the
        // wall-clock calendar date having rolled over since.
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(6))
                .reason("Before midnight")
                .checkedOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(2))
                .build();
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(eq(employeeId), any()))
                .thenReturn(List.of(firstCycle));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                        .checkInAt(firstCycle.getRequestedCheckIn()).status("PRESENT").build()));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        // Omitted reason accepted without error — this is not the first cycle of the day.
        assertNotNull(resp);
        verifyNoInteractions(notificationService);
    }
}
