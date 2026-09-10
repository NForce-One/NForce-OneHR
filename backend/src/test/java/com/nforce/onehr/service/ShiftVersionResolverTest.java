package com.nforce.onehr.service;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import com.nforce.onehr.repository.ShiftVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ShiftVersionResolver}'s two-method contract: {@link ShiftVersionResolver#resolve} keeps
 * throwing {@link IllegalStateException} for a date the Shift has no applicable version for (the
 * invariant every other Attendance-relevant consumer depends on, unchanged by the ONEHR-336
 * follow-up); {@link ShiftVersionResolver#resolveIfPresent} is the new non-throwing counterpart
 * used exclusively by {@link ShiftDayPolicy}'s own "did the Shift already exist as of yesterday"
 * pre-check — see that class's test for the actual bug-fix scenario.
 */
@ExtendWith(MockitoExtension.class)
class ShiftVersionResolverTest {

    @Mock private ShiftVersionRepository shiftVersionRepository;

    private final Shift shift = Shift.builder().id(UUID.randomUUID()).name("test").build();
    private final LocalDate day = LocalDate.of(2026, 9, 10);

    @Test
    void resolve_returnsTheVersionWhenOneExists() {
        ShiftVersionResolver resolver = new ShiftVersionResolver(shiftVersionRepository);
        ShiftVersion version = ShiftVersion.builder().shift(shift).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(day).build();
        when(shiftVersionRepository.findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), day))
                .thenReturn(Optional.of(version));

        assertEquals(version, resolver.resolve(shift, day));
    }

    @Test
    void resolve_throwsWithAClearMessage_whenNoVersionIsEffective() {
        ShiftVersionResolver resolver = new ShiftVersionResolver(shiftVersionRepository);
        when(shiftVersionRepository.findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), day))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolve(shift, day));
        assertTrue(ex.getMessage().contains("has no version effective on or before " + day));
    }

    @Test
    void resolveIfPresent_returnsEmpty_ratherThanThrowing_whenNoVersionIsEffective() {
        ShiftVersionResolver resolver = new ShiftVersionResolver(shiftVersionRepository);
        when(shiftVersionRepository.findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), day))
                .thenReturn(Optional.empty());

        assertFalse(resolver.resolveIfPresent(shift, day).isPresent());
    }

    @Test
    void resolveIfPresent_returnsTheSameVersionResolveWould_whenOneExists() {
        ShiftVersionResolver resolver = new ShiftVersionResolver(shiftVersionRepository);
        ShiftVersion version = ShiftVersion.builder().shift(shift).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).effectiveFrom(day).build();
        when(shiftVersionRepository.findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(shift.getId(), day))
                .thenReturn(Optional.of(version));

        assertEquals(Optional.of(version), resolver.resolveIfPresent(shift, day));
    }
}
