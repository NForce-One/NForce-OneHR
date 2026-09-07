package com.nforce.onehr.config;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ShiftRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The one mechanism this codebase relies on to guarantee every employee ends up with an assigned
 * Shift (see ShiftDayPolicy, which has no fallback for a null one) — both for any employee left
 * over from before the create-time default existed, and as a self-healing safety net going
 * forward. Runs on every application startup (see the class's own {@code @Order}).
 */
@ExtendWith(MockitoExtension.class)
class ShiftSeedCorrectorTest {

    @Mock private ShiftRepository shiftRepository;
    @Mock private EmployeeRepository employeeRepository;

    private ShiftSeedCorrector corrector() {
        return new ShiftSeedCorrector(shiftRepository, employeeRepository);
    }

    private Shift regularShift() {
        // Timing is irrelevant here — ShiftSeedCorrector's backfill only ever assigns the Shift
        // identity (see its own Javadoc: it no longer touches timing at all), never resolves a
        // ShiftVersion.
        return Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(true).build();
    }

    @Test
    void assignsRegularShift_toEveryEmployeeWithNoShift() {
        Shift regular = regularShift();
        Employee unassignedA = Employee.builder().userId(UUID.randomUUID()).build();
        Employee unassignedB = Employee.builder().userId(UUID.randomUUID()).build();
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.of(regular));
        when(employeeRepository.findByShiftIsNull()).thenReturn(List.of(unassignedA, unassignedB));

        corrector().run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Employee>> captor = ArgumentCaptor.forClass(List.class);
        verify(employeeRepository).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertTrue(captor.getValue().stream().allMatch(e -> regular.equals(e.getShift())));
    }

    @Test
    void doesNothing_whenNoEmployeeIsMissingAShift() {
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.of(regularShift()));
        when(employeeRepository.findByShiftIsNull()).thenReturn(List.of());

        corrector().run(null);

        verify(employeeRepository, never()).saveAll(any());
    }

    /**
     * If "Regular Shift" is somehow missing entirely, this must not throw or otherwise block
     * startup — it silently skips the backfill for this run (the org-level master-data problem is
     * a separate concern from this corrector's own job).
     */
    @Test
    void doesNothing_whenRegularShiftItselfIsMissing() {
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> corrector().run(null));

        verifyNoInteractions(employeeRepository);
    }

    /**
     * Regular Shift is a completely ordinary shift an admin can deactivate — this backfill must
     * respect the same "must be active to be newly assigned" rule every other assignment path
     * (create-employee, bulk-assign, CSV import) already enforces, not silently bypass it just
     * because it's the organization's default.
     */
    @Test
    void doesNotAssignAnInactiveRegularShift() {
        Shift inactiveRegular = Shift.builder().id(UUID.randomUUID()).name(Shift.DEFAULT_SHIFT_NAME).active(false).build();
        when(shiftRepository.findByName(Shift.DEFAULT_SHIFT_NAME)).thenReturn(Optional.of(inactiveRegular));

        corrector().run(null);

        verify(employeeRepository, never()).findByShiftIsNull();
        verify(employeeRepository, never()).saveAll(any());
    }
}
