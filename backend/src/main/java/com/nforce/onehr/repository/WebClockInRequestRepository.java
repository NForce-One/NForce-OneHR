package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WebClockInRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    // Every Web Clock-In cycle for the day, oldest first — an employee can Web Clock-In and
    // Web Clock-Out more than once per day (see WebClockInService#submit), so this is a List,
    // not a single Optional result. Backs AttendanceService#collectPunches's punch-history merge,
    // and WebClockInService#submit's own "is this the first cycle of the day" check (an empty
    // list here is what gates the mandatory first-cycle note and the once-per-day manager
    // notification — see its own Javadoc).
    List<WebClockInRequest> findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(
            UUID employeeUserId, LocalDate workDate);

    // Backs the "Remote Clock-ins" / "Remote Clock-in Requests Summary" / "Web Clock-ins" report
    // cards (ONEHR-109) — one entity backs all three, a manager's team over a date range.
    List<WebClockInRequest> findByEmployeeUserIdInAndWorkDateBetween(
            Collection<UUID> employeeUserIds, LocalDate from, LocalDate to);

    // The employee's currently-open request (submitted but not yet checked out), if any —
    // independent of calendar date, because a web clock-in from before midnight (shift crosses
    // into the next day) is still filed under *yesterday's* work_date once the clock rolls over,
    // so "today" is the wrong key to look it up by. See WebClockInService.checkOut/cancel.
    //
    // "findFirst...OrderBy..." (LIMIT 1), not a bare uniqueness-assuming lookup — same reasoning
    // as AttendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc:
    // a plain findBy...IsNull() throws IncorrectResultSizeDataAccessException the moment an
    // employee ever ends up with more than one open request, rather than just picking the most
    // recent one.
    Optional<WebClockInRequest> findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(UUID employeeUserId);
}
