package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ShiftVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftVersionRepository extends JpaRepository<ShiftVersion, UUID> {

    /** The resolver's one core query: the latest version whose effectiveFrom <= workDate. */
    Optional<ShiftVersion> findFirstByShiftIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID shiftId, LocalDate workDate);

    /** At most one row is ever expected (see the "at most one pending version" rule enforced in OrgService) — used to find it for display/replacement. */
    Optional<ShiftVersion> findFirstByShiftIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(UUID shiftId, LocalDate today);

    /** Bulk-safe replace: clears any pending version(s) before inserting a new one, even if more than one somehow exists. */
    void deleteByShiftIdAndEffectiveFromGreaterThan(UUID shiftId, LocalDate today);

    /** Full history for a shift, newest first — backs the Shifts tab's version drill-down. */
    List<ShiftVersion> findByShiftIdOrderByEffectiveFromDesc(UUID shiftId);
}
