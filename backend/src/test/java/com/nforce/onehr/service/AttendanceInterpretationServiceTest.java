package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceContext;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.InterpretationOutcome;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the single, narrow, shared owner of Shift-relative punch interpretation.
 * Uses a real {@link ShiftDayPolicy} (backed by a real {@link ShiftWeeklyOffRulesService} and an
 * in-memory {@link ShiftVersionResolver} stand-in) so the actual overnight/version-resolution math
 * is exercised, not just mocked away.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceInterpretationServiceTest {

    @Mock private com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository shiftWeeklyOffRulesRepository;
    @Mock private ShiftRepository shiftRepository;

    private final List<ShiftVersion> shiftVersions = new ArrayList<>();
    private AttendanceInterpretationService service;

    // Matches the pre-migration global app.attendance.late-grace-minutes default (10) — every
    // existing test's expectations were written against that value, so this fixture default
    // keeps them all unchanged unless a test explicitly asks for a different grace via the
    // 6-arg overload below.
    private static final int DEFAULT_TEST_GRACE_MINUTES = 10;

    private Shift shift(String name, LocalTime start, LocalTime end, LocalDate effectiveFrom, boolean active) {
        return shift(name, start, end, effectiveFrom, active, DEFAULT_TEST_GRACE_MINUTES);
    }

    private Shift shift(String name, LocalTime start, LocalTime end, LocalDate effectiveFrom, boolean active, int graceMinutes) {
        Shift s = Shift.builder().id(UUID.randomUUID()).name(name).active(active).build();
        shiftVersions.add(ShiftVersion.builder().shift(s).startTime(start).endTime(end)
                .lateGraceMinutes(graceMinutes).effectiveFrom(effectiveFrom).build());
        return s;
    }

    @BeforeEach
    void setUp() {
        lenient().when(shiftWeeklyOffRulesRepository.findBySingletonTrue()).thenReturn(Optional.of(
                com.nforce.onehr.entity.ShiftWeeklyOffRules.builder()
                        .maximumShiftDayDurationHours(java.math.BigDecimal.valueOf(18)).build()));
        ShiftVersionResolver shiftVersionResolver = new ShiftVersionResolver(null) {
            @Override
            public ShiftVersion resolve(Shift s, LocalDate workDate) {
                return shiftVersions.stream()
                        .filter(v -> v.getShift().getId().equals(s.getId()))
                        .filter(v -> !v.getEffectiveFrom().isAfter(workDate))
                        .max(Comparator.comparing(ShiftVersion::getEffectiveFrom))
                        .orElseThrow(() -> new IllegalStateException("no version effective on or before " + workDate));
            }
        };
        ShiftDayPolicy shiftDayPolicy = new ShiftDayPolicy(new ShiftWeeklyOffRulesService(shiftWeeklyOffRulesRepository), shiftVersionResolver);
        lenient().when(shiftRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return shiftVersions.stream().map(ShiftVersion::getShift).filter(s -> s.getId().equals(id)).findFirst();
        });
        service = new AttendanceInterpretationService(shiftDayPolicy, shiftRepository);
    }

    // ── Fresh action: resolves against the employee's CURRENT Shift ──────────

    @Test
    void interpretFreshAction_snapshotsTheEmployeesCurrentShift() {
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        Employee employee = Employee.builder().userId(UUID.randomUUID()).shift(shiftA).build();
        LocalDateTime now = LocalDateTime.of(2026, 3, 10, 9, 5);

        AttendanceInterpretation interpretation = service.interpretFreshAction(
                employee, new AttendanceContext(employee.getUserId(), now, ZoneId.of("Asia/Kolkata")));

        assertEquals(InterpretationOutcome.RESOLVED, interpretation.getOutcome());
        assertEquals(shiftA.getId(), interpretation.getShiftId(), "a fresh action must snapshot the CURRENT shift");
        assertEquals(LocalDate.of(2026, 3, 10), interpretation.getWorkDate());
        assertFalse(interpretation.getIsLate());
    }

    @Test
    void interpretForKnownWorkDate_usedByFreshRegularization_snapshotsTheEmployeesCurrentShift() {
        // A Regularization-created row for a date with no prior punch: the work-date is already
        // known (the employee-picked correction date) — nothing to derive.
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        Employee employee = Employee.builder().userId(UUID.randomUUID()).shift(shiftA).build();
        LocalDate correctionDate = LocalDate.of(2026, 1, 5);
        LocalDateTime checkInAt = LocalDateTime.of(correctionDate, LocalTime.of(9, 20));

        AttendanceInterpretation interpretation = service.interpretForKnownWorkDate(employee, correctionDate, checkInAt);

        assertEquals(InterpretationOutcome.RESOLVED, interpretation.getOutcome());
        assertEquals(shiftA.getId(), interpretation.getShiftId());
        assertEquals(correctionDate, interpretation.getWorkDate(), "work-date must be exactly the given date, never re-derived");
        assertTrue(interpretation.getIsLate());
    }

    @Test
    void interpretFreshAction_invariantViolation_isLoudAndNeverDegradesToCalendarDateAttribution() {
        // A null-shift employee should be database-impossible (mandatory-Shift invariant) — if it
        // ever happens, this must fail loudly, never silently attribute by plain calendar date
        // (which would be provably wrong for an overnight shift).
        Employee shiftlessEmployee = Employee.builder().userId(UUID.randomUUID()).shift(null).build();
        AttendanceContext context = new AttendanceContext(
                shiftlessEmployee.getUserId(), LocalDateTime.of(2026, 1, 1, 1, 0), ZoneId.of("Asia/Kolkata"));

        assertThrows(IllegalStateException.class, () -> service.interpretFreshAction(shiftlessEmployee, context));
    }

    // ── Existing session: resolves ONLY against the record's own snapshotted shiftId ──────────

    @Test
    void interpretExistingSession_usesTheRecordsOwnSnapshottedShift_neverAnyLiveEmployeeShift() {
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2026, 3, 10)).checkInAt(LocalDateTime.of(2026, 3, 10, 9, 5))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretation interpretation = service.interpretExistingSession(
                record, LocalDateTime.of(2026, 3, 10, 12, 0));

        assertEquals(InterpretationOutcome.RESOLVED, interpretation.getOutcome());
        assertEquals(shiftA.getId(), interpretation.getShiftId());
        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 0), interpretation.getCheckoutCutoff());
    }

    /**
     * The core regression this whole design exists to fix: an employee checks in under Shift A,
     * is reassigned to Shift B, and the open session's checkout/staleness interpretation must
     * still reflect Shift A — never Shift B, even though Shift B is what {@code employee.getShift()}
     * would now return.
     */
    @Test
    void interpretExistingSession_afterReassignment_stillResolvesTheOriginalShift_notTheNewOne() {
        Shift shiftA = shift("Shift A (9-18)", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        Shift shiftB = shift("Shift B (14-22)", LocalTime.of(14, 0), LocalTime.of(22, 0), LocalDate.MIN, true);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2026, 3, 10)).checkInAt(LocalDateTime.of(2026, 3, 10, 9, 5))
                .shiftId(shiftA.getId()) // snapshotted at check-in time, under Shift A
                .build();
        // Reassignment to Shift B happened since — irrelevant here, because interpretExistingSession
        // takes no live Employee reference at all; it can only ever see the record's own snapshot.

        AttendanceInterpretation interpretation = service.interpretExistingSession(
                record, LocalDateTime.of(2026, 3, 10, 19, 0));

        assertEquals(shiftA.getId(), interpretation.getShiftId(), "must resolve Shift A, never Shift B");
        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 0), interpretation.getCheckoutCutoff(),
                "cutoff must be Shift A's own end (18:00), not Shift B's (22:00)");
    }

    @Test
    void interpretExistingSession_overnightShift_remainsTiedToTheOriginalOvernightShift_afterReassignment() {
        Shift overnightA = shift("Overnight A", LocalTime.of(20, 30), LocalTime.of(5, 30), LocalDate.MIN, true);
        Shift dayB = shift("Day B", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(20, 35)))
                .shiftId(overnightA.getId())
                .build();

        // Checked at 1 AM the next calendar day — still the same logical workday for the
        // overnight shift. Reassigned to a day shift since, but that must not matter here.
        AttendanceInterpretation interpretation = service.interpretExistingSession(
                record, LocalDateTime.of(workDate.plusDays(1), LocalTime.of(1, 0)));

        assertEquals(overnightA.getId(), interpretation.getShiftId());
        assertEquals(LocalDateTime.of(workDate.plusDays(1), LocalTime.of(5, 30)), interpretation.getCheckoutCutoff(),
                "cutoff must still roll over per the ORIGINAL overnight shift, not the new day shift");
        assertEquals(workDate, interpretation.getWorkDate(), "1 AM is still within Shift A's own logical workday");
    }

    @Test
    void interpretExistingSession_deactivatedShift_stillResolvesHistoricalAttendance() {
        // Deactivating a Shift only gates NEW assignment (see OrgService) — it must never affect
        // resolving a historical record's own already-snapshotted Shift.
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        shiftA.setActive(false); // deactivated after this record was created
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2026, 3, 10)).checkInAt(LocalDateTime.of(2026, 3, 10, 9, 5))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretation interpretation = service.interpretExistingSession(
                record, LocalDateTime.of(2026, 3, 10, 19, 0));

        assertEquals(InterpretationOutcome.RESOLVED, interpretation.getOutcome());
        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 0), interpretation.getCheckoutCutoff());
    }

    @Test
    void interpretExistingSession_shiftTimingChangedSince_historicalInterpretationStaysDeterministic() {
        // Shift A's timing is changed for the FUTURE (a new ShiftVersion, effective later) —
        // resolving an OLD workDate must still use the version that was effective on that date,
        // never the new one. Mirrors OrgService.updateShift's own "never touches an
        // already-effective version" guarantee.
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.of(2020, 1, 1), true);
        // A new version, effective far in the future — must not affect a historical resolution.
        shiftVersions.add(ShiftVersion.builder().shift(shiftA).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(19, 0))
                .lateGraceMinutes(DEFAULT_TEST_GRACE_MINUTES).effectiveFrom(LocalDate.of(2030, 1, 1)).build());
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2026, 3, 10)).checkInAt(LocalDateTime.of(2026, 3, 10, 9, 5))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretation interpretation = service.interpretExistingSession(
                record, LocalDateTime.of(2026, 3, 10, 19, 0));

        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 0), interpretation.getCheckoutCutoff(),
                "must still use the OLD (9-18) version effective on 2026-03-10, not the future 10-19 one");
    }

    // ── Legacy rows: never guessed, never silently substituted ──────────────

    @Test
    void interpretExistingSession_legacyNullShiftId_returnsUnresolved_neverSubstitutesAnyShift() {
        Attendance legacyRecord = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2025, 1, 1)).checkInAt(LocalDateTime.of(2025, 1, 1, 9, 5))
                .shiftId(null) // predates the shiftId column
                .build();

        AttendanceInterpretation interpretation = service.interpretExistingSession(
                legacyRecord, LocalDateTime.of(2025, 1, 1, 19, 0));

        assertTrue(interpretation.isLegacyUnresolved());
        assertEquals(InterpretationOutcome.LEGACY_UNRESOLVED, interpretation.getOutcome());
        assertNull(interpretation.getShiftId());
        assertNull(interpretation.getCheckoutCutoff());
        assertNull(interpretation.getWorkDate());
    }

    @Test
    void interpretExistingRecordLateness_legacyNullShiftId_returnsUnresolved() {
        Attendance legacyRecord = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2025, 1, 1)).checkInAt(LocalDateTime.of(2025, 1, 1, 9, 5))
                .shiftId(null)
                .build();

        AttendanceInterpretation interpretation = service.interpretExistingRecordLateness(
                legacyRecord, LocalDateTime.of(2025, 1, 1, 9, 5));

        assertTrue(interpretation.isLegacyUnresolved());
        assertNull(interpretation.getIsLate());
        assertNull(interpretation.getLateByMinutes());
    }

    // ── Phase 3: per-Shift-Version grace period ──────────────────────────────

    // ── resolveScheduledWindow: powers the Attendance Log's shift-boundary markers ───────────

    @Test
    void resolveScheduledWindow_normalShift_returnsExactStartAndEnd() {
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        // Late check-in (09:30) and early checkout (17:45 the actual, via checkOutAt) must not
        // affect the SCHEDULED window at all — it is resolved purely from the Shift Version and
        // workDate, never from the record's own actual punch times.
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 30)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(17, 45)))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(record);

        assertEquals(LocalDateTime.of(workDate, LocalTime.of(9, 0)), window.start());
        assertEquals(LocalDateTime.of(workDate, LocalTime.of(18, 0)), window.end());
    }

    @Test
    void resolveScheduledWindow_actualExtendsBeyondScheduledEnd_windowStillReflectsTheShift_notTheActualPunch() {
        // Example from the Attendance Log marker design: shift 09:00-18:00, actual 09:30-18:15 —
        // the end marker must stay at 18:00 even though the employee actually checked out later.
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 30)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(18, 15)))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(record);

        assertEquals(LocalDateTime.of(workDate, LocalTime.of(18, 0)), window.end(),
                "scheduled end must stay 18:00 regardless of the actual (later) checkout");
    }

    @Test
    void resolveScheduledWindow_overnightShift_endRollsToTheNextCalendarDay() {
        Shift overnight = shift("Overnight", LocalTime.of(22, 0), LocalTime.of(6, 0), LocalDate.MIN, true);
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(22, 10)))
                .checkOutAt(LocalDateTime.of(workDate.plusDays(1), LocalTime.of(6, 5)))
                .shiftId(overnight.getId()).build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(record);

        assertEquals(LocalDateTime.of(workDate, LocalTime.of(22, 0)), window.start());
        assertEquals(LocalDateTime.of(workDate.plusDays(1), LocalTime.of(6, 0)), window.end(),
                "overnight scheduled end must roll onto workDate+1, matching shiftEndAt's own overnight rule");
    }

    @Test
    void resolveScheduledWindow_employeeReassignedSince_historicalRecordStillUsesItsOwnSnapshottedShift() {
        // The same reassignment regression interpretExistingSession already guards against, but
        // for the marker-window resolution: an employee checked in under Shift A (9-18), was
        // later reassigned to Shift B (14-22) — a historical record's markers must keep showing
        // Shift A's window, never Shift B's, no matter what the employee is assigned to today.
        Shift shiftA = shift("Shift A (9-18)", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true);
        shift("Shift B (14-22)", LocalTime.of(14, 0), LocalTime.of(22, 0), LocalDate.MIN, true); // reassigned-to shift
        LocalDate workDate = LocalDate.of(2025, 6, 1);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 5)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(18, 5)))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(record);

        assertEquals(LocalDateTime.of(workDate, LocalTime.of(9, 0)), window.start(), "must stay Shift A's start, never Shift B's");
        assertEquals(LocalDateTime.of(workDate, LocalTime.of(18, 0)), window.end(), "must stay Shift A's end, never Shift B's");
    }

    @Test
    void resolveScheduledWindow_shiftTimingChangedForTheFuture_historicalWindowStaysOnTheOldVersion() {
        // A new ShiftVersion effective far in the future must never leak into a historical
        // record's marker window — mirrors interpretExistingSession's own equivalent test above.
        Shift shiftA = shift("Shift A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.of(2020, 1, 1), true);
        shiftVersions.add(ShiftVersion.builder().shift(shiftA).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(19, 0))
                .lateGraceMinutes(DEFAULT_TEST_GRACE_MINUTES).effectiveFrom(LocalDate.of(2030, 1, 1)).build());
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 5)))
                .shiftId(shiftA.getId()).build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(record);

        assertEquals(LocalDateTime.of(workDate, LocalTime.of(9, 0)), window.start(),
                "must still use the OLD (9-18) version effective on 2026-03-10, not the future 10-19 one");
        assertEquals(LocalDateTime.of(workDate, LocalTime.of(18, 0)), window.end());
    }

    @Test
    void resolveScheduledWindow_legacyNullShiftId_returnsEmpty_neverSubstitutesAnyShift() {
        Attendance legacyRecord = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(LocalDate.of(2025, 1, 1)).checkInAt(LocalDateTime.of(2025, 1, 1, 9, 5))
                .shiftId(null)
                .build();

        AttendanceInterpretationService.ScheduledShiftWindow window = service.resolveScheduledWindow(legacyRecord);

        assertEquals(AttendanceInterpretationService.ScheduledShiftWindow.EMPTY, window);
        assertNull(window.start());
        assertNull(window.end());
    }

    @Test
    void interpretForKnownWorkDate_usesTheShiftsOwnGracePeriod_notAGlobalOne() {
        // 30-minute grace: a check-in 20 minutes late must be forgiven (not LATE), unlike the
        // 10-minute-grace fixtures elsewhere in this file where the same 20-minute delay IS late.
        Shift generousShift = shift("Generous Shift", LocalTime.of(9, 0), LocalTime.of(18, 0),
                LocalDate.MIN, true, 30);
        Employee employee = Employee.builder().userId(UUID.randomUUID()).shift(generousShift).build();
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(9, 20));

        AttendanceInterpretation interpretation = service.interpretForKnownWorkDate(employee, workDate, checkInAt);

        assertFalse(interpretation.getIsLate(), "20 minutes late must be forgiven under a 30-minute grace");
        assertEquals(20, interpretation.getLateByMinutes(), "raw lateByMinutes is never grace-forgiven, regardless of the grace value");
    }

    @Test
    void interpretForKnownWorkDate_twoShiftsWithDifferentGrace_eachUsesItsOwn() {
        Shift strictShift = shift("Strict Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true, 0);
        Shift generousShift = shift("Generous Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true, 30);
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(9, 10));

        AttendanceInterpretation strictInterpretation = service.interpretForKnownWorkDate(
                Employee.builder().userId(UUID.randomUUID()).shift(strictShift).build(), workDate, checkInAt);
        AttendanceInterpretation generousInterpretation = service.interpretForKnownWorkDate(
                Employee.builder().userId(UUID.randomUUID()).shift(generousShift).build(), workDate, checkInAt);

        assertTrue(strictInterpretation.getIsLate(), "0-minute grace: even 1 minute late is LATE");
        assertFalse(generousInterpretation.getIsLate(), "30-minute grace forgives the same 10-minute delay");
    }

    @Test
    void interpretExistingRecordLateness_usesTheRecordsOwnSnapshottedShiftsGrace_afterReassignment() {
        // The direct grace-period analogue of the reassignment regression above: an employee
        // checked in under a 0-grace shift, was reassigned to a 30-grace shift, and a
        // Regularization correcting that historical record's check-in time must still apply the
        // ORIGINAL shift's grace — never the new one.
        Shift strictShiftA = shift("Strict A", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true, 0);
        shift("Generous B", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.MIN, true, 30); // reassigned-to shift, irrelevant here
        LocalDate workDate = LocalDate.of(2026, 3, 10);
        Attendance record = Attendance.builder().id(UUID.randomUUID()).employeeUserId(UUID.randomUUID())
                .workDate(workDate).checkInAt(LocalDateTime.of(workDate, LocalTime.of(9, 5)))
                .shiftId(strictShiftA.getId())
                .build();

        AttendanceInterpretation interpretation = service.interpretExistingRecordLateness(
                record, LocalDateTime.of(workDate, LocalTime.of(9, 5)));

        assertTrue(interpretation.getIsLate(), "must use Strict A's 0-minute grace, never Generous B's 30-minute one");
    }
}
