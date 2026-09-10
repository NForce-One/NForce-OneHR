package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.AttendanceRulesResponse;
import com.nforce.onehr.dto.org.UpdateAttendanceRulesRequest;
import com.nforce.onehr.dto.org.UpdateDefaultTimezoneRequest;
import com.nforce.onehr.entity.AttendanceRules;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.AttendanceRulesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceRulesServiceTest {

    @Mock private AttendanceRulesRepository repository;

    private AttendanceRulesService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceRulesService(repository);
        lenient().when(repository.save(any(AttendanceRules.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AttendanceRules singletonRow(double hours) {
        return AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(hours)).build();
    }

    @Test
    void getRules_returnsTheSeededDefault() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(3.5)));

        AttendanceRulesResponse response = service.getRules();

        assertEquals(3.5, response.getHalfDayMaxHours());
    }

    @Test
    void getHalfDayMaxHours_isThePlainDoubleAttendanceCalculationConsumes() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(3.5)));
        assertEquals(3.5, service.getHalfDayMaxHours());
    }

    @Test
    void getRules_missingSingletonRow_failsLoudlyRatherThanGuessing() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.getRules());
    }

    @Test
    void update_acceptsAValueWithinBounds() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(3.5)));
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        req.setHalfDayMaxHours(BigDecimal.valueOf(4.0));

        AttendanceRulesResponse response = service.updateHalfDayMaxHours(req);

        assertEquals(4.0, response.getHalfDayMaxHours());
    }

    @Test
    void update_rejectsZero_notPositive() {
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        req.setHalfDayMaxHours(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service.updateHalfDayMaxHours(req));
    }

    @Test
    void update_rejectsNegative() {
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        req.setHalfDayMaxHours(BigDecimal.valueOf(-1));
        assertThrows(IllegalArgumentException.class, () -> service.updateHalfDayMaxHours(req));
    }

    @Test
    void update_rejectsTwentyFourHoursOrAbove() {
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        req.setHalfDayMaxHours(BigDecimal.valueOf(24));
        assertThrows(IllegalArgumentException.class, () -> service.updateHalfDayMaxHours(req));
    }

    @Test
    void update_acceptsJustBelowTheTwentyFourHourCeiling() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(3.5)));
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        req.setHalfDayMaxHours(BigDecimal.valueOf(23.9));

        AttendanceRulesResponse response = service.updateHalfDayMaxHours(req);

        assertEquals(23.9, response.getHalfDayMaxHours());
    }

    @Test
    void update_rejectsNull() {
        UpdateAttendanceRulesRequest req = new UpdateAttendanceRulesRequest();
        assertThrows(IllegalArgumentException.class, () -> service.updateHalfDayMaxHours(req));
    }

    // ── Phase 2: org-wide default timezone ───────────────────────────────────

    @Test
    void getDefaultZoneId_returnsTheSeededDefault() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(
                AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));

        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), service.getDefaultZoneId());
    }

    @Test
    void updateDefaultTimezone_acceptsAValidIanaZone() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(
                AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));
        UpdateDefaultTimezoneRequest req = new UpdateDefaultTimezoneRequest();
        req.setDefaultTimezone("America/New_York");

        AttendanceRulesResponse response = service.updateDefaultTimezone(req);

        assertEquals("America/New_York", response.getDefaultTimezone());
    }

    @Test
    void updateDefaultTimezone_rejectsAnInvalidZoneId() {
        UpdateDefaultTimezoneRequest req = new UpdateDefaultTimezoneRequest();
        req.setDefaultTimezone("Not/A_Real_Zone");

        assertThrows(IllegalArgumentException.class, () -> service.updateDefaultTimezone(req));
    }

    @Test
    void updateDefaultTimezone_neverReinterpretsExistingAttendance_onlyChangesTheStoredSetting() {
        // The fallback's only job is future resolution — it has no way to touch any existing
        // Attendance row (no such coupling exists in this service at all); this test documents
        // that absence as a regression guard rather than asserting a no-op against nothing.
        AttendanceRules rules = AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build();
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(rules));
        UpdateDefaultTimezoneRequest req = new UpdateDefaultTimezoneRequest();
        req.setDefaultTimezone("Europe/London");

        service.updateDefaultTimezone(req);

        assertEquals("Europe/London", rules.getDefaultTimezone());
    }

    // ── Finalized Location/Timezone model: resolveEmployeeZoneId ─────────────
    // The single shared implementation of "what timezone does this employee's attendance run
    // in" — Location is the ONLY source; Employee itself carries no timezone field of its own.

    @Test
    void resolveEmployeeZoneId_usesTheAssignedLocationsTimezone() {
        Location office = Location.builder().name("Chicago").timezone("America/Chicago").build();
        Employee employee = Employee.builder().location(office).build();

        assertEquals(java.time.ZoneId.of("America/Chicago"), service.resolveEmployeeZoneId(employee));
    }

    @Test
    void resolveEmployeeZoneId_noLocationAssigned_fallsBackToTheOrgWideDefault() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(
                AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));
        Employee employee = Employee.builder().location(null).build();

        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), service.resolveEmployeeZoneId(employee));
    }

    @Test
    void resolveEmployeeZoneId_nullEmployee_fallsBackToTheOrgWideDefault() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(
                AttendanceRules.builder().halfDayMaxHours(BigDecimal.valueOf(3.5)).defaultTimezone("Asia/Kolkata").build()));

        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), service.resolveEmployeeZoneId(null));
    }

    // ── Future-effective timezone (ONEHR-336 follow-up): pending-timezone resolved inline by
    // date, on every call — no scheduler/promotion job anywhere in this path. ──────────────────

    @Test
    void resolveEmployeeZoneId_pendingTimezoneNotYetEffective_stillUsesTheLiveTimezone() {
        Location office = Location.builder().name("Office").timezone("Asia/Kolkata")
                .pendingTimezone("America/New_York").pendingTimezoneEffectiveFrom(java.time.LocalDate.now().plusDays(1))
                .build();
        Employee employee = Employee.builder().location(office).build();

        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), service.resolveEmployeeZoneId(employee),
                "a pending change effective tomorrow must never be exposed by a fresh resolution today");
    }

    @Test
    void resolveEmployeeZoneId_pendingTimezoneEffectiveAsOfToday_usesThePendingOne() {
        Location office = Location.builder().name("Office").timezone("Asia/Kolkata")
                .pendingTimezone("America/New_York").pendingTimezoneEffectiveFrom(java.time.LocalDate.now())
                .build();
        Employee employee = Employee.builder().location(office).build();

        assertEquals(java.time.ZoneId.of("America/New_York"), service.resolveEmployeeZoneId(employee));
    }

    @Test
    void resolveEmployeeZoneId_pendingTimezoneEffectiveInThePast_usesThePendingOne() {
        // Resolved inline by date on every call — correctness never depends on any job having run
        // to "promote" a since-passed pending value into the live column.
        Location office = Location.builder().name("Office").timezone("Asia/Kolkata")
                .pendingTimezone("America/New_York").pendingTimezoneEffectiveFrom(java.time.LocalDate.now().minusDays(5))
                .build();
        Employee employee = Employee.builder().location(office).build();

        assertEquals(java.time.ZoneId.of("America/New_York"), service.resolveEmployeeZoneId(employee));
    }

    @Test
    void resolveEmployeeZoneId_noPendingTimezone_usesTheLiveTimezone() {
        Location office = Location.builder().name("Office").timezone("Asia/Kolkata").build();
        Employee employee = Employee.builder().location(office).build();

        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), service.resolveEmployeeZoneId(employee));
    }
}
