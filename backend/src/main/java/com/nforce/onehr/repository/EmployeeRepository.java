package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    // LEFT JOIN FETCH u.roles avoids an N+1: User.roles is EAGER, so without fetching it here
    // Hibernate would issue one extra "load roles" query per distinct user the moment each
    // Employee's User is initialized — 89 employees meant ~89 extra round trips (plus whatever
    // each caller's own per-employee lookups add on top), enough to make this take over a
    // minute against a remote DB. DISTINCT dedupes the root Employee rows the roles join
    // otherwise multiplies (a user with 2 roles would otherwise appear twice).
    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH u.roles LEFT JOIN FETCH e.businessUnit LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL")
    List<Employee> findAllWithDetails();

    // Backs WorkingDayService callers (Team Effort, Team Punctuality), the Penalty list, and
    // ExceptionService's per-employee shift-start resolution — joins the associations those read
    // (location, weeklyOffPolicy, designation, department, shift) in one query instead of one
    // lazy-load per employee.
    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.location LEFT JOIN FETCH e.weeklyOffPolicy "
         + "LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.department LEFT JOIN FETCH e.shift WHERE e.userId IN :ids")
    List<Employee> findAllByIdWithScheduleDetails(@Param("ids") Collection<UUID> ids);

    // Backs EmployeeService#getManagerDashboard — joins the associations that method reads
    // (user, designation, department) in one query instead of one findById (plus lazy-loads
    // of each association) per direct report.
    @Query("SELECT e FROM Employee e JOIN FETCH e.user LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.department WHERE e.userId IN :ids")
    List<Employee> findAllByIdWithUserDetails(@Param("ids") Collection<UUID> ids);

    // Backs the shift/weekly-off import (ONEHR-108) — rows are addressed by employee code, not id.
    Optional<Employee> findByEmployeeCode(String employeeCode);

    // Backs EmployeeCodeGenerator#claim's availability check for a manually-entered Employee
    // ID — the employees.employee_code UNIQUE constraint is still the final backstop against a
    // race between two such checks.
    boolean existsByEmployeeCode(String employeeCode);

    // Scoped to a non-deleted user: several test emails in this dataset have been
    // re-registered after a soft delete (same email, new user row), and an unscoped
    // lookup here matches every row for that email — Spring Data throws
    // IncorrectResultSizeDataAccessException the moment there's more than one.
    @Query("SELECT e FROM Employee e JOIN FETCH e.user u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<Employee> findByUser_Email(@Param("email") String email);

    @Query("SELECT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.businessUnit LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true")
    List<Employee> findAllActiveWithDetails();

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id NOT IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN ('HR_ADMIN', 'SUPER_ADMIN'))")
    List<Employee> findAllActiveNonAdminWithDetails();

    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department LEFT JOIN FETCH e.designation LEFT JOIN FETCH e.location WHERE u.deletedAt IS NULL AND u.active = true AND u.id IN (SELECT u2.id FROM User u2 JOIN u2.roles r WHERE r.code IN :roleCodes)")
    List<Employee> findActiveByRoleCodes(@Param("roleCodes") Set<String> roleCodes);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.businessUnit.id = :id AND u.deletedAt IS NULL")
    long countByBusinessUnitId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.department.id = :id AND u.deletedAt IS NULL")
    long countByDepartmentId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.designation.id = :id AND u.deletedAt IS NULL")
    long countByDesignationId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.location.id = :id AND u.deletedAt IS NULL")
    long countByLocationId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.shift.id = :id AND u.deletedAt IS NULL")
    long countByShiftId(@Param("id") UUID id);

    @Query("SELECT COUNT(e) FROM Employee e JOIN e.user u WHERE e.weeklyOffPolicy.id = :id AND u.deletedAt IS NULL")
    long countByWeeklyOffPolicyId(@Param("id") UUID id);

    // Batch equivalents of the 4 single-id counts above, one GROUP BY query each instead of one
    // COUNT query per row — backs OrgService's listDepartments/listDesignations/listLocations/
    // listShifts, which used to issue N extra round trips for N master-data rows on every
    // Organization Masters page load (and every Add/Edit User modal open, for the Shift
    // dropdown). Each returns Object[]{id (UUID), count (Long)}; a master-data row with zero
    // employees simply has no entry — callers default to 0.
    @Query("SELECT e.businessUnit.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.businessUnit IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.businessUnit.id")
    List<Object[]> countGroupedByBusinessUnitId();

    @Query("SELECT e.department.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.department IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.department.id")
    List<Object[]> countGroupedByDepartmentId();

    @Query("SELECT e.designation.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.designation IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.designation.id")
    List<Object[]> countGroupedByDesignationId();

    @Query("SELECT e.location.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.location IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.location.id")
    List<Object[]> countGroupedByLocationId();

    @Query("SELECT e.shift.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.shift IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.shift.id")
    List<Object[]> countGroupedByShiftId();

    @Query("SELECT e.weeklyOffPolicy.id, COUNT(e) FROM Employee e JOIN e.user u WHERE e.weeklyOffPolicy IS NOT NULL AND u.deletedAt IS NULL GROUP BY e.weeklyOffPolicy.id")
    List<Object[]> countGroupedByWeeklyOffPolicyId();

    // Backs the Shifts master-data "Employees" drill-down (Organization Masters → Shifts) —
    // same non-deleted scoping as countByShiftId, with department fetched to avoid an N+1.
    @Query("SELECT DISTINCT e FROM Employee e JOIN FETCH e.user u LEFT JOIN FETCH e.department "
         + "WHERE e.shift.id = :shiftId AND u.deletedAt IS NULL ORDER BY e.fullName")
    List<Employee> findByShiftIdWithDetails(@Param("shiftId") UUID shiftId);

    @Query("SELECT e.userId, e.fullName FROM Employee e WHERE e.userId IN :ids")
    List<Object[]> findNamesByUserIds(@Param("ids") Set<UUID> ids);

    // Backs LeaveService's batched employee-card rendering (Dashboard's Present Today/On Leave
    // widgets show avatar + name + code, Directory-row style) — same shape/usage as
    // findNamesByUserIds above, kept as its own method rather than widening that one's shared
    // 2-column shape, which many unrelated call sites already depend on.
    @Query("SELECT e.userId, e.employeeCode FROM Employee e WHERE e.userId IN :ids")
    List<Object[]> findCodesByUserIds(@Param("ids") Set<UUID> ids);

    // Batch employee + department fetch backing RegularizationService/WebClockInService's list
    // responses — one query for every row's employee name + department name instead of one
    // findById per row (department is LAZY, so a plain findAllById would still N+1 on it).
    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.department WHERE e.userId IN :ids")
    List<Employee> findAllByIdWithDepartment(@Param("ids") Collection<UUID> ids);

    // Backs ShiftSeedCorrector's startup backfill for employees created after V95's one-time
    // "assign everyone the default shift" migration ran (e.g. anyone onboarded since).
    List<Employee> findByShiftIsNull();

    // Backs the Policy List's "Employee Count" column (Section 5) — the legacy-FK-only half of
    // the authoritative count; see PenalizationPolicyResolutionService#resolveCurrentEmployeeCount
    // for the actual allocation-aware count callers should use instead.
    long countByPenalisationPolicy_Id(UUID penalisationPolicyId);

    // (employeeUserId, legacyPolicyId-or-null) for every non-deleted employee — the other bulk
    // read PenalizationPolicyResolutionService#resolveCurrentPolicyIdsByEmployee needs, alongside
    // PenalizationPolicyAllocationRepository#findCurrentAllocationsAt, to resolve every
    // employee's current policy in exactly two queries total.
    @Query("SELECT e.userId, e.penalisationPolicy.id FROM Employee e JOIN e.user u WHERE u.deletedAt IS NULL")
    List<Object[]> findAllEmployeeIdsWithLegacyPolicyId();

    // Backs the mid-cycle policy-change notification (PenalizationPolicyService#save) — every
    // employee explicitly assigned to the policy being edited.
    List<Employee> findByPenalisationPolicy_Id(UUID penalisationPolicyId);

    // Same notification, for employees with no explicit assignment who fall back to the org's
    // default policy (see PenalizationPolicyService#resolveDefaultPolicyId) — only consulted when
    // the policy being edited IS that default.
    List<Employee> findByPenalisationPolicyIsNull();

    // Backs the Penalization Policy Allocation employee search's row-hydration step —
    // PenalizationPolicyAllocationService#searchEmployees runs the filtered ID lookup through
    // Specifications first (JpaSpecificationExecutor#findAll), then re-fetches those rows here.
    // A purpose-built projection (only the scalar columns the Allocation row actually reads) —
    // NOT full JPA entities via JOIN FETCH — since fetching entire BusinessUnit/Department/
    // Designation/Location/User entity graphs for every row (previously via 5-way JOIN FETCH +
    // DISTINCT) was the dominant cost of the Allocation page's load time. LEFT JOIN (not FETCH):
    // every one of these associations is nullable (see V4/V135's schema), so this must not become
    // an inner join or employees with no business unit/department/etc. would silently disappear.
    @Query("SELECT new com.nforce.onehr.dto.penalization.EmployeeAllocationProjection("
         + "e.userId, e.employeeCode, e.fullName, u.email, u.active, d.title, "
         + "bu.id, bu.name, dept.id, dept.name, loc.id, loc.name, pp.id) "
         + "FROM Employee e "
         + "JOIN e.user u "
         + "LEFT JOIN e.designation d "
         + "LEFT JOIN e.businessUnit bu "
         + "LEFT JOIN e.department dept "
         + "LEFT JOIN e.location loc "
         + "LEFT JOIN e.penalisationPolicy pp "
         + "WHERE e.userId IN :ids")
    List<com.nforce.onehr.dto.penalization.EmployeeAllocationProjection> findAllocationProjectionsByIds(@Param("ids") Collection<UUID> ids);

    // Backs OrgService's hard-delete of a Department/Designation/Location/Shift master row —
    // called only after countBy*Id above has already confirmed 0 *active* (non-deleted)
    // employees remain. Any employee row still pointing at the id being deleted at that point is
    // therefore necessarily a soft-deleted (terminated) one — deleting a user never clears their
    // Employee row's own department/designation/location/shift columns (see
    // UserManagementService.deleteUser), so a stale FK is all that's left blocking the master
    // row's real removal. These null out that already-nullable column (same "— None —" state the
    // UI already supports) rather than touching the employee row itself or any Attendance/audit
    // table — no historical data is deleted or reassigned, only a now-dangling reference cleared.
    @Modifying
    @Query("UPDATE Employee e SET e.department = NULL WHERE e.department.id = :id")
    void clearDepartmentReferences(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Employee e SET e.designation = NULL WHERE e.designation.id = :id")
    void clearDesignationReferences(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Employee e SET e.location = NULL WHERE e.location.id = :id")
    void clearLocationReferences(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Employee e SET e.shift = NULL WHERE e.shift.id = :id")
    void clearShiftReferences(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Employee e SET e.weeklyOffPolicy = NULL WHERE e.weeklyOffPolicy.id = :id")
    void clearWeeklyOffPolicyReferences(@Param("id") UUID id);
}
