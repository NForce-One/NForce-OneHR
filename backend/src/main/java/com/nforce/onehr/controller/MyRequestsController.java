package com.nforce.onehr.controller;

import com.nforce.onehr.dto.MyRequestItemDto;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.OvertimeRequestService;
import com.nforce.onehr.service.RegularizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Requester-side mirror of the Unified Approval Center: aggregates the caller's
 * own Leave and Attendance Regularization submissions into a
 * single read-only tracking list. Issues no decisions — approve/reject for
 * every request type happens exclusively via Approval Center. Web Clock-In is
 * NOT a "request" here — it needs no approval and has no decision to track (see
 * WebClockInService's own class Javadoc); the employee sees it directly in the
 * Attendance page's punch history instead.
 */
@RestController
@RequestMapping("/api/my-requests")
@RequiredArgsConstructor
public class MyRequestsController {

    private final LeaveService leaveService;
    private final RegularizationService regularizationService;
    private final AttendanceRequestService attendanceRequestService;
    private final OvertimeRequestService overtimeRequestService;

    @GetMapping
    public List<MyRequestItemDto> myRequests(Principal principal) {
        String email = principal.getName();

        List<MyRequestItemDto> items = new ArrayList<>();
        leaveService.listMyRequests(email).stream().map(this::leaveToItem).forEach(items::add);
        regularizationService.listMine(email).stream().map(this::regularizationToItem).forEach(items::add);
        attendanceRequestService.listMine(email).stream().map(this::attendanceRequestToItem).forEach(items::add);
        overtimeRequestService.listMine(email).stream().map(this::overtimeToItem).forEach(items::add);

        items.sort(Comparator.comparing(MyRequestItemDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    // ── Mappers ───────────────────────────────────────────

    private MyRequestItemDto leaveToItem(LeaveRequestResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("LEAVE")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getDecisionReason())
                .decidedByName(r.getDecidedByName())
                .decidedAt(r.getDecidedAt() != null
                        ? r.getDecidedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .leaveTypeName(r.getLeaveTypeName())
                .leaveStartDate(r.getStartDate())
                .leaveEndDate(r.getEndDate())
                .leaveTotalDays(r.getTotalDays())
                .leaveHalfDay(r.isHalfDay())
                .leaveReason(r.getEmployeeReason())
                .build();
    }

    private MyRequestItemDto regularizationToItem(RegularizationResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("REGULARIZATION")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getReviewComment())
                .decidedByName(r.getReviewedByName())
                .decidedAt(r.getReviewedAt() != null
                        ? r.getReviewedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getAttendanceDate())
                .requestedCheckIn(r.getRequestedCheckIn())
                .requestedCheckOut(r.getRequestedCheckOut())
                .regularizationReason(r.getReason())
                .build();
    }

    private MyRequestItemDto attendanceRequestToItem(AttendanceRequestResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType(r.getRequestType()) // "WFH" or "PARTIAL_DAY"
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getReviewComment())
                .decidedByName(r.getReviewedByName())
                .decidedAt(r.getReviewedAt() != null
                        ? r.getReviewedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getRequestDate())
                .partialDayHours(r.getPartialDayHours())
                .regularizationReason(r.getReason())
                .build();
    }

    private MyRequestItemDto overtimeToItem(OvertimeRequestResponse r) {
        return MyRequestItemDto.builder()
                .id(r.getId().toString())
                .requestType("OVERTIME")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .status(r.getStatus())
                .decisionReason(r.getReviewComment())
                .decidedByName(r.getReviewedByName())
                .decidedAt(r.getReviewedAt() != null
                        ? r.getReviewedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getWorkDate())
                .requestedCheckIn(r.getRequestedStart())
                .requestedCheckOut(r.getRequestedEnd())
                .regularizationReason(r.getReason())
                .build();
    }
}
