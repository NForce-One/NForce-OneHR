package com.nforce.onehr.service;

import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link EmployeeShiftAssignmentResolver}'s two-method contract — mirrors
 * {@link ShiftVersionResolverTest}'s identical shape, one layer up: {@link
 * EmployeeShiftAssignmentResolver#resolve} throws {@link IllegalStateException} for a date the
 * employee has no assignment effective on or before (the invariant every attendance-relevant
 * consumer depends on); {@link EmployeeShiftAssignmentResolver#resolveIfPresent} is the
 * non-throwing counterpart used by {@link ShiftDayPolicy}'s own "did this employee already have
 * SOME assignment as of yesterday" pre-check.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeShiftAssignmentResolverTest {

    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;

    private final UUID employeeUserId = UUID.randomUUID();
    private final Shift shift = Shift.builder().id(UUID.randomUUID()).name("test").build();
    private final LocalDate day = LocalDate.of(2026, 9, 10);

    @Test
    void resolve_returnsTheAssignmentWhenOneExists() {
        EmployeeShiftAssignmentResolver resolver = new EmployeeShiftAssignmentResolver(employeeShiftAssignmentRepository);
        EmployeeShiftAssignment assignment = EmployeeShiftAssignment.builder()
                .employeeUserId(employeeUserId).shift(shift).effectiveFrom(day).build();
        when(employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.of(assignment));

        assertEquals(assignment, resolver.resolve(employeeUserId, day));
    }

    @Test
    void resolve_throwsWithAClearMessage_whenNoAssignmentIsEffective() {
        EmployeeShiftAssignmentResolver resolver = new EmployeeShiftAssignmentResolver(employeeShiftAssignmentRepository);
        when(employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolve(employeeUserId, day));
        assertTrue(ex.getMessage().contains("no Shift assignment effective on or before " + day));
    }

    @Test
    void resolveIfPresent_returnsEmpty_ratherThanThrowing_whenNoAssignmentIsEffective() {
        EmployeeShiftAssignmentResolver resolver = new EmployeeShiftAssignmentResolver(employeeShiftAssignmentRepository);
        when(employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.empty());

        assertFalse(resolver.resolveIfPresent(employeeUserId, day).isPresent());
    }

    @Test
    void resolveIfPresent_returnsTheSameAssignmentResolveWould_whenOneExists() {
        EmployeeShiftAssignmentResolver resolver = new EmployeeShiftAssignmentResolver(employeeShiftAssignmentRepository);
        EmployeeShiftAssignment assignment = EmployeeShiftAssignment.builder()
                .employeeUserId(employeeUserId).shift(shift).effectiveFrom(day).build();
        when(employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.of(assignment));

        assertEquals(Optional.of(assignment), resolver.resolveIfPresent(employeeUserId, day));
    }

    @Test
    void resolve_returnsTheLatestAssignmentEffectiveOnOrBeforeTheDate_whenMultipleExist() {
        // The repository query itself is responsible for "latest effectiveFrom <= date" ordering
        // (findFirstBy...OrderByEffectiveFromDesc) — this test locks in that resolve() trusts and
        // returns exactly what the repository resolves, the same contract ShiftVersionResolver's
        // own test proves for the identical query shape one layer down.
        EmployeeShiftAssignment latest = EmployeeShiftAssignment.builder()
                .employeeUserId(employeeUserId).shift(shift).effectiveFrom(day.minusDays(1)).build();
        EmployeeShiftAssignmentResolver resolver = new EmployeeShiftAssignmentResolver(employeeShiftAssignmentRepository);
        when(employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeUserId, day))
                .thenReturn(Optional.of(latest));

        assertEquals(latest, resolver.resolve(employeeUserId, day));
    }
}
