package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.ApprovalHistoryEntryDto;
import com.nforce.onehr.dto.attendance.ApproverOptionDto;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.InterpretationOutcome;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.RegularizationApproval;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationApprovalRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Attendance Regularization: employee-submitted corrections for missed/wrong punches,
 * routed via EmployeeManagerHistory to the employee's current manager (or HR/Super Admin)
 * for approval. Approval upserts the corresponding attendance_records row — the same table
 * and entity {@link AttendanceService} writes on check-in/check-out — tagging it with
 * source=REGULARIZATION.
 *
 * <p>Lifecycle notifications (Created/Approved/Rejected) reuse the existing
 * {@link NotificationService} — the same in-app notification model/API/bell every other
 * workstream (AssetService, DocumentService, ...) already sends through. No new notification
 * architecture; see {@link #notifyRecipients} for the one dedup seam.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegularizationService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    // Super Admin deliberately excluded — they already have blanket review visibility via
    // APPROVER_OVERRIDE_ROLES above, so they're not offered as an explicit "Assign To" target.
    private static final Set<String> ELIGIBLE_APPROVER_ROLES = Set.of("MANAGER", "HR_ADMIN");
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    private static final String SOURCE_REGULARIZATION = "REGULARIZATION";

    // Two-stage regularization approval: Manager first (PENDING -> PARTIALLY_APPROVED), then
    // HR Admin or Super Admin final (-> APPROVED). Super Admin may also bypass straight from
    // PENDING to APPROVED. See approve()/reject() for the status-first authorization logic.
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PARTIALLY_APPROVED = "PARTIALLY_APPROVED";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    // Request Regularization's own "today" switches at 07:00 AM, not midnight — a shift/punch
    // that runs into the early morning is still yesterday's business day until 7 AM, so an
    // employee correcting an overnight punch isn't blocked by the lookback window or miscounted
    // against the wrong month just because the wall clock rolled over. Scoped entirely to this
    // service's own date validation — AttendanceService's check-in/check-out day resolution
    // (open-session carry-over, see AttendanceService.getToday) is untouched.
    private static final LocalTime REGULARIZATION_DAY_BOUNDARY = LocalTime.of(7, 0);

    // Matches EmailService's existing notification-date wording exactly ("d MMM yyyy") rather
    // than introducing a different format for regularization notifications.
    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final RegularizationRequestRepository regularizationRepository;
    private final RegularizationApprovalRepository regularizationApprovalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final ExceptionService exceptionService;
    // The single, narrow, shared owner of Shift-relative punch interpretation — see its own class
    // Javadoc. Replaces this class's own former private resolveShiftStart/recomputeDerivedFields,
    // which independently resolved the employee's CURRENT (live) Shift even when correcting a
    // historical record — the exact "reassigned since this record's date" drift this class now
    // avoids by resolving through the record's own shiftId snapshot instead (see
    // AttendanceInterpretationService.interpretExistingSession). This also deliberately unifies
    // regularization's lateByMinutes onto the same shiftStart-anchored, grace-forgiving formula
    // AttendanceService/WebClockInService already share — see approve()'s own comment for the
    // exact, small, visible consequence of that unification. NOT used for this class's own
    // REGULARIZATION_DAY_BOUNDARY/resolveBusinessDate below, which remains a separate,
    // intentionally-independent request-validation concept, left untouched.
    private final AttendanceInterpretationService attendanceInterpretationService;
    // Persisted, Admin-editable HALF_DAY threshold (Workstream B) — see its own Javadoc.
    private final AttendanceRulesService attendanceRulesService;
    // The same test-controllable production Clock AuthService already uses (see TimeConfig/
    // MutableClock) — real wall-clock time in production, freezable in tests. Backs ONLY the
    // future-timestamp guard in resolveTimes() below; every other date/time computation in this
    // class (REGULARIZATION_DAY_BOUNDARY/resolveBusinessDate, reviewedAt/approvedAt/
    // finalApprovedAt, ...) is untouched and still reads the real system clock directly.
    private final Clock clock;

    /** Resolved requested times after applying punch auto-fill from attendance history. */
    private record ResolvedTimes(LocalDateTime checkIn, LocalDateTime checkOut) {}

    // Lookback window (N days): how far back a non-Super-Admin employee may request a
    // correction. Super Admin submitters (also holding EMPLOYEE in this org) are exempt
    // entirely — see submit()/update().
    @Value("${app.attendance.regularization.employee-lookback-days:7}")
    private int employeeLookbackDays;

    // Max regularization requests a non-Super-Admin employee may submit per calendar month
    // (counts every submission regardless of eventual status). Super Admin is exempt.
    @Value("${app.attendance.regularization.monthly-limit:3}")
    private int monthlyLimit;

    @Transactional
    public RegularizationResponse submit(CreateRegularizationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        boolean isSuperAdmin = hasRole(actor, "SUPER_ADMIN");

        ResolvedTimes times = resolveTimes(req, actor.getId());
        assertNotBeforeJoiningDate(actor.getId(), req.getAttendanceDate());
        if (!isSuperAdmin) {
            validateLookbackWindow(req.getAttendanceDate(), employeeLookbackDays);
            assertMonthlyLimitNotExceeded(actor.getId());
        }
        assertNoDuplicateRequest(actor.getId(), req.getAttendanceDate());

        RegularizationRequest entity = RegularizationRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()))
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(times.checkIn())
                .requestedCheckOut(times.checkOut())
                .reason(req.getReason().trim())
                .status(STATUS_PENDING)
                .build();
        entity = regularizationRepository.save(entity);

        auditService.log(actor.getId(), "REGULARIZATION_REQUESTED", actor.getId());

        // Request Created: notify the resolved approver (the employee's reporting manager, or a
        // manually-picked eligible approver) plus every active HR Admin, so HR isn't left to
        // discover a new request only by checking their queue. Deliberately HR_ADMIN only, not
        // findAdminUserIds — Super Admin already has blanket queue visibility (see
        // listPendingForApprover), so isn't separately paged on every single submission.
        Set<UUID> submittedRecipients = new LinkedHashSet<>();
        if (entity.getAssignedApproverId() != null) submittedRecipients.add(entity.getAssignedApproverId());
        submittedRecipients.addAll(userRepository.findActiveHrAdminUserIds());
        if (!submittedRecipients.isEmpty()) {
            notifyRecipients(submittedRecipients, "REGULARIZATION_SUBMITTED",
                    "Regularization Request Submitted",
                    employeeName(actor.getId()) + " has submitted a regularization request for "
                            + entity.getAttendanceDate().format(NOTIFICATION_DATE_FMT) + ".",
                    "/approvals?type=REGULARIZATION");
        }
        return toResponse(entity);
    }

    /**
     * Max {@link #monthlyLimit} submissions per calendar month per employee, counting every
     * request regardless of eventual status (a rejected request still consumed a slot) —
     * skipped entirely for Super Admin submitters. Not enforced on update() — editing an
     * existing pending request must not consume an extra slot.
     */
    private void assertMonthlyLimitNotExceeded(UUID employeeId) {
        LocalDate today = regularizationBusinessToday();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        long countThisMonth = regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(
                employeeId, monthStart, monthStart.plusMonths(1));
        if (countThisMonth >= monthlyLimit) {
            throw new IllegalArgumentException(
                    "You have reached the maximum of " + monthlyLimit + " regularization requests for this month");
        }
    }

    /**
     * "N requests remaining this month" for the Regularization modal's balance display —
     * read-only, reuses the exact same count query {@link #assertMonthlyLimitNotExceeded} runs
     * before every submit(); never enforces anything itself. Super Admin is exempt from the
     * limit (see submit()), so its balance is reported as unlimited rather than a real count.
     */
    @Transactional(readOnly = true)
    public RegularizationBalance getBalance(String actorEmail) {
        User actor = requireActor(actorEmail);
        boolean isSuperAdmin = hasRole(actor, "SUPER_ADMIN");
        LocalDate today = regularizationBusinessToday();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        int usedCount = (int) regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(
                actor.getId(), monthStart, monthStart.plusMonths(1));
        return new RegularizationBalance(usedCount, monthlyLimit, Math.max(0, monthlyLimit - usedCount), isSuperAdmin);
    }

    public record RegularizationBalance(int usedCount, int limitCount, int remainingCount, boolean unlimited) {}

    /**
     * Edit a still-pending request. Only the submitting employee may edit, and only while
     * status is PENDING — approved/rejected requests are immutable history.
     */
    @Transactional
    public RegularizationResponse update(UUID requestId, CreateRegularizationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest existing = regularizationRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!existing.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own requests");
        }
        if (!STATUS_PENDING.equals(existing.getStatus())) {
            throw new IllegalStateException("Only pending requests can be edited");
        }

        String before = auditSnapshot.toJson(regularizationSnapshot(existing));
        ResolvedTimes times = resolveTimes(req, actor.getId());
        assertNotBeforeJoiningDate(actor.getId(), req.getAttendanceDate());
        if (!hasRole(actor, "SUPER_ADMIN")) {
            validateLookbackWindow(req.getAttendanceDate(), employeeLookbackDays);
        }
        if (!existing.getAttendanceDate().equals(req.getAttendanceDate())) {
            assertNoDuplicateRequest(actor.getId(), req.getAttendanceDate());
        }

        existing.setAttendanceDate(req.getAttendanceDate());
        existing.setRequestedCheckIn(times.checkIn());
        existing.setRequestedCheckOut(times.checkOut());
        existing.setReason(req.getReason().trim());
        existing.setAssignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()));
        existing = regularizationRepository.save(existing);

        String after = auditSnapshot.toJson(regularizationSnapshot(existing));
        auditService.log(actor.getId(), "REGULARIZATION_UPDATED", existing.getId(), before, after);
        return toResponse(existing);
    }

    /**
     * Complete pre/post-correction picture of one Attendance row, for the audit trail captured
     * around the Attendance mutation in {@link #approve}. {@code null} (no record existed yet —
     * the "brand-new row" branch) is captured explicitly as {@code existed=false} rather than
     * an empty map, so the audit history can distinguish "there was nothing here before" from
     * "the snapshot failed to capture something."
     */
    private Map<String, Object> attendanceSnapshot(Attendance a) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (a == null) {
            snapshot.put("existed", false);
            return snapshot;
        }
        snapshot.put("existed", true);
        snapshot.put("checkInAt", a.getCheckInAt());
        snapshot.put("checkOutAt", a.getCheckOutAt());
        snapshot.put("status", a.getStatus());
        snapshot.put("lateByMinutes", a.getLateByMinutes());
        snapshot.put("workedMinutes", a.getWorkedMinutes());
        snapshot.put("source", a.getSource());
        snapshot.put("shiftId", a.getShiftId());
        return snapshot;
    }

    private Map<String, Object> regularizationSnapshot(RegularizationRequest r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("attendanceDate", r.getAttendanceDate());
        snapshot.put("requestedCheckIn", r.getRequestedCheckIn());
        snapshot.put("requestedCheckOut", r.getRequestedCheckOut());
        snapshot.put("reason", r.getReason());
        snapshot.put("assignedApproverId", r.getAssignedApproverId());
        snapshot.put("status", r.getStatus());
        return snapshot;
    }

    /**
     * An APPROVED request for a date is a settled correction — resubmitting is blocked outright.
     * A PENDING or PARTIALLY_APPROVED request is still awaiting a decision, so a second one for
     * the same date is blocked too (rejected/approved dates may otherwise be freely resubmitted).
     */
    private void assertNoDuplicateRequest(UUID employeeId, LocalDate attendanceDate) {
        if (regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                employeeId, attendanceDate, STATUS_APPROVED)) {
            throw new IllegalArgumentException("Already raised regularization for this date.");
        }
        if (regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                employeeId, attendanceDate, STATUS_PARTIALLY_APPROVED)) {
            throw new IllegalArgumentException(
                    "A regularization request for this date is already partially approved and pending final review");
        }
        if (regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                employeeId, attendanceDate, STATUS_PENDING)) {
            throw new IllegalArgumentException("A pending regularization request already exists for this date");
        }
    }

    /** Validates the raw request, then fills any omitted side from the existing punch record. */
    private ResolvedTimes resolveTimes(CreateRegularizationRequest req, UUID employeeId) {
        if (req.getRequestedCheckIn() == null && req.getRequestedCheckOut() == null) {
            throw new IllegalArgumentException("Provide at least a corrected check-in or check-out time");
        }

        Attendance existingPunch;
        try {
            existingPunch = attendanceRepository
                    .findByEmployeeUserIdAndWorkDate(employeeId, req.getAttendanceDate())
                    .orElse(null);
        } catch (Exception e) {
            // Punch auto-fill is a convenience, not a requirement — if the attendance lookup
            // is unavailable for any reason, fall back to exactly what the caller provided
            // rather than failing the whole regularization request over it.
            log.warn("Punch auto-fill lookup failed for employee {} on {}; continuing without it: {}",
                    employeeId, req.getAttendanceDate(), e.getMessage());
            existingPunch = null;
        }
        LocalDateTime checkIn = req.getRequestedCheckIn() != null
                ? req.getRequestedCheckIn()
                : (existingPunch != null ? existingPunch.getCheckInAt() : null);
        LocalDateTime checkOut = req.getRequestedCheckOut() != null
                ? req.getRequestedCheckOut()
                : (existingPunch != null ? existingPunch.getCheckOutAt() : null);

        // Overnight shift (e.g. the configured default 3:30 PM -> 12:30 AM): the frontend always
        // submits both times on the same attendanceDate (see RequestModal in AttendancePage.tsx —
        // there is no next-day rollover in the UI), so a check-out clock time earlier than
        // check-in's on that same date isn't a same-day ordering mistake, it's the next calendar
        // day. Roll it forward exactly once here, after punch auto-fill above, so this applies
        // identically whether check-out was explicitly requested or filled in from an existing
        // punch. Only fires when both are still on the same date — a check-out already resolved
        // to the next day (e.g. auto-filled from an existing overnight punch) is left untouched.
        if (checkIn != null && checkOut != null
                && checkOut.toLocalDate().equals(checkIn.toLocalDate())
                && checkOut.toLocalTime().isBefore(checkIn.toLocalTime())) {
            checkOut = checkOut.plusDays(1);
        }

        // Validate each EXPLICITLY-requested correction against this attendance's actual
        // shift-aware WORKDAY window (see ShiftDayPolicy#shiftDayOf), never a bare "same calendar
        // date" or fixed-clock-time comparison. A 10:00-19:00 shift with an 18h maximum workday
        // duration spans workday 04:00 -> 04:00 the NEXT calendar day, so a corrected punch at
        // 12:21 AM or 3:30 AM the next calendar day can still legitimately belong to this
        // attendanceDate's workday. Checked against the (possibly rolled-over) resolved value
        // above, not the raw request field, so an overnight check-out — e.g. rolled over to
        // 18-Aug 00:30 — is correctly validated against 17-Aug's own workday, not rejected for
        // merely landing on a different calendar date. Resolved against existingPunch's own
        // snapshotted shift when one already exists for this date (never the employee's current
        // shift — see AttendanceInterpretationService#belongsToWorkday), or the employee's
        // current shift for a brand-new correction with no prior punch to preserve context from.
        if (req.getRequestedCheckIn() != null || req.getRequestedCheckOut() != null) {
            Employee employee = employeeRepository.findById(employeeId).orElse(null);
            if (req.getRequestedCheckIn() != null
                    && !attendanceInterpretationService.belongsToWorkday(employee, existingPunch, req.getAttendanceDate(), checkIn)) {
                throw new IllegalArgumentException(
                        workdayValidationMessage("check-in", employee, existingPunch, req.getAttendanceDate()));
            }
            if (req.getRequestedCheckOut() != null
                    && !attendanceInterpretationService.belongsToWorkday(employee, existingPunch, req.getAttendanceDate(), checkOut)) {
                throw new IllegalArgumentException(
                        workdayValidationMessage("check-out", employee, existingPunch, req.getAttendanceDate()));
            }

            // A corrected check-in/check-out must describe something that has already happened —
            // never later than the authoritative server clock, read in the SAME employee/business
            // zone every real Check-In/Web Clock-In/Attendance row already resolves through (see
            // AttendanceRulesService#resolveEmployeeZoneId; mirrors AttendanceService.resolveZone's
            // own "employee's configured timezone is the only authoritative source" rule). This is
            // deliberately independent of attendanceDate/REGULARIZATION_DAY_BOUNDARY/
            // resolveBusinessDate above (that 07:00 boundary remains scoped purely to the lookback-
            // window check, untouched here): a genuinely historical correction's own resolved
            // instant is, by construction, already in the past relative to "now," so this can never
            // reject one. Checked against the same (auto-filled + overnight-rolled-over) `checkIn`/
            // `checkOut` values the workday check above just validated, and independently for each
            // side, so an overnight correction whose check-in already happened but whose check-out
            // (rolled onto tomorrow) has not is caught correctly rather than treated as one unit.
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), attendanceRulesService.resolveEmployeeZoneId(employee));
            if (req.getRequestedCheckIn() != null && checkIn.isAfter(now)) {
                throw new IllegalArgumentException("Corrected check-in time cannot be later than the current time");
            }
            if (req.getRequestedCheckOut() != null && checkOut.isAfter(now)) {
                throw new IllegalArgumentException("Corrected check-out time cannot be later than the current time");
            }
        }

        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Check-out time must be after check-in time");
        }
        return new ResolvedTimes(checkIn, checkOut);
    }

    /**
     * "Corrected check-in/out time must fall within the attendance workday (X - Y)" — includes the
     * actual resolved workday window (see {@link AttendanceInterpretationService#resolveWorkdayWindowFor})
     * so a rejected correction tells the employee exactly what range would have been accepted,
     * rather than a bare rejection. Falls back to a window-less message only in the (structurally
     * unreachable here, since this is only ever called right after {@code belongsToWorkday}
     * returned {@code false} — which itself requires a resolvable shift context) legacy-row case.
     */
    private String workdayValidationMessage(String field, Employee employee, Attendance existingRecordOrNull, LocalDate attendanceDate) {
        AttendanceInterpretationService.WorkdayWindow window =
                attendanceInterpretationService.resolveWorkdayWindowFor(employee, existingRecordOrNull, attendanceDate);
        if (window == null) {
            return "Corrected " + field + " time must fall within the attendance workday for "
                    + attendanceDate.format(NOTIFICATION_DATE_FMT) + ".";
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM h:mm a");
        return "Corrected " + field + " time must fall within the attendance workday ("
                + window.start().format(fmt) + " - " + window.end().format(fmt) + ").";
    }

    /** Selected manager (validated as an eligible approver) else the employee's current manager. */
    private UUID resolveAssignedApprover(UUID employeeId, UUID managerUserId) {
        if (managerUserId != null) {
            User candidate = userRepository.findById(managerUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected manager not found"));
            boolean eligible = candidate.getRoles().stream()
                    .anyMatch(r -> ELIGIBLE_APPROVER_ROLES.contains(r.getCode()));
            if (!eligible) {
                throw new IllegalArgumentException("Selected user is not an eligible approver");
            }
            return candidate.getId();
        }
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
    }

    /** Active users eligible to be selected/assigned as an approver, for the manager-select dropdown. */
    @Transactional(readOnly = true)
    public List<ApproverOptionDto> listApprovers() {
        return employeeRepository.findActiveByRoleCodes(ELIGIBLE_APPROVER_ROLES).stream()
                .map(e -> ApproverOptionDto.builder()
                        .userId(e.getUserId())
                        .fullName(e.getFullName())
                        .email(e.getUser().getEmail())
                        .roleCode(e.getUser().getRoles().stream()
                                .map(Role::getCode)
                                .filter(ELIGIBLE_APPROVER_ROLES::contains)
                                .findFirst().orElse(""))
                        .build())
                .sorted(Comparator.comparing(ApproverOptionDto::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /** Super Admin: full history across everyone, with optional filters. */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listAll(UUID employeeUserId, UUID approverUserId,
                                                 UUID departmentId, String month, String status) {
        List<RegularizationRequest> filtered = regularizationRepository.findAllWithActiveRequester().stream()
                .filter(r -> employeeUserId == null || employeeUserId.equals(r.getEmployeeUserId()))
                .filter(r -> approverUserId == null || approverUserId.equals(r.getAssignedApproverId()))
                .filter(r -> status == null || status.equalsIgnoreCase(r.getStatus()))
                .filter(r -> month == null || r.getAttendanceDate().toString().startsWith(month))
                .filter(r -> departmentId == null || departmentId.equals(departmentIdOf(r.getEmployeeUserId())))
                .sorted(Comparator.comparing(RegularizationRequest::getCreatedAt).reversed())
                .toList();
        return toResponses(filtered);
    }

    private UUID departmentIdOf(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId)
                .map(Employee::getDepartment)
                .map(d -> d.getId())
                .orElse(null);
    }

    /**
     * "View Regularization History" from the Penalties kebab menu — every request ever filed
     * for one employee/date. A plain Manager may only view a current direct report's history;
     * HR/Super Admin may view anyone's, same override as {@link #getEmployeeHistory}-style checks
     * elsewhere in this workstream.
     */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> getHistoryForManager(String managerEmail, UUID employeeUserId, LocalDate attendanceDate) {
        User actor = requireActor(managerEmail);
        if (!hasOverrideRole(actor)
                && !historyRepository.findCurrentDirectReportIds(actor.getId()).contains(employeeUserId)) {
            throw new AccessDeniedException("You can only view regularization history for your direct reports");
        }
        return toResponses(regularizationRepository.findByEmployeeUserIdAndAttendanceDateOrderByCreatedAtDesc(employeeUserId, attendanceDate));
    }

    @Transactional(readOnly = true)
    public List<RegularizationResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return toResponses(regularizationRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId()));
    }

    /**
     * Stage-aware queue: a plain MANAGER sees only their assigned PENDING requests (stage 1);
     * HR_ADMIN sees every PARTIALLY_APPROVED request (stage 2, final); SUPER_ADMIN sees both
     * (they can act at either stage). A dual-role actor (e.g. MANAGER + HR_ADMIN) gets the
     * union of both queues rather than one role shadowing the other.
     */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        boolean isSuperAdmin = hasRole(actor, "SUPER_ADMIN");
        boolean isHrAdmin = hasRole(actor, "HR_ADMIN");
        boolean isManager = hasRole(actor, "MANAGER");

        Map<UUID, RegularizationRequest> queue = new LinkedHashMap<>();
        if (isSuperAdmin) {
            regularizationRepository.findByStatusIn(List.of(STATUS_PENDING, STATUS_PARTIALLY_APPROVED))
                    .forEach(r -> queue.put(r.getId(), r));
        } else {
            if (isHrAdmin) {
                regularizationRepository.findByStatus(STATUS_PARTIALLY_APPROVED)
                        .forEach(r -> queue.put(r.getId(), r));
            }
            if (isManager) {
                regularizationRepository.findByStatus(STATUS_PENDING).stream()
                        .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                        .forEach(r -> queue.put(r.getId(), r));
            }
        }
        return toResponses(new ArrayList<>(queue.values()));
    }

    /**
     * Same scoping as {@link #listPendingForApprover}, but across every status — not just
     * PENDING. Powers the Pending Approvals screen's All/Pending/Approved/Rejected status tabs
     * for Manager/HR/Super Admin, since the review history for requests they've already
     * decided is otherwise invisible to them (listPendingForApprover only ever returns PENDING).
     */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<RegularizationRequest> all = regularizationRepository.findAllWithActiveRequester();

        if (hasOverrideRole(actor)) {
            return toResponses(all.stream()
                    .sorted(Comparator.comparing(RegularizationRequest::getCreatedAt).reversed())
                    .toList());
        }
        return toResponses(all.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .sorted(Comparator.comparing(RegularizationRequest::getCreatedAt).reversed())
                .toList());
    }

    /**
     * Status-first approval. From PENDING: SUPER_ADMIN, HR_ADMIN, or MANAGER (their assigned
     * request only) all go straight to the terminal APPROVED state — a single approval from any
     * one eligible approver is final; there is no longer a two-stage hand-off. The
     * PARTIALLY_APPROVED branch below is kept only to let SUPER_ADMIN/HR_ADMIN finalize any
     * request that already reached that status before this change (legacy data) — no new
     * approval can produce PARTIALLY_APPROVED going forward. Branching on the request's current
     * status first (rather than the actor's "highest" role) means a dual-role actor (e.g.
     * MANAGER + HR_ADMIN) gets whichever authority actually matches the request's stage, instead
     * of one role permanently shadowing the other.
     */
    @Transactional
    public RegularizationResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest req = regularizationRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        boolean finalStage;
        String actingRole;
        if (STATUS_PENDING.equals(req.getStatus())) {
            if (hasRole(actor, "SUPER_ADMIN")) {
                actingRole = "SUPER_ADMIN";
                finalStage = true;
            } else if (hasRole(actor, "HR_ADMIN")) {
                // Same bypass SUPER_ADMIN already has at this stage — HR_ADMIN need not be the
                // employee's manager, and may act before the manager has (ONEHR-140 follow-up).
                actingRole = "HR_ADMIN";
                finalStage = true;
            } else if (hasRole(actor, "MANAGER")) {
                // A Manager's approval is now final on its own — no HR/Super Admin sign-off
                // required afterward. Still gated to their own assigned/reporting employee.
                assertCanReview(req, actor);
                actingRole = "MANAGER";
                finalStage = true;
            } else {
                throw new AccessDeniedException("You are not authorized to review this request");
            }
        } else if (STATUS_PARTIALLY_APPROVED.equals(req.getStatus())) {
            if (hasRole(actor, "SUPER_ADMIN")) {
                actingRole = "SUPER_ADMIN";
                finalStage = true;
            } else if (hasRole(actor, "HR_ADMIN")) {
                actingRole = "HR_ADMIN";
                finalStage = true;
            } else {
                throw new AccessDeniedException("You are not authorized to review this request");
            }
        } else {
            throw new IllegalArgumentException("Only pending or partially-approved requests can be approved");
        }

        if (finalStage) {
            Attendance record = attendanceRepository
                    .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getAttendanceDate())
                    .orElse(null);
            if (record == null && req.getRequestedCheckIn() == null) {
                // check_in_at is mandatory on a new row (one-pair-per-day punch schema) — a
                // checkout-only correction can only ever amend an existing punch.
                throw new IllegalArgumentException(
                        "Cannot approve: no attendance record exists for this date and no check-in time was requested");
            }
            // Brand-new row (no prior punch existed for this date at all) vs. correcting an
            // already-existing one are resolved differently — see
            // AttendanceInterpretationService's own class Javadoc for why:
            //  - brand-new: no prior context to preserve, so lateness/shiftId snapshot resolve
            //    against the employee's CURRENT Shift (interpretForKnownWorkDate).
            //  - existing: MUST retain that row's own shiftId snapshot — never re-resolve against
            //    the employee's current Shift, even if the correction changes its check-in time —
            //    or an employee reassigned since this record's date would silently get their
            //    lateness recomputed against a Shift that didn't apply on that historical date.
            boolean isNewRecord = record == null;
            // Captured BEFORE any mutation below — this is the row's complete pre-correction
            // state (including its own `source`, which the next few lines are about to
            // overwrite), so a disputed or repeated correction can always be reconstructed from
            // audit_log rather than silently losing what the record looked like beforehand.
            Map<String, Object> beforeAttendanceSnapshot = attendanceSnapshot(record);
            Employee employee = employeeRepository.findById(req.getEmployeeUserId()).orElse(null);
            if (isNewRecord) {
                record = Attendance.builder()
                        .employeeUserId(req.getEmployeeUserId())
                        .workDate(req.getAttendanceDate())
                        .checkInAt(req.getRequestedCheckIn())
                        // Snapshotted once, at creation, exactly like a normal Check-In/Web
                        // Clock-In — see AttendanceRulesService#resolveEmployeeZoneId and
                        // Attendance#getTimezone's own doc comment. Never touched again after
                        // this: an existing row (the non-isNewRecord branch below) keeps
                        // whatever timezone it already snapshotted, even if this correction
                        // changes its check-in time or the employee's Location has since changed.
                        .timezone(attendanceRulesService.resolveEmployeeZoneId(employee).getId())
                        .build();
            }
            if (req.getRequestedCheckIn() != null) record.setCheckInAt(req.getRequestedCheckIn());
            if (req.getRequestedCheckOut() != null) record.setCheckOutAt(req.getRequestedCheckOut());
            record.setSource(SOURCE_REGULARIZATION);
            AttendanceInterpretation interpretation = isNewRecord
                    ? attendanceInterpretationService.interpretForKnownWorkDate(employee, record.getWorkDate(), record.getCheckInAt())
                    : attendanceInterpretationService.interpretExistingRecordLateness(record, record.getCheckInAt());
            applyInterpretation(record, interpretation, isNewRecord);
            Attendance savedAttendance = attendanceRepository.save(record);

            auditService.log(actor.getId(), "ATTENDANCE_REGULARIZED", savedAttendance.getId(),
                    auditSnapshot.toJson(beforeAttendanceSnapshot),
                    auditSnapshot.toJson(attendanceSnapshot(savedAttendance)));

            req.setStatus(STATUS_APPROVED);
            req.setFinalApprovedBy(actor.getId());
            req.setFinalApprovedAt(LocalDateTime.now());
            // Section 16/Gap-033: the attendance record for this date is now corrected — any
            // penalty whose discrepancy no longer holds against the corrected record is reversed
            // automatically, scoped to only the types this correction could have affected (not a
            // blind "reverse everything for this date," which could erase an unrelated, still-
            // legitimate penalty of a different discrepancy type on the same day).
            exceptionService.reevaluateAndReverseIfInvalid(req.getEmployeeUserId(), req.getAttendanceDate(),
                    ExceptionService.REGULARIZATION_REEVALUATION_TYPES, actor.getId(),
                    "Attendance corrected via approved regularization", "ATTENDANCE_PENALTY_REVERSED");
        } else {
            req.setStatus(STATUS_PARTIALLY_APPROVED);
            req.setApprovedBy(actor.getId());
            req.setApprovedAt(LocalDateTime.now());
        }
        // reviewed_by/reviewed_at/review_comment always reflect the most recent decision at
        // either stage — unchanged semantics, preserving every existing read of these fields.
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        regularizationRepository.save(req);
        recordApproval(req.getId(), actor.getId(), "APPROVED", comment, actingRole);

        auditService.log(actor.getId(),
                finalStage ? "REGULARIZATION_APPROVED" : "REGULARIZATION_PARTIALLY_APPROVED",
                req.getEmployeeUserId());

        // finalStage is always true for a fresh approval now (Manager included) — this guard
        // only still matters for finalizing a request that was already left PARTIALLY_APPROVED
        // before this change; that legacy path also reaches here with finalStage true, so the
        // guard itself is effectively redundant now, just defensive.
        if (finalStage) {
            notifyRecipients(List.of(req.getEmployeeUserId()), "REGULARIZATION_APPROVED",
                    "Regularization Request Approved",
                    "Your regularization request for " + req.getAttendanceDate().format(NOTIFICATION_DATE_FMT)
                            + " has been approved by " + employeeName(actor.getId()) + "."
                            + (comment != null && !comment.isBlank() ? " Comment: " + comment.trim() : ""),
                    "/my-requests?type=REGULARIZATION");
        }
        return toResponse(req);
    }

    /** Same status-first stage rules as {@link #approve}, but reject is always terminal. */
    @Transactional
    public RegularizationResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest req = regularizationRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        String actingRole;
        if (STATUS_PENDING.equals(req.getStatus())) {
            if (hasRole(actor, "SUPER_ADMIN")) {
                actingRole = "SUPER_ADMIN";
            } else if (hasRole(actor, "HR_ADMIN")) {
                // Same bypass SUPER_ADMIN already has at this stage — see approve() above.
                actingRole = "HR_ADMIN";
            } else if (hasRole(actor, "MANAGER")) {
                assertCanReview(req, actor);
                actingRole = "MANAGER";
            } else {
                throw new AccessDeniedException("You are not authorized to review this request");
            }
        } else if (STATUS_PARTIALLY_APPROVED.equals(req.getStatus())) {
            if (hasRole(actor, "SUPER_ADMIN")) {
                actingRole = "SUPER_ADMIN";
            } else if (hasRole(actor, "HR_ADMIN")) {
                actingRole = "HR_ADMIN";
            } else {
                throw new AccessDeniedException("You are not authorized to review this request");
            }
        } else {
            throw new IllegalArgumentException("Only pending or partially-approved requests can be rejected");
        }

        String before = auditSnapshot.toJson(regularizationSnapshot(req));

        req.setStatus(STATUS_REJECTED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        regularizationRepository.save(req);
        recordApproval(req.getId(), actor.getId(), "REJECTED", comment, actingRole);

        String after = auditSnapshot.toJson(Map.of("status", "REJECTED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "REGULARIZATION_REJECTED", req.getEmployeeUserId(), before, after);

        // Request Rejected: reject() has no interim stage — every reject() call is terminal —
        // so the employee is always notified, with the rejection reason included when given.
        notifyRecipients(List.of(req.getEmployeeUserId()), "REGULARIZATION_REJECTED",
                "Regularization Request Rejected",
                "Your regularization request for " + req.getAttendanceDate().format(NOTIFICATION_DATE_FMT)
                        + " has been rejected by " + employeeName(actor.getId()) + "."
                        + (comment != null && !comment.isBlank() ? " Reason: " + comment.trim() : ""),
                "/my-requests?type=REGULARIZATION");
        return toResponse(req);
    }

    private void recordApproval(UUID requestId, UUID actionBy, String actionType, String comments, String actorRole) {
        regularizationApprovalRepository.save(RegularizationApproval.builder()
                .requestId(requestId)
                .actionBy(actionBy)
                .actionType(actionType)
                .comments(comments)
                .actorRole(actorRole)
                .build());
    }

    /**
     * Applies an {@link AttendanceInterpretationService} result to a record being approved —
     * replaces this class's former private {@code recomputeDerivedFields}/{@code resolveShiftStart}.
     *
     * <p><b>A deliberate, small, visible consequence of unifying onto the shared interpretation
     * service</b> (per explicit instruction — this was previously left untouched pending exactly
     * this kind of shown-and-approved change): {@code lateByMinutes} is now the same
     * shiftStart-anchored, no-grace-forgiveness figure AttendanceService/WebClockInService have
     * always displayed, rather than this class's own previously-divergent deadline-anchored,
     * truncated figure. {@code status} is unaffected — both formulas agree on whether the grace
     * window was exceeded ({@code interpretation.getIsLate()} here; the old formula's
     * {@code lateByMinutes > 0} was already exactly equivalent, since its own lateByMinutes was
     * deadline-relative) — only the raw, employee-facing "late by N minutes" number changes,
     * becoming consistent with what the same employee would see had they simply checked in late
     * rather than had it regularization-corrected.
     *
     * <p>{@code isNewRecord} controls whether {@code shiftId} gets snapshotted (a brand-new row —
     * see {@link AttendanceInterpretationService#interpretForKnownWorkDate}) or left exactly as it
     * already was (an existing row — its own snapshot must never be replaced, even by this
     * correction — see {@link AttendanceInterpretationService#interpretExistingRecordLateness}).
     *
     * <p>{@link InterpretationOutcome#LEGACY_UNRESOLVED} (an existing row predating the
     * {@code shiftId} column, with no recorded Shift context) is never guessed past — this throws
     * a clear, explicit failure rather than presenting a computed lateness figure that would
     * necessarily be a guess against however the employee happens to be configured today.
     */
    private void applyInterpretation(Attendance record, AttendanceInterpretation interpretation, boolean isNewRecord) {
        if (interpretation.isLegacyUnresolved()) {
            throw new IllegalStateException(
                    "Cannot recompute lateness for this record: it predates Shift-based attendance "
                            + "tracking and has no recorded Shift context. This record cannot be safely "
                            + "corrected through the normal regularization flow — contact an administrator.");
        }
        if (isNewRecord) {
            record.setShiftId(interpretation.getShiftId());
        }
        int lateByMinutes = interpretation.getLateByMinutes();
        record.setLateByMinutes(lateByMinutes);

        if (record.getCheckOutAt() == null) {
            record.setWorkedMinutes(null);
            record.setStatus(interpretation.getIsLate() ? STATUS_LATE : STATUS_PRESENT);
            return;
        }

        // workedMinutes/HALF_DAY are genuinely Shift-independent (a plain duration, and the
        // org-wide AttendanceRulesService threshold) — left exactly as this class always computed
        // them, not touched by the interpretation-service unification above.
        int workedMinutes = (int) Duration.between(record.getCheckInAt(), record.getCheckOutAt()).toMinutes();
        record.setWorkedMinutes(workedMinutes);
        record.setStatus(workedMinutes < attendanceRulesService.getHalfDayMaxHours() * 60
                ? STATUS_HALF_DAY
                : (interpretation.getIsLate() ? STATUS_LATE : STATUS_PRESENT));
    }

    /**
     * Note: {@code update()} still requires strict PENDING (edit-while-pending only) — that
     * check is inlined there directly since it's a plain state check, not an authorization one.
     *
     * {@code windowDays} counts today itself as one of the allowed days — e.g. windowDays=3
     * with today=6th allows the 6th/5th/4th and blocks the 3rd onward. Enforced server-side so
     * it can't be bypassed by calling the API directly; the calendar UI mirrors the same rule
     * (see RequestModal in AttendancePage.tsx) purely as a convenience.
     */
    private void validateLookbackWindow(LocalDate attendanceDate, int windowDays) {
        LocalDate today = regularizationBusinessToday();
        if (attendanceDate.isAfter(today)) {
            throw new IllegalArgumentException("Cannot request regularization for a future date");
        }
        LocalDate earliestAllowed = today.minusDays(Math.max(windowDays, 1) - 1);
        if (attendanceDate.isBefore(earliestAllowed)) {
            throw new IllegalArgumentException(
                    "You are not allowed to apply regularization for this date after "
                            + earliestAllowed.format(NOTIFICATION_DATE_FMT) + ".");
        }
    }

    /**
     * There's no attendance to correct for a date before the employee even joined — unlike the
     * lookback window/monthly limit above, this is a data-integrity rule, not a business policy,
     * so it applies to everyone with no Super Admin exemption. Silently allows when the employee
     * record can't be resolved (never happens for a real actor, but fails open rather than
     * blocking on an unrelated lookup issue).
     */
    private void assertNotBeforeJoiningDate(UUID employeeUserId, LocalDate attendanceDate) {
        LocalDate joiningDate = employeeRepository.findById(employeeUserId).map(Employee::getJoiningDate).orElse(null);
        if (joiningDate != null && attendanceDate.isBefore(joiningDate)) {
            throw new IllegalArgumentException(
                    "Cannot request regularization for a date before your joining date (" + joiningDate.format(NOTIFICATION_DATE_FMT) + ").");
        }
    }

    private void assertCanReview(RegularizationRequest req, User actor) {
        if (hasOverrideRole(actor)) return;
        if (actor.getId().equals(req.getAssignedApproverId())) return;

        // Fallback for requests predating assigned_approver_id (should be rare — V34 backfills
        // in-flight PENDING rows at migration time).
        boolean isManager = actor.getRoles().stream().anyMatch(r -> r.getCode().equals("MANAGER"));
        if (isManager && isCurrentManagerOf(actor.getId(), req.getEmployeeUserId())) return;

        throw new AccessDeniedException("You are not authorized to review this request");
    }

    private boolean hasOverrideRole(User actor) {
        return actor.getRoles().stream().anyMatch(r -> APPROVER_OVERRIDE_ROLES.contains(r.getCode()));
    }

    private boolean hasRole(User actor, String code) {
        return actor.getRoles().stream().anyMatch(r -> code.equals(r.getCode()));
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

    private LocalDate regularizationBusinessToday() {
        return resolveBusinessDate(LocalDateTime.now(attendanceRulesService.getDefaultZoneId()));
    }

    /**
     * Package-private and pure (no {@code now()} call inside) so the 07:00 AM boundary itself is
     * directly unit-testable without depending on the wall clock — mirrors
     * {@code PenalizationPolicyService.resolveDefaultPolicyId}'s convention of exposing just
     * enough for a direct test, nothing more.
     */
    static LocalDate resolveBusinessDate(LocalDateTime now) {
        return now.toLocalTime().isBefore(REGULARIZATION_DAY_BOUNDARY)
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
    }

    private String employeeName(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId).map(Employee::getFullName).orElse("Unknown");
    }

    /**
     * Sends one notification per DISTINCT recipient — collapses any duplicate user IDs a single
     * event might otherwise resolve to (e.g. a dual-role actor matching more than one recipient
     * path) into exactly one notification per person, and silently skips nulls (e.g. an employee
     * with no manager on file). Reuses {@link NotificationService#send} as-is — no new
     * notification model, queue, or delivery mechanism.
     */
    private void notifyRecipients(Collection<UUID> recipientIds, String type, String title, String message, String linkPath) {
        new LinkedHashSet<>(recipientIds).stream()
                .filter(Objects::nonNull)
                .forEach(id -> notificationService.send(id, type, title, message, linkPath));
    }

    private RegularizationResponse toResponse(RegularizationRequest req) {
        return toResponses(List.of(req)).get(0);
    }

    /**
     * Batch equivalent of {@link #toResponse} — every list-returning caller (listMine,
     * listPendingForApprover, listForApprover, listAll, getHistoryForManager) funnels through
     * here instead of mapping row-by-row. Previously each row cost up to 7 round trips
     * (employee, email, reviewer, assignedApprover, approvedBy, finalApprovedBy, plus one
     * findById per approval-history entry); this collects every distinct user id referenced
     * across the whole batch — including every history row's actionBy — and resolves them with
     * exactly one approval-history query and one name-lookup query total, regardless of how many
     * requests are being mapped. Output fields/values are unchanged.
     */
    private List<RegularizationResponse> toResponses(List<RegularizationRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }

        List<UUID> requestIds = requests.stream().map(RegularizationRequest::getId).toList();
        Map<UUID, List<RegularizationApproval>> historyByRequest = regularizationApprovalRepository
                .findByRequestIdInOrderByActionDateDesc(requestIds).stream()
                .collect(Collectors.groupingBy(RegularizationApproval::getRequestId));

        Set<UUID> allUserIds = new LinkedHashSet<>();
        for (RegularizationRequest req : requests) {
            allUserIds.add(req.getEmployeeUserId());
            addIfNotNull(allUserIds, req.getReviewedBy());
            addIfNotNull(allUserIds, req.getAssignedApproverId());
            addIfNotNull(allUserIds, req.getApprovedBy());
            addIfNotNull(allUserIds, req.getFinalApprovedBy());
        }
        for (List<RegularizationApproval> history : historyByRequest.values()) {
            for (RegularizationApproval a : history) {
                addIfNotNull(allUserIds, a.getActionBy());
            }
        }

        Map<UUID, String> nameById = employeeRepository.findNamesByUserIds(allUserIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
        Map<UUID, Employee> employeeById = employeeRepository.findAllByIdWithDepartment(allUserIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        Map<UUID, String> emailById = userRepository.findAllById(allUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        return requests.stream().map(req -> toResponse(req, nameById, employeeById, emailById, historyByRequest)).toList();
    }

    private static void addIfNotNull(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private RegularizationResponse toResponse(RegularizationRequest req, Map<UUID, String> nameById,
                                               Map<UUID, Employee> employeeById, Map<UUID, String> emailById,
                                               Map<UUID, List<RegularizationApproval>> historyByRequest) {
        Employee employee = employeeById.get(req.getEmployeeUserId());
        String employeeName = employee != null ? employee.getFullName() : "Unknown";
        String departmentName = employee != null && employee.getDepartment() != null
                ? employee.getDepartment().getName() : null;
        String employeeEmail = emailById.getOrDefault(req.getEmployeeUserId(), "");
        String reviewerName = req.getReviewedBy() == null ? null : nameById.get(req.getReviewedBy());
        String assignedApproverName = req.getAssignedApproverId() == null ? null
                : nameById.get(req.getAssignedApproverId());
        Long totalMinutes = (req.getRequestedCheckIn() != null && req.getRequestedCheckOut() != null)
                ? Duration.between(req.getRequestedCheckIn(), req.getRequestedCheckOut()).toMinutes()
                : null;
        List<ApprovalHistoryEntryDto> history = historyByRequest
                .getOrDefault(req.getId(), List.of()).stream()
                .map(a -> ApprovalHistoryEntryDto.builder()
                        .actionType(a.getActionType())
                        .actorName(nameById.getOrDefault(a.getActionBy(), "Unknown"))
                        .actorRole(a.getActorRole())
                        .comments(a.getComments())
                        .actionDate(a.getActionDate())
                        .build())
                .toList();
        String approvedByName = req.getApprovedBy() == null ? null : nameById.get(req.getApprovedBy());
        String finalApprovedByName = req.getFinalApprovedBy() == null ? null
                : nameById.get(req.getFinalApprovedBy());

        return RegularizationResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .requestedCheckOut(req.getRequestedCheckOut())
                .reason(req.getReason())
                .status(req.getStatus())
                .assignedApproverId(req.getAssignedApproverId())
                .assignedApproverName(assignedApproverName)
                .totalMinutes(totalMinutes)
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .approvedByName(approvedByName)
                .approvedAt(req.getApprovedAt())
                .finalApprovedByName(finalApprovedByName)
                .finalApprovedAt(req.getFinalApprovedAt())
                .createdAt(req.getCreatedAt())
                .approvalHistory(history)
                .build();
    }
}
