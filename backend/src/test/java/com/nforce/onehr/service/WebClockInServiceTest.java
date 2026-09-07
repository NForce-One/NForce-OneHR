package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every new Web Clock-In starts PENDING and requires a real HR/manager approve or reject
 * decision (see the service's own class Javadoc) — the attendance effect (Attendance row,
 * worked minutes) is applied immediately regardless, decoupled from that review status. A
 * reviewed PENDING row must notify the original requester exactly once either way.
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

    private final UUID employeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String hrAdminEmail = "hr@test.com";
    // A minimal, real (not mocked) Shift Version resolver — single-version-per-shift, in-memory
    // "latest effectiveFrom <= day" lookup, mirroring ShiftVersionRepository's own query semantics.
    private final List<com.nforce.onehr.entity.ShiftVersion> shiftVersions = new java.util.ArrayList<>();
    private final ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
        @Override
        public com.nforce.onehr.entity.ShiftVersion resolve(com.nforce.onehr.entity.Shift s, LocalDate workDate) {
            return shiftVersions.stream()
                    .filter(v -> v.getShift().getId().equals(s.getId()))
                    .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                    .max(java.util.Comparator.comparing(com.nforce.onehr.entity.ShiftVersion::getEffectiveFrom))
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
        Role hrRole = Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build();
        User hrUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        lenient().when(userRepository.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrUser));
        lenient().when(webClockInRepository.save(any(WebClockInRequest.class))).thenAnswer(inv -> inv.getArgument(0));
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
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver);
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
                new AttendanceInterpretationService(shiftDayPolicy, shiftRepository);
        service = new WebClockInService(webClockInRepository, attendanceRepository, attendancePunchRepository,
                historyRepository, userRepository, employeeRepository, auditService, auditSnapshot,
                latePenaltyService, notificationService, attendanceService, attendanceRulesService,
                attendanceInterpretationService);
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

    private WebClockInRequest pendingRequest() {
        return WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(LocalDate.of(2026, 8, 10))
                .requestedCheckIn(LocalDateTime.of(2026, 8, 10, 9, 5))
                .reason("Legacy pending row")
                .status("PENDING")
                .build();
    }

    @Test
    void approve_notifiesOriginalRequester() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        WebClockInResponse resp = service.approve(req.getId(), "ok", hrAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_APPROVED"), any(), any(), any());
    }

    /**
     * approve() must NOT re-touch the Attendance row — submit() already applied the check-in
     * effect immediately. Re-applying it here (the old behavior, from before requests started
     * PENDING again) would silently reopen a session the employee may have already checked out
     * of or resumed since — the exact double-counting bug checkOut's own "already closed
     * elsewhere" guard exists to prevent, just triggered from the other direction.
     */
    @Test
    void approve_doesNotReopenOrModifyAnAlreadyClosedAttendanceRecord() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        Attendance closedRecord = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(req.getWorkDate())
                .checkInAt(req.getRequestedCheckIn())
                .checkOutAt(req.getRequestedCheckIn().plusHours(2))
                .workedMinutes(120)
                .status("PRESENT")
                .build();
        lenient().when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, req.getWorkDate()))
                .thenReturn(Optional.of(closedRecord));

        service.approve(req.getId(), "ok", hrAdminEmail);

        verify(attendanceRepository, never()).save(any(Attendance.class));
        assertNotNull(closedRecord.getCheckOutAt());
        assertEquals(120, closedRecord.getWorkedMinutes());
    }

    @Test
    void reject_notifiesOriginalRequesterWithReason() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        WebClockInResponse resp = service.reject(req.getId(), "Not a valid work day", hrAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_REJECTED"), any(),
                contains("Not a valid work day"), any());
    }

    @Test
    void reject_calledTwice_sendsNotificationOnlyOnce() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        service.reject(req.getId(), null, hrAdminEmail);
        assertThrows(IllegalArgumentException.class, () -> service.reject(req.getId(), null, hrAdminEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_REJECTED"), any(), any(), any());
    }

    /**
     * A Web Clock-In session left open past its own logical workday (per ShiftDayPolicy) must
     * reject the click rather than accept it with a fabricated checkedOutAt/workedMinutes. Unlike
     * the normal Check-In/Check-Out flow, this rejection is purely about THIS Web session's own
     * workDate — it must never mutate the shared Attendance record's status (that field is
     * reserved for the normal session's own Missing-Check-Out flagging, see
     * AttendanceService.closeSession).
     */
    @Test
    void checkOut_rejectsStaleClick_pastItsOwnGraceWindow_leavingTheSharedRecordUntouched() {
        String employeeEmail = "employee@test.com";
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User empUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(empRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(empUser));

        LocalDate workDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(2);
        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .status("APPROVED")
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
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        // PENDING, not self-approved — a real HR/manager decision is required (see class Javadoc).
        assertEquals("PENDING", resp.getStatus());
    }

    /**
     * A fresh Web Clock-In (no prior Attendance context for the day) must snapshot the employee's
     * CURRENT Shift onto the Attendance row it creates — see
     * AttendanceInterpretationService.interpretFreshAction/applyCheckInToAttendance.
     */
    @Test
    void submit_freshWebClockIn_snapshotsTheEmployeesCurrentShiftOntoTheAttendanceRecord() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        service.submit(req, employeeEmail);

        verify(attendanceRepository).saveAndFlush(argThat(a ->
                defaultShift.getId().equals(a.getShiftId())));
    }

    /**
     * The attendance effect is immediate regardless of review status, but the request itself
     * must still be routed to whoever it's assigned to for a real decision.
     */
    @Test
    void submit_notifiesTheAssignedApprover() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        com.nforce.onehr.entity.EmployeeManagerHistory history = com.nforce.onehr.entity.EmployeeManagerHistory.builder()
                .managerUserId(hrAdminId).build();
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(history));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        service.submit(req, employeeEmail);

        verify(notificationService, times(1)).send(eq(hrAdminId), eq("WEB_CLOCK_IN_SUBMITTED"), any(), any(), any());
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
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Remote while also clocked in").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        verifyNoInteractions(attendanceService);
    }

    /**
     * Only one WEB session may be open at a time, however it started — this is NOT the
     * once-per-day restriction (see the next two tests for that), and is unrelated to whatever
     * the normal Check-In/Check-Out session's own state happens to be.
     */
    @Test
    void submit_rejectsASecondConcurrentWebClockIn_whileAWebSessionIsStillOpen() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        WebClockInRequest openWebReq = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(today)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .status("PENDING")
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
     * checkOut) — no resetting, no reopening, no double-counting.
     */
    @Test
    void submit_allowsASecondWebClockInCycle_sameDay_withoutTouchingTheExistingAttendanceRecord() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

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

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Back after lunch").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertNotNull(closedRecord.getCheckOutAt());
        assertEquals(120, closedRecord.getWorkedMinutes());
        verify(attendanceRepository, never()).save(any(Attendance.class));
    }

    /**
     * A WEB session left open past its own workday/grace window (a forgotten Web Clock-Out from
     * days ago) must not block a fresh Web Clock-In forever — it's auto-closed at its own natural
     * shift end (see autoCloseStaleWebSession) instead, then the fresh submit proceeds normally.
     */
    @Test
    void submit_autoClosesAStaleOpenWebSession_thenStillAllowsAFreshWebClockIn() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        LocalDate staleWorkDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(2);
        WebClockInRequest staleWebReq = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .requestedCheckIn(LocalDateTime.of(staleWorkDate, LocalTime.of(17, 35)))
                .status("PENDING")
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

        assertEquals("PENDING", resp.getStatus());
        assertNotNull(staleWebReq.getCheckedOutAt(), "the stale Web session must be auto-closed, not left open forever");
        assertEquals(475, staleAttendance.getWorkedMinutes());
    }

    /**
     * A Web Clock-In session and a normal Check-In/Check-Out session can genuinely overlap in
     * real time on the same shared Attendance row. checkOut() must never write record.checkOutAt
     * (that field belongs exclusively to the normal session) and must always ask
     * AttendanceService for the combined, overlap-safe total rather than adding this session's
     * own minutes on top of whatever the normal side already counted.
     */
    @Test
    void checkOut_recomputesCombinedWorkedMinutes_viaAttendanceServiceMerge_andNeverTouchesRecordCheckOutAt() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(10, 0));
        LocalDateTime regularCheckOutAt = LocalDateTime.of(workDate, LocalTime.of(12, 0));

        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(checkInAt)
                .status("APPROVED")
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
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

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

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Late remote start").timezone(null).build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        // The request's own status is PENDING (review status) — the underlying attendance effect
        // (checked via the saved Attendance record) is what carries the lateness computation.
        assertEquals("PENDING", resp.getStatus());
        verify(attendanceRepository).saveAndFlush(argThat(a ->
                "LATE".equals(a.getStatus()) && a.getLateByMinutes() != null && a.getLateByMinutes() > 200));
    }

    /**
     * The actual reported bug: an employee Web Clock-Out then Web Clock-In again the same shift
     * BEFORE HR has reviewed the first (still-PENDING) request. The second cycle must mirror that
     * first request's PENDING status — not spawn an independent second PENDING request — and must
     * NOT re-notify the approver, since one real decision is already awaiting review.
     */
    @Test
    void submit_secondCycle_whileFirstRequestStillPending_mirrorsPendingStatus_withoutRenotifyingApprover() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        com.nforce.onehr.entity.EmployeeManagerHistory history = com.nforce.onehr.entity.EmployeeManagerHistory.builder()
                .managerUserId(hrAdminId).build();
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(history));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(today)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(3))
                .reason("First remote cycle")
                .status("PENDING")
                .checkedOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(1))
                .build();
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(eq(employeeId), any()))
                .thenReturn(List.of(firstCycle));
        // Already checked out (see firstCycle.checkedOutAt above), so no open-session guard fires.
        lenient().when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                        .workDate(today).checkInAt(firstCycle.getRequestedCheckIn()).status("PRESENT").build()));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Back again").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertNull(resp.getReviewedAt(), "mirrored PENDING cycle must not look reviewed");
        verify(notificationService, never()).send(eq(hrAdminId), eq("WEB_CLOCK_IN_SUBMITTED"), any(), any(), any());
    }

    /** Once the first request is APPROVED, a later cycle mirrors APPROVED and needs no reason-review round trip. */
    @Test
    void submit_secondCycle_afterFirstRequestApproved_autoApproves_withoutRenotifyingApprover() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        com.nforce.onehr.entity.EmployeeManagerHistory history = com.nforce.onehr.entity.EmployeeManagerHistory.builder()
                .managerUserId(hrAdminId).build();
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(history));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(today)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(3))
                .reason("First remote cycle")
                .status("APPROVED")
                .reviewedBy(hrAdminId)
                .checkedOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(1))
                .build();
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(eq(employeeId), any()))
                .thenReturn(List.of(firstCycle));
        lenient().when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                        .workDate(today).checkInAt(firstCycle.getRequestedCheckIn()).status("PRESENT").build()));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Back again").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, never()).send(eq(hrAdminId), eq("WEB_CLOCK_IN_SUBMITTED"), any(), any(), any());
    }

    /**
     * A REJECTED first request does NOT count as "already requested this shift" — a genuinely
     * fresh reason + review is required, matching the existing frontend "Resubmit" flow.
     */
    @Test
    void submit_afterFirstRequestRejected_startsAFreshPendingRequest_andNotifiesApprover() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        com.nforce.onehr.entity.EmployeeManagerHistory history = com.nforce.onehr.entity.EmployeeManagerHistory.builder()
                .managerUserId(hrAdminId).build();
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(history));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        WebClockInRequest rejectedCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(today)
                .requestedCheckIn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(3))
                .reason("Rejected cycle")
                .status("REJECTED")
                .checkedOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(1))
                .build();
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(eq(employeeId), any()))
                .thenReturn(List.of(rejectedCycle));
        lenient().when(webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                        .workDate(today).checkInAt(rejectedCycle.getRequestedCheckIn()).status("PRESENT").build()));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Resubmitting").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        verify(notificationService, times(1)).send(eq(hrAdminId), eq("WEB_CLOCK_IN_SUBMITTED"), any(), any(), any());
    }

    /** approve() must resolve every sibling PENDING cycle for the same employee+workDate, not just the one id reviewed. */
    @Test
    void approve_cascadesToSiblingPendingCyclesForTheSameEmployeeAndWorkDate() {
        LocalDate workDate = LocalDate.of(2026, 8, 10);
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(9, 0)))
                .reason("First").status("PENDING").build();
        WebClockInRequest secondCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(14, 0)))
                .reason("Second").status("PENDING").build();
        when(webClockInRepository.findById(firstCycle.getId())).thenReturn(Optional.of(firstCycle));
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate))
                .thenReturn(List.of(firstCycle, secondCycle));

        service.approve(firstCycle.getId(), "ok", hrAdminEmail);

        assertEquals("APPROVED", firstCycle.getStatus());
        assertEquals("APPROVED", secondCycle.getStatus());
        assertEquals(hrAdminId, secondCycle.getReviewedBy());
    }

    /** reject() must likewise resolve every sibling PENDING cycle, not leave them stuck PENDING forever. */
    @Test
    void reject_cascadesToSiblingPendingCyclesForTheSameEmployeeAndWorkDate() {
        LocalDate workDate = LocalDate.of(2026, 8, 10);
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(9, 0)))
                .reason("First").status("PENDING").build();
        WebClockInRequest secondCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(14, 0)))
                .reason("Second").status("PENDING").build();
        when(webClockInRepository.findById(firstCycle.getId())).thenReturn(Optional.of(firstCycle));
        when(webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate))
                .thenReturn(List.of(firstCycle, secondCycle));

        service.reject(firstCycle.getId(), "no", hrAdminEmail);

        assertEquals("REJECTED", firstCycle.getStatus());
        assertEquals("REJECTED", secondCycle.getStatus());
    }

    /** The approver's queue must show one item per employee+workDate, not one per mirrored cycle. */
    @Test
    void listPendingForApprover_dedupesMultipleCyclesForTheSameEmployeeAndWorkDate() {
        LocalDate workDate = LocalDate.of(2026, 8, 10);
        WebClockInRequest firstCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(9, 0)))
                .reason("First").status("PENDING").build();
        WebClockInRequest secondCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).assignedApproverId(hrAdminId)
                .workDate(workDate).requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(14, 0)))
                .reason("Second").status("PENDING").build();
        when(webClockInRepository.findByStatus("PENDING")).thenReturn(List.of(firstCycle, secondCycle));

        List<WebClockInResponse> pending = service.listPendingForApprover(hrAdminEmail);

        assertEquals(1, pending.size());
        assertEquals(firstCycle.getId(), pending.get(0).getId());
    }
}
