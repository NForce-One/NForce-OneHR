package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.ShiftWeeklyOffRulesResponse;
import com.nforce.onehr.dto.org.UpdateShiftWeeklyOffRulesRequest;
import com.nforce.onehr.entity.ShiftWeeklyOffRules;
import com.nforce.onehr.repository.ShiftWeeklyOffRulesRepository;
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
class ShiftWeeklyOffRulesServiceTest {

    @Mock private ShiftWeeklyOffRulesRepository repository;

    private ShiftWeeklyOffRulesService service;

    @BeforeEach
    void setUp() {
        service = new ShiftWeeklyOffRulesService(repository);
        lenient().when(repository.save(any(ShiftWeeklyOffRules.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ShiftWeeklyOffRules singletonRow(double hours) {
        return ShiftWeeklyOffRules.builder().maximumShiftDayDurationHours(BigDecimal.valueOf(hours)).build();
    }

    @Test
    void getRules_returnsTheSeededDefault() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(18)));

        ShiftWeeklyOffRulesResponse response = service.getRules();

        assertEquals(18.0, response.getMaximumShiftDayDurationHours());
    }

    @Test
    void getMaximumShiftDayDurationHours_isThePlainDoubleShiftDayPolicyConsumes() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(18)));
        assertEquals(18.0, service.getMaximumShiftDayDurationHours());
    }

    @Test
    void getRules_missingSingletonRow_failsLoudlyRatherThanGuessing() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.getRules());
    }

    @Test
    void update_acceptsAValueWithinBounds() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(18)));
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        req.setMaximumShiftDayDurationHours(BigDecimal.valueOf(20));

        ShiftWeeklyOffRulesResponse response = service.updateMaximumShiftDayDurationHours(req);

        assertEquals(20.0, response.getMaximumShiftDayDurationHours());
    }

    @Test
    void update_rejectsZero_notPositive() {
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        req.setMaximumShiftDayDurationHours(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service.updateMaximumShiftDayDurationHours(req));
    }

    @Test
    void update_rejectsNegative() {
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        req.setMaximumShiftDayDurationHours(BigDecimal.valueOf(-1));
        assertThrows(IllegalArgumentException.class, () -> service.updateMaximumShiftDayDurationHours(req));
    }

    @Test
    void update_rejectsAboveTheTwentyFourHourCeiling() {
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        req.setMaximumShiftDayDurationHours(BigDecimal.valueOf(24.1));
        assertThrows(IllegalArgumentException.class, () -> service.updateMaximumShiftDayDurationHours(req));
    }

    @Test
    void update_acceptsExactlyTwentyFourHours_theInclusiveCeiling() {
        when(repository.findBySingletonTrue()).thenReturn(Optional.of(singletonRow(18)));
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        req.setMaximumShiftDayDurationHours(BigDecimal.valueOf(24));

        ShiftWeeklyOffRulesResponse response = service.updateMaximumShiftDayDurationHours(req);

        assertEquals(24.0, response.getMaximumShiftDayDurationHours());
    }

    @Test
    void update_rejectsNull() {
        UpdateShiftWeeklyOffRulesRequest req = new UpdateShiftWeeklyOffRulesRequest();
        assertThrows(IllegalArgumentException.class, () -> service.updateMaximumShiftDayDurationHours(req));
    }
}
