package com.nforce.onehr.repository;

import com.nforce.onehr.entity.EmployeeShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeShiftAssignmentRepository extends JpaRepository<EmployeeShiftAssignment, UUID> {

    /** The resolver's one core query: the latest assignment whose effectiveFrom <= workDate. */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID employeeUserId, LocalDate workDate);

    /** At most one row is ever expected (see the "at most one pending assignment" rule enforced in EmployeeAssignmentService) — used to find it for display/replacement. */
    Optional<EmployeeShiftAssignment> findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(UUID employeeUserId, LocalDate today);

    /** Bulk-safe replace: clears any pending assignment(s) before inserting a new one, even if more than one somehow exists. */
    void deleteByEmployeeUserIdAndEffectiveFromGreaterThan(UUID employeeUserId, LocalDate today);

    /** Full history for an employee, newest first — mirrors ShiftVersionRepository's identical drill-down query. */
    List<EmployeeShiftAssignment> findByEmployeeUserIdOrderByEffectiveFromDesc(UUID employeeUserId);
}
