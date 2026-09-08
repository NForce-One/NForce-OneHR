package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WebClockInRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface WebClockInRequestRepository extends JpaRepository<WebClockInRequest, UUID> {

    List<WebClockInRequest> findByEmployeeUserIdOrderByCreatedAtDesc(UUID employeeUserId);

    // Backs AttendanceService.flagMissingCheckoutIfStale's "is this record ACTUALLY still open"
    // guard — the Web-session half of that check (see AttendancePunchRepository
    // .existsByAttendanceRecordIdAndCheckOutAtIsNull's identical-purpose comment for the normal-
    // session half). A day with no open Web session either (checkedOutAt already set on every
    // request for it) is NOT stale merely because the shared Attendance.checkOutAt column
    // — which Web Clock-Out deliberately never writes — happens to be null.
    boolean existsByEmployeeUserIdAndWorkDateAndCheckedOutAtIsNull(UUID employeeUserId, LocalDate workDate);

    // Backs audit-log target search — resolves which web clock-in requests belong to a set of employees.
    @Query("SELECT r.id FROM WebClockInRequest r WHERE r.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    // Backs WebClockInService#listPendingForApprover's company-wide (not manager-scoped) HR/
    // Super Admin branch — joins User to exclude soft-deleted requesters' web clock-in requests.
    @Query("SELECT r FROM WebClockInRequest r JOIN User u ON u.id = r.employeeUserId "
         + "WHERE r.status = :status AND u.deletedAt IS NULL")
    List<WebClockInRequest> findByStatus(@Param("status") String status);

    // Every Web Clock-In cycle for the day, oldest first — an employee can Web Clock-In and
    // Web Clock-Out more than once per day (see WebClockInService#submit), so this is a List,
    // not a single Optional result. Backs AttendanceService#collectPunches's punch-history merge.
    // Deliberately NOT filtered by status: the attendance effect (and the worked time it
    // represents) is applied the moment the request is submitted, regardless of PENDING/
    // APPROVED/REJECTED — HR review is a separate, parallel record, not a gate on whether the
    // session happened or how long it ran. See WebClockInService's class Javadoc.
    List<WebClockInRequest> findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(
            UUID employeeUserId, LocalDate workDate);

    // Backs the "Remote Clock-ins" / "Remote Clock-in Requests Summary" / "Web Clock-ins" report
    // cards (ONEHR-109) — one entity backs all three, a manager's team over a date range.
    List<WebClockInRequest> findByEmployeeUserIdInAndWorkDateBetween(
            Collection<UUID> employeeUserIds, LocalDate from, LocalDate to);

    // The employee's currently-open request (submitted but not yet checked out), if any —
    // independent of calendar date AND of review status: whether HR has approved, rejected, or
    // not yet looked at it, the employee must still be able to check out (or cancel) their own
    // real, in-progress session — review status is never a gate on that. Independent of calendar
    // date because a web clock-in from before midnight (shift crosses into the next day) is
    // still filed under *yesterday's* work_date once the clock rolls over, so "today" is the
    // wrong key to look it up by. See WebClockInService.checkOut/cancel.
    //
    // "findFirst...OrderBy..." (LIMIT 1), not a bare uniqueness-assuming lookup — same reasoning
    // as AttendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc:
    // a plain findBy...IsNull() throws IncorrectResultSizeDataAccessException the moment an
    // employee ever ends up with more than one open request, rather than just picking the most
    // recent one.
    Optional<WebClockInRequest> findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(UUID employeeUserId);
}
