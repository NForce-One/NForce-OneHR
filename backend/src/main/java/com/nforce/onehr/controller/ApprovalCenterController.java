package com.nforce.onehr.controller;

import com.nforce.onehr.dto.ApprovalItemDto;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.helpcontent.ApprovalAttemptDto;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AssetService;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.ExpenseService;
import com.nforce.onehr.service.HelpContentService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.OvertimeRequestService;
import com.nforce.onehr.service.RegularizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified Approval Center queue.
 *
 * HARD RULE: This is the ONLY endpoint family that issues approval/rejection
 * decisions. No other controller/endpoint may approve or reject any request type.
 * The individual service-level approve/reject endpoints (leave, expenses, assets,
 * regularization) do the actual work; this controller aggregates the pending
 * queue into a single unified view and delegates decisions to the appropriate
 * service.
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalCenterController {

    private final LeaveService leaveService;
    private final RegularizationService regularizationService;
    private final ExpenseService expenseService;
    private final AssetService assetService;
    private final AttendanceRequestService attendanceRequestService;
    private final OvertimeRequestService overtimeRequestService;
    private final HelpContentService helpContentService;
    private final UserRepository userRepo;

    /**
     * Returns all pending approval items visible to the caller.
     * - Manager: LEAVE (own reports), REGULARIZATION (own reports), EXPENSE at MANAGER stage (own reports), ASSET_REQUEST (own reports)
     * - HR Admin / Super Admin: LEAVE (all), REGULARIZATION (all), EXPENSE at either stage (all —
     *   see ExpenseService.pendingForFinalApprover for why this includes MANAGER-stage claims
     *   too), ASSET_REQUEST (all)
     */
    @GetMapping
    public List<ApprovalItemDto> pendingApprovals(Principal principal) {
        String email = principal.getName();
        User actor = userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        boolean isAdmin = roleCodes.contains("HR_ADMIN") || roleCodes.contains("SUPER_ADMIN");
        boolean isManager = roleCodes.contains("MANAGER");

        List<ApprovalItemDto> items = new ArrayList<>();

        if (isManager) {
            // Leave
            leaveService.listPendingApprovals(email).stream()
                    .map(this::leaveToApprovalItem).forEach(items::add);
            // Regularization — managers see own reports' pending requests
            regularizationService.listPendingForApprover(email).stream()
                    .map(this::regularizationToApprovalItem).forEach(items::add);
            // Expense — manager stage only
            expenseService.pendingForManager(email).stream()
                    .map(c -> expenseToApprovalItem(c, "MANAGER")).forEach(items::add);
            // Asset requests
            assetService.listPendingForApprover(email).stream()
                    .map(this::assetRequestToApprovalItem).forEach(items::add);
            // WFH / Partial Day — managers see own reports' pending requests
            attendanceRequestService.listPendingForApprover(email).stream()
                    .map(this::attendanceRequestToApprovalItem).forEach(items::add);
            // Overtime — managers see own reports' pending requests
            overtimeRequestService.listPendingForApprover(email).stream()
                    .map(this::overtimeToApprovalItem).forEach(items::add);
            // FAQs & Guides — manager sees only attempts resolved to them
            helpContentService.listPendingApprovalsForApprover(email).stream()
                    .map(this::helpContentToApprovalItem).forEach(items::add);
        }

        if (isAdmin) {
            // Leave — HR/SA see all pending, regardless of direct-report relationship
            leaveService.listPendingApprovals(email).stream()
                    .map(this::leaveToApprovalItem).forEach(items::add);
            // Regularization — HR/SA see all pending
            regularizationService.listPendingForApprover(email).stream()
                    .map(this::regularizationToApprovalItem).forEach(items::add);
            // Expense — HR/SA see claims at both stages now (SUBMITTED and MANAGER_APPROVED), so
            // tag each item with its ACTUAL stage rather than hardcoding "FINAL" — the frontend
            // routes the approve/reject click through managerApprove/managerReject vs
            // finalApprove/finalReject based on this field (ApprovalsPage.tsx approveItem/
            // rejectItem), and a still-SUBMITTED claim must go through the manager-stage endpoint
            // (which already allows HR/SA to act on it — see requireCurrentManagerOf) since
            // finalApprove/finalReject reject anything not already MANAGER_APPROVED.
            expenseService.pendingForFinalApprover(email).stream()
                    .map(c -> expenseToApprovalItem(c, "SUBMITTED".equals(c.getStatus()) ? "MANAGER" : "FINAL"))
                    .forEach(items::add);
            // Asset requests — HR/SA approve PENDING only (APPROVED → fulfilled via HR Assets page)
            assetService.listPendingForApprover(email).stream()
                    .filter(r -> "PENDING".equals(r.getStatus()))
                    .map(this::assetRequestToApprovalItem).forEach(items::add);
            // WFH / Partial Day — HR/SA see all pending
            attendanceRequestService.listPendingForApprover(email).stream()
                    .map(this::attendanceRequestToApprovalItem).forEach(items::add);
            // Overtime — HR/SA see all pending
            overtimeRequestService.listPendingForApprover(email).stream()
                    .map(this::overtimeToApprovalItem).forEach(items::add);
            // FAQs & Guides — Super Admin has blanket fallback-authority visibility, same
            // convention as every other request type's admin branch here.
            helpContentService.listPendingApprovalsForApprover(email).stream()
                    .map(this::helpContentToApprovalItem).forEach(items::add);
        }

        // De-duplicate by (id + requestType) in case manager and admin roles overlap
        Map<String, ApprovalItemDto> seen = new LinkedHashMap<>();
        for (ApprovalItemDto item : items) {
            String key = item.getRequestType() + ":" + item.getId();
            seen.putIfAbsent(key, item);
        }
        return new ArrayList<>(seen.values());
    }

    // ── Mappers ───────────────────────────────────────────

    private ApprovalItemDto leaveToApprovalItem(LeaveRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("LEAVE")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .leaveTypeName(r.getLeaveTypeName())
                .leaveStartDate(r.getStartDate())
                .leaveEndDate(r.getEndDate())
                .leaveTotalDays(r.getTotalDays())
                .leaveHalfDay(r.isHalfDay())
                .leaveReason(r.getEmployeeReason())
                .build();
    }

    private ApprovalItemDto regularizationToApprovalItem(RegularizationResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("REGULARIZATION")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getAttendanceDate())
                .requestedCheckIn(r.getRequestedCheckIn())
                .requestedCheckOut(r.getRequestedCheckOut())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto expenseToApprovalItem(ExpenseClaimResponse c, String stage) {
        return ApprovalItemDto.builder()
                .id(c.getId().toString())
                .requestType("EXPENSE")
                .employeeUserId(c.getEmployeeUserId())
                .employeeName(c.getEmployeeName())
                .createdAt(c.getCreatedAt())
                .expenseCategoryName(c.getCategoryName())
                .expenseAmount(c.getAmount())
                .expenseDate(c.getExpenseDate())
                .businessPurpose(c.getBusinessPurpose())
                .receiptUrl(c.getReceiptUrl())
                .approvalStage(stage)
                .build();
    }

    private ApprovalItemDto assetRequestToApprovalItem(AssetRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("ASSET_REQUEST")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt())
                .requestedCategoryName(r.getCategoryName())
                .assetRequestReason(r.getReason())
                .assetRequestStatus(r.getStatus())
                .build();
    }

    private ApprovalItemDto attendanceRequestToApprovalItem(AttendanceRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType(r.getRequestType()) // "WFH" or "PARTIAL_DAY"
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getRequestDate())
                .partialDayHours(r.getPartialDayHours())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto overtimeToApprovalItem(OvertimeRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("OVERTIME")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getWorkDate())
                .requestedCheckIn(r.getRequestedStart())
                .requestedCheckOut(r.getRequestedEnd())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto helpContentToApprovalItem(ApprovalAttemptDto a) {
        return ApprovalItemDto.builder()
                .id(a.getId().toString())
                .requestType("HELP_CONTENT")
                .employeeUserId(a.getSubmittedByUserId())
                .employeeName(a.getSubmittedByName())
                .createdAt(a.getSubmittedAt())
                .helpContentId(a.getContentId().toString())
                .helpContentType(a.getContentType())
                .helpContentTitle(a.getSnapshotTitle())
                .helpContentDescription(a.getSnapshotDescription())
                .helpContentBody(a.getSnapshotBody())
                .helpContentCategory(a.getSnapshotCategory())
                .helpContentAttemptNumber(a.getAttemptNumber())
                .helpContentModifiedSincePrevious(a.isModifiedSincePrevious())
                .build();
    }
}
