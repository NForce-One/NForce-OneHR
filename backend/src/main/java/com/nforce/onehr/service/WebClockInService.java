package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceContext;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Web Clock-In / Check-in: any employee working remotely can self-declare a check-in — it
 * upserts the day's {@link Attendance} row (source WEB_REMOTE) and starts counting worked time
 * immediately, the same instant it's submitted, with NO wait for HR review. That immediate
 * attendance effect is deliberately decoupled from the request's own approval lifecycle: the
 * FIRST request of a given employee's shift/workday starts {@code PENDING} and is routed to the
 * employee's manager (or HR/Super Admin) for a real approve/reject decision via
 * {@link #approve}/{@link #reject} — it does not self-approve. Every later Web Clock-In cycle
 * within the SAME shift/workday mirrors that first request's current outcome on submit (see
 * {@code submit}'s own comment) — whether it's still awaiting review (PENDING) or already
 * decided (APPROVED) — so it neither re-asks HR nor surfaces as a second, independently-
 * actionable pending request; the shift only ever gets one real review, and approve()/reject()
 * cascades that one decision to every sibling cycle at once (see their own comments). Check-out
 * and cancel need no approval either, and neither is gated by review status at all (PENDING,
 * APPROVED, or REJECTED) — whether HR has looked at the request yet is independent of the
 * employee actually finishing (or undoing) their own real session.
 *
 * <p>Web Clock-In/Out is deliberately independent of normal Check-In/Check-Out: each is tracked
 * via its own open/closed state (this class's own WebClockInRequest.checkedOutAt vs.
 * AttendanceService's AttendancePunch.checkOutAt) and neither blocks on, or is blocked by, the
 * other — an employee can be normally checked in AND have an open Web Clock-In session at the
 * same time. Submitting or checking out a Web Clock-In session must never flip the normal
 * Check-In/Check-Out status (canCheckIn/canCheckOut) shown on the dashboard — see
 * AttendanceService.getToday, which derives that purely from AttendancePunch. The two sources'
 * worked time is still combined into one total without double-counting any overlap — see
 * AttendanceService.recomputeCombinedWorkedMinutes, called from both this class's checkOut and
 * AttendanceService's own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebClockInService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    // Mirrors AttendanceService.STATUS_MISSING_CHECKOUT — see checkOut's own doc comment.
    private static final String STATUS_MISSING_CHECKOUT = "MISSING_CHECKOUT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String SOURCE_WEB_REMOTE = "WEB_REMOTE";

    private final WebClockInRequestRepository webClockInRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchRepository attendancePunchRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    // Shared with AttendanceService so the every-3rd-late-arrival penalty applies identically
    // regardless of check-in entry point — see LatePenaltyService.
    private final LatePenaltyService latePenaltyService;
    private final NotificationService notificationService;
    // Only for recomputeCombinedWorkedMinutes — the single shared source of truth for merging
    // Check-In/Out + Web Clock-In/Out into one overlap-safe total. No other coupling: this class
    // never reads AttendanceService's own open/closed (canCheckIn/canCheckOut) state.
    private final AttendanceService attendanceService;
    // Persisted, Admin-editable HALF_DAY threshold (Workstream B) — see its own Javadoc.
    private final AttendanceRulesService attendanceRulesService;
    // The single, narrow, shared owner of Shift-relative punch interpretation — see its own
    // class Javadoc. Replaces this class's own former private recomputeDerivedFields.
    private final AttendanceInterpretationService attendanceInterpretationService;

    @Transactional
    public WebClockInResponse submit(CreateWebClockInRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        // Fetched once and reused for every shift-relative computation below (both the stale-
        // session check and this fresh submission's own day resolution). Every employee is now
        // expected to have a real Employee profile AND an assigned Shift (see
        // Shift.DEFAULT_SHIFT_NAME's server-side default + ShiftSeedCorrector's backfill) —
        // requireEmployee fails loudly and clearly if the profile itself is missing, so a genuine
        // "no Employee row" case is never confused with ShiftDayPolicy's own (separate) loud
        // failure for "no assigned shift" further down.
        Employee employee = requireEmployee(actor.getId());
        assertEligibleToPunch(actor);

        // Only an already-open WEB session blocks a fresh Web Clock-In — deliberately independent
        // of whether a normal Check-In is currently open (see this class's own Javadoc). This is
        // NOT a once-per-day restriction — an employee may Web Clock-In and Web Clock-Out more
        // than once in the same day (see collectPunches), this only blocks trying to open a
        // *second, concurrent* Web session while one is already running. A Web session left open
        // past its own workday/grace window (a forgotten Web Clock-Out from days ago) is stale,
        // not really "still checked in" — auto-close it at its own natural shift end (mirrors
        // AttendanceService.checkIn's Missing-Check-Out staleness bypass, adapted here as an
        // auto-close since WebClockInRequest's own `status` field is a review status, not an
        // attendance-completion one) instead of letting it block Web Clock-In forever.
        Optional<WebClockInRequest> openWebSession = webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId());
        if (openWebSession.isPresent()) {
            WebClockInRequest openReq = openWebSession.get();
            Attendance openReqAttendance = attendanceRepository
                    .findByEmployeeUserIdAndWorkDate(actor.getId(), openReq.getWorkDate()).orElse(null);
            LocalDateTime openNow = LocalDateTime.now(resolveZone(openReqAttendance, actor.getId(), req.getTimezone()));
            // Resolved against the backing Attendance row's own snapshotted Shift (never the
            // employee's current one) — see AttendanceInterpretationService.interpretExistingSession.
            // No backing row at all, or one predating the Shift snapshot, means staleness can't be
            // safely determined — treated the same conservative way as "not stale": block with the
            // ordinary duplicate message rather than guessing an auto-close.
            AttendanceInterpretation openInterpretation = openReqAttendance != null
                    ? attendanceInterpretationService.interpretExistingSession(openReqAttendance, openNow)
                    : AttendanceInterpretation.legacyUnresolved();
            boolean stale = !openInterpretation.isLegacyUnresolved()
                    && openInterpretation.getWorkDate().isAfter(openReq.getWorkDate());
            if (stale) {
                autoCloseStaleWebSession(openReq, openReqAttendance, openInterpretation);
            } else {
                throw new IllegalArgumentException("You have already checked in today");
            }
        }

        // Resolved from the employee's own configured Location.timezone — mirrors
        // AttendanceService.checkIn's resolveZone (see its own doc comment: req.getTimezone(),
        // the browser-reported zone, is never consulted). Locked into the Attendance row this
        // creates/resumes (see applyCheckInToAttendance) for the rest of that session's lifetime.
        ZoneId zone = resolveZone(req.getTimezone(), actor.getId());
        LocalDateTime now = LocalDateTime.now(zone);
        // No existing Attendance is known yet at this point — a fresh-action question, resolved
        // against the employee's CURRENT Shift (see
        // AttendanceInterpretationService.interpretFreshAction). Reused below by
        // applyCheckInToAttendance so lateness/shiftId snapshot are computed from this exact same
        // interpretation, not re-derived a second time.
        AttendanceInterpretation interpretation = attendanceInterpretationService.interpretFreshAction(
                employee, new AttendanceContext(actor.getId(), now, zone));
        LocalDate today = interpretation.getWorkDate();

        // Once THIS employee's FIRST Web Clock-In of the shift/workday has a request in flight
        // (PENDING) or already decided (APPROVED), every later Web Clock-In cycle the same
        // shift/workday mirrors that same request's outcome and never re-notifies HR — only the
        // very first request of a shift needs a real HR decision. Keyed on employee + workDate
        // (the shift's own resolved workday, same key findOpenByEmployeeUserId/collectPunches
        // already use for "same shift"), not on the button click itself, so this survives any
        // number of Web Clock-In/Out cycles within the one shift/workday. A REJECTED first
        // request does NOT count here — per the existing "Resubmit" flow (see
        // AttendanceHeroBanner's WebClockInRow `legacy` handling), a rejected shift needs a
        // genuinely fresh reason + review, not a silent auto-carry of a decision HR already
        // turned down.
        Optional<WebClockInRequest> firstReviewableThisShift = webClockInRepository
                .findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(actor.getId(), today).stream()
                .filter(r -> STATUS_PENDING.equals(r.getStatus()) || STATUS_APPROVED.equals(r.getStatus()))
                .findFirst();

        UUID approverId = resolveAssignedApprover(actor.getId());
        WebClockInRequest entity = WebClockInRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(approverId)
                .workDate(today)
                .requestedCheckIn(now)
                .reason(req.getReason().trim())
                .status(firstReviewableThisShift.map(WebClockInRequest::getStatus).orElse(STATUS_PENDING))
                .build();
        // Mirrors the shift's first request exactly, whichever state it's in — this cycle needs
        // no HR notification/decision of its own. If that first request is itself still PENDING,
        // this row stays PENDING too but silently (no notification below), and approve()/reject()
        // cascades its eventual decision to every sibling row for this employee+workDate — see
        // those methods' own comments — so it never sits as an orphaned, separately-actionable
        // duplicate in the approver's queue.
        if (firstReviewableThisShift.isPresent() && STATUS_APPROVED.equals(firstReviewableThisShift.get().getStatus())) {
            entity.setReviewedBy(firstReviewableThisShift.get().getReviewedBy());
            entity.setReviewedAt(now);
            entity.setReviewComment("Auto-approved — a prior Web Clock-In this shift was already approved.");
        }
        entity = webClockInRepository.save(entity);

        // The attendance effect is immediate and real-time — the employee is checked in and
        // worked time starts accruing the moment they submit, regardless of how long HR takes to
        // review. See this class's own Javadoc for why these two things are deliberately
        // decoupled.
        applyCheckInToAttendance(entity, zone.getId(), employee, interpretation);

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CHECKED_IN", entity.getId());

        if (approverId != null && firstReviewableThisShift.isEmpty()) {
            notificationService.send(approverId, "WEB_CLOCK_IN_SUBMITTED",
                    "Web Clock-In Request Submitted",
                    employeeName(actor.getId()) + " has submitted a web clock-in request for " + today + ".",
                    "/approvals?type=WEB_CLOCK_IN");
        }
        return toResponse(entity);
    }

    /**
     * Undoes today's still-open check-in (before check-out) — deletes both the request and the
     * Attendance row it created, as if the check-in never happened. Only ever touches a row this
     * same flow created (source WEB_REMOTE, not yet checked out); if the employee had already
     * checked out, there's nothing left to cancel (checkOut clears the lookup this relies on).
     */
    @Transactional
    public void cancel(String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId())
                .orElseThrow(() -> new IllegalArgumentException("No active check-in to cancel"));

        // Only delete the day's shared Attendance row if THIS submission was the one that
        // created it fresh (source WEB_REMOTE, checkInAt matches this exact request) AND nothing
        // else has touched it since — no normal punches at all (open or closed), meaning nothing
        // else depends on it. Attendance/Web Clock-In are independent now (see this class's own
        // Javadoc), so a normal Check-In may have opened its own session on this same row after
        // this web submission; deleting the row out from under an unrelated open/closed normal
        // punch would corrupt that punch's own attendanceRecordId reference.
        attendanceRepository.findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .filter(a -> SOURCE_WEB_REMOTE.equals(a.getSource())
                        && req.getRequestedCheckIn().equals(a.getCheckInAt())
                        && attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(a.getId()).isEmpty())
                .ifPresent(attendanceRepository::delete);

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CANCELLED", req.getId());
        webClockInRepository.delete(req);
    }

    @Transactional(readOnly = true)
    public List<WebClockInResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return toResponses(webClockInRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId()));
    }

    /**
     * Manager sees only requests assigned to them; HR/Super Admin see all pending requests.
     * Deduplicated to at most one row per employee+workDate (the shift's earliest-submitted
     * PENDING cycle) — later Web Clock-In cycles within the same shift mirror that first
     * request's PENDING status (see submit()'s own comment) purely so checkOut/punch-history
     * still has its own row per cycle; they must never surface as separate actionable items for
     * the approver to review one-by-one.
     */
    @Transactional(readOnly = true)
    public List<WebClockInResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<WebClockInRequest> pending = webClockInRepository.findByStatus("PENDING");

        if (!hasOverrideRole(actor)) {
            pending = pending.stream().filter(r -> actor.getId().equals(r.getAssignedApproverId())).toList();
        }
        List<WebClockInRequest> deduped = pending.stream()
                .collect(Collectors.groupingBy(r -> Map.entry(r.getEmployeeUserId(), r.getWorkDate())))
                .values().stream()
                .map(rows -> rows.stream().min(Comparator.comparing(WebClockInRequest::getRequestedCheckIn)).orElseThrow())
                .toList();
        return toResponses(deduped);
    }

    /**
     * HR/manager approval of a Web Clock-In request. The attendance effect (the Attendance row,
     * worked minutes, punch history) was ALREADY applied the moment the employee submitted — see
     * {@link #submit}'s own doc comment — so this only ever updates the request's own review
     * status; it must NOT re-touch the Attendance row. Re-applying it here would silently reopen
     * a session the employee may have already checked out of (or resumed since), reintroducing
     * exactly the double-counting bug fixed in checkOut's own "already closed elsewhere" guard.
     *
     * <p>Cascades to every OTHER still-PENDING request for this same employee+workDate — later
     * Web Clock-In cycles within the same shift mirror the first request's PENDING status (see
     * submit()'s own comment) purely so checkOut/punch-history still has its own row per cycle;
     * approving the shift's one real decision must resolve all of them at once, not leave later
     * cycles stuck PENDING forever just because HR happened to approve a different row's id.
     */
    @Transactional
    public WebClockInResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("APPROVED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        webClockInRepository.save(req);
        approveSiblingCycles(req, actor.getId(), comment);

        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "WEB_CLOCK_IN_APPROVED", req.getEmployeeUserId(), before, after);

        notificationService.send(req.getEmployeeUserId(), "WEB_CLOCK_IN_APPROVED",
                "Web Clock-In Approved",
                "Your web clock-in for " + req.getWorkDate() + " has been approved by " + employeeName(actor.getId()) + ".",
                "/my-requests?type=WEB_CLOCK_IN");
        return toResponse(req);
    }

    /** Same cascade rationale as approve() — see its own doc comment. */
    @Transactional
    public WebClockInResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("REJECTED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        webClockInRepository.save(req);
        rejectSiblingCycles(req, actor.getId(), comment);

        String after = auditSnapshot.toJson(Map.of("status", "REJECTED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "WEB_CLOCK_IN_REJECTED", req.getEmployeeUserId(), before, after);

        notificationService.send(req.getEmployeeUserId(), "WEB_CLOCK_IN_REJECTED",
                "Web Clock-In Rejected",
                "Your web clock-in for " + req.getWorkDate() + " has been rejected by " + employeeName(actor.getId())
                        + (comment != null && !comment.isBlank() ? ". Reason: " + comment.trim() : "."),
                "/my-requests?type=WEB_CLOCK_IN");
        return toResponse(req);
    }

    /**
     * Every OTHER PENDING cycle for this same employee+workDate, approved alongside the one HR
     * actually reviewed — see approve()'s own doc comment. Silently skipped if none exist (the
     * normal case, going forward, since only the shift's first cycle is ever PENDING under the
     * new submit() logic); only reachable for pre-existing duplicate rows or a rare race between
     * two near-simultaneous submits.
     */
    private void approveSiblingCycles(WebClockInRequest approved, UUID reviewerId, String comment) {
        webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(
                        approved.getEmployeeUserId(), approved.getWorkDate())
                .stream()
                .filter(r -> !r.getId().equals(approved.getId()) && STATUS_PENDING.equals(r.getStatus()))
                .forEach(sibling -> {
                    sibling.setStatus(STATUS_APPROVED);
                    sibling.setReviewedBy(reviewerId);
                    sibling.setReviewedAt(LocalDateTime.now());
                    sibling.setReviewComment(comment);
                    webClockInRepository.save(sibling);
                });
    }

    /** Every OTHER PENDING cycle for this same employee+workDate, rejected alongside this one. */
    private void rejectSiblingCycles(WebClockInRequest rejected, UUID reviewerId, String comment) {
        webClockInRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(
                        rejected.getEmployeeUserId(), rejected.getWorkDate())
                .stream()
                .filter(r -> !r.getId().equals(rejected.getId()) && STATUS_PENDING.equals(r.getStatus()))
                .forEach(sibling -> {
                    sibling.setStatus("REJECTED");
                    sibling.setReviewedBy(reviewerId);
                    sibling.setReviewedAt(LocalDateTime.now());
                    sibling.setReviewComment(comment);
                    webClockInRepository.save(sibling);
                });
    }

    /**
     * No approval needed to check out — the employee closes out their own web clock-in day
     * regardless of whether HR has reviewed it yet. Deliberately independent of the normal
     * Check-In/Check-Out session (see this class's own Javadoc): never reads or writes
     * Attendance.checkOutAt (that field belongs exclusively to the normal session now — see
     * AttendanceService.findOpenNormalAttendance) and never blocks on, or is blocked by, whatever
     * the normal side's own state happens to be.
     */
    @Transactional
    public WebClockInResponse checkOut(String actorEmail, String clientTimezone) {
        User actor = requireActor(actorEmail);
        // Validates the Employee profile exists (a distinct, clearer failure than
        // ShiftDayPolicy's own "no assigned Shift" one — see requireEmployee's own Javadoc). The
        // profile itself isn't otherwise needed here: interpretExistingSession below resolves
        // Shift context from the Attendance record's own snapshot, never the employee's current
        // Shift.
        requireEmployee(actor.getId());
        assertEligibleToPunch(actor);

        // Looked up by "not yet checked out" regardless of review status, not by today's
        // work_date — a web clock-in from before midnight (shift crosses into the next day) can
        // still be open under yesterday's work_date once the calendar date rolls over. This is
        // this Web session's OWN open/closed signal — entirely independent of whether a normal
        // Check-In session happens to also be open right now.
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId())
                .orElseThrow(() -> new IllegalArgumentException("No web clock-in found for today"));

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .orElseThrow(() -> new IllegalStateException("Attendance record missing for a web clock-in"));

        // The session's own zone, locked in at Web Clock-In (via the shared Attendance row's
        // timezone, set by whichever source first created it that day) — NOT this click's browser
        // zone, which may have drifted since (travel, DST). Mirrors AttendanceService.checkOut's
        // resolveZone.
        LocalDateTime now = LocalDateTime.now(resolveZone(record, actor.getId(), clientTimezone));

        // Resolved against THIS RECORD's own snapshotted Shift (never the employee's current
        // one) — see AttendanceInterpretationService.interpretExistingSession. A legacy
        // pre-snapshot record ({@code shiftId == null}) can't be safely evaluated for either
        // question below — treated conservatively as "not past its workday" (don't block) and
        // "uncapped" (see closeSession's identical null-cutoff handling in AttendanceService),
        // rather than guessing using the employee's current Shift.
        AttendanceInterpretation interpretation = attendanceInterpretationService.interpretExistingSession(record, now);

        // Past its own logical workday (per ShiftDayPolicy.shiftDayOf, shift-relative) — checked
        // purely against THIS Web session's own workDate, deliberately not coupled to
        // the shared Attendance.status field (which is now reserved for the normal session's own
        // Missing-Check-Out flagging — see AttendanceService.flagMissingCheckoutIfStale): an
        // unrelated stale normal session sharing the same day must never block a legitimate,
        // timely Web Clock-Out, and vice versa.
        if (!interpretation.isLegacyUnresolved() && interpretation.getWorkDate().isAfter(req.getWorkDate())) {
            throw new IllegalArgumentException(
                    "This session is past its check-out window. Please submit a regularization request.");
        }

        // The shift's own natural end still bounds the WORKED-MINUTES figure — a forgotten
        // checkout left open for hours must not inflate into something like "27h 8m" for what's
        // supposed to be a single shift/day. But it must never be used as the recorded
        // checkedOutAt itself: the actual click time (`now`) is always what gets stored — shift
        // timing only feeds the capped aggregate below (recomputeCombinedWorkedMinutes's capAt),
        // never the timestamp. Mirrors AttendanceService.checkOut/closeSession.
        LocalDateTime cutoff = interpretation.isLegacyUnresolved() ? null : interpretation.getCheckoutCutoff();

        String before = auditSnapshot.toJson(Map.of("checkedOutAt", "null"));
        req.setCheckedOutAt(now);
        webClockInRepository.save(req);

        // Combined total across BOTH Check-In/Out and Web Clock-In/Out, overlap-safe — see
        // AttendanceService.recomputeCombinedWorkedMinutes. Normal and Web sessions are
        // independent and can genuinely overlap in real time, so this is a merge, not a running
        // "+= this session's minutes" (that would double-count any overlapping window).
        int workedMinutes = attendanceService.recomputeCombinedWorkedMinutes(actor.getId(), record.getId(), record.getWorkDate(), cutoff);
        record.setWorkedMinutes(workedMinutes);
        // Never touch record.checkOutAt here — see this method's own Javadoc. Status
        // (PRESENT/LATE/HALF_DAY) IS recomputed from the new combined total, since that
        // classification is about the day's overall worked-time sufficiency, not the normal
        // session's open/closed state — but a Missing-Check-Out flag already set by the normal
        // side's own staleness detection is left alone, not silently overwritten by this
        // unrelated Web checkout. And per AttendanceService.closeSession's identical rule, a
        // Web Clock-Out before the shift's own natural end (cutoff) is a resumable break, not the
        // day's final word — HALF_DAY is only finalized once the shift has actually ended. A
        // null cutoff (legacy pre-snapshot record) is never finalized here either — see
        // AttendanceService.closeSession's identical null-cutoff handling.
        if (!STATUS_MISSING_CHECKOUT.equals(record.getStatus()) && cutoff != null && !now.isBefore(cutoff)) {
            record.setStatus(workedMinutes < attendanceRulesService.getHalfDayMaxHours() * 60
                    ? STATUS_HALF_DAY
                    : (record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT));
        }
        attendanceRepository.save(record);

        String after = auditSnapshot.toJson(Map.of("checkedOutAt", now.toString(), "workedMinutes", workedMinutes));
        auditService.log(actor.getId(), "WEB_CLOCK_OUT", req.getId(), before, after);
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Ensures the day's Attendance row exists, so this session has somewhere to record its
     * eventual checkout/worked-minutes contribution. Deliberately does NOT touch checkInAt/
     * checkOutAt/sessionStartedAt on an already-existing row — those fields belong exclusively to
     * the NORMAL Check-In/Check-Out session now (see AttendanceService.findOpenNormalAttendance);
     * a Web Clock-In session is fully independent and tracks its own open/closed state entirely
     * on WebClockInRequest.requestedCheckIn/checkedOutAt, never by mutating the shared row. If no
     * row exists yet for the day, THIS is the day's first-ever punch (from either source) —
     * create it and compute lateness/status/penalty exactly as AttendanceService.checkIn would,
     * so a remote-only day is still evaluated for lateness like any other. {@code interpretation}
     * is the SAME {@link AttendanceInterpretation} {@link #submit} already resolved this employee's
     * current Shift against — reused here rather than re-derived, so the row's snapshotted
     * {@code shiftId} and its lateness are guaranteed consistent with each other.
     */
    private void applyCheckInToAttendance(WebClockInRequest req, String resolvedZoneId, Employee employee,
                                           AttendanceInterpretation interpretation) {
        boolean alreadyExists = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getWorkDate()).isPresent();
        if (alreadyExists) {
            return;
        }
        Attendance record = Attendance.builder()
                .employeeUserId(req.getEmployeeUserId())
                .workDate(req.getWorkDate())
                .checkInAt(req.getRequestedCheckIn())
                .sessionStartedAt(req.getRequestedCheckIn())
                .timezone(resolvedZoneId)
                .status(interpretation.getIsLate() ? STATUS_LATE : STATUS_PRESENT)
                .lateByMinutes(interpretation.getLateByMinutes())
                // Snapshotted once, here, at creation — never updated again — see
                // AttendanceInterpretationService.interpretExistingSession.
                .shiftId(interpretation.getShiftId())
                .build();
        record.setSource(SOURCE_WEB_REMOTE);
        Attendance saved;
        try {
            // Flushed immediately so the UNIQUE(employee_user_id, work_date) constraint — the
            // last line of defense against two near-simultaneous fresh check-ins (a Web Clock-In
            // racing another Web Clock-In, or a normal Check-In) both passing the alreadyExists
            // check above before either commits — is hit right here rather than surfacing later
            // as an unhandled 500.
            saved = attendanceRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("You have already checked in today");
        }

        // Same penalty as AttendanceService.checkIn — a fresh late arrival costs a half-day every
        // 3rd time in the month, regardless of whether the check-in was in-office or remote.
        if (STATUS_LATE.equals(saved.getStatus()) && employee != null) {
            latePenaltyService.applyIfDue(employee, req.getWorkDate());
        }
    }

    /**
     * A Web Clock-In session left open past its own workday/grace window (a forgotten Web
     * Clock-Out from days ago) — auto-closed at its own natural shift end (same cap a real Web
     * Clock-Out would apply, see {@link ShiftDayPolicy#shiftEndAt}) rather than left open forever
     * blocking a fresh Web Clock-In. Recomputes the day's combined worked minutes afterward so the
     * auto-close is correctly reflected in the total. {@code interpretation} is the SAME
     * {@link AttendanceInterpretation} {@link #submit} already resolved {@code staleReqAttendance}'s
     * own snapshotted Shift against (never the employee's current one) — reused here rather than
     * re-derived. Only ever called once {@code submit} has already confirmed it's resolvable
     * (non-legacy).
     */
    private void autoCloseStaleWebSession(WebClockInRequest staleReq, Attendance staleReqAttendance,
                                           AttendanceInterpretation interpretation) {
        LocalDateTime cutoff = interpretation.getCheckoutCutoff();
        staleReq.setCheckedOutAt(cutoff);
        webClockInRepository.save(staleReq);
        if (staleReqAttendance != null) {
            int workedMinutes = attendanceService.recomputeCombinedWorkedMinutes(
                    staleReq.getEmployeeUserId(), staleReqAttendance.getId(), staleReqAttendance.getWorkDate());
            staleReqAttendance.setWorkedMinutes(workedMinutes);
            attendanceRepository.save(staleReqAttendance);
        }
        auditService.log(staleReq.getEmployeeUserId(), "WEB_CLOCK_IN_AUTO_CLOSED_STALE", staleReq.getId());
    }

    /**
     * Precedence, highest first: the employee's own Employee.timezone (Admin-set, authoritative
     * once present), then their assigned Location.timezone, then the org-wide default
     * (AttendanceRulesService.getDefaultZoneId(), Admin-configurable — see V167). Mirrors
     * AttendanceService.zoneIdFor exactly. Used only when there's no browser-reported (or
     * session-locked) zone to prefer — see resolveZone.
     */
    private ZoneId zoneIdFor(UUID employeeUserId) {
        Employee employee = employeeRepository.findById(employeeUserId).orElse(null);
        if (employee == null) {
            return attendanceRulesService.getDefaultZoneId();
        }
        String employeeTimezone = employee.getTimezone();
        if (employeeTimezone != null && !employeeTimezone.isBlank()) {
            return ZoneId.of(employeeTimezone);
        }
        String locationTimezone = employee.getLocation() != null ? employee.getLocation().getTimezone() : null;
        return (locationTimezone != null && !locationTimezone.isBlank())
                ? ZoneId.of(locationTimezone)
                : attendanceRulesService.getDefaultZoneId();
    }

    /**
     * Parses an IANA zone id (e.g. from the browser's {@code Intl.DateTimeFormat()
     * .resolvedOptions().timeZone}), or null if it's missing/blank/not a real zone — callers
     * fall back to {@link #zoneIdFor} rather than fail the request over a malformed value.
     * Mirrors AttendanceService.parseZone.
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
     * Zone for a fresh Web Clock-In click: ALWAYS resolved server-side via {@link #zoneIdFor}'s
     * precedence chain. {@code clientTimezone} (the browser-reported zone, still sent by the
     * frontend on every Web Clock action) is deliberately never consulted — per explicit
     * requirement, the employee's own configured timezone is the ONLY authoritative source for
     * their attendance clock, and Web Clock must use the exact same source as normal
     * Check-In/Check-Out, never a different one. Mirrors AttendanceService.resolveZone(String, Employee).
     */
    private ZoneId resolveZone(String clientTimezone, UUID employeeUserId) {
        return zoneIdFor(employeeUserId);
    }

    /**
     * Zone for an EXISTING session — its own Attendance.timezone, locked in at Check-In/Web
     * Clock-In (itself already Location-derived — see the other resolveZone overload), governs
     * Check-Out/grace-window/worked-minutes math for as long as it's open. Falls back to the
     * employee's current Location.timezone only for a record predating this column;
     * clientTimezoneFallback is likewise never consulted, for the same reason as above. Mirrors
     * AttendanceService.resolveZone(Attendance, Employee, String).
     */
    private ZoneId resolveZone(Attendance record, UUID employeeUserId, String clientTimezoneFallback) {
        ZoneId stored = record != null ? parseZone(record.getTimezone()) : null;
        return stored != null ? stored : zoneIdFor(employeeUserId);
    }

    private UUID resolveAssignedApprover(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
    }

    private WebClockInRequest requirePending(UUID requestId) {
        WebClockInRequest req = webClockInRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void assertCanReview(WebClockInRequest req, User actor) {
        if (hasOverrideRole(actor)) return;
        if (actor.getId().equals(req.getAssignedApproverId())) return;

        boolean isManager = actor.getRoles().stream().anyMatch(r -> r.getCode().equals("MANAGER"));
        if (isManager && isCurrentManagerOf(actor.getId(), req.getEmployeeUserId())) return;

        throw new AccessDeniedException("You are not authorized to review this request");
    }

    private boolean hasOverrideRole(User actor) {
        return actor.getRoles().stream().anyMatch(r -> APPROVER_OVERRIDE_ROLES.contains(r.getCode()));
    }

    private boolean isCurrentManagerOf(UUID managerCandidateId, UUID employeeUserId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .map(managerCandidateId::equals)
                .orElse(false);
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    /**
     * Mirrors AttendanceService.resolveEmployee's exact message — a missing Employee profile is a
     * distinct, clearer failure than ShiftDayPolicy's own (separate) "no assigned Shift" one, and
     * must never be allowed to surface as that more confusing error further down this call.
     */
    private Employee requireEmployee(UUID userId) {
        return employeeRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }

    /**
     * Gate for self-service Web Clock-In/Out ONLY — mirrors
     * AttendanceService.assertEligibleToPunch exactly (same duplication precedent already
     * established for resolveZone/zoneIdFor in this class). Takes the already-loaded actor
     * User directly rather than re-deriving it from the Employee, avoiding a redundant lazy-load
     * of the same row.
     */
    private void assertEligibleToPunch(User actor) {
        if (!actor.isActive() || actor.getDeletedAt() != null) {
            throw new IllegalArgumentException(
                    "Your account is inactive. Contact HR if you believe this is an error.");
        }
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName).orElse("Unknown");
    }

    private WebClockInResponse toResponse(WebClockInRequest req) {
        return toResponses(List.of(req)).get(0);
    }

    /**
     * Batch equivalent of {@link #toResponse} — listMine/listPendingForApprover funnel through
     * here instead of mapping row-by-row. Previously each row cost up to 4 findById round trips
     * (employee, email, reviewer, assignedApprover); this collects every distinct user id
     * referenced across the whole batch and resolves them with one name-lookup query total,
     * regardless of how many requests are being mapped. Output fields/values are unchanged.
     */
    private List<WebClockInResponse> toResponses(List<WebClockInRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }

        Set<UUID> allUserIds = new LinkedHashSet<>();
        for (WebClockInRequest req : requests) {
            allUserIds.add(req.getEmployeeUserId());
            addIfNotNull(allUserIds, req.getReviewedBy());
            addIfNotNull(allUserIds, req.getAssignedApproverId());
        }

        Map<UUID, String> nameById = employeeRepository.findNamesByUserIds(allUserIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
        Map<UUID, Employee> employeeById = employeeRepository.findAllByIdWithDepartment(allUserIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        Map<UUID, String> emailById = userRepository.findAllById(allUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        return requests.stream().map(req -> toResponse(req, nameById, employeeById, emailById)).toList();
    }

    private static void addIfNotNull(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private WebClockInResponse toResponse(WebClockInRequest req, Map<UUID, String> nameById,
                                           Map<UUID, Employee> employeeById, Map<UUID, String> emailById) {
        Employee employee = employeeById.get(req.getEmployeeUserId());
        String employeeName = employee != null ? employee.getFullName() : "Unknown";
        String departmentName = employee != null && employee.getDepartment() != null
                ? employee.getDepartment().getName() : null;
        String employeeEmail = emailById.getOrDefault(req.getEmployeeUserId(), "");
        String reviewerName = req.getReviewedBy() == null ? null : nameById.get(req.getReviewedBy());
        String assignedApproverName = req.getAssignedApproverId() == null ? null
                : nameById.get(req.getAssignedApproverId());

        return WebClockInResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .workDate(req.getWorkDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .reason(req.getReason())
                .status(req.getStatus())
                .assignedApproverId(req.getAssignedApproverId())
                .assignedApproverName(assignedApproverName)
                .checkedOutAt(req.getCheckedOutAt())
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .createdAt(req.getCreatedAt())
                .build();
    }
}
