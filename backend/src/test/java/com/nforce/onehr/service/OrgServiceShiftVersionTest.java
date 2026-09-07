package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.CreateShiftRequest;
import com.nforce.onehr.dto.org.ShiftResponse;
import com.nforce.onehr.dto.org.UpdateShiftRequest;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.BusinessUnitRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.ShiftVersionRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Shift Versioning's CRUD contract: creating a Shift seeds an immediately-effective Version 1;
 * editing an existing Shift's timing always schedules a NEW, future-effective version rather than
 * mutating anything already in effect; at most one pending version exists per Shift at a time.
 * The version-resolution algorithm itself (which version a given workDate resolves to) is
 * exercised in {@link ShiftDayPolicyTest} and {@link ExpectedWorkHoursServiceTest} — this class
 * only covers the CRUD/validation surface around scheduling those versions.
 */
@ExtendWith(MockitoExtension.class)
class OrgServiceShiftVersionTest {

    @Mock private BusinessUnitRepository businessUnitRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private DesignationRepository designationRepo;
    @Mock private LocationRepository locationRepo;
    @Mock private ShiftRepository shiftRepo;
    @Mock private ShiftVersionRepository shiftVersionRepository;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private HolidayRepository holidayRepo;
    @Mock private AssetRepository assetRepo;
    @Mock private AttendanceRepository attendanceRepo;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(businessUnitRepo, departmentRepo, designationRepo, locationRepo, shiftRepo,
                shiftVersionRepository, shiftVersionResolver, shiftWeeklyOffRulesService, weeklyOffPolicyRepo, employeeRepo, historyRepo,
                holidayRepo, assetRepo, attendanceRepo);
        lenient().when(shiftRepo.save(any())).thenAnswer(inv -> {
            Shift s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        lenient().when(shiftVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepo.countByShiftId(any())).thenReturn(0L);
        // ShiftResponse.from needs a resolved "current version" for its display fields — not the
        // focus of these CRUD/validation tests, so a fixed stand-in suffices throughout.
        lenient().when(shiftVersionResolver.resolveCurrent(any()))
                .thenReturn(ShiftVersion.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
        // The real, configured Maximum Shift Day Duration — 18h default, matching V158's migration
        // default, reused (not re-hardcoded) by validateShiftDuration.
        lenient().when(shiftWeeklyOffRulesService.getMaximumShiftDayDurationHours()).thenReturn(18.0);
    }

    private CreateShiftRequest createReq(String name, LocalTime start, LocalTime end) {
        CreateShiftRequest req = new CreateShiftRequest();
        req.setName(name);
        req.setStartTime(start);
        req.setEndTime(end);
        return req;
    }

    private UpdateShiftRequest updateReq(String name, LocalTime start, LocalTime end, LocalDate effectiveFrom) {
        UpdateShiftRequest req = new UpdateShiftRequest();
        req.setName(name);
        req.setStartTime(start);
        req.setEndTime(end);
        req.setEffectiveFrom(effectiveFrom);
        return req;
    }

    private CreateShiftRequest createReq(String name, LocalTime start, LocalTime end, Integer graceMinutes) {
        CreateShiftRequest req = createReq(name, start, end);
        req.setLateGraceMinutes(graceMinutes);
        return req;
    }

    private UpdateShiftRequest updateReq(String name, LocalTime start, LocalTime end, LocalDate effectiveFrom, Integer graceMinutes) {
        UpdateShiftRequest req = updateReq(name, start, end, effectiveFrom);
        req.setLateGraceMinutes(graceMinutes);
        return req;
    }

    // ── 1. Version 1 creation ────────────────────────────────────────────────

    @Test
    void createShift_seedsAnImmediatelyEffectiveVersion1() {
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        service.createShift(createReq("Morning Shift", LocalTime.of(9, 0), LocalTime.of(18, 0)));

        verify(shiftVersionRepository).save(captor.capture());
        ShiftVersion v = captor.getValue();
        assertEquals(LocalTime.of(9, 0), v.getStartTime());
        assertEquals(LocalTime.of(18, 0), v.getEndTime());
        assertEquals(LocalDate.now(), v.getEffectiveFrom(), "a brand-new Shift's first version is effective immediately — no employees are assigned to it yet");
    }

    @Test
    void createShift_neverCreatesAPendingVersion() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        ShiftResponse response = service.createShift(createReq("Morning Shift", LocalTime.of(9, 0), LocalTime.of(18, 0)));
        assertNull(response.getPendingEffectiveFrom());
    }

    // ── 2/6. New version creation + pending-version replacement ─────────────

    @Test
    void updateShift_schedulesANewVersion_ratherThanMutatingTheCurrentOne() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), tomorrow));

        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);
        verify(shiftVersionRepository).save(captor.capture());
        ShiftVersion scheduled = captor.getValue();
        assertEquals(LocalTime.of(6, 0), scheduled.getStartTime());
        assertEquals(tomorrow, scheduled.getEffectiveFrom());
        // The Shift identity row itself is saved (name/code/description), but its timing is never
        // touched directly — Shift no longer has timing fields to mutate at all.
        verify(shiftRepo).save(existing);
    }

    @Test
    void updateShift_replacesAnyExistingPendingVersion_ratherThanStackingASecondOne() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        LocalDate today = LocalDate.now();

        service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), today.plusDays(10)));

        // Any previously-scheduled pending version (effectiveFrom > today) is cleared before the
        // new one is inserted — never leaves two pending versions to disambiguate between.
        verify(shiftVersionRepository).deleteByShiftIdAndEffectiveFromGreaterThan(shiftId, today);
    }

    @Test
    void updateShift_neverTouchesAnAlreadyEffectiveOrHistoricalVersion() {
        // The replace-pending delete is scoped to effectiveFrom > today ONLY — an already-effective
        // (or historical) version's row is never targeted by this call, regardless of how many
        // times the shift is edited going forward. Verified by asserting the exact bound passed.
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        LocalDate today = LocalDate.now();

        service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), today.plusDays(1)));

        ArgumentCaptor<LocalDate> boundCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(shiftVersionRepository).deleteByShiftIdAndEffectiveFromGreaterThan(eq(shiftId), boundCaptor.capture());
        assertEquals(today, boundCaptor.getValue(), "only versions strictly after today are ever candidates for replacement");
    }

    // ── 3/4/5. Effective From validation ─────────────────────────────────────

    @Test
    void updateShift_rejectsTodayAsEffectiveFrom() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now())));
        assertTrue(ex.getMessage().contains("future date"));
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void updateShift_rejectsAPastDateAsEffectiveFrom() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () ->
                service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().minusDays(1))));
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void updateShift_acceptsTomorrow() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() ->
                service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().plusDays(1))));
        verify(shiftVersionRepository).save(any());
    }

    @Test
    void updateShift_acceptsAnyFutureDate() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() ->
                service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().plusMonths(3))));
        verify(shiftVersionRepository).save(any());
    }

    // ── 18-hour maximum Shift duration ────────────────────────────────────────
    // Reuses ShiftWeeklyOffRulesService.getMaximumShiftDayDurationHours() — the exact same value
    // ShiftDayPolicy uses for the logical-workday boundary — rather than a second, independently-
    // hardcoded 18h limit. Exactly at the limit is valid; only strictly greater is rejected.

    @Test
    void createShift_exactlyMaximumDuration_isAccepted() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        // 06:00 -> 00:00 next day = 18h exactly.
        assertDoesNotThrow(() -> service.createShift(createReq("Long Shift", LocalTime.of(6, 0), LocalTime.MIDNIGHT)));
        verify(shiftVersionRepository).save(any());
    }

    @Test
    void createShift_overMaximumDuration_isRejected() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        // 06:00 -> 00:30 next day = 18.5h.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.createShift(createReq("Too Long Shift", LocalTime.of(6, 0), LocalTime.of(0, 30))));
        assertTrue(ex.getMessage().contains("18"));
        verify(shiftVersionRepository, never()).save(any());
        verify(shiftRepo, never()).save(any());
    }

    @Test
    void createShift_validOvernightShift_isAccepted() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        // 15:30 -> 00:30 next day = 9h — an ordinary overnight shift, well under the limit.
        assertDoesNotThrow(() -> service.createShift(createReq("Overnight Shift", LocalTime.of(15, 30), LocalTime.of(0, 30))));
        verify(shiftVersionRepository).save(any());
    }

    @Test
    void updateShift_overMaximumDuration_isRejected() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.updateShift(shiftId,
                updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(0, 30), LocalDate.now().plusDays(1))));
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void updateShift_exactlyMaximumDuration_isAccepted() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() -> service.updateShift(shiftId,
                updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.MIDNIGHT, LocalDate.now().plusDays(1))));
        verify(shiftVersionRepository).save(any());
    }

    // ── The organization's default shift must always remain resolvable ───────
    // Every employee having a real assigned Shift is a hard invariant — see UserManagementService/
    // EmployeeService's create-time failures and ShiftDayPolicy, which throws for a null-shift
    // employee everywhere. That invariant depends on Shift.DEFAULT_SHIFT_NAME always resolving to
    // a real, active shift, so OrgService must refuse to rename/deactivate/delete it — regardless
    // of employee count, unlike every other (non-default) shift.

    @Test
    void updateShift_rejectsRenamingTheDefaultShift() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.updateShift(shiftId,
                updateReq("Some Other Name", LocalTime.of(9, 0), LocalTime.of(18, 0), LocalDate.now().plusDays(1))));
        verify(shiftRepo, never()).save(any());
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void updateShift_allowsEditingTheDefaultShiftsTiming_solongAsTheNameIsUnchanged() {
        // Regular Shift is a completely ordinary shift with respect to its own timing — only its
        // NAME (the stable identity every default-shift lookup depends on) is protected.
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() -> service.updateShift(shiftId,
                updateReq(Shift.DEFAULT_SHIFT_NAME, LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().plusDays(1))));
        verify(shiftVersionRepository).save(any());
    }

    @Test
    void toggleShiftActive_rejectsDeactivatingTheDefaultShift() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.toggleShiftActive(shiftId));
        verify(shiftRepo, never()).save(any());
    }

    @Test
    void toggleShiftActive_allowsReactivatingTheDefaultShift() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name(Shift.DEFAULT_SHIFT_NAME).active(false).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() -> service.toggleShiftActive(shiftId));
        verify(shiftRepo).save(argThat(Shift::isActive));
    }

    @Test
    void deleteShift_rejectsDeletingTheDefaultShift_evenWithZeroEmployeesAssigned() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        // Deliberately 0 — proves this is blocked regardless of employee count, unlike the
        // ordinary in-use guard below.
        lenient().when(employeeRepo.countByShiftId(shiftId)).thenReturn(0L);

        assertThrows(IllegalArgumentException.class, () -> service.deleteShift(shiftId));
        verify(shiftRepo, never()).delete(any());
    }

    @Test
    void deleteShift_ordinaryShift_stillBlockedWhenEmployeesAreAssigned() {
        // Unrelated to the default-shift guard above — confirms the pre-existing employee-count
        // protection still applies to every other (non-default) shift unchanged.
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Night Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        when(employeeRepo.countByShiftId(shiftId)).thenReturn(3L);

        assertThrows(IllegalStateException.class, () -> service.deleteShift(shiftId));
        verify(shiftRepo, never()).delete(any());
    }

    @Test
    void deleteShift_blockedWhenAttendanceReferencesIt_evenWithZeroCurrentEmployeesAssigned() {
        // A Shift every currently-assigned employee has since been moved off of (count == 0, so
        // the ordinary in-use guard above would pass) can still be the historical context for
        // real Attendance rows via their snapshotted shiftId (see V163) — this must independently
        // block deletion, since erasing it would silently destroy that history.
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Night Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        when(employeeRepo.countByShiftId(shiftId)).thenReturn(0L);
        when(attendanceRepo.existsByShiftId(shiftId)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.deleteShift(shiftId));
        verify(shiftRepo, never()).delete(any());
    }

    @Test
    void deleteShift_allowedWhenNoAttendanceEverReferencedIt() {
        // The mirror case: zero current employees AND zero historical Attendance references —
        // deletion proceeds exactly as before this guard was added.
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Night Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        when(employeeRepo.countByShiftId(shiftId)).thenReturn(0L);
        when(attendanceRepo.existsByShiftId(shiftId)).thenReturn(false);

        assertDoesNotThrow(() -> service.deleteShift(shiftId));
        verify(shiftRepo).delete(existing);
    }

    // ── 17. A Shift Version change never touches Attendance ──────────────────

    @Test
    void orgService_hasExactlyOneAttendanceRelatedDependency_usedOnlyAsDeleteShiftsReadOnlyGuard() {
        // Structural proof, not a runtime assertion — updated alongside V163/deleteShift's new
        // historical-usage guard (see the two tests above): OrgService now has exactly ONE
        // Attendance-related field, a plain repository used purely as a read-only existence check
        // before allowing a delete — never to write, and never reached from createShift/
        // updateShift/toggleShiftActive at all (see the companion test below). This preserves the
        // original invariant's spirit ("Shift Version create/update never touches Attendance") —
        // only deleteShift's new safety check is the deliberate, narrow exception.
        long attendanceRelatedFieldCount = java.util.Arrays.stream(OrgService.class.getDeclaredFields())
                .filter(field -> field.getType().getSimpleName().contains("Attendance"))
                .count();
        assertEquals(1, attendanceRelatedFieldCount,
                "OrgService should depend on exactly one Attendance-related type (AttendanceRepository, "
                        + "for deleteShift's historical-usage guard) — no more");
    }

    @Test
    void createShift_and_updateShift_neverInteractWithAttendanceRepository() {
        // The original invariant this class's Shift Version tests protect: creating or scheduling
        // a Shift Version must never touch Attendance, historical or otherwise. Exercises both
        // write paths and verifies attendanceRepo is never called from either.
        CreateShiftRequest createReq = new CreateShiftRequest();
        createReq.setName("Evening Shift");
        createReq.setStartTime(LocalTime.of(14, 0));
        createReq.setEndTime(LocalTime.of(22, 0));
        lenient().when(shiftWeeklyOffRulesService.getMaximumShiftDayDurationHours()).thenReturn(18.0);
        service.createShift(createReq);

        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Evening Shift").active(true).build();
        lenient().when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        UpdateShiftRequest updateReq = new UpdateShiftRequest();
        updateReq.setName("Evening Shift");
        updateReq.setStartTime(LocalTime.of(14, 0));
        updateReq.setEndTime(LocalTime.of(22, 0));
        updateReq.setEffectiveFrom(LocalDate.now().plusDays(1));
        lenient().when(shiftVersionResolver.resolveCurrent(any())).thenReturn(
                ShiftVersion.builder().shift(existing).startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(22, 0)).build());
        service.updateShift(shiftId, updateReq);

        verifyNoInteractions(attendanceRepo);
    }

    // ── Phase 3: per-Shift-Version grace period ──────────────────────────────

    @Test
    void createShift_withNoExplicitGrace_backfillsThePreMigrationGlobalDefault() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);

        service.createShift(createReq("Morning Shift", LocalTime.of(9, 0), LocalTime.of(18, 0)));

        verify(shiftVersionRepository).save(captor.capture());
        assertEquals(10, captor.getValue().getLateGraceMinutes(),
                "matches the pre-migration app.attendance.late-grace-minutes default so existing behavior is unchanged");
    }

    @Test
    void createShift_withExplicitGrace_usesIt() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);

        service.createShift(createReq("Generous Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), 30));

        verify(shiftVersionRepository).save(captor.capture());
        assertEquals(30, captor.getValue().getLateGraceMinutes());
    }

    @Test
    void createShift_rejectsNegativeGrace() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () ->
                service.createShift(createReq("Bad Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), -5)));
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void updateShift_withExplicitGrace_scheduledOnTheNewVersion() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);

        service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0),
                LocalDate.now().plusDays(1), 20));

        verify(shiftVersionRepository).save(captor.capture());
        assertEquals(20, captor.getValue().getLateGraceMinutes());
    }

    @Test
    void updateShift_withNoExplicitGrace_backfillsThePreMigrationGlobalDefault() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);

        service.updateShift(shiftId, updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().plusDays(1)));

        verify(shiftVersionRepository).save(captor.capture());
        assertEquals(10, captor.getValue().getLateGraceMinutes());
    }

    @Test
    void updateShift_rejectsNegativeGrace() {
        UUID shiftId = UUID.randomUUID();
        Shift existing = Shift.builder().id(shiftId).name("Regular Shift").active(true).build();
        when(shiftRepo.findById(shiftId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.updateShift(shiftId,
                updateReq("Regular Shift", LocalTime.of(6, 0), LocalTime.of(15, 0), LocalDate.now().plusDays(1), -1)));
        verify(shiftVersionRepository, never()).save(any());
    }

    @Test
    void twoShifts_canHaveIndependentGracePeriods() {
        when(shiftRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        ArgumentCaptor<ShiftVersion> captor = ArgumentCaptor.forClass(ShiftVersion.class);

        service.createShift(createReq("Strict Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), 0));
        service.createShift(createReq("Generous Shift", LocalTime.of(9, 0), LocalTime.of(18, 0), 30));

        verify(shiftVersionRepository, times(2)).save(captor.capture());
        assertEquals(0, captor.getAllValues().get(0).getLateGraceMinutes());
        assertEquals(30, captor.getAllValues().get(1).getLateGraceMinutes());
    }
}
