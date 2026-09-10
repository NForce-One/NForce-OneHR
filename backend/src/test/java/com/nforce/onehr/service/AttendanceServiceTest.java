package com.nforce.onehr.service;

import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers two related but distinct guards on a forgotten-open session:
 *  - Still within its own logical workday (per ShiftDayPolicy, shift-relative): a late-arriving
 *    checkout is still accepted, but capped at the shift's own natural end — the "27h 8m" bug
 *    fix — never at the actual (possibly much later) click time.
 *  - Past its own logical workday entirely: no longer accepted or capped at all — flagged Missing
 *    Check-Out instead, with no fabricated checkOutAt or computed workedMinutes.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

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

    // The default employee's assigned Shift (set up in setUp()) — a field, not a setUp()-local,
    // so individual @Test methods can reference its id for Attendance fixtures' .shiftId(...),
    // exactly as AttendanceInterpretationService.interpretExistingSession now requires for any
    // "existing record" test scenario (checkout, staleness, resume) to resolve as RESOLVED rather
    // than LEGACY_UNRESOLVED.
    private Shift defaultShift;

    // Backs the fake EmployeeShiftAssignmentResolver below — the shared test employee's "as of
    // right now" assignment for the day-aware interpretFreshAction/getConfig/historyFor call
    // sites. A test that reassigns the employee to a different Shift for a FRESH check-in/out (no
    // prior Attendance record) must update this alongside the Employee fixture's own .shift(...);
    // a test resolving against an EXISTING record's own snapshotted shiftId never touches it.
    private Shift currentEmployeeShift;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    // A minimal, real (not mocked) Shift Version resolver — a single-version-per-shift, in-memory
    // "latest effectiveFrom <= day" lookup, exactly mirroring ShiftVersionRepository's own query
    // semantics — see shift() below.
    private final List<ShiftVersion> shiftVersions = new java.util.ArrayList<>();

    /** Builds a Shift (with a real id) and registers a single version effective from the dawn of time — the common case for every test in this file, none of which exercise Shift Versioning itself (see ShiftDayPolicyTest/OrgServiceShiftVersionTest for that). */
    private Shift shift(String name, LocalTime start, LocalTime end) {
        return shift(name, start, end, DEFAULT_TEST_GRACE_MINUTES);
    }

    // Matches the pre-migration global app.attendance.late-grace-minutes default (10).
    private static final int DEFAULT_TEST_GRACE_MINUTES = 10;

    private Shift shift(String name, LocalTime start, LocalTime end, int graceMinutes) {
        Shift s = Shift.builder().id(UUID.randomUUID()).name(name).build();
        shiftVersions.add(ShiftVersion.builder().shift(s).startTime(start).endTime(end)
                .lateGraceMinutes(graceMinutes).effectiveFrom(LocalDate.MIN).build());
        return s;
    }

    @BeforeEach
    void setUp() {
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(java.math.BigDecimal.valueOf(18)).build()));
        ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
            @Override
            public Optional<ShiftVersion> resolveIfPresent(Shift s, LocalDate workDate) {
                return shiftVersions.stream()
                        .filter(v -> v.getShift().getId().equals(s.getId()))
                        .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                        .max(java.util.Comparator.comparing(ShiftVersion::getEffectiveFrom));
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
        // Resolves any Shift created via this test file's own shift() helper below — so a fixture
        // that sets Attendance.shiftId to one of those shifts' ids resolves correctly through
        // AttendanceInterpretationService.interpretExistingSession, exactly like the real
        // ShiftRepository would.
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

        defaultShift = shift("Regular", LocalTime.of(15, 30), LocalTime.of(0, 30));
        currentEmployeeShift = defaultShift;
        // Active, non-deleted User by default — every checkIn/checkOut test implicitly exercises
        // assertEligibleToPunch's gate; tests that specifically want an inactive/deleted account
        // override this with their own Employee/User fixture.
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(true).build()).build();
        lenient().when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendanceRepository.saveAndFlush(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(any()))
                .thenReturn(Optional.empty());
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    /**
     * Stubs {@code record} as the employee's currently-open NORMAL session — checkIn/checkOut/
     * getToday now find this via an open AttendancePunch (see AttendanceService
     * .findOpenNormalAttendance), not a direct query on the Attendance row itself, since Web
     * Clock-In sessions are tracked entirely independently and must never be mistaken for one.
     */
    private void stubOpenNormalSession(Attendance record) {
        LocalDateTime punchCheckIn = record.getSessionStartedAt() != null ? record.getSessionStartedAt() : record.getCheckInAt();
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID())
                .attendanceRecordId(record.getId())
                .checkInAt(punchCheckIn)
                .build();
        lenient().when(attendancePunchRepository.findOpenByEmployeeUserId(employeeId)).thenReturn(List.of(openPunch));
        lenient().when(attendanceRepository.findById(record.getId())).thenReturn(Optional.of(record));
        // A genuinely open normal punch — see flagMissingCheckoutIfStale's hasAnyOpenSession guard,
        // which this record must satisfy to ever be eligible for staleness detection at all.
        lenient().when(attendancePunchRepository.existsByAttendanceRecordIdAndCheckOutAtIsNull(record.getId()))
                .thenReturn(true);
    }

    // ── Phase 0.2: active/deleted employee punch gate ────────────────────────

    @Test
    void checkIn_activeEmployee_isAllowed() {
        assertDoesNotThrow(() -> service.checkIn(employeeEmail, null));
    }

    @Test
    void checkIn_deactivatedEmployee_isRejected() {
        Employee deactivated = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(false).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(deactivated));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.checkIn(employeeEmail, null));
        assertTrue(ex.getMessage().toLowerCase().contains("inactive"));
        verify(attendanceRepository, never()).save(any(Attendance.class));
        verify(attendanceRepository, never()).saveAndFlush(any(Attendance.class));
    }

    @Test
    void checkIn_deletedEmployee_isRejected() {
        Employee deleted = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(true).deletedAt(java.time.Instant.now()).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(deleted));

        assertThrows(IllegalArgumentException.class, () -> service.checkIn(employeeEmail, null));
        verify(attendanceRepository, never()).save(any(Attendance.class));
        verify(attendanceRepository, never()).saveAndFlush(any(Attendance.class));
    }

    @Test
    void checkOut_deactivatedEmployee_isRejected() {
        Employee deactivated = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(false).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(deactivated));

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));
        verify(attendancePunchRepository, never()).save(any(AttendancePunch.class));
    }

    @Test
    void checkOut_deletedEmployee_isRejected() {
        Employee deleted = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(true).deletedAt(java.time.Instant.now()).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(deleted));

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));
    }

    @Test
    void checkOut_rejectsAndFlagsMissingCheckout_whenSessionWasLeftOpenForOverADay() {
        // Checked in two days ago at 5:35 PM, last resumed at 6:00 PM, and never checked out.
        // This used to be silently accepted with worked-minutes capped at the shift's natural
        // end (the "27h 8m" bug fix) — but two days is unambiguously past the grace window, so it
        // is no longer accepted or capped at all: no fabricated checkOutAt, no computed
        // workedMinutes, just flagged for correction via regularization.
        LocalDate workDate = currentShiftDay().minusDays(2);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(17, 35));
        LocalDateTime sessionStart = LocalDateTime.of(workDate, LocalTime.of(18, 0));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(sessionStart)
                .lateByMinutes(125)
                .status("LATE")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(open);

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

        assertEquals("MISSING_CHECKOUT", open.getStatus());
        assertNull(open.getCheckOutAt());
        assertNull(open.getWorkedMinutes());
    }

    // ── Org-wide stale sweep (flagAllStaleOpenSessionsAsMissingCheckout) ─────
    // Attendance.checkOutAt == null is NOT the same thing as "this day is still open" — a
    // Web-Clock-In-only day has a permanently-null checkOutAt (WebClockInService.checkOut
    // deliberately never writes it) even once fully, correctly completed. These tests lock in
    // that the org-wide sweep — whose candidate query is exactly checkOutAt IS NULL — never
    // mistakes an already-closed Web-only day for a forgotten normal checkout.

    @Test
    void sweep_neverFlagsAWebOnlyDay_thatWasAlreadyProperlyClosedViaWebClockOut() {
        LocalDate workDate = currentShiftDay().minusDays(5);
        Attendance webOnlyDay = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(15, 40)))
                .checkOutAt(null) // Web Clock-Out never sets this — see WebClockInService.checkOut
                .workedMinutes(480)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .source("WEB_REMOTE")
                .build();
        when(attendanceRepository.findByCheckOutAtIsNull()).thenReturn(List.of(webOnlyDay));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).shift(defaultShift)
                        .user(User.builder().id(employeeId).active(true).build()).build()));
        // No open normal punch...
        when(attendancePunchRepository.existsByAttendanceRecordIdAndCheckOutAtIsNull(webOnlyDay.getId()))
                .thenReturn(false);
        // ...and the day's Web session is itself already checked out (not open).
        when(webClockInRequestRepository.existsByEmployeeUserIdAndWorkDateAndCheckedOutAtIsNull(employeeId, workDate))
                .thenReturn(false);

        service.flagAllStaleOpenSessionsAsMissingCheckout();

        assertEquals("PRESENT", webOnlyDay.getStatus(), "an already-completed Web-only day must never be flipped to MISSING_CHECKOUT");
        verify(attendanceRepository, never()).saveAndFlush(any(Attendance.class));
    }

    @Test
    void sweep_stillFlagsAGenuinelyOpenWebSession_thatWasNeverCheckedOut() {
        LocalDate workDate = currentShiftDay().minusDays(5);
        Attendance openWebDay = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(15, 40)))
                .checkOutAt(null)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .source("WEB_REMOTE")
                .build();
        when(attendanceRepository.findByCheckOutAtIsNull()).thenReturn(List.of(openWebDay));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).shift(defaultShift)
                        .user(User.builder().id(employeeId).active(true).build()).build()));
        when(attendancePunchRepository.existsByAttendanceRecordIdAndCheckOutAtIsNull(openWebDay.getId()))
                .thenReturn(false);
        // The Web session itself is genuinely still open (never Web-Clocked-Out).
        when(webClockInRequestRepository.existsByEmployeeUserIdAndWorkDateAndCheckedOutAtIsNull(employeeId, workDate))
                .thenReturn(true);

        service.flagAllStaleOpenSessionsAsMissingCheckout();

        assertEquals("MISSING_CHECKOUT", openWebDay.getStatus(), "a genuinely forgotten Web Clock-Out must still be flagged stale");
    }

    @Test
    void sweep_stillFlagsAGenuinelyOpenNormalSession_regressionCheck() {
        LocalDate workDate = currentShiftDay().minusDays(5);
        Attendance openNormalDay = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(15, 40)))
                .checkOutAt(null)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        when(attendanceRepository.findByCheckOutAtIsNull()).thenReturn(List.of(openNormalDay));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).shift(defaultShift)
                        .user(User.builder().id(employeeId).active(true).build()).build()));
        // A genuinely open normal punch under this exact record.
        when(attendancePunchRepository.existsByAttendanceRecordIdAndCheckOutAtIsNull(openNormalDay.getId()))
                .thenReturn(true);

        service.flagAllStaleOpenSessionsAsMissingCheckout();

        assertEquals("MISSING_CHECKOUT", openNormalDay.getStatus(), "a genuinely forgotten normal Check-Out must still be flagged stale");
    }

    @Test
    void checkOut_recordsTheActualClickTime_butStillCapsWorkedMinutesAtShiftEnd_forALateClickWithinTheGraceWindow() {
        // Only meaningful between the shift's natural end (12:30 AM) and 7:00 AM — the narrow
        // real-time window where a late-but-still-correctable click's WORKED-MINUTES figure needs
        // capping (so it doesn't inflate into something like "27h 8m"), without that cap ever
        // touching the stored checkOutAt itself — the actual click time is always what gets
        // recorded, everywhere (this was a real reported bug: checkout showing as exactly the
        // shift's end time for every late-but-legitimate click). This window's upper bound (7 AM)
        // is now purely a property of this test's own currentShiftDay() helper below (still
        // deliberately pinned to the old fixed cutover, only to keep workDate self-consistent with
        // beforeNow/shiftEnd here) — NOT a production concept anymore: the real logical-workday
        // reset for this shift (15:30-00:30) is 09:30, strictly later, so whenever this test does
        // run, checkOut() is guaranteed to still be well within its own logical workday. Outside
        // this window the scenario doesn't apply, so the test is skipped rather than asserting
        // something time-dependent as if it always holds — see
        // checkOut_rejectsAndFlagsMissingCheckout_... and checkOut_usesActualClickTime_... for the
        // two deterministic (always-applicable) cases on either side of this window.
        LocalDateTime beforeNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate workDate = currentShiftDay();
        LocalDateTime shiftEnd = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(0, 30));
        Assumptions.assumeTrue(beforeNow.isAfter(shiftEnd), "only applicable between shift end (12:30 AM) and 7:00 AM");

        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(17, 35));
        LocalDateTime sessionStart = LocalDateTime.of(workDate, LocalTime.of(18, 0));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(sessionStart)
                .lateByMinutes(125)
                .status("LATE")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(open);
        // The SAME punch instance backs both lookups closeSession/recomputeCombinedWorkedMinutes
        // make: findFirst...OrderByCheckInAtDesc (closeSession closes it) and
        // findByAttendanceRecordIdOrderByCheckInAtAsc (collectPunches sums it) — closeSession's
        // own punch.setCheckOutAt(...) mutation must be visible to the second lookup, or the
        // worked-minutes sum sees no closed punches at all and silently returns 0.
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID())
                .attendanceRecordId(open.getId())
                .checkInAt(sessionStart)
                .build();
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(open.getId()))
                .thenReturn(Optional.of(openPunch));
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(open.getId()))
                .thenReturn(List.of(openPunch));

        AttendanceResponse response = service.checkOut(employeeEmail, null);
        LocalDateTime afterNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        int expectedMinutes = (int) Math.round(Duration.between(sessionStart, shiftEnd).getSeconds() / 60.0);
        assertFalse(response.getCheckOutAt().isBefore(beforeNow), "checkOutAt must be the real click time, not before the click");
        assertFalse(response.getCheckOutAt().isAfter(afterNow), "checkOutAt must be the real click time, not after the click");
        assertEquals(expectedMinutes, response.getWorkedMinutes());
    }

    /**
     * The unified-grace fix: closeSession's own "short day overrides LATE" status finalization
     * used to recompute LATE/PRESENT from the raw `lateByMinutes > 0` figure, ignoring the shift's
     * own allowed-late privilege entirely — silently flipping an already-correctly-graced PRESENT
     * arrival to LATE the moment the shift naturally ended. Check-in at 09:05 against a 09:00
     * shift with a 10-minute privilege is genuinely NOT late (lateByMinutes is still 5, the raw,
     * no-forgiveness display figure) — a full day worked past the shift's own end must still
     * finalize as PRESENT, not LATE.
     */
    @Test
    void checkOut_atOrAfterShiftEnd_checkInWithinGrace_finalizesAsPresent_neverFlipsToLateFromRawLateByMinutes() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(18, 5).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift dayShift = shift("Day Shift", LocalTime.of(9, 0), LocalTime.of(18, 0)); // grace = DEFAULT_TEST_GRACE_MINUTES (10)
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(dayShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(9, 5));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(5)
                .status("PRESENT")
                .shiftId(dayShift.getId())
                .build();
        stubOpenNormalSession(open);
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID())
                .attendanceRecordId(open.getId())
                .checkInAt(checkInAt)
                .build();
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(open.getId()))
                .thenReturn(Optional.of(openPunch));
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(open.getId()))
                .thenReturn(List.of(openPunch));

        AttendanceResponse response = service.checkOut(employeeEmail, null);

        assertEquals("PRESENT", response.getStatus(),
                "5 minutes late is within the shift's 10-minute allowed-late privilege — must stay "
                        + "PRESENT even once the shift naturally ends, never flip to LATE from raw lateByMinutes");
    }

    @Test
    void checkOut_usesActualClickTime_whenCheckoutHappensWithinTheSameShift() {
        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(15, 40));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(open);

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalDateTime before = LocalDateTime.now(istZone);
        AttendanceResponse response = service.checkOut(employeeEmail, null);
        LocalDateTime after = LocalDateTime.now(istZone);

        // A normal same-shift checkout is unaffected by the cap: checkOutAt is the real click
        // time, not clamped to the shift-end boundary.
        assertFalse(response.getCheckOutAt().isBefore(before));
        assertFalse(response.getCheckOutAt().isAfter(after));
    }

    /**
     * The core regression this whole Shift-snapshot design exists to fix: an employee checks in
     * under Shift A (15:30-00:30, overnight), is REASSIGNED to a different Shift B (09:00-18:00,
     * a same-day shift) while the session is still open, then checks out. The worked-minutes cap
     * must still reflect Shift A's own end (00:30 the next day) — never Shift B's (18:00 the same
     * day) — because the open record's {@code shiftId} was snapshotted at check-in time and is
     * never re-resolved against the employee's now-current Shift.
     */
    @Test
    void checkOut_afterReassignmentWhileSessionWasOpen_stillCapsWorkedMinutesAtTheOriginalShiftsEnd() {
        // Deterministic offset so "now" (at actual checkout time) reads as exactly 2:00 AM local,
        // regardless of when this test really runs — same technique as the overnight tests above.
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(2, 0).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId())).minusDays(1);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(15, 30));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId()) // snapshotted under Shift A (15:30-00:30) at check-in time
                .build();
        stubOpenNormalSession(open);

        // Reassignment happens AFTER check-in, while the session is still open: the employee's
        // CURRENT/live shift (and Location, carrying the deterministic offset — record has no
        // stored timezone of its own, so resolveZone falls back to this) is now a same-day
        // 09:00-18:00 shift — but must never be consulted for this already-open session's cutoff
        // (see AttendanceInterpretationService.interpretExistingSession).
        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift reassignedDayShift = shift("Day Shift (reassigned)", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Employee reassignedEmployee = Employee.builder().userId(employeeId).employeeCode("E1")
                .fullName("Test Employee").shift(reassignedDayShift).location(location)
                .user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(reassignedEmployee));

        // A single, still-open punch spanning past BOTH shifts' ends — "now" (2:00 AM the next
        // day) is well past Shift A's own 00:30 end but also well past Shift B's 18:00 end (the
        // previous day), so the two cutoffs produce clearly different capped totals (540 vs 150
        // minutes) if the bug were present.
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID()).attendanceRecordId(open.getId()).checkInAt(checkInAt).build();
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(open.getId()))
                .thenReturn(Optional.of(openPunch));
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(open.getId()))
                .thenReturn(List.of(openPunch));

        AttendanceResponse response = service.checkOut(employeeEmail, null);

        assertEquals(540, response.getWorkedMinutes(),
                "must be capped at Shift A's own end (00:30 next day = 9h from 15:30), not Shift B's (18:00 same day = 2.5h)");
    }

    /**
     * The mirror case: a FRESH check-in after reassignment correctly uses the NEW current Shift —
     * there is no prior context for this date to preserve. Uses the same deterministic
     * browser-offset-via-Location-timezone trick as the overnight-lateness test above so "now"
     * reads as a fixed 9:20 AM local, regardless of when this test actually runs.
     */
    @Test
    void checkIn_afterReassignment_usesTheNewCurrentShift_forAFreshDayWithNoPriorContext() {
        java.time.LocalDateTime utcNow = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(9, 20).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        java.time.ZoneOffset offset = java.time.ZoneOffset.ofTotalSeconds(offsetSeconds);

        // Reassigned FROM the default overnight shift (15:30-00:30, under which 9:20 AM would read
        // as PRESENT/not-late) TO a day shift starting at 09:00 — 20 minutes past its start, past
        // the 10-minute default grace, so LATE proves the NEW shift was actually consulted.
        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift reassignedDayShift = shift("Day Shift (reassigned)", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Employee reassignedEmployee = Employee.builder().userId(employeeId).employeeCode("E1")
                .fullName("Test Employee").shift(reassignedDayShift).location(location)
                .user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(reassignedEmployee));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        currentEmployeeShift = reassignedDayShift; // the fresh check-in must resolve via the NEW assignment

        AttendanceResponse resp = service.checkIn(employeeEmail, null);

        assertEquals("LATE", resp.getStatus(), "must be judged against the NEW shift's 9:00 start, not the old 15:30 one");
        assertTrue(resp.getLateByMinutes() > 0 && resp.getLateByMinutes() < 30);
    }

    // ---------------------------------------------------------------- missing check-out

    /**
     * A test-local stand-in for "the shift-day 'right now' belongs to", computed independently of
     * the service so tests can construct a workDate that deterministically IS or ISN'T past its
     * own grace window, regardless of what real wall-clock time the suite happens to run at.
     * Deliberately still pinned to a fixed 7:00 AM cutover — NOT a production concept anymore
     * (see ShiftDayPolicy, which derives the real boundary from the employee's own shift, e.g.
     * 09:30 for this file's default 15:30-00:30 employee) — purely so this helper stays a fixed,
     * self-consistent reference point for the tests below. Every test using it only relies on the
     * resulting workDate being unambiguously "N shift-days ago" or "still within a narrow window
     * strictly before the real, shift-relative boundary", both of which hold regardless of this
     * helper's exact cutover value.
     */
    private static LocalDate currentShiftDay() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        return now.toLocalTime().isBefore(LocalTime.of(7, 0)) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    @Test
    void getToday_flagsMissingCheckout_forStaleOpenSession_andReportsFreshCanCheckIn() {
        // Checked in 6 shift-days ago and never checked out — the exact shape of a forgotten
        // session that would otherwise show as "still checked in" forever (see
        // flagMissingCheckoutIfStale). Always past its own grace window regardless of the current
        // time of day.
        LocalDate workDate = currentShiftDay().minusDays(6);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(18, 4));
        Attendance stale = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(stale);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

        // Flagged Missing Check-Out — never a fabricated checkOutAt or computed workedMinutes;
        // the real check-out time is unknown, so none is guessed.
        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt(), "a missing check-out must never be assigned a fabricated check-out time");
        assertNull(stale.getWorkedMinutes(), "worked hours must never be computed from an assumed check-out");
        // ...and today's own state is reported fresh: nothing from 6 shift-days ago blocks a new
        // check-in today.
        assertTrue(response.isCanCheckIn(), "an old, now-flagged session must not block today's check-in");
        assertFalse(response.isCanCheckOut(), "a flagged Missing Check-Out record must not offer Check Out");
        assertNull(response.getRecord());
    }

    @Test
    void getToday_leavesGenuineOvernightSessionOpen_whenStillWithinGraceWindow() {
        // Checked in during the current shift-day (however long ago that shift started) and
        // hasn't yet crossed its own logical-workday reset (see ShiftDayPolicy) — the legitimate
        // midnight-crossing case, not a stale session, regardless of what time this test runs at.
        LocalDate workDate = currentShiftDay();
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(15, 40));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(open);

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

        assertEquals("PRESENT", open.getStatus(), "a session still within its own grace window must not be flagged");
        assertNull(open.getCheckOutAt());
        assertFalse(response.isCanCheckIn());
        assertTrue(response.isCanCheckOut());
        assertNotNull(response.getRecord());
    }

    @Test
    void checkIn_flagsMissingCheckoutForStaleOpenSession_thenProceedsWithFreshCheckIn() {
        LocalDate staleWorkDate = currentShiftDay().minusDays(6);
        LocalDateTime staleCheckInAt = LocalDateTime.of(staleWorkDate, LocalTime.of(18, 4));
        Attendance stale = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .checkInAt(staleCheckInAt)
                .sessionStartedAt(staleCheckInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(stale);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());

        // Must not throw "You have already checked in today" — the stale session is flagged
        // Missing Check-Out first, then a brand-new attendance record is created for today, same
        // as if there had been no prior record at all.
        AttendanceResponse response = service.checkIn(employeeEmail, null);

        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt(), "a missing check-out must never be assigned a fabricated check-out time");
        assertNotEquals(stale.getId(), response.getId(), "a fresh check-in must open a new record, not reuse the stale one");
        assertNull(response.getCheckOutAt());
    }

    @Test
    void checkOut_rejectsExplicitClick_onceSessionIsPastItsGraceWindow() {
        // The frontend should never offer Check Out for a record this stale (getToday reports
        // canCheckOut=false for it) — but a request that arrives anyway must not be allowed to
        // fabricate a checkout, and must instead flag the record and reject the click.
        LocalDate staleWorkDate = currentShiftDay().minusDays(6);
        LocalDateTime staleCheckInAt = LocalDateTime.of(staleWorkDate, LocalTime.of(18, 4));
        Attendance stale = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .checkInAt(staleCheckInAt)
                .sessionStartedAt(staleCheckInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(stale);

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt());
        assertNull(stale.getWorkedMinutes());
    }

    // ---------------------------------------------------------------- browser timezone

    @Test
    void checkIn_ignoresBrowserReportedTimezone_alwaysUsesTheEmployeesConfiguredZone() {
        // No Location on this employee (see setUp), so the employee's own zone is the global
        // default (Asia/Kolkata). A browser reporting a completely different zone
        // ("Australia/Adelaide", a genuine IANA zone, UTC+9:30/+10:30 — a half-hour offset
        // distinct from IST's own +5:30) must be ignored entirely: per explicit requirement, the
        // employee's own assigned Location timezone (or the global default, absent one) is the
        // ONLY source for their attendance clock — never the viewer's/browser's own zone.
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any()))
                .thenReturn(Optional.empty());

        AttendanceResponse response = service.checkIn(employeeEmail, "Australia/Adelaide");

        assertEquals("Asia/Kolkata", response.getTimezone(),
                "the browser-reported zone must never override the employee's own configured zone");
        LocalDateTime expectedNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        assertEquals(expectedNow.toLocalDate(), response.getCheckInAt().toLocalDate());
        assertTrue(Duration.between(response.getCheckInAt(), expectedNow).abs().toSeconds() < 5,
                "checkInAt must reflect the employee's own configured zone's wall clock, never the browser's");
    }

    @Test
    void checkIn_ignoresInvalidBrowserTimezone_fallsBackToConfiguredZone() {
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any()))
                .thenReturn(Optional.empty());

        // Not a real IANA zone — must not throw, must fall back silently (this employee has no
        // Location, so the fallback is the global default zone, "Asia/Kolkata").
        AttendanceResponse response = service.checkIn(employeeEmail, "not-a-real-timezone");

        assertEquals("Asia/Kolkata", response.getTimezone());
    }

    // ── Finalized Location/Timezone model: Location is the sole source ───────
    // Employee carries no timezone field of its own (see Employee's class Javadoc) — its
    // effective attendance zone is ALWAYS its Location's, with the org-wide default only for an
    // employee who has no Location at all. See AttendanceRulesService#resolveEmployeeZoneId.

    @Test
    void checkIn_resolvesFromAssignedLocationTimezone() {
        Location location = Location.builder().name("Office").timezone("America/New_York").build();
        Employee employeeWithLocation = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).location(location)
                .user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employeeWithLocation));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());

        AttendanceResponse response = service.checkIn(employeeEmail, null);

        assertEquals("America/New_York", response.getTimezone());
    }

    @Test
    void checkIn_noLocationAssigned_fallsBackToTheOrgWideDefault() {
        // setUp()'s default employee has no Location at all.
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());

        AttendanceResponse response = service.checkIn(employeeEmail, null);

        assertEquals("Asia/Kolkata", response.getTimezone(), "matches this test class's AttendanceRules.defaultTimezone fixture");
    }

    @Test
    void checkIn_locationTimezoneChange_neverReinterpretsAnAlreadyClosedHistoricalRecord() {
        // A prior, already-closed Attendance row keeps its own locked-in timezone regardless of
        // any later change to the employee's Location (or its timezone) — see Attendance.timezone's
        // own write-once semantics. This is a read-only display path (getToday's closed-record
        // branch), not a recompute, so there is nothing here that COULD reinterpret it.
        Attendance historical = Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(LocalDate.now()).checkInAt(LocalDateTime.now().minusHours(9))
                .checkOutAt(LocalDateTime.now().minusHours(1)).timezone("America/New_York")
                .status("PRESENT").shiftId(defaultShift.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.of(historical));

        service.getToday(employeeEmail, null);

        assertEquals("America/New_York", historical.getTimezone(), "a Location change must never rewrite an existing record's own locked-in zone");
    }

    // ── My Team "Not in yet today" roster: location must never affect the check-in verdict ──
    // (defect/353-357). getDayForMyTeam/getDayForPeers query a widened +/-1 business-zone-day
    // window and resolveRosterRecord picks the right row out of it — see that method's own
    // Javadoc for why an exact-day-only match used to misclassify a genuinely checked-in
    // employee as "not in yet" whenever their Location's timezone crossed midnight at a
    // different moment than the org's business zone.

    @Test
    void getDayForMyTeam_employeeCheckedInSameBusinessDay_excludedFromNotInYet() {
        Employee employeeNoLocation = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(true).build()).build();
        UUID managerId = UUID.randomUUID();
        String managerEmail = "manager@test.com";
        Employee manager = Employee.builder().userId(managerId).employeeCode("M1").fullName("Manager")
                .user(User.builder().id(managerId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(employeeId));
        when(employeeRepository.findAllById(List.of(employeeId))).thenReturn(List.of(employeeNoLocation));

        LocalDate day = LocalDate.of(2026, 1, 15);
        Attendance checkedIn = Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(day).checkInAt(day.atTime(9, 0)).timezone("Asia/Kolkata").status("PRESENT")
                .shiftId(defaultShift.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(
                List.of(employeeId), day.minusDays(1), day.plusDays(1)))
                .thenReturn(List.of(checkedIn));

        List<AttendanceResponse> roster = service.getDayForMyTeam(managerEmail, day);

        assertEquals(1, roster.size());
        assertNotNull(roster.get(0).getCheckInAt());
        assertEquals(day, roster.get(0).getWorkDate());
    }

    @Test
    void getDayForMyTeam_employeeCheckedInAtDifferentLocationTimezone_stillExcludedFromNotInYet() {
        // Reference zone (standing in for the business day the roster is queried for -- passed
        // explicitly below, so the org-default-timezone stub from setUp() is never consulted)
        // and this employee's Location zone are deliberately chosen 26 hours apart (more than a
        // full day), so their calendar dates are guaranteed to differ right now no matter when
        // this test runs -- no Assumptions.assumeTrue skip needed (see this file's shiftEnd test
        // above for the alternative this sidesteps).
        Location location = Location.builder().name("Baker Island Office").timezone("Etc/GMT+12").build();
        Employee employeeWithLocation = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        UUID managerId = UUID.randomUUID();
        String managerEmail = "manager@test.com";
        Employee manager = Employee.builder().userId(managerId).employeeCode("M1").fullName("Manager")
                .user(User.builder().id(managerId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(employeeId));
        when(employeeRepository.findAllById(List.of(employeeId))).thenReturn(List.of(employeeWithLocation));

        LocalDate businessDay = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
        LocalDate employeeOwnDay = LocalDate.now(ZoneId.of("Etc/GMT+12"));
        assertNotEquals(businessDay, employeeOwnDay, "test setup requires the two zones' calendar dates to actually differ");

        Attendance checkedIn = Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(employeeOwnDay).checkInAt(LocalDateTime.now().minusHours(1))
                .timezone("Etc/GMT+12").status("PRESENT").shiftId(defaultShift.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(
                List.of(employeeId), businessDay.minusDays(1), businessDay.plusDays(1)))
                .thenReturn(List.of(checkedIn));

        List<AttendanceResponse> roster = service.getDayForMyTeam(managerEmail, businessDay);

        assertEquals(1, roster.size());
        assertNotNull(roster.get(0).getCheckInAt(),
                "an employee who already checked in must never show as 'not in yet' just because their Location's timezone differs from the business zone");
    }

    @Test
    void getDayForMyTeam_noAttendanceRecordInWindow_stillShowsNotInYet() {
        Employee employeeNoRecord = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).user(User.builder().id(employeeId).active(true).build()).build();
        UUID managerId = UUID.randomUUID();
        String managerEmail = "manager@test.com";
        Employee manager = Employee.builder().userId(managerId).employeeCode("M1").fullName("Manager")
                .user(User.builder().id(managerId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(employeeId));
        when(employeeRepository.findAllById(List.of(employeeId))).thenReturn(List.of(employeeNoRecord));

        LocalDate day = LocalDate.of(2026, 1, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(
                List.of(employeeId), day.minusDays(1), day.plusDays(1)))
                .thenReturn(List.of());

        List<AttendanceResponse> roster = service.getDayForMyTeam(managerEmail, day);

        assertEquals(1, roster.size());
        assertNull(roster.get(0).getCheckInAt());
        assertEquals(day, roster.get(0).getWorkDate());
    }

    // ── Phase 0.4 / Phase 1: concurrency race -> clean application response ──

    @Test
    void checkIn_concurrentFreshCheckIn_databaseConstraintViolation_translatesToTheCleanDuplicateMessage() {
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        // Simulates the race: this call's own pre-check passed, but by the time it inserts, a
        // concurrent request already committed the same (employee, workDate) row first.
        when(attendanceRepository.saveAndFlush(any(Attendance.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.checkIn(employeeEmail, null));
        assertEquals("You have already checked in today", ex.getMessage());
    }

    @Test
    void checkIn_resumeBranch_concurrentDuplicateOpenPunch_translatesToTheCleanDuplicateMessage() {
        // The resume (existing closed record) branch's own race: two concurrent resume-check-ins
        // both pass the Java-level open-punch scan, then the DB-level partial unique index
        // (idx_attendance_punches_one_open_per_record, V165) rejects the second insert.
        Attendance existing = Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(LocalDate.now()).checkInAt(LocalDateTime.now().minusHours(2))
                .checkOutAt(LocalDateTime.now().minusHours(1)).status("PRESENT").shiftId(defaultShift.getId()).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.of(existing));
        when(attendancePunchRepository.saveAndFlush(any(AttendancePunch.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.checkIn(employeeEmail, null));
        assertEquals("You have already checked in today", ex.getMessage());
    }

    @Test
    void checkOut_concurrentCheckout_optimisticLockConflict_propagatesUncaught_forGlobalExceptionHandlerToTranslate() {
        // @Version's failure mode (ObjectOptimisticLockingFailureException) is translated to a
        // clean 409 by GlobalExceptionHandler, already-established for LeaveBalance (V153). This
        // test proves AttendanceService itself doesn't swallow or mask that exception when the
        // final save() inside closeSession conflicts — it must propagate uncaught, exactly like
        // any other Spring Data repository call, for that global handler to catch.
        // Deterministic offset so "now" reads as exactly 16:00 local (comfortably inside the
        // default 15:30-00:30 shift, no staleness/boundary edge cases) regardless of when this
        // test actually runs — same technique as the reassignment test above.
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(16, 0).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);
        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(15, 35));
        Employee employeeWithZone = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(defaultShift).location(Location.builder().name("Test Location").timezone(offset.getId()).build())
                .user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employeeWithZone));
        Attendance open = Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .workDate(workDate).checkInAt(checkInAt).sessionStartedAt(checkInAt)
                .status("PRESENT").shiftId(defaultShift.getId()).build();
        stubOpenNormalSession(open);
        when(attendanceRepository.save(any(Attendance.class))).thenThrow(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(Attendance.class, open.getId().toString()));

        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> service.checkOut(employeeEmail, null));
    }

    @Test
    void checkOut_usesTheSessionsLockedInZone_ignoringADifferentBrowserZoneAtCheckoutTime() {
        // Checked in from a UTC+10:30 browser (Lord Howe Island standard time) earlier today
        // (that shift-day, per the locked zone) — session still open.
        LocalDate workDate = LocalDate.now(ZoneId.of("Australia/Lord_Howe"));
        LocalDateTime checkInAt = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe")).minusHours(1);
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .timezone("Australia/Lord_Howe")
                .shiftId(defaultShift.getId())
                .build();
        stubOpenNormalSession(open);

        // Check-out click arrives from a browser now reporting a completely different zone
        // (e.g. a VPN, or genuine travel) — must NOT be used; only the session's own locked zone
        // ("Australia/Lord_Howe") may compute this checkout, so worked-minutes stays correct.
        LocalDateTime beforeLordHowe = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe"));
        AttendanceResponse response = service.checkOut(employeeEmail, "America/New_York");
        LocalDateTime afterLordHowe = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe"));

        assertFalse(response.getCheckOutAt().isBefore(beforeLordHowe));
        assertFalse(response.getCheckOutAt().isAfter(afterLordHowe));
        assertTrue(response.getWorkedMinutes() < 120, "roughly the 1-hour session, not skewed by the mismatched browser zone");
    }

    /**
     * A pure LocalTime-of-day comparison (the old bug) silently breaks lateness for any check-in
     * that has crossed midnight relative to an overnight shift: 1:00 AM as a bare LocalTime reads
     * as "before" a 20:30 shift start, so it would wrongly compute 0 minutes late / PRESENT for a
     * check-in that's actually ~4.5 hours late. Uses a browser-timezone OFFSET chosen so "now" is
     * always exactly 1:00 AM local, regardless of when this test actually runs — avoids a flaky
     * dependency on real wall-clock time while still exercising the exact scenario.
     */
    @Test
    void checkIn_computesLatenessCorrectly_forACheckInThatHasCrossedMidnightOnAnOvernightShift() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(1, 0).toSecondOfDay();
        int nowSecondOfDay = utcNow.toLocalTime().toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - nowSecondOfDay;
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        // The deterministic "now reads as 1:00 AM" trick now has to come from the employee's own
        // configured Location.timezone, not the browser-reported clientTimezone — resolveZone no
        // longer consults the latter at all (Location is the ONLY source, per explicit
        // requirement). ZoneId.of accepts a bare numeric offset ("+05:30" etc.) just as well as a
        // real IANA region name, so this is otherwise the exact same technique as before.
        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        currentEmployeeShift = overnightShift; // the fresh check-in must resolve via this assignment

        AttendanceResponse resp = service.checkIn(employeeEmail, null);

        assertEquals("LATE", resp.getStatus());
        assertTrue(resp.getLateByMinutes() > 200,
                "expected several hours late (shift started 20:30 the previous day), was " + resp.getLateByMinutes());
    }

    // ---------------------------------------------------------------- half day / full day timing

    /**
     * A checkout well before the shift's own natural end is a resumable break (checkIn's own
     * "resume" branch explicitly allows checking in again later the same shift/day) — HALF_DAY
     * must not be judged this early just because worked-minutes-so-far happens to be low. Uses
     * the same deterministic offset trick as the overnight-lateness test above: "now" reads as
     * 8:36 PM, six minutes into a 20:30-05:30 shift — nowhere near its 5:30 AM end.
     */
    @Test
    void checkOut_beforeShiftEnd_doesNotFinalizeHalfDay_evenWithLowWorkedMinutes() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(20, 36).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(20, 30));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(overnightShift.getId())
                .build();
        stubOpenNormalSession(open);

        AttendanceResponse resp = service.checkOut(employeeEmail, null);

        assertNotEquals("HALF_DAY", resp.getStatus(),
                "shift hasn't ended yet (20:36, shift ends 05:30) — must not judge the day as HALF_DAY this early");
        assertEquals("PRESENT", resp.getStatus(), "status should stay whatever check-in set until the shift actually ends");
    }

    /**
     * Once the shift has actually reached its own natural end, a genuinely short day (checked in
     * right at the very end, checked out minutes later — never came back) must finalize as
     * HALF_DAY: there's no more opportunity to resume. "now" reads as 5:35 AM, five minutes past
     * the 20:30-05:30 shift's own end.
     */
    @Test
    void checkOut_atOrAfterShiftEnd_finalizesHalfDay_whenWorkedMinutesBelowThreshold() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(5, 35).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));

        // "Now" (5:35 AM) is past midnight relative to the shift's own start (20:30 the evening
        // before) — the open session's workDate is that earlier calendar day, same convention as
        // the overnight-lateness test above.
        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId())).minusDays(1);
        LocalDateTime checkInAt = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(5, 30));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(overnightShift.getId())
                .build();
        stubOpenNormalSession(open);

        AttendanceResponse resp = service.checkOut(employeeEmail, null);

        assertEquals("HALF_DAY", resp.getStatus(),
                "shift has ended (05:35, shift ends 05:30) and only ~5 minutes were worked — must finalize as HALF_DAY now");
    }

    /**
     * The sweep covering the case checkOut itself can't: an employee who checked out well
     * before their shift ended and simply never came back that day. Once the shift's own
     * natural end has since passed (checked here, not at the original checkout), the sweep must
     * finalize the day as HALF_DAY on its own, without any further attendance action from the
     * employee.
     */
    @Test
    void finalizeStatusPastShiftEnd_finalizesHalfDay_forAClosedDayThatNeverReopenedAfterShiftEnd() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(5, 35).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId())).minusDays(1);
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(20, 35)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(20, 40)))
                .workedMinutes(5)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(overnightShift.getId())
                .build();
        when(attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(closed));

        service.finalizeStatusPastShiftEnd();

        assertEquals("HALF_DAY", closed.getStatus());
        verify(attendanceRepository).saveAndFlush(closed);
    }

    /** The mirror case: shift hasn't ended yet, so the sweep must leave the record untouched. */
    @Test
    void finalizeStatusPastShiftEnd_leavesRecordUntouched_whenShiftHasNotEndedYet() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(20, 36).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = shift("US Night Shift", LocalTime.of(20, 30), LocalTime.of(5, 30));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(20, 30)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(20, 31)))
                .workedMinutes(1)
                .lateByMinutes(0)
                .status("PRESENT")
                .shiftId(overnightShift.getId())
                .build();
        when(attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(closed));

        service.finalizeStatusPastShiftEnd();

        assertEquals("PRESENT", closed.getStatus(), "shift ends at 05:30 the next day — 20:36 is nowhere near over yet");
        verify(attendanceRepository, never()).save(any(Attendance.class));
        verify(attendanceRepository, never()).saveAndFlush(any(Attendance.class));
    }

    /**
     * Same unified-grace fix as checkOut's own version above, for the sweep path: a genuinely
     * within-privilege arrival (5 minutes late against a 10-minute grace) must still read PRESENT
     * once the sweep finalizes the day past its shift end — never flipped to LATE from the raw
     * `lateByMinutes > 0` figure alone.
     */
    @Test
    void finalizeStatusPastShiftEnd_checkInWithinGrace_finalizesAsPresent_neverFlipsToLateFromRawLateByMinutes() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(18, 5).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift dayShift = shift("Day Shift", LocalTime.of(9, 0), LocalTime.of(18, 0));
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee")
                .shift(dayShift).location(location).user(User.builder().id(employeeId).active(true).build()).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 5)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(17, 55)))
                .workedMinutes(530)
                .lateByMinutes(5)
                .status("PRESENT")
                .shiftId(dayShift.getId())
                .build();
        when(attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(closed));

        service.finalizeStatusPastShiftEnd();

        assertEquals("PRESENT", closed.getStatus(),
                "5 minutes late is within the shift's 10-minute allowed-late privilege — the sweep "
                        + "must never flip it to LATE from raw lateByMinutes alone");
    }

    // ---------------------------------------------------------------- break minutes

    /**
     * A Web Clock-In/Out session can genuinely overlap a normal Check-In/Out session in real
     * time (the two are independent — see WebClockInService's own class Javadoc): here the
     * WEB_REMOTE cycle starts and ends entirely inside the still-open... no, entirely inside the
     * already-closed SYSTEM session's window. collectPunches sorts by checkInAt only, so the
     * "gap" between the SYSTEM session's checkOutAt and the (earlier-ending) WEB_REMOTE session's
     * checkInAt is negative. Must be floored at 0, not surfaced to the employee as e.g.
     * "-6 / 60 min" on the Today's Timings panel.
     */
    @Test
    void getToday_neverReportsANegativeBreakUsedMinutes_whenWebAndNormalSessionsOverlap() {
        LocalDate workDate = currentShiftDay();
        LocalDateTime systemCheckIn = LocalDateTime.of(workDate, LocalTime.of(22, 3));
        LocalDateTime systemCheckOut = LocalDateTime.of(workDate, LocalTime.of(22, 32));
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(systemCheckIn)
                .checkOutAt(systemCheckOut)
                .workedMinutes(29)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(closed));

        AttendancePunch systemPunch = AttendancePunch.builder()
                .id(UUID.randomUUID()).attendanceRecordId(closed.getId())
                .checkInAt(systemCheckIn).checkOutAt(systemCheckOut).build();
        when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(closed.getId()))
                .thenReturn(List.of(systemPunch));

        // Web Clock-In/Out cycle entirely inside the SYSTEM session's window — checkInAt 22:07,
        // checkedOutAt 22:08 — both well before the SYSTEM session's own 22:32 checkout.
        WebClockInRequest webCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).workDate(workDate)
                .requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(22, 7)))
                .checkedOutAt(LocalDateTime.of(workDate, LocalTime.of(22, 8)))
                .reason("test").build();
        when(webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate))
                .thenReturn(List.of(webCycle));

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

        assertNotNull(response.getBreakUsedMinutes());
        assertTrue(response.getBreakUsedMinutes() >= 0,
                "break-used minutes must never be negative, was " + response.getBreakUsedMinutes());
        assertEquals(0, response.getBreakUsedMinutes());
    }
}
