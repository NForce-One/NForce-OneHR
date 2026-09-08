package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendancePunch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendancePunchRepository extends JpaRepository<AttendancePunch, UUID> {

    // Backs the NORMAL Check-In/Check-Out open-session signal, independent of any Web Clock-In
    // session (see AttendanceService.checkIn/checkOut/getToday) — AttendancePunch carries no
    // employeeUserId of its own (only attendanceRecordId), so this resolves it via a subquery on
    // Attendance rather than a mapped join. Ordered newest-first, same "pick the most recent, LIMIT
    // 1" defensive convention as the other findFirst...OrderBy... queries in this codebase — a
    // plain unique lookup would throw the moment more than one punch is ever left open.
    @Query("SELECT p FROM AttendancePunch p WHERE p.checkOutAt IS NULL "
            + "AND p.attendanceRecordId IN (SELECT a.id FROM Attendance a WHERE a.employeeUserId = :employeeUserId) "
            + "ORDER BY p.checkInAt DESC")
    List<AttendancePunch> findOpenByEmployeeUserId(@Param("employeeUserId") UUID employeeUserId);

    List<AttendancePunch> findByAttendanceRecordIdOrderByCheckInAtAsc(UUID attendanceRecordId);

    // findFirstBy...OrderBy...Desc rather than a plain findBy — a data slip (or a past bug) can
    // leave more than one punch open under the same attendance record, and a plain findBy throws
    // NonUniqueResultException the moment that happens, crashing checkout instead of degrading
    // gracefully. Ordering by checkInAt descending picks the most recently opened session, which
    // is always the one an in-progress checkOut actually means to close.
    Optional<AttendancePunch> findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(UUID attendanceRecordId);

    // Backs AttendanceService.flagMissingCheckoutIfStale's "is this record ACTUALLY still open"
    // guard — Attendance.checkOutAt is null for every Web-Clock-In-only day (by design, see
    // WebClockInService.checkOut's own comment: it never touches that column), so the org-wide
    // stale sweep's candidate query (Attendance.checkOutAt IS NULL) alone cannot tell a genuinely
    // forgotten normal check-in apart from an already-fully-closed Web-only day. This is the
    // normal-session half of that check.
    boolean existsByAttendanceRecordIdAndCheckOutAtIsNull(UUID attendanceRecordId);

    // Backs the "Frequent Breaks" negligence panel (ONEHR-107) — pulls every session for a
    // batch of attendance records (already scoped to a manager's team + date range) in one
    // query, grouped/aggregated in the service layer since punches carry no employeeUserId/workDate.
    List<AttendancePunch> findByAttendanceRecordIdInOrderByCheckInAtAsc(Collection<UUID> attendanceRecordIds);
}
