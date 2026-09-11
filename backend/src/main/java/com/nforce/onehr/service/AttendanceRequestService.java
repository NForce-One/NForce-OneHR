package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Work From Home / Partial Day: a forward-looking self-declaration of work mode for a given
 * date — NOT a punch correction (contrast RegularizationService) and NOT an actual check-in
 * (contrast WebClockInService). Approval here only records a decision; it never writes to
 * attendance_records, since there's no shift-end (ONEHR-108) to reconcile worked hours against.
 *
 * Approver resolution intentionally reuses RegularizationService's two-argument, "assign to a
 * chosen approver else fall back to current manager" pattern (see resolveAssignedApprover)
 * rather than WebClockInService's simpler current-manager-only version — this is the explicit
 * product requirement to reuse the regularization approver-assignment pattern for this type.
 */
@Service
@RequiredArgsConstructor
public class AttendanceRequestService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> ELIGIBLE_APPROVER_ROLES = Set.of("MANAGER", "HR_ADMIN");
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String TYPE_WFH = "WFH";
    private static final String TYPE_PARTIAL_DAY = "PARTIAL_DAY";
    private static final Set<String> PARTIAL_DAY_MODES = Set.of("LATE_ARRIVE", "INTERVENING_TIMEOFF", "LEAVING_EARLY");
    private static final Set<String> WFH_DAY_MODES = Set.of("FULL_DAY", "FIRST_HALF", "SECOND_HALF");
    private static final BigDecimal WFH_FULL_DAY = new BigDecimal("1.00");
    private static final BigDecimal WFH_HALF_DAY = new BigDecimal("0.50");

    // Partial Day's allowance per calendar month, spendable on any day(s) within that month —
    // enforced as a hard cap in submit() (a request that would push the month's total past this
    // is rejected outright), same as WFH's cap below. The employee also sees usage-vs-allowance
    // via getPartialDayBalance before submitting.
    private static final BigDecimal PARTIAL_DAY_MONTHLY_LIMIT_HOURS = new BigDecimal("2");
    private static final int PARTIAL_DAY_MONTHLY_LIMIT_MINUTES = 120;

    // WFH's monthly allowance, in days (Full Day = 1, First/Second Half = 0.5 each) — same
    // hard-limit treatment as Partial Day's cap above: a request that would push the month's
    // total past this is rejected outright.
    private static final BigDecimal WFH_MONTHLY_LIMIT_DAYS = new BigDecimal("2");

    // Minimum lead time before a WFH request's date — matches the policy text ("requires 2
    // day(s) of prior notice, containing at least 0 working day(s)") and Keka's own reference
    // behavior: a flat calendar-day minimum (the working-day sub-requirement is configured as 0,
    // so it never adds anything beyond this). Subsumes "no past dates" — today+2 is always
    // strictly in the future.
    private static final int WFH_PRIOR_NOTICE_DAYS = 2;

    private final AttendanceRequestRepository requestRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProps;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;

    @Transactional
    public AttendanceRequestResponse submit(CreateAttendanceRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        String type = normalizeType(req.getRequestType());
        BigDecimal partialDayHours = resolvePartialDayHours(type, req.getPartialDayHours());
        String partialDayMode = resolvePartialDayMode(type, req.getPartialDayMode());
        BigDecimal wfhDayFraction = resolveWfhDayFraction(type, partialDayMode);
        UUID notifyUserId = resolveNotifyUser(req.getNotifyUserId());

        if (TYPE_WFH.equals(type)) {
            LocalDate today = LocalDate.now(ZoneId.of(attendanceProps.getZone()));
            LocalDate earliestAllowed = today.plusDays(WFH_PRIOR_NOTICE_DAYS);
            if (req.getRequestDate().isBefore(earliestAllowed)) {
                throw new IllegalArgumentException("WFH request requires " + WFH_PRIOR_NOTICE_DAYS + " day(s) of prior notice.");
            }
            // One WFH request per date, full day or half day alike — unlike Partial Day (below),
            // which allows several same-day requests as long as their combined minutes stay
            // within the monthly cap. A REJECTED prior request for the same date doesn't count;
            // it never happened as far as the employee's standing requests go.
            boolean alreadyRequestedThisDate = requestRepository
                    .findByEmployeeUserIdAndRequestTypeAndRequestDate(actor.getId(), TYPE_WFH, req.getRequestDate())
                    .stream()
                    .anyMatch(r -> !STATUS_REJECTED.equals(r.getStatus()));
            if (alreadyRequestedThisDate) {
                throw new IllegalArgumentException("You already have a Work From Home request for this date.");
            }
            BigDecimal usedThisMonth = wfhDaysUsedInMonth(actor.getId(), req.getRequestDate());
            if (usedThisMonth.add(wfhDayFraction).compareTo(WFH_MONTHLY_LIMIT_DAYS) > 0) {
                throw new IllegalArgumentException(
                        "This request exceeds your remaining Work From Home balance of "
                                + WFH_MONTHLY_LIMIT_DAYS.subtract(usedThisMonth).max(BigDecimal.ZERO) + " day(s) for this month");
            }
        }
        if (TYPE_PARTIAL_DAY.equals(type)) {
            // Same date + same mode + same duration = the same slot. A PENDING or APPROVED
            // request already occupies it; a REJECTED one doesn't — the employee is free to
            // resubmit for that exact slot once it's been turned down.
            boolean slotOccupied = requestRepository
                    .findByEmployeeUserIdAndRequestTypeAndRequestDate(actor.getId(), TYPE_PARTIAL_DAY, req.getRequestDate())
                    .stream()
                    .anyMatch(r -> !STATUS_REJECTED.equals(r.getStatus())
                            && partialDayMode.equals(r.getPartialDayMode())
                            && partialDayHours.compareTo(r.getPartialDayHours()) == 0);
            if (slotOccupied) {
                throw new IllegalArgumentException(
                        "You already have a pending or approved Partial Day request for this date, mode, and duration.");
            }

            BigDecimal usedThisMonth = partialDayHoursUsedInMonth(actor.getId(), req.getRequestDate());
            if (usedThisMonth.add(partialDayHours).compareTo(PARTIAL_DAY_MONTHLY_LIMIT_HOURS) > 0) {
                // "You have used your 120 minutes" only holds when the allowance is actually
                // already exhausted — a fresh 0-used request for 200 minutes isn't "used up",
                // it's just larger than the cap allows in one request.
                boolean allowanceExhausted = usedThisMonth.compareTo(PARTIAL_DAY_MONTHLY_LIMIT_HOURS) >= 0;
                throw new IllegalArgumentException(allowanceExhausted
                        ? "You have used your " + PARTIAL_DAY_MONTHLY_LIMIT_MINUTES + " minutes. You are not allowed to raise a request for more than "
                                + PARTIAL_DAY_MONTHLY_LIMIT_MINUTES + " minutes."
                        : "You are not allowed to raise a request for more than " + PARTIAL_DAY_MONTHLY_LIMIT_MINUTES + " minutes.");
            }
        }

        AttendanceRequest entity = AttendanceRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()))
                .requestType(type)
                .requestDate(req.getRequestDate())
                .partialDayHours(partialDayHours)
                .partialDayMode(partialDayMode)
                .wfhDayFraction(wfhDayFraction)
                .notifyUserId(notifyUserId)
                .reason(req.getReason().trim())
                .status(STATUS_PENDING)
                .build();
        entity = requestRepository.save(entity);

        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_SUBMITTED", entity.getId());

        Employee requester = employeeRepository.findById(actor.getId()).orElse(null);
        String requesterName = requester != null ? requester.getFullName() : "A colleague";
        String typeLabel = TYPE_WFH.equals(type) ? "Work From Home" : "Partial Day";

        // Action-needed notification to whoever can actually approve this: the resolved approver
        // (reporting manager, or a manually-picked eligible approver) plus every active HR Admin
        // — mirrors RegularizationService.submit's notifyRecipients pattern. Deliberately
        // HR_ADMIN only, not findAdminUserIds — Super Admin already has blanket queue visibility
        // (see listPendingForApprover) so isn't separately paged on every submission.
        Set<UUID> approvalRecipients = new LinkedHashSet<>();
        if (entity.getAssignedApproverId() != null) approvalRecipients.add(entity.getAssignedApproverId());
        approvalRecipients.addAll(userRepository.findActiveHrAdminUserIds());
        for (UUID recipientId : approvalRecipients) {
            notificationService.send(recipientId, "ATTENDANCE",
                    "Attendance Request Submitted",
                    requesterName + " has submitted a " + typeLabel + " request for " + req.getRequestDate() + ".",
                    "/approvals?type=" + type);
        }

        // Separate, purely informational "FYI" to whichever colleague the employee optionally
        // chose to notify — distinct from the approval-recipients block above, and independent
        // of whether that colleague has any role in reviewing the request.
        if (notifyUserId != null) {
            notificationService.send(notifyUserId, "ATTENDANCE",
                    "Attendance request submitted",
                    requesterName + " submitted a " + typeLabel
                            + " request for " + req.getRequestDate() + " and wanted you to know.",
                    "/attendance");
        }
        return toResponse(entity);
    }

    private String normalizeType(String requestType) {
        String type = requestType == null ? "" : requestType.trim().toUpperCase();
        if (!TYPE_WFH.equals(type) && !TYPE_PARTIAL_DAY.equals(type)) {
            throw new IllegalArgumentException("requestType must be WFH or PARTIAL_DAY");
        }
        return type;
    }

    /**
     * PARTIAL_DAY: LATE_ARRIVE | INTERVENING_TIMEOFF | LEAVING_EARLY, required. WFH: FULL_DAY |
     * FIRST_HALF | SECOND_HALF, defaulting to FULL_DAY when omitted (a plain single/multi-day
     * request with no half-day split).
     */
    private String resolvePartialDayMode(String type, String partialDayMode) {
        String mode = partialDayMode == null ? "" : partialDayMode.trim().toUpperCase();
        if (TYPE_WFH.equals(type)) {
            if (mode.isEmpty()) return "FULL_DAY";
            if (!WFH_DAY_MODES.contains(mode)) {
                throw new IllegalArgumentException("partialDayMode must be one of " + WFH_DAY_MODES + " for WFH");
            }
            return mode;
        }
        if (!PARTIAL_DAY_MODES.contains(mode)) {
            throw new IllegalArgumentException("partialDayMode must be one of " + PARTIAL_DAY_MODES);
        }
        return mode;
    }

    /** FULL_DAY -> 1.00, FIRST_HALF/SECOND_HALF -> 0.50 — null for PARTIAL_DAY (uses partialDayHours instead). */
    private BigDecimal resolveWfhDayFraction(String type, String wfhMode) {
        if (!TYPE_WFH.equals(type)) return null;
        return "FULL_DAY".equals(wfhMode) ? WFH_FULL_DAY : WFH_HALF_DAY;
    }

    private UUID resolveNotifyUser(UUID notifyUserId) {
        if (notifyUserId == null) return null;
        if (!userRepository.existsById(notifyUserId)) {
            throw new IllegalArgumentException("Selected employee to notify was not found");
        }
        return notifyUserId;
    }

    /** For the "View Available Balance" line: hours already committed this month vs. the cap. */
    @Transactional(readOnly = true)
    public PartialDayBalance getPartialDayBalance(String actorEmail, LocalDate forDate) {
        User actor = requireActor(actorEmail);
        BigDecimal used = partialDayHoursUsedInMonth(actor.getId(), forDate);
        return new PartialDayBalance(used, PARTIAL_DAY_MONTHLY_LIMIT_HOURS, PARTIAL_DAY_MONTHLY_LIMIT_HOURS.subtract(used).max(BigDecimal.ZERO));
    }

    public record PartialDayBalance(BigDecimal usedHours, BigDecimal limitHours, BigDecimal remainingHours) {}

    /** WFH's remaining-balance line — days used this month vs. the enforced monthly cap. */
    @Transactional(readOnly = true)
    public WfhBalance getWfhBalance(String actorEmail, LocalDate forDate) {
        User actor = requireActor(actorEmail);
        BigDecimal used = wfhDaysUsedInMonth(actor.getId(), forDate);
        return new WfhBalance(used, WFH_MONTHLY_LIMIT_DAYS, WFH_MONTHLY_LIMIT_DAYS.subtract(used).max(BigDecimal.ZERO));
    }

    public record WfhBalance(BigDecimal usedDays, BigDecimal limitDays, BigDecimal remainingDays) {}

    /**
     * Sum of wfhDayFraction across every non-rejected WFH request the employee has for the
     * calendar month requestDate falls in — PENDING counts too, same reasoning as
     * partialDayHoursUsedInMonth (and this one backs a hard cap, so it must never undercount).
     */
    private BigDecimal wfhDaysUsedInMonth(UUID employeeUserId, LocalDate requestDate) {
        LocalDate monthStart = requestDate.withDayOfMonth(1);
        LocalDate monthEnd = requestDate.withDayOfMonth(requestDate.lengthOfMonth());
        return requestRepository
                .findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(employeeUserId, TYPE_WFH, monthStart, monthEnd)
                .stream()
                .filter(r -> !STATUS_REJECTED.equals(r.getStatus()))
                .map(AttendanceRequest::getWfhDayFraction)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Validates the structural shape only (must be present and positive) — the monthly cap
     * (PARTIAL_DAY_MONTHLY_LIMIT_HOURS) is a separate hard check in submit(), since it needs the
     * employee's other requests this month to evaluate, not just this one field.
     */
    private BigDecimal resolvePartialDayHours(String type, BigDecimal partialDayHours) {
        if (TYPE_WFH.equals(type)) {
            return null;
        }
        if (partialDayHours == null || partialDayHours.signum() <= 0) {
            throw new IllegalArgumentException("Partial day hours must be greater than zero");
        }
        return partialDayHours;
    }

    /**
     * Sum of partialDayHours across every non-rejected Partial Day request the employee has for
     * the calendar month requestDate falls in — PENDING requests count too (not just APPROVED),
     * since otherwise several simultaneously-pending requests could individually pass this check
     * and later all be approved past the monthly cap.
     */
    private BigDecimal partialDayHoursUsedInMonth(UUID employeeUserId, LocalDate requestDate) {
        LocalDate monthStart = requestDate.withDayOfMonth(1);
        LocalDate monthEnd = requestDate.withDayOfMonth(requestDate.lengthOfMonth());
        return requestRepository
                .findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(employeeUserId, TYPE_PARTIAL_DAY, monthStart, monthEnd)
                .stream()
                .filter(r -> !STATUS_REJECTED.equals(r.getStatus()))
                .map(AttendanceRequest::getPartialDayHours)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return toResponseList(requestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId()));
    }

    /** Manager sees only requests assigned to them; HR/Super Admin see all pending requests. */
    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<AttendanceRequest> pending = requestRepository.findByStatus(STATUS_PENDING);

        if (hasOverrideRole(actor)) {
            return toResponseList(pending);
        }
        return toResponseList(pending.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .toList());
    }

    @Transactional
    public AttendanceRequestResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        AttendanceRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_APPROVED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_APPROVED", req.getEmployeeUserId());
        notificationService.send(req.getEmployeeUserId(), "ATTENDANCE_REQUEST_APPROVED",
                "Attendance Request Approved",
                "Your " + (TYPE_WFH.equals(req.getRequestType()) ? "Work From Home" : "Partial Day")
                        + " request for " + req.getRequestDate() + " has been approved by " + employeeName(actor.getId()) + ".",
                "/my-requests?type=" + req.getRequestType());
        return toResponse(req);
    }

    @Transactional
    public AttendanceRequestResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        AttendanceRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_REJECTED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        String after = auditSnapshot.toJson(Map.of("status", STATUS_REJECTED, "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_REJECTED", req.getEmployeeUserId(), null, after);
        notificationService.send(req.getEmployeeUserId(), "ATTENDANCE_REQUEST_REJECTED",
                "Attendance Request Rejected",
                "Your " + (TYPE_WFH.equals(req.getRequestType()) ? "Work From Home" : "Partial Day")
                        + " request for " + req.getRequestDate() + " has been rejected by " + employeeName(actor.getId())
                        + (comment != null && !comment.isBlank() ? ". Reason: " + comment.trim() : "."),
                "/my-requests?type=" + req.getRequestType());
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    /** Selected approver (validated as an eligible MANAGER/HR_ADMIN) else the employee's current manager. */
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

    private AttendanceRequest requirePending(UUID requestId) {
        AttendanceRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!STATUS_PENDING.equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void assertCanReview(AttendanceRequest req, User actor) {
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

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName).orElse("Unknown");
    }

    private AttendanceRequestResponse toResponse(AttendanceRequest req) {
        return toResponseList(List.of(req)).get(0);
    }

    /**
     * Batched equivalent of the old per-row toResponse: collects every distinct user id referenced
     * anywhere in the list (employee, reviewer, assigned approver, notify user) up front, then
     * resolves names/department/email in a handful of bulk queries instead of ~5 findById calls
     * per row. Output fields/values are unchanged.
     */
    private List<AttendanceRequestResponse> toResponseList(List<AttendanceRequest> requests) {
        if (requests.isEmpty()) return List.of();

        Set<UUID> employeeIds = new LinkedHashSet<>();
        Set<UUID> nameIds = new LinkedHashSet<>();
        for (AttendanceRequest r : requests) {
            employeeIds.add(r.getEmployeeUserId());
            nameIds.add(r.getEmployeeUserId());
            if (r.getReviewedBy() != null) nameIds.add(r.getReviewedBy());
            if (r.getAssignedApproverId() != null) nameIds.add(r.getAssignedApproverId());
            if (r.getNotifyUserId() != null) nameIds.add(r.getNotifyUserId());
        }

        Map<UUID, String> nameById = employeeRepository.findNamesByUserIds(nameIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
        Map<UUID, Employee> employeeById = employeeRepository.findAllByIdWithDepartment(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        Map<UUID, String> emailById = userRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        return requests.stream().map(req -> {
            String employeeName = nameById.getOrDefault(req.getEmployeeUserId(), "Unknown");
            Employee employee = employeeById.get(req.getEmployeeUserId());
            String departmentName = employee != null && employee.getDepartment() != null
                    ? employee.getDepartment().getName() : null;
            String employeeEmail = emailById.getOrDefault(req.getEmployeeUserId(), "");
            String reviewerName = req.getReviewedBy() == null ? null : nameById.get(req.getReviewedBy());
            String assignedApproverName = req.getAssignedApproverId() == null ? null
                    : nameById.get(req.getAssignedApproverId());
            String notifyUserName = req.getNotifyUserId() == null ? null : nameById.get(req.getNotifyUserId());

            return AttendanceRequestResponse.builder()
                    .id(req.getId())
                    .employeeUserId(req.getEmployeeUserId())
                    .employeeName(employeeName)
                    .employeeEmail(employeeEmail)
                    .departmentName(departmentName)
                    .requestType(req.getRequestType())
                    .requestDate(req.getRequestDate())
                    .partialDayHours(req.getPartialDayHours())
                    .partialDayMode(req.getPartialDayMode())
                    .wfhDayFraction(req.getWfhDayFraction())
                    .reason(req.getReason())
                    .status(req.getStatus())
                    .assignedApproverId(req.getAssignedApproverId())
                    .assignedApproverName(assignedApproverName)
                    .notifyUserId(req.getNotifyUserId())
                    .notifyUserName(notifyUserName)
                    .reviewedByName(reviewerName)
                    .reviewedAt(req.getReviewedAt())
                    .reviewComment(req.getReviewComment())
                    .createdAt(req.getCreatedAt())
                    .build();
        }).toList();
    }
}
