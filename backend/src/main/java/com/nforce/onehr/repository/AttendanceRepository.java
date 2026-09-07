package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Attendance;
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
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    Optional<Attendance> findByEmployeeUserIdAndWorkDate(UUID employeeUserId, LocalDate workDate);

    // The employee's currently open session, if any — independent of calendar date. Needed
    // because a shift can cross midnight (e.g. 3:30 PM - 12:30 AM): the open record may still
    // be filed under *yesterday's* work_date once the clock rolls over, so "today" is the
    // wrong key to look it up by. See AttendanceService.checkIn/checkOut/getToday.
    //
    // "findFirst...OrderBy..." (LIMIT 1), not a bare uniqueness-assuming lookup: several
    // employees have more than one still-open row from before this cross-midnight logic
    // existed (an old forgotten check-out, then a check-in on a later date that the old
    // per-date lookup happily allowed). A plain findBy...IsNull() throws
    // IncorrectResultSizeDataAccessException the moment it hits one of those accounts — this
    // picks the most recent open session instead of crashing on the stale one.
    Optional<Attendance> findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(UUID employeeUserId);

    // Every currently-open session, across all employees — backs the periodic sweep that
    // auto-closes any session left open past its own shift's natural end (see
    // AttendanceService.closeAllStaleOpenSessions / StaleAttendanceSweeper). Small in practice:
    // at most one open row per employee at any time.
    List<Attendance> findByCheckOutAtIsNull();

    // Backs the "every 3rd late arrival this month costs a half-day" policy — see
    // AttendanceService.checkIn/applyLatePenaltyIfDue.
    long countByEmployeeUserIdAndWorkDateBetweenAndStatus(UUID employeeUserId, LocalDate from, LocalDate to, String status);

    // Backs audit-log target search — resolves which attendance records belong to a set of employees.
    @Query("SELECT a.id FROM Attendance a WHERE a.employeeUserId IN :employeeUserIds")
    Set<UUID> findIdsByEmployeeUserIdIn(Collection<UUID> employeeUserIds);

    List<Attendance> findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
            UUID employeeUserId, LocalDate from, LocalDate to);

    List<Attendance> findByWorkDate(LocalDate workDate);

    List<Attendance> findByWorkDateAndEmployeeUserIdIn(LocalDate workDate, List<UUID> employeeUserIds);

    List<Attendance> findByEmployeeUserIdInAndWorkDateBetween(List<UUID> employeeUserIds, LocalDate from, LocalDate to);

    // Backs the periodic sweep that finalizes HALF_DAY (or confirms PRESENT/LATE) once a shift
    // has actually ended for a day closeSession/WebClockInService.checkOut deliberately left
    // un-finalized at checkout time — see AttendanceService.closeSession's own comment. Scoped
    // to `from` (a small recent-days window, not all history) since a record only stays
    // "pending finalization" between its own checkout and its own shift's natural end — at most
    // a day or two even for the most delayed overnight-shift case — so this never grows into an
    // unbounded full-table scan as the org's attendance history grows.
    List<Attendance> findByStatusInAndWorkDateGreaterThanEqual(Collection<String> statuses, LocalDate from);

    // Backs OrgService#deleteShift's historical-usage guard — a Shift must never be deletable
    // once ANY Attendance (including historical, already-closed records) references it via its
    // snapshotted shiftId, or deletion would silently erase that historical context. Deliberately
    // NOT scoped to "current" anything — this checks every row regardless of age or status.
    boolean existsByShiftId(UUID shiftId);
}
