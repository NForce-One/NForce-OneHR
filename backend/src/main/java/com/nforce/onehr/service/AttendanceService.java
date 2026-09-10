package com.nforce.onehr.service;

import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendanceContext;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.DailyPunctuality;
import com.nforce.onehr.dto.attendance.PunctualityLeaderboardEntry;
import com.nforce.onehr.dto.attendance.PunctualitySummary;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Punch-clock attendance: self-service check-in/check-out plus HR/Manager roster views.
 * Regularization (employee-submitted corrections) lives in {@link RegularizationService},
 * which upserts into the same {@link Attendance} rows this service writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    // A session that was never checked out and whose logical workday (per ShiftDayPolicy,
    // shift-relative — see its own Javadoc) has since ended — see flagMissingCheckoutIfStale.
    // Deliberately never paired with a
    // fabricated checkOutAt/workedMinutes: the actual check-out time is unknown, so none is
    // guessed. Corrected via the existing Regularization flow, same as any other attendance
    // correction.
    private static final String STATUS_MISSING_CHECKOUT = "MISSING_CHECKOUT";

    private static final int DEFAULT_HISTORY_DAYS = 30;

    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchRepository attendancePunchRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    // Shared with WebClockInService so the every-3rd-late-arrival penalty applies identically
    // regardless of which check-in entry point was used — see LatePenaltyService.
    private final LatePenaltyService latePenaltyService;
    private final WorkingDayService workingDayService;
    private final ExpectedWorkHoursService expectedWorkHoursService;
    // Single source of truth for shift-relative day/time computation — see its own Javadoc. Added
    // last so every existing explicit-constructor test only needs to append one argument.
    private final ShiftDayPolicy shiftDayPolicy;
    // Persisted, Admin-editable HALF_DAY threshold (Workstream B) — see its own Javadoc for why
    // this is the only app.attendance.* value that moved to a DB-backed singleton. Added last for
    // the same explicit-constructor-test reason as shiftDayPolicy above.
    private final AttendanceRulesService attendanceRulesService;
    // The single, narrow, shared owner of Shift-relative punch interpretation (work-date,
    // lateness, checkout/staleness boundary) — see its own class Javadoc. Added last for the same
    // explicit-constructor-test reason as shiftDayPolicy/attendanceRulesService above.
    private final AttendanceInterpretationService attendanceInterpretationService;
    // Only for getConfig's/historyFor's own "resolve for a real employee" pin — see
    // ShiftDayPolicy's pinned-vs-day-aware overload split. Added last for the same
    // explicit-constructor-test reason as the others above.
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;

    // ---------------------------------------------------------------- self-service

    @Transactional
    public TodayAttendanceResponse getToday(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);

        // canCheckIn/canCheckOut reflect ONLY the normal Check-In/Check-Out session state, driven
        // by AttendancePunch, deliberately independent of any Web Clock-In session (which has its
        // own, separate open/closed state — see WebClockInService and AttendanceHeroBanner's
        // WebClockInRow) — Web Clock-In/Out must never flip this status. A shift can cross
        // midnight (e.g. 3:30 PM - 12:30 AM) — an open normal session started yesterday is still
        // the actionable "today" state even once the calendar date has rolled over, so it takes
        // priority over a plain work_date lookup. But an open session whose own workday/grace
        // window has already ended (a forgotten checkout from days ago, not just "yesterday
        // crossing into today") is stale, not today's state — flag it Missing Check-Out (see
        // flagMissingCheckoutIfStale) and fall through to the plain work_date lookup below instead
        // of reporting it as an active session forever.
        Optional<Attendance> open = findOpenNormalAttendance(employee.getUserId());

        // An open session's own locked-in zone (from Check-In) decides "now" for it — the
        // viewer's current browser zone only applies once there's no open session to defer to,
        // i.e. for a genuinely fresh "today". See resolveZone's own doc comments.
        ZoneId zone = open.isPresent() ? resolveZone(open.get(), employee, clientTimezone) : resolveZone(clientTimezone, employee);
        LocalDateTime now = LocalDateTime.now(zone);
        // No existing record is known yet for the "no open session" branch below — a fresh-action
        // question, resolved against the employee's CURRENT Shift (see
        // AttendanceInterpretationService.interpretFreshAction). Unused when an open session
        // exists (that branch keys off the session's own record.getWorkDate() instead).
        LocalDate today = attendanceInterpretationService
                .interpretFreshAction(employee, new AttendanceContext(employee.getUserId(), now, zone))
                .getWorkDate();

        if (open.isPresent() && !flagMissingCheckoutIfStale(open.get(), now)) {
            Attendance record = open.get();
            return TodayAttendanceResponse.builder()
                    .workDate(record.getWorkDate())
                    .serverNow(now)
                    .canCheckIn(false)
                    .canCheckOut(true)
                    .record(toResponse(record, employee))
                    // Missing here previously — this active-session branch never computed these,
                    // so breakUsedMinutes came back null (frontend's `?? 0` fallback then showed
                    // "0 / N min" instead of the real accumulated total) for the entire duration
                    // of an open session, i.e. right after Check-In and on every refresh until
                    // Check-Out. The closed-record branch below already did this correctly.
                    .breakUsedMinutes(computeBreakMinutes(employee.getUserId(), record.getId(), record.getWorkDate()))
                    .build();
        }

        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today)
                .map(record -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        // No open session (checked above), so this record is always closed —
                        // another session can still be started (e.g. after a lunch break).
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(toResponse(record, employee))
                        .breakUsedMinutes(computeBreakMinutes(employee.getUserId(), record.getId(), today))
                        .build())
                .orElseGet(() -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(null)
                        .breakUsedMinutes(null)
                        .build());
    }

    /**
     * Sum of the gaps between consecutive closed punch sessions — an open (unclosed) session
     * contributes nothing yet. Spans BOTH punch sources (normal Check-In/Out and Web Check-In/
     * Out): a gap between, say, a Web Check-Out and a later normal Check-In is still a break,
     * so it must not be missed just because the two sessions came from different entry points.
     * See {@link #collectPunches}.
     */
    private int computeBreakMinutes(UUID employeeId, UUID attendanceRecordId, LocalDate workDate) {
        return sumGapMinutes(collectPunches(employeeId, attendanceRecordId, workDate));
    }

    private int sumGapMinutes(List<PunchResponse> punches) {
        int breakMinutes = 0;
        for (int i = 0; i < punches.size() - 1; i++) {
            LocalDateTime gapStart = punches.get(i).getCheckOutAt();
            LocalDateTime gapEnd = punches.get(i + 1).getCheckInAt();
            if (gapStart != null && gapEnd != null) {
                // Punches are sorted by checkInAt only (see collectPunches), but a Web Clock-In/
                // Out session can genuinely overlap a normal Check-In/Out session in real time
                // (they're independent — see WebClockInService's own class Javadoc), so an
                // adjacent pair here can have gapEnd before gapStart. That's a real overlap, not
                // a negative-length break — floor each interval at 0 rather than letting a
                // negative gap corrupt the day's total (surfaced to the employee as e.g. "-6 /
                // 60 min" on the Today's Timings panel). Mirrors the identical fix already
                // applied to the frontend's own computeBreakMinutesFromPunches.
                breakMinutes += Math.max(0, (int) Duration.between(gapStart, gapEnd).toMinutes());
            }
        }
        return breakMinutes;
    }

    /**
     * Every check-in/check-out session for one employee/day, from BOTH entry points — normal
     * Check-In/Out ({@link AttendancePunch}, source SYSTEM) and Web Check-In/Out
     * ({@link WebClockInRequest}, source WEB_REMOTE — an employee may Web Clock-In/Out more than
     * once per day, see WebClockInService#submit) — merged and sorted chronologically by
     * check-in time. Used for both the punch-history display and the break/gross/effective-hours
     * math, so a session started one way and continued the other still contributes correctly to
     * each, and no session (from either source) is ever double-counted or dropped.
     */
    private List<PunchResponse> collectPunches(UUID employeeId, UUID attendanceRecordId, LocalDate workDate) {
        List<PunchResponse> punches = new ArrayList<>();
        if (attendanceRecordId != null) {
            attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId)
                    .forEach(p -> punches.add(PunchResponse.builder()
                            .id(p.getId())
                            .checkInAt(p.getCheckInAt())
                            .checkOutAt(p.getCheckOutAt())
                            .source("SYSTEM")
                            .build()));
        }
        // Web Clock-In needs no approval at all (see WebClockInService's own class Javadoc) — the
        // session is real, and shown here, the moment it's submitted.
        webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate)
                .forEach(req -> punches.add(PunchResponse.builder()
                        .id(req.getId())
                        .checkInAt(req.getRequestedCheckIn())
                        .checkOutAt(req.getCheckedOutAt())
                        .source("WEB_REMOTE")
                        .note(req.getReason())
                        .build()));
        punches.sort(Comparator.comparing(PunchResponse::getCheckInAt));
        return punches;
    }

    /**
     * Read-only shift/break config for the Today's Timings panel. shiftStart/shiftEnd are
     * resolved from the caller's assigned Shift (ONEHR-108) — every employee is expected to
     * always have one, so this throws (via shiftDayPolicy) rather than falling back to any
     * global default if that invariant is ever violated.
     */
    @Transactional(readOnly = true)
    public AttendanceConfigResponse getConfig(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);
        WeeklyOffPolicy weeklyOffPolicy = employee.getWeeklyOffPolicy();
        // A live "what applies today" display, not tied to any specific historical Attendance
        // record — resolves the Shift Version effective right now, same as the Shifts tab itself.
        // Resolved in THIS employee's own zone (see AttendanceRulesService.resolveEmployeeZoneId)
        // — not the bare org-wide default — so the displayed shift start/end matches what their
        // own Check-In would actually compute.
        LocalDate configDay = LocalDate.now(attendanceRulesService.resolveEmployeeZoneId(employee));
        // Resolved from the Shift Assignment effective TODAY (never employee.getShift(), a
        // best-effort display cache — see that field's own Javadoc), then pinned so
        // ShiftDayPolicy's existing Employee-taking methods resolve against exactly this Shift.
        Shift shift = employeeShiftAssignmentResolver.resolve(employee.getUserId(), configDay).getShift();
        Employee pinnedToday = Employee.builder().userId(employee.getUserId()).shift(shift).build();

        return AttendanceConfigResponse.builder()
                .shiftName(shift.getName())
                .shiftStart(shiftDayPolicy.resolveShiftStart(pinnedToday, configDay))
                .shiftEnd(shiftDayPolicy.shiftEndAt(pinnedToday, configDay).toLocalTime())
                // Per-Shift-Version grace (see V168).
                .lateGraceMinutes(shiftDayPolicy.resolveLateGraceMinutes(pinnedToday, configDay))
                .halfDayMaxHours(attendanceRulesService.getHalfDayMaxHours())
                .weeklyOffDays(weeklyOffPolicy != null
                        ? Arrays.stream(weeklyOffPolicy.getOffDays().split(",")).map(String::trim).toList()
                        : List.of("SATURDAY", "SUNDAY"))
                .build();
    }

    /**
     * Always empty today — see AttendanceExceptionResponse's Javadoc. Passive read only, no
     * detection logic added here.
     */
    @Transactional(readOnly = true)
    public List<AttendanceExceptionResponse> getMyExceptions(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                        List.of(employee.getUserId()), from, to)
                .stream()
                .map(e -> AttendanceExceptionResponse.builder()
                        .id(e.getId())
                        .exceptionDate(e.getExceptionDate())
                        .exceptionType(e.getExceptionType())
                        .status(e.getStatus())
                        .minutesLate(e.getMinutesLate())
                        .build())
                .toList();
    }

    @Transactional
    public AttendanceResponse checkIn(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);
        assertEligibleToPunch(employee);

        // Only an open NORMAL session blocks a fresh Check-In — deliberately independent of any
        // open Web Clock-In session, which is tracked and gated entirely separately (see
        // WebClockInService). A shift can cross midnight (e.g. 3:30 PM - 12:30 AM) — an open
        // session from yesterday's work_date must block a fresh check-in exactly like an open
        // session filed under today would, so check for one regardless of date first. But an open
        // session whose own workday/grace window has already ended (a forgotten checkout from
        // days ago) is stale, not a real in-progress day — flag it Missing Check-Out (see
        // flagMissingCheckoutIfStale) instead of letting it block every check-in from now on.
        // Evaluated using the open session's OWN locked-in zone, not this click's browser zone —
        // see resolveZone's doc comments.
        Optional<Attendance> openSession = findOpenNormalAttendance(employee.getUserId());
        if (openSession.isPresent()) {
            LocalDateTime openNow = LocalDateTime.now(resolveZone(openSession.get(), employee, clientTimezone));
            if (!flagMissingCheckoutIfStale(openSession.get(), openNow)) {
                throw new IllegalArgumentException("You have already checked in today");
            }
        }

        // A fresh click (or resuming today's own record after the stale-session flag above) —
        // single clock read so the punch time and the work date it's attributed to can never
        // disagree — resolved from the employee's own configured Location.timezone (see
        // resolveZone's own doc comment: the browser-reported clientTimezone is never consulted).
        ZoneId freshZone = resolveZone(clientTimezone, employee);
        LocalDateTime now = LocalDateTime.now(freshZone);
        // No existing Attendance row is known yet at this point — this is inherently a "fresh
        // action" question (which work-date bucket does `now` belong to), so it's resolved
        // against the employee's CURRENT Shift via AttendanceInterpretationService, exactly as
        // ShiftDayPolicy always has. If a record already exists for the resulting date (the
        // "resume" branch below), that record's own lateness/status are left untouched — this
        // interpretation's isLate/lateByMinutes are only ever used in the create-new-record
        // branch further down.
        AttendanceInterpretation interpretation = attendanceInterpretationService.interpretFreshAction(
                employee, new AttendanceContext(employee.getUserId(), now, freshZone));
        LocalDate today = interpretation.getWorkDate();

        Optional<Attendance> existing = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today);

        if (existing.isPresent()) {
            // No open session (checked above), so this is always a closed record — resuming
            // after a break (e.g. lunch). The day's original check-in time, late status, and
            // worked-minutes-so-far all stay put; only a new session opens. Reuses the day's
            // ORIGINALLY locked-in zone (from its first check-in), not re-resolved from this
            // click, so the whole day's worked-minutes math stays on one consistent clock (e.g.
            // across a DST change over a long lunch).
            Attendance record = existing.get();
            LocalDateTime resumeNow = LocalDateTime.now(resolveZone(record, employee, clientTimezone));
            record.setSessionStartedAt(resumeNow);
            record.setCheckOutAt(null);
            Attendance saved = attendanceRepository.save(record);
            openPunch(saved.getId(), resumeNow);
            auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", saved.getId());
            return toResponse(saved, employee);
        }

        // isLate (official status, HR/penalty-relevant, grace-aware) and lateByMinutes
        // (employee-facing display only, no grace forgiveness) both come from the interpretation
        // computed above — see AttendanceInterpretationService's own Javadoc for the exact
        // formula (unchanged from what this method always computed inline).
        boolean isLate = interpretation.getIsLate();
        int lateByMinutes = interpretation.getLateByMinutes();

        Attendance record;
        try {
            // Flushed immediately (rather than left for this transaction's own commit-time
            // flush) so the UNIQUE(employee_user_id, work_date) constraint — the last line of
            // defense against two near-simultaneous fresh check-ins both passing the "no existing
            // record" check above before either commits — is hit right here, where it can be
            // caught and translated, instead of surfacing later as an unhandled 500.
            record = attendanceRepository.saveAndFlush(Attendance.builder()
                    .employeeUserId(employee.getUserId())
                    .workDate(today)
                    .checkInAt(now)
                    .sessionStartedAt(now)
                    .status(isLate ? STATUS_LATE : STATUS_PRESENT)
                    .lateByMinutes(lateByMinutes)
                    .timezone(freshZone.getId())
                    // Snapshotted once, here, at creation — never updated again. This is what lets a
                    // later reassignment of the employee to a different Shift leave this row's own
                    // interpretation (checkout cutoff, staleness boundary) untouched — see
                    // AttendanceInterpretationService.interpretExistingSession.
                    .shiftId(interpretation.getShiftId())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // The race actually happened — the same clean rejection the open-session check above
            // would have given if it had won the race instead.
            throw new IllegalArgumentException("You have already checked in today");
        }
        openPunch(record.getId(), now);
        if (isLate) {
            latePenaltyService.applyIfDue(employee, today);
        }

        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", record.getId());
        return toResponse(record, employee);
    }

    private void openPunch(UUID attendanceRecordId, LocalDateTime checkInAt) {
        // Defensive: close out any punch(es) still open under this record before opening a new
        // one. Should never happen in the normal flow — checkIn's own open-session guard blocks a
        // second check-in while one is already in progress — but a duplicate/retried request that
        // slips past that guard (e.g. a network-retry race) must not be allowed to leave more
        // than one simultaneously-open punch: that ambiguity is exactly what used to crash
        // checkOut with NonUniqueResultException. Closing any stragglers here, rather than only
        // tolerating them at read time, stops the bad state from accumulating further.
        attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId).stream()
                .filter(p -> p.getCheckOutAt() == null)
                .forEach(p -> {
                    p.setCheckOutAt(checkInAt);
                    attendancePunchRepository.save(p);
                });
        try {
            // Flushed immediately so idx_attendance_punches_one_open_per_record (the DB-level
            // backstop for "at most one open punch per record" — the close-stragglers scan above
            // is only a best-effort Java-level guard, not a real lock) is hit right here if two
            // concurrent resume-Check-In requests for this same record both raced past that scan
            // before either committed, instead of surfacing later as an unhandled 500.
            attendancePunchRepository.saveAndFlush(AttendancePunch.builder()
                    .attendanceRecordId(attendanceRecordId)
                    .checkInAt(checkInAt)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("You have already checked in today");
        }
    }

    @Transactional
    public AttendanceResponse checkOut(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);
        assertEligibleToPunch(employee);

        // Looked up by open NORMAL session, not by today's work_date — a shift that started
        // before midnight (e.g. 3:30 PM - 12:30 AM) is still open under *yesterday's* work_date
        // once the calendar date rolls over. Deliberately independent of any open Web Clock-In
        // session — see WebClockInService.checkOut, which manages its own session separately.
        Attendance record = findOpenNormalAttendance(employee.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("You have not checked in today"));

        // The session's own zone, locked in at Check-In — NOT this click's browser zone, which
        // may have drifted since (travel, DST) — governs its Check-Out, so worked-minutes math
        // and the grace-window check below stay on one consistent clock for the whole session.
        // clientTimezone only matters as a fallback for a record from before this column existed.
        LocalDateTime now = LocalDateTime.now(resolveZone(record, employee, clientTimezone));

        // Past its own workday/grace window (e.g. someone opens a stale tab and clicks Check Out
        // days later) — there is no legitimate "now" to check out with at this point, so this is
        // flagged Missing Check-Out (same as getToday/checkIn would have already done for this
        // record) rather than silently accepted as a real, very-late checkout. The frontend
        // should never offer this button once getToday reports canCheckOut=false for it, but a
        // stale request must not fabricate a checkout time either way.
        if (flagMissingCheckoutIfStale(record, now)) {
            throw new IllegalArgumentException(
                    "This session is past its check-out window and has been marked as a missing check-out. Please submit a regularization request.");
        }

        // The shift's own natural end still bounds the WORKED-MINUTES figure — a forgotten
        // checkout left open for hours (e.g. checking out at 3 AM for a shift that ended at
        // 12:30 AM) must not inflate into something like "27h 8m" for what's supposed to be a
        // single shift/day. But it must never be used as the recorded checkOutAt itself: the
        // actual click time (`now`) is always what gets stored, everywhere (this record, the
        // punch, the audit log, punch history) — shift timing only feeds the capped aggregate
        // below, via recomputeCombinedWorkedMinutes's capAt, never the timestamp. See
        // closeSession. Resolved against THIS RECORD's own snapshotted Shift (see
        // AttendanceInterpretationService.interpretExistingSession) — never the employee's
        // current one, so a reassignment while this session was open cannot change its cutoff.
        // Null (uncapped — closeSession already treats that as a supported value) only for a
        // legacy pre-snapshot row this interpretation cannot safely resolve.
        AttendanceInterpretation interpretation = attendanceInterpretationService.interpretExistingSession(record, now);
        LocalDateTime cutoff = interpretation.isLegacyUnresolved() ? null : interpretation.getCheckoutCutoff();

        String before = auditSnapshot.toJson(Map.of(
                "checkOutAt", "null", "workedMinutes", record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0));
        Attendance saved = closeSession(record, now, cutoff);
        String after = auditSnapshot.toJson(Map.of(
                "checkOutAt", now.toString(), "workedMinutes", saved.getWorkedMinutes(), "status", saved.getStatus()));
        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_OUT", saved.getId(), before, after);
        return toResponse(saved, employee);
    }

    /**
     * Closes an open attendance record as of {@code actualCheckOut} — the real click time,
     * ALWAYS recorded verbatim on both the record and its punch, never replaced by
     * {@code workedMinutesCapAt}. Recomputes the day's combined worked minutes (see
     * recomputeCombinedWorkedMinutes), bounding that aggregate — but not the stored
     * timestamp — at {@code workedMinutesCapAt} (typically the shift's own natural end; null
     * for uncapped), and sets the resulting status — exactly what an explicit {@link #checkOut}
     * does.
     */
    private Attendance closeSession(Attendance record, LocalDateTime actualCheckOut, LocalDateTime workedMinutesCapAt) {
        record.setCheckOutAt(actualCheckOut);

        // Close the punch FIRST — recomputeCombinedWorkedMinutes below reads this same punch back
        // out of the DB (via collectPunches), so it must already reflect this checkout.
        // findFirstBy...OrderByCheckInAtDesc, not a plain findBy: if more than one punch is ever
        // left open under this record (a data slip, or two near-simultaneous check-ins racing
        // past the open-session guard above), a plain findBy throws NonUniqueResultException and
        // crashes the checkout instead of just closing the most recently opened session.
        attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(record.getId())
                .ifPresent(punch -> {
                    punch.setCheckOutAt(actualCheckOut);
                    attendancePunchRepository.save(punch);
                });

        // Combined total across BOTH Check-In/Out and Web Clock-In/Out, overlap-safe (see
        // recomputeCombinedWorkedMinutes) — Normal and Web Clock sessions are independent and can
        // be open at the same time, so this can no longer be a simple "add this session's minutes
        // to the running total" (that would double-count any overlapping window).
        int workedMinutes = recomputeCombinedWorkedMinutes(record.getEmployeeUserId(), record.getId(), record.getWorkDate(), workedMinutesCapAt);
        record.setWorkedMinutes(workedMinutes);

        // A short day overrides LATE — the shortfall is the more significant fact for payroll.
        // Only finalized once the shift has actually reached its own natural end
        // (workedMinutesCapAt) — a checkout BEFORE that is a resumable break (checkIn's own
        // "resume" branch explicitly allows checking in again later the same shift/day), so
        // downgrading to HALF_DAY this early would judge the day before it's actually over.
        // Status stays whatever check-in already set (PRESENT/LATE) until then.
        // StaleAttendanceSweeper#finalizeStatusPastShiftEnd covers the case where the employee
        // never comes back to trigger this recompute themselves (a genuine early, one-off day).
        // A null cap (a legacy pre-Shift-snapshot row whose interpretation couldn't be safely
        // resolved — see AttendanceInterpretationService) is never finalized here either: there
        // is no reliable "has the shift ended" signal to finalize against, so status is left
        // exactly as check-in set it rather than guessed.
        //
        // Re-derived via interpretExistingRecordLateness (the same shift-grace-aware formula
        // check-in itself used, against THIS record's own snapshotted Shift/workDate) rather than
        // a bare `lateByMinutes > 0` — lateByMinutes is the raw, no-forgiveness display figure, so
        // using it directly here would silently flip an already-correctly-graced PRESENT arrival
        // (e.g. 5 minutes late against a 10-minute allowed-late privilege) to LATE the moment the
        // day's shift naturally ends, contradicting the grace decision check-in already made.
        // Never LEGACY_UNRESOLVED here: workedMinutesCapAt is only ever non-null when this exact
        // record's shiftId already resolved via the identical resolveShiftContextOrNull check
        // inside checkOut's own interpretExistingSession call just above in the call chain.
        if (workedMinutesCapAt != null && !actualCheckOut.isBefore(workedMinutesCapAt)) {
            boolean isLate = attendanceInterpretationService
                    .interpretExistingRecordLateness(record, record.getCheckInAt()).getIsLate();
            record.setStatus(workedMinutes < attendanceRulesService.getHalfDayMaxHours() * 60
                    ? STATUS_HALF_DAY
                    : (isLate ? STATUS_LATE : STATUS_PRESENT));
        }

        return attendanceRepository.save(record);
    }

    /**
     * The day's total worked minutes, combining BOTH Check-In/Out (AttendancePunch) and Web
     * Clock-In/Out (WebClockInRequest) sessions — see {@link #collectPunches}. Normal and Web
     * Clock sessions are tracked fully independently (deliberately: Web Clock-In must never block
     * on, or be blocked by, a Check-In/Out session, and vice versa — see WebClockInService), so
     * they CAN genuinely overlap in real time (e.g. checked in normally 9am-6pm, and also Web
     * Clock-In for an unrelated hour in between). Only CLOSED sessions are counted — an open
     * session contributes nothing until it closes (matches the pre-existing "live elapsed" UI
     * having been removed; workedMinutes is a settled total, not a ticking counter). Overlapping
     * or back-to-back intervals are merged before summing, so the overlapping window is counted
     * once, never twice, regardless of which source(s) cover it.
     */
    public int recomputeCombinedWorkedMinutes(UUID employeeId, UUID attendanceRecordId, LocalDate workDate) {
        return recomputeCombinedWorkedMinutes(employeeId, attendanceRecordId, workDate, null);
    }

    /**
     * Same as {@link #recomputeCombinedWorkedMinutes(UUID, UUID, LocalDate)}, but bounds each
     * closed interval's contribution to the total at {@code capAt} (e.g. the shift's own natural
     * end) when a session ran past it — protects the WORKED-MINUTES figure from a forgotten
     * checkout inflating hours (e.g. "27h 8m"), without ever touching the punches' own stored
     * checkInAt/checkOutAt: those remain the real, actual click times everywhere else (API
     * responses, punch history, audit log) — only this aggregate sum is clamped. {@code capAt}
     * null means uncapped, same as the 3-arg overload.
     */
    public int recomputeCombinedWorkedMinutes(UUID employeeId, UUID attendanceRecordId, LocalDate workDate, LocalDateTime capAt) {
        List<long[]> intervals = collectPunches(employeeId, attendanceRecordId, workDate).stream()
                .filter(p -> p.getCheckOutAt() != null)
                .map(p -> {
                    // Only clamp when capAt actually falls WITHIN this session (after its own
                    // checkInAt) — a session that started after capAt (e.g. a Web Clock-In opened
                    // late into the night, past the normal shift's own end) must never have its
                    // end clamped to a point before its start: that produced a negative-width
                    // interval here, which summed straight into a negative workedMinutes total
                    // (the reported "-48m" bug). Such a session simply isn't subject to this cap.
                    LocalDateTime end = (capAt != null && p.getCheckOutAt().isAfter(capAt) && capAt.isAfter(p.getCheckInAt()))
                            ? capAt
                            : p.getCheckOutAt();
                    return new long[]{toComparableMinute(p.getCheckInAt()), toComparableMinute(end)};
                })
                .sorted(Comparator.comparingLong(iv -> iv[0]))
                .toList();

        long totalMinutes = 0;
        long curStart = -1, curEnd = -1;
        for (long[] iv : intervals) {
            if (curStart == -1) {
                curStart = iv[0];
                curEnd = iv[1];
            } else if (iv[0] <= curEnd) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                totalMinutes += (curEnd - curStart);
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        if (curStart != -1) {
            totalMinutes += (curEnd - curStart);
        }
        return (int) totalMinutes;
    }

    // An arbitrary but internally-consistent monotonic long for interval-merge arithmetic only —
    // every LocalDateTime fed into it already shares the same resolved (naive) clock for this
    // employee/day, so this is never mixed with a genuine UTC-aware instant elsewhere.
    private long toComparableMinute(LocalDateTime dt) {
        return dt.toEpochSecond(java.time.ZoneOffset.UTC) / 60;
    }

    /**
     * The employee's currently-open NORMAL Check-In/Check-Out session, if any — deliberately
     * independent of any open Web Clock-In session (see WebClockInService, which tracks its own
     * open/closed state entirely separately via WebClockInRequest). Backs checkIn/checkOut/
     * getToday's canCheckIn/canCheckOut, which must reflect ONLY this, never flip because of a
     * Web Clock-In/Out action.
     */
    private Optional<Attendance> findOpenNormalAttendance(UUID employeeId) {
        List<AttendancePunch> open = attendancePunchRepository.findOpenByEmployeeUserId(employeeId);
        if (open.isEmpty()) return Optional.empty();
        return attendanceRepository.findById(open.get(0).getAttendanceRecordId());
    }

    /**
     * A session left open past its own logical workday (per {@link ShiftDayPolicy#shiftDayOf},
     * shift-relative to the employee's assigned shift — every employee is expected to have one,
     * see {@link Shift#DEFAULT_SHIFT_NAME}/{@code ShiftSeedCorrector}) — the employee forgot to
     * check out and never came back to click it — must not go
     * on blocking fresh check-ins ({@link #checkIn}) or showing as "still checked in" / offering a
     * Check Out button forever ({@link #getToday}), no matter how many calendar days have since
     * passed. Flags it {@link #STATUS_MISSING_CHECKOUT} right then — deliberately WITHOUT
     * fabricating a checkOutAt or computing workedMinutes; the real check-out time is unknown, so
     * none is guessed. (Employee/HR/Manager can still correct it via the existing Regularization
     * flow.) Returns whether the record is — now or already — flagged, so the caller can fall
     * through to treating this employee as having no open session.
     *
     * <p>This method OWNS the stale/missing-checkout decision entirely — {@link ShiftDayPolicy}
     * only supplies the day-attribution input; crossing that day boundary does not, by itself,
     * flag or close anything (see {@link ShiftDayPolicy}'s own Javadoc).
     *
     * <p>An open session still within its own logical workday (e.g. checked in at 11 PM, now
     * 2 AM — the 3:30 PM - 12:30 AM shift has ended but its logical-workday boundary hasn't) is
     * left untouched — this only fires once that boundary has genuinely passed, never for a
     * session legitimately still correctable (a late but real Check-Out click — see
     * {@link #checkOut}'s own {@link ShiftDayPolicy#shiftEndAt} cap) or still in progress across
     * midnight.
     *
     * <p>Resolved against THIS RECORD's own snapshotted Shift (via
     * {@link AttendanceInterpretationService#interpretExistingSession}), never the employee's
     * current one — so a reassignment while this session was open cannot change whether it's
     * judged stale. A legacy pre-snapshot record ({@code shiftId == null}) cannot be safely
     * evaluated at all — rather than substituting the employee's current Shift, this leaves it
     * untouched (not flagged) until it's resolved some other way (e.g. Regularization).
     *
     * <p>{@code record.getCheckOutAt() == null} alone is NOT the same thing as "this day is still
     * open" — {@link WebClockInService#checkOut} deliberately never writes this column (that field
     * is reserved exclusively for the normal Check-In/Check-Out session — see its own Javadoc), so
     * a day whose ONLY punches were Web Clock-In/Out has a permanently-null
     * {@code checkOutAt} even once fully, correctly completed. Without the
     * {@link #hasAnyOpenSession} guard below, the org-wide sweep ({@link
     * #flagAllStaleOpenSessionsAsMissingCheckout}, whose candidate query is exactly
     * {@code checkOutAt IS NULL}) would silently overwrite that already-correct PRESENT/LATE/
     * HALF_DAY status with MISSING_CHECKOUT on every such day, the very next sweep after its
     * workday boundary passed — this bug is why the guard exists. The per-employee lazy callers
     * ({@link #getToday}/{@link #checkIn}/{@link #checkOut}) are unaffected either way — they only
     * ever pass a record already known to have a genuinely open normal punch (via {@link
     * #findOpenNormalAttendance}), so this guard is a no-op (always true) for them.
     */
    private boolean flagMissingCheckoutIfStale(Attendance record, LocalDateTime now) {
        if (record.getCheckOutAt() != null) return false;
        if (!hasAnyOpenSession(record)) return false;
        if (STATUS_MISSING_CHECKOUT.equals(record.getStatus())) return true;
        AttendanceInterpretation interpretation = attendanceInterpretationService.interpretExistingSession(record, now);
        if (interpretation.isLegacyUnresolved()) {
            log.warn("flagMissingCheckoutIfStale: Attendance {} has no Shift snapshot — staleness cannot be "
                    + "safely determined, leaving it open rather than guessing", record.getId());
            return false;
        }
        if (!interpretation.getWorkDate().isAfter(record.getWorkDate())) return false;

        String before = auditSnapshot.toJson(Map.of("status", record.getStatus()));
        record.setStatus(STATUS_MISSING_CHECKOUT);
        // Flushed immediately (rather than left for the transaction's own commit-time flush) so a
        // concurrent conflict on THIS row surfaces right here — where flagAllStaleOpenSessionsAsMissingCheckout
        // catches it per-record — instead of at the end of a whole batch sweep, which would abort
        // every other record's already-applied update in the same transaction.
        Attendance saved = attendanceRepository.saveAndFlush(record);
        String after = auditSnapshot.toJson(Map.of("status", STATUS_MISSING_CHECKOUT));
        auditService.log(record.getEmployeeUserId(), "ATTENDANCE_MISSING_CHECKOUT", saved.getId(), before, after);
        return true;
    }

    /**
     * Is EITHER of this record's two independent session mechanisms — the normal Check-In/Out
     * session (an open {@link AttendancePunch}) or a Web Clock-In session (an open {@link
     * WebClockInRequest} for this employee/workDate) — still genuinely open? See {@link
     * #flagMissingCheckoutIfStale}'s own Javadoc for why {@code Attendance.checkOutAt == null}
     * alone cannot answer this question: Web Clock-Out never sets that column, even for a fully,
     * correctly completed day.
     */
    private boolean hasAnyOpenSession(Attendance record) {
        if (attendancePunchRepository.existsByAttendanceRecordIdAndCheckOutAtIsNull(record.getId())) {
            return true;
        }
        return webClockInRequestRepository.existsByEmployeeUserIdAndWorkDateAndCheckedOutAtIsNull(
                record.getEmployeeUserId(), record.getWorkDate());
    }

    /**
     * Org-wide sweep for {@link StaleAttendanceSweeper}: flags every currently-open attendance
     * record whose own workday/grace window has already ended as Missing Check-Out, regardless of
     * whether that employee ever opens the app again to trigger {@link #flagMissingCheckoutIfStale}
     * themselves via {@link #getToday}/{@link #checkIn}. Without this, an employee who forgets to
     * check out and simply doesn't come back (a resignation, an extended leave, a forgotten
     * account) would leave that session open — and visibly "still checked in" to HR —
     * indefinitely.
     */
    @Transactional
    public void flagAllStaleOpenSessionsAsMissingCheckout() {
        List<Attendance> open = attendanceRepository.findByCheckOutAtIsNull();
        int flagged = 0;
        for (Attendance record : open) {
            try {
                Optional<Employee> employee = employeeRepository.findById(record.getEmployeeUserId());
                if (employee.isPresent()
                        && flagMissingCheckoutIfStale(record, LocalDateTime.now(resolveZone(record, employee.get())))) {
                    flagged++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // This one record was concurrently modified (e.g. the employee checked out
                // themselves in the same moment the sweeper reached it) — skip it and keep
                // sweeping the rest of the batch rather than losing every other record's already-
                // applied update to one row's conflict.
                log.warn("flagAllStaleOpenSessionsAsMissingCheckout: skipped Attendance {} — concurrently modified by another request", record.getId());
            }
        }
        if (flagged > 0) {
            log.info("flagAllStaleOpenSessionsAsMissingCheckout: flagged {} of {} open session(s) as Missing Check-Out", flagged, open.size());
        }
    }

    /**
     * Finalizes HALF_DAY (or confirms PRESENT/LATE) for a closed day whose shift has now ended,
     * covering the one case closeSession/WebClockInService.checkOut can't handle themselves: an
     * employee who checks out well before their shift's own natural end and simply never comes
     * back that day (a genuine early finish, not a resumable break) — see closeSession's own
     * comment for why that checkout deliberately left status as PRESENT/LATE rather than judging
     * the day before it was actually over. Scoped to PRESENT/LATE records from the last few days
     * (see the repository query) — once a record settles into HALF_DAY here it's never touched
     * again, so this never re-processes already-finalized history.
     */
    @Transactional
    public void finalizeStatusPastShiftEnd() {
        List<Attendance> candidates = attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(
                List.of(STATUS_PRESENT, STATUS_LATE), LocalDate.now(attendanceRulesService.getDefaultZoneId()).minusDays(3));
        int finalized = 0;
        for (Attendance record : candidates) {
            UUID employeeId = record.getEmployeeUserId();
            // Still resumable — a currently-open normal session under this exact record, or a
            // currently-open Web session for this exact workDate — must not be judged yet.
            boolean normalSessionStillOpen = attendancePunchRepository.findOpenByEmployeeUserId(employeeId).stream()
                    .anyMatch(p -> p.getAttendanceRecordId().equals(record.getId()));
            boolean webSessionStillOpen = webClockInRequestRepository
                    .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId)
                    .map(req -> req.getWorkDate().equals(record.getWorkDate()))
                    .orElse(false);
            if (normalSessionStillOpen || webSessionStillOpen) {
                continue;
            }
            Employee employee = employeeRepository.findById(employeeId).orElse(null);
            if (employee == null) {
                continue;
            }
            // Resolved against THIS RECORD's own snapshotted Shift, never the employee's current
            // one — see AttendanceInterpretationService.interpretExistingSession.
            AttendanceInterpretation interpretation = attendanceInterpretationService.interpretExistingSession(
                    record, LocalDateTime.now(resolveZone(record, employee)));
            if (interpretation.isLegacyUnresolved()) {
                // No reliable "has the shift ended" signal for a legacy pre-snapshot record —
                // leave status exactly as it is rather than guessing; see closeSession's own
                // null-cutoff handling for the same principle.
                continue;
            }
            LocalDateTime shiftEnd = interpretation.getCheckoutCutoff();
            LocalDateTime nowInRecordZone = LocalDateTime.now(resolveZone(record, employee));
            if (nowInRecordZone.isBefore(shiftEnd)) {
                continue; // shift hasn't ended yet — still resumable, leave it for a later sweep
            }
            int workedMinutes = record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0;
            // Same shift-grace-aware recompute as closeSession, and for the same reason: a bare
            // `lateByMinutes > 0` would flip an already-correctly-graced PRESENT arrival (within
            // the shift's own allowed-late privilege) to LATE the moment this sweep finalizes the
            // day, contradicting the grace decision check-in already made. Never LEGACY_UNRESOLVED
            // here — already `continue`d above via the identical shiftId resolution.
            boolean isLate = attendanceInterpretationService
                    .interpretExistingRecordLateness(record, record.getCheckInAt()).getIsLate();
            String finalStatus = workedMinutes < attendanceRulesService.getHalfDayMaxHours() * 60
                    ? STATUS_HALF_DAY
                    : (isLate ? STATUS_LATE : STATUS_PRESENT);
            if (!finalStatus.equals(record.getStatus())) {
                record.setStatus(finalStatus);
                try {
                    attendanceRepository.saveAndFlush(record);
                    finalized++;
                } catch (ObjectOptimisticLockingFailureException e) {
                    // Same per-record isolation rationale as flagAllStaleOpenSessionsAsMissingCheckout.
                    log.warn("finalizeStatusPastShiftEnd: skipped Attendance {} — concurrently modified by another request", record.getId());
                }
            }
        }
        if (finalized > 0) {
            log.info("finalizeStatusPastShiftEnd: finalized {} of {} candidate record(s)", finalized, candidates.size());
        }
    }


    /**
     * Every check-in/check-out session for a single day — e.g. to show a lunch-break gap.
     * Includes both normal Check-In/Out and Web Check-In/Out sessions, merged chronologically —
     * see {@link #collectPunches}.
     */
    @Transactional(readOnly = true)
    public List<PunchResponse> getPunches(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        UUID attendanceRecordId = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(Attendance::getId)
                .orElse(null);
        return collectPunches(employee.getUserId(), attendanceRecordId, date);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMyHistory(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return historyFor(employee, from, to);
    }

    /**
     * The caller's own punch for a single date, if any — backs the regularization request
     * form's auto-fill (Attendance Regularization spec scenarios 1/2: prefill whichever side
     * of the punch already exists so only the missing one needs to be entered). Null if the
     * employee never punched that day.
     */
    @Transactional(readOnly = true)
    public AttendanceResponse getPunchForDate(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(record -> toResponse(record, employee))
                .orElse(null);
    }

    // ---------------------------------------------------------------- HR / Manager views

    /** Full day roster for HR — one row per active employee, punched or not. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForAll(LocalDate date) {
        // Org-wide/multi-employee default (no single employee to be shift-relative for, and
        // ShiftDayPolicy.shiftDayOf now requires a real assigned shift — it throws for a null
        // employee) — just today's plain calendar date in the business zone, same value this
        // produced before ShiftDayPolicy existed for every employee that had no shift assigned.
        LocalDate day = date != null ? date : now().toLocalDate();
        List<Employee> employees = employeeRepository.findAllWithDetails();
        return joinRoster(employees, attendanceRepository.findByWorkDate(day), day);
    }

    /** Day roster limited to the caller's current direct reports. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForMyTeam(String managerEmail, LocalDate date) {
        // Org-wide/multi-employee default (no single employee to be shift-relative for, and
        // ShiftDayPolicy.shiftDayOf now requires a real assigned shift — it throws for a null
        // employee) — just today's plain calendar date in the business zone, same value this
        // produced before ShiftDayPolicy existed for every employee that had no shift assigned.
        LocalDate day = date != null ? date : now().toLocalDate();
        Employee manager = resolveEmployee(managerEmail);

        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Employee> reports = employeeRepository.findAllById(reportIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, reportIds);
        return joinRoster(reports, records, day);
    }

    /**
     * Attendance rows for the caller's current direct reports across a date range — backs the
     * My Team calendar. Unlike {@link #getDayForMyTeam}, this returns only rows that actually
     * exist (no synthetic per-day placeholders for a whole team/range); the caller infers a
     * "missing attendance" day from the absence of a row on a working day.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForMyTeam(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
    }

    /**
     * Day roster limited to the caller's current peers (same-manager siblings) — backs the
     * employee-facing My Team: Peers view (ONEHR-73). Mirrors {@link #getDayForMyTeam} exactly,
     * swapping direct-report resolution for peer resolution.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForPeers(String employeeEmail, LocalDate date) {
        // Org-wide/multi-employee default (no single employee to be shift-relative for, and
        // ShiftDayPolicy.shiftDayOf now requires a real assigned shift — it throws for a null
        // employee) — just today's plain calendar date in the business zone, same value this
        // produced before ShiftDayPolicy existed for every employee that had no shift assigned.
        LocalDate day = date != null ? date : now().toLocalDate();
        Employee self = resolveEmployee(employeeEmail);

        // "Project Team" = every employee (including the caller) who currently reports to the
        // same manager — empty if the caller has no manager assigned, since there's no team to
        // belong to in that case.
        if (managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(self.getUserId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add self.getUserId() again here.
        List<UUID> teamIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());

        List<Employee> team = employeeRepository.findAllById(teamIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, teamIds);
        return joinRoster(team, records, day);
    }

    /** Project Team attendance across a date range — backs the Peers view calendar. Mirrors {@link #getMonthForMyTeam}. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForPeers(String employeeEmail, LocalDate from, LocalDate to) {
        Employee self = resolveEmployee(employeeEmail);
        if (managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(self.getUserId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add self.getUserId() again here.
        List<UUID> teamIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());

        Map<UUID, Employee> byId = employeeRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(teamIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
    }

    /**
     * Drill-down history for a single employee. HR and Super Admin may view anyone; a Manager
     * may only view their own current direct reports.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getEmployeeHistory(UUID employeeUserId, LocalDate from, LocalDate to,
                                                       String actorEmail, boolean restrictToDirectReports) {
        if (restrictToDirectReports) {
            Employee manager = resolveEmployee(actorEmail);
            if (!managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId())
                    .contains(employeeUserId)) {
                throw new AccessDeniedException("You can only view attendance for your direct reports");
            }
        }

        Employee employee = employeeRepository.findById(employeeUserId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        return historyFor(employee, from, to);
    }

    // ---------------------------------------------------------------- Team Effort / Negligence (ONEHR-106/107)

    /** Avg. Work Hours Leaderboard — ranked desc by avg hrs/day over the range (ONEHR-106). */
    @Transactional(readOnly = true)
    public List<TeamEffortEntry> getTeamEffort(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllByIdWithScheduleDetails(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        // Working-days-per-employee (joining date, weekly off, holidays, approved leave) —
        // NOT a flat weekday count: two direct reports can have different expected hours in the
        // same range (e.g. one joined mid-range, or is on a non-Sat/Sun weekly-off policy).
        Map<UUID, WorkingDaySchedule> schedules =
                workingDayService.computeExpectedWorkingDaysBulk(new ArrayList<>(byId.values()), from, to);
        // Per-employee assigned-shift duration, reduced day-by-day by any approved hourly/
        // quarter-day leave (same adjusted-expected-hours calculation the Penalization Policy
        // engine uses — see ExpectedWorkHoursService) — never a flat per-day constant, since two
        // reports can be on different shifts.
        var partialHourLeaveByEmployeeDate =
                expectedWorkHoursService.loadPartialHourLeaveByEmployeeDate(reportIds, from, to);

        return groupByEmployee(records).entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    WorkingDaySchedule schedule = schedules.get(e.getKey());
                    double expectedHours = expectedHoursFor(employee, schedule, partialHourLeaveByEmployeeDate);
                    return TeamEffortEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .expectedHours(expectedHours)
                            .activeDays(stat.activeDays)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamEffortEntry::getAvgHoursPerDay).reversed())
                .toList();
    }

    // No assigned shift on file — falls back to this flat estimate rather than silently reporting
    // 0 expected hours (matches this leaderboard's pre-shift-aware behavior for that edge case
    // only; every shift-assigned employee uses their real, leave-adjusted shift duration below).
    private static final int FALLBACK_HOURS_PER_WORKDAY_WHEN_NO_SHIFT = 8;

    /**
     * Sums each working date's adjusted expected minutes (assigned shift duration, reduced by any
     * approved hourly/quarter-day leave on that date) — the same calculation the Penalization
     * Policy engine uses (see {@link ExpectedWorkHoursService}), so this leaderboard's "expected
     * hours" is never a second, independently-derived figure.
     */
    private double expectedHoursFor(Employee employee, WorkingDaySchedule schedule,
                                     Map<String, com.nforce.onehr.entity.LeaveRequest> partialHourLeaveByEmployeeDate) {
        if (schedule == null) {
            return 0.0;
        }
        // shiftMinutes(employee, date) is null only when the employee has no assigned shift at
        // all (an overnight shift's own rollover keeps its minutes positive regardless of date —
        // see that method's own Javadoc) — so this coarse "does a real shift exist" gate doesn't
        // need a specific date; the per-date figure below (adjustedExpectedMinutes) is what
        // actually resolves each working date's own Shift Version.
        if (employee == null || employee.getShift() == null) {
            return schedule.getExpectedWorkingDays() * (double) FALLBACK_HOURS_PER_WORKDAY_WHEN_NO_SHIFT;
        }
        long totalMinutes = schedule.getWorkingDates().stream()
                .mapToLong(date -> {
                    Long minutes = expectedWorkHoursService.adjustedExpectedMinutes(employee, date,
                            partialHourLeaveByEmployeeDate.get(employee.getUserId() + "|" + date));
                    return minutes != null ? minutes : 0L;
                })
                .sum();
        return totalMinutes / 60.0;
    }

    /**
     * Team Punctuality / On-Time Leaderboard — "on time" is defined solely as
     * {@code attendance.status == PRESENT}; LATE, HALF_DAY, and no record at all (ABSENT) never
     * count. Ranked desc by percentage, ties left in whatever order the underlying stream
     * produces (no invented secondary tie-break). Direct reports with zero expected working
     * days in the range are excluded from the leaderboard entirely, not shown as 0%.
     */
    @Transactional(readOnly = true)
    public TeamPunctualityResponse getTeamPunctuality(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return TeamPunctualityResponse.builder()
                    .leaderboard(List.of()).daily(List.of())
                    .summary(PunctualitySummary.builder().averageEmployeesOnTime(0).minimumEmployeesOnTime(0).maximumEmployeesOnTime(0).build())
                    .build();
        }

        List<Employee> reports = employeeRepository.findAllByIdWithScheduleDetails(reportIds);
        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, WorkingDaySchedule> schedules = workingDayService.computeExpectedWorkingDaysBulk(reports, from, to);

        Map<UUID, Set<LocalDate>> onTimeDatesByEmployee = records.stream()
                .filter(r -> STATUS_PRESENT.equals(r.getStatus()))
                .collect(Collectors.groupingBy(Attendance::getEmployeeUserId,
                        Collectors.mapping(Attendance::getWorkDate, Collectors.toSet())));

        List<PunctualityLeaderboardEntry> leaderboard = new ArrayList<>();
        // Union of every direct report's working dates — a date only belongs on the daily chart
        // if it was a working day for at least one of them.
        Map<LocalDate, Integer> onTimeCountByDate = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            onTimeCountByDate.put(d, 0);
        }
        Set<LocalDate> anyWorkingDate = new HashSet<>();

        for (Employee employee : reports) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null || schedule.getExpectedWorkingDays() == 0) {
                continue;
            }
            Set<LocalDate> onTimeDates = onTimeDatesByEmployee.getOrDefault(employee.getUserId(), Set.of());
            int onTimeDays = 0;
            for (LocalDate workingDate : schedule.getWorkingDates()) {
                anyWorkingDate.add(workingDate);
                if (onTimeDates.contains(workingDate)) {
                    onTimeDays++;
                    onTimeCountByDate.merge(workingDate, 1, Integer::sum);
                }
            }
            leaderboard.add(PunctualityLeaderboardEntry.builder()
                    .employeeUserId(employee.getUserId())
                    .fullName(employee.getFullName())
                    .designationName(designationOf(employee))
                    .onTimeDays(onTimeDays)
                    .expectedWorkingDays(schedule.getExpectedWorkingDays())
                    .percentage(round1(onTimeDays * 100.0 / schedule.getExpectedWorkingDays()))
                    .build());
        }
        leaderboard.sort(Comparator.comparingDouble(PunctualityLeaderboardEntry::getPercentage).reversed());

        List<DailyPunctuality> daily = onTimeCountByDate.entrySet().stream()
                .filter(e -> anyWorkingDate.contains(e.getKey()))
                .map(e -> DailyPunctuality.builder().date(e.getKey()).employeesOnTime(e.getValue()).build())
                .toList();

        List<Integer> applicableCounts = daily.stream().map(DailyPunctuality::getEmployeesOnTime).toList();
        PunctualitySummary summary = applicableCounts.isEmpty()
                ? PunctualitySummary.builder().averageEmployeesOnTime(0).minimumEmployeesOnTime(0).maximumEmployeesOnTime(0).build()
                : PunctualitySummary.builder()
                        .averageEmployeesOnTime(round1(applicableCounts.stream().mapToInt(Integer::intValue).average().orElse(0)))
                        .minimumEmployeesOnTime(applicableCounts.stream().min(Integer::compareTo).orElse(0))
                        .maximumEmployeesOnTime(applicableCounts.stream().max(Integer::compareTo).orElse(0))
                        .build();

        return TeamPunctualityResponse.builder().leaderboard(leaderboard).daily(daily).summary(summary).build();
    }

    /** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks (ONEHR-107). */
    @Transactional(readOnly = true)
    public TeamNegligenceResponse getTeamNegligence(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return TeamNegligenceResponse.builder()
                    .lateArrivals(List.of()).dailyLateCounts(List.of())
                    .leastHoursWorked(List.of()).hoursHistogram(List.of())
                    .frequentBreaks(List.of()).build();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        Map<UUID, TeamStat> statsByEmployee = groupByEmployee(records);

        List<TeamNegligenceResponse.LateArrivalEntry> lateArrivals = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LateArrivalEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .lateDays(stat.lateDays)
                            .activeDays(stat.activeDays)
                            .latePct(round1(stat.lateDays * 100.0 / stat.activeDays))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LateArrivalEntry::getLatePct).reversed())
                .toList();

        Map<LocalDate, Long> lateByDate = records.stream()
                .filter(r -> STATUS_LATE.equals(r.getStatus()))
                .collect(Collectors.groupingBy(Attendance::getWorkDate, Collectors.counting()));
        List<TeamNegligenceResponse.DailyCount> dailyLateCounts = from.datesUntil(to.plusDays(1))
                .map(d -> TeamNegligenceResponse.DailyCount.builder().date(d).count(lateByDate.getOrDefault(d, 0L)).build())
                .toList();

        List<TeamNegligenceResponse.LeastHoursEntry> leastHoursWorked = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LeastHoursEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LeastHoursEntry::getAvgHoursPerDay))
                .toList();

        FrequentBreaksResult breaks = computeFrequentBreaks(records, byId, from, to);

        return TeamNegligenceResponse.builder()
                .lateArrivals(lateArrivals)
                .dailyLateCounts(dailyLateCounts)
                .leastHoursWorked(leastHoursWorked)
                .hoursHistogram(buildHoursHistogram(leastHoursWorked))
                .frequentBreaks(breaks.entries)
                .breaksTrend(breaks.trend)
                .build();
    }

    /** Per-employee accumulator for the range — active/late days and total worked minutes. */
    private static final class TeamStat {
        int activeDays;
        int lateDays;
        int totalWorkedMinutes;
    }

    private Map<UUID, TeamStat> groupByEmployee(List<Attendance> records) {
        Map<UUID, TeamStat> stats = new HashMap<>();
        for (Attendance r : records) {
            if (r.getCheckInAt() == null) {
                continue; // no punch that day — not an "active" day for averaging purposes
            }
            TeamStat stat = stats.computeIfAbsent(r.getEmployeeUserId(), k -> new TeamStat());
            stat.activeDays++;
            if (STATUS_LATE.equals(r.getStatus())) {
                stat.lateDays++;
            }
            stat.totalWorkedMinutes += r.getWorkedMinutes() != null ? r.getWorkedMinutes() : 0;
        }
        return stats;
    }

    private List<TeamNegligenceResponse.HoursBucket> buildHoursHistogram(
            List<TeamNegligenceResponse.LeastHoursEntry> entries) {
        String[] labels = {"< 4 hours", "4 - 5 hours", "5 - 6 hours", "6 - 7 hours", "7 - 8 hours", ">= 8 hours"};
        int[] counts = new int[labels.length];
        for (TeamNegligenceResponse.LeastHoursEntry e : entries) {
            double h = e.getAvgHoursPerDay();
            int idx = h < 4 ? 0 : h < 5 ? 1 : h < 6 ? 2 : h < 7 ? 3 : h < 8 ? 4 : 5;
            counts[idx]++;
        }
        int total = entries.size();
        List<TeamNegligenceResponse.HoursBucket> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            double pct = total == 0 ? 0 : counts[i] * 100.0 / total;
            buckets.add(TeamNegligenceResponse.HoursBucket.builder()
                    .label(labels[i]).count(counts[i]).pct(round1(pct)).build());
        }
        return buckets;
    }

    /** Per-employee accumulator for break count/minutes while walking punch sessions. */
    private static final class BreakStat {
        int count;
        long minutes;
    }

    /** Per-day accumulator for the trend line: total breaks and how many employees punched that day. */
    private static final class DayBreakTotals {
        int totalBreaks;
        int employeesWithSessions;
    }

    private static final class FrequentBreaksResult {
        final List<TeamNegligenceResponse.FrequentBreaksEntry> entries;
        final List<TeamNegligenceResponse.DailyAverage> trend;

        FrequentBreaksResult(List<TeamNegligenceResponse.FrequentBreaksEntry> entries,
                              List<TeamNegligenceResponse.DailyAverage> trend) {
            this.entries = entries;
            this.trend = trend;
        }
    }

    private FrequentBreaksResult computeFrequentBreaks(
            List<Attendance> records, Map<UUID, Employee> byId, LocalDate from, LocalDate to) {
        if (records.isEmpty()) {
            return new FrequentBreaksResult(List.of(), List.of());
        }
        Map<UUID, Attendance> recordById = records.stream()
                .collect(Collectors.toMap(Attendance::getId, Function.identity()));
        List<AttendancePunch> punches =
                attendancePunchRepository.findByAttendanceRecordIdInOrderByCheckInAtAsc(recordById.keySet());
        Map<UUID, List<AttendancePunch>> byRecord = punches.stream()
                .collect(Collectors.groupingBy(AttendancePunch::getAttendanceRecordId));

        Map<UUID, BreakStat> statByEmployee = new HashMap<>();
        Map<UUID, Integer> activeDaysWithPunches = new HashMap<>();
        Map<LocalDate, DayBreakTotals> dailyTotals = new HashMap<>();

        for (Map.Entry<UUID, List<AttendancePunch>> entry : byRecord.entrySet()) {
            List<AttendancePunch> sessions = entry.getValue(); // already ordered by checkInAt asc
            Attendance record = recordById.get(entry.getKey());
            if (sessions.isEmpty() || record == null) {
                continue;
            }
            UUID employeeUserId = record.getEmployeeUserId();
            activeDaysWithPunches.merge(employeeUserId, 1, Integer::sum);
            BreakStat stat = statByEmployee.computeIfAbsent(employeeUserId, k -> new BreakStat());
            DayBreakTotals dayTotals = dailyTotals.computeIfAbsent(record.getWorkDate(), k -> new DayBreakTotals());
            dayTotals.employeesWithSessions++;

            // sessions.size() - 1 gaps = the day's breaks; the first session isn't a break.
            for (int i = 0; i < sessions.size() - 1; i++) {
                AttendancePunch current = sessions.get(i);
                AttendancePunch next = sessions.get(i + 1);
                stat.count++;
                dayTotals.totalBreaks++;
                if (current.getCheckOutAt() != null) {
                    stat.minutes += Duration.between(current.getCheckOutAt(), next.getCheckInAt()).toMinutes();
                }
            }
        }

        List<TeamNegligenceResponse.FrequentBreaksEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, BreakStat> entry : statByEmployee.entrySet()) {
            if (entry.getValue().count == 0) {
                continue; // no breaks in range — excluded entirely, not shown as 0 (AC #4)
            }
            Employee employee = byId.get(entry.getKey());
            int activeDays = activeDaysWithPunches.getOrDefault(entry.getKey(), 1);
            entries.add(TeamNegligenceResponse.FrequentBreaksEntry.builder()
                    .employeeUserId(entry.getKey())
                    .fullName(employee != null ? employee.getFullName() : null)
                    .designationName(designationOf(employee))
                    .totalBreakHours(round1(entry.getValue().minutes / 60.0))
                    .totalBreakCount(entry.getValue().count)
                    .avgBreaksPerDay(round1((double) entry.getValue().count / activeDays))
                    .build());
        }
        entries.sort(Comparator.comparingDouble(TeamNegligenceResponse.FrequentBreaksEntry::getTotalBreakHours).reversed());

        List<TeamNegligenceResponse.DailyAverage> trend = from.datesUntil(to.plusDays(1))
                .map(d -> {
                    DayBreakTotals t = dailyTotals.get(d);
                    double avg = (t == null || t.employeesWithSessions == 0)
                            ? 0 : (double) t.totalBreaks / t.employeesWithSessions;
                    return TeamNegligenceResponse.DailyAverage.builder().date(d).avgBreaks(round1(avg)).build();
                })
                .toList();

        return new FrequentBreaksResult(entries, trend);
    }

    private String designationOf(Employee employee) {
        return employee != null && employee.getDesignation() != null ? employee.getDesignation().getTitle() : null;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Clock for HR/Manager roster views (getDayForAll/getDayForMyTeam/getDayForPeers) that span
     * many employees at once — those stay on the single global business zone deliberately: with
     * employees potentially in different timezones there's no single unambiguous "whose clock"
     * answer for an aggregate view, so this is intentionally NOT per-employee. Reads the
     * configured business zone rather than the JVM default so "today" is identical in local dev
     * and on Railway (which runs UTC).
     */
    private LocalDateTime now() {
        return LocalDateTime.now(attendanceRulesService.getDefaultZoneId());
    }

    /**
     * Clock for a specific employee's own self-service actions and history — check-in/out,
     * "today" status, worked-hours/late-arrival math, and shift-day attribution are all computed
     * in THIS employee's own configured timezone, not the single global business zone. See
     * {@link AttendanceRulesService#resolveEmployeeZoneId} for the resolution chain.
     */
    private LocalDateTime now(Employee employee) {
        return LocalDateTime.now(attendanceRulesService.resolveEmployeeZoneId(employee));
    }

    /**
     * Parses an IANA zone id (e.g. from the browser's {@code Intl.DateTimeFormat()
     * .resolvedOptions().timeZone}), or null if it's missing/blank/not a real zone — callers fall
     * back to {@link AttendanceRulesService#resolveEmployeeZoneId} rather than fail the request
     * over a malformed value.
     */
    private ZoneId parseZone(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        try {
            return ZoneId.of(candidate.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Zone for a fresh Check-In/Web Clock-In click: ALWAYS resolved server-side via
     * {@link AttendanceRulesService#resolveEmployeeZoneId} (the employee's Location, then the
     * org-wide default). {@code clientTimezone} (the browser-reported zone, still sent by the
     * frontend on every punch) is deliberately never consulted here: per explicit requirement,
     * the employee's own configured timezone is the ONLY authoritative source for their
     * attendance clock — a viewer's own browser/location, or the server/host's own timezone,
     * must never be able to shift it. This is the value that gets locked into
     * {@link Attendance#getTimezone()} for the rest of that session's lifetime.
     */
    private ZoneId resolveZone(String clientTimezone, Employee employee) {
        return attendanceRulesService.resolveEmployeeZoneId(employee);
    }

    /**
     * Zone for an EXISTING session — its own {@link Attendance#getTimezone()}, locked in at
     * Check-In (itself already resolved via {@link AttendanceRulesService#resolveEmployeeZoneId})
     * governs Check-Out/grace-window/worked-minutes math for as long as it's open. Falls back to
     * {@link AttendanceRulesService#resolveEmployeeZoneId} only for a record predating this
     * column; {@code clientTimezoneFallback} is likewise never consulted, for the same reason as
     * above.
     */
    private ZoneId resolveZone(Attendance record, Employee employee, String clientTimezoneFallback) {
        ZoneId stored = parseZone(record.getTimezone());
        return stored != null ? stored : attendanceRulesService.resolveEmployeeZoneId(employee);
    }

    private ZoneId resolveZone(Attendance record, Employee employee) {
        return resolveZone(record, employee, null);
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }

    /**
     * Gate for self-service punch actions ONLY (Check-In/Check-Out here; Web Clock-In/Out has
     * its own mirror in WebClockInService) — a deactivated or deleted account must not be able to
     * punch itself, even though the same Employee row remains fully readable elsewhere (rosters,
     * stats, and HR/Regularization's own historical corrections, which never call this). Not
     * folded into resolveEmployee itself, which backs many read-only call sites that must keep
     * working for an inactive employee (e.g. viewing their own history).
     */
    private void assertEligibleToPunch(Employee employee) {
        User user = employee.getUser();
        if (user == null || !user.isActive() || user.getDeletedAt() != null) {
            throw new IllegalArgumentException(
                    "Your account is inactive. Contact HR if you believe this is an error.");
        }
    }

    private List<AttendanceResponse> historyFor(Employee employee, LocalDate from, LocalDate to) {
        // Scoped to one specific employee (never an aggregate/roster view), so their own
        // timezone unambiguously answers "what does 'today' mean" for defaulting the range end.
        LocalDate end = to != null ? to : shiftDayPolicy.shiftDayOf(employee.getUserId(), now(employee));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        return attendanceRepository
                .findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
                        employee.getUserId(), start, end)
                .stream()
                .map(record -> toResponse(record, employee))
                .toList();
    }

    /** Left-joins a day's records onto an employee list so non-punchers still appear as a row. */
    private List<AttendanceResponse> joinRoster(List<Employee> employees, List<Attendance> records,
                                                LocalDate day) {
        Map<UUID, Attendance> byEmployee = records.stream()
                .collect(Collectors.toMap(Attendance::getEmployeeUserId, Function.identity()));

        List<AttendanceResponse> rows = new ArrayList<>(employees.size());
        for (Employee employee : employees) {
            Attendance record = byEmployee.get(employee.getUserId());
            rows.add(record != null
                    ? toResponse(record, employee)
                    : AttendanceResponse.builder()
                            .employeeUserId(employee.getUserId())
                            .employeeCode(employee.getEmployeeCode())
                            .fullName(employee.getFullName())
                            .workDate(day)
                            .workMode(employee.getWorkMode())
                            .build());
        }
        rows.sort(Comparator.comparing(AttendanceResponse::getFullName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return rows;
    }

    private AttendanceResponse toResponse(Attendance record, Employee employee) {
        Integer worked = record.getWorkedMinutes();
        // Resolved against THIS ROW's own snapshotted shiftId (never employee's current Shift) —
        // see AttendanceInterpretationService#resolveScheduledWindow's own Javadoc. Powers the
        // Attendance Log's shift-boundary markers only.
        AttendanceInterpretationService.ScheduledShiftWindow scheduledWindow =
                attendanceInterpretationService.resolveScheduledWindow(record);
        return AttendanceResponse.builder()
                .id(record.getId())
                .employeeUserId(record.getEmployeeUserId())
                .employeeCode(employee.getEmployeeCode())
                .fullName(employee.getFullName())
                .workDate(record.getWorkDate())
                .checkInAt(record.getCheckInAt())
                .checkOutAt(record.getCheckOutAt())
                .sessionStartedAt(record.getSessionStartedAt())
                .workedMinutes(worked)
                .status(record.getStatus())
                .lateByMinutes(record.getLateByMinutes())
                .source(record.getSource())
                .workMode(employee.getWorkMode())
                .timezone(record.getTimezone())
                .shiftStartAt(scheduledWindow.start())
                .shiftEndAt(scheduledWindow.end())
                .workdayStartAt(scheduledWindow.workdayStart())
                .workdayEndAt(scheduledWindow.workdayEnd())
                .build();
    }
}
